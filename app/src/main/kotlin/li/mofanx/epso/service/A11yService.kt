package li.mofanx.epso.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context.WINDOW_SERVICE
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import li.mofanx.epso.a11y.A11yCommonImpl
import li.mofanx.epso.a11y.topActivityFlow
import li.mofanx.epso.a11y.updateTopActivity
import li.mofanx.epso.shizuku.shizukuContextFlow
import li.mofanx.epso.util.AndroidTarget
import li.mofanx.epso.util.DefaultA11yLifeImpl
import li.mofanx.epso.util.LogUtils
import li.mofanx.epso.util.OnA11yLife
import li.mofanx.epso.util.componentName
import li.mofanx.epso.util.runMainPost
import li.mofanx.epso.util.toast
import kotlin.coroutines.resume

@SuppressLint("AccessibilityPolicy")
abstract class A11yService : AccessibilityService(), OnA11yLife by DefaultA11yLifeImpl(),
    A11yCommonImpl {
    override val windowNodeInfo: AccessibilityNodeInfo? get() = rootInActiveWindow
    override val windowInfos: List<AccessibilityWindowInfo> get() = windows
    override suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (AndroidTarget.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                application.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }

                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            if (cont.isActive) {
                                cont.resume(
                                    Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer, screenshot.colorSpace
                                    )
                                )
                            }
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }
                }
            )
        } else {
            cont.resume(null)
        }
    }

    override fun onCreate() = onCreated()
    override fun onServiceConnected() = onA11yConnected()
    override fun onInterrupt() {}
    override fun onDestroy() = onDestroyed()
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    val startTime = System.currentTimeMillis()
    override var justStarted: Boolean = true
        get() {
            if (field) {
                field = System.currentTimeMillis() - startTime < 3_000
            }
            return field
        }

    private var tempShutdownFlag = false

    override fun shutdown(temp: Boolean) {
        if (temp) {
            tempShutdownFlag = true
        }
        disableSelf()
    }

    private var destroyed = false
    private var connected = false

    val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        onA11yConnected { instance = this }
        onDestroyed { instance = null }
        onDestroyed {
            if (tempShutdownFlag) {
                toast("无障碍局部关闭")
            } else {
                toast("无障碍已关闭")
            }
        }
        useAliveOverlayView()
        onCreated { StatusService.autoStart() }
        onDestroyed {
            synchronized(topActivityFlow) {
                shizukuContextFlow.value.topCpn()?.let { cpn ->
                    if (!topActivityFlow.value.sameAs(cpn.packageName, cpn.className)) {
                        updateTopActivity(cpn.packageName, cpn.className)
                    }
                }
            }
        }
        onDestroyed { destroyed = true }
        onA11yConnected {
            connected = true
            toast("无障碍已启动")
        }
        onCreated {
            runMainPost(3000) {
                if (!(destroyed || connected)) {
                    toast("无障碍启动超时，请尝试关闭重启", forced = true)
                }
            }
        }
    }

    companion object {
        val a11yCn by lazy { SelectToSpeakService::class.componentName }
        val isRunning = MutableStateFlow(false)

        @Volatile
        var instance: A11yService? = null
            private set
    }
}

private fun A11yService.useAliveOverlayView() {
    val context = this
    var aliveView: View? = null
    val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    fun removeA11View() {
        if (aliveView != null) {
            wm.removeView(aliveView)
            aliveView = null
        }
    }

    fun addA11View() {
        removeA11View()
        val tempView = View(context)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags =
                flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            packageName = context.packageName
        }
        try {
            // 某些设备 android.view.WindowManager$BadTokenException
            wm.addView(tempView, lp)
            aliveView = tempView
        } catch (e: Throwable) {
            aliveView = null
            LogUtils.d(e)
            toast("添加无障碍保活失败\n请尝试重启无障碍")
        }
    }
    onA11yConnected { addA11View() }
    onDestroyed { removeA11View() }
}
