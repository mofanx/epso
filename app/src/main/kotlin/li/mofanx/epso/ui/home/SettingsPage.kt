package li.mofanx.epso.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.viewmodel.compose.viewModel
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.AboutRoute
import li.mofanx.epso.ui.AdvancedRoute
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.component.SettingItem
import li.mofanx.epso.ui.component.TextSwitch
import li.mofanx.epso.ui.component.useScrollBehaviorState
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.EmptyHeight
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.util.throttle

@Composable
fun useSettingsPage(): ScaffoldExt {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<HomeVm>()
    val scrollKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, scrollState) = useScrollBehaviorState(scrollKey)
    LaunchedEffect(null) {
        mainVm.resetPageScrollEvent.collect {
            if (it == BottomNavItem.Settings) {
                scrollKey.intValue++
            }
        }
    }
    return ScaffoldExt(
        navItem = BottomNavItem.Settings,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(scrollBehavior = scrollBehavior, title = {
                Text(text = "设置")
            })
        }) { contentPadding ->
        val store by storeFlow.collectAsState()

        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding)
        ) {
            TextSwitch(
                title = "动态配色",
                subtitle = "使用系统配色方案",
                checked = store.enableDynamicColor,
                onCheckedChange = {
                    storeFlow.value = store.copy(enableDynamicColor = it)
                }
            )

            SettingItem(
                title = "高级设置",
                subtitle = "Shizuku / HTTP 服务",
                imageVector = PerfIcon.Settings,
                onClick = throttle {
                    mainVm.navigatePage(AdvancedRoute)
                }
            )

            SettingItem(
                title = "关于",
                imageVector = PerfIcon.Info,
                onClick = throttle {
                    mainVm.navigatePage(AboutRoute)
                }
            )

            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}
