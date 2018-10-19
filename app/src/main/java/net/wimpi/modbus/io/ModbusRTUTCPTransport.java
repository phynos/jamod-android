package net.wimpi.modbus.io;

import android.util.Log;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.msg.ModbusMessage;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.util.ModbusUtil;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * @author bonino
 * 修正优化：lupc
 * 
 */
public class ModbusRTUTCPTransport implements ModbusTransport
{

	public static final String TAG = ModbusRTUTCPTransport.class.getSimpleName();

	public static final String logId = "[ModbusRTUTCPTransport]: ";

	/**
	 * 输入流
	 */
	private InputStream mInputStream;

	/**
	 * 输出流
	 */
	private DataOutputStream mOutputStream;

	// The Bytes output stream to use as output buffer for Modbus frames
	private BytesOutputStream outputBuffer;

	// The BytesInputStream wrapper for the transport input stream
	private BytesInputStream inputBuffer;

	// the socket used by this transport
	private Socket mSocket;

	private byte[] mSendBuffer = new byte[100];

	/**
	 * @param socket
	 * @throws IOException
	 * 
	 */
	public ModbusRTUTCPTransport(Socket socket) throws IOException
	{
		// prepare the input and output streams...
		if (socket != null)
			this.setSocket(socket);
	}

	/**
	 * Stores the given {@link Socket} instance and prepares the related streams
	 * to use them for Modbus RTU over TCP communication.
	 * 
	 * @param socket
	 * @throws IOException
	 */
	public void setSocket(Socket socket) throws IOException
	{
		if (mSocket != null)
		{
			// TODO: handle clean closure of the streams
			this.outputBuffer.close();
			this.inputBuffer.close();
			mInputStream.close();
			mOutputStream.close();
		}

		// store the socket used by this transport
		mSocket = socket;
		mSocket.setSoTimeout(3000);

		// get the input and output streams		
		mInputStream = socket.getInputStream();
		mOutputStream = new DataOutputStream(mSocket.getOutputStream());

		// prepare the buffers
		this.outputBuffer = new BytesOutputStream(Modbus.MAX_MESSAGE_LENGTH);
		this.inputBuffer = new BytesInputStream(Modbus.MAX_MESSAGE_LENGTH);
	}

	/**
	 * writes the given ModbusMessage over the physical transport handled by
	 * this object.
	 * 
	 * @param msg
	 *            the {@link ModbusMessage} to be written on the transport.
	 */
	@Override
	public synchronized void writeMessage(ModbusMessage msg) throws ModbusIOException
	{
		try
		{
			// atomic access to the output buffer
			synchronized (this.outputBuffer)
			{
				//写数据之前，先清理 接收缓冲区
				//cleanInputStream();

				// reset the output buffer
				this.outputBuffer.reset();

				// prepare the message for "virtual" serial transport
				msg.setHeadless();

				// write the message to the output buffer
				msg.writeTo(this.outputBuffer);

				// compute the CRC
				int[] crc = ModbusUtil.calculateCRC(this.outputBuffer.getBuffer(), 0, this.outputBuffer.size());

				// write the CRC on the output buffer
				this.outputBuffer.writeByte(crc[0]);
				this.outputBuffer.writeByte(crc[1]);

				// store the buffer length
				int bufferLength = this.outputBuffer.size();

				// store the raw output buffer reference
				byte rawBuffer[] = this.outputBuffer.getBuffer();
				System.out.println("modubs实际数据: " + ModbusUtil.toHex(rawBuffer, 0, bufferLength));

				mOutputStream.write(rawBuffer, 0, bufferLength);
				mOutputStream.flush();

				// sleep for the time needed to receive the request at the other
				// point of the connection
				Thread.sleep(bufferLength);
			}

		}
		catch (Exception ex)
		{
			Log.e(TAG,ex.getMessage(),ex);
			throw new ModbusIOException("I/O failed to write");
		}

	}// writeMessage

