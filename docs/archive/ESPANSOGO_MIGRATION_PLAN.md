# espansogo → epso 功能迁移重构计划

> **状态：已完成**（历史归档）
> 本文档记录的功能迁移计划已基本实现，当前仅作历史参考。如需了解最新实现，请直接查看 `app/src/main/kotlin/li/mofanx/epso/expansion/` 目录。

> 目标：将 espansogo（.NET MAUI + Blazor）的文本扩展功能，以原生 Kotlin + Jetpack Compose 重新实现，移植到 epso 框架中，最终形成一个完整可用的 Android 文本扩展应用。

---

## 一、现状差距分析

### epso 现有 `expansion/` 模块

| 文件 | 现状 | 问题 |
|---|---|---|
| `Match.kt` | 有字段定义，`@Serializable` | `propagate_case`/`vars`/`form` 字段**未实现**；缺 `triggers(多触发词)`、`left_word`、`right_word`、`Params` |
| `TriggerMatcher.kt` | 精确 + 正则匹配，Mutex 线程安全 | 不支持多触发词；无 `left_word`/`right_word` 细粒度单词边界 |
| `TextReplacer.kt` | 两种替换策略，`$|$` 光标标记 | `undo`/`setClipboard` 未实现；变量求值完全缺失 |
| `ExpansionService.kt` | 防抖 300ms、StateFlow 状态管理 | 规则来源硬编码，无持久化；无变量求值调用链 |
| `ExpansionTestPage.kt` | 简单的添加规则 + 测试 | 无规则列表/编辑/删除；无 YAML 导入 |

### espansogo 已实现、需要移植的功能

| 功能 | 复杂度 | 优先级 |
|---|---|---|
| **完整 Match/Var/Params 数据模型** | 低 | P0 |
| **多触发词 `triggers`** | 低 | P0 |
| **`left_word` / `right_word` 细粒度边界** | 低 | P0 |
| **变量系统**：`echo` / `date` / `clipboard` / `random` / `choice` | 中 | P0 |
| **`propagate_case` + `uppercase_style`** | 低 | P0 |
| **YAML 多文件读写**（espanso 兼容格式） | 中 | P0 |
| **`imports` 递归解析**（最大深度 10，防循环） | 中 | P1 |
| **表单（Form）支持**：`[[field]]` 语法 + 悬浮窗 | 高 | P1 |
| **全局变量管理** | 低 | P1 |
| **规则管理 UI**：列表 / 编辑 / 删除 / 搜索 | 中 | P1 |
| **文件管理 UI**：按 YAML 文件分组 | 中 | P2 |
| **同步**：WebDAV / 本地文件夹 | 高 | P2 |
| **包商店（Hub）** | 高 | P3 |

---

## 二、架构设计

```
expansion/
├── model/
│   ├── Match.kt            ← 扩展现有，补齐所有字段
│   ├── Var.kt              ← 拆分出独立文件，补齐 Params
│   └── MatchGroup.kt       ← 新增，对应一个 YAML 文件
├── store/
│   ├── YamlWorkspace.kt    ← 新增，YAML 多文件读写
│   └── MatchStore.kt       ← 新增，运行时规则仓库（Flow）
├── engine/
│   ├── TriggerMatcher.kt   ← 改造，支持多触发词 + 细粒度边界
│   ├── VarEvaluator.kt     ← 新增，变量求值
│   └── TextReplacer.kt     ← 改造，接入 VarEvaluator
├── form/
│   └── FormOverlayWindow.kt ← 新增，表单悬浮窗
├── sync/
│   ├── SyncManager.kt      ← 新增，同步管理器
│   └── WebDavClient.kt     ← 新增，WebDAV 客户端（复用 Ktor）
├── ExpansionService.kt     ← 改造，接入 MatchStore
└── ui/
    ├── MatchListPage.kt    ← 新增
    ├── MatchEditorPage.kt  ← 新增
    ├── FilesPage.kt        ← 新增
    ├── GlobalVarsPage.kt   ← 新增
    ├── SyncSettingsPage.kt ← 新增
    └── ExpansionTestPage.kt ← 改造，增强
```

