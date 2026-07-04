package li.mofanx.epso.expansion

import android.view.accessibility.AccessibilityNodeInfo
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.util.LogUtils
import kotlinx.coroutines.delay

/**
 * 文本替换引擎
 * 负责通过无障碍服务执行文本替换操作
 */
class TextReplacer(private val a11yService: A11yService) {
    
    companion object {
        private const val TAG = "TextReplacer"
        private const val CURSOR_MARKER = "\$|\$"
    }
    
    /**
     * 执行文本替换
     * @param node 可编辑的文本节点
     * @param originalText 原始文本
     * @param replacement 替换文本
     * @param matchResult 匹配结果
     */
    suspend fun replace(
        node: AccessibilityNodeInfo,
        originalText: String,
        replacement: String,
        matchResult: MatchResult
    ): Boolean {
        try {
            // 处理光标标记
            val (processedReplacement, cursorOffset) = processCursorMarker(replacement)
            
            // 计算要删除的文本范围
            val startIndex = matchResult.startIndex
            val endIndex = matchResult.endIndex
            
            // 构建新文本
            val newText = buildString {
                append(originalText.substring(0, startIndex))
                append(processedReplacement)
                append(originalText.substring(endIndex))
            }
            
            // 执行替换操作
            return performTextReplacement(node, newText, cursorOffset)
            
        } catch (exception: Exception) {
            LogUtils.e(TAG, "Text replacement failed", exception)
            return false
        }
    }
    
    /**
     * 处理光标标记 $|$
     */
    private fun processCursorMarker(text: String): Pair<String, Int> {
        val markerIndex = text.indexOf(CURSOR_MARKER)
        if (markerIndex == -1) {
            return Pair(text, text.length)
        }
        
        val processedText = text.replace(CURSOR_MARKER, "")
        val cursorOffset = markerIndex
        return Pair(processedText, cursorOffset)
    }
    
    /**
     * 执行实际的文本替换操作
     */
    private suspend fun performTextReplacement(
        node: AccessibilityNodeInfo,
        newText: String,
        cursorOffset: Int
    ): Boolean {
        try {
            // 方法1: 直接设置文本（适用于某些应用）
            if (node.isEditable) {
                node.text = newText
                delay(50) // 等待文本更新
                
                // 尝试设置光标位置
                if (cursorOffset >= 0 && cursorOffset <= newText.length) {
                    setCursorPosition(node, cursorOffset)
                }
                
                return true
            }
            
            // 方法2: 通过焦点和文本操作（更通用的方法）
            return performActionBasedReplacement(node, newText, cursorOffset)
            
        } catch (exception: Exception) {
            LogUtils.e(TAG, "Perform text replacement failed", exception)
            return false
        }
    }
    
    /**
     * 基于操作的文本替换（更可靠的方法）
     */
    private suspend fun performActionBasedReplacement(
        node: AccessibilityNodeInfo,
        newText: String,
        cursorOffset: Int
    ): Boolean {
        try {
            // 1. 全选文本
            val args = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            delay(30)
            
            // 2. 删除选中的文本
            val textArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)
            delay(50)
            
            // 3. 设置光标位置
            if (cursorOffset >= 0 && cursorOffset <= newText.length) {
                setCursorPosition(node, cursorOffset)
            }
            
            return true
            
        } catch (exception: Exception) {
            LogUtils.e(TAG, "Action based replacement failed", exception)
            return false
        }
    }
    
    /**
     * 设置光标位置
     */
    private fun setCursorPosition(node: AccessibilityNodeInfo, position: Int) {
        try {
            val args = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (exception: Exception) {
            LogUtils.e(TAG, "Set cursor position failed", exception)
        }
    }
    
    /**
     * 撤销上一次替换（如果可能）
     */
    suspend fun undo(node: AccessibilityNodeInfo): Boolean {
        try {
            // 尝试发送撤销快捷键 Ctrl+Z
            // 这里需要根据具体应用实现
            return false
        } catch (exception: Exception) {
            LogUtils.e(TAG, "Undo failed", exception)
            return false
        }
    }
    
    /**
     * 设置剪贴板内容
     */
    suspend fun setClipboard(text: String): Boolean {
        try {
            // 利用 Shizuku 设置剪贴板（如果有权限）
            // 或者回退到无障碍服务的剪贴板操作
            return false
        } catch (exception: Exception) {
            LogUtils.e(TAG, "Set clipboard failed", exception)
            return false
        }
    }
}

// 扩展属性
private val AccessibilityNodeInfo.isEditable: Boolean
    get() = isEditable || (className?.toString()?.contains("EditText") == true)