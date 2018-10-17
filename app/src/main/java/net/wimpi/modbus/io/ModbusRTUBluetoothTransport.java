package net.wimpi.modbus.io;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.msg.ModbusMessage;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.util.ModbusUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 传统蓝牙 modbus传输层
 */
public class ModbusRTUBluetoothTransport implements ModbusTransport {

    public static final String TAG = ModbusRTUBluetoothTransport.class.getSimpleName();

    /**
     * 输入流
     */
    private InputStream mInputStream;

    /**
     * 输出流
     */
    private OutputStream mOutputStream;

    // The Bytes output stream to use as output buffer for Modbus frames
    private BytesOutputStream outputBuffer;

    // The BytesInputStream wrapper for the transport input stream
    private BytesInputStream inputBuffer;

    private BluetoothSocket mSocket;

    public ModbusRTUBluetoothTransport(BluetoothSocket socket) throws IOException {
        if (socket != null)
            this.setSocket(socket);
    }

    public void setSocket(BluetoothSocket socket) throws IOException {
        if(mSocket != null) {
            this.outputBuffer.close();
            this.inputBuffer.close();
            mInputStream.close();
            mOutputStream.close();
        }
        mSocket = socket;

        // get the input and output streams
        mInputStream = socket.getInputStream();
        mOutputStream = socket.getOutputStream();

        // prepare the buffers
        this.outputBuffer = new BytesOutputStream(Modbus.MAX_MESSAGE_LENGTH);
        this.inputBuffer = new BytesInputStream(Modbus.MAX_MESSAGE_LENGTH);
    }

    @Override
    public void close() throws IOException {
        mInputStream.close();
        mOutputStream.close();
    }

    @Override
    public void writeMessage(ModbusMessage msg) throws ModbusIOException {
        try {
            synchronized(this.outputBuffer){
                this.outputBuffer.reset();

                msg.setHeadless();
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
                Log.d(TAG,"modubs应用数据: " + ModbusUtil.toHex(rawBuffer, 0, bufferLength));

                //在这里决定是否 发送新模块的帧头
                mOutputStream.write(rawBuffer, 0, bufferLength);
                mOutputStream.flush();

                Thread.sleep(bufferLength);
            }
        } catch (Exception ex) {
            Log.e(TAG,ex.getMessage(),ex);
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

            synchronized (inputBuffer) {
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

                this.inputBuffer.reset(buffer, length);

                // create the response
                response = ModbusResponse.createModbusResponse(functionCode);
                response.setHeadless();

                // read the response
                response.readFrom(inputBuffer);
            }
            return response;
        } catch (IOException e) {
            Log.e(TAG,"Error while reading from socket: " + e.getMessage());

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

}
