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
import li.mofanx.epso.permission.canDrawOverlaysState
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.util.LogUtils
import li.mofanx.epso.util.toast
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
open class ExpansionService : A11yService() {

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
        if (event == null) return
        // TYPE_VIEW_TEXT_CHANGED 不在 isUseful() 的 interestedEvents 中，
        // 需要单独处理，否则文本变化事件全部被过滤掉
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (event.packageName == null || event.className == null) return
            handleTextChanged(event)
            return
        }
        if (!event.isUseful()) return
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
            // 如果在 debounce 期间文本已变化，放弃本次处理
            if (lastTextChanges[packageName]?.text != currentText) return@launch
            // 处理完毕后清除条目，避免 map 无限增长
            lastTextChanges.remove(packageName)
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

            // ── 搜索触发词优先检查（在正常匹配之前） ──────────────────────
            val searchTrigger = storeFlow.value.searchTrigger
            if (searchTrigger.isNotEmpty() && text.endsWith(searchTrigger)) {
                // 搜索触发词后面用户可能继续输入了内容，提取作为预填关键词
                // 此处 text 末尾是 searchTrigger，preQuery 为空
                handleSearchExpansion(node, text, searchTrigger, preQuery = "")
                _expansionState.value = ExpansionState.Idle
                return
            }
            // 也支持 ":s keyword" 模式：文本末尾为 searchTrigger + 空格 + 关键词（无额外空格）
            if (searchTrigger.isNotEmpty()) {
                val prefix = "$searchTrigger "
                // 只检查末尾，避免文本中间历史出现的 ":s " 误触发
                val lastPrefixIdx = text.lastIndexOf(prefix)
                if (lastPrefixIdx >= 0) {
                    val afterPrefix = text.substring(lastPrefixIdx + prefix.length)
                    // afterPrefix 不含空格且末尾确实是这段内容，说明用户正在末尾输入关键词
                    val triggeredSegment = prefix + afterPrefix
                    if (!afterPrefix.contains(' ') && afterPrefix.isNotEmpty()
                        && text.endsWith(triggeredSegment)
                    ) {
                        handleSearchExpansion(node, text, triggeredSegment, preQuery = afterPrefix)
                        _expansionState.value = ExpansionState.Idle
                        return
                    }
                }
            }
            // ─────────────────────────────────────────────────────────────

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
                val resolved = handleFormExpansion(match, matchResult) ?: run {
                    _expansionState.value = ExpansionState.Idle
                    return
                }
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

    /**
     * 搜索展开：
     * 1. 保存当前焦点节点（悬浮窗弹出后原节点可能失焦）
     * 2. 启动 SearchOverlayWindow，等待用户选中规则（超时 5 分钟）
     * 3. 用户选中 → 删除触发片段，通过 TextReplacer 插入替换结果
     * 4. 用户取消或超时 → 不做任何文本修改
     *
     * @param node           当前焦点输入节点
     * @param text           输入框当前全文
     * @param triggeredText  需要被删除的触发片段（如 ":s" 或 ":s keyword"）
     * @param preQuery       预填入搜索框的关键词
     */
    private suspend fun handleSearchExpansion(
        node: AccessibilityNodeInfo,
        text: String,
        triggeredText: String,
        preQuery: String,
    ) {
        // 检查悬浮窗权限，无权限则提示用户并退出
        if (!canDrawOverlaysState.updateAndGet()) {
            toast("请先开启「悬浮窗权限」才能使用快速搜索功能")
            return
        }

        // 问题4：弹出悬浮窗前先删除触发词，避免悬浮窗存在期间再次触发匹配
        // 同时也让用户清楚地看到触发词已消除，搜索结果会插入到此位置
        val textWithoutTrigger = text.dropLast(triggeredText.length)
        val clearArgs = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textWithoutTrigger,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        delay(50)
        // 将光标移到文本末尾（即触发词原来的起始位置）
        val cursorArgs = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, textWithoutTrigger.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textWithoutTrigger.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)

        // 保存原节点的 windowId，用于悬浮窗弹出后找回原节点
        val sourceWindowId = node.windowId

        SearchOverlayWindow.requestSearch(this, preQuery)

        // 等待用户选择，超时 5 分钟后自动取消
        val selectedMatch = withTimeoutOrNull(5 * 60 * 1000L) {
            SearchOverlayWindow.resultFlow.first()
        }

        if (selectedMatch == null) {
            // 用户取消或超时，关闭悬浮窗（可能已自关，stopService 幂等）
            stopService(android.content.Intent(this, SearchOverlayWindow::class.java))
            return
        }

        // 悬浮窗关闭后，通过 windowId 找回原输入框节点
        // 悬浮窗弹出时原节点可能已失焦，需要从对应 window 重新查找
        delay(100) // 等待悬浮窗完全关闭、焦点归还
        val targetNode = findEditableNodeInWindow(sourceWindowId) ?: run {
            // 兜底：尝试通用焦点查找
            findFocusedEditableNode() ?: node
        }

        // 读取当前文本（触发词已在上面删除，此处文本应不含触发词）
        val currentText = targetNode.text?.toString() ?: textWithoutTrigger

        // 构造 MatchResult：插入位置在当前文本末尾（触发词已删除，直接在末尾追加）
        val insertIndex = currentText.length
        val fakeMatchResult = MatchResult(
            match = selectedMatch,
            matchedText = "",       // 触发词已删除，无需再删除
            startIndex = insertIndex,
            endIndex = insertIndex,
        )

        val success = textReplacer.replace(
            node = targetNode,
            originalText = currentText,
            matchResult = fakeMatchResult,
            varEvaluator = varEvaluator,
        )

        if (success) {
            LogUtils.d(TAG, "Search expand: inserted '${selectedMatch.replace.take(60)}'")
            _expansionState.value = ExpansionState.Completed(
                trigger = triggeredText,
                expandedText = selectedMatch.replace.take(60),
            )
            delay(1000)
        } else {
            LogUtils.e(TAG, "Search expand replacement failed")
            _expansionState.value = ExpansionState.Failed("Search expand failed")
        }
    }

    /**
     * 在指定 windowId 的窗口中查找可编辑节点。
     * 悬浮窗弹出后 rootInActiveWindow 指向悬浮窗，需要通过 windows 列表找回原窗口。
     */
    private fun findEditableNodeInWindow(windowId: Int): AccessibilityNodeInfo? {
        return try {
            windows?.firstOrNull { it.id == windowId }
                ?.root
                ?.let { root ->
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                        ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.takeIf { it.isEditable }
                        ?: findFirstEditableNode(root)
                }
        } catch (e: Exception) {
            LogUtils.e(TAG, "findEditableNodeInWindow failed", e)
            null
        }
    }

    /**
     * 递归在树中找第一个可编辑节点（兜底方案）。
     */
    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * 通用焦点查找（兜底，悬浮窗可能抢占了 rootInActiveWindow）。
     */
    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?.takeIf { it.isEditable }
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
    data object Idle : ExpansionState()
    data object Matching : ExpansionState()
    data object Expanding : ExpansionState()
    data class Completed(val trigger: String, val expandedText: String) : ExpansionState()
    data class Failed(val error: String) : ExpansionState()
}

private val AccessibilityNodeInfo.isEditable: Boolean
    get() = isEditable || (className?.toString()?.contains("EditText") == true)
