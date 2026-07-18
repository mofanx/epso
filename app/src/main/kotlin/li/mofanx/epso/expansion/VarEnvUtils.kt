package li.mofanx.epso.expansion

/**
 * 把已求值变量转换成 espanso 风格的环境变量。
 *
 * 规则对照官方 espanso：
 * - 普通字符串变量：ESPANSO_NAME
 * - form 等 Map 变量：ESPANSO_NAME_FIELD
 */
fun envFromValues(values: Map<String, Any?>): Map<String, String> {
    val env = mutableMapOf<String, String>()
    for ((key, value) in values) {
        if (value == null) continue
        when (value) {
            is String -> {
                env["ESPANSO_${key.uppercase()}"] = value
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, String>
                for ((subKey, subValue) in map) {
                    env["ESPANSO_${key.uppercase()}_${subKey.uppercase()}"] = subValue
                }
            }
            else -> {
                env["ESPANSO_${key.uppercase()}"] = value.toString()
            }
        }
    }
    return env
}
