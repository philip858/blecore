package cn.zfs.blelib.core;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.zfs.blelib.callback.ConnectionStateChangeListener;
import cn.zfs.blelib.event.Events;
import cn.zfs.blelib.util.BleUtils;

/**
 * 描述: 蓝牙连接
 * 时间: 2018/4/11 15:29
 * 作者: zengfansheng
 */
public class Connection extends BaseConnection {
    private static final int MSG_ARG_NONE = 0;
    private static final int MSG_ARG_RECONNECT = 1;
    private static final int MSG_ARG_NOTIFY = 2;
    
    private static final int MSG_CONNECT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_REFRESH= 3;
    private static final int MSG_AUTO_REFRESH = 4;
    private static final int MSG_TIMER = 5;
    private static final int MSG_RELEASE = 6;
    private static final int MSG_DISCOVER_SERVICES = 7;
    private static final int MSG_ON_CONNECTION_STATE_CHANGE = 8;
    private static final int MSG_ON_SERVICES_DISCOVERED = 9;
    
	private Device device;
	private Handler handler;
	private Context context;
	private ConnectionStateChangeListener stateChangeListener;
	private long connStartTime;
	private int refreshTimes;//记录刷新次数，如果成功发现服务器，则清零
    private int tryReconnectTimes;//尝试重连次数
    private int lastConnectState = -1;
    private int reconnectImmediatelyCount;//不搜索，直接连接次数
    private boolean refreshing;
    private boolean isActiveDisconnect;    
	    
    private Connection(BluetoothDevice bluetoothDevice, ConnectionConfig config) {
        super(bluetoothDevice, config);
        handler = new ConnHandler(this);        
    }

    /**
     * 连接
     * @param device 蓝牙设备
     * {@link BluetoothDevice#TRANSPORT_AUTO}<br>{@link BluetoothDevice#TRANSPORT_BREDR}<br>{@link BluetoothDevice#TRANSPORT_LE}              
     */
    synchronized static Connection newInstance(@NonNull BluetoothAdapter bluetoothAdapter, @NonNull Context context, @NonNull Device device,
                                               ConnectionConfig config, long connectDelay, ConnectionStateChangeListener stateChangeListener) {
        if (device.addr == null || !device.addr.matches("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")) {
            Ble.println(Connection.class, Log.ERROR, String.format(Locale.US, "connect failed! [type: unspecified mac address, name: %s, mac: %s]",
                    device.name, device.addr));
            notifyConnectFailed(device, CONNECT_FAIL_TYPE_UNSPECIFIED_MAC_ADDRESS, stateChangeListener);
            return null;
        }
        //初始化并建立连接
        if (config == null) {
            config = ConnectionConfig.newInstance();
        }
        Connection conn = new Connection(bluetoothAdapter.getRemoteDevice(device.addr), config);
        conn.bluetoothAdapter = bluetoothAdapter;
        conn.device = device;
        conn.context = context.getApplicationContext();
        conn.stateChangeListener = stateChangeListener;
        //连接蓝牙设备
        conn.connStartTime = System.currentTimeMillis();
        conn.handler.sendEmptyMessageDelayed(MSG_CONNECT, connectDelay);//连接
        conn.handler.sendEmptyMessageDelayed(MSG_TIMER, connectDelay);//启动定时器，用于断线重连
        return conn;
    }

    /**
     * 获取当前连接的设备
     */
    public Device getDevice() {
        return device;
    }
    
    public BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    /**
     * 获取当前连接的配置
     */
    public ConnectionConfig getConfig() {
        return config;
    }

    /**
     * 获取蓝牙服务列表
     */
    public List<BluetoothGattService> getGattServices() {
	    if (bluetoothGatt != null) {
	        return bluetoothGatt.getServices();
	    }
	    return new ArrayList<>();
    }
    
