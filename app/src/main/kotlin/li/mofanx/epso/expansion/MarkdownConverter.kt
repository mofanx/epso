package li.mofanx.epso.expansion

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.italic
import androidx.core.text.underline

/**
 * 轻量级 Markdown → CharSequence 转换器。
 *
 * 目前覆盖日常高频语法：
 * - 加粗：**text** / __text__
 * - 斜体：*text* / _text_
 * - 删除线：~~text~~
 * - 行内代码：`code`
 * - 标题：# ## ### 转成粗体+不同大小（用相对尺寸 Span）
 * - 链接：[text](url) 转成带下划线文本
 * - 列表：- / * / 1. 保留为 • 或数字
 * - 代码块：``` 保留为等宽字体块
 *
 * 未覆盖的块级/扩展语法按纯文本输出，保证不丢失内容。
 */
object MarkdownConverter {

    fun toCharSequence(markdown: String): CharSequence {
        if (markdown.isBlank()) return markdown

        // 先处理代码块（避免内部语法被二次处理）
        val codeBlocks = mutableListOf<Pair<IntRange, String>>()
        val codeRegex = "```[\\s\\S]*?```".toRegex()
        val withoutCodeBlocks = codeRegex.replace(markdown) { match ->
            val placeholder = "\u0000CODEBLOCK${codeBlocks.size}\u0000"
            codeBlocks.add(match.range to match.value)
            placeholder
        }

        // 再处理行内代码（同样先替换）
        val inlineCodes = mutableListOf<Pair<IntRange, String>>()
        val inlineCodeRegex = "`([^`]+)`".toRegex()
        val withoutInlineCodes = inlineCodeRegex.replace(withoutCodeBlocks) { match ->
            val placeholder = "\u0000INLINECODE${inlineCodes.size}\u0000"
            inlineCodes.add(match.range to match.groupValues[1])
            placeholder
        }

        // 逐行处理块级语法
        val lines = withoutInlineCodes.lines()
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            val processed = processLine(line)
            sb.append(processed)
            if (index < lines.lastIndex) sb.append("\n")
        }

        // 恢复行内代码
        inlineCodes.reversed().forEach { (range, code) ->
            val placeholder = "\u0000INLINECODE${inlineCodes.indexOf(range to code)}\u0000"
            val start = sb.indexOf(placeholder)
            if (start != -1) {
                sb.replace(start, start + placeholder.length, code)
                sb.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    start + code.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        // 恢复代码块
        codeBlocks.reversed().forEach { (range, block) ->
            val index = codeBlocks.indexOf(range to block)
            val placeholder = "\u0000CODEBLOCK${index}\u0000"
            val start = sb.indexOf(placeholder)
            if (start != -1) {
                val content = block.removeSurrounding("```", "```").trim()
                sb.replace(start, start + placeholder.length, content)
                sb.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    start + content.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        return sb
    }

    private fun processLine(line: String): CharSequence {
        // 标题
        val headingMatch = "^(#{1,6})\\s+(.*)$".toRegex().matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            return buildSpannedString {
                inSpans(
                    android.text.style.RelativeSizeSpan(when (level) {
                        1 -> 1.5f
                        2 -> 1.3f
                        3 -> 1.15f
                        else -> 1.0f
                    }),
                ) {
                    bold { append(text) }
                }
            }
        }

        // 无序列表
        val bulletMatch = "^([-*+])\\s+(.*)$".toRegex().matchEntire(line)
        if (bulletMatch != null) {
            return "• ${inlineStyle(bulletMatch.groupValues[2])}"
        }

        // 有序列表
        val orderedMatch = "^(\\d+)\\.\\s+(.*)$".toRegex().matchEntire(line)
        if (orderedMatch != null) {
            return "${orderedMatch.groupValues[1]}. ${inlineStyle(orderedMatch.groupValues[2])}"
        }

        // 普通行
        return inlineStyle(line)
    }

    private fun inlineStyle(text: String): CharSequence {
        var result: CharSequence = text

        // 删除线 ~~text~~
        result = applyPattern(result, "~~(.+?)~~".toRegex()) { match ->
            buildSpannedString {
                inSpans(StrikethroughSpan()) { append(match.groupValues[1]) }
            }
        }

        // 加粗 **text** / __text__
        result = applyPattern(result, "\\*\\*(.+?)\\*\\*|__(.+?)__".toRegex()) { match ->
            val content = match.groupValues[1].ifEmpty { match.groupValues[2] }
            buildSpannedString {
                bold { append(content) }
            }
        }

        // 斜体 *text* / _text_
        result = applyPattern(result, "\\*(.+?)\\*|_(.+?)_".toRegex()) { match ->
            val content = match.groupValues[1].ifEmpty { match.groupValues[2] }
            buildSpannedString {
                italic { append(content) }
            }
        }

        // 链接 [text](url)
        result = applyPattern(result, "\\[([^\\]]+)\\]\\(([^)]+)\\)".toRegex()) { match ->
            val label = match.groupValues[1]
            buildSpannedString {
                underline { append(label) }
            }
        }

        return result
    }

    /**
     * 在 CharSequence 中按正则替换匹配项，并保持原有 Spannable。
     */
    private fun applyPattern(
        source: CharSequence,
        regex: Regex,
        factory: (kotlin.text.MatchResult) -> CharSequence,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        var lastEnd = 0
        for (match in regex.findAll(source)) {
            sb.append(source.subSequence(lastEnd, match.range.first))
            sb.append(factory(match))
            lastEnd = match.range.last + 1
        }
        sb.append(source.subSequence(lastEnd, source.length))
        return sb
    }
}
