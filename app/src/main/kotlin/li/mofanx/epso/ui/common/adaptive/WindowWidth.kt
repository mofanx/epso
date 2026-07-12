package li.mofanx.epso.ui.common.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 窗口宽度分级
 *
 * 断点与 Material3 WindowWidthSizeClass 对齐：
 * - Compact: < 600 dp
 * - Medium: 600 ~ 839 dp
 * - Expanded: >= 840 dp
 */
enum class WindowWidth {
    Compact,
    Medium,
    Expanded,
}

@Composable
fun currentWindowWidth(): WindowWidth {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return when {
        screenWidth < 600 -> WindowWidth.Compact
        screenWidth < 840 -> WindowWidth.Medium
        else -> WindowWidth.Expanded
    }
}
