package li.mofanx.epso.ui.expansion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import li.mofanx.epso.R
import li.mofanx.epso.expansion.Var
import li.mofanx.epso.ui.common.feedback.BannerType
import li.mofanx.epso.ui.common.feedback.StatusBanner
import li.mofanx.epso.ui.common.form.FormState
import li.mofanx.epso.ui.common.form.canSubmit
import li.mofanx.epso.ui.common.form.draft
import li.mofanx.epso.ui.common.form.isDirty
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.throttle

/**
 * 规则编辑页
 *
 * 同时承担「新建」和「编辑」功能：
 * - [route].triggerToEdit 为空 → 新建模式
 * - 非空 → 编辑模式，从 MatchStore 加载对应规则
 *
 * 基础/高级分层：
 * - 基础：触发词 / 正则、替换文本
 * - 高级：单词边界、大小写传播、备注、规则变量、YAML 语法提示
 */
private enum class MatchEditorTab { Basic, Advanced }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchEditorPage(route: MatchEditorRoute) {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<MatchEditorVm>()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val formState by vm.formState.collectAsState()
    val draft = formState.draft() ?: MatchDraft()
    val isEdit = route.triggerToEdit.isNotEmpty()

    LaunchedEffect(route) { vm.load(route) }
    LaunchedEffect(formState) { if (formState is FormState.Success) mainVm.popPage() }

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showVarEditor by remember { mutableStateOf(false) }
    var editingVarIndex by remember { mutableStateOf(-1) }
    var editingVar by remember { mutableStateOf(Var()) }
    var selectedTab by remember { mutableStateOf(MatchEditorTab.Basic) }

    if (showVarEditor) {
        VarEditorDialog(
            v = editingVar,
            isNew = editingVarIndex < 0,
            onValueChange = { editingVar = it },
            onSave = {
                val updatedVars = if (editingVarIndex < 0) {
                    draft.vars + editingVar
                } else {
                    draft.vars.toMutableList().also { it[editingVarIndex] = editingVar }
                }
                vm.updateDraft { copy(vars = updatedVars) }
                showVarEditor = false
            },
            onDismiss = { showVarEditor = false },
        )
    }

    val onBack = throttle {
        if (formState.isDirty()) {
            showUnsavedDialog = true
        } else {
            mainVm.popPage()
        }
    }

