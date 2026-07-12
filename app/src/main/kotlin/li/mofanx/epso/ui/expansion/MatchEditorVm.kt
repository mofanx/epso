package li.mofanx.epso.ui.expansion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.mofanx.epso.expansion.Match
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.expansion.Var
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.common.form.FormState
import java.io.File

/**
 * 规则编辑页表单草稿
 */
data class MatchDraft(
    val triggersText: String = "",
    val prefix: String? = null,
    val effectivePrefix: String = "",
    val replace: String = "",
    val regex: String = "",
    val isRegex: Boolean = false,
    val word: Boolean = false,
    val leftWord: Boolean = false,
    val rightWord: Boolean = false,
    val propagateCase: Boolean = false,
    val label: String = "",
    val vars: List<Var> = emptyList(),
) {
    companion object {
        fun fromMatch(match: Match): MatchDraft {
            val effectivePrefix = match.effectivePrefix ?: match.prefix ?: ""
            return MatchDraft(
                triggersText = match.allTriggers.joinToString("\n") {
                    it.removePrefix(effectivePrefix)
                },
                prefix = match.prefix,
                effectivePrefix = effectivePrefix,
                replace = match.replace,
                regex = match.regex,
                isRegex = match.isRegex,
                word = match.word,
                leftWord = match.leftWord,
                rightWord = match.rightWord,
                propagateCase = match.propagateCase,
                label = match.label ?: "",
                vars = match.vars,
            )
        }
    }

    fun toMatch(): Match = Match(
        trigger = if (isRegex) "" else parseTriggers().firstOrNull() ?: "",
        triggers = if (isRegex) emptyList() else parseTriggers().drop(1),
        replace = replace,
        regex = if (isRegex) regex else "",
        prefix = prefix,
        word = word,
        leftWord = leftWord,
        rightWord = rightWord,
        propagateCase = propagateCase,
        label = label.ifBlank { null },
        vars = vars,
    ).apply { this.effectivePrefix = this@MatchDraft.effectivePrefix }

    /** 用于校验的实际触发词（已拼接前缀） */
    fun effectiveTriggers(): List<String> =
        parseTriggers().map { (prefix ?: effectivePrefix) + it }

    private fun parseTriggers(): List<String> =
        triggersText.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

class MatchEditorVm : ViewModel() {

    private val _formState = MutableStateFlow<FormState<MatchDraft>>(
        FormState.Editing(MatchDraft())
    )
    val formState: StateFlow<FormState<MatchDraft>> = _formState.asStateFlow()

    private lateinit var sourceFile: File
    private var triggerToEdit: String = ""
    private var originalMatch: Match? = null
    private var initialDraft: MatchDraft = MatchDraft()

    private val isLoaded: Boolean
        get() = ::sourceFile.isInitialized

    fun load(route: MatchEditorRoute) {
        if (isLoaded &&
            sourceFile.absolutePath == route.sourceFilePath &&
            triggerToEdit == route.triggerToEdit
        ) {
            return
        }

        sourceFile = File(route.sourceFilePath)
        triggerToEdit = route.triggerToEdit

        if (triggerToEdit.isNotEmpty()) {
            val match = MatchStore.exactMatches[triggerToEdit]
                ?: MatchStore.regexMatches.values.find { it.regex == triggerToEdit }
                ?: return

            originalMatch = match
            initialDraft = MatchDraft.fromMatch(match)
            _formState.value = FormState.Editing(
                draft = initialDraft,
                isDirty = false,
                isValid = true,
                errors = emptyMap(),
            )
        } else {
            originalMatch = null
            val effectivePrefix = resolveEffectivePrefix(sourceFile)
            initialDraft = MatchDraft(effectivePrefix = effectivePrefix)
            _formState.value = FormState.Editing(
                draft = initialDraft,
                isDirty = false,
                isValid = false,
                errors = emptyMap(),
            )
        }
    }

    fun updateDraft(transform: MatchDraft.() -> MatchDraft) {
        val current = _formState.value as? FormState.Editing ?: return
        val newDraft = current.draft.transform()
        val errors = validate(newDraft)
        _formState.value = FormState.Editing(
            draft = newDraft,
            isDirty = newDraft != initialDraft,
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    fun save() {
        val current = when (val s = _formState.value) {
            is FormState.Editing -> s
            is FormState.Failed -> FormState.Editing(
                draft = s.draft,
                isDirty = true,
                isValid = validate(s.draft).isEmpty(),
                errors = validate(s.draft),
            )
            else -> return
        }
        if (!current.isValid) return

        _formState.value = FormState.Submitting(current.draft)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var targetFile = sourceFile
                if (!targetFile.isFile) {
                    targetFile = MatchStore.ensureBaseFile()
                }

                val match = current.draft.toMatch()
                val original = originalMatch
                if (original != null && triggerToEdit.isNotEmpty()) {
                    MatchStore.updateMatch(targetFile, original, match)
                } else {
                    MatchStore.addMatch(targetFile, match)
                }

                _formState.value = FormState.Success(current.draft)
            } catch (e: Exception) {
                _formState.value = FormState.Failed(
                    draft = current.draft,
                    error = e.message ?: "Unknown error",
                )
            }
        }
    }

    private fun resolveEffectivePrefix(sourceFile: File): String {
        val group = MatchStore.groups.value.find { it.sourceFile == sourceFile.absolutePath }
        return group?.prefix ?: storeFlow.value.triggerPrefix
    }

    private fun validate(draft: MatchDraft): Map<String, String> {
        val errors = mutableMapOf<String, String>()

        if (draft.isRegex) {
            if (draft.regex.isBlank()) {
                errors["regex"] = "match_editor_error_empty_regex"
            } else {
                try {
                    Regex(draft.regex)
                } catch (_: Exception) {
                    errors["regex"] = "match_editor_error_invalid_regex"
                }
            }

            val existingRegexes = MatchStore.regexMatches.values
                .map { it.regex }
                .filter { it != triggerToEdit }
            if (draft.regex in existingRegexes) {
                errors["regex"] = "match_editor_error_duplicate_trigger"
            }
        } else {
            val triggers = draft.effectiveTriggers()
            if (triggers.isEmpty()) {
                errors["trigger"] = "match_editor_error_empty_trigger"
            } else {
                val existingTriggers = MatchStore.exactMatches.keys.filter { it != triggerToEdit }
                if (triggers.any { it in existingTriggers }) {
                    errors["trigger"] = "match_editor_error_duplicate_trigger"
                }
            }
        }

        if (draft.replace.isBlank()) {
            errors["replace"] = "match_editor_error_empty_replace"
        }

        return errors
    }
}
