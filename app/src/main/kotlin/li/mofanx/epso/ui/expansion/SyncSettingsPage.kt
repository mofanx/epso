package li.mofanx.epso.ui.expansion

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.mofanx.epso.MainActivity
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.expansion.sync.ConflictStrategy
import li.mofanx.epso.expansion.sync.SyncConfig
import li.mofanx.epso.expansion.sync.SyncManager
import li.mofanx.epso.expansion.sync.SyncMethod
import li.mofanx.epso.expansion.sync.SyncResult
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.throttle
import li.mofanx.epso.util.toast

@Serializable
data object SyncSettingsRoute : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsPage() {
    val mainVm = LocalMainViewModel.current
    val activity = LocalActivity.current as MainActivity
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val store by storeFlow.collectAsState()

    // 本地编辑状态（保存时才写入 storeFlow）
    var methodStr by remember(store.syncMethod) { mutableStateOf(store.syncMethod) }
    var uri by remember(store.syncUri) { mutableStateOf(store.syncUri) }
    var username by remember(store.syncUsername) { mutableStateOf(store.syncUsername) }
    var password by remember(store.syncPassword) { mutableStateOf(store.syncPassword) }
    var conflictStr by remember(store.syncConflictStrategy) { mutableStateOf(store.syncConflictStrategy) }
    var wifiOnly by remember(store.syncWifiOnly) { mutableStateOf(store.syncWifiOnly) }
    var autoOnSave by remember(store.syncAutoOnSave) { mutableStateOf(store.syncAutoOnSave) }

    var showPassword by remember { mutableStateOf(false) }
    var methodMenuExpanded by remember { mutableStateOf(false) }
    var conflictMenuExpanded by remember { mutableStateOf(false) }

    // 操作状态
    var syncStatus by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    val currentMethod = runCatching { SyncMethod.valueOf(methodStr) }.getOrElse { SyncMethod.None }

    fun buildConfig() = SyncConfig(
        method = currentMethod,
        uri = uri,
        username = username,
        password = password,
        conflictStrategy = runCatching { ConflictStrategy.valueOf(conflictStr) }
            .getOrElse { ConflictStrategy.LastWriteWins },
        wifiOnly = wifiOnly,
        autoOnSave = autoOnSave,
    )

