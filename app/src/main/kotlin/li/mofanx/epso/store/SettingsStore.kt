package li.mofanx.epso.store

import kotlinx.serialization.Serializable

@Serializable
data class SettingsStore(
    val enableShizuku: Boolean = false,
    val enableStatusService: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val httpServerPort: Int = 8888,
    val httpApiToken: String = "",
    val enableDarkTheme: Boolean? = null,
    val enableDynamicColor: Boolean = true,
    val useSystemToast: Boolean = false,

    // 文本扩展相关配置
    /** 是否启用文本扩展服务 */
    val enableExpansion: Boolean = true,
    /** 自定义 YAML 工作区路径（空 = 使用默认内置路径 <filesDir>/matches/） */
    val expansionWorkspacePath: String = "",
    /** 默认触发前缀，空字符串表示无前缀 */
    val triggerPrefix: String = ":",
    /** 防抖延迟（毫秒），输入停止后等待此时间再触发匹配 */
    val expansionDebounceMs: Long = 300L,
    /** 快速搜索触发词，输入后弹出悬浮搜索窗（空 = 禁用） */
    val searchTrigger: String = ":s",

    // 同步配置
    /** 同步方式：None / LocalFolder / WebDav */
    val syncMethod: String = "None",
    /** 同步目标 URI（本地文件夹 content:// URI 或 WebDAV URL） */
    val syncUri: String = "",
    /** WebDAV 用户名 */
    val syncUsername: String = "",
    /** WebDAV 密码（明文，存在本地 SharedPreferences，不传服务器） */
    val syncPassword: String = "",
    /** 冲突策略：LastWriteWins / KeepBoth */
    val syncConflictStrategy: String = "LastWriteWins",
    /** 仅在 WiFi 下自动同步 */
    val syncWifiOnly: Boolean = true,
    /** 自动同步：每次工作区有修改后触发推送 */
    val syncAutoOnSave: Boolean = false,

    /** 是否已同意使用声明与无障碍协议 */
    val termsAccepted: Boolean = false,
)