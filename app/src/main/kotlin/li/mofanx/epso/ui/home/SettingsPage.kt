package li.mofanx.epso.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import li.mofanx.epso.R
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

        // 搜索触发词编辑对话框
        var showSearchTriggerDlg by remember { mutableStateOf(false) }
        if (showSearchTriggerDlg) {
            var value by remember { mutableStateOf(store.searchTrigger) }
            AlertDialog(
                title = { Text(text = "快速搜索触发词") },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = { Text(text = "留空可禁用，默认 :s") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("在任意输入框输入此触发词后弹出搜索悬浮窗") },
                    )
                },
                onDismissRequest = { showSearchTriggerDlg = false },
                confirmButton = {
                    TextButton(onClick = {
                        showSearchTriggerDlg = false
                        if (value != store.searchTrigger) {
                            storeFlow.value = store.copy(searchTrigger = value.trim())
                        }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showSearchTriggerDlg = false }) { Text("取消") }
                },
            )
        }

        // 默认触发前缀编辑对话框
        var showTriggerPrefixDlg by remember { mutableStateOf(false) }
        if (showTriggerPrefixDlg) {
            var value by remember { mutableStateOf(store.triggerPrefix) }
            AlertDialog(
                title = { Text(text = stringResource(R.string.settings_trigger_prefix)) },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = { Text(text = stringResource(R.string.settings_trigger_prefix_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.settings_trigger_prefix_description)) },
                    )
                },
                onDismissRequest = { showTriggerPrefixDlg = false },
                confirmButton = {
                    TextButton(onClick = {
                        showTriggerPrefixDlg = false
                        if (value != store.triggerPrefix) {
                            storeFlow.value = store.copy(triggerPrefix = value.trim())
                        }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showTriggerPrefixDlg = false }) { Text("取消") }
                },
            )
        }

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
                title = stringResource(R.string.settings_search_trigger),
                subtitle = store.searchTrigger.ifEmpty { stringResource(R.string.settings_search_trigger_disabled) },
                onClick = throttle { showSearchTriggerDlg = true },
            )

            SettingItem(
                title = stringResource(R.string.settings_trigger_prefix),
                subtitle = store.triggerPrefix.ifEmpty { "无" },
                onClick = throttle { showTriggerPrefixDlg = true },
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
