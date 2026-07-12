package li.mofanx.epso.ui.expansion

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.mofanx.epso.R
import li.mofanx.epso.expansion.Match
import li.mofanx.epso.expansion.MatchGroup
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.ui.common.feedback.EmptyState
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.throttle
import java.io.File

@Serializable
data class MatchListRoute(
    val sourceFilePath: String = "",
) : NavKey

@Serializable
data class MatchEditorRoute(
    val sourceFilePath: String,
    val triggerToEdit: String = "",   // 空字符串 = 新建
) : NavKey

/**
 * 规则列表页
 *
 * - 按文件分组展示所有规则
 * - 支持新建规则（跳转 MatchEditorPage）
 * - 支持删除规则（长按菜单）
 * - 顶部 AppBar 显示总规则数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchListPage(route: MatchListRoute) {
    val mainVm = LocalMainViewModel.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val groups by MatchStore.groups.collectAsState()
    val matchDict by MatchStore.matchDict.collectAsState()
    val totalCount = remember(matchDict) { matchDict.values.distinct().size }

    val sourceFilePath = route.sourceFilePath
    var query by remember { mutableStateOf("") }
    var showSelectFileDialog by remember { mutableStateOf(false) }

    val filteredGroups = remember(groups, query, sourceFilePath) {
        val byFile = if (sourceFilePath.isEmpty()) {
            groups
        } else {
            groups.filter { it.sourceFile == sourceFilePath }
        }
        if (query.isBlank()) {
            byFile
        } else {
            byFile.map { g ->
                g.copy(
                    matches = g.matches.filter { m ->
                        val q = query.trim().lowercase()
                        m.allTriggers.any { it.lowercase().contains(q) } ||
                            m.regex.lowercase().contains(q) ||
                            m.replace.lowercase().contains(q) ||
                            (m.label?.lowercase()?.contains(q) == true)
                    }
                )
            }.filter { it.matches.isNotEmpty() }
        }
    }

    val filteredCount = filteredGroups.sumOf { it.matches.size }
    val workspaceDir = MatchStore.getWorkspaceDir()

    fun createAndNavigate(file: File) {
        scope.launch(Dispatchers.IO) {
            if (!file.exists()) MatchStore.createFile(file.nameWithoutExtension)
            withContext(Dispatchers.Main) {
                mainVm.navigatePage(MatchEditorRoute(file.absolutePath))
            }
        }
    }

    val onFabClick = throttle {
        when {
            sourceFilePath.isNotEmpty() -> createAndNavigate(File(sourceFilePath))
            groups.size == 1 -> createAndNavigate(File(groups.first().sourceFile))
            groups.isEmpty() -> createAndNavigate(workspaceDir.resolve("base.yml"))
            else -> showSelectFileDialog = true
        }
    }

    val title = if (sourceFilePath.isEmpty()) {
        stringResource(R.string.match_list_title_with_count, totalCount)
    } else {
        stringResource(R.string.match_list_title_with_count, filteredCount)
    }

    if (showSelectFileDialog) {
        TargetFileDialog(
            groups = groups,
            onDismiss = { showSelectFileDialog = false },
            onSelect = { file -> createAndNavigate(file) },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(title) },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = throttle { mainVm.popPage() },
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onFabClick) {
                PerfIcon(imageVector = PerfIcon.Add)
            }
        },
    ) { paddingValues ->
        if (groups.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(paddingValues),
                icon = PerfIcon.FormatListBulleted,
                title = stringResource(R.string.match_list_empty_title),
                subtitle = stringResource(R.string.match_list_empty_subtitle),
                primaryAction = stringResource(R.string.expansion_action_create_rule),
                onPrimaryAction = onFabClick,
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = itemHorizontalPadding,
                    vertical = itemHorizontalPadding / 2,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.action_search)) },
                        placeholder = { Text(stringResource(R.string.match_list_search_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = if (query.isNotEmpty()) {
                            { PerfIconButton(imageVector = PerfIcon.Close, onClick = { query = "" }) }
                        } else null,
                    )
                }

                if (filteredGroups.isEmpty()) {
                    item {
                        EmptyState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            title = stringResource(R.string.match_list_no_matches),
                            subtitle = stringResource(R.string.match_list_clear_search),
                            primaryAction = stringResource(R.string.action_clear),
                            onPrimaryAction = { query = "" },
                        )
                    }
                }

                filteredGroups.forEach { group ->
                    item {
                        GroupHeader(group = group)
                    }
                    items(
                        items = group.matches,
                        key = { m ->
                            "${group.sourceFile}::${m.trigger}::${m.regex}::${m.replace.take(20)}"
                        },
                    ) { match ->
                        MatchCard(
                            match = match,
                            group = group,
                            onEdit = {
                                mainVm.navigatePage(
                                    MatchEditorRoute(
                                        sourceFilePath = group.sourceFile,
                                        triggerToEdit = match.allTriggers.firstOrNull() ?: match.regex,
                                    )
                                )
                            },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    MatchStore.deleteMatch(File(group.sourceFile), match)
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 子组件
// ──────────────────────────────────────────────────────────────

@Composable
private fun GroupHeader(group: MatchGroup) {
    val fileName = File(group.sourceFile).name.ifEmpty { "未命名" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "${group.matches.size} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchCard(
    match: Match,
    group: MatchGroup,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDlg by remember { mutableStateOf(false) }

    if (showDeleteDlg) {
        AlertDialog(
            onDismissRequest = { showDeleteDlg = false },
            title = { Text("删除规则") },
            text = {
                val displayTrigger = match.allTriggers.firstOrNull()
                    ?: match.regex.take(30)
                Text("确认删除规则「$displayTrigger」？此操作不可撤销。")
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
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle { onEdit() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 触发词
                val triggerDisplay = if (match.isRegex) {
                    "正则: ${match.regex}"
                } else {
                    match.allTriggers.joinToString(" / ")
                }
                Text(
                    text = triggerDisplay,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 替换文本
                Text(
                    text = if (match.isForm) "表单: ${match.form}" else match.replace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标签：label / vars / word
                val chips = buildList {
                    if (match.label != null) add(match.label)
                    if (match.vars.isNotEmpty()) add("${match.vars.size} 变量")
                    if (match.word) add("单词边界")
                    if (match.propagateCase) add("大小写传播")
                }
                if (chips.isNotEmpty()) {
                    Text(
                        text = chips.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
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
                        text = { Text("编辑") },
                        leadingIcon = { PerfIcon(PerfIcon.Edit) },
                        onClick = { showMenu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
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
private fun TargetFileDialog(
    groups: List<MatchGroup>,
    onDismiss: () -> Unit,
    onSelect: (File) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.match_new_select_file_title)) },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                LazyColumn {
                    items(
                        items = groups,
                        key = { it.sourceFile },
                    ) { group ->
                        val file = remember(group.sourceFile) { File(group.sourceFile) }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onSelect(file)
                                onDismiss()
                            },
                        ) {
                            Text(text = file.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PerfIcon(
                imageVector = PerfIcon.FormatListBulleted,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "暂无规则",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "点击右下角 + 新建",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
