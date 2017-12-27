package net.wimpi.modbus.io;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import android.os.Handler;
import android.os.Message;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.msg.ModbusMessage;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.net.RTUBLEMasterConnection;
import net.wimpi.modbus.util.ModbusUtil;

/**
 * 在这里将 BLE基于回调的形式 改装成 同步模式
 * @author lupc
 *
 */
public class ModbusRTUBLETransport implements ModbusTransport {

	private byte[] mBuffer = new byte[256];

	private int mIndex = 0;

	/**
	 * 写操作的 实际执行结果
	 */
	private boolean mWriteResult;

	private BytesOutputStream outputBuffer;

	private BytesInputStream inputBuffer;

	private Handler mHandler;	

	private final Object mWriteMutex = new Object();

	private final Object mReadMutex = new Object();
	private boolean isRecieved = false;

	private static final int TIMEOUT_READ = 3000;

	public ModbusRTUBLETransport(final Handler handler){
		mHandler = handler;

		mWriteResult = false;

		outputBuffer = new BytesOutputStream(1024);
		inputBuffer = new BytesInputStream(1024);
	}

	@Override
	public void close() throws IOException {

	}

	@Override
	public void writeMessage(ModbusMessage msg) throws ModbusIOException {
		try {

			outputBuffer.reset();
			inputBuffer.reset();
			java.util.Arrays.fill(mBuffer, (byte)0);
			mIndex = 0;

			msg.setHeadless();

			msg.writeTo(outputBuffer);

			int[] crc = ModbusUtil.calculateCRC(outputBuffer.getBuffer(), 0, outputBuffer.size());

			outputBuffer.writeByte(crc[0]);
			outputBuffer.writeByte(crc[1]);

			//获取字节数组
			int len = outputBuffer.size();
			byte[] t = new byte[len];
			System.arraycopy(outputBuffer.getBuffer(), 0, t, 0, len);

			//将数据写到 蓝牙特性中
			isRecieved = false;	

			//BLE写数据确认
			synchronized (mWriteMutex) {
				//发送前置 标志位
				mWriteResult = false;
				//向主线程 发送消息，通知其 发送数据			
				writeData(t);
				//执行完毕后，等待结果，最多等待3秒
				mWriteMutex.wait(3000);					
			}
			//到这里对结果进行判定，如果规定时间内，执行者没有将标志位置为true，则认定写失败
			if(!mWriteResult)
				throw new TimeoutException("write time out");

		} catch (Exception e) {
			throw new ModbusIOException("I/O failed to write");
		}
	}

	@Override
	public ModbusRequest readRequest() throws ModbusIOException {
		throw new RuntimeException("Operation not supported.");
	}

	@Override
	public ModbusResponse readResponse() throws ModbusIOException {
		try {
			ModbusResponse response = null;

			synchronized (mReadMutex) {
				if(!isRecieved)
					mReadMutex.wait(TIMEOUT_READ);
			}

			if(!isRecieved)
				throw new ModbusIOException("BLE读取数据超时"); 

			inputBuffer.reset(mBuffer);

			//读取前2个字节
			byte[] buffer = new byte[256];
			if(inputBuffer.read(buffer, 0, 2) == -1){
				throw new ModbusIOException("Premature end of stream (Header truncated).");
			}

			//先解读出 功能码，根据功能码来计算后续长度
			int functionCode = buffer[1] & 0xff;

			//获取数据总长度 并读取剩余数据
			int length = getResponse(functionCode,buffer);

			//计算CRC
			int crc[] = ModbusUtil.calculateCRC(buffer, 0, length - 2);

			//校验CRC
			if (ModbusUtil.unsignedByteToInt(buffer[length - 2]) != crc[0]
					|| ModbusUtil.unsignedByteToInt(buffer[length - 1]) != crc[1])
			{
				throw new IOException("CRC Error in received frame: " + length + " bytes: "
						+ ModbusUtil.toHex(buffer, 0, length));
			}

			//创建回复
			response = ModbusResponse.createModbusResponse(functionCode);
			response.setHeadless();
			inputBuffer.reset(buffer, length);
			response.readFrom(inputBuffer);	

			return response;
		} catch (Exception e) {
			throw new ModbusIOException("I/O exception - failed to read.\n" + e);
		}		
	}

	private int getResponse(int fn,byte[] buffer) 
			throws IOException{
		int length = 0;

		switch (fn)
		{
		case 0x01:
		case 0x02:
		case 0x03:
		case 0x04:
		case 0x0C:
		case 0x11: // report slave ID version and run/stop state
		case 0x14: // read log entry (60000 memory reference)
		case 0x15: // write log entry (60000 memory reference)
		case 0x17:
		{
			inputBuffer.read(buffer, 2, 1);
			int dataLength = buffer[2] & 0xff;
			//读取剩余数据(包括CRC)
			inputBuffer.read(buffer, 3, dataLength + 2);
			//计算数据总长度
			length = dataLength + 5; // UID+FC+CRC(2bytes)
			break;
		}
		case 0x05:
		case 0x06:
		case 0x0B:
		case 0x0F:
		case 0x10:
		{
			//读取剩余字节
			inputBuffer.read(buffer, 2, 6);
			//计算数据总长度
			length = 8;
			break;
		}
		case 0x83:
		{
			//读取剩余字节
			inputBuffer.read(buffer, 2, 3);
			// error code
			length = 5;
			break;
		}
		}

		return length;
	}

	public void onRecieve(byte[] data){
		//将数据写入 缓冲区
		System.arraycopy(data, 0, mBuffer, mIndex, data.length);
		mIndex += data.length;

		if(mIndex > 2) {
			int fc = mBuffer[1] & 0xff;
			int ll = mBuffer[2] & 0xff;
			int len = calcLength(fc,ll);
			if(mIndex >= len){
				synchronized (mReadMutex) {
					mReadMutex.notify();
					isRecieved = true;
				}
			}
		}
	}

	public void onWrite(boolean result){
		synchronized (mWriteMutex) {
			mWriteResult = result;
			mWriteMutex.notify();
		}		
	}

	//发送指令
	private void writeData(byte[] data) {		
		Message msg = mHandler.obtainMessage(RTUBLEMasterConnection.MSG_WRITE);
		msg.obj = data;
		mHandler.sendMessage(msg);
	}

	private int calcLength(int fn,int ll) {
		int length = 0;

		switch (fn)
		{
		case 0x01:
		case 0x02:
		case 0x03:
		case 0x04:
		case 0x0C:
		case 0x11: // report slave ID version and run/stop state
		case 0x14: // read log entry (60000 memory reference)
		case 0x15: // write log entry (60000 memory reference)
		case 0x17:
			//计算数据总长度
			length = 3 + ll + 2; // UID+FC+CRC(2bytes)
			break;
		case 0x05:
		case 0x06:
		case 0x0B:
		case 0x0F:
		case 0x10:
			length = 8;
			break;
		case 0x83:
			length = 5;
			break;
		}
		return length;
	}

}
