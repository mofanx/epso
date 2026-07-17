package li.mofanx.epso.expansion.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

// ──────────────────────────────────────────────────────────────
// 公共数据模型
// ──────────────────────────────────────────────────────────────

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

internal enum class Direction { PUSH, PULL, SYNC }
internal enum class Side { LOCAL, REMOTE }
internal enum class ChangeType { UNCHANGED, ADDED, MODIFIED, DELETED }

/**
 * 中性的同步动作。
 *
 * 实现类根据 [path] 找到自己维护的本地文件对象或远程 URL 后执行。
 */
internal sealed class SyncAction(open val path: String) {
    data class CopyToRemote(override val path: String) : SyncAction(path)
    data class CopyToLocal(override val path: String) : SyncAction(path)
    data class DeleteRemote(override val path: String) : SyncAction(path)
    data class DeleteLocal(override val path: String) : SyncAction(path)
    data class ResolveConflict(
        override val path: String,
        val winner: Side,
        val target: Side,
        val strategy: ConflictStrategy,
    ) : SyncAction(path)
    data object Nothing : SyncAction("")
}

// ──────────────────────────────────────────────────────────────
// 变更检测
// ──────────────────────────────────────────────────────────────

/**
 * 比较当前扫描结果 [current] 与上次同步保存的 [old]，判断变更类型。
 *
 * 目录只比较存在性；文件优先比较 [sha256]，其次回退到 size + lastModified。
 */
internal fun computeChange(current: SyncFileEntry?, old: SyncFileEntry?): ChangeType {
    return when {
        current != null && old == null -> ChangeType.ADDED
        current == null && old != null -> ChangeType.DELETED
        current != null && old != null -> {
            if (current.isDirectory && old.isDirectory) {
                ChangeType.UNCHANGED
            } else if (current.sha256.isNotEmpty() && old.sha256.isNotEmpty()) {
                // 双方都有内容校验，直接比较 sha256
                if (current.sha256 == old.sha256) ChangeType.UNCHANGED else ChangeType.MODIFIED
            } else if (current.sha256.isNotEmpty() && old.sha256.isEmpty()) {
                // 当前已能计算内容校验，但旧状态缺少：保守视为修改，触发重新同步
                ChangeType.MODIFIED
            } else if (
                current.size != old.size ||
                current.lastModified != old.lastModified
            ) {
                ChangeType.MODIFIED
            } else {
                ChangeType.UNCHANGED
            }
        }
        else -> ChangeType.UNCHANGED
    }
}

// ──────────────────────────────────────────────────────────────
// 冲突解决与动作生成
// ──────────────────────────────────────────────────────────────

/**
 * 根据当前扫描结果、历史状态、同步方向和冲突策略，生成下一步动作。
 *
 * 这是同步算法的核心，两个实现（本地文件夹 / WebDAV）共用同一份决策逻辑。
 */
