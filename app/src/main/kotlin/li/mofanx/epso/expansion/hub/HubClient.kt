package li.mofanx.epso.expansion.hub

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import li.mofanx.epso.app
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.util.LogUtils
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val TAG = "HubClient"

/** espanso hub package_index.json 主地址（GitHub Releases latest） */
private const val INDEX_URL =
    "https://github.com/espanso/hub/releases/latest/download/package_index.json"

/** 包商店索引备用地址列表，按顺序尝试 */
private val FALLBACK_INDEX_URLS = listOf(
    INDEX_URL,
    "https://ghproxy.com/https://github.com/espanso/hub/releases/latest/download/package_index.json",
    "https://mirror.ghproxy.com/https://github.com/espanso/hub/releases/latest/download/package_index.json",
    "https://raw.githubusercontent.com/espanso/hub/main/package_index.json",
    "https://ghproxy.com/https://raw.githubusercontent.com/espanso/hub/main/package_index.json",
)

/** 国内代理前缀，下载 zip 时按顺序尝试 */
private val PROXY_PREFIXES = listOf(
    "https://ghproxy.com/",
    "https://mirror.ghproxy.com/",
)

// ──────────────────────────────────────────────────────────────
// 客户端
// ──────────────────────────────────────────────────────────────

/**
 * espanso Hub 客户端
 *
 * 职责：
 * 1. 拉取并缓存 [PackageIndex]，支持自定义源和多个镜像回退
 * 2. 下载包 zip，支持原地址 + 代理回退，校验 SHA256
 * 3. 解压 package.yml 到工作区 packages/{name}/package.yml
 * 4. 支持从本地 zip 文件直接安装
 * 5. 卸载：删除 packages/{name}/ 目录
 */
object HubClient {

    private val client = HttpClient(OkHttp)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ── 公共 API ───────────────────────────────────────────────

    /**
     * 获取包索引（内存 + 可选磁盘缓存）
     *
     * @param cacheFile 如果非 null，先读缓存，失败再联网
     * @param packageIndexUrl 用户自定义索引地址（空 = 使用内置备用列表）
     * @param useProxy 是否在自定义地址和内置地址上追加代理回退
     */
    suspend fun fetchIndex(
        cacheFile: File? = null,
        packageIndexUrl: String = "",
        useProxy: Boolean = true,
    ): HubResult<PackageIndex> = withContext(Dispatchers.IO) {
        try {
            // 尝试读缓存（最多 24 小时有效）
            if (cacheFile != null && cacheFile.exists()) {
                val age = System.currentTimeMillis() - cacheFile.lastModified()
                if (age < 24 * 3600 * 1000L) {
                    try {
                        val text = cacheFile.readText()
                        return@withContext HubResult.Success(json.decodeFromString(text))
                    } catch (_: Exception) { /* 缓存损坏，继续联网 */ }
                }
            }

            // 构建候选地址列表
            val candidates = buildIndexCandidates(packageIndexUrl, useProxy)
            var lastError = ""

            for (url in candidates) {
                try {
                    val resp = client.get(url)
                    if (!resp.status.isSuccess()) {
                        lastError = "HTTP ${resp.status}"
                        continue
                    }
                    val text = resp.bodyAsText()
                    cacheFile?.writeText(text)
                    return@withContext HubResult.Success(json.decodeFromString(text))
                } catch (e: Exception) {
                    lastError = e.message ?: "Network error"
                }
            }

            HubResult.Failure(lastError.ifEmpty { "All index sources failed" })
        } catch (e: Exception) {
            HubResult.Failure(e.message ?: "Network error")
        }
    }

    /**
     * 安装包到 workspaceDir/packages/{name}/package.yml
     *
     * 流程：下载 zip（支持代理回退） -> 校验 SHA256 -> 解压 package.yml -> 完成
     */
    suspend fun install(
        pkg: HubPackage,
        workspaceDir: File,
        useProxy: Boolean = true,
    ): HubResult<Unit> = withContext(Dispatchers.IO) {
        try {
            LogUtils.d(TAG, "Installing ${pkg.name} v${pkg.version}")

            val zipBytes = downloadBytesWithFallback(pkg.archiveUrl, useProxy)
                ?: return@withContext HubResult.Failure("Download failed: ${pkg.archiveUrl}")

            verifySha256(pkg, zipBytes, useProxy)

            val pkgDir = workspaceDir.resolve("packages/${pkg.name}").also { it.mkdirs() }
            val extracted = extractPackageFiles(zipBytes, pkgDir)

            if (extracted == 0) return@withContext HubResult.Failure("No package.yml found in archive")
            LogUtils.d(TAG, "Installed ${pkg.name} ($extracted files)")
            HubResult.Success(Unit)
        } catch (e: Exception) {
            HubResult.Failure(e.message ?: "Install failed")
        }
    }

