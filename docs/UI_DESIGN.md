# epso UI 设计指南

> 本文档总结 epso 项目的 UI 架构、设计模式和组件体系，可用于指导新项目采用相同的框架和设计语言进行开发。

---

## 1. 设计语言总览

### 核心理念

- **Material 3 原生风格** — 不自定义颜色，使用 M3 默认 `lightColorScheme()` / `darkColorScheme()`，配合动态配色
- **卡片化布局** — 内容以 Card 为单元组织，使用 `surfaceContainer` 作为卡片背景色
- **极简间距体系** — 全局统一的 padding 常量，不随意使用魔法数字
- **无障碍优先** — 所有交互元素都带 `contentDescription` / `onClickLabel` / `stateDescription`
- **流畅动画** — 主题切换、页面转场、列表项、FAB 都有精心调校的动画

### 技术框架

| 技术 | 版本 | 用途 |
|---|---|---|
| Jetpack Compose | 1.11.2 | 声明式 UI |
| Material 3 | 1.4.0 | 设计组件 |
| Navigation 3 | 1.1.2 | `androidx.navigation3` 新一代导航 |
| Coil 3 | 3.5.0 | 图片加载 |
| Accompanist | 0.37.3 | Drawable → Painter |

---

## 2. 主题系统

### 2.1 配色策略

项目**不使用自定义颜色**，完全依赖 Material 3 默认配色 + 动态配色：

```kotlin
// Theme.kt 核心逻辑
private val LightColorScheme = lightColorScheme()  // M3 默认浅色
private val DarkColorScheme = darkColorScheme()    // M3 默认深色

val colorScheme = when {
    AndroidTarget.S && enableDynamicColor && darkTheme -> dynamicDarkColorScheme(app)
    AndroidTarget.S && enableDynamicColor && !darkTheme -> dynamicLightColorScheme(app)
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}
```

**设计要点**：
- **动态配色**（Android 12+）：从壁纸提取颜色，默认开启
- **深色模式**：跟随系统，用户可手动覆盖（`enableDarkTheme: Boolean?`，null = 跟随系统）
- **无自定义品牌色** — 依赖系统动态配色让 APP 自然融入用户设备

### 2.2 主题切换动画

切换深色/浅色模式时，**所有 ColorScheme 属性都做 500ms 颜色渐变动画**，避免突兀切换：

```kotlin
@Composable
private fun ColorScheme.animation(): ColorScheme {
    return copy(
        primary = primary.animation(),
        onPrimary = onPrimary.animation(),
        // ... 全部 30+ 颜色属性都做动画
        surfaceContainer = surfaceContainer.animation(),
        // ...
    )
}
```

### 2.3 状态栏联动

主题切换时同步更新状态栏图标颜色：

```kotlin
LaunchedEffect(darkTheme) {
    WindowInsetsControllerCompat(activity.window, ...).apply {
        isAppearanceLightStatusBars = !darkTheme  // 深色模式用白色图标
    }
}
```

### 2.4 CompositionLocal 注入

通过 `CompositionLocalProvider` 向子树注入全局状态：

```kotlin
CompositionLocalProvider(
    LocalDarkTheme provides darkTheme,
    LocalIsTalkbackEnabled provides isTalkbackEnabled
) {
    MaterialTheme(colorScheme = colorScheme.animation(), content = content)
}
```

---

## 3. 导航架构

### 3.1 Navigation 3 路由定义

每个页面用一个 `@Serializable data class/object : NavKey` 定义路由：

```kotlin
@Serializable
data object HomeRoute : NavKey

@Serializable
data class WebViewRoute(val initUrl: String) : NavKey
```

### 3.2 路由注册

在 `MainActivity` 中集中注册所有页面：

```kotlin
NavDisplay(
    backStack = mainVm.backStack,
    onBack = mainVm::popPage,
    entryProvider = entryProvider {
        entry<HomeRoute> { HomePage() }
        entry<WebViewRoute> { WebViewPage(it) }
        // ...
    },
    transitionSpec = {
        slideInHorizontally(initialOffsetX = { it }) togetherWith
        slideOutHorizontally(targetOffsetX = { -it })
    },
    popTransitionSpec = {
        slideInHorizontally(initialOffsetX = { -it }) togetherWith
        slideOutHorizontally(targetOffsetX = { it })
    },
)
```

### 3.3 BackStack 管理

导航栈由 ViewModel 管理，支持 push / pop / replace：

