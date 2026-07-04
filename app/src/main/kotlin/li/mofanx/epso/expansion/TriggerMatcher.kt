package li.mofanx.epso.expansion

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 触发器匹配器
 * 负责检测输入文本是否匹配某个触发器
 */
class TriggerMatcher {
    private val exactMatches = ConcurrentHashMap<String, Match>()
    private val regexMatches = ConcurrentHashMap<Regex, Match>()
    private val mutex = Mutex()
    
    // 单词边界分隔符
    private val wordSeparators = setOf(' ', '\n', '\r', '\t', ',')
    
    /**
     * 添加匹配规则
     */
    suspend fun addMatch(match: Match) = mutex.withLock {
        if (match.isRegex && match.regex.isNotEmpty()) {
            try {
                val regex = Regex(match.regex)
                regexMatches[regex] = match
            } catch (exception: Exception) {
                // 忽略无效的正则表达式
            }
        } else if (match.trigger.isNotEmpty()) {
            exactMatches[match.trigger] = match
        }
    }
    
    /**
     * 移除匹配规则
     */
    suspend fun removeMatch(trigger: String) = mutex.withLock {
        exactMatches.remove(trigger)
        // 同时移除对应的正则匹配
        val toRemove = regexMatches.filter { it.value.trigger == trigger }.keys
        toRemove.forEach { regexMatches.remove(it) }
    }
    
    /**
     * 清空所有匹配规则
     */
    suspend fun clear() = mutex.withLock {
        exactMatches.clear()
        regexMatches.clear()
    }
    
    /**
     * 匹配文本，返回匹配结果
     */
    suspend fun match(text: String): MatchResult? = mutex.withLock {
        // 先尝试精确匹配
        val exactResult = findExactMatch(text)
        if (exactResult != null) {
            return exactResult
        }
        
        // 再尝试正则匹配
        return findRegexMatch(text)
    }
    
    /**
     * 精确匹配
     */
    private fun findExactMatch(text: String): MatchResult? {
        // 检查每个精确触发器
        for ((trigger, match) in exactMatches) {
            if (text.endsWith(trigger)) {
                // 检查单词边界
                if (match.word) {
                    val textLength = text.length
                    val triggerLength = trigger.length
                    if (textLength > triggerLength) {
                        val charBefore = text[textLength - triggerLength - 1]
                        if (charBefore !in wordSeparators) {
                            continue
                        }
                    }
                }
                return MatchResult(
                    match = match,
                    matchedText = trigger,
                    startIndex = text.length - trigger.length
                )
            }
        }
        return null
    }
    
    /**
     * 正则匹配
     */
    private fun findRegexMatch(text: String): MatchResult? {
        for ((regex, match) in regexMatches) {
            val result = regex.find(text)
            if (result != null) {
                return MatchResult(
                    match = match,
                    matchedText = result.value,
                    startIndex = result.range.first,
                    endIndex = result.range.last + 1
                )
            }
        }
        return null
    }
    
    /**
     * 获取所有匹配规则数量
     */
    suspend fun getMatchCount(): Int = mutex.withLock {
        exactMatches.size + regexMatches.size
    }
}

/**
 * 匹配结果
 */
data class MatchResult(
    val match: Match,
    val matchedText: String,
    val startIndex: Int,
    val endIndex: Int = startIndex + matchedText.length
)