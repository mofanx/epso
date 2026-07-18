package li.mofanx.epso.expansion

import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.text.Spanned
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.delay
import li.mofanx.epso.app
import li.mofanx.epso.service.A11yService
import li.mofanx.epso.util.LogUtils
import java.io.File

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
            val (replacement, cursorOffset) = resolveReplacement(match, matchResult, varEvaluator)

            // 图片输出走剪贴板粘贴
            if (!match.imagePath.isNullOrEmpty()) {
                return performImageReplacement(node, originalText, matchResult, match.imagePath)
            }

            lastReplacement = originalText to node

            if (shouldUseClipboard(match)) {
                performClipboardReplacement(
                    node = node,
                    originalText = originalText,
                    matchResult = matchResult,
                    replacement = replacement,
                    cursorOffset = cursorOffset,
                )
            } else {
                performTextReplacement(
                    node = node,
                    newText = buildFullText(originalText, matchResult, replacement),
                    cursorPosition = matchResult.startIndex + cursorOffset,
                )
            }
        } catch (e: FormCanceledException) {
            throw e
        } catch (e: Exception) {
            LogUtils.e(TAG, "Text replacement failed", e)
            false
        }
    }

    /**
     * 直接插入已求值后的替换文本（用于表单/搜索等需要先弹窗再替换的场景）
     */
    suspend fun replace(
        node: AccessibilityNodeInfo,
        originalText: String,
        replacement: String,
        matchResult: MatchResult,
        match: Match,
    ): Boolean {
        return try {
            // 1. 大小写传播
            var finalReplacement = replacement
            if (match.propagateCase) {
                finalReplacement = applyPropagateCase(
                    trigger = matchResult.matchedText,
                    replace = finalReplacement,
                    style = match.uppercaseStyle,
                )
            }

            // 2. 处理光标标记 $|$
            val (processedText, cursorOffset) = processCursorMarker(finalReplacement)

            // 3. 记录替换前状态（供 undo 使用）
            lastReplacement = originalText to node

            if (shouldUseClipboard(match)) {
                performClipboardReplacement(
                    node = node,
                    originalText = originalText,
                    matchResult = matchResult,
                    replacement = processedText,
                    cursorOffset = cursorOffset,
                )
            } else {
                performTextReplacement(
                    node = node,
                    newText = buildFullText(originalText, matchResult, processedText),
                    cursorPosition = matchResult.startIndex + cursorOffset,
                )
            }
        } catch (e: FormCanceledException) {
            throw e
        } catch (e: Exception) {
            LogUtils.e(TAG, "Text replacement failed", e)
            false
        }
    }

    // ── 兼容旧接口（内部旧调用逐步迁移到带 match 的版本） ──────

    @Deprecated("Use replace(node, originalText, replacement, matchResult, match)")
    suspend fun replace(
        node: AccessibilityNodeInfo,
        originalText: String,
        replacement: String,
        matchResult: MatchResult,
    ): Boolean {
        return replace(
            node = node,
            originalText = originalText,
            replacement = replacement,
            matchResult = matchResult,
            match = matchResult.match,
        )
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
     * 根据 match 中实际设置了哪个输出字段，求值并返回最终文本与光标偏移。
     */
    private suspend fun resolveReplacement(
        match: Match,
        matchResult: MatchResult,
        varEvaluator: VarEvaluator,
    ): Pair<CharSequence, Int> {
        val rawSource = when {
            match.replace.isNotEmpty() -> match.replace
            !match.markdown.isNullOrEmpty() -> match.markdown
            !match.html.isNullOrEmpty() -> match.html
            else -> ""
        }

        // 变量求值
        var replacement = if (rawSource.isNotEmpty()) {
            varEvaluator.evaluate(rawSource, match.vars)
        } else {
            ""
        }

        // 大小写传播
        if (match.propagateCase) {
            replacement = applyPropagateCase(
                trigger = matchResult.matchedText,
                replace = replacement,
                style = match.uppercaseStyle,
            )
        }

        // 光标标记
        val (processedText, cursorOffset) = processCursorMarker(replacement)

        // 根据输出类型转换
        val result = when {
            !match.html.isNullOrEmpty() -> HtmlCompat.fromHtml(processedText, HtmlCompat.FROM_HTML_MODE_COMPACT)
            !match.markdown.isNullOrEmpty() -> MarkdownConverter.toCharSequence(processedText)
            else -> processedText
        }

        return result to cursorOffset
    }

    /**
     * 构建完整文本：触发词区间替换为展开内容。
     */
    private fun buildFullText(originalText: String, matchResult: MatchResult, replacement: CharSequence): CharSequence {
        return buildString {
            append(originalText.substring(0, matchResult.startIndex))
            append(replacement)
            append(originalText.substring(matchResult.endIndex))
        }
    }

    /**
     * 判断当前 match 是否需要走剪贴板+粘贴。
     * 优先级：match.force_clipboard > effectiveBackend（force_mode / group backend）
     */
    private fun shouldUseClipboard(match: Match): Boolean {
        return match.forceClipboard
            || match.effectiveBackend.equals("clipboard", ignoreCase = true)
            || match.effectiveBackend.equals("keys", ignoreCase = true) // 手机上无法模拟键入，回退到剪贴板
    }

    /**
     * 执行实际的文本替换操作
     * 优先使用 ACTION_SET_TEXT（更通用），失败时直接设置 node.text
     */
    private suspend fun performTextReplacement(
        node: AccessibilityNodeInfo,
        newText: CharSequence,
        cursorPosition: Int,
    ): Boolean {
        return try {
            // 先请求焦点，避免弹窗关闭后目标节点失焦导致 ACTION_SET_TEXT 无效
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(50)

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText,
                )
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            delay(100)

            // 验证文本是否真正写入（部分 App 的自定义输入框 performAction 返回 true 但不生效）
            if (success && node.refresh() && node.text?.toString() == newText.toString()) {
                setCursorPosition(node, cursorPosition.coerceIn(0, newText.length))
                return true
            }

            // 回退：直接设置节点文本
            node.text = newText
            delay(100)
            val refreshed = node.refresh()
            val actualText = node.text?.toString()
            if (refreshed && actualText == newText.toString()) {
                setCursorPosition(node, cursorPosition.coerceIn(0, newText.length))
                true
            } else {
                LogUtils.e(TAG, "Text replacement not reflected in UI, expected '$newText' got '$actualText'")
                false
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "performTextReplacement failed", e)
            false
        }
    }

    /**
     * 使用剪贴板+粘贴的方式插入内容：先删除触发词，定位光标，再粘贴。
     * 若粘贴失败，回退到 ACTION_SET_TEXT。
     */
    private suspend fun performClipboardReplacement(
        node: AccessibilityNodeInfo,
        originalText: String,
        matchResult: MatchResult,
        replacement: CharSequence,
        cursorOffset: Int,
    ): Boolean {
        val leftPart = originalText.substring(0, matchResult.startIndex)
        val rightPart = originalText.substring(matchResult.endIndex)
        val textWithoutTrigger = leftPart + rightPart
        val pastePosition = matchResult.startIndex.coerceIn(0, textWithoutTrigger.length)

        return try {
            if (!setClipboard(replacement.toString())) {
                return performTextReplacement(
                    node = node,
                    newText = buildFullText(originalText, matchResult, replacement),
                    cursorPosition = matchResult.startIndex + cursorOffset,
                )
            }

            // 先删除触发词
            if (!performTextReplacement(node, textWithoutTrigger, pastePosition)) {
                return false
            }

            // 定位光标到原来触发词位置
            setCursorPosition(node, pastePosition)
            delay(80)

            // 执行粘贴
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(150)

            if (!pasted || !node.refresh() || node.text?.toString() != leftPart + replacement + rightPart) {
                // 粘贴未生效，回退到完整 set text
                LogUtils.e(TAG, "ACTION_PASTE failed, fallback to ACTION_SET_TEXT")
                return performTextReplacement(
                    node = node,
                    newText = buildFullText(originalText, matchResult, replacement),
                    cursorPosition = matchResult.startIndex + cursorOffset,
                )
            }

            // 光标定位
            val finalPosition = (matchResult.startIndex + cursorOffset).coerceIn(0, leftPart.length + replacement.length + rightPart.length)
            setCursorPosition(node, finalPosition)
            true
        } catch (e: Exception) {
            LogUtils.e(TAG, "Clipboard replacement failed", e)
            performTextReplacement(
                node = node,
                newText = buildFullText(originalText, matchResult, replacement),
                cursorPosition = matchResult.startIndex + cursorOffset,
            )
        }
    }

    /**
     * 图片输出：将图片文件复制到剪贴板，尝试 ACTION_PASTE；失败则插入文件名。
     */
    private suspend fun performImageReplacement(
        node: AccessibilityNodeInfo,
        originalText: String,
        matchResult: MatchResult,
        imagePath: String,
    ): Boolean {
        return try {
            val file = File(imagePath.replace("%CONFIG%", try {
                MatchStore.getWorkspaceDir().absolutePath
            } catch (e: Throwable) {
                ""
            }))
            if (!file.exists()) {
                LogUtils.e(TAG, "Image not found: $imagePath")
                return insertFallbackText(node, originalText, matchResult, "[图片: $imagePath]")
            }

            val uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.provider",
                file,
            )

            val cm = a11yService.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return insertFallbackText(
                        node, originalText, matchResult, "[图片: ${file.name}]"
                    )

            cm.setPrimaryClip(ClipData.newUri(app.contentResolver, "expansion image", uri))

            val leftPart = originalText.substring(0, matchResult.startIndex)
            val rightPart = originalText.substring(matchResult.endIndex)
            val textWithoutTrigger = leftPart + rightPart
            val pastePosition = matchResult.startIndex.coerceIn(0, textWithoutTrigger.length)

            if (!performTextReplacement(node, textWithoutTrigger, pastePosition)) {
                return insertFallbackText(node, originalText, matchResult, "[图片: ${file.name}]")
            }

            setCursorPosition(node, pastePosition)
            delay(80)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(150)

            if (!pasted) {
                insertFallbackText(node, originalText, matchResult, "[图片: ${file.name}]")
            } else {
                true
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Image replacement failed", e)
            insertFallbackText(node, originalText, matchResult, "[图片: $imagePath]")
        }
    }

    /**
     * 在触发词位置插入纯文本替代内容（用于图片/剪贴板失败回退）。
     */
    private suspend fun insertFallbackText(
        node: AccessibilityNodeInfo,
        originalText: String,
        matchResult: MatchResult,
        fallback: String,
    ): Boolean {
        return performTextReplacement(
            node = node,
            newText = buildFullText(originalText, matchResult, fallback),
            cursorPosition = matchResult.startIndex + fallback.length,
        )
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


