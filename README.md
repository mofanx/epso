# epso

基于 Android 无障碍服务的文本扩展应用，兼容 espanso YAML 配置格式。集成 Shizuku、HTTP Server、Compose UI 组件库等基础设施。

## 核心能力

- **文本扩展** — 通过无障碍服务监听输入，实现触发词 → 文本替换
- **YAML 规则管理** — 兼容 espanso 格式，支持本地文件、WebDAV / 本地文件夹同步
- **变量求值** — 支持 `echo` / `date` / `clipboard` / `random` / `choice` / `shell` / `script` / `form` 等变量
- **包商店** — 浏览和安装社区规则包
- **HTTP Server** — 内嵌 Ktor CIO 服务器，提供 REST API 与调试端点
- **Shizuku 集成** — 免 Root 调用隐藏 API
- **Compose UI 组件库** — Material 3 + Navigation 3

## 技术栈

- Kotlin 2.4.0 + Jetpack Compose 1.11.2
- Material 3 + Navigation 3
- Shizuku + LSposed HiddenApiBypass
- Ktor 3 (Server + Client)

详见 [TECH_STACK.md](docs/TECH_STACK.md) 和 [UI_DESIGN.md](docs/UI_DESIGN.md)

## 免责声明

**本项目遵循 [GPL-3.0](/LICENSE) 开源，项目仅供学习交流，禁止用于商业或非法用途**
