package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.app
import li.mofanx.epso.util.LogUtils
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private const val TAG = "VarEvaluator"

/** 变量占位符前缀/后缀（espanso 标准） */
private const val VAR_PREFIX = "{{"
private const val VAR_SUFFIX = "}}"

/** 防止 match 类型变量递归过深 */
private const val MAX_MATCH_DEPTH = 5

/** 用户取消表单或表单超时/无权限，外部应终止本次扩展 */
class FormCanceledException : Exception("Form canceled or timed out")

/**
 * 变量求值器
 *
 * 负责将 replace 字符串中的 {{varName}} 占位符替换为实际值。
 *
 * 支持的变量类型（对齐 espansogo）：
 * - echo       → 直接返回 params.echo
 * - date       → 当前日期时间，支持 strftime 格式转换
 * - clipboard  → 读取剪贴板文本
 * - random     → 从 params.choices 随机选一项
 * - choice     → 同 random（通过 choices/values 提供选项）
 * - shell      → 执行 shell 命令
 * - script     → 执行外部脚本
 * - javascript → 使用 Rhino 执行 JS 代码，返回最后一行表达式结果
 * - http       → 发送 HTTP 请求并返回响应文本，支持 json_path 提取
 * - match      → 递归求值另一条规则的 replace（防循环，最大深度 5）
 * - form       → 启动表单悬浮窗收集字段值，返回 Map<String, String>
 *
 * @param globalVars 全局变量（来自 MatchStore.globalVars）
 * @param formLauncher 表单启动器：传入 form 类型变量，返回用户填写结果，取消/超时返回 null
 */
