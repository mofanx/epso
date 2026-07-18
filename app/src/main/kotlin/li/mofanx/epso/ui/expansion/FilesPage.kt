package li.mofanx.epso.ui.expansion

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    var directoryVersion by remember { mutableStateOf(0) }

    // 批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDlg by remember { mutableStateOf(false) }
    var showBatchCopyDlg by remember { mutableStateOf(false) }
    var showBatchMoveDlg by remember { mutableStateOf(false) }

    // 导入：选择 YAML 文件 → 复制到工作区
    fun importYaml() {
        scope.launch {
            val uri = activity.pickFile("application/octet-stream") ?: return@launch
            try {
                val cr = activity.contentResolver
                val displayName = cr.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                } ?: "imported.yml"

                val safeName = displayName.let {
                    if (it.endsWith(".yml") || it.endsWith(".yaml")) it else "$it.yml"
                }.let { File(it).name }.takeIf { it.isNotBlank() } ?: "imported.yml"
                val input = cr.openInputStream(uri) ?: return@launch
                withContext(Dispatchers.IO) {
                    MatchStore.importFile(safeName, input)
                }
                withContext(Dispatchers.Main) { toast("已导入 $safeName") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("导入失败：${e.message}") }
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
                        "${activity.packageName}.provider",
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
        CreateItemDlg(
            onDismiss = { showCreateDlg = false },
            onCreate = { name, isFolder ->
                showCreateDlg = false
                if (isFolder) {
                    scope.launch(Dispatchers.IO) {
                        MatchStore.createFolder(name)
                        withContext(Dispatchers.Main) { directoryVersion++ }
                    }
                } else {
                    scope.launch(Dispatchers.IO) {
                        MatchStore.createFile(name)
                    }
                }
            },
        )
    }

    var collapsedDirs by rememberSaveable { mutableStateOf(setOf<String>()) }
    val treeItems by produceState(
        initialValue = emptyList<FileTreeItem>(),
        groups, workspaceDir, collapsedDirs, directoryVersion,
    ) {
        value = withContext(Dispatchers.IO) {
            buildFileTreeItems(groups, workspaceDir, collapsedDirs)
        }
    }

    val selectedItems = remember(treeItems, selected) {
        treeItems.filter { selected.contains(it.id) }
    }
    val selectedFiles = remember(selectedItems) {
        selectedItems.filterIsInstance<FileTreeItem.File>().map { File(it.group.sourceFile) }
    }
    val selectedFolders = remember(selectedItems, workspaceDir) {
        selectedItems.filterIsInstance<FileTreeItem.Dir>().map { File(workspaceDir, it.path) }
    }
    val visibleIds = remember(treeItems) { treeItems.map { it.id } }
    val allSelected = remember(selected, treeItems) {
        treeItems.isNotEmpty() && selected.containsAll(visibleIds)
    }

    fun onSelect(item: FileTreeItem) {
        selected = if (selected.contains(item.id)) {
            val new = selected - item.id
            if (new.isEmpty()) selectionMode = false
            new
        } else {
            selected + item.id
        }
    }

    fun onToggleSelectAll(checked: Boolean) {
        selected = if (checked) {
            selected + visibleIds.toSet()
        } else {
            selected - visibleIds.toSet()
        }
        if (selected.isEmpty()) selectionMode = false
    }

    if (showBatchDeleteDlg) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDlg = false },
            title = { Text("删除确认") },
            text = { Text("确认删除 ${selected.size} 个文件/文件夹？删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteDlg = false
                        val items = selectedItems
                        scope.launch(Dispatchers.IO) {
                            items.forEach { item ->
                                when (item) {
                                    is FileTreeItem.File -> MatchStore.deleteFile(File(item.group.sourceFile))
                                    is FileTreeItem.Dir -> MatchStore.deleteFolder(File(workspaceDir, item.path))
                                }
                            }
                            withContext(Dispatchers.Main) {
                                selected = emptySet()
                                selectionMode = false
                                directoryVersion++
                            }
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton({ showBatchDeleteDlg = false }) { Text("取消") }
            },
        )
    }

    if (showBatchCopyDlg && selectedItems.isNotEmpty()) {
        val itemName = if (selectedItems.size > 1) {
            "选中的 ${selectedItems.size} 个文件/文件夹"
        } else {
            when (val item = selectedItems.first()) {
                is FileTreeItem.File -> File(item.group.sourceFile).name
                is FileTreeItem.Dir -> item.name
            }
        }
        TargetDirDialog(
            title = "批量复制",
            itemName = itemName,
            actionLabel = "复制",
            onDismiss = { showBatchCopyDlg = false },
            onConfirm = { targetDir ->
                showBatchCopyDlg = false
                scope.launch(Dispatchers.IO) {
                    selectedFiles.forEach { file ->
                        try {
                            MatchStore.copyFile(file, targetDir)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { toast("复制失败：${e.message}") }
                        }
                    }
                    selectedFolders.forEach { folder ->
                        try {
                            MatchStore.copyFolder(folder, targetDir)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { toast("复制失败：${e.message}") }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        selected = emptySet()
                        selectionMode = false
                        directoryVersion++
                    }
                }
            },
        )
    }

    if (showBatchMoveDlg && selectedItems.isNotEmpty()) {
        val itemName = if (selectedItems.size > 1) {
            "选中的 ${selectedItems.size} 个文件/文件夹"
        } else {
            when (val item = selectedItems.first()) {
                is FileTreeItem.File -> File(item.group.sourceFile).name
                is FileTreeItem.Dir -> item.name
            }
        }
        TargetDirDialog(
            title = "批量移动",
            itemName = itemName,
            actionLabel = "移动",
            onDismiss = { showBatchMoveDlg = false },
            onConfirm = { targetDir ->
                showBatchMoveDlg = false
                scope.launch(Dispatchers.IO) {
                    selectedFiles.forEach { file ->
                        if (workspaceDir.resolve(targetDir).resolve(file.name).absolutePath == file.absolutePath) return@forEach
                        try {
                            MatchStore.moveFile(file, targetDir)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { toast("移动失败：${e.message}") }
                        }
                    }
                    selectedFolders.forEach { folder ->
                        if (workspaceDir.resolve(targetDir).resolve(folder.name).absolutePath == folder.absolutePath) return@forEach
                        try {
                            MatchStore.moveFolder(folder, targetDir)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { toast("移动失败：${e.message}") }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        selected = emptySet()
                        selectionMode = false
                        directoryVersion++
                    }
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selectionMode) {
                PerfTopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = { Text("已选择 ${selected.size} 项") },
                    navigationIcon = {
                        PerfIconButton(
                            imageVector = PerfIcon.Close,
                            contentDescription = "取消选择",
                            onClick = {
                                selectionMode = false
                                selected = emptySet()
                            },
                        )
                    },
                    actions = {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { onToggleSelectAll(it) },
                        )
                    },
                )
            } else {
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
                        PerfIconButton(
                            imageVector = Icons.Outlined.SelectAll,
                            contentDescription = "批量选择",
                            onClickLabel = "进入批量选择模式",
                            onClick = { selectionMode = true },
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = throttle { showCreateDlg = true }) {
                    PerfIcon(imageVector = PerfIcon.Add)
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                BatchActionsBar(
                    selectedFiles = selectedFiles,
                    selectedFolders = selectedFolders,
                    onDelete = { showBatchDeleteDlg = true },
                    onCopy = { showBatchCopyDlg = true },
                    onMove = { showBatchMoveDlg = true },
                    onDownload = { scope.launch(Dispatchers.IO) { downloadFiles(activity, selectedFiles) } },
                    onShare = { scope.launch(Dispatchers.IO) { shareFiles(activity, selectedFiles) } },
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            if (treeItems.isEmpty()) {
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
                        items = treeItems,
                        key = { it.id },
                    ) { item ->
                        when (item) {
                            is FileTreeItem.File -> FileCard(
                                group = item.group,
                                modifier = Modifier.padding(start = (item.depth * 16).dp),
                                isSelectionMode = selectionMode,
                                isSelected = selected.contains(item.id),
                                onSelect = { onSelect(item) },
                                onClick = {
                                    mainVm.navigatePage(
                                        MatchListRoute(sourceFilePath = item.group.sourceFile)
                                    )
                                },
                                onAddRule = {
                                    mainVm.navigatePage(
                                        MatchEditorRoute(sourceFilePath = item.group.sourceFile)
                                    )
                                },
                                onEditYaml = {
                                    mainVm.navigatePage(
                                        YamlEditorRoute(sourceFilePath = item.group.sourceFile)
                                    )
                                },
                                onDelete = { file ->
                                    scope.launch(Dispatchers.IO) {
                                        MatchStore.deleteFile(file)
                                    }
                                },
                            )

                            is FileTreeItem.Dir -> {
                                val folder = File(workspaceDir, item.path)
                                FolderCard(
                                    name = item.name,
                                    collapsed = item.collapsed,
                                    depth = item.depth,
                                    count = item.count,
                                    isSelectionMode = selectionMode,
                                    isSelected = selected.contains(item.id),
                                    onSelect = { onSelect(item) },
                                    onClick = {
                                        collapsedDirs = if (item.collapsed) {
                                            collapsedDirs - item.path
                                        } else {
                                            collapsedDirs + item.path
                                        }
                                    },
                                    onCopy = { targetDir ->
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                MatchStore.copyFolder(folder, targetDir)
                                                withContext(Dispatchers.Main) { directoryVersion++ }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) { toast("复制失败：${e.message}") }
                                            }
                                        }
                                    },
                                    onMove = { targetDir ->
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                MatchStore.moveFolder(folder, targetDir)
                                                withContext(Dispatchers.Main) { directoryVersion++ }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) { toast("移动失败：${e.message}") }
                                            }
                                        }
                                    },
                                    onDelete = {
                                        scope.launch(Dispatchers.IO) {
                                            MatchStore.deleteFolder(folder)
                                            withContext(Dispatchers.Main) { directoryVersion++ }
                                        }
                                    },
                                )
                            }
                        }
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
// 批量操作工具栏
// ──────────────────────────────────────────────────────────────

@Composable
private fun BatchActionsBar(
    selectedFiles: List<File>,
    selectedFolders: List<File>,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    val fileOnlyEnabled = selectedFiles.isNotEmpty() && selectedFolders.isEmpty()
    val hasSelection = selectedFiles.isNotEmpty() || selectedFolders.isNotEmpty()

    BottomAppBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon = PerfIcon.Delete,
                label = "删除",
                enabled = hasSelection,
                isDestructive = true,
                onClick = onDelete,
            )
            ActionButton(
                icon = PerfIcon.ContentCopy,
                label = "复制",
                enabled = hasSelection,
                onClick = onCopy,
            )
            ActionButton(
                icon = PerfIcon.DriveFileMove,
                label = "移动",
                enabled = hasSelection,
                onClick = onMove,
            )
            ActionButton(
                icon = PerfIcon.Download,
                label = "下载",
                enabled = fileOnlyEnabled,
                onClick = onDownload,
            )
            ActionButton(
                icon = PerfIcon.Share,
                label = "分享",
                enabled = fileOnlyEnabled,
                onClick = onShare,
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isDestructive: Boolean = false,
) {
    val textColor = if (enabled) {
        if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PerfIconButton(
            imageVector = icon,
            enabled = enabled,
            colors = if (isDestructive) {
                IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                )
            } else {
                IconButtonDefaults.iconButtonColors()
            },
            contentDescription = label,
            onClick = throttle(onClick),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

private suspend fun downloadFiles(activity: MainActivity, files: List<File>) {
    if (files.isEmpty()) {
        withContext(Dispatchers.Main) { toast("未选择文件") }
        return
    }
    try {
        val uri = withContext(Dispatchers.Main) { activity.pickDirectory() } ?: return
        var success = 0
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(activity, uri) ?: return@withContext
            files.forEach { file ->
                root.findFile(file.name)?.delete()
                val dest = root.createFile("application/octet-stream", file.name) ?: return@forEach
                activity.contentResolver.openOutputStream(dest.uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } ?: return@forEach
                success++
            }
        }
        withContext(Dispatchers.Main) { toast("已下载 $success 个文件") }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { toast("下载失败：${e.message}") }
    }
}

private suspend fun shareFiles(activity: MainActivity, files: List<File>) {
    if (files.isEmpty()) {
        withContext(Dispatchers.Main) { toast("未选择文件") }
        return
    }
    try {
        val cacheDir = activity.cacheDir.resolve("share").also { it.mkdirs() }
        val uris = ArrayList<Uri>(files.size)
        withContext(Dispatchers.IO) {
            files.forEach { file ->
                val cacheFile = cacheDir.resolve(file.name)
                file.inputStream().use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                uris.add(
                    androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.provider",
                        cacheFile,
                    )
                )
            }
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            activity.startActivity(Intent.createChooser(intent, "分享 ${files.size} 个文件"))
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { toast("分享失败：${e.message}") }
    }
}

// ──────────────────────────────────────────────────────────────
// 子组件
// ──────────────────────────────────────────────────────────────

@Composable
private fun FileCard(
    group: MatchGroup,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onAddRule: () -> Unit,
    onEditYaml: () -> Unit,
    onDelete: (File) -> Unit,
) {
    val file = remember(group.sourceFile) { File(group.sourceFile) }
    val fileName = file.name
    val isYaml = file.extension.equals("yml", ignoreCase = true) || file.extension.equals("yaml", ignoreCase = true)
    val activity = LocalActivity.current as MainActivity
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDlg by remember { mutableStateOf(false) }
    var showCopyDlg by remember { mutableStateOf(false) }
    var showMoveDlg by remember { mutableStateOf(false) }

    if (showCopyDlg) {
        TargetDirDialog(
            title = "复制文件",
            itemName = fileName,
            actionLabel = "复制",
            onDismiss = { showCopyDlg = false },
            onConfirm = { targetDir ->
                showCopyDlg = false
                scope.launch(Dispatchers.IO) {
                    try {
                        MatchStore.copyFile(file, targetDir)
                        withContext(Dispatchers.Main) { toast("已复制") }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { toast("复制失败：${e.message}") }
                    }
                }
            },
        )
    }

    if (showMoveDlg) {
        TargetDirDialog(
            title = "移动文件",
            itemName = fileName,
            actionLabel = "移动",
            onDismiss = { showMoveDlg = false },
            onConfirm = { targetDir ->
                showMoveDlg = false
                scope.launch(Dispatchers.IO) {
                    try {
                        MatchStore.moveFile(file, targetDir)
                        withContext(Dispatchers.Main) { toast("已移动") }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { toast("移动失败：${e.message}") }
                    }
                }
            },
        )
    }

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
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle {
            if (isSelectionMode) {
                onSelect()
            } else if (isYaml) {
                onClick()
            } else {
                toast("非 YAML 规则文件，无法编辑")
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                )
            }
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
                    text = if (isYaml) {
                        "${group.matches.size} 条规则" +
                            if (group.globalVars.isNotEmpty()) "，${group.globalVars.size} 个全局变量" else ""
                    } else {
                        "非 YAML 规则文件"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isSelectionMode) {
                Box {
                    PerfIconButton(
                        imageVector = PerfIcon.MoreVert,
                        onClick = { showMenu = true },
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (isYaml) {
                            DropdownMenuItem(
                                text = { Text("编辑 YAML") },
                                leadingIcon = { PerfIcon(PerfIcon.Edit) },
                                onClick = { showMenu = false; onEditYaml() },
                            )
                            DropdownMenuItem(
                                text = { Text("新增规则") },
                                leadingIcon = { PerfIcon(PerfIcon.Add) },
                                onClick = { showMenu = false; onAddRule() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { PerfIcon(PerfIcon.ContentCopy) },
                            onClick = { showMenu = false; showCopyDlg = true },
                        )
                        DropdownMenuItem(
                            text = { Text("移动") },
                            leadingIcon = { PerfIcon(PerfIcon.DriveFileMove) },
                            onClick = { showMenu = false; showMoveDlg = true },
                        )
                        DropdownMenuItem(
                            text = { Text("分享") },
                            leadingIcon = { PerfIcon(PerfIcon.Share) },
                            onClick = {
                                showMenu = false
                                scope.launch(Dispatchers.IO) { shareFile(activity, file) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("下载") },
                            leadingIcon = { PerfIcon(PerfIcon.Download) },
                            onClick = {
                                showMenu = false
                                scope.launch(Dispatchers.IO) { downloadFile(activity, file) }
                            },
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
}

@Composable
private fun FolderCard(
    name: String,
    collapsed: Boolean,
    depth: Int,
    count: Int,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onCopy: (String) -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDlg by remember { mutableStateOf(false) }
    var showCopyDlg by remember { mutableStateOf(false) }
    var showMoveDlg by remember { mutableStateOf(false) }

    if (showCopyDlg) {
        TargetDirDialog(
            title = "复制文件夹",
            itemName = name,
            actionLabel = "复制",
            onDismiss = { showCopyDlg = false },
            onConfirm = { targetDir ->
                showCopyDlg = false
                onCopy(targetDir)
            },
        )
    }

    if (showMoveDlg) {
        TargetDirDialog(
            title = "移动文件夹",
            itemName = name,
            actionLabel = "移动",
            onDismiss = { showMoveDlg = false },
            onConfirm = { targetDir ->
                showMoveDlg = false
                onMove(targetDir)
            },
        )
    }

    if (showDeleteDlg) {
        AlertDialog(
            onDismissRequest = { showDeleteDlg = false },
            title = { Text("删除文件夹") },
            text = {
                Text("确认删除文件夹「$name」？\n${if (count > 0) "该文件夹包含 $count 个文件/子目录，" else ""}删除后不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDlg = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDlg = false }) { Text("取消") }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle { if (isSelectionMode) onSelect() else onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                )
            } else {
                PerfIconButton(
                    imageVector = if (collapsed) PerfIcon.KeyboardArrowRight else PerfIcon.KeyboardArrowDown,
                    contentDescription = if (collapsed) "展开" else "折叠",
                    onClick = { onClick() },
                )
            }
            PerfIcon(
                imageVector = PerfIcon.Folder,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = name,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (count > 0 && !isSelectionMode) {
                Text(
                    text = "($count 个文件)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (!isSelectionMode) {
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
                            text = { Text("复制") },
                            leadingIcon = { PerfIcon(PerfIcon.ContentCopy) },
                            onClick = { showMenu = false; showCopyDlg = true },
                        )
                        DropdownMenuItem(
                            text = { Text("移动") },
                            leadingIcon = { PerfIcon(PerfIcon.DriveFileMove) },
                            onClick = { showMenu = false; showMoveDlg = true },
                        )
                        DropdownMenuItem(
                            text = { Text("删除文件夹", color = MaterialTheme.colorScheme.error) },
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
}

@Composable
private fun TargetDirDialog(
    title: String,
    itemName: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var targetDir by remember { mutableStateOf("") }
    val normalizedDir = targetDir.trimEnd('/')
    val isValid = normalizedDir.isEmpty() ||
            normalizedDir.matches(Regex("^[a-zA-Z0-9_\\-]+(/[a-zA-Z0-9_\\-]+)*$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将「$itemName」${actionLabel}到目标文件夹：")
                OutlinedTextField(
                    value = targetDir,
                    onValueChange = { targetDir = it },
                    label = { Text("目标文件夹路径") },
                    placeholder = { Text("留空=根目录，如 packages/work") },
                    singleLine = true,
                    isError = normalizedDir.isNotEmpty() && !isValid,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onConfirm(normalizedDir) },
                enabled = isValid,
            ) { Text(actionLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private sealed class FileTreeItem {
    abstract val id: String
    abstract val path: String
    abstract val depth: Int

    data class File(
        override val id: String,
        override val path: String,
        val group: MatchGroup,
        override val depth: Int,
    ) : FileTreeItem()

    data class Dir(
        override val path: String,
        val name: String,
        val collapsed: Boolean,
        val count: Int,
        override val depth: Int,
    ) : FileTreeItem() {
        override val id: String get() = path
    }
}

private data class TreeNode(
    val path: String,
    val name: String,
    val group: MatchGroup? = null,
    val children: MutableList<TreeNode> = mutableListOf(),
) {
    fun countFiles(): Int = if (group != null) 1 else children.sumOf { it.countFiles() }
}

private fun buildFileTreeItems(
    groups: List<MatchGroup>,
    workspaceDir: File,
    collapsedDirs: Set<String>,
): List<FileTreeItem> {
    val groupMap = groups.associateBy { it.sourceFile }
    val allEntries = workspaceDir.walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { it != workspaceDir && !it.name.startsWith(".") }
        .sortedBy { it.path }

    val root = TreeNode(path = "", name = "")
    for (entry in allEntries) {
        val relative = runCatching { entry.toRelativeString(workspaceDir) }
            .getOrDefault(entry.name)
            .trim(File.separatorChar, '/')
            .ifEmpty { entry.name }
        val segments = relative.split(File.separatorChar, '/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue

        val group = if (entry.isDirectory) {
            null
        } else {
            groupMap[entry.absolutePath] ?: MatchGroup().apply { sourceFile = entry.absolutePath }
        }

        var current = root
        val pathParts = mutableListOf<String>()
        for ((index, segment) in segments.withIndex()) {
            pathParts.add(segment)
            val path = pathParts.joinToString("/")
            val isLast = index == segments.lastIndex
            val existing = current.children.find { it.path == path }
            if (existing != null) {
                current = existing
                if (isLast && group != null) {
                    // YAML file exists in a directory that was already created as a dir node; replace it with the file node
                    val fileNode = TreeNode(path = path, name = segment, group = group)
                    current.children[current.children.indexOf(existing)] = fileNode
                    current = fileNode
                }
            } else if (isLast) {
                val node = TreeNode(path = path, name = segment, group = group)
                current.children.add(node)
                current = node
            } else {
                val node = TreeNode(path = path, name = segment)
                current.children.add(node)
                current = node
            }
        }
    }
    val result = mutableListOf<FileTreeItem>()
    flattenTree(root, collapsedDirs, result)
    return result
}

private fun flattenTree(
    node: TreeNode,
    collapsedDirs: Set<String>,
    result: MutableList<FileTreeItem>,
) {
    for (child in node.children) {
        if (child.group != null) {
            result.add(FileTreeItem.File(child.group.sourceFile, child.path, child.group, child.path.count { it == '/' }))
        } else {
            if (child.path.isNotEmpty()) {
                result.add(FileTreeItem.Dir(child.path, child.name, child.path in collapsedDirs, child.countFiles(), child.path.count { it == '/' }))
            }
            if (child.path !in collapsedDirs) {
                flattenTree(child, collapsedDirs, result)
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
    java.util.zip.ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
        // 递归打包整个工作区目录，保留相对路径（含 packages/ 子目录）
        srcDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val entryName = file.relativeTo(srcDir).path
            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}

private suspend fun shareFile(activity: MainActivity, file: File) {
    try {
        val cacheDir = activity.cacheDir.resolve("share").also { it.mkdirs() }
        val cacheFile = cacheDir.resolve(file.name)
        file.inputStream().use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.provider",
            cacheFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            activity.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { toast("分享失败：${e.message}") }
    }
}

private suspend fun downloadFile(activity: MainActivity, file: File) {
    try {
        val uri = withContext(Dispatchers.Main) { activity.pickDirectory() } ?: return
        var success = false
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(activity, uri) ?: return@withContext
            root.findFile(file.name)?.delete()
            val dest = root.createFile("application/octet-stream", file.name) ?: return@withContext
            val output = activity.contentResolver.openOutputStream(dest.uri) ?: return@withContext
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            success = true
        }
        if (success) {
            withContext(Dispatchers.Main) { toast("已下载 ${file.name}") }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { toast("下载失败：${e.message}") }
    }
}

@Composable
private fun CreateItemDlg(
    onDismiss: () -> Unit,
    onCreate: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isFolder by remember { mutableStateOf(false) }
    val isValid = name.matches(Regex("^[a-zA-Z0-9_\\-]+(/[a-zA-Z0-9_\\-]+)*$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = { isFolder = false },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = if (!isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text("文件") }
                    TextButton(
                        onClick = { isFolder = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = if (isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text("文件夹") }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("名称") },
                    placeholder = {
                        if (isFolder) {
                            Text("如：folder 或 sub/folder")
                        } else {
                            Text("如：email 或 sub/email")
                        }
                    },
                    singleLine = true,
                    isError = name.isNotEmpty() && !isValid,
                    supportingText = if (name.isNotEmpty() && !isValid) {
                        { Text("只允许字母、数字、下划线、连字符和 '/' 子目录分隔") }
                    } else {
                        { Text("允许使用 '/' 表示子目录") }
                    },
                )
                Text(
                    text = if (isFolder) "文件夹将创建为 $name/" else "文件将保存为 $name.yml",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, isFolder) },
                enabled = isValid,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