```kotlin
class MainViewModel : BaseViewModel() {
    val backStack: NavBackStack<NavKey> = NavBackStack(HomeRoute)
    val topRoute get() = backStack.last()

    fun navigatePage(navKey: NavKey, replaced: Boolean = false) {
        if (navKey != backStack.last()) {
            if (replaced) backStack[backStack.lastIndex] = navKey
            else backStack.add(navKey)
        }
    }

    fun popPage() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
```

### 3.4 页面转场动画

| 操作 | 动画 |
|---|---|
| push | 新页面从右侧滑入，旧页面向左滑出 |
| pop | 新页面从左侧滑入，旧页面向右滑出 |
| predictive pop | 同 pop，支持手势预测 |

---

## 4. 页面结构

### 4.1 底部导航 + 多 Tab 架构

首页使用 `NavigationBar` + 3 个 Tab，每个 Tab 页面通过 hook 函数返回 `ScaffoldExt`：

```kotlin
sealed class BottomNavItem(val key: Int, val label: String, val icon: ImageVector) {
    object Control : BottomNavItem(0, "首页", PerfIcon.Home)
    object AppList : BottomNavItem(1, "应用", PerfIcon.Apps)
    object Settings : BottomNavItem(2, "设置", PerfIcon.Settings)
}
```

### 4.2 ScaffoldExt 模式

用 data class 封装页面配置，实现声明式页面组装：

```kotlin
data class ScaffoldExt(
    val navItem: BottomNavItem,
    val modifier: Modifier = Modifier,
    val topBar: @Composable () -> Unit = { PerfTopAppBar(title = { Text(navItem.label) }) },
    val floatingActionButton: @Composable () -> Unit = {},
    val content: @Composable (PaddingValues) -> Unit
)

// 使用
@Composable
fun HomePage() {
    val pages = arrayOf(useControlPage(), useAppListPage(), useSettingsPage())
    val page = pages.find { p -> p.navItem.key == tab } ?: pages.first()
    Scaffold(
        topBar = page.topBar,
        bottomBar = { NavigationBar { ... } },
        content = page.content
    )
}
```

### 4.3 典型设置页结构

```
Scaffold
├── PerfTopAppBar (scrollBehavior)
└── Column (verticalScroll)
    ├── Text("常规")          ← titleSmall + primary 色，section 标题
    ├── TextSwitch(...)       ← 设置项
    ├── TextSwitch(...)
    ├── Text("外观")          ← section 标题
    ├── TextMenu(...)         ← 下拉选择项
    ├── TextSwitch(...)
    ├── Text("其他")          ← section 标题
    ├── SettingItem(...)      ← 导航项
    └── Spacer(EmptyHeight)   ← 底部留白
```

**Section 标题样式**：

```kotlin
Text(
    text = "外观",
    modifier = Modifier.titleItemPadding(),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
)
```

---

## 5. 间距与布局规范

### 5.1 全局常量

```kotlin
val itemHorizontalPadding = 16.dp   // 列表项左右间距
val itemVerticalPadding = 12.dp     // 列表项上下间距
val EmptyHeight = 80.dp             // 列表底部留白
val cardHorizontalPadding = 12.dp   // 卡片内间距
```

### 5.2 间距扩展函数

```kotlin
// 标准列表项 padding
fun Modifier.itemPadding() = this.padding(itemHorizontalPadding, itemVerticalPadding)

// Section 标题 padding（顶部多留空间，底部少留）
fun Modifier.titleItemPadding(showTop: Boolean = true) = this.padding(
    itemHorizontalPadding,
    if (showTop) itemVerticalPadding + itemVerticalPadding / 2 else 0.dp,
    itemHorizontalPadding,
    itemVerticalPadding - itemVerticalPadding / 2
)
```

### 5.3 Scaffold Padding 处理

```kotlin
fun Modifier.scaffoldPadding(values: PaddingValues): Modifier {
    return padding(top = values.calculateTopPadding())
    // bottom padding 交给 LazyXXX 处理，让底部导航栏实现透明背景
}
```

---

## 6. 核心组件库

### 6.1 图标系统 — `PerfIcon`

统一图标管理，自动生成 `contentDescription`，支持 Material Icons 和 Drawable 资源：

