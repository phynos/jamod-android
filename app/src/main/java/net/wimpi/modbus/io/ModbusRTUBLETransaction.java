package net.wimpi.modbus.io;

import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.ModbusSlaveException;
import net.wimpi.modbus.msg.ExceptionResponse;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.net.RTUBLEMasterConnection;

/**
 * BLE通信 不重发
 * @author lupc
 *
 */
public class ModbusRTUBLETransaction implements ModbusTransaction {

	private RTUBLEMasterConnection mConnection;
	private ModbusTransport mIO;
	private ModbusRequest mRequest;
	private ModbusResponse mResponse;

	public ModbusRTUBLETransaction(RTUBLEMasterConnection con){
		mConnection = con;
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
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isCheckingValidity() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void execute() throws ModbusIOException, 
								ModbusSlaveException,
								ModbusException {

		if(!mConnection.isConnected()){
			try {
				mConnection.connect();

			} catch (Exception e) {
				throw new ModbusIOException("Connecting failed.");
			}
		}

		mIO = mConnection.getModbusTransport();
		mIO.writeMessage(mRequest);
		mResponse = mIO.readResponse();

		if (mResponse instanceof ExceptionResponse)
			throw new ModbusSlaveException(((ExceptionResponse) mResponse).getExceptionCode());

	}

}