    public synchronized void onScanResult(String addr) {
	    if (!isReleased && device.addr.equals(addr) && device.connectionState == STATE_SCANNING) {
            handler.sendEmptyMessage(MSG_CONNECT);
	    }
    }

    private static class ConnHandler extends Handler {
        private WeakReference<Connection> ref;

        ConnHandler(Connection conn) {
            super(Looper.getMainLooper());
            ref = new WeakReference<>(conn);
        }

        @Override
        public void handleMessage(Message msg) {
            Connection conn = ref.get();
            if (conn == null || (conn.isReleased && msg.what != MSG_RELEASE)) {
                return;
            }
            switch(msg.what) {
                case MSG_CONNECT://连接
                    if (conn.bluetoothAdapter.isEnabled()) {
                        conn.doConnect();
                    }
                    break;
                case MSG_DISCONNECT://处理断开
                    conn.doDisconnect(msg.arg1 == MSG_ARG_RECONNECT && conn.bluetoothAdapter.isEnabled(), true);
                    break;
                case MSG_REFRESH://手动刷新
                    conn.doRefresh(false);
                    break;
                case MSG_AUTO_REFRESH://自动刷新
                    conn.doRefresh(true);
                    break;
                case MSG_RELEASE://销毁连接
                    conn.config.autoReconnect = false;//停止重连
                    conn.doDisconnect(false, msg.arg1 == MSG_ARG_NOTIFY);
                    break;
                case MSG_TIMER://定时器
                    conn.doTimer();
                    break;
                case MSG_DISCOVER_SERVICES://开始发现服务
                case MSG_ON_CONNECTION_STATE_CHANGE://连接状态变化
                case MSG_ON_SERVICES_DISCOVERED://发现服务
                    if (conn.bluetoothAdapter.isEnabled()) {
                        if (msg.what == MSG_DISCOVER_SERVICES) {
                            conn.doDiscoverServices();
                        } else {
                            if (msg.what == MSG_ON_SERVICES_DISCOVERED) {
                                conn.doOnServicesDiscovered(msg.arg1);
                            } else {
                                conn.doOnConnectionStateChange(msg.arg1, msg.arg2);
                            }                            
                        }
                    }
                    break;
            }
        }
    }
        
    private void notifyDisconnected() {
        device.connectionState = STATE_DISCONNECTED;
        sendConnectionCallback();
    }
    
    private static void notifyConnectFailed(Device device, int type, ConnectionStateChangeListener listener) {
        if (listener != null) {
            listener.onConnectFailed(device, type);
        }
        Ble.getInstance().postEvent(Events.newConnectFailed(device, type));
    }
    