	// This is required for the slave that is not supported
	@Override
	public synchronized ModbusRequest readRequest() throws ModbusIOException
	{
		throw new RuntimeException("Operation not supported.");
	}

	@Override
	public ModbusResponse readResponse() 
			throws ModbusIOException {
		try
		{
			ModbusResponse response = null;

			// atomic access to the input buffer
			synchronized (inputBuffer)
			{
				// clean the input buffer
				inputBuffer.reset(new byte[Modbus.MAX_MESSAGE_LENGTH]);

				byte[] buffer = inputBuffer.getBuffer();

				//读取前2个字节
				if(mInputStream.read(buffer, 0, 2) == -1){
					throw new ModbusIOException("Premature end of stream (Header truncated).");
				}

				int uid = inputBuffer.readUnsignedByte();

				//功能码
				int functionCode = inputBuffer.readUnsignedByte();

				//获取数据总长度 并读取剩余数据
				int length = getResponse(functionCode);

				//计算CRC				
				int crc[] = ModbusUtil.calculateCRC(buffer, 0, length - 2);

				//校验CRC
				if (ModbusUtil.unsignedByteToInt(buffer[length - 2]) != crc[0]
						|| ModbusUtil.unsignedByteToInt(buffer[length - 1]) != crc[1])
				{
					throw new IOException("CRC Error in received frame: " + length + " bytes: "
							+ ModbusUtil.toHex(buffer, 0, length));
				}

				// reset the input buffer to the given packet length (excluding
				// the CRC)
				this.inputBuffer.reset(buffer, length);

				// create the response
				response = ModbusResponse.createModbusResponse(functionCode);
				response.setHeadless();

				// read the response
				response.readFrom(inputBuffer);				
			}
			return response;
		}
		catch (IOException e)
		{
			// debug
			System.err.println(ModbusRTUTCPTransport.logId + "Error while reading from socket: " + e);

			// clean the input stream
			cleanInputStream();

			// wrap and re-throw
			throw new ModbusIOException("I/O exception - failed to read.\n" + e);
		}			
	}
	
	private void cleanInputStream(){
		try
		{
			while (mInputStream.read() != -1)
				;
		}
		catch (IOException e)
		{
			// debug
			System.err.println("清理输入缓冲区异常：" + e.getMessage());
		}
	}

	private int getResponse(int fn) 
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
			byte[] buffer = inputBuffer.getBuffer();
			int count = mInputStream.read(buffer, 2, 1);
			int dataLength = this.inputBuffer.readUnsignedByte();
			//读取剩余数据(包括CRC)
			int remain = dataLength + 2;
			int offset  = 3;
			while(remain > 0){
				count = mInputStream.read(buffer, offset, remain);
				remain = remain - count;
				offset = offset + count;
			}
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
			byte[] buffer = inputBuffer.getBuffer();
			//读取剩余字节
			mInputStream.read(buffer, 2, 6);
			//计算数据总长度
			length = 8;
			break;
		}
		case 0x07:
		case 0x08:
		{
			//读取剩余字节
			length = 3;
			break;
		}
		case 0x16:
		{
			//读取剩余字节
			length = 8;
			break;
		}
		case 0x18:
		{
			// get a reference to the inner byte buffer
			byte inBuffer[] = this.inputBuffer.getBuffer();
			mInputStream.read(inBuffer, 2, 2);
			length = this.inputBuffer.readUnsignedShort() + 6;// UID+FC+CRC(2bytes)
			break;
		}
		case 0x83:
		case 0x90:		
			// error code
			byte[] buffer = inputBuffer.getBuffer();
			//读取剩余字节
			mInputStream.read(buffer, 2, 3);
			length = 5;			
			break;
		
		}

		return length;
	}

	@Override
	public void close() throws IOException
	{
		mInputStream.close();
		mOutputStream.close();
	}

}
