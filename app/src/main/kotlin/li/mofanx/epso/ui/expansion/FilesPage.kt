package li.mofanx.epso.ui.expansion

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.mofanx.epso.MainActivity
import li.mofanx.epso.expansion.MatchGroup
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.expansion.SyncSettingsRoute
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.throttle
import li.mofanx.epso.util.toast
import java.io.File

@Serializable
data object FilesRoute : NavKey

/**
 * 文件管理页
 *
 * - 展示工作区所有 YAML 文件，每个文件显示规则数
 * - 点击文件 → 进入规则列表（筛选该文件的规则）
 * - FAB 新建文件（弹对话框输入名称）
 * - 三点菜单：删除文件（含确认对话框）
 * - 底部显示工作区路径（帮助用户理解文件存放位置）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesPage() {
    val mainVm = LocalMainViewModel.current
    val activity = LocalActivity.current as MainActivity
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val groups by MatchStore.groups.collectAsState()
    val workspaceDir = MatchStore.getWorkspaceDir()

    var showCreateDlg by remember { mutableStateOf(false) }

    // 导入：选择 YAML 文件 → 复制到工作区
    fun importYaml() {
        scope.launch {
            val uri = activity.pickFile("application/octet-stream") ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    val cr = activity.contentResolver
                    val displayName = cr.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                    } ?: "imported.yml"

                    val safeName = displayName.let {
                        if (it.endsWith(".yml") || it.endsWith(".yaml")) it else "$it.yml"
                    }
                    val destFile = workspaceDir.resolve(safeName)
                    cr.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    MatchStore.reload()
                    withContext(Dispatchers.Main) { toast("已导入 $safeName") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("导入失败：${e.message}") }
                }
            }
        }
    }

    // 导出：将整个工作区打包为 zip 并分享
    fun exportWorkspace() {
        scope.launch(Dispatchers.IO) {
            try {
                val zipFile = activity.cacheDir.resolve("epso_matches_export.zip")
                zipWorkspaceDir(workspaceDir, zipFile)
                withContext(Dispatchers.Main) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        zipFile,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "导出规则"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("导出失败：${e.message}") }
            }
        }
    }

    if (showCreateDlg) {
        CreateFileDlg(
            onDismiss = { showCreateDlg = false },
            onCreate = { name ->
                showCreateDlg = false
                scope.launch(Dispatchers.IO) {
                    MatchStore.createFile(name)
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("文件管理 (${groups.size})") },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = throttle { mainVm.popPage() },
                    )
                },
                actions = {
                    PerfIconButton(
                        imageVector = PerfIcon.Autorenew,
                        contentDescription = "同步设置",
                        onClickLabel = "前往同步设置",
                        onClick = throttle { mainVm.navigatePage(SyncSettingsRoute) },
                    )
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowDownward,
                        contentDescription = "导入 YAML",
                        onClickLabel = "从文件导入规则",
                        onClick = throttle { importYaml() },
                    )
                    PerfIconButton(
                        imageVector = PerfIcon.Share,
                        contentDescription = "导出",
                        onClickLabel = "导出规则为 zip",
                        onClick = throttle { exportWorkspace() },
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = throttle { showCreateDlg = true }) {
                PerfIcon(imageVector = PerfIcon.Add)
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PerfIcon(
                            imageVector = PerfIcon.Layers,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "暂无 YAML 文件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "点击右下角 + 新建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = itemHorizontalPadding,
                        vertical = itemHorizontalPadding / 2,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = groups,
                        key = { it.sourceFile },
                    ) { group ->
                        FileCard(
                            group = group,
                            onAddRule = {
                                mainVm.navigatePage(
                                    MatchEditorRoute(sourceFilePath = group.sourceFile)
                                )
                            },
                            onDelete = { file ->
                                scope.launch(Dispatchers.IO) {
                                    MatchStore.deleteFile(file)
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }

            // 工作区路径提示
            Text(
                text = "工作区：${workspaceDir.absolutePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(
                    horizontal = itemHorizontalPadding,
                    vertical = 8.dp,
                ),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 子组件
// ──────────────────────────────────────────────────────────────

@Composable
private fun FileCard(
    group: MatchGroup,
    onAddRule: () -> Unit,
    onDelete: (File) -> Unit,
) {
    val file = remember(group.sourceFile) { File(group.sourceFile) }
    val fileName = file.name
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDlg by remember { mutableStateOf(false) }

    if (showDeleteDlg) {
        AlertDialog(
            onDismissRequest = { showDeleteDlg = false },
            title = { Text("删除文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("确认删除文件「$fileName」？")
                    if (group.matches.isNotEmpty()) {
                        Text(
                            "⚠ 该文件包含 ${group.matches.size} 条规则，删除后不可恢复。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDlg = false
                    onDelete(file)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDlg = false }) { Text("取消") }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PerfIcon(
                imageVector = PerfIcon.Layers,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${group.matches.size} 条规则" +
                            if (group.globalVars.isNotEmpty()) "，${group.globalVars.size} 个全局变量" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                PerfIconButton(
                    imageVector = PerfIcon.MoreVert,
                    onClick = { showMenu = true },
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("新增规则") },
                        leadingIcon = { PerfIcon(PerfIcon.Add) },
                        onClick = { showMenu = false; onAddRule() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除文件", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            PerfIcon(PerfIcon.Delete, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { showMenu = false; showDeleteDlg = true },
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 工具函数
// ──────────────────────────────────────────────────────────────

/**
 * 将 [srcDir] 下所有 .yml/.yaml 文件打包到 [destZip]
 */
private fun zipWorkspaceDir(srcDir: File, destZip: File) {
    val files = srcDir.listFiles { f ->
        f.isFile && (f.extension == "yml" || f.extension == "yaml")
    } ?: return

    java.util.zip.ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
        for (file in files) {
            zos.putNextEntry(java.util.zip.ZipEntry(file.name))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}

@Composable
private fun CreateFileDlg(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val isValid = name.matches(Regex("[a-zA-Z0-9_\\-]+"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建规则文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("文件名（不含扩展名）") },
                    placeholder = { Text("如：email 或 shortcuts") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !isValid,
                    supportingText = if (name.isNotEmpty() && !isValid) {
                        { Text("只允许字母、数字、下划线和连字符") }
                    } else null,
                )
                Text(
                    text = "文件将保存为 $name.yml",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = isValid,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
