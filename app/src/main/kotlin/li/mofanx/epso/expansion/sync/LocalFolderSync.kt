package li.mofanx.epso.expansion.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import li.mofanx.epso.app
import li.mofanx.epso.util.LogUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

private const val TAG = "LocalFolderSync"

private val syncMutex = Mutex()

private val json = Json { ignoreUnknownKeys = true }

@Serializable
internal data class SyncState(
    val local: Map<String, SyncFileEntry> = emptyMap(),
    val remote: Map<String, SyncFileEntry> = emptyMap(),
)

@Serializable
internal data class SyncFileEntry(
    val path: String,
    val lastModified: Long,
    val size: Long,
    val sha256: String,
    val isDirectory: Boolean = false,
)

private enum class Direction { PUSH, PULL, SYNC }

private enum class Side { LOCAL, REMOTE }

private enum class ChangeType { UNCHANGED, ADDED, MODIFIED, DELETED }

private sealed class Action(open val path: String) {
    data class CopyLocalToRemote(
        override val path: String,
        val localFile: File,
        val remoteFile: DocumentFile?,
    ) : Action(path)

    data class CopyRemoteToLocal(
        override val path: String,
        val localFile: File?,
        val remoteFile: DocumentFile,
    ) : Action(path)

    data class DeleteLocal(
        override val path: String,
        val localFile: File,
    ) : Action(path)

    data class DeleteRemote(
        override val path: String,
        val remoteFile: DocumentFile,
    ) : Action(path)

