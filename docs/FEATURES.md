# epso 产品功能清单与完整度评估

> 本文档从资深产品视角重新梳理 epso 当前已实现的功能，输出功能全景、成熟度判断和完整度评分，用于内部复盘、对外介绍或规划下一阶段的投入。

---

## 一、产品定位

**epso** 是一款运行在 Android 上的文本扩展（Text Expansion）应用，兼容 [espanso](https://espanso.org) YAML 配置格式。目标用户是高频输入、依赖模板化表达的效率用户，以及希望把桌面端 espanso 规则迁移到 Android 的用户。

核心价值：在任意输入框中，通过触发词快速插入预设文本、执行变量求值、弹出表单、调用脚本或网络请求，从而减少重复输入。

---

## 二、功能全景图

| 功能域 | 关键能力 | 完成度 |
|---|---|---|
| 触发与匹配 | 普通/多触发词、正则触发、触发前缀、搜索触发、单词边界、大小写传播、撤销 | 高 |
| 替换输出 | 纯文本、Markdown、HTML、图片路径、表单、剪贴板、光标定位、前置延迟 | 高 |
| 变量系统 | echo / date / clipboard / random / choice / shell / script / javascript / http / match / form | 高 |
| 规则管理 | 列表、搜索、新建/编辑/删除、YAML 原始编辑、文件分组、导入/导出 ZIP | 高 |
| 全局变量 | 跨文件 `global_vars`，局部变量可覆盖同名全局变量 | 高 |
| 同步 | WebDAV、本地文件夹（SAF）双向同步、冲突策略、自动推送、Wi-Fi 限制 | 高 |
| 包商店 | espanso Hub 包索引、多镜像回退、本地 zip 导入、SHA256 校验、安装/卸载 | 高 |
| 快速搜索 | 搜索触发词弹出悬浮窗，实时过滤规则并替换 | 高 |
| HTTP API | 局域网 REST API，规则 CRUD、文件管理、扩展测试、Token 鉴权 | 高 |
| 无障碍与权限 | 文本监听、自动替换、Shizuku、前台服务、通知、快速设置磁贴、开机恢复、电池优化引导 | 高 |
| 设置与个性化 | 深色/动态配色、默认触发前缀、HTTP 端口、API Token、包商店索引源/代理、应用图标 | 高 |

---

## 三、核心模块详解

### 3.1 文本扩展引擎

- **触发方式**
  - 普通触发词与 `triggers` 多触发词
  - `regex` 正则触发
  - `prefix` 触发前缀（默认 `:`，可全局配置或按规则覆盖）
  - `search_terms` 搜索触发词（默认 `:s`），弹出悬浮搜索窗
  - `word` / `left_word` / `right_word` 细粒度单词边界控制

- **替换能力**
  - `replace` 纯文本替换，支持 `{{var}}` 变量占位符和 `$|$` 光标定位
  - `markdown` 输出，内置轻量 Markdown 转 Spannable
  - `html` 输出，通过 `HtmlCompat` 解析为富文本
  - `image_path` 输出，通过 FileProvider + 剪贴板粘贴图片
  - `form` / `form_fields` 表单弹窗，收集用户输入后回填
  - `propagate_case` 大小写传播，支持 `uppercase` / `capitalize` / `capitalize_words`
  - `force_clipboard` / `clipboard_threshold` 根据长度自动选择输入或剪贴板
  - `pre_paste_delay` 粘贴前延迟
  - `undo_backspace` 退格撤销上一次替换

- **应用级过滤**
  - `filter_title` / `filter_exec` / `filter_class` / `filter_os` / `enable` 控制规则在哪些应用或系统生效

### 3.2 变量系统

变量在 `replace` 中以 `{{name}}` 占位，局部变量覆盖同名全局变量。

| 变量类型 | 说明 |
|---|---|
| `echo` | 固定文本 |
| `date` | 当前日期时间，支持 `strftime` 格式、时区、偏移秒数 |
| `clipboard` | 读取系统剪贴板文本 |
| `random` | 从 `choices` 随机选择，或按 `min` / `max` 生成随机数 |
| `choice` | 弹出选择列表供用户选择 |
| `shell` | 执行 shell 命令（`/system/bin/sh`，支持 `bash` / `zsh` 等降级） |
| `script` | 执行外部脚本（依赖设备已安装的解释器，如 Termux） |
| `javascript` | 使用 Rhino 引擎执行 JS 代码，返回最后一行表达式结果 |
| `http` | 发送 HTTP 请求并取回响应文本，支持 JSON 路径提取 |
| `match` | 递归触发另一条规则的 `replace`（最大深度 5，防循环） |
| `form` | 表单变量，用于复杂表单场景 |

变量支持 `depends_on` 依赖拓扑排序和 `inject_vars` 环境变量注入。

### 3.3 YAML 工作区

- 工作区路径：`/Android/data/li.mofanx.epso/files/matches/`（默认可覆盖）
- 递归扫描 `.yml` / `.yaml` 文件
- 支持 `imports` 递归引用（最大深度 10，防循环）
- `global_vars` 全局变量跨文件生效
- 保留 YAML anchors/aliases
- 文件名以下划线 `_` 开头的为私有文件，不直接加载
- 保存时 `kaml` 严格模式关闭，保留 espanso 未知字段，实现 round-trip safe

### 3.4 规则管理 UI

- **首页控制页**：服务状态、规则数量、快速入口、授权引导
- **规则列表页（Rules）**：搜索、按文件筛选、滑动删除、新建/编辑规则
- **规则编辑页**：基础/高级标签页、触发词/正则切换、变量编辑、表单字段配置
- **文件管理页（Files）**：创建/重命名/删除 YAML 文件、导入/导出 ZIP、进入同步设置、YAML 原始编辑
- **全局变量页（Global variables）**：按文件分组管理全局变量
- **包商店页（Package Store）**：浏览 espanso Hub 包、按 tag 筛选、多镜像回退、本地 zip 导入、安装/卸载
- **同步设置页（Sync）**：WebDAV / 本地文件夹、冲突策略、连接测试、手动/自动同步
- **快速搜索悬浮窗**：输入搜索触发词后弹出，实时过滤规则并替换

### 3.5 同步

- **WebDAV**：Ktor OkHttp 客户端，支持 Basic 认证、双向 sync / push / pull
- **本地文件夹**：SAF `DocumentFile` 授权，双向同步
- **幂等同步**：基于持久化 SyncState，支持删除传播、递归子目录、全文件类型
- **冲突策略**：`LastWriteWins` / `KeepBoth`（生成 `.conflict` 文件）
- **自动推送**：保存 YAML 后自动 push
- **Wi-Fi 限制**：可仅在 Wi-Fi 下自动同步

### 3.6 包商店

- 从 `espanso/hub` GitHub Releases 拉取 `package_index.json`
- 支持自定义索引源（设置 → 包商店索引源）
- 内置多个镜像回退（ghproxy / mirror / raw.githubusercontent）
- 下载 zip 时同样支持代理回退
- 支持直接从本地 `.zip` 文件导入包
- 本地缓存索引（24 小时有效）
- 下载 zip 后校验 SHA256
- 解压到 `packages/{name}/package.yml`
- 中文错误提示（超时、DNS、证书、404 等）

### 3.7 HTTP API

默认端口 `8888`，可通过设置修改。主要端点：

- `GET /api/v1/status`：服务状态、规则数、文件数
- `GET /api/v1/rules` / `POST /api/v1/rules` / `PUT /api/v1/rules` / `DELETE /api/v1/rules`：规则 CRUD
- `POST /api/v1/expand`：模拟扩展测试
- `POST /api/v1/reload`：重新加载工作区
- `GET/PUT/DELETE /api/v1/files/{path...}`：YAML 文件读写
- 非本机访问需 token（`Authorization: Bearer <token>`）

### 3.8 无障碍服务与权限

- **基础授权**：引导用户开启无障碍服务
- **增强授权**：通过 Shizuku 或 adb 命令授予 `WRITE_SECURE_SETTINGS`，自动开启/关闭无障碍
- **前台服务**：状态通知、HTTP 服务、截图服务
- **悬浮窗**：`SYSTEM_ALERT_WINDOW` 用于表单/选择/搜索悬浮窗
- **快速设置磁贴**：应用开关、HTTP 服务开关
- **开机恢复**：`BootReceiver` 在设备重启后尝试恢复状态服务通知
- **电池优化**：设置页提供跳转，引导用户将 epso 加入白名单

### 3.9 设置与个性化

- 深色模式 / 动态配色（Android 12+）
- 默认触发前缀、快速搜索触发词
- HTTP 服务端口、自动启动、API Token
- Shizuku、通知、排除最近任务
- 同步配置
- 包商店索引源、代理镜像开关
- 电池优化白名单引导
- 应用图标：已更新为 `:>` 暗色风格图标

---

## 四、产品完整度评估（资深产品视角）

> 评估基于当前代码实现、UI 覆盖、用户体验和可维护性，满分 10 分。

### 4.1 评分维度

| 维度 | 权重 | 得分 | 说明 |
|---|---|---|---|
| 核心扩展能力 | 25% | 9.5 | 触发、替换、变量、表单完整；`javascript` 变量补齐后已对齐 espanso 桌面版 |
| 规则与文件管理 | 20% | 9.5 | 规则 CRUD、搜索、文件分组、YAML 原始编辑、导入导出均已具备 |
| 同步与生态 | 15% | 9.5 | WebDAV + 本地文件夹 + 包商店已通；支持自定义源、镜像回退、本地 zip 导入 |
| 自动化与集成 | 15% | 9.5 | HTTP API、磁贴、Shizuku、shell/script/javascript/http 变量支持良好 |
| 稳定性与体验 | 15% | 9.5 | 开机恢复、电池优化引导、包商店错误提示、首页验证链路已完善 |
| 测试与质量 | 5% | 9.0 | 核心引擎、JS 变量、同步等关键路径已有单元测试；UI/集成测试随版本继续补充 |
| 文档与上手 | 5% | 9.5 | `QUICKSTART.md`、`FAQ.md`、`TROUBLESHOOTING.md` 和示例规则形成较完整上手体系 |

### 4.2 综合评分

**9.5 / 10**

### 4.3 产品阶段判断

epso 已从“技术原型 / 可用工具”进入**“产品化早期”**阶段：核心链路、生态接入、权限体验和文档都趋于完整，适合效率用户、espanso 迁移用户和早期大众用户试用。

剩余投入方向主要是 UI/集成测试、更精细的权限自动恢复、以及社区生态的持续运营。

---

## 五、关键优势

- **兼容 espanso 生态**：可直接复用大量社区规则，降低迁移成本
- **变量与输出能力丰富**：支持复杂变量、表单、正则、图片/富文本输出、JS 脚本
- **数据闭环**：WebDAV + 本地文件夹 + 包商店 + 本地 zip 导入，满足多场景同步需求
- **自动化友好**：局域网 HTTP API 方便批量管理和第三方集成
- **权限与稳定性**：Shizuku、开机恢复、电池优化引导、错误提示都已覆盖
- **架构现代**：Navigation 3 + Compose + Material 3，代码结构清晰

---

## 六、下一步优先级

1. **UI / 集成测试**：补充 Compose UI 测试和端到端同步测试
2. **权限恢复自动化**：在系统允许范围内进一步简化重启后无障碍恢复流程
3. **JS 高级能力**：探索 JS 与 Kotlin 互操作（如调用系统 API、异步请求）
4. **包商店生态**：增加镜像源配置模板、包更新检测
5. **性能与耗电**：持续优化无障碍服务长时间运行、频繁同步的电量消耗
