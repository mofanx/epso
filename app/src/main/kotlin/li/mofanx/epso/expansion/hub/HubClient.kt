package li.mofanx.epso.expansion.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import li.mofanx.epso.util.LogUtils
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val TAG = "HubClient"

/** espanso hub package_index.json URL（GitHub Releases latest） */
private const val INDEX_URL =
    "https://github.com/espanso/hub/releases/latest/download/package_index.json"

// ──────────────────────────────────────────────────────────────
// 客户端
// ──────────────────────────────────────────────────────────────

/**
 * espanso Hub 客户端
 *
 * 职责：
 * 1. 拉取并缓存 [PackageIndex]
 * 2. 下载包 zip，校验 SHA256
 * 3. 解压 package.yml 到工作区 packages/{name}/package.yml
 * 4. 卸载：删除 packages/{name}/ 目录
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
     * @param cacheFile 如果非 null，先读缓存，失败再联网
     */
    suspend fun fetchIndex(cacheFile: File? = null): HubResult<PackageIndex> =
        withContext(Dispatchers.IO) {
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
                // 联网拉取
                val resp = client.get(INDEX_URL)
                if (!resp.status.isSuccess()) return@withContext HubResult.Failure("HTTP ${resp.status}")
                val text = resp.bodyAsText()
                cacheFile?.writeText(text)
                HubResult.Success(json.decodeFromString(text))
            } catch (e: Exception) {
                HubResult.Failure(e.message ?: "Network error")
            }
        }

    /**
     * 安装包到 workspaceDir/packages/{name}/package.yml
     *
     * 流程：下载 zip -> 校验 SHA256 -> 解压 package.yml -> 完成
     */
    suspend fun install(pkg: HubPackage, workspaceDir: File): HubResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                LogUtils.d(TAG, "Installing ${pkg.name} v${pkg.version}")

                // 1. 下载 zip
                val zipResp = client.get(pkg.archiveUrl)
                if (!zipResp.status.isSuccess())
                    return@withContext HubResult.Failure("Download failed: ${zipResp.status}")
                val zipBytes = zipResp.bodyAsBytes()

                // 2. 下载并验证 SHA256（可选，失败不阻断安装）
                verifySha256(pkg, zipBytes)

                // 3. 解压 package.yml 和 _manifest.yml
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

    // ── 私有 ───────────────────────────────────────────────────

    private suspend fun verifySha256(pkg: HubPackage, zipBytes: ByteArray) {
        try {
            val resp = client.get(pkg.archiveSha256Url)
            if (!resp.status.isSuccess()) return
            val expected = resp.bodyAsText().trim().split("\\s+".toRegex()).first().lowercase()
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


