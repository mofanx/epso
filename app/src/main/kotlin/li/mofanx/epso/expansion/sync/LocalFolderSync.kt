package li.mofanx.epso.expansion.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.util.LogUtils
import java.io.File

private const val TAG = "LocalFolderSync"

/**
 * 本地文件夹同步
 *
 * 将工作区 YAML 文件与本地另一目录保持一致，适合搭配 Syncthing / 挂载 SMB 使用。
 *
 * 冲突策略：
 * - [ConflictStrategy.LastWriteWins]：比较 lastModified，保留较新文件
 * - [ConflictStrategy.KeepBoth]：冲突时本地复制一份 `.conflict` 后缀，再拉取远端
 */
class LocalFolderSync(private val config: SyncConfig) : SyncManager {

    private val remoteDir: File
        get() = File(config.uri)

    // ── 接口实现 ───────────────────────────────────────────────

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        remoteDir.exists() && remoteDir.isDirectory && remoteDir.canRead()
    }

    override suspend fun push(localDir: File): SyncResult = withContext(Dispatchers.IO) {
        runCatching {
            ensureRemoteDir()
            val pushed = localYamls(localDir).count { localFile ->
                val remoteFile = remoteDir.resolve(localFile.name)
                copyIfNewer(src = localFile, dst = remoteFile)
            }
            LogUtils.d(TAG, "Pushed $pushed files to ${remoteDir.absolutePath}")
            SyncResult.Success(pushed = pushed)
        }.getOrElse { SyncResult.Failure(it.message ?: "Push failed") }
    }

    override suspend fun pull(localDir: File): SyncResult = withContext(Dispatchers.IO) {
        runCatching {
            ensureRemoteDir()
            var pulled = 0
            var conflicts = 0
            remoteYamls().forEach { remoteFile ->
                val localFile = localDir.resolve(remoteFile.name)
                if (!localFile.exists()) {
                    remoteFile.copyTo(localFile)
                    pulled++
                } else {
                    when (config.conflictStrategy) {
                        ConflictStrategy.LastWriteWins -> {
                            if (remoteFile.lastModified() > localFile.lastModified()) {
                                remoteFile.copyTo(localFile, overwrite = true)
                                pulled++
                            }
                        }
                        ConflictStrategy.KeepBoth -> {
                            if (remoteFile.lastModified() > localFile.lastModified()) {
                                // 保留本地为 .conflict
                                localFile.renameTo(
                                    localDir.resolve("${localFile.nameWithoutExtension}.conflict.yml")
                                )
                                remoteFile.copyTo(localFile, overwrite = true)
                                pulled++
                                conflicts++
                            }
                        }
                    }
                }
            }
            LogUtils.d(TAG, "Pulled $pulled files, $conflicts conflicts")
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
        SyncResult.Success(
            pushed = q.pushed,
            pulled = p.pulled,
            conflicts = p.conflicts,
        )
    }

    // ── 私有辅助 ───────────────────────────────────────────────

    private fun ensureRemoteDir() {
        if (!remoteDir.exists()) remoteDir.mkdirs()
    }

    private fun localYamls(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.toList() ?: emptyList()

    private fun remoteYamls(): List<File> =
        remoteDir.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.toList() ?: emptyList()

    /**
     * 若 src 比 dst 新（或 dst 不存在），复制并返回 true
     */
    private fun copyIfNewer(src: File, dst: File): Boolean {
        if (!dst.exists() || src.lastModified() > dst.lastModified()) {
            src.copyTo(dst, overwrite = true)
            return true
        }
        return false
    }
}
