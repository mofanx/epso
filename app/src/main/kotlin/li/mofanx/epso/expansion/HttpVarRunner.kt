package li.mofanx.epso.expansion

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.mofanx.epso.util.LogUtils

private const val TAG = "HttpVarRunner"

/**
 * 执行 http 类型变量。
 *
 * 支持：
 * - 任意 HTTP 方法（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS）
 * - 自定义请求头 headers
 * - 请求体 body
 * - JSON 路径提取 json_path（点分路径，如 "data.0.name" 或 "result.value"）
 * - trim / ignore_error
 *
 * 如果 url/body 中包含 `{{varName}}` 占位符，应在传入前由 [VarEvaluator] 完成替换。
 */
object HttpVarRunner {

    private val client by lazy { HttpClient(OkHttp) }
    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    suspend fun run(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        jsonPath: String? = null,
        trim: Boolean = true,
        ignoreError: Boolean = false,
        debug: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            LogUtils.e(TAG, "Empty HTTP URL")
            return@withContext null
        }

        val resolvedMethod = try {
            HttpMethod.parse(method.uppercase())
        } catch (e: Exception) {
            LogUtils.e(TAG, "Invalid HTTP method '$method', fallback to GET", e)
            HttpMethod.Get
        }

        if (debug) {
            LogUtils.d(TAG, "HTTP $resolvedMethod $url, headers=$headers, body=${body?.take(200)}")
        }

        try {
            val response = client.request(url) {
                this.method = resolvedMethod
                headers.forEach { (k, v) ->
                    if (k.equals("Content-Type", ignoreCase = true) && body != null) {
                        header(k, v)
                    } else if (k.isNotBlank()) {
                        header(k, v)
                    }
                }
                if (body != null && resolvedMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                    setBody(body)
                }
            }

            val responseText = response.bodyAsText()
            if (debug) {
                LogUtils.d(TAG, "HTTP response status=${response.status} body=${responseText.take(200)}")
            }

            if (!response.status.isSuccess() && !ignoreError) {
                LogUtils.e(TAG, "HTTP request failed: ${response.status}, body=${responseText.take(200)}")
                return@withContext null
            }

            val extracted = if (!jsonPath.isNullOrBlank()) {
                extractJsonPath(responseText, jsonPath)
            } else {
                responseText
            }

            if (trim) extracted?.trim() else extracted
        } catch (e: Exception) {
            LogUtils.e(TAG, "HTTP request failed for $url", e)
            if (ignoreError) "" else null
        }
    }

    /**
     * 从 JSON 字符串中按点分路径提取值。
     *
     * 路径规则：
     * - 用 `.` 分隔层级
     * - 数组索引用数字，如 `items.0.name`
     * - 如果路径不存在返回 null
     */
    private fun extractJsonPath(jsonText: String, path: String): String? {
        return try {
            val element = json.parseToJsonElement(jsonText)
            val parts = path.split('.').filter { it.isNotBlank() }
            val result = parts.fold<String, JsonElement?>(element) { current, part ->
                when (current) {
                    is JsonObject -> current[part]
                    is JsonArray -> part.toIntOrNull()?.let { index ->
                        if (index in current.indices) current[index] else null
                    }
                    else -> null
                }
            }
            result?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to extract json_path '$path'", e)
            null
        }
    }
}
