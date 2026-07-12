package li.mofanx.epso.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import li.mofanx.epso.MainActivity
import li.mofanx.epso.permission.foregroundServiceSpecialUseState
import li.mofanx.epso.permission.notificationState
import li.mofanx.epso.permission.requiredPermission
import li.mofanx.epso.permission.shizukuGrantedState
import li.mofanx.epso.service.HttpService
import li.mofanx.epso.shizuku.shizukuContextFlow
import li.mofanx.epso.shizuku.updateBinderMutex
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.component.AuthCard
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.component.SettingItem
import li.mofanx.epso.ui.component.TextSwitch
import li.mofanx.epso.ui.component.autoFocus
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.share.asMutableState
import li.mofanx.epso.ui.style.EmptyHeight
import li.mofanx.epso.ui.style.itemPadding
import li.mofanx.epso.ui.style.titleItemPadding
import li.mofanx.epso.util.launchAsFn
import li.mofanx.epso.util.throttle
import li.mofanx.epso.util.toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment

@Serializable
data object AdvancedRoute : NavKey

@Composable
fun AdvancedPage() {
    val context = LocalActivity.current as MainActivity
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<AdvancedVm>()
    val store by storeFlow.collectAsState()

    var showEditPortDlg by vm.showEditPortDlgFlow.asMutableState()
    if (showEditPortDlg) {
        val portRange = remember { 1000 to 65535 }
        val placeholderText = remember { "请输入 ${portRange.first}-${portRange.second} 的整数" }
        var value by remember {
            mutableStateOf(store.httpServerPort.toString())
        }
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(text = "服务端口") },
            text = {
                OutlinedTextField(
                    value = value,
                    placeholder = { Text(text = placeholderText) },
                    onValueChange = {
                        value = it.filter { c -> c.isDigit() }.take(5)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().autoFocus(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            text = "${value.length} / 5",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                        )
                    },
                )
            },
            onDismissRequest = { showEditPortDlg = false },
            confirmButton = {
                TextButton(
                    enabled = value.isNotEmpty(),
                    onClick = {
                        val newPort = value.toIntOrNull()
                        if (newPort == null || !(portRange.first <= newPort && newPort <= portRange.second)) {
                            toast(placeholderText)
                            return@TextButton
                        }
                        showEditPortDlg = false
                        if (newPort != store.httpServerPort) {
                            storeFlow.value = store.copy(httpServerPort = newPort)
                            toast("更新成功")
                        }
                    }
                ) { Text(text = "确认") }
            },
            dismissButton = {
                TextButton(onClick = { showEditPortDlg = false }) { Text(text = "取消") }
            }
        )
    }

    var showApiTokenDlg by remember { mutableStateOf(false) }
    if (showApiTokenDlg) {
        val token = HttpService.ensureApiToken()
        AlertDialog(
            title = { Text("局域网 API 令牌") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请求需要携带 Authorization: Bearer <令牌>。请勿分享给不受信任的设备。")
                    OutlinedTextField(
                        value = token,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("接口：GET /api/v1/status、GET /api/v1/rules；POST /api/v1/files、/rules、/reload、/expansion")
                }
            },
            onDismissRequest = { showApiTokenDlg = false },
            confirmButton = {
                TextButton(onClick = { HttpService.regenerateApiToken() }) { Text("重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { showApiTokenDlg = false }) { Text("关闭") }
            },
        )
    }

    var showShizukuState by vm.showShizukuStateFlow.asMutableState()
    if (showShizukuState) {
        val onDismissRequest = { showShizukuState = false }
        AlertDialog(
            title = { Text(text = "授权状态") },
            text = {
                val states = shizukuContextFlow.collectAsState().value.states
                Column {
                    states.forEach { (name, value) ->
                        Text(
                            text = name,
                            textDecoration = if (value != null) null else TextDecoration.LineThrough,
                        )
                    }
                }
            },
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(text = "我知道了") }
            },
        )
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    PerfIconButton(imageVector = PerfIcon.ArrowBack, onClick = {
                        mainVm.popPage()
                    })
                },
                title = { Text(text = "高级设置") },
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .titleItemPadding(showTop = false),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Shizuku",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PerfIcon(
                    modifier = Modifier
                        .clickable(onClickLabel = "打开 Shizuku 状态弹窗", onClick = throttle {
                            showShizukuState = true
                        }),
                    imageVector = PerfIcon.Api,
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = "Shizuku 状态",
                )
            }
            val shizukuGranted by shizukuGrantedState.stateFlow.collectAsState()
            androidx.compose.animation.AnimatedVisibility(store.enableShizuku && !shizukuGranted) {
                AuthCard(
                    title = "未授权",
                    subtitle = "点击授权以优化体验",
                    onAuthClick = { mainVm.requestShizuku() }
                )
            }
            TextSwitch(
                title = "启用优化",
                subtitle = "提升权限优化体验",
                checked = store.enableShizuku,
                suffixIcon = {
                    if (updateBinderMutex.state.collectAsState().value) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                onCheckedChange = { mainVm.switchEnableShizuku(it) },
                onClick = null,
            )

            val server by HttpService.httpServerFlow.collectAsState()
            val httpServerRunning = server != null
            val localNetworkIps by HttpService.localNetworkIpsFlow.collectAsState()

            Text(
                text = "HTTP",
                modifier = Modifier.titleItemPadding(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextSwitch(
                title = "HTTP服务",
                subtitle = "在浏览器下连接调试",
                checked = httpServerRunning,
                onCheckedChange = throttle(fn = vm.viewModelScope.launchAsFn<Boolean> {
                    if (it) {
                        requiredPermission(context, foregroundServiceSpecialUseState)
                        requiredPermission(context, notificationState)
                        HttpService.start()
                    } else {
                        HttpService.stop()
                    }
                })
            )
            androidx.compose.animation.AnimatedVisibility(visible = httpServerRunning) {
                Column(modifier = Modifier.itemPadding()) {
                    Text(text = "点击下方链接即可连接")
                    val localUrl = "http://127.0.0.1:${store.httpServerPort}"
                    Text(
                        text = localUrl,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = throttle {
                            mainVm.openUrl(localUrl)
                        }),
                    )
                    localNetworkIps.forEach { host ->
                        val lanUrl = "http://${host}:${store.httpServerPort}"
                        Text(
                            text = lanUrl,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = throttle {
                                mainVm.openUrl(lanUrl)
                            })
                        )
                    }
                }
            }
            SettingItem(
                title = "服务端口",
                subtitle = store.httpServerPort.toString(),
                imageVector = PerfIcon.Edit,
                onClickLabel = "编辑服务端口",
                onClick = { showEditPortDlg = true }
            )
            SettingItem(
                title = "API 访问令牌",
                subtitle = if (httpServerRunning) "查看、重新生成令牌和接口说明" else "启动 HTTP 服务后可通过局域网使用",
                imageVector = PerfIcon.Api,
                onClickLabel = "查看 API 访问令牌",
                onClick = { showApiTokenDlg = true }
            )

            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}
