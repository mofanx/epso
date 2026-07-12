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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
    var directoryVersion by remember { mutableStateOf(0) }

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
        CreateItemDlg(
            onDismiss = { showCreateDlg = false },
            onCreate = { name, isFolder ->
                showCreateDlg = false
                if (isFolder) {
                    scope.launch(Dispatchers.IO) {
                        File(workspaceDir, name).mkdirs()
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
            var collapsedDirs by rememberSaveable { mutableStateOf(setOf<String>()) }
            val treeItems by produceState(
                initialValue = emptyList<FileTreeItem>(),
                groups, workspaceDir, collapsedDirs, directoryVersion,
            ) {
                value = withContext(Dispatchers.IO) {
                    buildFileTreeItems(groups, workspaceDir, collapsedDirs)
                }
            }

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
                                onDelete = { file ->
                                    scope.launch(Dispatchers.IO) {
                                        MatchStore.deleteFile(file)
                                    }
                                },
                            )

                            is FileTreeItem.Dir -> FolderCard(
                                name = item.name,
                                collapsed = item.collapsed,
                                depth = item.depth,
                                count = item.count,
                                onClick = {
                                    collapsedDirs = if (item.collapsed) {
                                        collapsedDirs - item.path
                                    } else {
                                        collapsedDirs + item.path
                                    }
                                },
                            )
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
// 子组件
// ──────────────────────────────────────────────────────────────

@Composable
private fun FileCard(
    group: MatchGroup,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
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
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle { onClick() },
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

@Composable
private fun FolderCard(
    name: String,
    collapsed: Boolean,
    depth: Int,
    count: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (count > 0) {
                Text(
                    text = "($count 个文件)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            PerfIcon(
                imageVector = if (collapsed) PerfIcon.KeyboardArrowRight else PerfIcon.KeyboardArrowDown,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        .filter { it != workspaceDir && (it.isDirectory || it.extension.equals("yml", ignoreCase = true) || it.extension.equals("yaml", ignoreCase = true)) }
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
