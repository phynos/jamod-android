package net.wimpi.modbus.io;

import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.ModbusSlaveException;
import net.wimpi.modbus.msg.ExceptionResponse;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.net.RTUBluetoothMasterConnection;
import net.wimpi.modbus.util.Mutex;

public class ModbusRTUBluetoothTransaction implements ModbusTransaction {

    private RTUBluetoothMasterConnection mConnection;
    private ModbusTransport mIO;
    private ModbusRequest mRequest;
    private ModbusResponse mResponse;

    private Mutex m_TransactionLock = new Mutex();

    public ModbusRTUBluetoothTransaction(RTUBluetoothMasterConnection con){
        mConnection = con;
        mIO = con.getModbusTransport();
    }

    @Override
    public void setRequest(ModbusRequest req) {
        mRequest = req;
    }

    @Override
    public ModbusRequest getRequest() {
        return mRequest;
    }

    @Override
    public ModbusResponse getResponse() {
        return mResponse;
    }

    @Override
    public int getTransactionID() {
        return 0;
    }

    @Override
    public void setRetries(int retries) {

    }

    @Override
    public int getRetries() {
        return 0;
    }

    @Override
    public void setCheckingValidity(boolean b) {

    }

    @Override
    public boolean isCheckingValidity() {
        return false;
    }

    @Override
    public void execute() throws ModbusException {
        try {
            m_TransactionLock.acquire();

            if(!mConnection.isConnected()){
                try {
                    mConnection.connect();
                    mIO = mConnection.getModbusTransport();
                } catch (Exception e) {
                    throw new ModbusIOException("Connecting failed.");
                }
            }

            mIO.writeMessage(mRequest);
            mResponse = mIO.readResponse();

            if (mResponse instanceof ExceptionResponse)
                throw new ModbusSlaveException(((ExceptionResponse) mResponse).getExceptionCode());

        } catch (InterruptedException ex) {
            throw new ModbusIOException("Thread acquiring lock was interrupted.");
        } finally {
            m_TransactionLock.release();
        }

    }

}
