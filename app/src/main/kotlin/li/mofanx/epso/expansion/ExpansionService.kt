package li.mofanx.epso.expansion

import android.content.Intent
import android.graphics.Rect
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var varEvaluator = VarEvaluator(
        globalVars = MatchStore.globalVars.value,
        formLauncher = { launchForm(it) },
        choiceLauncher = { launchChoice(it) },
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastTextChanges = ConcurrentHashMap<String, TextChangeInfo>()

    /** 表单弹窗互斥锁：同时只能显示一个表单 */
    private val formMutex = Mutex()

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
            .onEach { vars ->
                varEvaluator = VarEvaluator(
                    globalVars = vars,
                    formLauncher = { launchForm(it) },
                    choiceLauncher = { launchChoice(it) },
                )
            }
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

            val match = matchResult.match

            // 应用 filter_* / enable 过滤：检查当前 App 上下文是否允许该规则生效
            val root = rootInActiveWindow
            val title = root?.contentDescription?.toString() ?: root?.text?.toString()
            val className = root?.className?.toString()
            if (!match.isActiveFor(packageName = packageName, className = className, title = title)) {
                LogUtils.d(TAG, "Match '${matchResult.matchedText}' filtered out for package=$packageName, class=$className, title=$title")
                _expansionState.value = ExpansionState.Idle
                return
            }

            LogUtils.d(TAG, "Match: '${matchResult.matchedText}' in $packageName")
            _expansionState.value = ExpansionState.Expanding

            // 表单/带 form 类型变量的规则需要先把触发词删掉、等悬浮窗关闭后再回填，
            // 否则悬浮窗弹出期间原输入框会失焦，节点失效导致替换失败。
            val success = when {
                match.isForm && !match.form.isNullOrEmpty() -> {
                    handleFormExpansion(match, matchResult, node, text)
                }
                match.vars.any { it.type == "form" || it.type == "choice" } -> {
                    handleFormVarExpansion(match, matchResult, node, text)
                }
                else -> textReplacer.replace(
                    node = node,
                    originalText = text,
                    matchResult = matchResult,
                    varEvaluator = varEvaluator,
                )
            }

            _expansionState.value = if (success) {
                val expandedText = match.replace.takeIf { it.isNotEmpty() } ?: match.form ?: ""
                LogUtils.d(TAG, "Expanded: '${matchResult.matchedText}' -> '$expandedText'")
                ExpansionState.Completed(
                    trigger = matchResult.matchedText,
                    expandedText = expandedText.take(60),
                )
            } else {
                LogUtils.e(TAG, "Replacement failed for: '${matchResult.matchedText}'")
                ExpansionState.Failed("Text replacement failed")
            }

            delay(1000)
            _expansionState.value = ExpansionState.Idle

        } catch (e: FormCanceledException) {
            _expansionState.value = ExpansionState.Idle
        } catch (e: Exception) {
            _expansionState.value = ExpansionState.Failed(e.message ?: "Unknown error")
            LogUtils.e(TAG, "Expansion error", e)
        }
    }

    /**
     * 表单展开：
     * 1. 先删除触发词，避免悬浮窗存在期间再次触发匹配
     * 2. 启动 FormOverlayWindow 等待用户填写
     * 3. 悬浮窗关闭后重新找回原输入框，插入渲染后的文本
     * 返回 false 表示用户取消/超时/失败。
     */
    private suspend fun handleFormExpansion(
        match: Match,
        matchResult: MatchResult,
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        val startIndex = matchResult.startIndex
        val endIndex = matchResult.endIndex
        val textWithoutTrigger = text.removeRange(startIndex, endIndex)

        // 先删除触发词，避免悬浮窗期间重复匹配
        val clearArgs = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textWithoutTrigger,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        delay(50)
        val cursorArgs = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, startIndex)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, startIndex)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)

        val sourceWindowId = node.windowId

        FormOverlayWindow.requestForm(this, match)
        val result = withTimeoutOrNull(5 * 60 * 1000L) {
            FormOverlayWindow.resultFlow.first()
        }
        if (result == null) {
            stopService(android.content.Intent(this, FormOverlayWindow::class.java))
            return false
        }

        val renderedReplace = FormOverlayWindow.renderForm(
            form = match.form ?: return false,
            values = result.values,
        )

        delay(500)
        val targetNode = resolveTargetNode(node, sourceWindowId)
        val currentText = targetNode.text?.toString() ?: textWithoutTrigger
        val insertIndex = startIndex.coerceIn(0, currentText.length)
        val fakeMatchResult = MatchResult(
            match = match.copy(replace = renderedReplace),
            matchedText = "",
            startIndex = insertIndex,
            endIndex = insertIndex,
        )

        return textReplacer.replace(
            node = targetNode,
            originalText = currentText,
            replacement = renderedReplace,
            matchResult = fakeMatchResult,
            match = fakeMatchResult.match,
        )
    }

    /**
     * 带 form 类型变量的规则展开：
     * 1. 先删除触发词
     * 2. 调用 VarEvaluator 求值（期间会弹出 form 悬浮窗收集字段）
     * 3. 悬浮窗关闭后重新找回原输入框，插入求值后的文本
     * 返回 false 表示用户取消/超时/失败。
     */
    private suspend fun handleFormVarExpansion(
        match: Match,
        matchResult: MatchResult,
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        val startIndex = matchResult.startIndex
        val endIndex = matchResult.endIndex
        val textWithoutTrigger = text.removeRange(startIndex, endIndex)

        // 先删除触发词，避免悬浮窗期间重复匹配
        val clearArgs = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textWithoutTrigger,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        delay(50)
        val cursorArgs = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, startIndex)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, startIndex)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)

        val sourceWindowId = node.windowId

        val replacement = try {
            varEvaluator.evaluate(match.replace, match.vars)
        } catch (e: FormCanceledException) {
            return false
        }

        delay(500)
        val targetNode = resolveTargetNode(node, sourceWindowId)
        val currentText = targetNode.text?.toString() ?: textWithoutTrigger
        val insertIndex = startIndex.coerceIn(0, currentText.length)
        val fakeMatchResult = MatchResult(
            match = match,
            matchedText = "",
            startIndex = insertIndex,
            endIndex = insertIndex,
        )

        return textReplacer.replace(
            node = targetNode,
            originalText = currentText,
            replacement = replacement,
            matchResult = fakeMatchResult,
            match = match,
        )
    }

    /**
     * 启动表单悬浮窗并收集结果（给 VarEvaluator 的 form 类型变量使用）。
     * 同一时间只能显示一个表单；用户取消/超时/无权限都返回 null。
     */
    private suspend fun launchForm(formVar: Var): Map<String, String>? {
        if (!canDrawOverlaysState.checkOrToast()) return null
        if (formVar.params.layout.isNullOrBlank() && formVar.params.fields.isEmpty()) {
            LogUtils.e(TAG, "Form var '${formVar.name}' has neither layout nor fields")
            return null
        }

        val layout = formVar.params.layout
            ?: formVar.params.fields.keys.joinToString("\n") { "[[$it]]" }
        val match = Match(
            form = layout,
            formFields = formVar.params.fields,
        )

        return formMutex.withLock {
            FormOverlayWindow.requestForm(this, match)
            val result = withTimeoutOrNull(5 * 60 * 1000L) {
                FormOverlayWindow.resultFlow.first()
            }
            if (result == null) {
                stopService(Intent(this, FormOverlayWindow::class.java))
                throw FormCanceledException()
            }
            result.values
        }
    }

    /**
     * 启动选择悬浮窗并收集结果（给 VarEvaluator 的 choice 类型变量使用）。
     * 同一时间只能显示一个弹窗；用户取消/超时/无权限都返回 null。
     */
    private suspend fun launchChoice(choiceVar: Var): String? {
        if (!canDrawOverlaysState.checkOrToast()) return null
        val choices = choiceVar.params.values.ifEmpty { choiceVar.params.choices }
        if (choices.isEmpty()) {
            LogUtils.e(TAG, "Choice var '${choiceVar.name}' has no choices")
            return null
        }

        return formMutex.withLock {
            ChoiceOverlayWindow.requestChoice(
                ctx = this,
                title = "请选择 ${choiceVar.name}",
                options = choices,
            )
            val result = withTimeoutOrNull(5 * 60 * 1000L) {
                ChoiceOverlayWindow.resultFlow.first()
            }
            if (result == null) {
                stopService(Intent(this, ChoiceOverlayWindow::class.java))
                throw FormCanceledException()
            }
            result
        }
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
     * 弹出悬浮窗后重新定位目标输入框。
     * 优先复用触发时的原始节点（它本来就是输入框），刷新后仍有效就直接用；
     * 否则在指定窗口中查找最合适的可编辑节点。
     */
    private fun resolveTargetNode(
        originalNode: AccessibilityNodeInfo,
        sourceWindowId: Int,
    ): AccessibilityNodeInfo {
        // 原始节点刷新后仍然指向输入框，直接复用，避免在聊天记录里误找
        val refreshed = try { originalNode.refresh() } catch (e: Exception) { false }
        if (refreshed && originalNode.isEditable) {
            return originalNode
        }
        return findEditableNodeInWindow(sourceWindowId)
            ?: findFocusedEditableNode()
            ?: originalNode
    }

    /**
     * 在指定 windowId 的窗口中查找可编辑节点。
     * 悬浮窗弹出后 rootInActiveWindow 指向悬浮窗，需要通过 windows 列表找回原窗口。
     * 元宝等 App 的聊天记录文本也可能被标记为 editable，因此优先：
     * 1. 当前有焦点的可编辑节点
     * 2. className 包含 EditText 的可编辑节点
     * 3. 屏幕位置最靠下的可编辑节点（输入框一般在底部）
     */
    private fun findEditableNodeInWindow(windowId: Int): AccessibilityNodeInfo? {
        return try {
            windows?.firstOrNull { it.id == windowId }
                ?.root
                ?.let { root ->
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                        ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.takeIf { it.isEditable }
                        ?: findBestEditableNode(root)
                }
        } catch (e: Exception) {
            LogUtils.e(TAG, "findEditableNodeInWindow failed", e)
            null
        }
    }

    /**
     * 从节点树中挑选最合适的可编辑节点。
     * 优先选真正可聚焦的，再优先 EditText 类名，最后选屏幕最靠下的。
     */
    private fun findBestEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, candidates)
        if (candidates.isEmpty()) return null

        // 优先可聚焦的，避免消息气泡被选中
        val focusable = candidates.filter { it.isFocusable }
        val pool = if (focusable.isNotEmpty()) focusable else candidates

        // 优先 EditText 类名
        pool.firstOrNull { it.className?.toString()?.contains("EditText") == true }?.let { return it }

        // 优先屏幕位置最靠下（输入框通常在底部）
        return pool.maxByOrNull { node ->
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
                rect.bottom
            } catch (e: Exception) {
                0
            }
        }
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableNodes(child, out)
        }
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
