package li.mofanx.epso.ui.share

import androidx.compose.runtime.staticCompositionLocalOf
import li.mofanx.epso.MainViewModel

val LocalMainViewModel = staticCompositionLocalOf<MainViewModel> {
    error("not found MainViewModel")
}

val LocalDarkTheme = staticCompositionLocalOf { false }

val LocalIsTalkbackEnabled = staticCompositionLocalOf {
    false
}
