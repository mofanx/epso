package li.mofanx.epso.expansion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 变量定义（espanso 兼容）
 * 支持类型：echo / date / clipboard / random / choice / shell / script / http / match / form
 */
@Serializable
data class Var(
    val name: String = "",
    val type: String = "",
    val params: VarParams = VarParams(),
    @SerialName("inject_vars")
    val injectVars: Boolean = true,
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
)

/**
 * 变量参数（espanso 兼容）
 * 不同 type 使用不同字段子集
 */
@Serializable
data class VarParams(
    // echo
    val echo: String? = null,

    // date
    val format: String? = null,         // strftime 格式，如 "%Y-%m-%d"
    val offset: Long = 0L,              // 偏移秒数
    val tz: String? = null,             // 时区，如 "Asia/Shanghai"

    // random
    val min: Int? = null,
    val max: Int? = null,

    // random / choice
    val choices: List<String> = emptyList(),
    val values: List<String> = emptyList(),  // choice 的别名

    // shell / script
    val shell: String? = null,          // shell 类型：sh / bash / zsh 等
    val cmd: String? = null,
    val args: List<String> = emptyList(),
    val trim: Boolean = true,
    val debug: Boolean = false,
    @SerialName("ignore_error")
    val ignoreError: Boolean = false,

    // http
    val url: String? = null,
    val method: String = "GET",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("json_path")
    val jsonPath: String? = null,

    // javascript
    val code: String? = null,

    // match（递归触发另一条规则）
    val trigger: String? = null,

    // form 表单变量
    val layout: String? = null,
    val fields: Map<String, FormField> = emptyMap(),
)

/**
 * 表单字段配置（espanso form_fields 兼容）
 */
@Serializable
data class FormField(
    val type: String = "input",             // input / multiline / list
    val default: String? = null,
    val values: List<String> = emptyList(), // list 类型的选项
    val multiline: Boolean = false,         // 多行输入简写
)
