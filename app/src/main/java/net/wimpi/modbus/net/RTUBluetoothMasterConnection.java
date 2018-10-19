package net.wimpi.modbus.net;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import net.wimpi.modbus.io.ModbusRTUBluetoothTransport;

import java.io.IOException;
import java.util.UUID;

/**
 * 传统蓝牙 modbus连接器
 */
public class RTUBluetoothMasterConnection implements MasterConnection {

    public static final String TAG = RTUBluetoothMasterConnection.class.getSimpleName();

    private BluetoothSocket mSocket;

    private BluetoothDevice mDevice;

    // 该应用的唯一UUID（串口服务SPP专用）
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private ModbusRTUBluetoothTransport mTransport;

    private boolean connected;

    private Context mContext;

    private final Handler mHandler;

    //定义一些 消息 用于通信
    public static final int MSG_INIT = 0;

    public RTUBluetoothMasterConnection(Context context, BluetoothDevice device){
        mContext = context;
        mDevice = device;
        mHandler = new Handler(mContext.getMainLooper(),new Handler.Callback(){

            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_INIT:
                        init();
                        break;
                }
                return false;
            }
        });
    }

    private void init(){
        Log.d(TAG,"进入UI线程创建蓝牙，当前线程名称：" + Thread.currentThread().getName());
        BluetoothSocket tmp = null;
        try {
            tmp = mDevice.createInsecureRfcommSocketToServiceRecord(MY_UUID);
        } catch (IOException e) {
            Log.e(TAG,"创建蓝牙socket失败：" + e.getMessage(),e.getCause());
        }
        mSocket = tmp;
    }

    @Override
    public void connect() throws IOException {
        if (!this.connected){
            Log.d(TAG,"蓝牙连接地址：" + mDevice.getAddress());
            //发送消息给UI线程，让其创建相关对象
            mHandler.sendEmptyMessage(MSG_INIT);
            //这里要等到UI线程处理完再执行
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG,"蓝牙开始连接。。。");
            try {
                mSocket.connect();
            } catch (IOException e) {
                Log.e(TAG,"蓝牙socket连接失败：" + e.getMessage(),e.getCause());
                try{
                    mSocket.close();
                } catch (IOException ie){
                    Log.e(TAG, "unable to close() socket during connection failure", ie);
                }
                throw e;
            }

            if(!mSocket.isConnected()) {
                Log.e(TAG,"蓝牙socket连接失败：通过socket连接属性判断！");
                throw new IOException();
            }

            Log.d(TAG,"蓝牙连接成功。。。");

            prepareTransport();

            connected = true;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        if (this.connected) {
            try {
                this.mTransport.close();
            } catch (IOException e) {
                try {
                    mSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
            }
            mSocket = null;
            this.connected = false;
        }
    }

    private void prepareTransport() throws IOException {
        if(mTransport == null) {
            mTransport = new ModbusRTUBluetoothTransport(mSocket);
        } else {
            mTransport.setSocket(mSocket);
        }
    }

    public ModbusRTUBluetoothTransport getModbusTransport(){
        return mTransport;
    }

}
