package li.mofanx.epso.a11y

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope

interface A11yCommonImpl {
    suspend fun screenshot(): Bitmap?
    val windowNodeInfo: AccessibilityNodeInfo?
    val windowInfos: List<AccessibilityWindowInfo>
    val scope: CoroutineScope
    var justStarted: Boolean
    fun shutdown(temp: Boolean = false)
}
