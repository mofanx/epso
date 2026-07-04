package li.mofanx.epso.ui.expansion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import li.mofanx.epso.expansion.Match
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.expansion.Var
import li.mofanx.epso.expansion.VarParams
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.util.throttle
import java.io.File

/**
 * 规则编辑页
 *
 * 同时承担「新建」和「编辑」功能：
 * - [route].triggerToEdit 为空 → 新建模式
 * - 非空 → 编辑模式，从 MatchStore 加载对应规则
 *
 * 支持字段：
 * - trigger / triggers（多触发词用换行分隔）
 * - replace（支持 {{var}} 和 $|$ 语法）
 * - regex（正则触发）
 * - word / left_word / right_word
 * - propagate_case
 * - label（备注）
 *
 * 变量（vars）管理和 form 字段留待后续迭代（Phase 4/6）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchEditorPage(route: MatchEditorRoute) {
    val mainVm = LocalMainViewModel.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val sourceFile = remember { File(route.sourceFilePath) }
    val isEdit = route.triggerToEdit.isNotEmpty()

    // ── 编辑状态 ──────────────────────────────────────────────────
    // 触发词：多个用换行分隔
    var triggersText by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf(false) }
    var leftWord by remember { mutableStateOf(false) }
    var rightWord by remember { mutableStateOf(false) }
    var propagateCase by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var vars by remember { mutableStateOf(listOf<Var>()) }
    var showVarEditor by remember { mutableStateOf(false) }
    var editingVarIndex by remember { mutableStateOf(-1) }
    var editingVar by remember { mutableStateOf(Var()) }

    // 编辑模式时存储原始 match（用于 updateMatch 定位）
    var originalMatch by remember { mutableStateOf<Match?>(null) }

    // 加载已有规则
    LaunchedEffect(route.triggerToEdit) {
        if (isEdit) {
            val key = route.triggerToEdit
            val match = MatchStore.exactMatches[key]
                ?: MatchStore.regexMatches.values.find { it.regex == key }
                ?: return@LaunchedEffect

            originalMatch = match
            triggersText = match.allTriggers.joinToString("\n")
            replace = match.replace
            regex = match.regex
            isRegex = match.isRegex
            word = match.word
            leftWord = match.leftWord
            rightWord = match.rightWord
            propagateCase = match.propagateCase
            label = match.label ?: ""
            vars = match.vars
        }
    }

    if (showVarEditor) {
        VarEditorDialog(
            v = editingVar,
            isNew = editingVarIndex < 0,
            onValueChange = { editingVar = it },
            onSave = {
                vars = if (editingVarIndex < 0) {
                    vars + editingVar
                } else {
                    vars.toMutableList().also { it[editingVarIndex] = editingVar }
                }
                showVarEditor = false
            },
            onDismiss = { showVarEditor = false },
        )
    }

    val title = if (isEdit) "编辑规则" else "新建规则"

    // ── 保存逻辑 ──────────────────────────────────────────────────
    val canSave = if (isRegex) regex.isNotBlank() && replace.isNotBlank()
                  else triggersText.isNotBlank() && replace.isNotBlank()

    fun save() {
        if (!canSave) return
        scope.launch(Dispatchers.IO) {
            val triggers = triggersText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val newMatch = Match(
                trigger = if (!isRegex) triggers.firstOrNull() ?: "" else "",
                triggers = if (!isRegex) triggers.drop(1) else emptyList(),
                replace = replace,
                regex = if (isRegex) regex else "",
                word = word,
                leftWord = leftWord,
                rightWord = rightWord,
                propagateCase = propagateCase,
                label = label.ifBlank { null },
                vars = vars,
            )

            val original = originalMatch
            if (isEdit && original != null) {
                MatchStore.updateMatch(sourceFile, original, newMatch)
            } else {
                MatchStore.addMatch(sourceFile, newMatch)
            }

            mainVm.popPage()
        }
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
                actions = {
                    TextButton(
                        onClick = throttle { save() },
                        enabled = canSave,
                    ) {
                        Text("保存")
                    }
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

            // ── 触发词 / 正则 ──────────────────────────────────────

            SectionLabel("触发词")

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(checked = isRegex, onCheckedChange = { isRegex = it })
                Text("使用正则触发", style = MaterialTheme.typography.bodyMedium)
            }

            if (isRegex) {
                OutlinedTextField(
                    value = regex,
                    onValueChange = { regex = it },
                    label = { Text("正则表达式") },
                    placeholder = { Text("如：\\b\\d{4}-\\d{2}-\\d{2}\\b") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            } else {
                OutlinedTextField(
                    value = triggersText,
                    onValueChange = { triggersText = it },
                    label = { Text("触发词（每行一个）") },
                    placeholder = { Text("如：:eml\n:email\n:mymail") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
                Text(
                    text = "多个触发词请每行一个，全部触发同一规则",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 替换文本 ───────────────────────────────────────────

            SectionLabel("替换文本")

            OutlinedTextField(
                value = replace,
                onValueChange = { replace = it },
                label = { Text("替换为") },
                placeholder = { Text("如：my@email.com，支持 {{var}} 变量和 \$|\$ 光标标记") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
            )

            // ── 单词边界 ───────────────────────────────────────────

            SectionLabel("单词边界")

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                CheckboxRow(
                    label = "word（左右两侧均需边界）",
                    checked = word,
                    onCheckedChange = {
                        word = it
                        if (it) { leftWord = false; rightWord = false }
                    },
                )
                CheckboxRow(
                    label = "left_word（仅左侧需边界）",
                    checked = leftWord,
                    onCheckedChange = {
                        leftWord = it
                        if (it) word = false
                    },
                )
                CheckboxRow(
                    label = "right_word（仅右侧需边界）",
                    checked = rightWord,
                    onCheckedChange = {
                        rightWord = it
                        if (it) word = false
                    },
                )
            }

            // ── 高级选项 ───────────────────────────────────────────

            SectionLabel("高级")

            CheckboxRow(
                label = "propagate_case（传播触发词大小写）",
                checked = propagateCase,
                onCheckedChange = { propagateCase = it },
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("备注（label，可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // ── 规则变量 ───────────────────────────────────────────

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("规则变量 (${vars.size})")
                TextButton(onClick = {
                    editingVarIndex = -1
                    editingVar = Var()
                    showVarEditor = true
                }) {
                    PerfIcon(imageVector = PerfIcon.Add)
                    Text("添加")
                }
            }

            if (vars.isNotEmpty()) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = surfaceCardColors,
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = itemHorizontalPadding,
                            vertical = 4.dp,
                        ),
                    ) {
                        vars.forEachIndexed { idx, v ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "{{${v.name}}}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = v.type + if (v.params.echo != null) ": ${v.params.echo}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                PerfIconButton(
                                    imageVector = PerfIcon.Edit,
                                    onClick = {
                                        editingVarIndex = idx
                                        editingVar = v
                                        showVarEditor = true
                                    },
                                )
                                PerfIconButton(
                                    imageVector = PerfIcon.Delete,
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    onClick = {
                                        vars = vars.toMutableList().also { it.removeAt(idx) }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── YAML 预览 ──────────────────────────────────────────

            SectionLabel("语法提示")

            val hint = buildString {
                appendLine("# 支持的 YAML 字段：")
                appendLine("# trigger: :hello")
                appendLine("# replace: Hello, {{name}}!")
                appendLine("# vars:")
                appendLine("#   - name: name")
                appendLine("#     type: echo")
                appendLine("#     params:")
                appendLine("#       echo: World")
                appendLine("# word: true   # 单词边界")
                appendLine("# propagate_case: true")
            }
            Text(
                text = hint.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 子组件
// ──────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