---

## 三、分阶段实施计划

### Phase 0 — 依赖准备（约 0.5 天）

**目标**：确认并补充所需依赖。

**检查项**：
- `kaml`（Kotlin YAML 序列化）或 `snakeyaml`：epso 目前无 YAML 库，需选型并添加
  - 推荐 `com.charleskorn.kaml:kaml`（基于 kotlinx.serialization，与现有序列化体系一致）
- `ktor-client-okhttp`：已有，可直接用于 WebDAV
- `kotlinx.coroutines`：已有
- `exp4j`：已有，可备用于表达式变量

**需要新增的依赖**（`libs.versions.toml`）：
```toml
kaml = "0.61.0"

[libraries]
kaml = { module = "com.charleskorn.kaml:kaml", version.ref = "kaml" }
```

---

### Phase 1 — 数据模型重构（约 1 天）

**目标**：让 Kotlin 数据模型与 espanso YAML 格式完全对齐。

#### 1.1 扩展 `Match.kt`

在现有基础上补充以下字段：

```kotlin
@Serializable
data class Match(
    val trigger: String = "",
    val triggers: List<String> = emptyList(),   // 新增：多触发词
    val replace: String = "",
    val regex: String = "",
    val word: Boolean = false,
    val left_word: Boolean = false,             // 新增
    val right_word: Boolean = false,            // 新增
    val propagate_case: Boolean = false,
    val uppercase_style: String = "uppercase",  // 新增：uppercase / capitalize / capitalize_words
    val vars: List<Var> = emptyList(),
    val form: String? = null,
    val form_fields: Map<String, FormField> = emptyMap(), // 新增
    val label: String? = null,
) {
    /** 所有有效触发词（trigger + triggers 合并去重） */
    val allTriggers: List<String> get() = (listOf(trigger) + triggers).filter { it.isNotEmpty() }.distinct()
    val isRegex: Boolean get() = regex.isNotEmpty()
    val isForm: Boolean get() = form != null
    val isValid: Boolean get() = allTriggers.isNotEmpty() && (replace.isNotEmpty() || isForm)
}
```

#### 1.2 重构 `Var.kt`（拆分出独立文件）

```kotlin
@Serializable
data class Var(
    val name: String = "",
    val type: String = "",    // echo / date / clipboard / random / choice / shell / script / http / match
    val params: VarParams = VarParams(),
)

@Serializable
data class VarParams(
    val echo: String? = null,
    val format: String? = null,         // date 格式，兼容 strftime（需转换为 Java DateTimeFormatter）
    val offset: Long = 0L,              // date 偏移（秒）
    val choices: List<String> = emptyList(),   // random / choice
    val values: List<String> = emptyList(),    // choice 别名
    val cmd: String? = null,            // shell / script
    val args: List<String> = emptyList(),
    val trim: Boolean = false,
    val ignore_error: Boolean = false,
    val url: String? = null,            // http
    val method: String = "GET",
    val body: String? = null,
    val json_path: String? = null,
    val trigger: String? = null,        // match 类型：递归触发
    val code: String? = null,           // javascript
)

@Serializable
data class FormField(
    val type: String = "input",         // input / multiline / list
    val default: String? = null,
    val values: List<String> = emptyList(),
)
```

#### 1.3 新增 `MatchGroup.kt`

```kotlin
@Serializable
data class MatchGroup(
    val matches: List<Match> = emptyList(),
    val global_vars: List<Var> = emptyList(),
    val imports: List<String> = emptyList(),
    @Transient val sourceFile: String = "",  // 运行时，不序列化到 YAML
)
```

---

### Phase 2 — YAML 工作区（约 1.5 天）

**目标**：实现与 espanso 完全兼容的 YAML 多文件读写，保留未知字段（round-trip safe）。

#### 2.1 新增 `YamlWorkspace.kt`

核心功能：

