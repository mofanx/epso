package li.mofanx.epso.expansion

import kotlinx.serialization.Serializable

/**
 * 文本扩展匹配规则
 */
@Serializable
data class Match(
    val trigger: String = "",
    val replace: String = "",
    val regex: String = "",
    val word: Boolean = true,
    val propagate_case: Boolean = false,
    val vars: List<Var> = emptyList(),
    val form: String? = null,
    val label: String? = null
) {
    val isRegex: Boolean
        get() = regex.isNotEmpty()
    
    val isValid: Boolean
        get() = trigger.isNotEmpty() || regex.isNotEmpty()
}

/**
 * 变量定义
 */
@Serializable
data class Var(
    val name: String,
    val type: String,
    val params: Map<String, String> = emptyMap()
)

/**
 * 扩展结果
 */
data class ExpansionResult(
    val originalText: String,
    val expandedText: String,
    val trigger: String,
    val cursorPosition: Int = 0
)