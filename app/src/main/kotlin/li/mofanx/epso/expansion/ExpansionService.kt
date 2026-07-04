package li.mofanx.epso.expansion

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import li.mofanx.epso.a11y.isUseful
import li.mofanx.epso.a11y.toA11yEvent
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.util.LogUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * 文本扩展无障碍服务
 *
 * 完整数据链路：
 * MatchStore (StateFlow)
 *   → syncMatcher()  同步规则到 TriggerMatcher
 *   → match()        检测触发词
 *   → VarEvaluator   展开 {{var}} 变量占位符
 *   → TextReplacer   处理 propagate_case / $|$ / 执行 A11y 文本替换
 *
 * 规则热更新：订阅 MatchStore.matchDict，无需重启服务。
 */
class ExpansionService : A11yService() {

    companion object {
        private const val TAG = "ExpansionService"

        val isRunning = MutableStateFlow(false)

        @Volatile
        private var instance: ExpansionService? = null

        fun getInstance(): ExpansionService? = instance
    }

    private val triggerMatcher = TriggerMatcher()
    private val textReplacer by lazy { TextReplacer(this) }

    /**
     * VarEvaluator 跟随 globalVars 热更新：
     * 每当 MatchStore.globalVars 变化时重建（globalVars 不可变，重建成本低）
     */
    @Volatile
    private var varEvaluator = VarEvaluator(MatchStore.globalVars.value)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastTextChanges = ConcurrentHashMap<String, TextChangeInfo>()

    private val _expansionState = MutableStateFlow<ExpansionState>(ExpansionState.Idle)
    val expansionState: StateFlow<ExpansionState> = _expansionState

    // ──────────────────────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning.value = true
        subscribeMatchStore()
        LogUtils.d(TAG, "ExpansionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning.value = false
        serviceScope.launch { triggerMatcher.clear() }
        LogUtils.d(TAG, "ExpansionService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        super.onAccessibilityEvent(event)
        if (event == null || !event.isUseful()) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleTextChanged(event)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // MatchStore 订阅
    // ──────────────────────────────────────────────────────────────

    private fun subscribeMatchStore() {
        // 规则变化 → 同步到 TriggerMatcher
        MatchStore.matchDict
            .onEach { dict -> syncMatcher(dict) }
            .launchIn(serviceScope)

        // 全局变量变化 → 重建 VarEvaluator
        MatchStore.globalVars
            .onEach { vars -> varEvaluator = VarEvaluator(vars) }
            .launchIn(serviceScope)
    }

    private suspend fun syncMatcher(dict: Map<String, Match>) {
        triggerMatcher.clear()
        dict.values.distinct().forEach { match -> triggerMatcher.addMatch(match) }
        LogUtils.d(TAG, "Synced ${dict.values.distinct().size} matches")
    }

    // ──────────────────────────────────────────────────────────────
    // 文本变化处理
    // ──────────────────────────────────────────────────────────────

    private fun handleTextChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val a11yEvent = event.toA11yEvent() ?: return
        val node = a11yEvent.safeSource ?: return

        if (!node.isEditable) return

        val currentText = node.text?.toString() ?: return
        if (currentText.isBlank()) return

        if (!storeFlow.value.enableExpansion) return

        val debounceMs = storeFlow.value.expansionDebounceMs

        serviceScope.launch {
            val now = System.currentTimeMillis()
            lastTextChanges[packageName] = TextChangeInfo(currentText, now)
            delay(debounceMs)
            if (lastTextChanges[packageName]?.text != currentText) return@launch
            performExpansion(node, currentText, packageName)
        }
    }

    private suspend fun performExpansion(
        node: AccessibilityNodeInfo,
        text: String,
        packageName: String,
    ) {
        try {
            _expansionState.value = ExpansionState.Matching

            val matchResult = triggerMatcher.match(text)
            if (matchResult == null) {
                _expansionState.value = ExpansionState.Idle
                return
            }

            LogUtils.d(TAG, "Match: '${matchResult.matchedText}' in $packageName")
            _expansionState.value = ExpansionState.Expanding

            val match = matchResult.match

            // 表单分支：启动 FormOverlayWindow，等待用户填写后再替换
            val resolvedMatchResult = if (match.isForm && !match.form.isNullOrEmpty()) {
                val resolved = handleFormExpansion(match, matchResult) ?: return
                resolved
            } else {
                matchResult
            }

            val success = textReplacer.replace(
                node = node,
                originalText = text,
                matchResult = resolvedMatchResult,
                varEvaluator = varEvaluator,
            )

            _expansionState.value = if (success) {
                LogUtils.d(TAG, "Expanded: '${matchResult.matchedText}' -> '${resolvedMatchResult.match.replace}'")
                ExpansionState.Completed(
                    trigger = matchResult.matchedText,
                    expandedText = resolvedMatchResult.match.replace.take(60),
                )
            } else {
                LogUtils.e(TAG, "Replacement failed for: '${matchResult.matchedText}'")
                ExpansionState.Failed("Text replacement failed")
            }

            delay(1000)
            _expansionState.value = ExpansionState.Idle

        } catch (e: Exception) {
            _expansionState.value = ExpansionState.Failed(e.message ?: "Unknown error")
            LogUtils.e(TAG, "Expansion error", e)
        }
    }

    /**
     * 表单展开：
     * 1. 启动 FormOverlayWindow
     * 2. 等待用户填写结果（超时 5 分钟）
     * 3. 根据结果生成新的 MatchResult（replace 字段替换为渲染后的文本）
     * 返回 null 表示用户取消或超时。
     */
    private suspend fun handleFormExpansion(
        match: Match,
        matchResult: MatchResult,
    ): MatchResult? {
        FormOverlayWindow.requestForm(this, match)
        // 等待结果，超时 5 分钟后自动取消（关闭悬浮窗）
        val result = withTimeoutOrNull(5 * 60 * 1000L) {
            FormOverlayWindow.resultFlow.first()
        }
        if (result == null) {
            // 超时：关闭悬浮窗
            stopService(android.content.Intent(this, FormOverlayWindow::class.java))
            return null
        }

        val renderedReplace = FormOverlayWindow.renderForm(
            form = match.form ?: return null,
            values = result.values,
        )
        // 构造一个 replace 已被渲染的临时 match
        val resolvedMatch = match.copy(replace = renderedReplace)
        return matchResult.copy(match = resolvedMatch)
    }

    // ──────────────────────────────────────────────────────────────
    // 公开接口（供 UI 查询）
    // ──────────────────────────────────────────────────────────────

    suspend fun getMatchCount(): Int = triggerMatcher.getMatchCount()
}

// ──────────────────────────────────────────────────────────────
// 内部数据类 / 状态类
// ──────────────────────────────────────────────────────────────

private data class TextChangeInfo(val text: String, val timestamp: Long)

sealed class ExpansionState {
    object Idle : ExpansionState()
    object Matching : ExpansionState()
    object Expanding : ExpansionState()
    data class Completed(val trigger: String, val expandedText: String) : ExpansionState()
    data class Failed(val error: String) : ExpansionState()
}

private val AccessibilityNodeInfo.isEditable: Boolean
    get() = isEditable || (className?.toString()?.contains("EditText") == true)
