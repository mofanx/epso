package li.mofanx.epso.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import li.mofanx.epso.MainActivity
import li.mofanx.epso.R
import li.mofanx.epso.expansion.ExpansionService
import li.mofanx.epso.expansion.ExpansionTestPage
import li.mofanx.epso.permission.appOpsRestrictedFlow
import li.mofanx.epso.permission.writeSecureSettingsState
import li.mofanx.epso.service.StatusService
import li.mofanx.epso.service.switchAutomatorService
import li.mofanx.epso.shizuku.shizukuContextFlow
import li.mofanx.epso.shizuku.uiAutomationFlow
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.AuthA11yRoute
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfSwitch
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.component.useScrollBehaviorState
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.EmptyHeight
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.throttle

@Composable
fun useExpansionPage(): ScaffoldExt {
    val scrollKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, scrollState) = useScrollBehaviorState(scrollKey)
    
    return ScaffoldExt(
        navItem = BottomNavItem.Expansion,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(scrollBehavior = scrollBehavior, title = {
                Text("文本扩展")
            })
        }
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            ExpansionTestPage()
        }
    }
}

@Composable
fun useControlPage(): ScaffoldExt {
    val context = LocalActivity.current as MainActivity
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<HomeVm>()
    val scrollKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, scrollState) = useScrollBehaviorState(scrollKey)
    LaunchedEffect(null) {
        mainVm.resetPageScrollEvent.collect {
            if (it == BottomNavItem.Home) {
                scrollKey.intValue++
            }
        }
    }
    return ScaffoldExt(
        navItem = BottomNavItem.Home,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(scrollBehavior = scrollBehavior, title = {
                Text(
                    text = stringResource(R.string.app_name)
                )
            }, actions = {
                PerfIconButton(
                    imageVector = PerfIcon.RocketLaunch,
                    onClickLabel = "前往无障碍授权页面",
                    contentDescription = "无障碍",
                    onClick = throttle {
                        mainVm.navigatePage(AuthA11yRoute)
                    },
                )
            })
        }) { contentPadding ->
        val store by storeFlow.collectAsState()

        val a11yRunning by ExpansionService.isRunning.collectAsState()
        val manageRunning by StatusService.isRunning.collectAsState()
        val writeSecureSettings by writeSecureSettingsState.stateFlow.collectAsState()

        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2)
        ) {
            if (appOpsRestrictedFlow.collectAsState().value) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            this.onClick(label = "前往解除限制页面", action = null)
                        },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    onClick = throttle {
                        mainVm.navigatePage(AuthA11yRoute)
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(itemVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PerfIcon(imageVector = PerfIcon.WarningAmber)
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "检测到权限受限制，请前往解除",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        PerfIcon(imageVector = PerfIcon.KeyboardArrowRight)
                    }
                }
            }

            PageSwitchItemCard(
                imageVector = PerfIcon.Memory,
                title = "无障碍服务",
                subtitle = if (a11yRunning) {
                    "无障碍正在运行"
                } else if (mainVm.a11yServiceEnabledFlow.collectAsState().value) {
                    "无障碍发生故障"
                } else if (writeSecureSettings) {
                    "无障碍已关闭"
                } else {
                    "无障碍未授权"
                },
                checked = a11yRunning,
                onCheckedChange = { newEnabled ->
                    if (newEnabled && !writeSecureSettingsState.value) {
                        mainVm.navigatePage(AuthA11yRoute)
                    } else {
                        switchAutomatorService()
                    }
                },
            )

            PageSwitchItemCard(
                imageVector = PerfIcon.Notifications,
                title = "常驻通知",
                subtitle = "显示运行状态",
                checked = manageRunning && store.enableStatusService,
                onCheckedChange = {
                    if (it) {
                        context.lifecycleScope.launch { StatusService.requestStart(context) }
                    } else {
                        StatusService.stop()
                        storeFlow.value = store.copy(
                            enableStatusService = false
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}


@Composable
private fun PageSwitchItemCard(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val onClick = throttle { onCheckedChange(!checked) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.onClick(label = "切换$title", action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PerfIcon(
                imageVector = imageVector,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            PerfSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}