```kotlin
class YamlWorkspace(private val workspaceDir: File) {

    /** 递归读取工作区所有 YAML，返回运行时规则字典 */
    suspend fun loadAll(): Pair<Map<String, Match>, List<Var>>

    /** 读取单个 YAML 文件 → MatchGroup */
    suspend fun readFile(file: File): MatchGroup

    /** 将 MatchGroup 写回 YAML 文件（保留未知字段） */
    suspend fun writeFile(file: File, group: MatchGroup)

    /** 列出工作区所有 .yml/.yaml 文件 */
    fun listFiles(): List<File>

    /** 创建新规则文件 */
    suspend fun createFile(name: String): File

    /** 删除规则文件 */
    fun deleteFile(file: File)
}
```

关键实现细节（参考 espansogo `YamlWorkspace.cs`）：

- 使用 `kaml` 库解析，配置 `YamlConfiguration(strictMode = false)` 保留未知字段
- `imports` 递归处理：`HashSet<String>` 防循环，最大深度 10
- 文件监听：`FileObserver` 监听工作区变更，触发 `MatchStore` 热更新
- 编码：UTF-8，换行 LF

#### 2.2 `MatchStore.kt`（运行时规则仓库）

```kotlin
object MatchStore {
    val matchesFlow: StateFlow<Map<String, Match>>   // trigger → Match
    val globalVarsFlow: StateFlow<List<Var>>
    val groupsFlow: StateFlow<List<MatchGroup>>      // 用于文件管理 UI

    suspend fun reload()
    suspend fun addMatch(groupFile: File, match: Match)
    suspend fun updateMatch(groupFile: File, old: Match, new: Match)
    suspend fun deleteMatch(groupFile: File, match: Match)
    suspend fun updateGlobalVars(vars: List<Var>)
}
```

- `ExpansionService` 订阅 `matchesFlow`，配置变更时自动热更新规则，无需重启服务

#### 2.3 扩展 `SettingsStore.kt`

```kotlin
@Serializable
data class SettingsStore(
    // 现有字段...
    val enableExpansion: Boolean = true,            // 新增
    val expansionWorkspacePath: String = "",        // 新增：YAML 工作区路径（空=内置默认路径）
    val expansionDebounceMs: Long = 300,            // 新增：防抖延迟
    val expansionSeparators: String = " \n\t.,;:!?()[]{}\"'", // 新增：分隔符
)
```

---

### Phase 3 — 引擎增强（约 2 天）

**目标**：实现完整的变量求值链路，补齐 `propagate_case`，支持多触发词和细粒度单词边界。

#### 3.1 改造 `TriggerMatcher.kt`

改造要点（对照 espansogo `HandleTextExpansionAsync`）：

```kotlin
class TriggerMatcher {
    // 精确匹配字典：trigger → Match（扁平化，allTriggers 展开后各自入库）
    // 正则字典：Regex → Match

    /**
     * 从输入文本末尾向前匹配
     * 返回匹配结果，包含 matchedTrigger、startIndex、endIndex
     */
    suspend fun match(text: String): MatchResult?
}
```

单词边界逻辑（对照 espansogo 精确还原）：

```
left_word  = true → 触发词左侧必须是分隔符（或字符串起始）
right_word = true → 触发词右侧必须是分隔符（或字符串末尾）
word       = true → 等价于 left_word = true AND right_word = true
```

#### 3.2 新增 `VarEvaluator.kt`

支持的变量类型（Phase 1 实现，后续扩展）：

| 类型 | 实现方式 | 说明 |
|---|---|---|
| `echo` | 直接返回 `params.echo` | 最简单 |
| `date` | `java.time.LocalDateTime` + 格式转换 | strftime → DateTimeFormatter |
| `clipboard` | `ClipboardManager.primaryClip` | 需要权限检查 |
| `random` | `choices` 随机选一个 | |
| `choice` | 触发 `FormOverlayWindow` 显示选择列表 | 需要与表单系统联动 |
| `shell` | 通过 Shizuku `SafeInputManager` 执行 | Phase 2 实现 |
| `match` | 递归求值另一条 Match 的 replace | 需防循环 |

