package li.mofanx.epso.expansion

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 触发器匹配器
 *
 * 支持：
 * - 精确匹配（allTriggers 展开后各自入库）
 * - 正则匹配
 * - word / left_word / right_word 细粒度单词边界（对照 espansogo 实现）
 *
 * 单词边界规则（与 espanso 一致）：
 * - word = true       → 触发词左右两侧必须是分隔符或字符串边界
 * - left_word = true  → 触发词左侧必须是分隔符或字符串起始
 * - right_word = true → 触发词右侧必须是分隔符或字符串末尾
 * - word = false, left_word = false, right_word = false → 无边界限制（子串匹配）
 */
class TriggerMatcher {

    // trigger → Match（每个 allTriggers 条目分别入库）
    private val exactMatches = LinkedHashMap<String, Match>()

    // regex pattern → (Regex, Match)
    private val regexMatches = LinkedHashMap<String, Pair<Regex, Match>>()

    private val mutex = Mutex()

    // 单词分隔符集合（与 espansogo 保持一致）
    private val wordSeparators = setOf(
        ' ', '\n', '\r', '\t', ',', '.', ';', ':', '!', '?',
        '(', ')', '[', ']', '{', '}', '"', '\'', '/', '\\',
        '-', '+', '*', '=', '<', '>', '&', '|', '@', '#', '%',
        '^', '~', '`',
    )

    // ──────────────────────────────────────────────────────────────
    // 规则管理
    // ──────────────────────────────────────────────────────────────

    suspend fun addMatch(match: Match) = mutex.withLock {
        if (match.isRegex) {
            runCatching { Regex(match.regex) }.getOrNull()?.let { regex ->
                regexMatches[match.regex] = regex to match
            }
        } else {
            for (trigger in match.allTriggers) {
                exactMatches[trigger] = match
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        exactMatches.clear()
        regexMatches.clear()
    }

    suspend fun getMatchCount(): Int = mutex.withLock {
        exactMatches.values.distinct().size + regexMatches.size
    }

    // ──────────────────────────────────────────────────────────────
    // 匹配
    // ──────────────────────────────────────────────────────────────

    /**
     * 在 [text] 中查找匹配的触发词，返回第一个匹配结果。
     * 精确匹配优先于正则匹配。
     */
    suspend fun match(text: String): MatchResult? = mutex.withLock {
        findExactMatch(text) ?: findRegexMatch(text)
    }

    // ── 精确匹配 ──────────────────────────────────────────────────

    private fun findExactMatch(text: String): MatchResult? {
        // 从最长触发词开始匹配（避免短触发词误触长触发词）
        val sorted = exactMatches.entries.sortedByDescending { it.key.length }
        for ((trigger, match) in sorted) {
            val idx = text.lastIndexOf(trigger)
            if (idx < 0) continue

            if (checkWordBoundary(text, idx, trigger.length, match)) {
                return MatchResult(
                    match = match,
                    matchedText = trigger,
                    startIndex = idx,
                    endIndex = idx + trigger.length,
                )
            }
        }
        return null
    }

    // ── 正则匹配 ──────────────────────────────────────────────────

    private fun findRegexMatch(text: String): MatchResult? {
        for ((_, pair) in regexMatches) {
            val (regex, match) = pair
            val result = regex.find(text) ?: continue
            return MatchResult(
                match = match,
                matchedText = result.value,
                startIndex = result.range.first,
                endIndex = result.range.last + 1,
            )
        }
        return null
    }

    // ── 单词边界检查 ──────────────────────────────────────────────

    /**
     * 检查 [text] 中位于 [startIndex]..[startIndex]+[len] 的触发词是否满足边界条件。
     */
    private fun checkWordBoundary(
        text: String,
        startIndex: Int,
        len: Int,
        match: Match,
    ): Boolean {
        val checkLeft = match.word || match.leftWord
        val checkRight = match.word || match.rightWord

        if (checkLeft) {
            val leftOk = startIndex == 0 || text[startIndex - 1] in wordSeparators
            if (!leftOk) return false
        }

        if (checkRight) {
            val afterIndex = startIndex + len
            val rightOk = afterIndex >= text.length || text[afterIndex] in wordSeparators
            if (!rightOk) return false
        }

        return true
    }
}

/**
 * 匹配结果
 */
data class MatchResult(
    val match: Match,
    val matchedText: String,
    val startIndex: Int,
    val endIndex: Int = startIndex + matchedText.length,
)
