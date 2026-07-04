package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.app
import li.mofanx.epso.util.LogUtils
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private const val TAG = "VarEvaluator"

/** 变量占位符前缀/后缀（espanso 标准） */
private const val VAR_PREFIX = "{{"
private const val VAR_SUFFIX = "}}"

/** 防止 match 类型变量递归过深 */
private const val MAX_MATCH_DEPTH = 5

/**
 * 变量求值器
 *
 * 负责将 replace 字符串中的 {{varName}} 占位符替换为实际值。
 *
 * 支持的变量类型（对齐 espansogo）：
 * - echo     → 直接返回 params.echo
 * - date     → 当前日期时间，支持 strftime 格式转换
 * - clipboard → 读取剪贴板文本
 * - random   → 从 params.choices 随机选一项
 * - choice   → 同 random（通过 choices/values 提供选项，Phase 4 接入悬浮窗后再升级）
 * - match    → 递归求值另一条规则的 replace（防循环，最大深度 5）
 *
 * 不支持的类型（shell/script/javascript）：忽略，占位符保留原样。
 *
 * @param globalVars 全局变量（来自 MatchStore.globalVars）
 */
class VarEvaluator(
    private val globalVars: List<Var>,
) {
    /**
     * 对 [replace] 字符串进行变量替换。
     * 先应用全局变量，再应用 [localVars]（局部变量可覆盖同名全局变量）。
     */
    suspend fun evaluate(replace: String, localVars: List<Var>, depth: Int = 0): String {
        if (replace.isBlank()) return replace
        if (!replace.contains(VAR_PREFIX)) return replace

        var result = replace

        // 全局变量先处理（局部变量可覆盖）
        for (v in globalVars) {
            result = applyVar(result, v, depth)
        }
        for (v in localVars) {
            result = applyVar(result, v, depth)
        }

        return result
    }

    private suspend fun applyVar(text: String, v: Var, depth: Int): String {
        val placeholder = "$VAR_PREFIX${v.name}$VAR_SUFFIX"
        if (!text.contains(placeholder)) return text

        val value = evaluateVar(v, depth) ?: return text
        return text.replace(placeholder, value)
    }

    private suspend fun evaluateVar(v: Var, depth: Int): String? {
        return try {
            when (v.type) {
                "echo" -> v.params.echo

                "date" -> evaluateDate(v.params)

                "clipboard" -> withContext(Dispatchers.Main) {
                    val cm = app.clipboardManager
                    cm.primaryClip?.getItemAt(0)?.text?.toString()
                }

                "random" -> {
                    val choices = v.params.choices.ifEmpty { v.params.values }
                    choices.randomOrNull()
                }

                "choice" -> {
                    // Phase 3：与 random 相同（Phase 4 接入悬浮窗后升级为交互选择）
                    val choices = v.params.values.ifEmpty { v.params.choices }
                    choices.randomOrNull()
                }

                "match" -> {
                    if (depth >= MAX_MATCH_DEPTH) {
                        LogUtils.e(TAG, "match var recursion depth exceeded for '${v.name}'")
                        null
                    } else {
                        evaluateMatchVar(v.params.trigger, depth)
                    }
                }

                else -> {
                    LogUtils.d(TAG, "Unsupported var type '${v.type}' for '${v.name}', skipping")
                    null
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to evaluate var '${v.name}' (type=${v.type})", e)
            null
        }
    }

    // ── date ──────────────────────────────────────────────────────

    private fun evaluateDate(params: VarParams): String {
        val dt = LocalDateTime.now().plusSeconds(params.offset)
        val format = params.format ?: "%Y-%m-%d"
        val pattern = strftimeToDateTimeFormatter(format)
        return try {
            dt.format(DateTimeFormatter.ofPattern(pattern))
        } catch (e: Exception) {
            LogUtils.e(TAG, "Invalid date format '$format'", e)
            dt.toString()
        }
    }

    /**
     * 将 strftime 格式（espanso 使用）转换为 Java DateTimeFormatter 格式。
     * 覆盖常用格式符，不支持的保留原样。
     */
    private fun strftimeToDateTimeFormatter(fmt: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            if (fmt[i] == '%' && i + 1 < fmt.length) {
                val replacement = when (fmt[i + 1]) {
                    'Y' -> "yyyy"
                    'y' -> "yy"
                    'm' -> "MM"
                    'd' -> "dd"
                    'H' -> "HH"
                    'I' -> "hh"
                    'M' -> "mm"
                    'S' -> "ss"
                    'p' -> "a"
                    'A' -> "EEEE"
                    'a' -> "EEE"
                    'B' -> "MMMM"
                    'b', 'h' -> "MMM"
                    'j' -> "DDD"
                    'Z' -> "zzz"
                    'z' -> "Z"
                    'e' -> "d"          // day without leading zero
                    'k' -> "H"          // hour (0-23) without leading zero
                    'l' -> "h"          // hour (1-12) without leading zero
                    'n' -> "\n"
                    't' -> "\t"
                    '%' -> "%"
                    else -> "%${fmt[i + 1]}"
                }
                sb.append(replacement)
                i += 2
            } else {
                // 对 DateTimeFormatter 特殊字符转义
                val c = fmt[i]
                if (c in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") {
                    sb.append("'$c'")
                } else {
                    sb.append(c)
                }
                i++
            }
        }
        return sb.toString()
    }

    // ── match 递归 ────────────────────────────────────────────────

    private suspend fun evaluateMatchVar(trigger: String?, depth: Int): String? {
        if (trigger.isNullOrBlank()) return null
        val match = MatchStore.exactMatches[trigger] ?: run {
            LogUtils.e(TAG, "match var: trigger '$trigger' not found")
            return null
        }
        // 递归求值目标规则的 replace
        return evaluate(match.replace, match.vars, depth + 1)
    }
}

// ── 大小写传播（propagate_case）──────────────────────────────────

/**
 * 根据触发词的大小写形式，对替换文本进行大小写调整（espanso propagate_case 逻辑）。
 *
 * @param trigger 原始触发词
 * @param replace 替换文本
 * @param style uppercaseStyle：uppercase / capitalize / capitalize_words
 */
fun applyPropagateCase(trigger: String, replace: String, style: String): String {
    if (replace.isEmpty()) return replace

    val letters = trigger.filter { it.isLetter() }
    if (letters.isEmpty()) return replace

    val isAllUpper = letters.all { it.isUpperCase() }
    val isCapitalized = letters.first().isUpperCase() && letters.drop(1).any { it.isLowerCase() }

    return when {
        isAllUpper -> when (style) {
            "capitalize", "capitalize_words" ->
                replace.split(' ').joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercaseChar() }
                }
            else -> replace.uppercase()    // "uppercase"（默认）
        }
        isCapitalized -> replace.replaceFirstChar { it.uppercaseChar() }
        else -> replace
    }
}
