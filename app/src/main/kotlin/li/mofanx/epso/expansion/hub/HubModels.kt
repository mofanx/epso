package li.mofanx.epso.expansion.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

// ──────────────────────────────────────────────────────────────
// Hub 数据模型（单独文件，避免循环依赖和编译顺序问题）
// ──────────────────────────────────────────────────────────────

@Serializable
data class HubPackage(
    val name: String = "",
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val version: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("archive_url") val archiveUrl: String = "",
    @SerialName("archive_sha256_url") val archiveSha256Url: String = "",
)

@Serializable
data class PackageIndex(
    @SerialName("last_update") val lastUpdate: Long = 0L,
    val packages: List<HubPackage> = emptyList(),
)

sealed class HubResult<out T> {
    data class Success<T>(val data: T) : HubResult<T>()
    data class Failure(val error: String) : HubResult<Nothing>()
}

data class InstalledPackage(val name: String, val dir: File)
