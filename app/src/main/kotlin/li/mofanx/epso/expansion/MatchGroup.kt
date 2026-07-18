package li.mofanx.epso.expansion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 对应一个 YAML 文件的内容（espanso 兼容）
 *
 * espanso 文件结构：
 * ```yaml
 * global_vars:
 *   - name: myname
 *     type: echo
 *     params:
 *       echo: "John"
 *
 * imports:
 *   - "/path/to/other.yml"
 *
 * matches:
 *   - trigger: ":hello"
 *     replace: "Hello, {{myname}}!"
 * ```
 */
@Serializable
data class MatchGroup(
    val matches: List<Match> = emptyList(),
    @SerialName("global_vars")
    val globalVars: List<Var> = emptyList(),
    val imports: List<String> = emptyList(),
    /** 文件级默认触发前缀，null 表示继承全局设置 */
    val prefix: String? = null,
    val backend: String = "",
    @SerialName("preserve_clipboard")
    val preserveClipboard: Boolean? = null,
    @SerialName("restore_clipboard_delay")
    val restoreClipboardDelay: Int? = null,
    @SerialName("clipboard_threshold")
    val clipboardThreshold: Int? = null,
    @SerialName("word_separators")
    val wordSeparators: List<String> = emptyList(),
    @SerialName("undo_backspace")
    val undoBackspace: Boolean? = null,
    @SerialName("filter_title")
    val filterTitle: String? = null,
    @SerialName("filter_exec")
    val filterExec: String? = null,
    @SerialName("filter_class")
    val filterClass: String? = null,
    @SerialName("filter_os")
    val filterOs: String? = null,
    val enable: Boolean? = null,
) {
    /**
     * 运行时记录来源文件路径，不序列化到 YAML
     */
    @Transient
    var sourceFile: String = ""

    val isEmpty: Boolean get() = matches.isEmpty() && globalVars.isEmpty()
}
