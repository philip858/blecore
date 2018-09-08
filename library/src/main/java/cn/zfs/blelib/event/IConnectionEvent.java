package cn.zfs.blelib.event;

/**
 * 描述: 蓝牙连接事件
 * 使用方法: 在要监听的类中实现接口，并在方法上添加上@Subscribe注解
 * 时间: 2018/9/8 11:49
 * 作者: zengfansheng
 */
public interface IConnectionEvent {
    /**
     * 蓝牙状态变化
     */    
    void onBluetoothStateChanged(Events.BluetoothStateChanged event);

    /**
     * 连接失败
     */
    void onConnectFailed(Events.ConnectFailed event);

    /**
     * 连接状态变化
     */
    void onConnectionStateChanged(Events.ConnectionStateChanged event);

    /**
     * 连接超时
     */
    void onConnectTimeout(Events.ConnectTimeout event);
}
