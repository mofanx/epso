package li.mofanx.epso.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.util.LogUtils

private const val TAG = "BootReceiver"

/**
 * 开机广播：在设备重启后尝试恢复 epso 前台状态服务。
 *
 * Android 12+ 限制后台启动前台服务，因此这里仅发送启动 Intent，
 * 系统会在用户解锁后允许服务启动。同时刷新通知状态，提示用户检查无障碍服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        LogUtils.d(TAG, "Boot completed, checking service state")

        val store = storeFlow.value
        if (!store.enableStatusService) {
            LogUtils.d(TAG, "Status service disabled, skip")
            return
        }

        if (StatusService.needRestart) {
            LogUtils.d(TAG, "Auto starting StatusService after boot")
            StatusService.start()
        }
    }
}
