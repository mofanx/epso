package li.mofanx.epso.expansion

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.util.LogUtils

private const val TAG = "TextReplacer"
private const val CURSOR_MARKER = "\$|\$"

/**
 * 文本替换引擎
 *
 * 核心流程：
 * 1. 调用 [VarEvaluator] 对 replace 字符串求值（展开 {{var}} 占位符）
 * 2. 若 match.propagateCase = true，对替换文本进行大小写调整
 * 3. 处理 $|$ 光标位置标记
 * 4. 通过无障碍服务执行文本替换
 */
class TextReplacer(private val a11yService: A11yService) {

    /**
     * 执行文本替换
     * @param node         目标可编辑节点
     * @param originalText 输入框当前全文
     * @param matchResult  匹配结果（含触发词位置）
     * @param varEvaluator 变量求值器（已绑定全局变量）
     */
    suspend fun replace(
        node: AccessibilityNodeInfo,
        originalText: String,
        matchResult: MatchResult,
        varEvaluator: VarEvaluator,
    ): Boolean {
        return try {
            val match = matchResult.match

            // 1. 变量求值
            var replacement = varEvaluator.evaluate(match.replace, match.vars)

            // 2. 大小写传播
            if (match.propagateCase) {
                replacement = applyPropagateCase(
                    trigger = matchResult.matchedText,
                    replace = replacement,
                    style = match.uppercaseStyle,
                )
            }

            // 3. 处理光标标记 $|$
            val (processedText, cursorOffset) = processCursorMarker(replacement)

            // 4. 构建新的完整文本（触发词区间替换为展开文本）
            val newText = buildString {
                append(originalText.substring(0, matchResult.startIndex))
                append(processedText)
                append(originalText.substring(matchResult.endIndex))
            }

            // 5. 记录替换前状态（供 undo 使用）
            lastReplacement = originalText to node

            // 6. 执行替换
            performTextReplacement(node, newText, matchResult.startIndex + cursorOffset)

        } catch (e: Exception) {
            LogUtils.e(TAG, "Text replacement failed", e)
            false
        }
    }

    // ── 兼容旧接口（ExpansionTestPage 不再调用，保留给可能的外部调用） ──────

    @Deprecated("Use replace(node, originalText, matchResult, varEvaluator)")
    suspend fun replace(
        node: AccessibilityNodeInfo,
        originalText: String,
        replacement: String,
        matchResult: MatchResult,
    ): Boolean {
        val (processedText, cursorOffset) = processCursorMarker(replacement)
        val newText = buildString {
            append(originalText.substring(0, matchResult.startIndex))
            append(processedText)
            append(originalText.substring(matchResult.endIndex))
        }
        return performTextReplacement(node, newText, matchResult.startIndex + cursorOffset)
    }

    // ──────────────────────────────────────────────────────────────
    // 私有方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 处理 $|$ 光标标记，返回（清除标记后的文本，光标偏移量）
     */
    private fun processCursorMarker(text: String): Pair<String, Int> {
        val idx = text.indexOf(CURSOR_MARKER)
        return if (idx == -1) {
            text to text.length
        } else {
            text.replace(CURSOR_MARKER, "") to idx
        }
    }

    /**
     * 执行实际的文本替换操作
     * 优先使用 ACTION_SET_TEXT（更通用），失败时直接设置 node.text
     */
    private suspend fun performTextReplacement(
        node: AccessibilityNodeInfo,
        newText: String,
        cursorPosition: Int,
    ): Boolean {
        return try {
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText,
                )
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            delay(50)

            if (success) {
                setCursorPosition(node, cursorPosition.coerceIn(0, newText.length))
            } else {
                // 回退：直接设置文本
                node.text = newText
                delay(50)
                setCursorPosition(node, cursorPosition.coerceIn(0, newText.length))
            }

            true
        } catch (e: Exception) {
            LogUtils.e(TAG, "performTextReplacement failed", e)
            false
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
        } catch (e: Exception) {
            LogUtils.e(TAG, "setCursorPosition failed", e)
        }
    }

    /**
     * 设置剪贴板（目前通过 ClipboardManager，Shizuku 可用时可升级）
     */
    suspend fun setClipboard(text: String): Boolean {
        return try {
            val cm = a11yService.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return false
            cm.setPrimaryClip(android.content.ClipData.newPlainText("expansion", text))
            true
        } catch (e: Exception) {
            LogUtils.e(TAG, "setClipboard failed", e)
            false
        }
    }

    /**
     * 撤销上次替换（通过 ACTION_SET_TEXT 还原替换前的完整文本）
     *
     * @param node 当前焦点节点（优先使用，比保存的引用更新鲜）；传 null 时回退到保存的节点引用
     */
    private var lastReplacement: Pair<String, AccessibilityNodeInfo>? = null

    suspend fun undo(node: AccessibilityNodeInfo? = null): Boolean {
        val (original, savedNode) = lastReplacement ?: return false
        val target = node ?: savedNode
        return try {
            performTextReplacement(target, original, original.length)
        } finally {
            lastReplacement = null
        }
    }
}