    data class ConflictKeepWinner(
        override val path: String,
        val localFile: File,
        val remoteFile: DocumentFile,
        val winner: Side,
        val target: Side,
    ) : Action(path)
}

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

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val remote = remoteDir
        remote != null && remote.exists() && remote.canRead()
    }

    override suspend fun push(localDir: File): SyncResult = runSync(localDir, Direction.PUSH)

    override suspend fun pull(localDir: File): SyncResult = runSync(localDir, Direction.PULL)

    override suspend fun sync(localDir: File): SyncResult = runSync(localDir, Direction.SYNC)

    private val stateKey: String
        get() = "${config.method.name}_${config.uri.hashCode()}_${config.username.hashCode()}"

    private val stateFile: File
        get() = app.filesDir.resolve("sync_state").resolve("$stateKey.json")

    private fun loadState(): SyncState {
        val file = stateFile
        if (!file.exists()) return SyncState()
        return try {
            json.decodeFromString(SyncState.serializer(), file.readText())
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to load sync state", e)
            SyncState()
        }
    }

    private fun saveState(state: SyncState) {
        try {
            val file = stateFile
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SyncState.serializer(), state))
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to save sync state", e)
        }
    }

    private suspend fun runSync(localDir: File, direction: Direction): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val remote = remoteDir ?: return@withContext SyncResult.Failure("Remote directory not accessible")
            val state = loadState()
            val (localFiles, localEntries) = scanLocal(localDir)
            val (remoteFiles, remoteEntries) = scanRemote(remote)

            val counters = Counters()
            val allPaths = (state.local.keys + state.remote.keys + localEntries.keys + remoteEntries.keys)
                .distinct()
                .sortedByDescending { it.count { c -> c == '/' } }

            allPaths.forEach { path ->
                val localFile = localFiles[path]
                val remoteFile = remoteFiles[path]
                val localEntry = localEntries[path]
                val remoteEntry = remoteEntries[path]
                val localOld = state.local[path]
                val remoteOld = state.remote[path]

                val localChange = computeChange(localEntry, localOld)
                val remoteChange = computeChange(remoteEntry, remoteOld)

                val action = resolveAction(
                    path = path,
                    localFile = localFile,
                    remoteFile = remoteFile,
                    localEntry = localEntry,
                    remoteEntry = remoteEntry,
                    localChange = localChange,
                    remoteChange = remoteChange,
                    direction = direction,
                    strategy = config.conflictStrategy,
                )
                action?.let { executeAction(it, localDir, remote, counters) }
            }

            val newState = SyncState(
                local = scanLocal(localDir).second,
                remote = scanRemote(remote).second,
            )
            saveState(newState)

            SyncResult.Success(
                pushed = counters.pushed,
                pulled = counters.pulled,
                deleted = counters.deleted,
                conflicts = counters.conflicts,
            )
        }
    }

    private fun computeChange(current: SyncFileEntry?, old: SyncFileEntry?): ChangeType {
        return when {
            current != null && old == null -> ChangeType.ADDED
            current == null && old != null -> ChangeType.DELETED
            current != null && old != null -> {
                if (current.sha256.isNotEmpty() && current.sha256 == old.sha256) {
                    ChangeType.UNCHANGED
                } else if (current.sha256.isEmpty() || old.sha256.isEmpty()) {
                    if (current.size == old.size && current.lastModified == old.lastModified) {
                        ChangeType.UNCHANGED
                    } else {
                        ChangeType.MODIFIED
                    }
                } else {
                    ChangeType.MODIFIED
                }
            }
            else -> ChangeType.UNCHANGED
        }
    }

    private fun resolveAction(
        path: String,
        localFile: File?,
        remoteFile: DocumentFile?,
        localEntry: SyncFileEntry?,
        remoteEntry: SyncFileEntry?,
        localChange: ChangeType,
        remoteChange: ChangeType,
        direction: Direction,
        strategy: ConflictStrategy,
    ): Action? {
        val localActive = localChange == ChangeType.ADDED || localChange == ChangeType.MODIFIED
        val remoteActive = remoteChange == ChangeType.ADDED || remoteChange == ChangeType.MODIFIED
        val localDeleted = localChange == ChangeType.DELETED
        val remoteDeleted = remoteChange == ChangeType.DELETED
        val localUnchanged = localChange == ChangeType.UNCHANGED
        val remoteUnchanged = remoteChange == ChangeType.UNCHANGED

        // Directories are only relevant for structure; if both sides already have the same directory, nothing to do.
        if (localFile?.isDirectory == true && remoteFile?.isDirectory == true) return null

        // Unchanged vs Unchanged
        if (localUnchanged && remoteUnchanged) {
            val localPresent = localFile != null
            val remotePresent = remoteFile != null
            return when {
                !localPresent && !remotePresent -> null
                localPresent && remotePresent -> null
                localPresent && !remotePresent -> if (direction == Direction.PULL) null else Action.CopyLocalToRemote(path, localFile, remoteFile)
                else -> if (direction == Direction.PUSH) null else Action.CopyRemoteToLocal(path, localFile, remoteFile!!)
            }
        }

        // Active vs Unchanged / Deleted
        if (localActive) {
            if (remoteUnchanged || remoteDeleted) {
                if (direction == Direction.PULL) return null
                return Action.CopyLocalToRemote(path, localFile!!, remoteFile)
            }
        }
        if (remoteActive) {
            if (localUnchanged || localDeleted) {
                if (direction == Direction.PUSH) return null
                return Action.CopyRemoteToLocal(path, localFile, remoteFile!!)
            }
        }

        // Deleted vs Unchanged
        if (localDeleted && remoteUnchanged) {
            if (direction == Direction.PULL) return null
            if (remoteFile != null) return Action.DeleteRemote(path, remoteFile)
            return null
        }
        if (remoteDeleted && localUnchanged) {
            if (direction == Direction.PUSH) return null
            if (localFile != null) return Action.DeleteLocal(path, localFile)
            return null
        }

        // Deleted vs Deleted
        if (localDeleted && remoteDeleted) return null

        // Active vs Active (conflict)
        if (localActive && remoteActive) {
            val localMtime = localEntry?.lastModified ?: 0L
            val remoteMtime = remoteEntry?.lastModified ?: 0L

            val localWins = when (direction) {
                Direction.PUSH, Direction.SYNC -> localMtime >= remoteMtime
                Direction.PULL -> localMtime > remoteMtime
            }
            val remoteWins = when (direction) {
                Direction.PULL, Direction.SYNC -> remoteMtime >= localMtime
                Direction.PUSH -> remoteMtime > localMtime
            }

            val winner = when (direction) {
                Direction.PUSH, Direction.SYNC -> if (localWins) Side.LOCAL else Side.REMOTE
                Direction.PULL -> if (remoteWins) Side.REMOTE else Side.LOCAL
            }

            if (strategy == ConflictStrategy.KeepBoth) {
                val target = when (direction) {
                    Direction.PUSH -> Side.REMOTE
                    Direction.PULL -> Side.LOCAL
                    Direction.SYNC -> if (winner == Side.LOCAL) Side.REMOTE else Side.LOCAL
                }
                return Action.ConflictKeepWinner(path, localFile!!, remoteFile!!, winner, target)
            }

            // LastWriteWins
            return when (direction) {
                Direction.PUSH -> if (winner == Side.LOCAL) Action.CopyLocalToRemote(path, localFile!!, remoteFile) else null
                Direction.PULL -> if (winner == Side.REMOTE) Action.CopyRemoteToLocal(path, localFile, remoteFile!!) else null
                Direction.SYNC -> if (winner == Side.LOCAL) {
                    Action.CopyLocalToRemote(path, localFile!!, remoteFile)
                } else {
                    Action.CopyRemoteToLocal(path, localFile, remoteFile!!)
                }
            }
        }

        return null
    }

    private fun executeAction(action: Action, localDir: File, remoteRoot: DocumentFile, counters: Counters) {
        try {
            when (action) {
                is Action.CopyLocalToRemote -> copyLocalToRemote(action, remoteRoot, counters)
                is Action.CopyRemoteToLocal -> copyRemoteToLocal(action, localDir, counters)
                is Action.DeleteLocal -> deleteLocal(action, counters)
                is Action.DeleteRemote -> deleteRemote(action, counters)
                is Action.ConflictKeepWinner -> conflictKeepWinner(action, localDir, remoteRoot, counters)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to execute action for ${action.path}", e)
        }
    }

    private fun copyLocalToRemote(action: Action.CopyLocalToRemote, remoteRoot: DocumentFile, counters: Counters) {
        val local = action.localFile
        try {
            val remote = if (local.isDirectory) {
                if (action.remoteFile != null && !action.remoteFile.isDirectory) {
                    if (!action.remoteFile.delete()) {
                        LogUtils.e(TAG, "Failed to delete remote file before creating directory: ${action.path}")
                        return
                    }
                }
                action.remoteFile?.takeIf { it.isDirectory } ?: findOrCreateRemoteDir(remoteRoot, action.path)
            } else {
                if (action.remoteFile != null && action.remoteFile.isDirectory) {
                    if (!deleteRemoteDir(action.remoteFile)) {
                        LogUtils.e(TAG, "Failed to delete remote directory before creating file: ${action.path}")
                        return
                    }
                }
                action.remoteFile?.takeIf { it.isFile } ?: findOrCreateRemoteFile(remoteRoot, action.path)
            }
            if (remote == null) {
                LogUtils.e(TAG, "Failed to create remote entry for ${action.path}")
                return
            }
            if (!local.isDirectory) {
                app.contentResolver.openOutputStream(remote.uri)?.use { output ->
                    FileInputStream(local).use { input -> input.copyTo(output) }
                } ?: run {
                    LogUtils.e(TAG, "Failed to open output stream for ${action.path}")
                    return
                }
            }
            counters.pushed++
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to copy local to remote: ${action.path}", e)
        }
    }

    private fun copyRemoteToLocal(action: Action.CopyRemoteToLocal, localDir: File, counters: Counters) {
        val local = localDir.resolve(action.path)
        try {
            if (action.remoteFile.isDirectory) {
                if (local.exists() && !local.isDirectory) {
                    if (!local.delete()) {
                        LogUtils.e(TAG, "Failed to delete local file before creating directory: ${action.path}")
                        return
                    }
                }
                local.mkdirs()
            } else {
                if (local.exists() && local.isDirectory) {
                    if (!local.deleteRecursively()) {
                        LogUtils.e(TAG, "Failed to delete local directory before creating file: ${action.path}")
                        return
                    }
                }
                local.parentFile?.mkdirs()
                app.contentResolver.openInputStream(action.remoteFile.uri)?.use { input ->
                    FileOutputStream(local).use { output -> input.copyTo(output) }
                } ?: run {
                    LogUtils.e(TAG, "Failed to open input stream for ${action.path}")
                    return
                }
                val remoteMtime = action.remoteFile.lastModified()
                if (remoteMtime > 0) local.setLastModified(remoteMtime)
            }
            counters.pulled++
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to copy remote to local: ${action.path}", e)
        }
    }

    private fun deleteLocal(action: Action.DeleteLocal, counters: Counters) {
        val success = if (action.localFile.isDirectory) action.localFile.deleteRecursively() else action.localFile.delete()
        if (success) {
            counters.deleted++
        } else {
            LogUtils.e(TAG, "Failed to delete local file ${action.path}")
        }
    }

    private fun deleteRemote(action: Action.DeleteRemote, counters: Counters) {
        val success = if (action.remoteFile.isDirectory) deleteRemoteDir(action.remoteFile) else action.remoteFile.delete()
        if (success) {
            counters.deleted++
        } else {
            LogUtils.e(TAG, "Failed to delete remote file ${action.path}")
        }
    }

    private fun conflictKeepWinner(
        action: Action.ConflictKeepWinner,
        localDir: File,
        remoteRoot: DocumentFile,
        counters: Counters,
    ) {
        if (action.localFile.isDirectory || action.remoteFile.isDirectory) {
            if (action.winner != action.target) {
                if (action.winner == Side.LOCAL) {
                    copyLocalToRemote(Action.CopyLocalToRemote(action.path, action.localFile, action.remoteFile), remoteRoot, counters)
                } else {
                    copyRemoteToLocal(Action.CopyRemoteToLocal(action.path, action.localFile, action.remoteFile), localDir, counters)
                }
            }
            counters.conflicts++
            return
        }

        // 1. Create conflict copy of the loser on the target side.
        if (action.target == Side.LOCAL) {
            val localConflict = localConflictFile(action.localFile)
            if (action.winner == Side.LOCAL) {
                // loser is remote
                app.contentResolver.openInputStream(action.remoteFile.uri)?.use { input ->
                    FileOutputStream(localConflict).use { output -> input.copyTo(output) }
                } ?: return
            } else {
                // loser is local
                FileInputStream(action.localFile).use { input ->
                    FileOutputStream(localConflict).use { output -> input.copyTo(output) }
                }
            }
        } else {
            val conflictPath = insertConflictSuffix(action.path)
            val remoteConflict = findOrCreateRemoteFile(remoteRoot, conflictPath) ?: return
            app.contentResolver.openOutputStream(remoteConflict.uri)?.use { output ->
                if (action.winner == Side.LOCAL) {
                    // loser is remote
                    app.contentResolver.openInputStream(action.remoteFile.uri)?.use { input ->
                        input.copyTo(output)
                    } ?: return
                } else {
                    // loser is local
                    FileInputStream(action.localFile).use { input -> input.copyTo(output) }
                }
            } ?: return
        }

        // 2. Copy the winner to the target if they are on different sides.
        if (action.winner != action.target) {
            if (action.winner == Side.LOCAL) {
                app.contentResolver.openOutputStream(action.remoteFile.uri)?.use { output ->
                    FileInputStream(action.localFile).use { input -> input.copyTo(output) }
                } ?: return
                counters.pushed++
            } else {
                app.contentResolver.openInputStream(action.remoteFile.uri)?.use { input ->
                    FileOutputStream(action.localFile).use { output -> input.copyTo(output) }
                } ?: return
                val remoteMtime = action.remoteFile.lastModified()
                if (remoteMtime > 0) action.localFile.setLastModified(remoteMtime)
                counters.pulled++
            }
        }

        counters.conflicts++
    }

    private fun scanLocal(localDir: File): Pair<Map<String, File>, Map<String, SyncFileEntry>> {
        val files = mutableMapOf<String, File>()
        val entries = mutableMapOf<String, SyncFileEntry>()
        localDir.walkTopDown().filter { it != localDir && (it.isDirectory || (it.isFile && isYaml(it.name))) }.forEach { file ->
            val path = file.relativeTo(localDir).path
            if (file.isDirectory) {
                files[path] = file
                entries[path] = SyncFileEntry(path, 0, 0, "", isDirectory = true)
            } else {
                val sha256 = file.inputStream().use { hashInputStream(it) }
                val entry = SyncFileEntry(path, file.lastModified(), file.length(), sha256)
                files[path] = file
                entries[path] = entry
            }
        }
        return files to entries
    }

    private fun scanRemote(dir: DocumentFile): Pair<Map<String, DocumentFile>, Map<String, SyncFileEntry>> {
        val files = mutableMapOf<String, DocumentFile>()
        val entries = mutableMapOf<String, SyncFileEntry>()
        scanRemoteRecursive(dir, "", files, entries)
        return files to entries
    }

    private fun scanRemoteRecursive(
        dir: DocumentFile,
        prefix: String,
        files: MutableMap<String, DocumentFile>,
        entries: MutableMap<String, SyncFileEntry>,
    ) {
        dir.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            if (file.isDirectory) {
                val path = "$prefix$name"
                files[path] = file
                entries[path] = SyncFileEntry(path, 0, 0, "", isDirectory = true)
                scanRemoteRecursive(file, "$prefix$name/", files, entries)
            } else if (file.isFile && isYaml(name)) {
                val path = "$prefix$name"
                val sha256 = app.contentResolver.openInputStream(file.uri)?.use { hashInputStream(it) } ?: ""
                val entry = SyncFileEntry(path, file.lastModified(), file.length(), sha256)
                files[path] = file
                entries[path] = entry
            }
        }
    }

    private fun findOrCreateRemoteFile(base: DocumentFile, relativePath: String): DocumentFile? {
        var current = base
        val parts = relativePath.split("/")
        parts.forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed
            if (index < parts.size - 1) {
                val existing = current.findFile(part)
                current = if (existing != null && existing.isDirectory) {
                    existing
                } else if (existing != null) {
                    LogUtils.e(TAG, "Remote path conflict: $part is not a directory")
                    return null
                } else {
                    current.createDirectory(part) ?: return null
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
        val parts = relativePath.split("/")
        parts.forEachIndexed { index, part ->
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

    private fun localConflictFile(localFile: File): File {
        val name = "${localFile.nameWithoutExtension}.conflict.${System.currentTimeMillis()}.${localFile.extension}"
        return localFile.resolveSibling(name)
    }

    private fun insertConflictSuffix(path: String): String {
        val ext = path.substringAfterLast('.', "")
        return if (ext.isEmpty()) {
            "$path.conflict.${System.currentTimeMillis()}"
        } else {
            "${path.substringBeforeLast('.')}.conflict.${System.currentTimeMillis()}.$ext"
        }
    }

    private fun isYaml(name: String): Boolean {
        return name.endsWith(".yml", ignoreCase = true) || name.endsWith(".yaml", ignoreCase = true)
    }

    private fun hashInputStream(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }
}