    fun saveSettings() {
        storeFlow.update {
            it.copy(
                syncMethod = methodStr,
                syncUri = uri,
                syncUsername = username,
                syncPassword = password,
                syncConflictStrategy = conflictStr,
                syncWifiOnly = wifiOnly,
                syncAutoOnSave = autoOnSave,
            )
        }
        toast("同步配置已保存")
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("同步设置") },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = throttle { mainVm.popPage() },
                    )
                },
                actions = {
                    TextButton(onClick = throttle { saveSettings() }) { Text("保存") }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2),
        ) {
            Spacer(Modifier.height(itemVerticalPadding))

            // ── 同步方式 ───────────────────────────────────────
            SyncSectionLabel("同步方式")

            ExposedDropdownMenuBox(
                expanded = methodMenuExpanded,
                onExpandedChange = { methodMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = methodDisplayName(currentMethod),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("同步方式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(methodMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = methodMenuExpanded,
                    onDismissRequest = { methodMenuExpanded = false },
                ) {
                    SyncMethod.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(methodDisplayName(m)) },
                            onClick = { methodStr = m.name; methodMenuExpanded = false },
                        )
                    }
                }
            }

            // ── 连接参数（根据方式显示） ─────────────────────────
            if (currentMethod != SyncMethod.None) {
                SyncSectionLabel(
                    when (currentMethod) {
                        SyncMethod.LocalFolder -> "本地文件夹路径"
                        SyncMethod.WebDav -> "WebDAV 配置"
                        else -> "连接配置"
                    }
                )

                when (currentMethod) {
                    SyncMethod.LocalFolder -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = uri,
                                onValueChange = { uri = it },
                                label = { Text("文件夹路径") },
                                placeholder = { Text("/sdcard/espanso/matches") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                        Text(
                            text = "输入绝对路径，或搭配 Syncthing 将该目录与其他设备同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SyncMethod.WebDav -> {
                        OutlinedTextField(
                            value = uri,
                            onValueChange = { uri = it },
                            label = { Text("WebDAV URL") },
                            placeholder = { Text("https://dav.example.com/remote.php/dav/files/user/") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("用户名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showPassword)
                                VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                PerfIconButton(
                                    imageVector = if (showPassword) PerfIcon.ToggleOn else PerfIcon.ToggleOff,
                                    onClick = { showPassword = !showPassword },
                                )
                            },
                        )
                    }
                    else -> {}
                }

                // ── 冲突策略 ───────────────────────────────────

                SyncSectionLabel("冲突处理")

                ExposedDropdownMenuBox(
                    expanded = conflictMenuExpanded,
                    onExpandedChange = { conflictMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = conflictDisplayName(conflictStr),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("冲突策略") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(conflictMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = conflictMenuExpanded,
                        onDismissRequest = { conflictMenuExpanded = false },
                    ) {
                        ConflictStrategy.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(conflictDisplayName(c.name)) },
                                onClick = { conflictStr = c.name; conflictMenuExpanded = false },
                            )
                        }
                    }
                }

                // ── 自动化选项 ─────────────────────────────────

                SyncSectionLabel("自动化")

                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = surfaceCardColors,
                ) {
                    Column(modifier = Modifier.padding(itemHorizontalPadding)) {
                        SyncToggleRow(
                            label = "仅 WiFi 同步",
                            description = "移动网络下不自动同步",
                            checked = wifiOnly,
                            onCheckedChange = { wifiOnly = it },
                        )
                        SyncToggleRow(
                            label = "保存后自动推送",
                            description = "规则有修改时自动推送到远端",
                            checked = autoOnSave,
                            onCheckedChange = { autoOnSave = it },
                        )
                    }
                }

                // ── 操作按钮 ───────────────────────────────────

                SyncSectionLabel("操作")

                // 测试连接
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isTesting = true
                            syncStatus = "测试连接中…"
                            scope.launch(Dispatchers.IO) {
                                val manager = SyncManager(buildConfig())
                                val ok = manager?.testConnection() ?: false
                                withContext(Dispatchers.Main) {
                                    syncStatus = if (ok) "✓ 连接成功" else "✗ 连接失败"
                                    isTesting = false
                                }
                            }
                        },
                        enabled = !isTesting && !isSyncing && uri.isNotBlank(),
                    ) {
                        Text(if (isTesting) "测试中…" else "测试连接")
                    }
                }

                // 手动推送/拉取/双向同步
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = !isTesting && !isSyncing && uri.isNotBlank()
                    TextButton(
                        onClick = {
                            isSyncing = true; syncStatus = "推送中…"
                            scope.launch(Dispatchers.IO) {
                                val r = SyncManager(buildConfig())?.push(MatchStore.getWorkspaceDir())
                                withContext(Dispatchers.Main) {
                                    syncStatus = r.toDisplay("推送")
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = enabled,
                    ) { Text("推送") }

                    TextButton(
                        onClick = {
                            isSyncing = true; syncStatus = "拉取中…"
                            scope.launch(Dispatchers.IO) {
                                val r = SyncManager(buildConfig())?.pull(MatchStore.getWorkspaceDir())
                                MatchStore.reload()
                                withContext(Dispatchers.Main) {
                                    syncStatus = r.toDisplay("拉取")
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = enabled,
                    ) { Text("拉取") }

                    TextButton(
                        onClick = {
                            isSyncing = true; syncStatus = "同步中…"
                            scope.launch(Dispatchers.IO) {
                                val r = SyncManager(buildConfig())?.sync(MatchStore.getWorkspaceDir())
                                MatchStore.reload()
                                withContext(Dispatchers.Main) {
                                    syncStatus = r.toDisplay("同步")
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = enabled,
                    ) { Text("双向同步") }
                }

                // 状态消息
                if (syncStatus.isNotEmpty()) {
                    Text(
                        text = syncStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (syncStatus.startsWith("✓") || syncStatus.contains("成功"))
                            MaterialTheme.colorScheme.primary
                        else if (syncStatus.startsWith("✗") || syncStatus.contains("失败"))
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 子组件
// ──────────────────────────────────────────────────────────────

@Composable
private fun SyncSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SyncToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ──────────────────────────────────────────────────────────────
// 工具函数
// ──────────────────────────────────────────────────────────────

private fun methodDisplayName(method: SyncMethod): String = when (method) {
    SyncMethod.None -> "不同步"
    SyncMethod.LocalFolder -> "本地文件夹"
    SyncMethod.WebDav -> "WebDAV"
}

private fun conflictDisplayName(name: String): String = when (name) {
    "LastWriteWins" -> "以最新修改为准"
    "KeepBoth" -> "保留两份（冲突文件加 .conflict 后缀）"
    else -> name
}

private fun SyncResult?.toDisplay(action: String): String = when (this) {
    is SyncResult.Success -> buildString {
        append("✓ $action 完成")
        if (pushed > 0) append("，上传 $pushed 个")
        if (pulled > 0) append("，下载 $pulled 个")
        if (conflicts > 0) append("，冲突 $conflicts 个")
    }
    is SyncResult.Failure -> "✗ $action 失败：$error"
    null -> "✗ 未配置同步方式"
}
