package li.mofanx.epso.service

import android.app.Service
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.mofanx.epso.META
import li.mofanx.epso.MainActivity
import li.mofanx.epso.a11y.useA11yServiceEnabledFlow
import li.mofanx.epso.app
import li.mofanx.epso.notif.abNotif
import li.mofanx.epso.permission.appOpsRestrictedFlow
import li.mofanx.epso.permission.foregroundServiceSpecialUseState
import li.mofanx.epso.permission.notificationState
import li.mofanx.epso.permission.requiredPermission
import li.mofanx.epso.permission.shizukuGrantedState
import li.mofanx.epso.permission.writeSecureSettingsState
import li.mofanx.epso.shizuku.uiAutomationFlow
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.util.DefaultSimpleLifeImpl
import li.mofanx.epso.util.OnSimpleLife
import li.mofanx.epso.util.startForegroundServiceByClass
import li.mofanx.epso.util.stopServiceByClass

class StatusService : Service(), OnSimpleLife by DefaultSimpleLifeImpl() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() = onCreated()
    override fun onDestroy() = onDestroyed()

    val shizukuWarnFlow = combine(
        shizukuGrantedState.stateFlow,
        storeFlow.map { it.enableShizuku },
    ) { a, b ->
        !a && b
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val a11yServiceEnabledFlow = useA11yServiceEnabledFlow()

    fun statusTriple(): Triple<String, String, String?> {
        val abRunning = A11yService.isRunning.value
        val automationRunning = uiAutomationFlow.value != null
        val store = storeFlow.value
        val shizukuWarn = shizukuWarnFlow.value
        val title = META.appName
        return if (appOpsRestrictedFlow.value) {
            Triple(title, "权限受限，请解除限制", null)
        } else if (shizukuWarn) {
            Triple(title, "Shizuku 未连接，请授权或关闭优化", null)
        } else if (!automationRunning && !abRunning) {
            val text = if (a11yServiceEnabledFlow.value) {
                "无障碍发生故障"
            } else if (writeSecureSettingsState.updateAndGet()) {
                "无障碍已关闭"
            } else {
                "无障碍未授权"
            }
            Triple(title, text, abNotif.uri)
        } else {
            Triple(title, "服务正在运行", abNotif.uri)
        }
    }

    init {
        useAliveFlow(isRunning)
        useAliveToast(
            name = "常驻通知",
            delayMillis = if (app.justStarted) 1000 else 0,
        )
        onCreated {
            abNotif.notifyService()
            scope.launch {
                combine(
                    A11yService.isRunning,
                    uiAutomationFlow,
                    storeFlow,
                    shizukuWarnFlow,
                    a11yServiceEnabledFlow,
                    writeSecureSettingsState.stateFlow,
                    appOpsRestrictedFlow,
                ) {
                    statusTriple()
                }
                    .stateIn(
                        scope,
                        SharingStarted.Eagerly,
                        Triple(abNotif.title, abNotif.text, abNotif.uri)
                    )
                    .collect {
                        abNotif.copy(
                            title = it.first,
                            text = it.second,
                            uri = it.third,
                        ).notifyService()
                    }
            }
        }
    }

    companion object {
        val isRunning = MutableStateFlow(false)
        val needRestart
            get() = storeFlow.value.enableStatusService
                    && !isRunning.value
                    && notificationState.updateAndGet()
                    && foregroundServiceSpecialUseState.updateAndGet()

        fun start() = startForegroundServiceByClass(StatusService::class)
        fun stop() = stopServiceByClass(StatusService::class)
        suspend fun requestStart(context: MainActivity) {
            requiredPermission(context, foregroundServiceSpecialUseState)
            requiredPermission(context, notificationState)
            start()
            storeFlow.update { it.copy(enableStatusService = true) }
        }

        private var lastAutoStart = 0L
        fun autoStart() {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            if (needRestart) {
                start()
                lastAutoStart = System.currentTimeMillis()
            }
        }
    }
}
