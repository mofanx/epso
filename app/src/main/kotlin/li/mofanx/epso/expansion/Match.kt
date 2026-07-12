package li.mofanx.epso.expansion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 文本扩展匹配规则（espanso 兼容）
 *
 * 字段说明：
 * - trigger / triggers：触发词，两者可同时使用
 * - replace：替换文本，支持 {{var_name}} 变量占位符和 $|$ 光标定位符
 * - regex：正则触发（与 trigger/triggers 互斥）
 * - word / left_word / right_word：单词边界控制
 * - propagate_case / uppercase_style：大小写传播
 * - vars：局部变量列表
 * - form / form_fields：表单模式，replace 改为模板，通过悬浮窗收集输入
 * - label：规则备注，仅用于显示
 */
@Serializable
data class Match(
    val trigger: String = "",
    val triggers: List<String> = emptyList(),
    val replace: String = "",
    val regex: String = "",
    /** 触发前缀：null 表示继承文件/全局设置，"" 表示无前缀，":" 等表示显式前缀 */
    val prefix: String? = null,
    val word: Boolean = false,
    @SerialName("left_word")
    val leftWord: Boolean = false,
    @SerialName("right_word")
    val rightWord: Boolean = false,
    @SerialName("propagate_case")
    val propagateCase: Boolean = false,
    @SerialName("uppercase_style")
    val uppercaseStyle: String = "uppercase",   // uppercase / capitalize / capitalize_words
    val vars: List<Var> = emptyList(),
    val form: String? = null,
    @SerialName("form_fields")
    val formFields: Map<String, FormField> = emptyMap(),
    val label: String? = null,
) {
    /** 运行时解析后的有效前缀（不参与 equals/序列化） */
    @Transient
    var effectivePrefix: String? = null

    /** 所有有效触发词（trigger + triggers 合并去重，并自动拼接 effectivePrefix/prefix） */
    val allTriggers: List<String>
        get() = (listOf(trigger) + triggers)
            .filter { it.isNotEmpty() }
            .distinct()
            .map { (effectivePrefix ?: prefix ?: "") + it }

    val isRegex: Boolean get() = regex.isNotEmpty()
    val isForm: Boolean get() = form != null

    val isValid: Boolean
        get() = (allTriggers.isNotEmpty() || isRegex) && (replace.isNotEmpty() || isForm)
}


