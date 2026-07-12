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
) {
    /**
     * 运行时记录来源文件路径，不序列化到 YAML
     */
    @Transient
    var sourceFile: String = ""

    val isEmpty: Boolean get() = matches.isEmpty() && globalVars.isEmpty()
}