internal fun resolveAction(
    path: String,
    localEntry: SyncFileEntry?,
    remoteEntry: SyncFileEntry?,
    localChange: ChangeType,
    remoteChange: ChangeType,
    direction: Direction,
    strategy: ConflictStrategy,
): SyncAction? {
    val localPresent = localEntry != null
    val remotePresent = remoteEntry != null
    val localActive = localChange == ChangeType.ADDED || localChange == ChangeType.MODIFIED
    val remoteActive = remoteChange == ChangeType.ADDED || remoteChange == ChangeType.MODIFIED
    val localDeleted = localChange == ChangeType.DELETED
    val remoteDeleted = remoteChange == ChangeType.DELETED
    val localUnchanged = localChange == ChangeType.UNCHANGED
    val remoteUnchanged = remoteChange == ChangeType.UNCHANGED

    // 同一路径两侧类型不同（文件 vs 目录）属于冲突
    if (localEntry != null && remoteEntry != null && localEntry.isDirectory != remoteEntry.isDirectory) {
        return resolveConflict(path, localEntry, remoteEntry, direction, strategy)
    }

    // 两侧都是目录，或两侧都未变更
    if (localEntry?.isDirectory == true && remoteEntry?.isDirectory == true) return null
    if (localUnchanged && remoteUnchanged) {
        return when {
            !localPresent && !remotePresent -> null
            localPresent && remotePresent -> null
            localPresent && !remotePresent -> if (direction == Direction.PULL) null else SyncAction.CopyToRemote(path)
            else -> if (direction == Direction.PUSH) null else SyncAction.CopyToLocal(path)
        }
    }

    // 目录只存在于单侧：把目录结构镜像到另一侧
    if (localEntry?.isDirectory == true && remoteEntry == null) {
        return if (direction == Direction.PULL) null else SyncAction.CopyToRemote(path)
    }
    if (remoteEntry?.isDirectory == true && localEntry == null) {
        return if (direction == Direction.PUSH) null else SyncAction.CopyToLocal(path)
    }

    // 不变 vs 删除
    if (localUnchanged && remoteDeleted) {
        return if (direction == Direction.PUSH) null else SyncAction.DeleteLocal(path)
    }
    if (remoteUnchanged && localDeleted) {
        return if (direction == Direction.PULL) null else SyncAction.DeleteRemote(path)
    }

    // 不变 vs 新增/修改
    if (localUnchanged && remoteActive) {
        return if (direction == Direction.PUSH) null else SyncAction.CopyToLocal(path)
    }
    if (remoteUnchanged && localActive) {
        return if (direction == Direction.PULL) null else SyncAction.CopyToRemote(path)
    }

    // 删除 vs 新增/修改（一方删除、另一方变更 => 冲突）
    if ((localDeleted && (remoteActive || remotePresent)) ||
        (remoteDeleted && (localActive || localPresent))
    ) {
        return resolveConflict(path, localEntry, remoteEntry, direction, strategy)
    }

    // 双方都删除
    if (localDeleted && remoteDeleted) return null

    // 双方都变更（冲突）
    if (localActive && remoteActive) {
        return resolveConflict(path, localEntry, remoteEntry, direction, strategy)
    }

    return null
}

private fun resolveConflict(
    path: String,
    localEntry: SyncFileEntry?,
    remoteEntry: SyncFileEntry?,
    direction: Direction,
    strategy: ConflictStrategy,
): SyncAction {
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

    val target = when (direction) {
        Direction.PUSH -> Side.REMOTE
        Direction.PULL -> Side.LOCAL
        Direction.SYNC -> if (winner == Side.LOCAL) Side.REMOTE else Side.LOCAL
    }

    return SyncAction.ResolveConflict(path, winner, target, strategy)
}

// ──────────────────────────────────────────────────────────────
// 工具函数
// ──────────────────────────────────────────────────────────────

/**
 * 生成稳定的状态文件 key。使用 SHA-256 替代 hashCode，避免 key 冲突。
 * [localPath] 加入本地工作区路径，避免同一远端对应多个本地工作区时状态串扰。
 */
internal fun makeStableStateKey(method: String, uri: String, username: String, localPath: String = ""): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$method|$uri|$username|$localPath".toByteArray(Charsets.UTF_8)
    return digest.digest(input).joinToString("") { String.format("%02x", it) }
}

/**
 * 计算输入流的 SHA-256 十六进制摘要。
 */
internal fun hashInputStream(input: InputStream): String {
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

/**
 * 判断文件/目录名是否为隐藏项。
 */
internal fun isHidden(name: String): Boolean = name.startsWith(".")

/**
 * 为冲突文件生成一个带时间戳的新路径。
 * 保证无扩展名文件也能得到干净的文件名（不会以 . 结尾）。
 */
internal fun insertConflictSuffix(path: String): String {
    val ext = path.substringAfterLast('.', "")
    return if (ext.isEmpty()) {
        "$path.conflict.${System.currentTimeMillis()}"
    } else {
        "${path.substringBeforeLast('.')}.conflict.${System.currentTimeMillis()}.$ext"
    }
}

/**
 * 在本地文件旁边生成冲突副本文件。
 */
internal fun localConflictFile(file: File): File {
    return file.resolveSibling(insertConflictSuffix(file.name))
}

// ──────────────────────────────────────────────────────────────
// 全局同步锁
// ──────────────────────────────────────────────────────────────

/**
 * 按同步状态 key 维护的进程内互斥锁。
 *
 * 保证同一 [stateKey]（同一远端配置 + 同一本地工作区）的 push/pull/sync 串行执行，
 * 避免手动同步和自动推送并发读写同一个状态文件。
 */
internal object SyncLock {
    private val mutexes = mutableMapOf<String, Mutex>()
    private val mapMutex = Mutex()

    suspend inline fun <T> withLock(stateKey: String, block: () -> T): T {
        val mutex = mapMutex.withLock {
            mutexes.getOrPut(stateKey) { Mutex() }
        }
        return mutex.withLock { block() }
    }
}
