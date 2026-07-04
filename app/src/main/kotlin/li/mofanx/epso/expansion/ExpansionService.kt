package li.mofanx.epso.expansion

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.util.LogUtils
import java.util.concurrent.ConcurrentHashMap
import li.mofanx.epso.a11y.isUseful
import li.mofanx.epso.a11y.toA11yEvent

/**
 * 文本扩展服务
 * 继承自无障碍服务，监听文本变化并执行扩展
 */
class ExpansionService : A11yService() {
    
    companion object {
        private const val TAG = "ExpansionService"
        private const val DEBOUNCE_DELAY = 300L // 防抖延迟
        
        val isRunning = MutableStateFlow(false)
        
        @Volatile
        private var instance: ExpansionService? = null
        
        fun getInstance(): ExpansionService? = instance
    }
    
    // 核心组件
    private val triggerMatcher = TriggerMatcher()
    private val textReplacer by lazy { TextReplacer(this) }
    
    // 服务作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 文本变化防抖
    private val lastTextChanges = ConcurrentHashMap<String, TextChangeInfo>()
    
    // 当前匹配状态
    private val _expansionState = MutableStateFlow<ExpansionState>(ExpansionState.Idle)
    val expansionState: StateFlow<ExpansionState> = _expansionState
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning.value = true
        LogUtils.d(TAG, "ExpansionService created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning.value = false
        serviceScope.launch {
            triggerMatcher.clear()
        }
        LogUtils.d(TAG, "ExpansionService destroyed")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        super.onAccessibilityEvent(event)
        
        if (event == null || !event.isUseful()) {
            return
        }
        
        // 只处理文本变化事件
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleTextChanged(event)
        }
    }
    
    /**
     * 处理文本变化事件
     */
    private fun handleTextChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val a11yEvent = event.toA11yEvent() ?: return
        val node = a11yEvent.safeSource ?: return
        
        // 检查是否是可编辑节点
        if (!node.isEditable) {
            return
        }
        
        val currentText = node.text?.toString() ?: return
        if (currentText.isBlank()) {
            return
        }
        
        serviceScope.launch {
            // 防抖处理
            val changeInfo = lastTextChanges[packageName]
            val now = System.currentTimeMillis()
            
            if (changeInfo != null && (now - changeInfo.timestamp) < DEBOUNCE_DELAY) {
                // 更新最后一次变化信息
                lastTextChanges[packageName] = TextChangeInfo(currentText, now)
                return@launch
            }
            
            lastTextChanges[packageName] = TextChangeInfo(currentText, now)
            
            // 延迟执行，等待用户停止输入
            delay(DEBOUNCE_DELAY)
            
            // 再次检查文本是否发生变化
            val latestInfo = lastTextChanges[packageName]
            if (latestInfo?.text != currentText) {
                return@launch
            }
            
            // 执行扩展匹配
            performExpansion(node, currentText, packageName)
        }
    }
    
    /**
     * 执行文本扩展
     */
    private suspend fun performExpansion(
        node: AccessibilityNodeInfo,
        text: String,
        packageName: String
    ) {
        try {
            _expansionState.value = ExpansionState.Matching
            
            // 匹配触发器
            val matchResult = triggerMatcher.match(text)
            if (matchResult == null) {
                _expansionState.value = ExpansionState.Idle
                return
            }
            
            LogUtils.d(TAG, "Match found: ${matchResult.match.trigger} in $packageName")
            
            _expansionState.value = ExpansionState.Expanding
            
            // 执行替换
            val success = textReplacer.replace(
                node = node,
                originalText = text,
                replacement = matchResult.match.replace,
                matchResult = matchResult
            )
            
            if (success) {
                _expansionState.value = ExpansionState.Completed(
                    trigger = matchResult.match.trigger,
                    expandedText = matchResult.match.replace
                )
                LogUtils.d(TAG, "Expansion successful: ${matchResult.match.trigger} -> ${matchResult.match.replace}")
            } else {
                _expansionState.value = ExpansionState.Failed("Text replacement failed")
                LogUtils.e(TAG, "Expansion failed for trigger: ${matchResult.match.trigger}")
            }
            
            // 延迟后重置状态
            delay(1000)
            _expansionState.value = ExpansionState.Idle
            
        } catch (exception: Exception) {
            _expansionState.value = ExpansionState.Failed(exception.message ?: "Unknown error")
            LogUtils.e(TAG, "Expansion error", exception)
        }
    }
    
    /**
     * 添加匹配规则
     */
    suspend fun addMatch(match: Match) {
        triggerMatcher.addMatch(match)
        LogUtils.d(TAG, "Added match: ${match.trigger}")
    }
    
    /**
     * 移除匹配规则
     */
    suspend fun removeMatch(trigger: String) {
        triggerMatcher.removeMatch(trigger)
        LogUtils.d(TAG, "Removed match: $trigger")
    }
    
    /**
     * 清空所有匹配规则
     */
    suspend fun clearMatches() {
        triggerMatcher.clear()
        LogUtils.d(TAG, "Cleared all matches")
    }
    
    /**
     * 获取当前匹配规则数量
     */
    suspend fun getMatchCount(): Int {
        return triggerMatcher.getMatchCount()
    }
}

/**
 * 文本变化信息
 */
private data class TextChangeInfo(
    val text: String,
    val timestamp: Long
)

/**
 * 扩展状态
 */
sealed class ExpansionState {
    object Idle : ExpansionState()
    object Matching : ExpansionState()
    object Expanding : ExpansionState()
    data class Completed(val trigger: String, val expandedText: String) : ExpansionState()
    data class Failed(val error: String) : ExpansionState()
}

// 扩展属性
private val AccessibilityNodeInfo.isEditable: Boolean
    get() = isEditable || (className?.toString()?.contains("EditText") == true)