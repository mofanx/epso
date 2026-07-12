package li.mofanx.epso.ui.common.adaptive

import androidx.compose.runtime.Composable

/**
 * 按窗口宽度自适应入口
 *
 * 分别提供 Compact / Medium / Expanded 三种布局插槽，
 * 未提供的插槽默认回退到更紧凑的上一级。
 */
@Composable
fun AdaptiveLayout(
    compact: @Composable () -> Unit,
    medium: @Composable () -> Unit = compact,
    expanded: @Composable () -> Unit = medium,
) {
    when (currentWindowWidth()) {
        WindowWidth.Compact -> compact()
        WindowWidth.Medium -> medium()
        WindowWidth.Expanded -> expanded()
    }
}