```kotlin
object PerfIcon {
    val Home get() = Icons.Outlined.Home
    val Settings get() = Icons.Outlined.Settings
    val Close get() = Icons.Default.Close
    // ... 40+ 图标常量
}

// 自动推断无障碍描述
fun getIconDefaultDesc(imageVector: ImageVector): String? = when (imageVector) {
    PerfIcon.Add -> "添加"
    PerfIcon.Edit -> "编辑"
    PerfIcon.Close -> "关闭"
    // ...
}
```

**设计原则**：图标集中在 `PerfIcon` object 中管理，不散落各处。优先使用 `Outlined` 风格。

### 6.2 图标按钮 — `PerfIconButton`

封装 `IconButton` + 自动 Tooltip + 无障碍标签：

```kotlin
@Composable
fun PerfIconButton(
    imageVector: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = getIconDefaultDesc(imageVector),
    onClickLabel: String? = null,  // 如 "进入设置页面"
) = TooltipIconButtonBox(contentDescription) {
    IconButton(onClick = onClick, ...) { PerfIcon(imageVector, ...) }
}
```

**Tooltip 智能降级**：TalkBack 开启时不显示 Tooltip（视障用户通过 TalkBack 朗读 contentDescription）。

### 6.3 开关 — `TextSwitch`

设置页最核心组件，标题 + 副标题 + Switch 的组合行：

```kotlin
@Composable
fun TextSwitch(
    title: String,
    subtitle: String? = null,       // 副标题（灰色）
    suffix: String? = null,         // 副标题后缀（primary 色，可点击）
    suffixIcon: (@Composable () -> Unit)? = null,  // 右侧额外图标
    checked: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClickLabel: String? = "切换${title}状态",
)
```

**视觉结构**：
```
[标题 (bodyLarge)]                    [Switch]
[副标题 (bodyMedium, onSurfaceVariant)] [后缀 (primary)]
```

### 6.4 下拉选择 — `TextMenu`

```kotlin
@Composable
fun <T> TextMenu(
    title: String,
    option: Option<T>,               // 当前选中项
    onOptionChange: ((Option<T>) -> Unit),
)
```

**视觉结构**：
```
[标题 (bodyLarge)]    [选中值 (bodyMedium)] [UnfoldMore 图标]
                       └─ DropdownMenu
```

### 6.5 导航项 — `SettingItem`

带箭头图标的可点击行，用于跳转子页面：

```kotlin
@Composable
fun SettingItem(
    title: String,
    subtitle: String? = null,
    imageVector: ImageVector? = PerfIcon.KeyboardArrowRight,  // 右箭头
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,  // 如 "进入高级设置页面"
)
```

### 6.6 顶部栏 — `PerfTopAppBar`

封装 M3 `TopAppBar`，解决主题切换动画冲突问题：

```kotlin
@Composable
fun PerfTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    canScroll: Boolean = true,  // false 时禁用折叠效果但保持 pinned
)
```

**关键修复**：用 `key(MaterialTheme.colorScheme.surface)` 包裹 TopAppBar，避免主题色切换时容器颜色动画与全局颜色动画叠加冲突。

### 6.7 全屏对话框 — `ScaffoldDialog` / `FullscreenDialog`

```kotlin
@Composable
fun ScaffoldDialog(
    title: String,
    onClose: () -> Unit,
    content: @Composable (ColumnScope.() -> Unit)
) = FullscreenDialog(onDismissRequest = onClose) {
    Scaffold(
        topBar = { PerfTopAppBar(title = { Text(title) }, actions = { PerfIconButton(Close, onClose) }) },
        content = { Column(verticalScroll(rememberScrollState()), padding(it)) { content() } }
    )
}
```

**设计要点**：
- 使用全屏 Dialog 而非 AlertDialog，适合复杂表单
- 透明背景 + 无遮罩（`setDimAmount(0f)`），视觉上覆盖原页面
- 状态栏图标颜色同步深色主题

### 6.8 卡片组件

**图标文字卡片**（首页常用）：

```kotlin
// 图标在圆形背景中 + 文字内容
@Composable
fun IconTextCard(imageVector: ImageVector, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PerfIcon(
            imageVector = imageVector,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(8.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(itemHorizontalPadding))
        content()
    }
}
```

**卡片颜色**：

```kotlin
val surfaceCardColors: CardColors
    @Composable get() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
```

### 6.9 空状态 — `EmptyText`

```kotlin
@Composable
fun EmptyText(text: String = "暂无数据") {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
}
```

### 6.10 动画 FAB — `AnimationFloatingActionButton`