```kotlin
class VarEvaluator(
    private val globalVars: List<Var>,
    private val matchStore: MatchStore,
) {
    /** 求值 replace 字符串中的所有变量占位符 {{varName}} */
    suspend fun evaluate(replace: String, localVars: List<Var>): String

    /** 求值单个 Var */
    private suspend fun evaluateVar(v: Var): String?
}
```

变量占位符格式（与 espanso 一致）：`{{variable_name}}`

#### 3.3 改造 `TextReplacer.kt`

接入变量求值，完成未实现方法：

```kotlin
class TextReplacer(private val a11yService: A11yService) {

    suspend fun replace(
        node: AccessibilityNodeInfo,
        originalText: String,
        matchResult: MatchResult,
        varEvaluator: VarEvaluator,
    ): Boolean {
        // 1. 调用 varEvaluator.evaluate(match.replace, match.vars)
        // 2. 处理 propagate_case
        // 3. 处理 $|$ 光标标记
        // 4. 执行文本替换
    }

    /** 实现剪贴板辅助（Shizuku 可用时走 Shizuku，否则用 ClipboardManager） */
    suspend fun setClipboard(text: String): Boolean

    /** undo：记录上一次替换的原始文本，发送 ACTION_SET_TEXT 恢复 */
    suspend fun undo(node: AccessibilityNodeInfo): Boolean
}
```

#### 3.4 改造 `ExpansionService.kt`

```kotlin
class ExpansionService : A11yService() {

    // 订阅 MatchStore.matchesFlow，自动热更新
    // 使用 VarEvaluator 进行变量求值
    // 接入表单流程（form != null 时暂停替换，等待悬浮窗返回结果）

    private val triggerMatcher = TriggerMatcher()
    private val textReplacer by lazy { TextReplacer(this) }
    private val varEvaluator by lazy { VarEvaluator(MatchStore.globalVarsFlow.value, MatchStore) }
}
```

---

### Phase 4 — 表单支持（约 1.5 天）

**目标**：支持 espanso `form` 字段，通过悬浮窗收集用户输入后再完成替换。

#### 表单语法（espanso 标准）

```yaml
- trigger: ":greet"
  form: "Hello, [[name]]! You are [[age]] years old."
  form_fields:
    name:
      default: "World"
    age:
      type: list
      values: ["18", "25", "30"]
```

#### `FormOverlayWindow.kt` 实现要点

- 继承 / 复用现有 `OverlayWindowService`
- 解析 `form` 字符串中的 `[[field_name]]` 占位符
- 根据 `form_fields` 配置渲染输入控件：
  - `input`（默认）→ `TextField`
  - `multiline` → 多行 `TextField`
  - `list` → `DropdownMenu`
- 用户确认后，将字段值填入 `[[field_name]]` 位置，再执行文本替换
- 用户取消时，不执行任何替换

---

### Phase 5 — 规则管理 UI（约 2 天）

**目标**：在 epso 的 Navigation 3 + Compose 体系下构建完整规则管理界面。

#### 5.1 底部导航调整（`HomePage.kt`）

```kotlin
sealed class BottomNavItem {
    data object Control : BottomNavItem(0, "控制", PerfIcon.Home)
    data object Rules   : BottomNavItem(1, "规则", PerfIcon.Edit)    // 新增
    data object Files   : BottomNavItem(2, "文件", PerfIcon.Folder)  // 新增
    data object Settings: BottomNavItem(3, "设置", PerfIcon.Settings)
}
```

#### 5.2 `MatchListPage.kt`（规则列表）

- `LazyColumn` 展示所有规则（来自 `MatchStore.matchesFlow`）
- 顶部搜索框（按 trigger / label / replace 过滤）
- 每项显示：trigger、replace 预览、label、所属文件名
- 右滑删除（`SwipeToDismiss`）
- FAB 新建规则 → 跳转 `MatchEditorPage`

