package li.mofanx.epso.expansion.sync

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.util.LogUtils
import java.io.File

private const val TAG = "WebDavSync"

/**
 * WebDAV 同步实现
 *
 * 使用 Ktor OkHttp client + Basic Auth header（手动注入，无需 ktor-client-auth 插件）。
 * 兼容 Nextcloud / 坚果云 / Nginx WebDAV 等标准实现。
 */
class WebDavSync(private val config: SyncConfig) : SyncManager {

    private val baseUrl: String get() = config.uri.trimEnd('/')
    private val matchesUrl: String get() = "$baseUrl/matches"

    /** Basic Auth header 值 */
    private val authHeader: String by lazy {
        if (config.username.isEmpty()) ""
        else {
            val credentials = "${config.username}:${config.password}"
            "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        }
    }

    private val client: HttpClient by lazy { HttpClient(OkHttp) }

    // ── 接口实现 ───────────────────────────────────────────────

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.get(baseUrl) { if (authHeader.isNotEmpty()) header("Authorization", authHeader) }
            resp.status.isSuccess() || resp.status == HttpStatusCode.MultiStatus
        }.getOrElse { false }
    }

    override suspend fun push(localDir: File): SyncResult = withContext(Dispatchers.IO) {
        runCatching {
            ensureRemoteDir()
            var pushed = 0
            // 递归遍历整个工作区（含 packages/ 子目录）
            for (file in localDir.walkTopDown().filter { it.isFile }) {
                val relative = file.relativeTo(localDir).path
                val url = "$matchesUrl/$relative"
                val resp = client.put(url) {
                    if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                    contentType(ContentType.Text.Plain)
                    setBody(file.readBytes())
                }
                if (resp.status.isSuccess() || resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.NoContent) {
                    pushed++
                } else {
                    LogUtils.e(TAG, "Push failed for $relative: ${resp.status}")
                }
            }
            SyncResult.Success(pushed = pushed)
        }.getOrElse { SyncResult.Failure(it.message ?: "Push failed") }
    }

    override suspend fun pull(localDir: File): SyncResult = withContext(Dispatchers.IO) {
        runCatching {
            val remoteFiles = listRemoteFiles()
            var pulled = 0
            var conflicts = 0
            for (path in remoteFiles) {
                val resp = client.get("$matchesUrl/$path") {
                    if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                }
                if (!resp.status.isSuccess()) continue

                val remoteBytes = resp.bodyAsBytes()
                val localFile = localDir.resolve(path)
                localFile.parentFile?.mkdirs()
                val remoteModified = resp.headers["Last-Modified"]?.let { parseHttpDate(it) } ?: 0L

                if (!localFile.exists()) {
                    localFile.writeBytes(remoteBytes)
                    pulled++
                } else {
                    when (config.conflictStrategy) {
                        ConflictStrategy.LastWriteWins -> {
                            if (remoteModified > localFile.lastModified()) {
                                localFile.writeBytes(remoteBytes); pulled++
                            }
                        }
                        ConflictStrategy.KeepBoth -> {
                            if (remoteModified > localFile.lastModified()) {
                                val ts = System.currentTimeMillis()
                                localFile.renameTo(localDir.resolve("${localFile.nameWithoutExtension}.conflict.$ts.yml"))
                                localFile.writeBytes(remoteBytes); pulled++; conflicts++
                            }
                        }
                    }
                }
            }
            SyncResult.Success(pulled = pulled, conflicts = conflicts)
        }.getOrElse { SyncResult.Failure(it.message ?: "Pull failed") }
    }

    override suspend fun sync(localDir: File): SyncResult = withContext(Dispatchers.IO) {
        val pullResult = pull(localDir)
        if (pullResult is SyncResult.Failure) return@withContext pullResult
        val pushResult = push(localDir)
        if (pushResult is SyncResult.Failure) return@withContext pushResult
        val p = pullResult as SyncResult.Success
        val q = pushResult as SyncResult.Success
        SyncResult.Success(pushed = q.pushed, pulled = p.pulled, conflicts = p.conflicts)
    }

    // ── 私有辅助 ───────────────────────────────────────────────

    private suspend fun ensureRemoteDir() {
        try {
            val resp = client.request(matchesUrl) {
                method = HttpMethod("MKCOL")
                if (authHeader.isNotEmpty()) header("Authorization", authHeader)
            }
            LogUtils.d(TAG, "MKCOL $matchesUrl: ${resp.status}")
        } catch (_: Exception) { /* 已存在时服务器返回 405，忽略 */ }
    }

    private suspend fun listRemoteFiles(): List<String> = try {
        val body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:getlastmodified/></D:prop></D:propfind>"""
        val resp = client.request(matchesUrl) {
            method = HttpMethod("PROPFIND")
            if (authHeader.isNotEmpty()) header("Authorization", authHeader)
            header("Depth", "1")
            contentType(ContentType.Application.Xml)
            setBody(body)
        }
        val text = resp.bodyAsText()
        // 保留完整相对路径（含子目录），去掉 URL 前缀和查询参数
        val basePath = matchesUrl.substringAfterLast('/')
        Regex("""href>([^<]*\.ya?ml)<""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1] }
            .map { href ->
                // 解码 URL 编码，去掉前缀路径，保留 matches/ 之后的相对路径
                java.net.URLDecoder.decode(href, "UTF-8")
                    .substringAfter("$basePath/")
                    .takeIf { it.isNotEmpty() } ?: href.substringAfterLast('/')
            }
            .filter { it.endsWith(".yml") || it.endsWith(".yaml") }
            .filter { it.isNotEmpty() }
            .toList()
    } catch (e: Exception) {
        LogUtils.e(TAG, "listRemoteFiles failed", e)
        emptyList()
    }

    private fun parseHttpDate(value: String): Long = try {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .parse(value)?.time ?: 0L
    } catch (_: Exception) { 0L }
}