    /**
     * 从本地 zip 文件（SAF 返回的 Uri）安装包。
     * 优先从 _manifest.yml 读取 name，否则使用文件名。
     */
    suspend fun installFromZip(
        zipUri: Uri,
        workspaceDir: File,
    ): HubResult<String> = withContext(Dispatchers.IO) {
        try {
            val resolver = app.contentResolver
            val input = resolver.openInputStream(zipUri)
                ?: return@withContext HubResult.Failure("Cannot open selected file")

            val zipBytes = input.use { it.readBytes() }

            val pkgName = readPackageNameFromZip(zipBytes)
                ?: zipUri.lastPathSegment?.substringBeforeLast('.')
                ?: "imported-package"

            val pkgDir = workspaceDir.resolve("packages/$pkgName").also { it.mkdirs() }
            val extracted = extractPackageFiles(zipBytes, pkgDir)

            if (extracted == 0) return@withContext HubResult.Failure("No package.yml found in archive")
            MatchStore.addPackageImport(pkgName)
            LogUtils.d(TAG, "Installed $pkgName from local zip")
            HubResult.Success(pkgName)
        } catch (e: Exception) {
            HubResult.Failure(e.message ?: "Install from zip failed")
        }
    }

    /**
     * 卸载包（删除 workspaceDir/packages/{name}/ 目录）
     */
    suspend fun uninstall(packageName: String, workspaceDir: File): HubResult<Unit> =
        withContext(Dispatchers.IO) {
            val pkgDir = workspaceDir.resolve("packages/$packageName")
            return@withContext if (!pkgDir.exists()) {
                HubResult.Failure("Package not found: $packageName")
            } else {
                pkgDir.deleteRecursively()
                HubResult.Success(Unit)
            }
        }

    /**
     * 列出已安装的包（读取 packages 子目录下 _manifest.yml 的 name 字段）
     */
    suspend fun installedPackages(workspaceDir: File): List<InstalledPackage> =
        withContext(Dispatchers.IO) {
            val packagesDir = workspaceDir.resolve("packages")
            if (!packagesDir.exists()) return@withContext emptyList()

            packagesDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    val manifest = dir.resolve("_manifest.yml")
                    val name = if (manifest.exists()) {
                        manifest.readLines()
                            .firstOrNull { it.startsWith("name:") }
                            ?.substringAfter("name:")?.trim()?.trim('"') ?: dir.name
                    } else dir.name
                    InstalledPackage(name = name, dir = dir)
                } ?: emptyList()
        }

    /** 释放底层连接池（在 App 退出时调用） */
    fun close() = client.close()

    // ── 私有 ───────────────────────────────────────────────────

    private fun buildIndexCandidates(packageIndexUrl: String, useProxy: Boolean): List<String> {
        val candidates = LinkedHashSet<String>()
        if (packageIndexUrl.isNotBlank()) {
            candidates.add(packageIndexUrl)
            if (useProxy) {
                PROXY_PREFIXES.forEach { candidates.add("$it$packageIndexUrl") }
            }
        }
        FALLBACK_INDEX_URLS.forEach { candidates.add(it) }
        if (useProxy) {
            // 为自定义地址再次尝试镜像（避免主地址失败时无代理可用）
            if (packageIndexUrl.isNotBlank()) {
                PROXY_PREFIXES.forEach { candidates.add("$it$packageIndexUrl") }
            }
        }
        return candidates.toList()
    }

    private suspend fun downloadBytesWithFallback(url: String, useProxy: Boolean): ByteArray? {
        val candidates = mutableListOf(url)
        if (useProxy) {
            PROXY_PREFIXES.forEach { candidates.add("$it$url") }
        }

        var lastError = ""
        for (candidate in candidates) {
            try {
                val resp = client.get(candidate)
                if (resp.status.isSuccess()) return resp.bodyAsBytes()
                lastError = "HTTP ${resp.status}"
            } catch (e: Exception) {
                lastError = e.message ?: "Network error"
            }
        }
        LogUtils.e(TAG, "Download failed for $url: $lastError")
        return null
    }

    private fun readPackageNameFromZip(zipBytes: ByteArray): String? {
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "_manifest.yml") {
                    val text = zis.bufferedReader().readText()
                    val nameLine = text.lineSequence()
                        .firstOrNull { it.startsWith("name:") }
                        ?.substringAfter("name:")
                        ?.trim()
                        ?.trim('"')
                    if (!nameLine.isNullOrBlank()) return nameLine
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    private suspend fun verifySha256(
        pkg: HubPackage,
        zipBytes: ByteArray,
        useProxy: Boolean = true,
    ) {
        try {
            val shaBytes = downloadBytesWithFallback(pkg.archiveSha256Url, useProxy)
                ?: return
            val expected = shaBytes.toString(Charsets.UTF_8).trim().split("\\s+".toRegex()).first().lowercase()
            val actual = sha256Hex(zipBytes)
            if (actual != expected) {
                LogUtils.e(TAG, "SHA256 mismatch: expected=$expected actual=$actual")
                throw IllegalStateException("SHA256 mismatch, aborting install for safety")
            }
            LogUtils.d(TAG, "SHA256 OK: $actual")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            LogUtils.d(TAG, "Could not verify SHA256 for ${pkg.name}: ${e.message}")
        }
    }

    private fun extractPackageFiles(zipBytes: ByteArray, pkgDir: File): Int {
        var extracted = 0
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val fileName = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && (fileName == "package.yml" || fileName == "_manifest.yml")) {
                    pkgDir.resolve(fileName).writeBytes(zis.readBytes())
                    extracted++
                    LogUtils.d(TAG, "Extracted: ${entry.name}")
                }
                entry = zis.nextEntry
            }
        }
        return extracted
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