#### 5.3 `MatchEditorPage.kt`（规则编辑）

- 新建 / 编辑 Match
- 字段：trigger（可添加多个）、replace、label
- 高级选项折叠：`word` / `left_word` / `right_word`、`propagate_case`、`regex`
- 变量列表编辑器（`+` 添加 `Var`，每个 Var 可选类型和参数）
- 表单编辑：启用 form 时显示 form 模板编辑 + form_fields 配置
- 保存到选定的 YAML 文件

#### 5.4 `FilesPage.kt`（文件管理）

- 按 YAML 文件分组展示规则
- 创建 / 重命名 / 删除 YAML 文件
- 展开 / 折叠每个文件下的规则列表
- 支持规则在文件间移动

#### 5.5 `GlobalVarsPage.kt`（全局变量）

- 全局变量列表
- 增删改（与规则变量编辑器复用同一组件）

#### 5.6 改造 `ExpansionTestPage.kt`

- 实时输入框：在页面内直接输入文本，触发匹配预览
- 显示当前已加载规则数量
- 显示最近扩展历史（成功 / 失败 / 触发词 / 耗时）

---

### Phase 6 — 同步功能（约 2 天）

**目标**：支持将 YAML 工作区与外部存储同步，实现桌面 espanso 配置共享。

#### 支持的同步方式（按优先级）

| 方式 | 实现 | 说明 |
|---|---|---|
| **本地文件夹** | SAF + File | 与 Syncthing/本地 SMB 挂载配合 |
| **WebDAV** | Ktor Client | Nextcloud / 坚果云等 |
| **手动导入/导出** | File Picker | 最简单，始终支持 |

#### `SyncManager.kt` 接口

```kotlin
interface SyncManager {
    suspend fun push(): SyncResult
    suspend fun pull(): SyncResult
    suspend fun testConnection(): Boolean
}

data class SyncConfig(
    val method: SyncMethod = SyncMethod.None,
    val uri: String = "",
    val username: String = "",
    val password: String = "",
    val conflictStrategy: ConflictStrategy = ConflictStrategy.LastWriteWins,
    val wifiOnly: Boolean = false,
)

enum class SyncMethod { None, LocalFolder, WebDav, Manual }
enum class ConflictStrategy { LastWriteWins, KeepBoth }
```

#### `SyncSettingsPage.kt`

- 选择同步方式
- 配置参数（URI / 账号密码）
- 一键测试连接
- 手动推送 / 拉取
- 冲突策略选择

---

### Phase 7 — 包商店（可选，约 2 天）

**目标**：集成 espanso hub，一键安装社区规则包。

- `HubClient.kt`：从 GitHub 下载 `package_index.json`，SHA256 校验，解压到 packages 子目录
- `PackageStorePage.kt`：浏览包列表、安装 / 卸载、显示已安装包

> 此阶段与核心功能无依赖，可独立推进，也可暂缓。

---

## 四、文件变更总览

### 改造（修改现有文件）

| 文件 | 变更内容 |
|---|---|
| `expansion/Match.kt` | 补齐所有字段，拆分 `Var.kt` |
| `expansion/TriggerMatcher.kt` | 支持多触发词、细粒度单词边界 |
| `expansion/TextReplacer.kt` | 接入 `VarEvaluator`，实现 `undo`/`setClipboard` |
| `expansion/ExpansionService.kt` | 订阅 `MatchStore`，接入完整求值链路 |
| `expansion/ExpansionTestPage.kt` | 增强为完整测试页 |
| `store/SettingsStore.kt` | 新增扩展相关配置项 |
| `ui/home/HomePage.kt` | 增加 Rules / Files 底部导航项 |
| `ui/home/ControlPage.kt` | 展示扩展服务状态和规则数量 |
| `MainActivity.kt` | 注册新页面路由 |
| `gradle/libs.versions.toml` | 添加 kaml 依赖 |
| `app/build.gradle.kts` | 添加 kaml 依赖引用 |

### 新增文件