class VarEvaluator(
    private val globalVars: List<Var>,
    private val formLauncher: suspend (formVar: Var) -> Map<String, String>? = { null },
    private val choiceLauncher: suspend (choiceVar: Var) -> String? = { null },
) {
    /**
     * 对 [replace] 字符串进行变量替换。
     * 局部变量覆盖同名全局变量，合并后按依赖拓扑排序统一求值。
     */
    suspend fun evaluate(replace: String, localVars: List<Var>, depth: Int = 0): String {
        if (replace.isBlank()) return replace
        if (!replace.contains(VAR_PREFIX)) return replace

        // 合并：局部变量覆盖同名全局变量
        val merged = buildMap<String, Var> {
            globalVars.forEach { put(it.name, it) }
            localVars.forEach { put(it.name, it) }
        }.values

        // 阶段一：按依赖拓扑排序求值所有变量
        val sortedVars = topologicalSort(merged)
        val values = mutableMapOf<String, Any?>()
        for (v in sortedVars) {
            values[v.name] = evaluateVar(v, values, depth)
        }

        // 阶段二：替换 replace 中所有 {{name}} 或 {{name.field}}
        return replacePlaceholders(replace, values)
    }

    /**
     * 根据 depends_on 对变量做拓扑排序，保证依赖先求值。
     * 若存在循环依赖，剩余变量按原顺序追加并打印警告。
     */
    private fun topologicalSort(vars: Collection<Var>): List<Var> {
        val varMap = vars.associateBy { it.name }
        val inDegree = vars.associate { it.name to it.dependsOn.count { dep -> dep in varMap } }.toMutableMap()
        val dependents = mutableMapOf<String, MutableList<String>>()
        for (v in vars) {
            for (dep in v.dependsOn) {
                if (dep in varMap) {
                    dependents.getOrPut(dep) { mutableListOf() }.add(v.name)
                }
            }
        }

        val queue = ArrayDeque(vars.filter { inDegree[it.name] == 0 }.map { it.name })
        val result = mutableListOf<Var>()
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in visited) continue
            visited.add(name)
            val v = varMap[name] ?: continue
            result.add(v)
            for (dependent in dependents[name] ?: emptyList()) {
                inDegree[dependent] = (inDegree[dependent] ?: 0) - 1
                if (inDegree[dependent] == 0) queue.add(dependent)
            }
        }

        val remaining = vars.filter { it.name !in visited }
        if (remaining.isNotEmpty()) {
            LogUtils.e(TAG, "Circular or unresolved dependencies among: ${remaining.map { it.name }}")
            result.addAll(remaining)
        }

        return result
    }

    private suspend fun evaluateVar(v: Var, values: Map<String, Any?>, depth: Int): Any? {
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
                    if (choices.isNotEmpty()) {
                        choices.random()
                    } else {
                        // min/max 数值范围模式（espanso 原始功能）
                        val min = v.params.min ?: 0
                        val max = v.params.max ?: 100
                        Random.nextInt(min, max + 1).toString()
                    }
                }

                "choice" -> {
                    val choices = v.params.values.ifEmpty { v.params.choices }
                    if (choices.isEmpty()) {
                        null
                    } else {
                        choiceLauncher(v) ?: throw FormCanceledException()
                    }
                }

                "shell" -> {
                    ShellRunner.run(
                        cmd = v.params.cmd ?: "",
                        shell = v.params.shell,
                        values = if (v.injectVars) values else emptyMap(),
                        injectVars = v.injectVars,
                        trim = v.params.trim,
                        debug = v.params.debug,
                        ignoreError = v.params.ignoreError,
                    )
                }

                "script" -> {
                    ScriptRunner.run(
                        args = v.params.args,
                        values = if (v.injectVars) values else emptyMap(),
                        injectVars = v.injectVars,
                        trim = v.params.trim,
                        debug = v.params.debug,
                        ignoreError = v.params.ignoreError,
                    )
                }

                "javascript" -> {
                    JavaScriptVarRunner.run(
                        code = v.params.code ?: "",
                        values = if (v.injectVars) values else emptyMap(),
                        injectVars = v.injectVars,
                        trim = v.params.trim,
                        debug = v.params.debug,
                        ignoreError = v.params.ignoreError,
                    )
                }

                "http" -> {
                    val resolvedUrl = resolveVarPlaceholders(v.params.url ?: "", values)
                    val resolvedBody = v.params.body?.let { resolveVarPlaceholders(it, values) }
                    HttpVarRunner.run(
                        url = resolvedUrl,
                        method = v.params.method,
                        headers = v.params.headers,
                        body = resolvedBody,
                        jsonPath = v.params.jsonPath,
                        trim = v.params.trim,
                        ignoreError = v.params.ignoreError,
                        debug = v.params.debug,
                    )
                }

                "match" -> {
                    if (depth >= MAX_MATCH_DEPTH) {
                        LogUtils.e(TAG, "match var recursion depth exceeded for '${v.name}'")
                        null
                    } else {
                        evaluateMatchVar(v.params.trigger, depth)
                    }
                }

                "form" -> {
                    formLauncher(v) ?: throw FormCanceledException()
                }

                else -> {
                    LogUtils.d(TAG, "Unsupported var type '${v.type}' for '${v.name}', skipping")
                    null
                }
            }
        } catch (e: FormCanceledException) {
            throw e
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to evaluate var '${v.name}' (type=${v.type})", e)
            null
        }
    }

    /**
     * 统一替换字符串中的 {{varName}} / {{formName.fieldName}} 占位符。
     * 同时用于 replace 文本和 http 变量的 url/body。
     */
    private fun replacePlaceholders(text: String, values: Map<String, Any?>): String {
        return resolveVarPlaceholders(text, values)
    }

    private fun resolveVarPlaceholders(text: String, values: Map<String, Any?>): String {
        val regex = Regex("""\{\{([^{}]+)\}\}""")
        return regex.replace(text) { matchResult ->
            val path = matchResult.groupValues[1].trim()
            resolvePlaceholder(path, values) ?: matchResult.value
        }
    }

    private fun resolvePlaceholder(path: String, values: Map<String, Any?>): String? {
        val dotIndex = path.indexOf('.')
        return if (dotIndex == -1) {
            when (val v = values[path]) {
                is String -> v
                null -> null
                else -> null
            }
        } else {
            val name = path.substring(0, dotIndex)
            val field = path.substring(dotIndex + 1)
            val formValues = values[name]
            if (formValues is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (formValues as Map<String, String>)[field]
            } else {
                null
            }
        }
    }

    // ── date ──────────────────────────────────────────────────────

    private fun evaluateDate(params: VarParams): String {
        val zone = try {
            params.tz?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        } catch (e: Exception) {
            LogUtils.e(TAG, "Invalid timezone '${params.tz}', fallback to system default", e)
            ZoneId.systemDefault()
        }
        val zdt = ZonedDateTime.now(zone).plusSeconds(params.offset)
        val format = params.format ?: "%Y-%m-%d"
        val pattern = strftimeToDateTimeFormatter(format)
        return try {
            zdt.format(DateTimeFormatter.ofPattern(pattern))
        } catch (e: Exception) {
            LogUtils.e(TAG, "Invalid date format '$format'", e)
            zdt.toString()
        }
    }

    /**
     * 将 strftime 格式（espanso 使用）转换为 Java DateTimeFormatter 格式。
     *
     * 非格式符的字母必须用 DateTimeFormatter 的 `'...'` 语法转义；
     * 单引号本身转义为 `''`。
     */
    private fun strftimeToDateTimeFormatter(fmt: String): String {
        // 先把 strftime 序列替换为 DTF 序列，非格式符收集后统一用 '...' 包裹
        data class Token(val isDtf: Boolean, val value: String)

        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < fmt.length) {
            if (fmt[i] == '%' && i + 1 < fmt.length) {
                val dtf = when (fmt[i + 1]) {
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
                    'e' -> "d"
                    'k' -> "H"
                    'l' -> "h"
                    'n' -> null.also { tokens += Token(false, "\n") }
                    't' -> null.also { tokens += Token(false, "\t") }
                    '%' -> null.also { tokens += Token(false, "%") }
                    else -> null.also { tokens += Token(false, "%${fmt[i + 1]}") }
                }
                if (dtf != null) tokens += Token(isDtf = true, value = dtf)
                i += 2
            } else {
                tokens += Token(isDtf = false, value = fmt[i].toString())
                i++
            }
        }

        // 合并相邻非 DTF token，用 '...' 包裹（单引号自身转义为 ''）
        val sb = StringBuilder()
        var j = 0
        while (j < tokens.size) {
            val tok = tokens[j]
            if (tok.isDtf) {
                sb.append(tok.value)
                j++
            } else {
                // 收集连续的 literal token
                val literal = buildString {
                    while (j < tokens.size && !tokens[j].isDtf) {
                        append(tokens[j].value)
                        j++
                    }
                }
                if (literal.isNotEmpty()) {
                    // 转义 literal 内的单引号，再用 '...' 包裹
                    val escaped = literal.replace("'", "''")
                    sb.append("'$escaped'")
                }
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