    BackHandler(enabled = formState.isDirty(), onBack = onBack)

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.match_editor_unsaved_title)) },
            text = { Text(stringResource(R.string.match_editor_unsaved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        vm.save()
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        mainVm.popPage()
                    },
                ) {
                    Text(stringResource(R.string.match_editor_unsaved_discard))
                }
            },
        )
    }

    val title = stringResource(
        if (isEdit) R.string.match_editor_title_edit else R.string.match_editor_title_new
    )
    val errors = (formState as? FormState.Editing)?.errors ?: emptyMap()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(title) },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = onBack,
                    )
                },
                actions = {
                    TextButton(
                        onClick = throttle { vm.save() },
                        enabled = formState.canSubmit(),
                    ) {
                        Text(stringResource(R.string.action_save))
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

            val failed = formState as? FormState.Failed
            if (failed != null) {
                StatusBanner(
                    title = stringResource(R.string.match_editor_save_failed, failed.error),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { vm.save() },
                    type = BannerType.Warning(),
                )
            }

            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                MatchEditorTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab.ordinal == index,
                        onClick = { selectedTab = tab },
                        text = { Text(tabLabel(tab)) },
                    )
                }
            }

            Spacer(Modifier.height(itemVerticalPadding / 2))

            when (selectedTab) {
                MatchEditorTab.Basic -> BasicTab(
                    draft = draft,
                    errors = errors,
                    onDraftChange = { newDraft -> vm.updateDraft { newDraft } },
                )
                MatchEditorTab.Advanced -> AdvancedTab(
                    draft = draft,
                    onDraftChange = { newDraft -> vm.updateDraft { newDraft } },
                    onAddVar = {
                        editingVarIndex = -1
                        editingVar = Var()
                        showVarEditor = true
                    },
                    onEditVar = { index, v ->
                        editingVarIndex = index
                        editingVar = v
                        showVarEditor = true
                    },
                    onDeleteVar = { index ->
                        val updatedVars = draft.vars.toMutableList().also { it.removeAt(index) }
                        vm.updateDraft { copy(vars = updatedVars) }
                    },
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun tabLabel(tab: MatchEditorTab): String = when (tab) {
    MatchEditorTab.Basic -> stringResource(R.string.match_editor_tab_basic)
    MatchEditorTab.Advanced -> stringResource(R.string.match_editor_tab_advanced)
}

@Composable
private fun resolveError(key: String): String = when (key) {
    "match_editor_error_empty_trigger" -> stringResource(R.string.match_editor_error_empty_trigger)
    "match_editor_error_empty_regex" -> stringResource(R.string.match_editor_error_empty_regex)
    "match_editor_error_invalid_regex" -> stringResource(R.string.match_editor_error_invalid_regex)
    "match_editor_error_duplicate_trigger" -> stringResource(R.string.match_editor_error_duplicate_trigger)
    "match_editor_error_empty_replace" -> stringResource(R.string.match_editor_error_empty_replace)
    else -> key
}

@Composable
private fun BasicTab(
    draft: MatchDraft,
    errors: Map<String, String>,
    onDraftChange: (MatchDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2)) {
        SectionLabel(stringResource(R.string.match_editor_section_trigger))

        CheckboxRow(
            label = stringResource(
                if (draft.isRegex) R.string.match_editor_trigger_mode_regex
                else R.string.match_editor_trigger_mode_plain
            ),
            checked = draft.isRegex,
            onCheckedChange = { onDraftChange(draft.copy(isRegex = it)) },
        )

        if (draft.isRegex) {
            OutlinedTextField(
                value = draft.regex,
                onValueChange = { onDraftChange(draft.copy(regex = it)) },
                label = { Text(stringResource(R.string.match_editor_regex_label)) },
                placeholder = { Text(stringResource(R.string.match_editor_regex_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = errors["regex"] != null,
                supportingText = errors["regex"]?.let { { Text(resolveError(it)) } },
            )
        } else {
            OutlinedTextField(
                value = draft.prefix ?: draft.effectivePrefix,
                onValueChange = { newPrefix ->
                    val prefix = if (newPrefix.isNotEmpty() && newPrefix == draft.effectivePrefix) {
                        null
                    } else {
                        newPrefix
                    }
                    onDraftChange(draft.copy(prefix = prefix))
                },
                label = { Text(stringResource(R.string.match_editor_prefix_label)) },
                placeholder = { Text(draft.effectivePrefix) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = errors["trigger"] != null,
            )
            OutlinedTextField(
                value = draft.triggersText,
                onValueChange = { onDraftChange(draft.copy(triggersText = it)) },
                label = { Text(stringResource(R.string.match_editor_trigger_label)) },
                placeholder = { Text(stringResource(R.string.match_editor_trigger_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
                isError = errors["trigger"] != null,
                supportingText = errors["trigger"]?.let { { Text(resolveError(it)) } },
            )
            Text(
                text = stringResource(R.string.match_editor_trigger_helper),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel(stringResource(R.string.match_editor_replace_label))

        OutlinedTextField(
            value = draft.replace,
            onValueChange = { onDraftChange(draft.copy(replace = it)) },
            label = { Text(stringResource(R.string.match_editor_replace_label)) },
            placeholder = { Text(stringResource(R.string.match_editor_replace_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 10,
            isError = errors["replace"] != null,
            supportingText = errors["replace"]?.let { { Text(resolveError(it)) } },
        )
    }
}

@Composable
private fun AdvancedTab(
    draft: MatchDraft,
    onDraftChange: (MatchDraft) -> Unit,
    onAddVar: () -> Unit,
    onEditVar: (Int, Var) -> Unit,
    onDeleteVar: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2)) {
        SectionLabel(stringResource(R.string.match_editor_section_word_boundary))

        CheckboxRow(
            label = stringResource(R.string.match_editor_word_boundary_full),
            checked = draft.word,
            onCheckedChange = {
                onDraftChange(draft.copy(word = it, leftWord = false, rightWord = false))
            },
        )
        CheckboxRow(
            label = stringResource(R.string.match_editor_word_boundary_left),
            checked = draft.leftWord,
            onCheckedChange = {
                onDraftChange(draft.copy(leftWord = it, word = false, rightWord = false))
            },
        )
        CheckboxRow(
            label = stringResource(R.string.match_editor_word_boundary_right),
            checked = draft.rightWord,
            onCheckedChange = {
                onDraftChange(draft.copy(rightWord = it, word = false, leftWord = false))
            },
        )

        SectionLabel(stringResource(R.string.match_editor_section_advanced))

        CheckboxRow(
            label = stringResource(R.string.match_editor_propagate_case),
            checked = draft.propagateCase,
            onCheckedChange = { onDraftChange(draft.copy(propagateCase = it)) },
        )

        OutlinedTextField(
            value = draft.label,
            onValueChange = { onDraftChange(draft.copy(label = it)) },
            label = { Text(stringResource(R.string.match_editor_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(stringResource(R.string.match_editor_section_vars, draft.vars.size))
            TextButton(onClick = onAddVar) {
                PerfIcon(imageVector = PerfIcon.Add)
                Text(stringResource(R.string.match_editor_add_var))
            }
        }

        if (draft.vars.isNotEmpty()) {
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
                    draft.vars.forEachIndexed { idx, v ->
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
                                onClick = { onEditVar(idx, v) },
                            )
                            PerfIconButton(
                                imageVector = PerfIcon.Delete,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                onClick = { onDeleteVar(idx) },
                            )
                        }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.match_editor_syntax_help))

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
