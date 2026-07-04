# epso

基于 Android 无障碍服务的通用 APP 开发框架，集成 Shizuku、HTTP Server、Compose UI 组件库等基础设施。

## 核心能力

- **无障碍服务** — 通用 AccessibilityService 框架
- **Shizuku 集成** — 免 Root 调用隐藏 API
- **HTTP Server** — 内嵌 Ktor CIO 服务器
- **Compose UI 组件库** — Material 3 + Navigation 3
- **快速设置磁贴** — 通用 TileService
- **自定义 Scheme** — `epso://` 深度链接

## 技术栈

- Kotlin 2.4.0 + Jetpack Compose 1.11.2
- Material 3 + Navigation 3
- Shizuku + LSposed HiddenApiBypass
- Ktor 3 (Server + Client)

详见 [TECH_STACK.md](docs/TECH_STACK.md) 和 [UI_DESIGN.md](docs/UI_DESIGN.md)

## 免责声明

**本项目遵循 [GPL-3.0](/LICENSE) 开源，项目仅供学习交流，禁止用于商业或非法用途**
