package li.mofanx.epso.expansion.sync

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import li.mofanx.epso.app
import li.mofanx.epso.util.LogUtils
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "WebDavSync"

private val json = Json { ignoreUnknownKeys = true }

/**
 * WebDAV 同步实现
 *
 * 与 LocalFolderSync 共用同一套状态机 + 三向合并算法，实现：
 * - 幂等同步（基于持久化的 SyncState，不再每次全量上传/下载）
 * - 删除传播
 * - 真正的双向 sync 合并
 * - 全文件类型同步（不再只同步 .yml/.yaml）
 * - 递归子目录支持
 */
class WebDavSync(private val config: SyncConfig) : SyncManager {

    private val baseUrl: String get() = config.uri.trimEnd('/')
    private val matchesUrl: String get() = "$baseUrl/matches"

    private val authHeader: String by lazy {
        if (config.username.isEmpty()) ""
        else {
            val credentials = "${config.username}:${config.password}"
            "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        }
    }

    private companion object {
        val client: HttpClient by lazy { HttpClient(OkHttp) }
    }

    private fun stateKey(localDir: File): String =
        makeStableStateKey(config.method.name, config.uri, config.username, localDir.absolutePath)

    private fun stateFile(localDir: File): File =
        app.filesDir.resolve("sync_state").resolve("${stateKey(localDir)}.json")

    private class Counters {
        var pushed: Int = 0
        var pulled: Int = 0
        var deleted: Int = 0
        var conflicts: Int = 0
    }

    private data class WebDavResource(
        val path: String,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long,
    )

    // ── 接口实现 ───────────────────────────────────────────────

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.get(baseUrl) { if (authHeader.isNotEmpty()) header("Authorization", authHeader) }
            resp.status.isSuccess() || resp.status == HttpStatusCode.MultiStatus
        }.getOrElse { false }
    }

    override suspend fun push(localDir: File): SyncResult = runSync(localDir, Direction.PUSH)

    override suspend fun pull(localDir: File): SyncResult = runSync(localDir, Direction.PULL)

    override suspend fun sync(localDir: File): SyncResult = runSync(localDir, Direction.SYNC)

    // ── 状态机 ─────────────────────────────────────────────────

    private fun loadState(localDir: File): SyncState {
        val file = stateFile(localDir)
        if (!file.exists()) return SyncState()
        return try {
            json.decodeFromString(SyncState.serializer(), file.readText())
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to load sync state", e)
            SyncState()
        }
    }

    private fun saveState(localDir: File, state: SyncState) {
        try {
            val file = stateFile(localDir)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SyncState.serializer(), state))
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to save sync state", e)
        }
    }

    private suspend fun runSync(localDir: File, direction: Direction): SyncResult = SyncLock.withLock(stateKey(localDir)) {
        withContext(Dispatchers.IO) {
            runCatching {
                val state = loadState(localDir)
                val localMap = scanLocal(localDir)
                val remoteMap = scanRemote(state)

                val counters = Counters()
                val failures = mutableListOf<String>()

                val allPaths = (state.local.keys + state.remote.keys + localMap.keys + remoteMap.keys)
                    .distinct()
                    // 先处理深层路径，再处理目录
                    .sortedByDescending { it.count { c -> c == '/' } }

                val actions = allPaths.mapNotNull { path ->
                    val localEntry = localMap[path]
                    val remoteEntry = remoteMap[path]
                    val localChange = computeChange(localEntry, state.local[path])
                    val remoteChange = computeChange(remoteEntry, state.remote[path])

                    resolveAction(
                        path = path,
                        localEntry = localEntry,
                        remoteEntry = remoteEntry,
                        localChange = localChange,
                        remoteChange = remoteChange,
                        direction = direction,
                        strategy = config.conflictStrategy,
                    )
                }

                var hasFailure = false
                for (action in actions) {
                    if (action is SyncAction.Nothing) continue
                    try {
                        executeAction(action, localDir, remoteMap, counters)
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "Failed to execute action for ${action.path}", e)
                        failures.add("${action.path}: ${e.message}")
                        hasFailure = true
                    }
                }

                if (hasFailure) {
                    return@withContext SyncResult.Failure(
                        "Sync completed with failures:\n${failures.joinToString("\n") { "- $it" }}"
                    )
                }

                saveState(
                    localDir,
                    SyncState(
                        local = scanLocal(localDir),
                        remote = remoteMap,
                    )
                )

                SyncResult.Success(
                    pushed = counters.pushed,
                    pulled = counters.pulled,
                    deleted = counters.deleted,
                    conflicts = counters.conflicts,
                )
            }.getOrElse { SyncResult.Failure(it.message ?: it.toString()) }
        }
    }

    // ── 扫描 ───────────────────────────────────────────────────

    private fun scanLocal(localDir: File): Map<String, SyncFileEntry> {
        val entries = mutableMapOf<String, SyncFileEntry>()
        if (!localDir.exists()) return entries
        localDir.walkTopDown()
            .onEnter { !isHidden(it.name) }
            .filter { it != localDir && !isHidden(it.name) }
            .forEach { file ->
                val path = file.relativeTo(localDir).path.replace(File.separator, "/")
                if (file.isDirectory) {
                    entries[path] = SyncFileEntry(path, 0, 0, "", isDirectory = true)
                } else {
                    val sha256 = file.inputStream().use { hashInputStream(it) }
                    entries[path] = SyncFileEntry(path, file.lastModified(), file.length(), sha256)
                }
            }
        return entries
    }

    private suspend fun scanRemote(state: SyncState): MutableMap<String, SyncFileEntry> {
        ensureRemoteDir("")
        val entries = mutableMapOf<String, SyncFileEntry>()
        val dirsToScan = ArrayDeque<String>()
        dirsToScan.add("")

        while (dirsToScan.isNotEmpty()) {
            val dirPath = dirsToScan.removeFirst()
            val url = if (dirPath.isEmpty()) matchesUrl else "$matchesUrl/${encodePath(dirPath)}"
            val resources = propFind(url)

            for (resource in resources) {
                if (resource.path == dirPath || resource.path.isEmpty()) continue
                if (isHidden(resource.path.substringAfterLast('/'))) continue
                if (resource.path.contains("/.")) continue

                if (resource.isDirectory) {
                    entries[resource.path] = SyncFileEntry(
                        path = resource.path,
                        lastModified = 0L,
                        size = 0L,
                        sha256 = "",
                        isDirectory = true,
                    )
                    dirsToScan.add(resource.path)
                } else {
                    val cached = state.remote[resource.path]
                    val sha256 = if (
                        cached != null &&
                        cached.lastModified == resource.lastModified &&
                        cached.size == resource.size &&
                        cached.sha256.isNotEmpty()
                    ) {
                        cached.sha256
                    } else {
                        downloadAndHash("$matchesUrl/${encodePath(resource.path)}")
                    }
                    entries[resource.path] = SyncFileEntry(
                        path = resource.path,
                        lastModified = resource.lastModified,
                        size = resource.size,
                        sha256 = sha256,
                    )
                }
            }
        }
        return entries
    }

    // ── WebDAV 基础操作 ───────────────────────────────────────

    private suspend fun propFind(url: String, depth: String = "1"): List<WebDavResource> {
        val body = """<?xml version="1.0" encoding="UTF-8"?>
            |<D:propfind xmlns:D="DAV:">
            |  <D:prop>
            |    <D:resourcetype/>
            |    <D:getlastmodified/>
            |    <D:getcontentlength/>
            |  </D:prop>
            |</D:propfind>""".trimMargin()

        val resp = client.request(url) {
            method = HttpMethod("PROPFIND")
            if (authHeader.isNotEmpty()) header("Authorization", authHeader)
            header("Depth", depth)
            contentType(ContentType.Application.Xml)
            setBody(body)
        }
        if (resp.status == HttpStatusCode.NotFound) return emptyList()
        if (!resp.status.isSuccess()) {
            throw IOException("PROPFIND failed: ${resp.status}")
        }
        return parsePropFind(resp.bodyAsText())
    }

    private fun parsePropFind(xml: String): List<WebDavResource> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xml.byteInputStream())
        val result = mutableListOf<WebDavResource>()
        val responses = doc.getElementsByTagNameNS("*", "response")

        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = response.getElementsByTagNameNS("*", "href").item(0)?.textContent ?: continue
            val relPath = relativizeHref(href) ?: continue

            val propstat = response.getElementsByTagNameNS("*", "propstat").item(0) as? Element ?: continue
            val status = propstat.getElementsByTagNameNS("*", "status").item(0)?.textContent ?: ""
            if (!status.contains("200")) continue

            val prop = propstat.getElementsByTagNameNS("*", "prop").item(0) as? Element ?: continue
            val resourceType = prop.getElementsByTagNameNS("*", "resourcetype").item(0) as? Element
            val isDirectory = (resourceType?.getElementsByTagNameNS("*", "collection")?.length ?: 0) > 0

            val lastModifiedStr = prop.getElementsByTagNameNS("*", "getlastmodified").item(0)?.textContent
            val lastModified = lastModifiedStr?.let { str ->
                runCatching { str.fromHttpToGmtDate().timestamp }.getOrDefault(0L)
            } ?: 0L

            val sizeStr = prop.getElementsByTagNameNS("*", "getcontentlength").item(0)?.textContent
            val size = sizeStr?.toLongOrNull() ?: 0L

            result.add(WebDavResource(relPath, isDirectory, lastModified, size))
        }
        return result
    }

    private fun relativizeHref(href: String): String? {
        return runCatching {
            val baseUri = java.net.URI(matchesUrl + "/")
            val resolved = baseUri.resolve(href)
            val basePath = baseUri.path?.trimEnd('/')?.plus("/") ?: return@runCatching null
            val hrefPath = resolved.path ?: return@runCatching null
            if (!hrefPath.startsWith(basePath)) return@runCatching null
            val relative = hrefPath.removePrefix(basePath).removePrefix("/")
            if (relative.isEmpty()) return@runCatching null
            URLDecoder.decode(relative, "UTF-8")
        }.getOrNull()
    }

    private suspend fun downloadAndHash(url: String): String {
        val resp = client.get(url) {
            if (authHeader.isNotEmpty()) header("Authorization", authHeader)
        }
        if (!resp.status.isSuccess()) {
            throw IOException("GET failed for $url: ${resp.status}")
        }
        val bytes = resp.bodyAsBytes()
        return hashInputStream(bytes.inputStream())
    }

    private suspend fun ensureRemoteDir(relativePath: String) {
        // 先确保 matches 根目录存在
        if (relativePath.isEmpty() || relativePath == "/") {
            try {
                val resp = client.request(matchesUrl) {
                    method = HttpMethod("MKCOL")
                    if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                }
                LogUtils.d(TAG, "MKCOL $matchesUrl: ${resp.status}")
            } catch (_: Exception) {
                // 目录可能已存在，或服务器不支持 MKCOL；后续 PUT/DELETE 会暴露真实问题
            }
        }
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        var currentPath = ""
        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val url = "$matchesUrl/${encodePath(currentPath)}"
            try {
                val resp = client.request(url) {
                    method = HttpMethod("MKCOL")
                    if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                }
                if (resp.status == HttpStatusCode.Conflict || resp.status == HttpStatusCode.MethodNotAllowed) {
                    // 可能是同名文件阻塞了目录创建：尝试删除后重新 MKCOL
                    deleteIfRemoteFile(url)
                    client.request(url) {
                        method = HttpMethod("MKCOL")
                        if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                    }
                }
                LogUtils.d(TAG, "MKCOL $url: ${resp.status}")
            } catch (_: Exception) {
                // 目录可能已存在，或服务器不支持 MKCOL；后续 PUT/DELETE 会暴露真实问题
            }
        }
    }

    private suspend fun deleteIfRemoteFile(url: String) {
        val resources = runCatching { propFind(url, "0") }.getOrDefault(emptyList())
        if (resources.isNotEmpty() && !resources[0].isDirectory) {
            client.request(url) {
                method = HttpMethod("DELETE")
                if (authHeader.isNotEmpty()) header("Authorization", authHeader)
            }
        }
    }

    private suspend fun ensureParentDirs(path: String) {
        val parent = File(path).parent?.replace(File.separator, "/")
        if (parent != null) ensureRemoteDir(parent)
    }

    private fun encodePath(path: String): String = path
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    private fun responseMtime(resp: HttpResponse): Long {
        return resp.headers["Last-Modified"]?.let { str ->
            runCatching { str.fromHttpToGmtDate().timestamp }.getOrDefault(0L)
        } ?: System.currentTimeMillis()
    }

    // ── 动作执行 ───────────────────────────────────────────────

    private suspend fun executeAction(
        action: SyncAction,
        localDir: File,
        remoteMap: MutableMap<String, SyncFileEntry>,
        counters: Counters,
    ) {
        when (action) {
            is SyncAction.CopyToRemote -> copyLocalToRemote(action.path, localDir, remoteMap, counters)
            is SyncAction.CopyToLocal -> copyRemoteToLocal(action.path, localDir, remoteMap, counters)
            is SyncAction.DeleteRemote -> deleteRemote(action.path, remoteMap, counters)
            is SyncAction.DeleteLocal -> deleteLocal(action.path, localDir, counters)
            is SyncAction.ResolveConflict -> resolveConflictAction(action, localDir, remoteMap, counters)
            is SyncAction.Nothing -> Unit
        }
    }

    private suspend fun copyLocalToRemote(
        path: String,
        localDir: File,
        remoteMap: MutableMap<String, SyncFileEntry>,
        counters: Counters,
    ) {
        val local = localDir.resolve(path)
        if (!local.exists()) throw IllegalStateException("Local file does not exist: $path")

        if (local.isDirectory) {
            // 远端同名文件需要先删除，否则 MKCOL 会冲突
            if (remoteMap[path]?.isDirectory == false) {
                deleteRemote(path, remoteMap, counters)
            }
            ensureRemoteDir(path)
            remoteMap[path] = SyncFileEntry(path, 0, 0, "", isDirectory = true)
        } else {
            // 远端同名目录需要先删除，否则 PUT 会失败或变成在目录内创建文件
            if (remoteMap[path]?.isDirectory == true) {
                deleteRemote(path, remoteMap, counters)
            }
            ensureParentDirs(path)
            val url = "$matchesUrl/${encodePath(path)}"
            val bytes = local.readBytes()
            val resp = client.put(url) {
                if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
            if (!resp.status.isSuccess()) {
                throw IOException("PUT failed for $path: ${resp.status}")
            }
            remoteMap[path] = SyncFileEntry(
                path = path,
                lastModified = responseMtime(resp),
                size = bytes.size.toLong(),
                sha256 = hashInputStream(bytes.inputStream()),
            )
        }
        counters.pushed++
    }

    private suspend fun copyRemoteToLocal(
        path: String,
        localDir: File,
        remoteMap: MutableMap<String, SyncFileEntry>,
        counters: Counters,
    ) {
        val local = localDir.resolve(path)
        val url = "$matchesUrl/${encodePath(path)}"
        val resp = client.get(url) {
            if (authHeader.isNotEmpty()) header("Authorization", authHeader)
        }
        if (!resp.status.isSuccess()) {
            throw IOException("GET failed for $path: ${resp.status}")
        }
        val bytes = resp.bodyAsBytes()
        val remoteMtime = resp.headers["Last-Modified"]?.let { str ->
            runCatching { str.fromHttpToGmtDate().timestamp }.getOrDefault(0L)
        } ?: 0L

        if (local.exists() && local.isDirectory) {
            if (!local.deleteRecursively()) {
                throw IllegalStateException("Failed to delete local directory before copying file: $path")
            }
        }
        local.parentFile?.mkdirs()
        local.writeBytes(bytes)
        if (remoteMtime > 0) local.setLastModified(remoteMtime)

        val sha256 = hashInputStream(bytes.inputStream())
        remoteMap[path] = SyncFileEntry(
            path = path,
            lastModified = remoteMtime,
            size = bytes.size.toLong(),
            sha256 = sha256,
        )
        counters.pulled++
    }

    private suspend fun deleteRemote(
        path: String,
        remoteMap: MutableMap<String, SyncFileEntry>,
        counters: Counters,
    ) {
        val url = "$matchesUrl/${encodePath(path)}"
        val resp = client.request(url) {
            method = HttpMethod("DELETE")
            if (authHeader.isNotEmpty()) header("Authorization", authHeader)
        }
        if (!resp.status.isSuccess() && resp.status != HttpStatusCode.NotFound) {
            throw IOException("DELETE failed for $path: ${resp.status}")
        }
        remoteMap.remove(path)
        counters.deleted++
    }

    private fun deleteLocal(path: String, localDir: File, counters: Counters) {
        val local = localDir.resolve(path)
        if (!local.exists()) return
        val success = if (local.isDirectory) local.deleteRecursively() else local.delete()
        if (success) {
            counters.deleted++
        } else {
            throw IllegalStateException("Failed to delete local file: $path")
        }
    }

    private suspend fun resolveConflictAction(
        action: SyncAction.ResolveConflict,
        localDir: File,
        remoteMap: MutableMap<String, SyncFileEntry>,
        counters: Counters,
    ) {
        if (action.winner == action.target) {
            counters.conflicts++
            return
        }

        when (action.strategy) {
            ConflictStrategy.LastWriteWins -> {
                if (action.winner == Side.LOCAL) {
                    copyLocalToRemote(action.path, localDir, remoteMap, counters)
                } else {
                    copyRemoteToLocal(action.path, localDir, remoteMap, counters)
                }
            }
            ConflictStrategy.KeepBoth -> {
                if (action.target == Side.LOCAL) {
                    val local = localDir.resolve(action.path)
                    if (local.exists() && local.isFile) {
                        local.copyTo(localConflictFile(local), overwrite = true)
                    }
                } else {
                    val url = "$matchesUrl/${encodePath(action.path)}"
                    val backupResp = client.get(url) {
                        if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                    }
                    if (backupResp.status.isSuccess()) {
                        val bytes = backupResp.bodyAsBytes()
                        val conflictPath = insertConflictSuffix(action.path)
                        ensureParentDirs(conflictPath)
                        val conflictUrl = "$matchesUrl/${encodePath(conflictPath)}"
                        val putResp = client.put(conflictUrl) {
                            if (authHeader.isNotEmpty()) header("Authorization", authHeader)
                            contentType(ContentType.Application.OctetStream)
                            setBody(bytes)
                        }
                        if (putResp.status.isSuccess()) {
                            remoteMap[conflictPath] = SyncFileEntry(
                                path = conflictPath,
                                lastModified = responseMtime(putResp),
                                size = bytes.size.toLong(),
                                sha256 = hashInputStream(bytes.inputStream()),
                            )
                        }
                    }
                }

                if (action.winner == Side.LOCAL) {
                    copyLocalToRemote(action.path, localDir, remoteMap, counters)
                } else {
                    copyRemoteToLocal(action.path, localDir, remoteMap, counters)
                }
            }
        }
        counters.conflicts++
    }
}
