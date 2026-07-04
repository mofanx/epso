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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import kotlinx.serialization.Serializable
import li.mofanx.epso.expansion.MatchGroup
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.expansion.Var
import li.mofanx.epso.expansion.VarParams
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
data object GlobalVarsRoute : NavKey

/** 支持的变量类型列表（与 VarEvaluator 对齐） */
private val VAR_TYPES = listOf("echo", "date", "clipboard", "random", "choice")

/**
 * 全局变量管理页
 *
 * 全局变量写入 base.yml（第一个文件）的 global_vars 字段，
 * 对所有规则的 {{var_name}} 占位符生效。
 *
 * - 列表：名称 / 类型 / 参数预览
 * - FAB 新建全局变量
 * - 卡片点击编辑，三点菜单删除
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalVarsPage() {
    val mainVm = LocalMainViewModel.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val groups by MatchStore.groups.collectAsState()
    val globalVars by MatchStore.globalVars.collectAsState()

    // 全局变量写入第一个文件（通常为 base.yml）
    val targetGroup: MatchGroup? = groups.firstOrNull()
    val targetFile: File? = targetGroup?.let { File(it.sourceFile) }

    var showEditor by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf(-1) }  // -1 = 新建
    var editingVar by remember { mutableStateOf(Var()) }

    fun openEditor(index: Int, v: Var) {
        editingIndex = index
        editingVar = v
        showEditor = true
    }

    fun saveVar() {
        val file = targetFile ?: return
        scope.launch(Dispatchers.IO) {
            val current = globalVars.toMutableList()
            if (editingIndex < 0) {
                current.add(editingVar)
            } else {
                current[editingIndex] = editingVar
            }
            MatchStore.updateGlobalVars(file, current)
        }
        showEditor = false
    }

    fun deleteVar(index: Int) {
        val file = targetFile ?: return
        scope.launch(Dispatchers.IO) {
            val current = globalVars.toMutableList().apply { removeAt(index) }
            MatchStore.updateGlobalVars(file, current)
        }
    }

    if (showEditor) {
        VarEditorDialog(
            v = editingVar,
            isNew = editingIndex < 0,
            onValueChange = { editingVar = it },
            onSave = { saveVar() },
            onDismiss = { showEditor = false },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("全局变量 (${globalVars.size})") },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = throttle { mainVm.popPage() },
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = throttle {
                openEditor(-1, Var())
            }) {
                PerfIcon(imageVector = PerfIcon.Add)
            }
        },
    ) { paddingValues ->
        if (globalVars.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PerfIcon(
                        imageVector = PerfIcon.TextFields,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "暂无全局变量",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "全局变量对所有规则的 {{name}} 占位符生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = itemHorizontalPadding,
                    vertical = itemHorizontalPadding / 2,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = globalVars,
                    key = { idx, v -> "${idx}_${v.name}" },
                ) { index, v ->
                    VarCard(
                        v = v,
                        onEdit = { openEditor(index, v) },
                        onDelete = { deleteVar(index) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// VarCard
// ──────────────────────────────────────────────────────────────

@Composable
private fun VarCard(
    v: Var,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDlg by remember { mutableStateOf(false) }

    if (showDeleteDlg) {
        AlertDialog(
            onDismissRequest = { showDeleteDlg = false },
            title = { Text("删除变量") },
            text = { Text("确认删除全局变量「${v.name}」？") },
            confirmButton = {
                TextButton(onClick = { showDeleteDlg = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
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
                Text(
                    text = "{{${v.name}}}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = buildString {
                        append("类型：${v.type}")
                        val params = v.params
                        when (v.type) {
                            "echo" -> append("  值：${params.echo}")
                            "date" -> append("  格式：${params.format}")
                            "random" -> {
                                val range = "${params.min ?: 0}–${params.max ?: 100}"
                                append("  范围：$range")
                            }
                            "choice" -> append("  选项：${params.values.take(3).joinToString(", ")}")
                            else -> {}
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PerfIconButton(
                imageVector = PerfIcon.Delete,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = { showDeleteDlg = true },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// VarEditorDialog
// ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VarEditorDialog(
    v: Var,
    isNew: Boolean,
    onValueChange: (Var) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typeMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建变量" else "编辑变量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 变量名
                OutlinedTextField(
                    value = v.name,
                    onValueChange = { onValueChange(v.copy(name = it.trim())) },
                    label = { Text("变量名") },
                    placeholder = { Text("如：name、date_today") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 类型下拉
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = v.type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        VAR_TYPES.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    onValueChange(v.copy(type = type, params = VarParams()))
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                // 参数（根据类型展示不同输入框）
                when (v.type) {
                    "echo" -> OutlinedTextField(
                        value = v.params.echo ?: "",
                        onValueChange = { onValueChange(v.copy(params = v.params.copy(echo = it))) },
                        label = { Text("固定文本") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    "date" -> {
                        OutlinedTextField(
                            value = v.params.format ?: "%Y-%m-%d",
                            onValueChange = { onValueChange(v.copy(params = v.params.copy(format = it))) },
                            label = { Text("格式（strftime）") },
                            placeholder = { Text("%Y-%m-%d %H:%M:%S") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = if (v.params.offset == 0L) "" else v.params.offset.toString(),
                            onValueChange = { raw ->
                                onValueChange(v.copy(params = v.params.copy(offset = raw.toLongOrNull() ?: 0L)))
                            },
                            label = { Text("偏移秒数（可选，如 86400 = 明天）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    "random" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = v.params.min?.toString() ?: "0",
                            onValueChange = { n ->
                                onValueChange(v.copy(params = v.params.copy(min = n.toIntOrNull())))
                            },
                            label = { Text("最小值") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = v.params.max?.toString() ?: "100",
                            onValueChange = { n ->
                                onValueChange(v.copy(params = v.params.copy(max = n.toIntOrNull())))
                            },
                            label = { Text("最大值") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    "choice" -> OutlinedTextField(
                        value = v.params.values.joinToString("\n"),
                        onValueChange = { raw ->
                            val vals = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            onValueChange(v.copy(params = v.params.copy(values = vals)))
                        },
                        label = { Text("选项（每行一个）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    else -> Text(
                        "clipboard 类型无需额外参数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = v.name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
