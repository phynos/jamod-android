package net.wimpi.modbus.net;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import net.wimpi.modbus.io.ModbusRTUBLETransport;
import net.wimpi.modbus.io.ModbusTransport;

/**
 * 根据stackoverflow上的说明，如果蓝牙连接操作不在主线程（UI线程）中，
 * 会导致 某些手机上无法注册回调函数
 * @author lupc
 *
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class RTUBLEMasterConnection implements MasterConnection {

	public static final String TAG = "RTUBLEMasterConnection";

	/**
	 * 利尔达 蓝牙模块 透传服务UUID
	 */
	protected static final UUID UUID_DATA_SERVICE = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb");
	/**
	 * 写特性UUID
	 */
	protected static final UUID UUID_DATA_IN = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
	/**
	 * 通知特性UUID
	 */
	protected static final UUID UUID_DATA_OUT = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

	private Context mContext;

	private ModbusRTUBLETransport mTransport;

	private BluetoothManager mBluetoothManager;	
	private BluetoothAdapter mBluetoothAdapter;
	private String mBluetoothDeviceAddress;
	private BluetoothGatt mBluetoothGatt;
	private BluetoothGattCharacteristic mWriteCharacter;
	private BluetoothGattCharacteristic mNotifyCharacter;

	private int mConnectionState = STATE_DISCONNECTED;

	private static final int STATE_DISCONNECTED = 0;
	private static final int STATE_CONNECTING = 1;
	private static final int STATE_CONNECTED = 2;

	private Object mConMutex = new Object();
	private Object mSerMutex = new Object();

	private Handler mHandler;
	//定义一些 消息 用于通信
	public static final int MSG_INIT = 0;
	public static final int MSG_CONNECT = 1;
	public static final int MSG_DISCONNECT = 2;
	public static final int MSG_WRITE = 3;
	public static final int MSG_SERVICES_DISCOVERED = 4;
	public static final int MSG_CLOSE = 5;

	public RTUBLEMasterConnection(Context context,final String address) throws Exception {
		mContext = context;
		mBluetoothDeviceAddress = address;
		//构建一个UI线程的消息处理
		mHandler = new Handler(context.getMainLooper(),new Handler.Callback() {

			@Override
			public boolean handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_INIT:
					gattInit();
					break;
				case MSG_CONNECT:
					//连接
					gattConnect();
					break;
				case MSG_DISCONNECT:
					//断开连接
					gattDisconnect();
					break;
				case MSG_WRITE:
					byte[] data = (byte[]) msg.obj;
					gattWrite(data);
					break;
				case MSG_SERVICES_DISCOVERED:
					gattDisplayServices();
					break;
				case MSG_CLOSE:
					gattClose();
					break;
				default:
					break;
				}
				return false;
			}
		});	
		mHandler.sendEmptyMessage(MSG_INIT);
	}


	@Override
	public void connect() throws Exception {
		synchronized (mConMutex) {
			//给界面发送消息 要求gatt连接
			mHandler.sendEmptyMessage(MSG_CONNECT);
			mConMutex.wait(3000);
		}
		//连接成功后 准备相关
		if(mConnectionState != STATE_CONNECTED)
			throw new IOException("BLE连接失败");
		//连接成功后 等待1000毫秒，如果此时特性都为空，继续等待2000毫秒
		Thread.sleep(1000);
		if(mWriteCharacter == null && mNotifyCharacter == null){
			synchronized (mSerMutex) {
				mSerMutex.wait(2000);
			}
		}		
		if(mWriteCharacter == null)
			throw new IOException("BLE读取 写 特性失败");
		if(mNotifyCharacter == null)
			throw new IOException("BLE读取 通知 特性失败");

		//传输层之前，必须准备好 连接、写特性、通知特性，否则 
		prepareTransport();		
	}

	private void gattInit(){
		try {
			mBluetoothManager = (BluetoothManager) mContext.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager == null) {
				Log.e(TAG, "Unable to initialize BluetoothManager.");
				throw new Exception("Unable to initialize BluetoothManager.");
			}

			mBluetoothAdapter = mBluetoothManager.getAdapter();
			if (mBluetoothAdapter == null || mBluetoothAdapter == null) {
				Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
				throw new Exception("BluetoothAdapter not initialized or unspecified address.");
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage(),e);
		}	
	}

	private void gattConnect(){
		//为了保证兼容性，连接必须在 main线程中 执行
		Log.d(TAG, "BLE连接，所在线程：" + Thread.currentThread().getName());
		try {
			//
			if(mBluetoothGatt != null){
				if(mBluetoothGatt.connect()){
					mConnectionState = STATE_CONNECTING;
					return;
				}
			}		

			final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mBluetoothDeviceAddress);
			if (device == null) {
				Log.w(TAG, "Device not found.  Unable to connect.");
				throw new Exception();
			}

			//连接，注册回调，必须要在UI线程中执行
			mBluetoothGatt = device.connectGatt(mContext, false, mGattCallback);
			Log.d(TAG, "Trying to create a new connection.");
			mConnectionState = STATE_CONNECTING;

		} catch (Exception e) {
			Log.e(TAG, e.getMessage(),e);
		} 	
	}

	private void gattDisconnect(){
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.disconnect();
	}

	private void gattWrite(byte[] data){
		try {
			Log.d(TAG, "gatt写，当前ble连接状态：" + mConnectionState);
			mWriteCharacter.setValue(data);
			mWriteCharacter.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			mBluetoothGatt.writeCharacteristic(mWriteCharacter);
		} catch (Exception e) {
			Log.e(TAG, e.getMessage(), e);
			mTransport.onWrite(false);
		}		
	}

	private void gattDisplayServices(){	
		try {
			BluetoothGattService s = mBluetoothGatt.getService(UUID_DATA_SERVICE);

			mWriteCharacter = s.getCharacteristic(UUID_DATA_IN);
			mNotifyCharacter = s.getCharacteristic(UUID_DATA_OUT);
			if(mNotifyCharacter != null){
				boolean result = mBluetoothGatt.setCharacteristicNotification(mNotifyCharacter, true);
				Log.d(TAG, "通知设置结果：" + result);
				List<BluetoothGattDescriptor> descriptors = mNotifyCharacter.getDescriptors();
				if(descriptors != null){
					for (BluetoothGattDescriptor bluetoothGattDescriptor : descriptors) {
						bluetoothGattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
						mBluetoothGatt.writeDescriptor(bluetoothGattDescriptor);
					}
				}
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage(), e);
		}		
		//
		synchronized (mSerMutex) {
			mSerMutex.notify();
		}
	}

	private void gattClose(){
		if (mBluetoothGatt == null) {
			return;
		}
		mBluetoothGatt.close();
		mBluetoothGatt = null;
	}

	@Override
	public boolean isConnected() {
		if(mConnectionState == STATE_CONNECTED){


			return true;
		}			
		else
			return false;
	}

	@Override
	public void close() {
		//保证 一定在 UI线程中执行 操作
		mHandler.sendEmptyMessage(MSG_CLOSE);
	}

	public void disConnect(){
		mHandler.sendEmptyMessage(MSG_DISCONNECT);
	}

	//注意：回调会在新的线程中，一般是 在Binder线程中
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			Log.d(TAG, "BLE回调线程：" + Thread.currentThread().getName()
					+ ",连接执行状态：" + status 
					+ ",最新连接状态：" + newState);
			if (newState == BluetoothProfile.STATE_CONNECTED) {

				mConnectionState = STATE_CONNECTED;

				Log.i(TAG, "Connected to GATT server.");
				//连接成功后再次搜索
				Log.i(TAG, "Attempting to start service discovery:" +
						mBluetoothGatt.discoverServices());	

				synchronized (mConMutex) {
					mConMutex.notify();
				}
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

				mConnectionState = STATE_DISCONNECTED;
				Log.i(TAG, "Disconnected from GATT server.");
			}

		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			Log.w(TAG, "onServicesDiscovered received: " + status);
			if (status == BluetoothGatt.GATT_SUCCESS) {
				//服务读取成功，通知UI线程 读取相关服务和特性
				mHandler.sendEmptyMessage(MSG_SERVICES_DISCOVERED);
			} else {
				synchronized (mSerMutex) {
					mSerMutex.notify();
				}
			}
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic,
				int status) {
			Log.d(TAG, "Characteristic 读");
			if (status == BluetoothGatt.GATT_SUCCESS) {
				final byte[] data = characteristic.getValue();
				mTransport.onRecieve(data);
			}
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic) {
			Log.d(TAG, "BLE接收数据成功");
			final byte[] data = characteristic.getValue();
			//将数据 发送到
			mTransport.onRecieve(data);
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic, int status) {			
			if(status == BluetoothGatt.GATT_SUCCESS){
				Log.d(TAG, "BLE发送数据成功");
				mTransport.onWrite(true);
			} else {
				Log.d(TAG, "BLE发送数据失败");
			}
		}

	};

	private void prepareTransport(){	
		mTransport = new ModbusRTUBLETransport(mHandler);
	}

	public ModbusTransport getModbusTransport(){
		return mTransport;
	}

	public void checkCharacteristic(){
		if(mWriteCharacter == null || mNotifyCharacter == null){
			gattDisplayServices();
		}
		if(mTransport == null){
			prepareTransport();
		}
	}

}
