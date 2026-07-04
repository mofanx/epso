# epso 技术栈

## 项目概况

**epso** 是一个基于 Android 无障碍服务的通用 APP 开发框架，包含两个子模块：

| 模块 | 类型 | 说明 |
|---|---|---|
| `:app` | Android Application | 主应用，UI + 服务层 |
| `:hidden_api` | Android Library | 隐藏 API 桥接（Shizuku） |

---

## 构建配置

- **构建工具**: Gradle (Kotlin DSL) + Version Catalog (`gradle/libs.versions.toml`)
- **AGP**: 9.2.1
- **Kotlin**: 2.4.0
- **JVM Target**: Java 11
- **compileSdk**: 37 / **minSdk**: 26 / **targetSdk**: 37
- **ABI**: arm64-v8a, x86_64
- **产品渠道**: `epso` (默认) / `play` (Google Play)

---

## 核心技术栈

### UI 层
- **Jetpack Compose** (1.11.2) — 声明式 UI 框架
- **Material 3** (1.4.0) — Material Design 组件
- **Navigation 3** (1.1.2) — `androidx.navigation3` 新一代导航
- **Coil 3** (3.5.0) — 图片加载（含 GIF、网络）
- **Telephoto** (0.19.0) — 可缩放图片
- **Accompanist DrawablePainter** (0.37.3) — Drawable 转 Painter

### 数据层
- **kotlinx.serialization** (1.11.0) — JSON 序列化
- **kotlinx-atomicfu** (0.33.0) — 原子操作

### 网络层
- **Ktor 3** (3.5.0) — 同时作为 HTTP Server (CIO引擎) 和 Client (OkHttp引擎)
  - 内嵌 HTTP 服务器供外部连接调试

### 系统能力
- **Shizuku** (13.1.5) — 免 Root 调用隐藏 API（AIDL: `IUserService`）
- **LSposed HiddenApiBypass** (6.1) — 隐藏 API 绕过
- **无障碍服务** — 核心功能基础
- **前台服务** — 截图、状态、HTTP 等通用 FGS
- **快速设置磁贴** — 通用 TileService
- **自定义 Scheme** — `epso://` 深度链接

### 工具库
- **exp4j** (0.4.8) — 表达式求值
- **Toaster** (15.0) — Toast 封装
- **XXPermissions** (28.2) — 权限请求
- **DeviceCompat** (2.6) — 设备信息
- **ActivityResultLauncher** (1.1.2) — Activity Result 封装
- **Reorderable** (3.1.0) — Compose 拖拽排序
- **Compose WebView** (0.33.6) — WebView 组件
- **SplashScreen** (1.2.0) — 启动屏

### 代码生成 / 编译插件
- **remap** (0.1.2) — 属性映射注解处理
- **loc** (0.7.1) — 日志定位注解
- **kotlin-parcelize** — Parcelable 生成
- **kotlin-compose** — Compose 编译器插件

### 测试
- JUnit 4 (4.13.2)
- AndroidX Test (JUnit 1.3.0, Espresso 3.7.0)
- Compose UI Test

---

## 架构特点

1. **无障碍服务框架** — 通过 `AccessibilityService` 提供通用无障碍服务基础设施
2. **内嵌 HTTP Server** — Ktor CIO 引擎，供外部连接调试
3. **Shizuku 集成** — 免 Root 执行高权限操作（如 `WRITE_SECURE_SETTINGS`）
4. **多渠道分发** — epso 渠道和 play 渠道
