package li.mofanx.epso.expansion.sync

import li.mofanx.epso.store.SettingsStore
import java.io.File

// ──────────────────────────────────────────────────────────────
// 数据模型
// ──────────────────────────────────────────────────────────────

enum class SyncMethod { None, LocalFolder, WebDav }

enum class ConflictStrategy { LastWriteWins, KeepBoth }

data class SyncConfig(
    val method: SyncMethod = SyncMethod.None,
    val uri: String = "",
    val username: String = "",
    val password: String = "",
    val conflictStrategy: ConflictStrategy = ConflictStrategy.LastWriteWins,
    val wifiOnly: Boolean = true,
    val autoOnSave: Boolean = false,
)

fun SettingsStore.toSyncConfig(): SyncConfig {
    val method = runCatching { SyncMethod.valueOf(syncMethod) }.getOrElse { SyncMethod.None }
    val strategy = runCatching { ConflictStrategy.valueOf(syncConflictStrategy) }.getOrElse { ConflictStrategy.LastWriteWins }
    return SyncConfig(
        method = method,
        uri = syncUri,
        username = syncUsername,
        password = syncPassword,
        conflictStrategy = strategy,
        wifiOnly = syncWifiOnly,
        autoOnSave = syncAutoOnSave,
    )
}

sealed class SyncResult {
    data class Success(
        val pushed: Int = 0,
        val pulled: Int = 0,
        val deleted: Int = 0,
        val conflicts: Int = 0,
        val message: String = "",
    ) : SyncResult()

    data class Failure(val error: String) : SyncResult()
}

// ──────────────────────────────────────────────────────────────
// 接口
// ──────────────────────────────────────────────────────────────

/**
 * 同步管理器接口
 *
 * 所有实现必须是幂等的：重复调用 push/pull 结果相同（不产生重复文件）。
 */
interface SyncManager {
    /** 推送本地工作区到远端 */
    suspend fun push(localDir: File): SyncResult

    /** 拉取远端内容到本地工作区 */
    suspend fun pull(localDir: File): SyncResult

    /** 双向合并 */
    suspend fun sync(localDir: File): SyncResult

    /** 测试连接可用性 */
    suspend fun testConnection(): Boolean
}

// ──────────────────────────────────────────────────────────────
// 工厂方法
// ──────────────────────────────────────────────────────────────

/**
 * 根据 [SyncConfig] 创建对应的 [SyncManager] 实现
 */
fun SyncManager(config: SyncConfig): SyncManager? = when (config.method) {
    SyncMethod.None -> null
    SyncMethod.LocalFolder -> LocalFolderSync(config)
    SyncMethod.WebDav -> WebDavSync(config)
}