```kotlin
@Composable
fun AnimationFloatingActionButton(
    visible: Boolean,    // 显隐控制
    onClick: () -> Unit,
    imageVector: ImageVector,
)
```

**动画细节**：
- 显示：先淡入 + 从右侧滑入，再升起 elevation
- 隐藏：先降下 elevation，再淡出 + 向右滑出
- 两段式动画，总时长 ~300ms

### 6.11 对话框管理 — `DialogOptions`

通过 `MutableStateFlow<AlertDialogOptions?>` 集中管理弹窗：

```kotlin
// 显示简单提示
dialogFlow.updateDialogOptions(title = "限制说明", text = "...")

// 等待用户确认（suspend）
val confirmed = dialogFlow.getResult(title = "使用须知", text = "...", confirmText = "继续")
if (!confirmed) stopCoroutine()
```

---

## 7. 滚动行为

### 7.1 滚动 Hook

项目封装了三个滚动状态 Hook，统一管理 TopAppBar 折叠 + 列表滚动状态：

```kotlin
// enterAlways 折叠 + LazyListState
fun useListScrollState(v1: Any?, v2: Any? = null, v3: Any? = null): Pair<TopAppBarScrollBehavior, LazyListState>

// pinned（不折叠）+ LazyListState
fun usePinnedScrollBehaviorState(v1: Any?): Pair<TopAppBarScrollBehavior, LazyListState>

// enterAlways 折叠 + ScrollState（非 Lazy 列表）
fun useScrollBehaviorState(v1: Any?): Pair<TopAppBarScrollBehavior, ScrollState>
```

**关键设计**：传入 `v1/v2/v3` 作为 `rememberSaveable` 的 key，数据变化时重置滚动位置。

### 7.2 列表项动画

```kotlin
context(scope: LazyItemScope)
fun Modifier.animateListItem(enabled: Boolean = true): Modifier {
    return scope.run {
        animateItem(
            fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
            placementSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
            fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow)
        )
    }
}
```

---

## 8. 交互防护

### 8.1 节流（Throttle）

所有交互回调都经过 `throttle` 包装，防止快速重复点击：

```kotlin
// 开关
PerfSwitch(onCheckedChange = onCheckedChange?.let { throttle(it) })

// 点击
Modifier.clickable(onClick = throttle(fn = onClick))

// FAB
FloatingActionButton(onClick = throttle(onClick))
```

### 8.2 无障碍语义

每个交互元素都配置完整的语义信息：

```kotlin
// 点击标签
Modifier.semantics { this.onClick(label = "进入高级设置页面", action = null) }

// 状态描述
Modifier.semantics { this.stateDescription = if (checked) "已开启" else "已关闭" }

// 合并后代语义（卡片整体可点击时）
Modifier.semantics(mergeDescendants = true) { ... }
```

---

## 9. Edge-to-Edge 适配

```kotlin
// Activity 启用 edge-to-edge
enableEdgeToEdge()

// 自定义 WindowInsets 处理
var topBarWindowInsets by mutableStateOf(WindowInsets(top = BarUtils.getStatusBarHeight()))

// 监测实际 insets 并更新
if (latestInsets.getTop(density) > topBarWindowInsets.getTop(density)) {
    topBarWindowInsets = FixedWindowInsets(latestInsets)
}

// TopAppBar 使用自定义 insets
PerfTopAppBar(windowInsets = activity.topBarWindowInsets)
```

---

## 10. 代码组织结构

