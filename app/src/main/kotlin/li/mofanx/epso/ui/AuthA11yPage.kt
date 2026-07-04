package li.mofanx.epso.ui

import android.Manifest
import android.app.AppOpsManagerHidden
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import li.mofanx.epso.META
import li.mofanx.epso.permission.Manifest_permission_GET_APP_OPS_STATS
import li.mofanx.epso.permission.writeSecureSettingsState
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.shizuku.SafeAppOpsService
import li.mofanx.epso.shizuku.shizukuUsedFlow
import li.mofanx.epso.ui.component.AnimatedBooleanContent
import li.mofanx.epso.ui.component.ManualAuthDialog
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.component.updateDialogOptions
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.EmptyHeight
import li.mofanx.epso.ui.style.cardHorizontalPadding
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.AndroidTarget
import li.mofanx.epso.util.launchAsFn
import li.mofanx.epso.util.openA11ySettings
import li.mofanx.epso.util.shFolder
import li.mofanx.epso.util.throttle
import li.mofanx.epso.util.toast

@Serializable
data object AuthA11yRoute : NavKey

@Composable
fun AuthA11yPage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<AuthA11yVm>()
    val showCopyDlg by vm.showCopyDlgFlow.collectAsState()
    val writeSecureSettings by writeSecureSettingsState.stateFlow.collectAsState()
    val a11yRunning by A11yService.isRunning.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
        PerfTopAppBar(scrollBehavior = scrollBehavior, navigationIcon = {
            PerfIconButton(
                imageVector = PerfIcon.ArrowBack,
                onClick = { mainVm.popPage() }
            )
        }, title = { Text(text = "无障碍授权") })
    }) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
        ) {
            Card(
                modifier = Modifier
                    .padding(horizontal = itemHorizontalPadding)
                    .fillMaxWidth(),
                colors = surfaceCardColors,
            ) {
                Text(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(start = 4.dp, top = 12.dp),
                    text = "基础",
                    style = MaterialTheme.typography.titleSmall
                )
                TextListItem(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(start = 8.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    list = listOf(
                        "授予「无障碍权限」",
                        "无障碍关闭后需重新授权"
                    ),
                )
                AnimatedBooleanContent(
                    targetState = writeSecureSettings || a11yRunning,
                    contentTrue = {
                        Text(
                            modifier = Modifier
                                .padding(horizontal = cardHorizontalPadding)
                                .padding(start = 8.dp, top = 4.dp),
                            text = "已持有「无障碍权限」可继续使用",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    contentFalse = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = cardHorizontalPadding),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = throttle { openA11ySettings() },
                            ) {
                                Text(
                                    text = "手动授权",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                )
                Text(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(start = 4.dp, top = 8.dp),
                    text = "增强",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextListItem(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(start = 8.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    list = listOf(
                        "授予「写入安全设置权限」",
                        "应用可自行控制开关无障碍",
                    ),
                )
                AnimatedBooleanContent(
                    targetState = writeSecureSettings,
                    contentTrue = {
                        Text(
                            modifier = Modifier
                                .padding(horizontal = cardHorizontalPadding)
                                .padding(start = 8.dp, top = 4.dp),
                            text = "已持有「写入安全设置权限」 优先使用此项",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    contentFalse = {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = cardHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ShizukuAuthButton()
                            TextButton(onClick = { vm.showCopyDlgFlow.value = true }) {
                                Text(
                                    text = "命令授权",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }

    ManualAuthDialog(
        commandText = epsoStartCommandText,
        show = showCopyDlg,
        onUpdateShow = { vm.showCopyDlgFlow.value = it },
    )
}

@Composable
private fun ShizukuAuthButton(
    modifier: Modifier = Modifier,
) {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<AuthA11yVm>()
    TextButton(
        modifier = modifier,
        onClick = throttle(vm.viewModelScope.launchAsFn(Dispatchers.IO) {
            mainVm.guardShizukuContext()
            if (writeSecureSettingsState.value) {
                toast("授权成功")
            }
        })
    ) {
        Text(
            text = "Shizuku 授权",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private val Int.appopsAllow get() = "appops set ${META.appId} ${AppOpsManagerHidden.opToName(this)} allow"
private val String.pmGrant get() = "pm grant ${META.appId} $this"

val epsoStartCommandText by lazy {
    val commandText = listOfNotNull(
        "set -euo pipefail",
        "echo '> start start.sh'",
        Manifest.permission.WRITE_SECURE_SETTINGS.pmGrant,
        Manifest_permission_GET_APP_OPS_STATS.pmGrant,
        if (AndroidTarget.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS.pmGrant else null,
        AppOpsManagerHidden.OP_POST_NOTIFICATION.appopsAllow,
        AppOpsManagerHidden.OP_SYSTEM_ALERT_WINDOW.appopsAllow,
        if (AndroidTarget.Q) AppOpsManagerHidden.OP_ACCESS_ACCESSIBILITY.appopsAllow else null,
        if (AndroidTarget.TIRAMISU) AppOpsManagerHidden.OP_ACCESS_RESTRICTED_SETTINGS.appopsAllow else null,
        if (AndroidTarget.UPSIDE_DOWN_CAKE) AppOpsManagerHidden.OP_FOREGROUND_SERVICE_SPECIAL_USE.appopsAllow else null,
        if (SafeAppOpsService.supportCreateA11yOverlay) AppOpsManagerHidden.OP_CREATE_ACCESSIBILITY_OVERLAY.appopsAllow else null,
        "sh ${shFolder.absolutePath}/expose.sh 1",
        "echo '> start.sh end'",
    ).joinToString("\n")
    val file = shFolder.resolve("start.sh")
    file.writeText(commandText)
    "adb shell sh ${file.absolutePath}"
}

@Composable
private fun TextListItem(
    list: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val lineHeightDp = LocalDensity.current.run { style.lineHeight.toDp() }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        list.forEach { text ->
            Row {
                Spacer(
                    modifier = Modifier
                        .padding(vertical = (lineHeightDp - 4.dp) / 2)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                        .size(4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = text, style = style)
            }
        }
    }
}
