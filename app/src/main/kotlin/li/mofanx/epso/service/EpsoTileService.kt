package li.mofanx.epso.service

import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import li.mofanx.epso.accessRestrictedSettingsShowFlow
import li.mofanx.epso.a11y.a11yCn
import li.mofanx.epso.app
import li.mofanx.epso.appScope
import li.mofanx.epso.expansion.ExpansionService
import li.mofanx.epso.permission.writeSecureSettingsState
import li.mofanx.epso.shizuku.AutomationService
import li.mofanx.epso.shizuku.shizukuContextFlow
import li.mofanx.epso.shizuku.uiAutomationFlow
import li.mofanx.epso.util.launchTry
import li.mofanx.epso.util.toast

class EpsoTileService : BaseTileService() {
    override val activeFlow = combine(ExpansionService.isRunning, uiAutomationFlow) { a11y, automator ->
        a11y || automator != null
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        onTileClicked { switchAutomatorService() }
    }
}

private suspend fun switchA11yService() {
    if (ExpansionService.isRunning.value) {
        ExpansionService.getInstance()?.disableSelf()
    } else {
        if (!writeSecureSettingsState.updateAndGet()) {
            if (!writeSecureSettingsState.value) {
                toast("请先授予「写入安全设置权限」")
                return
            }
        }
        val names = app.getSecureA11yServices()
        app.putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        if (names.contains(a11yCn)) {
            names.remove(a11yCn)
            app.putSecureA11yServices(names)
            delay(1000L)
        }
        names.add(a11yCn)
        app.putSecureA11yServices(names)
        delay(2000L)
        if (!ExpansionService.isRunning.value) {
            toast("开启无障碍失败")
            accessRestrictedSettingsShowFlow.value = true
        }
    }
}

private fun switchAutomationService() {
    val newEnabled = uiAutomationFlow.value == null
    uiAutomationFlow.value?.shutdown()
    if (newEnabled && shizukuContextFlow.value.ok) {
        AutomationService.tryConnect()
    }
}

fun switchAutomatorService() = appScope.launchTry(Dispatchers.IO) {
    switchA11yService()
}
