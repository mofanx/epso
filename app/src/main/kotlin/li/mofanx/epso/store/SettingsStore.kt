package li.mofanx.epso.store

import kotlinx.serialization.Serializable

@Serializable
data class SettingsStore(
    val enableShizuku: Boolean = false,
    val enableStatusService: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val httpServerPort: Int = 8888,
    val enableDarkTheme: Boolean? = null,
    val enableDynamicColor: Boolean = true,
    val useSystemToast: Boolean = false,
)