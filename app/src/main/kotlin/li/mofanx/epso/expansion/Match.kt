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
    @SerialName("force_clipboard")
    val forceClipboard: Boolean = false,
    @SerialName("force_mode")
    val forceMode: String = "",                // clipboard / keys / auto
    val vars: List<Var> = emptyList(),
    val form: String? = null,
    @SerialName("form_fields")
    val formFields: Map<String, FormField> = emptyMap(),
    val markdown: String? = null,
    val html: String? = null,
    @SerialName("image_path")
    val imagePath: String? = null,
    val paragraph: Boolean = false,
    @SerialName("search_terms")
    val searchTerms: List<String> = emptyList(),
    val comment: String? = null,
    val label: String? = null,
    @SerialName("filter_title")
    val filterTitle: String? = null,
    @SerialName("filter_exec")
    val filterExec: String? = null,
    @SerialName("filter_class")
    val filterClass: String? = null,
    @SerialName("filter_os")
    val filterOs: String? = null,
    val enable: Boolean? = null,
    @SerialName("preserve_clipboard")
    val preserveClipboard: Boolean? = null,
    @SerialName("restore_clipboard_delay")
    val restoreClipboardDelay: Int? = null,
    @SerialName("clipboard_threshold")
    val clipboardThreshold: Int? = null,
) {
    /** 运行时解析后的有效前缀（不参与 equals/序列化） */
    @Transient
    var effectivePrefix: String? = null

    /** 运行时解析后的有效过滤与开关（由 group 级继承） */
    @Transient
    var effectiveFilterTitle: String? = null
    @Transient
    var effectiveFilterExec: String? = null
    @Transient
    var effectiveFilterClass: String? = null
    @Transient
    var effectiveFilterOs: String? = null
    @Transient
    var effectiveEnable: Boolean = true

    /** 运行时解析后的有效 backend（match force_mode > group backend） */
    @Transient
    var effectiveBackend: String = ""

    /** 运行时解析后的剪贴板保留与恢复延迟（默认跟随 espanso：preserve=true, delay=300） */
    @Transient
    var effectivePreserveClipboard: Boolean = true
    @Transient
    var effectiveRestoreClipboardDelay: Int = 300

    /** 运行时解析后的剪贴板阈值（默认 100） */
    @Transient
    var effectiveClipboardThreshold: Int = 100

    /** 所有有效触发词（trigger + triggers 合并去重，并自动拼接 effectivePrefix/prefix） */
    val allTriggers: List<String>
        get() = (listOf(trigger) + triggers)
            .filter { it.isNotEmpty() }
            .distinct()
            .map { (effectivePrefix ?: prefix ?: "") + it }

    val isRegex: Boolean get() = regex.isNotEmpty()
    val isForm: Boolean get() = form != null

    /** 是否有可用的输出内容 */
    val hasOutput: Boolean
        get() = replace.isNotEmpty() || isForm || !markdown.isNullOrEmpty()
            || !html.isNullOrEmpty() || !imagePath.isNullOrEmpty()

    val isValid: Boolean
        get() = (allTriggers.isNotEmpty() || isRegex) && hasOutput

    /**
     * 判断当前规则是否对指定 App 上下文生效。
     * @param packageName 应用包名（对应 filter_exec）
     * @param className   当前节点/窗口类名（对应 filter_class）
     * @param title       当前窗口标题（对应 filter_title）
     * 规则：设置了哪个过滤器，哪个就必须匹配；全部设置了的过滤器都匹配才算生效。
     */
    fun isActiveFor(
        packageName: String? = null,
        className: String? = null,
        title: String? = null,
    ): Boolean {
        if (!effectiveEnable) return false

        val filterTitle = effectiveFilterTitle
        val filterExec = effectiveFilterExec
        val filterClass = effectiveFilterClass
        val filterOs = effectiveFilterOs

        if (filterTitle != null) {
            if (title == null || !title.matchesFilter(filterTitle)) return false
        }
        if (filterExec != null) {
            if (packageName == null || !packageName.matchesFilter(filterExec)) return false
        }
        if (filterClass != null) {
            if (className == null || !className.matchesFilter(filterClass)) return false
        }
        if (filterOs != null) {
            if (!"android".matchesFilter(filterOs)) return false
        }
        return true
    }

    private fun String.matchesFilter(pattern: String): Boolean {
        return try {
            Regex(pattern).containsMatchIn(this)
        } catch (e: Exception) {
            // 正则无效时退化为包含子串
            this.contains(pattern)
        }
    }
}


