package li.mofanx.epso.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import li.mofanx.epso.app

/**
 * 电池优化相关工具。
 *
 * 被电池优化限制后，前台服务和后台恢复可能被系统延迟或杀死，
 * 建议用户将 epso 加入白名单。
 */
object BatteryOptimization {

    /** 是否已忽略电池优化（即在白名单中） */
    fun isIgnoring(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    /**
     * 跳转到系统电池优化设置页面，让用户手动将 epso 设为不优化。
     */
    fun requestIgnore() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${app.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }
}