```
app/src/main/kotlin/li/mofanx/epso/
├── MainActivity.kt              # 入口，导航注册，全局 Dialog
├── MainViewModel.kt             # 导航栈管理，全局状态
├── App.kt                       # 应用初始化
├── a11y/                        # 无障碍服务状态管理
├── data/                        # 通用数据模型（AppInfo、CrashData 等）
├── expansion/                   # 文本扩展核心引擎
│   ├── Match.kt / Var.kt / MatchGroup.kt          # 规则数据模型
│   ├── MatchStore.kt / YamlWorkspace.kt           # 运行时仓库 / YAML 文件读写
│   ├── TriggerMatcher.kt / VarEvaluator.kt        # 触发匹配 / 变量求值
│   ├── TextReplacer.kt / ExpansionService.kt      # 文本替换 / 扩展服务
│   ├── FormOverlayWindow.kt / ChoiceOverlayWindow.kt / SearchOverlayWindow.kt
│   ├── sync/                    # WebDAV / 本地文件夹同步
│   ├── hub/                     # 包商店客户端
│   └── ...
├── notif/                       # 通知与通知渠道
├── permission/                  # 权限框架
├── service/                     # 后台服务（无障碍、HTTP、截图、状态、Tile）
├── shizuku/                     # Shizuku 隐藏 API 桥接
├── store/                       # 数据存储（SettingsStore、storeFlow）
├── ui/
│   ├── style/                   # 设计系统（Theme、Padding、Color、TextTransformation）
│   ├── component/               # 通用组件库（40+ 组件）
│   ├── common/                  # 通用 UI 能力（adaptive、feedback、form、state）
│   ├── home/                    # 首页 Tab 页面
│   │   ├── HomePage.kt          # 底部导航容器
│   │   ├── ControlPage.kt       # 控制首页
│   │   ├── SettingsPage.kt      # 设置 Tab
│   │   ├── HomeComponents.kt    # 首页共享组件
│   │   └── ScaffoldExt.kt       # 页面配置封装
│   ├── expansion/               # 扩展规则管理页面
│   │   ├── MatchListPage.kt
│   │   ├── MatchEditorPage.kt
│   │   ├── FilesPage.kt
│   │   ├── GlobalVarsPage.kt
│   │   ├── SyncSettingsPage.kt
│   │   ├── PackageStorePage.kt
│   │   └── YamlEditorPage.kt
│   ├── icon/                    # 自定义图标
│   ├── share/                   # CompositionLocal
│   ├── *Page.kt / *Vm.kt        # 其他子页面及对应 ViewModel
│   └── *Route.kt                # 路由定义（通常与 Page 同文件）
└── util/                        # 通用工具函数
```

---

## 11. 新项目快速复用清单

### 必须复用

- [ ] `Theme.kt` — 主题系统（M3 默认配色 + 动态配色 + 切换动画）
- [ ] `Padding.kt` — 间距常量体系
- [ ] `PerfIcon.kt` — 统一图标管理
- [ ] `PerfTopAppBar.kt` — 顶部栏（含动画冲突修复）
- [ ] `TextSwitch.kt` / `TextMenu.kt` / `SettingItem.kt` — 设置页三件套
- [ ] `ScaffoldDialog.kt` + `FullscreenDialog.kt` — 全屏对话框
- [ ] `Hooks.kt` — 滚动行为 Hook
- [ ] `LocalExt.kt` — CompositionLocal 注入
- [ ] Navigation 3 路由模式（`@Serializable data class : NavKey`）

### 推荐复用

- [ ] `DialogOptions.kt` — 集中弹窗管理
- [ ] `AnimationFloatingActionButton.kt` — 动画 FAB
- [ ] `EmptyText.kt` — 空状态
- [ ] `Animation.kt` — 列表项动画
- [ ] `TooltipIconButtonBox.kt` — Tooltip + TalkBack 降级
- [ ] `throttle` 交互防护模式
- [ ] `ScaffoldExt` 页面配置模式
- [ ] Edge-to-Edge 适配方案

### 可选复用

- [ ] `Color.kt` — JSON5 语法高亮（仅代码编辑场景需要）
- [ ] `TextTransformation.kt` — 输入可视化转换
- [ ] `AppIcon.kt` — 应用图标加载（仅应用管理类 APP 需要）

---

## 12. 设计决策总结

| 决策 | 选择 | 理由 |
|---|---|---|
| 配色 | M3 默认 + 动态配色 | 无需维护品牌色，自然融入用户设备 |
| 导航 | Navigation 3 | 新一代类型安全路由，比 Navigation Compose 更简洁 |
| 页面转场 | 水平滑动 | 符合 Android 原生体验 |
| 设置页布局 | Column + Section 标题 | 简单直接，无需复杂列表 |
| 对话框 | 全屏 Dialog（复杂表单）+ AlertDialog（简单确认） | 分场景选择 |
| 图标风格 | Material Icons Outlined 优先 | 轻量现代风格 |
| 间距 | 固定常量（16dp / 12dp） | 全局一致，避免随意调整 |
| 动画 | tween 500ms（主题）+ spring（列表） | 不同场景用不同动画策略 |
| 无障碍 | 全量语义标注 | TalkBack 友好，Tooltip 智能降级 |
| 交互防护 | 全局 throttle | 防止重复点击 |
