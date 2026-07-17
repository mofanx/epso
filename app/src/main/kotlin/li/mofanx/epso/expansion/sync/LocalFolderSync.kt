package li.mofanx.epso.expansion.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import li.mofanx.epso.app
import li.mofanx.epso.util.LogUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "LocalFolderSync"

private val json = Json { ignoreUnknownKeys = true }

private class Counters {
    var pushed: Int = 0
    var pulled: Int = 0
    var deleted: Int = 0
    var conflicts: Int = 0
}

class LocalFolderSync(private val config: SyncConfig) : SyncManager {

    private val remoteDir: DocumentFile?
        get() {
            val uri = config.uri.takeIf { it.isNotBlank() } ?: return null
            return DocumentFile.fromTreeUri(app, Uri.parse(uri))
        }

    private fun stateKey(localDir: File): String =
        makeStableStateKey(config.method.name, config.uri, config.username, localDir.absolutePath)

    private fun stateFile(localDir: File): File =
        app.filesDir.resolve("sync_state").resolve("${stateKey(localDir)}.json")

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val remote = remoteDir
        remote != null && remote.exists() && remote.canRead()
    }

    override suspend fun push(localDir: File): SyncResult = runSync(localDir, Direction.PUSH)

    override suspend fun pull(localDir: File): SyncResult = runSync(localDir, Direction.PULL)

    override suspend fun sync(localDir: File): SyncResult = runSync(localDir, Direction.SYNC)

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
            val remote = remoteDir ?: return@withContext SyncResult.Failure("Remote directory not accessible")
            val state = loadState(localDir)
            val localMap = scanLocal(localDir)
            val remoteMap = scanRemote(remote)

            val counters = Counters()
            val failures = mutableListOf<String>()

            val allPaths = buildSyncPaths(
                (state.local.keys + state.remote.keys + localMap.keys + remoteMap.keys),
                localMap,
                remoteMap,
                direction,
            )

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
                    executeAction(action, localDir, remote, counters)
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
                    remote = scanRemote(remote),
                )
            )

            SyncResult.Success(
                pushed = counters.pushed,
                pulled = counters.pulled,
                deleted = counters.deleted,
                conflicts = counters.conflicts,
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 动作执行
    // ──────────────────────────────────────────────────────────────

    private fun executeAction(action: SyncAction, localDir: File, remoteRoot: DocumentFile, counters: Counters) {
        when (action) {
            is SyncAction.CopyToRemote -> copyLocalToRemote(action.path, localDir, remoteRoot, counters)
            is SyncAction.CopyToLocal -> copyRemoteToLocal(action.path, localDir, remoteRoot, counters)
            is SyncAction.DeleteRemote -> deleteRemote(action.path, remoteRoot, counters)
            is SyncAction.DeleteLocal -> deleteLocal(action.path, localDir, counters)
            is SyncAction.ResolveConflict -> resolveConflictAction(action, localDir, remoteRoot, counters)
            is SyncAction.Nothing -> Unit
        }
    }

    private fun copyLocalToRemote(path: String, localDir: File, remoteRoot: DocumentFile, counters: Counters) {
        val local = localDir.resolve(path)
        if (!local.exists()) throw IllegalStateException("Local file does not exist: $path")

        if (local.isDirectory) {
            findOrCreateRemoteDir(remoteRoot, path)
                ?: throw IllegalStateException("Failed to create remote directory: $path")
        } else {
            val existing = findRemoteEntry(remoteRoot, path)
            if (existing != null && existing.isDirectory) {
                if (!deleteRemoteDir(existing)) {
                    throw IllegalStateException("Failed to delete remote directory before copying file: $path")
                }
            }
            val remote = existing?.takeIf { it.isFile }
                ?: findOrCreateRemoteFile(remoteRoot, path)
                ?: throw IllegalStateException("Failed to create remote file: $path")

            app.contentResolver.openOutputStream(remote.uri)?.use { output ->
                FileInputStream(local).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open output stream for $path")
        }
        counters.pushed++
    }

    private fun copyRemoteToLocal(path: String, localDir: File, remoteRoot: DocumentFile, counters: Counters) {
        val local = localDir.resolve(path)
        val remote = findRemoteEntry(remoteRoot, path)
            ?: throw IllegalStateException("Remote entry not found: $path")

        if (remote.isDirectory) {
            if (local.exists() && !local.isDirectory) {
                if (!local.delete()) throw IllegalStateException("Failed to delete local file before creating directory: $path")
            }
            local.mkdirs()
        } else {
            if (local.exists() && local.isDirectory) {
                if (!local.deleteRecursively()) {
                    throw IllegalStateException("Failed to delete local directory before copying file: $path")
                }
            }
            local.parentFile?.mkdirs()
            app.contentResolver.openInputStream(remote.uri)?.use { input ->
                FileOutputStream(local).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open input stream for $path")
            val remoteMtime = remote.lastModified()
            if (remoteMtime > 0) local.setLastModified(remoteMtime)
        }
        counters.pulled++
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

    private fun deleteRemote(path: String, remoteRoot: DocumentFile, counters: Counters) {
        val remote = findRemoteEntry(remoteRoot, path) ?: return
        val success = if (remote.isDirectory) deleteRemoteDir(remote) else remote.delete()
        if (success) {
            counters.deleted++
        } else {
            throw IllegalStateException("Failed to delete remote file: $path")
        }
    }

    private fun resolveConflictAction(
        action: SyncAction.ResolveConflict,
        localDir: File,
        remoteRoot: DocumentFile,
        counters: Counters,
    ) {
        if (action.winner == action.target) {
            counters.conflicts++
            return
        }

        when (action.strategy) {
            ConflictStrategy.LastWriteWins -> {
                if (action.winner == Side.LOCAL) {
                    copyLocalToRemote(action.path, localDir, remoteRoot, counters)
                } else {
                    copyRemoteToLocal(action.path, localDir, remoteRoot, counters)
                }
            }
            ConflictStrategy.KeepBoth -> {
                // 1. 在目标侧备份失败方内容
                if (action.target == Side.LOCAL) {
                    val local = localDir.resolve(action.path)
                    if (local.exists() && local.isFile) {
                        local.copyTo(localConflictFile(local), overwrite = true)
                    }
                } else {
                    val remoteTarget = findRemoteEntry(remoteRoot, action.path)
                    if (remoteTarget != null && remoteTarget.isFile) {
                        val conflictPath = insertConflictSuffix(action.path)
                        val remoteConflict = findOrCreateRemoteFile(remoteRoot, conflictPath)
                            ?: throw IllegalStateException("Failed to create remote conflict file: $conflictPath")
                        app.contentResolver.openOutputStream(remoteConflict.uri)?.use { output ->
                            app.contentResolver.openInputStream(remoteTarget.uri)?.use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // 2. 将胜利方内容复制到目标侧
                if (action.winner == Side.LOCAL) {
                    copyLocalToRemote(action.path, localDir, remoteRoot, counters)
                } else {
                    copyRemoteToLocal(action.path, localDir, remoteRoot, counters)
                }
            }
        }
        counters.conflicts++
    }

    // ──────────────────────────────────────────────────────────────
    // 扫描
    // ──────────────────────────────────────────────────────────────

    private fun scanLocal(localDir: File): Map<String, SyncFileEntry> {
        val entries = mutableMapOf<String, SyncFileEntry>()
        if (!localDir.exists()) return entries
        localDir.walkTopDown()
            .onEnter { !isHidden(it.name) }
            .filter { it != localDir && !isHidden(it.name) }
            .forEach { file ->
                val path = file.relativeTo(localDir).path
                if (file.isDirectory) {
                    entries[path] = SyncFileEntry(path, 0, 0, "", isDirectory = true)
                } else {
                    val sha256 = file.inputStream().use { hashInputStream(it) }
                    entries[path] = SyncFileEntry(path, file.lastModified(), file.length(), sha256)
                }
            }
        return entries
    }

    private fun scanRemote(dir: DocumentFile): Map<String, SyncFileEntry> {
        val entries = mutableMapOf<String, SyncFileEntry>()
        scanRemoteRecursive(dir, "", entries)
        return entries
    }

    private fun scanRemoteRecursive(
        dir: DocumentFile,
        prefix: String,
        entries: MutableMap<String, SyncFileEntry>,
    ) {
        dir.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            if (isHidden(name)) return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (file.isDirectory) {
                entries[path] = SyncFileEntry(path, 0, 0, "", isDirectory = true)
                scanRemoteRecursive(file, path, entries)
            } else {
                val sha256 = app.contentResolver.openInputStream(file.uri)?.use { hashInputStream(it) } ?: ""
                entries[path] = SyncFileEntry(path, file.lastModified(), file.length(), sha256)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 远程文件系统辅助
    // ──────────────────────────────────────────────────────────────

    private fun findRemoteEntry(root: DocumentFile, relativePath: String): DocumentFile? {
        var current: DocumentFile? = root
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        for ((index, part) in parts.withIndex()) {
            if (current == null) return null
            val child = current.findFile(part) ?: return null
            if (index == parts.lastIndex) return child
            if (!child.isDirectory) return null
            current = child
        }
        return current
    }

    private fun findOrCreateRemoteFile(base: DocumentFile, relativePath: String): DocumentFile? {
        var current = base
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed
            if (index < parts.size - 1) {
                val existing = current.findFile(part)
                current = when {
                    existing != null && existing.isDirectory -> existing
                    existing != null -> {
                        LogUtils.e(TAG, "Remote path conflict: $part is not a directory")
                        return null
                    }
                    else -> current.createDirectory(part) ?: return null
                }
            } else {
                val existing = current.findFile(part)
                if (existing != null && existing.isFile) return existing
                if (existing != null && existing.isDirectory) {
                    LogUtils.e(TAG, "Remote path conflict: $part is a directory")
                    return null
                }
                current = current.createFile("application/octet-stream", part) ?: return null
            }
        }
        return current
    }

    private fun findOrCreateRemoteDir(base: DocumentFile, relativePath: String): DocumentFile? {
        var current = base
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        parts.forEachIndexed { _, part ->
            if (part.isEmpty()) return@forEachIndexed
            val existing = current.findFile(part)
            if (existing != null) {
                if (existing.isDirectory) {
                    current = existing
                } else {
                    if (!existing.delete()) return null
                    val newDir = current.createDirectory(part) ?: return null
                    current = newDir
                }
            } else {
                val newDir = current.createDirectory(part) ?: return null
                current = newDir
            }
        }
        return current
    }

    private fun deleteRemoteDir(dir: DocumentFile): Boolean {
        var success = true
        dir.listFiles().forEach { child ->
            if (child.isDirectory) {
                if (!deleteRemoteDir(child)) success = false
            } else {
                if (!child.delete()) success = false
            }
        }
        if (!dir.delete()) success = false
        return success
    }
}