    private void doOnConnectionStateChange(int status, int newState) {
        if (bluetoothGatt != null) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "connected! [name: %s, mac: %s]",
                            bluetoothGatt.getDevice().getName(), bluetoothGatt.getDevice().getAddress()));
                    device.connectionState = STATE_CONNECTED;
                    sendConnectionCallback();
                    // 进行服务发现，延时
                    handler.sendEmptyMessageDelayed(MSG_DISCOVER_SERVICES, config.discoverServicesDelayMillis);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "disconnected! [name: %s, mac: %s, autoReconnEnable: %s]",
                            bluetoothGatt.getDevice().getName(), bluetoothGatt.getDevice().getAddress(), String.valueOf(config.autoReconnect)));
                    clearRequestQueueAndNotify();
                    notifyDisconnected();
                }
            } else {
                Ble.println(Connection.class, Log.ERROR, String.format(Locale.US, "GATT error! [name: %s, mac: %s, status: %d]",
                        bluetoothGatt.getDevice().getName(), bluetoothGatt.getDevice().getAddress(), status));
                if (status == 133) {
                    doClearTaskAndRefresh();
                } else {
                    clearRequestQueueAndNotify();
                    notifyDisconnected();
                }
            }
        }        
    }
    
    private void doOnServicesDiscovered(int status) {  
        if (bluetoothGatt != null) {
            List<BluetoothGattService> services = bluetoothGatt.getServices();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "services discovered! [name: %s, mac: %s, size: %d]",
                        bluetoothGatt.getDevice().getName(), bluetoothGatt.getDevice().getAddress(), bluetoothGatt.getServices().size()));
                if (services.isEmpty()) {
                    doClearTaskAndRefresh();
                } else {
                    refreshTimes = 0;
                    tryReconnectTimes = 0;
                    reconnectImmediatelyCount = 0;
                    device.connectionState = STATE_SERVICE_DISCOVERED;
                    sendConnectionCallback();
                }
            } else {
                doClearTaskAndRefresh();
                Ble.println(Connection.class, Log.ERROR, String.format(Locale.US, "GATT error! [status: %d, name: %s, mac: %s]",
                        status, bluetoothGatt.getDevice().getName(), bluetoothGatt.getDevice().getAddress()));
            }
        }        
    }
    
    private void doDiscoverServices() {
        if (bluetoothGatt != null) {
            bluetoothGatt.discoverServices();
            device.connectionState = STATE_SERVICE_DISCOVERING;
            sendConnectionCallback();
        } else {
            notifyDisconnected();
        }
    }
    
    private void doTimer() {
        if (!isReleased) {
            //只处理不在连接状态的、不在刷新、不是主动断开
            if (device.connectionState != STATE_SERVICE_DISCOVERED && !refreshing && !isActiveDisconnect) {
                if (device.connectionState != STATE_DISCONNECTED) {
                    //超时
                    if (System.currentTimeMillis() - connStartTime > config.connectTimeoutMillis) {
                        connStartTime = System.currentTimeMillis();
                        Ble.println(Connection.class, Log.ERROR, String.format(Locale.US, "connect timeout! [name: %s, mac: %s]", device.name, device.addr));
                        int type;
                        if (device.connectionState == STATE_SCANNING) {
                            type = TIMEOUT_TYPE_CANNOT_DISCOVER_DEVICE;
                        } else if (device.connectionState == STATE_CONNECTING) {
                            type = TIMEOUT_TYPE_CANNOT_CONNECT;
                        } else {
                            type = TIMEOUT_TYPE_CANNOT_DISCOVER_SERVICES;
                        }
                        Ble.getInstance().postEvent(Events.newConnectTimeout(device, type));
                        if (config.autoReconnect && (config.tryReconnectTimes == ConnectionConfig.TRY_RECONNECT_TIMES_INFINITE || tryReconnectTimes < config.tryReconnectTimes)) {
                            doDisconnect(true, true);
                        } else {
                            doDisconnect(false, true);
                            notifyConnectFailed(device, CONNECT_FAIL_TYPE_MAXIMUM_RECONNECTION, stateChangeListener);
                            Ble.println(Connection.class, Log.ERROR, String.format(Locale.US, "connect failed! [type: maximun reconnection, name: %s, mac: %s]", 
                                    device.name, device.addr));
                        }
                    }                
                } else if (config.autoReconnect) {
                    doDisconnect(true, true);
                }
            }
            handler.sendEmptyMessageDelayed(MSG_TIMER, 500);            
        }
    }
        
    //处理刷新
    private void doRefresh(boolean isAuto) {
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "refresh GATT! [name: %s, mac: %s]", device.name, device.addr));
	    connStartTime = System.currentTimeMillis();//防止刷新过程自动重连
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            refreshing = true;
            if (isAuto) {
                if (refreshTimes <= 5) {
                    refresh(bluetoothGatt);
                }
                refreshTimes++;
            } else {
                refresh(bluetoothGatt);
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (refreshing) {
                        refreshing = false;
                        bluetoothGatt.close();
                        bluetoothGatt = null;
                    }
                }
            }, 2000);
        }
        notifyDisconnected();
    }
    
    private void doConnect() {
        if (refreshing) {
            refreshing = false;
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        device.connectionState = STATE_CONNECTING;
        sendConnectionCallback();
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "connecting [name: %s, mac: %s]", device.name, device.addr));
        //连接时需要停止蓝牙扫描
        Ble.getInstance().stopScan();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isReleased) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        bluetoothGatt = bluetoothDevice.connectGatt(context, false, Connection.this,
                                config.transport == -1 ? BluetoothDevice.TRANSPORT_LE : config.transport);
                    } else {
                        bluetoothGatt = bluetoothDevice.connectGatt(context, false, Connection.this);
                    }
                }
            }
        }, 500);
    }
    
    private void doDisconnect(boolean reconnect, boolean notify) {
        clearRequestQueueAndNotify();
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        device.connectionState = STATE_DISCONNECTED;
        if (isReleased) {//销毁
            device.connectionState = STATE_RELEASED;
            bluetoothGatt = null;
            handler.removeCallbacksAndMessages(null);
            Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "connection released! [name: %s, mac: %s]", device.name, device.addr));
        } else if (reconnect) {
            tryReconnectTimes++;
            if (reconnectImmediatelyCount < config.reconnectImmediatelyTimes) {
                reconnectImmediatelyCount++;
                connStartTime = System.currentTimeMillis();
                doConnect();
            } else {
                tryScanReconnect();
            }
        }        
        if (notify) {
            sendConnectionCallback();
        }
    }

    private void tryScanReconnect() {        
        if (!isReleased) {
            connStartTime = System.currentTimeMillis();
            Ble.getInstance().stopScan();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isReleased) {
                        //开启扫描，扫描到才连接
                        device.connectionState = STATE_SCANNING;
                        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "scanning [name: %s, mac: %s]", device.name, device.addr));
                        Ble.getInstance().startScan(context);
                    }
                }
            }, 2000);
        }
    }

    private void doClearTaskAndRefresh() {
        clearRequestQueueAndNotify();
        doRefresh(true);       
    }
    
    private void sendConnectionCallback() {
	    if (lastConnectState != device.connectionState) {
            lastConnectState = device.connectionState;
            if (stateChangeListener != null) {
                stateChangeListener.onConnectionStateChanged(device);
            }
            Ble.getInstance().postEvent(Events.newConnectionStateChanged(device, device.connectionState));
	    }	    
    }
    
    void setAutoReconnectEnable(boolean enable) {
        config.autoReconnect = enable;
    }

    boolean isAutoReconnectEnabled() {
        return config.autoReconnect;
    }
    
    public void reconnect() {
	    if (!isReleased) {
	        isActiveDisconnect = false;
            tryReconnectTimes = 0;
            reconnectImmediatelyCount = 0;
            Message.obtain(handler, MSG_DISCONNECT, MSG_ARG_RECONNECT, 0).sendToTarget();
	    }
	}

    public void disconnect() {
        if (!isReleased) {
            isActiveDisconnect = true;
            Message.obtain(handler, MSG_DISCONNECT, MSG_ARG_NONE, 0).sendToTarget();
        }
	}
	
    /**
     * 清理缓存
     */
    public void refresh() {
        handler.sendEmptyMessage(MSG_REFRESH);
    }
    
	/**
	 * 销毁连接，停止定时器
	 */
	@Override
	public void release() {
	    super.release();
        Message.obtain(handler, MSG_RELEASE, MSG_ARG_NOTIFY, 0).sendToTarget();
	}

    /**
     * 销毁连接，不发布消息
     */
    public void releaseNoEvnet() {
        super.release();
        Message.obtain(handler, MSG_RELEASE, MSG_ARG_NONE, 0).sendToTarget();
    }
	
    public int getConnctionState() {
		return device.connectionState;
	}
	
	@Override
	public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        if (isReleased) {
            gatt.disconnect();
            gatt.close();
        } else {
            handler.sendMessage(Message.obtain(handler, MSG_ON_CONNECTION_STATE_CHANGE, status, newState));
        }	    
	}  
    
	@Override
	public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if (isReleased) {
            gatt.disconnect();
            gatt.close();
        } else {
            handler.sendMessage(Message.obtain(handler, MSG_ON_SERVICES_DISCOVERED, status, 0));
        }
	}

	private String getHex(byte[] value) {
        return BleUtils.bytesToHexString(value).trim();
    }
	
    @Override
    public void onCharacteristicRead(@NonNull String requestId, BluetoothGattCharacteristic characteristic) {
        Ble.getInstance().postEvent(Events.newCharacteristicRead(device, requestId, new GattCharacteristic(characteristic.getService().getUuid(), characteristic.getUuid(), characteristic.getValue())));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "characteristic read! [mac: %s, value: %s]", device.addr, getHex(characteristic.getValue())));
    }

    @Override
    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        Ble.getInstance().postEvent(Events.newCharacteristicChanged(device, new GattCharacteristic(characteristic.getService().getUuid(), characteristic.getUuid(), characteristic.getValue())));
        Ble.println(Connection.class, Log.INFO, String.format(Locale.US, "characteristic change! [mac: %s, value: %s]", device.addr, getHex(characteristic.getValue())));
    }

    @Override
    public void onReadRemoteRssi(@NonNull String requestId, int rssi) {
        Ble.getInstance().postEvent(Events.newRemoteRssiRead(device, requestId, rssi));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "rssi read! [mac: %s, rssi: %d]", device.addr, rssi));
    }

    @Override
    public void onMtuChanged(@NonNull String requestId, int mtu) {
        Ble.getInstance().postEvent(Events.newMtuChanged(device, requestId, mtu));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "mtu change! [mac: %s, mtu: %d]", device.addr, mtu));
    }

    @Override
    public void onRequestFialed(@NonNull String requestId, @NonNull Request.RequestType requestType, int failType, byte[] value) {
        Ble.getInstance().postEvent(Events.newRequestFailed(device, requestId, requestType, failType, value));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "request failed! [mac: %s, requestId: %s, failType: %d]", device.addr, requestId, failType));
    }

    @Override
    public void onDescriptorRead(@NonNull String requestId, BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        Ble.getInstance().postEvent(Events.newDescriptorRead(device, requestId, new GattDescriptor(characteristic.getService().getUuid(), characteristic.getUuid(), descriptor.getUuid(), descriptor.getValue())));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "descriptor read! [mac: %s, value: %s]", device.addr, getHex(descriptor.getValue())));
    }

    @Override
    public void onNotificationChanged(@NonNull String requestId, BluetoothGattDescriptor descriptor, boolean isEnabled) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        Ble.getInstance().postEvent(Events.newNotificationChanged(device, requestId, new GattDescriptor(characteristic.getService().getUuid(), characteristic.getUuid(), descriptor.getUuid(), descriptor.getValue()), isEnabled));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, (isEnabled ? "notification enabled!" : "notification disabled!") + " [mac: %s]", device.addr));
    }

    @Override
    public void onIndicationChanged(@NonNull String requestId, BluetoothGattDescriptor descriptor, boolean isEnabled) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        Ble.getInstance().postEvent(Events.newIndicationChanged(device, requestId, new GattDescriptor(characteristic.getService().getUuid(), characteristic.getUuid(), descriptor.getUuid(), descriptor.getValue()), isEnabled));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, (isEnabled ? "indication enabled!" : "indication disabled") + " [mac: %s]", device.addr));
    }

    @Override
    public void onCharacteristicWrite(@NonNull String requestId, byte[] value) {
        Ble.getInstance().postEvent(Events.newCharacteristicWrite(device, requestId, value));
        Ble.println(Connection.class, Log.DEBUG, String.format(Locale.US, "write success! [mac: %s, value: %s]", device.addr, getHex(value)));
    }
}
