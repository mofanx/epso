package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.util.LogUtils
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

private const val TAG = "JavaScriptVarRunner"

/**
 * 执行 javascript 类型变量。
 *
 * 使用 Rhino 引擎在沙箱中运行 JS 代码，返回最后一行表达式的字符串结果。
 * 支持 inject_vars 将前置变量作为全局变量注入，支持 trim / ignore_error / debug。
 */
object JavaScriptVarRunner {

    suspend fun run(
        code: String,
        values: Map<String, Any?> = emptyMap(),
        injectVars: Boolean = true,
        trim: Boolean = true,
        debug: Boolean = false,
        ignoreError: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (code.isBlank()) {
            LogUtils.e(TAG, "Empty javascript code")
            return@withContext null
        }

        if (debug) LogUtils.d(TAG, "javascript code: $code")

        try {
            val cx = Context.enter()
            try {
                // 关闭优化以提升 Android 兼容性，避免某些类加载问题
                cx.optimizationLevel = -1

                val scope: ScriptableObject = cx.initStandardObjects()

                if (injectVars) {
                    values.forEach { (name, value) ->
                        // 将 Kotlin 值转换为 Rhino 原生 JS 对象
                        val jsValue = Context.javaToJS(value, scope)
                        ScriptableObject.putProperty(scope, name, jsValue)
                    }
                }

                val result = cx.evaluateString(scope, code, "javascript", 1, null)
                val output = Context.toString(result)

                if (trim) output.trim() else output
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "javascript execution failed", e)
            if (ignoreError) "" else null
        }
    }
}