| 文件 | 说明 |
|---|---|
| `expansion/model/Var.kt` | Var / VarParams / FormField 数据类 |
| `expansion/model/MatchGroup.kt` | 单 YAML 文件对应的数据结构 |
| `expansion/store/YamlWorkspace.kt` | YAML 多文件读写 |
| `expansion/store/MatchStore.kt` | 运行时规则仓库 |
| `expansion/engine/VarEvaluator.kt` | 变量求值器 |
| `expansion/form/FormOverlayWindow.kt` | 表单悬浮窗 |
| `expansion/sync/SyncManager.kt` | 同步管理器接口 + 实现 |
| `expansion/sync/WebDavClient.kt` | WebDAV 客户端 |
| `expansion/ui/MatchListPage.kt` | 规则列表页 |
| `expansion/ui/MatchEditorPage.kt` | 规则编辑页 |
| `expansion/ui/FilesPage.kt` | 文件管理页 |
| `expansion/ui/GlobalVarsPage.kt` | 全局变量管理页 |
| `expansion/ui/SyncSettingsPage.kt` | 同步设置页 |

---

## 五、优先级与时间估算

| 阶段 | 内容 | 估算 | 依赖 |
|---|---|---|---|
| **Phase 0** | 依赖准备 | 0.5 天 | — |
| **Phase 1** | 数据模型重构 | 1 天 | Phase 0 |
| **Phase 2** | YAML 工作区 + MatchStore | 1.5 天 | Phase 1 |
| **Phase 3** | 引擎增强（变量求值 + 多触发词） | 2 天 | Phase 2 |
| **Phase 4** | 表单支持 | 1.5 天 | Phase 3 |
| **Phase 5** | 规则管理 UI | 2 天 | Phase 2（可与 Phase 3/4 并行） |
| **Phase 6** | 同步功能 | 2 天 | Phase 2 |
| **Phase 7** | 包商店 | 2 天 | Phase 2 |
| **合计（P0-P1）** | Phase 0–5 | **~8.5 天** | |

---

## 六、关键技术决策

### Q1：YAML 库选型

**选 `kaml`**，原因：
- 基于 `kotlinx.serialization`，与 epso 现有序列化体系（`@Serializable`）无缝集成
- 配置 `strictMode = false` 可保留未知字段，实现 round-trip safe
- 不依赖 Java 反射，适合 Android ProGuard/R8

### Q2：规则持久化方式

**选 YAML 多文件**（不用 Room），原因：
- 与 espanso 桌面端配置直接兼容，用户可通过文件管理器或 Syncthing 同步
- 无 schema 迁移负担
- 文件可读，便于手动编辑

### Q3：变量占位符格式

**保持 `{{variable_name}}`**（espanso 标准），确保导入的 espanso 配置无需转换直接可用。

### Q4：`shell` / `script` / `javascript` 变量类型

Phase 1 **不实现**，原因：
- `shell` 需要 Shizuku 授权，存在安全风险
- `javascript` 需要嵌入 JS 引擎，体积较大

Phase 3 可选实现 `shell`（通过 Shizuku `SafeInputManager`）。

### Q5：strftime 格式转换

espanso 的 `date` 变量使用 strftime 格式（如 `%Y-%m-%d`），Java 使用 `DateTimeFormatter`（如 `yyyy-MM-dd`）。
需要实现一个简单的 strftime → DateTimeFormatter 映射表，覆盖常见格式符。

---

## 七、验证检查点

每个 Phase 完成后需通过：

- [ ] `./gradlew :app:compileEpsoDebugKotlin` 编译通过（无警告）
- [ ] `./gradlew :app:lintEpsoDebug` Lint 通过
- Phase 2 额外：加载一个 espanso 标准 YAML 示例文件，验证 round-trip（读取后写回内容一致）
- Phase 3 额外：所有 5 种变量类型（echo/date/clipboard/random/choice）在 `ExpansionTestPage` 中手动验证
- Phase 5 额外：完整走通「新建规则 → 触发扩展 → 删除规则」流程
