package cn.zfs.bledemo

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import cn.zfs.blelib.core.Ble
import cn.zfs.blelib.core.Connection
import cn.zfs.blelib.core.ConnectionConfig
import cn.zfs.blelib.core.Device
import cn.zfs.blelib.event.Events
import cn.zfs.common.utils.ToastUtils
import kotlinx.android.synthetic.main.activity_connection.*
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 描述:
 * 时间: 2018/6/16 14:01
 * 作者: zengfansheng
 */
class ConnectionActivity : AppCompatActivity() {
    private var device: Device? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "功能列表"
        setContentView(R.layout.activity_connection)
        device = intent.getParcelableExtra(Consts.EXTRA_DEVICE)
        Ble.getInstance().registerSubscriber(this)        
        Ble.getInstance().connect(this, device!!, getConnectionConfig(true), null)        
        tvName.text = device!!.name
        tvAddr.text = device!!.addr
    }

    private fun getConnectionConfig(autoReconnect: Boolean): ConnectionConfig {
        val config = ConnectionConfig.newInstance()
        config.setDiscoverServicesDelayMillis(500)
        config.isAutoReconnect = autoReconnect
        return config
    }     

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onConnectionStateChange(e: Events.ConnectionStateChanged) {
        when (e.state) {
            Connection.STATE_CONNECTED -> {
                tvState.text = "连接成功，等待发现服务"
            }
            Connection.STATE_CONNECTING -> {
                tvState.text = "连接中..."
            }
            Connection.STATE_DISCONNECTED -> {
                tvState.text = "连接断开"
                ToastUtils.showShort("连接断开")
            }
            Connection.STATE_SCANNING -> {
                tvState.text = "正在搜索设备..."
            }
            Connection.STATE_SERVICE_DISCOVERING -> {
                tvState.text = "连接成功，正在发现服务..."
            }
            Connection.STATE_SERVICE_DISCOVERED -> {
                tvState.text = "连接成功，并成功发现服务"                
            }
            Connection.STATE_RELEASED -> {
                tvState.text = "连接已释放"
            }
        }
        invalidateOptionsMenu()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onConnectFailed(e: Events.ConnectFailed) {
        tvState.text = "连接失败： ${e.type}"
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onConnectTimeout(e: Events.ConnectTimeout) {
        val msg = when(e.type) {
            Connection.TIMEOUT_TYPE_CANNOT_DISCOVER_DEVICE -> "无法搜索到设备"
            Connection.TIMEOUT_TYPE_CANNOT_CONNECT -> "无法连接设备"
            else -> "无法发现蓝牙服务"
        }
        ToastUtils.showShort("连接超时:$msg")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Ble.getInstance().unregisterSubscriber(this)
        Ble.getInstance().releaseConnection(device)//销毁连接
    }
}