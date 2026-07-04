package li.mofanx.epso.a11y

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import li.mofanx.epso.app
import li.mofanx.epso.data.isSystem
import li.mofanx.epso.expansion.ExpansionService
import li.mofanx.epso.shizuku.safeInvokeShizuku
import li.mofanx.epso.util.AndroidTarget
import li.mofanx.epso.util.LogUtils
import li.mofanx.epso.util.PKG_FLAGS
import li.mofanx.epso.util.systemUiAppId
import li.songe.loc.Loc

data class TopActivity(
    val appId: String = "",
    val activityId: String? = null,
    val number: Int = 0,
) {
    val shortActivityId: String?
        get() = if (activityId != null && activityId.startsWith(appId)) {
            activityId.substring(appId.length)
        } else {
            activityId
        }

    fun format(): String = "${appId}/${shortActivityId}/${number}"

    fun sameAs(a: String, b: String?): Boolean {
        return appId == a && activityId == b
    }

    fun sameAs(cn: ComponentName): Boolean {
        return appId == cn.packageName && activityId == cn.className
    }
}

val topActivityFlow = MutableStateFlow(TopActivity())

// 更新无障碍服务组件名称引用
val a11yCn by lazy { 
    ComponentName("li.mofanx.epso", "li.mofanx.epso.expansion.ExpansionService")
}
private var lastValidActivity: TopActivity = topActivityFlow.value
    set(value) {
        if (value.activityId != null) {
            field = value
        }
    }

private var lastActivityUpdateTime = 0L
private var lastActivityForceUpdateTime = 0L

private object ActivityCache : LruCache<Pair<String, String>, Boolean>(256) {
    override fun create(key: Pair<String, String>): Boolean = try {
        app.packageManager.getActivityInfo(
            ComponentName(key.first, key.second),
            PKG_FLAGS
        )
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

fun isActivity(
    appId: String,
    activityId: String,
): Boolean {
    return topActivityFlow.value.sameAs(appId, activityId) || ActivityCache.get(appId to activityId)
}

sealed class ActivityScene {
    data object ScreenOn : ActivityScene()
    data object A11y : ActivityScene()
    data object TaskStack : ActivityScene()
}

fun updateTopActivity(
    appId: String,
    activityId: String?,
    scene: ActivityScene = ActivityScene.A11y,
    @Loc loc: String = "",
) {
    val t = System.currentTimeMillis()
    val oldActivity = topActivityFlow.value
    val isSame = scene != ActivityScene.ScreenOn && oldActivity.sameAs(appId, activityId)
    if (scene == ActivityScene.A11y && isSame && t - lastActivityUpdateTime < 1000) return
    val number = if (isSame) oldActivity.number + 1 else 0
    topActivityFlow.value = TopActivity(
        appId = appId,
        activityId = activityId ?: lastValidActivity.takeIf { it.appId == appId }?.activityId,
        number = number,
    )
    lastValidActivity = oldActivity
    lastActivityUpdateTime = t
    LogUtils.d(
        "${oldActivity.format()} -> ${topActivityFlow.value.format()} (scene=$scene)",
        loc = loc,
        tag = "updateTopActivity",
    )
}

var imeAppId = ""
var launcherAppId = ""
var systemRecentCn = ComponentName("", "")

fun updateSystemDefaultAppId() {
    imeAppId = app.getSecureString(Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let(ComponentName::unflattenFromString)?.packageName ?: ""
    val launcherCn = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .resolveActivity(app.packageManager)
    launcherAppId = launcherCn.packageName
    if (app.getPkgInfo(launcherAppId)?.applicationInfo?.isSystem == true) {
        systemRecentCn = launcherCn
    } else {
        safeInvokeShizuku {
            if (AndroidTarget.P) {
                systemRecentCn = ComponentName.unflattenFromString(
                    app.getString(com.android.internal.R.string.config_recentsComponentName)
                ) ?: systemRecentCn
            }
        }
        if (systemRecentCn.packageName.isEmpty()) {
            systemRecentCn = ComponentName(
                systemUiAppId,
                "$systemUiAppId.recents.RecentsActivity",
            )
        }
    }
}
