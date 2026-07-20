# epso 产品功能描述

> 本文档从产品和用户视角梳理 epso 当前已实现的功能，用于快速了解项目现状、对外介绍或规划后续迭代。

---

## 一、产品定位

**epso** 是一款运行在 Android 上的文本扩展（Text Expansion）应用，兼容 [espanso](https://espanso.org) YAML 配置格式。用户可以通过定义触发词（trigger）和替换文本（replace），在任何输入框中快速插入预设内容；同时支持变量、表单、正则触发、应用过滤、云端同步和社区包商店。

---

## 二、核心功能概览

| 功能域 | 主要能力 | 状态 |
|---|---|---|
| 触发匹配 | 普通触发词、多触发词、正则触发、单词边界、触发前缀、搜索触发词 | 已实现 |
| 替换输出 | 纯文本、markdown、html、图片、表单、剪贴板输出 | 已实现 |
| 变量求值 | echo / date / clipboard / random / choice / shell / script / http / match / form | 已实现 |
| 规则管理 | 列表、搜索、新建、编辑、删除、YAML 编辑、文件分组 | 已实现 |
| 文件管理 | 多 YAML 文件、递归子目录、imports、导入/导出/分享 ZIP | 已实现 |
| 全局变量 | 跨规则复用的全局变量 | 已实现 |
| 同步 | WebDAV、本地文件夹（SAF）双向同步、冲突策略、自动推送 | 已实现 |
| 包商店 | espanso Hub 包索引、下载、校验 SHA256、安装/卸载 | 已实现 |
| 快速搜索 | 搜索触发词弹出悬浮窗选择规则并替换 | 已实现 |
| HTTP API | 局域网 REST API，支持规则 CRUD、文件管理、扩展测试 | 已实现 |
| 无障碍服务 | 文本变化监听、自动替换、授权引导、故障修复 | 已实现 |
| 权限与设置 | 无障碍、Shizuku、前台服务、通知、磁贴、深色/动态主题 | 已实现 |

---

## 三、功能模块详解

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
| `http` | 发送 HTTP 请求并取回响应文本，支持 JSON 路径提取 |
| `match` | 递归触发另一条规则的 `replace`（最大深度 5，防循环） |
| `form` | 表单变量，用于复杂表单场景 |

变量支持 `depends_on` 依赖拓扑排序和 `inject_vars` 环境变量注入。

> **当前限制**：`javascript` 类型变量未实现（字段已预留，求值器会跳过）。
> `http` 变量已实现，支持 `GET/POST/PUT/DELETE/PATCH`、自定义 Headers、请求体和 `json_path`。

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
- **包商店页（Package Store）**：浏览 espanso Hub 包、按 tag 筛选、安装/卸载
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

### 3.9 设置与个性化

- 深色模式 / 动态配色（Android 12+）
- 默认触发前缀、快速搜索触发词
- HTTP 服务端口、自动启动、API Token
- Shizuku、通知、排除最近任务
- 同步配置

---

## 四、产品完整度评分

> 评分基于当前代码实现、UI 覆盖和用户体验，满分 10 分。

| 维度 | 得分 | 说明 |
|---|---|---|
| 核心扩展能力 | 9.5/10 | 触发匹配、变量、表单、输出格式非常完整，`http` 变量补齐后更接近 espanso 桌面版 |
| 规则管理 | 9/10 | 规则 CRUD、搜索、文件分组、YAML 原始编辑都已具备 |
| 同步与生态 | 7.5/10 | WebDAV + 本地文件夹 + 包商店已通，包商店增加浏览器手动下载入口 |
| 自动化 / 集成 | 8.5/10 | HTTP API、磁贴、Shizuku、shell/script/http 变量支持良好 |
| 稳定性与体验 | 7.5/10 | 首页验证步骤接入真实扩展成功事件，包商店错误提示更友好 |
| 测试覆盖 | 6.5/10 | 新增 `HttpVarRunnerTest` 等测试覆盖 http 变量，核心引擎测试较全，但 UI 和集成测试仍偏少 |
| 文档与上手 | 8/10 | 新增 `QUICKSTART.md` 和 `docs/examples/` 示例规则，上手门槛明显降低 |

### 总体评分：8.0 / 10

**结论**：epso 已经从"框架"进化为一款**功能完整度较高的 Android 文本扩展产品**，核心引擎和主要用户场景都已跑通。当前状态适合早期用户和开发者使用，但要成为稳定的大众产品，还需要在测试覆盖、用户文档、包商店体验和权限稳定性上继续投入。

---

## 五、主要优势与待完善项

### 优势

- 兼容 espanso 丰富生态，可直接复用大量社区规则
- 支持复杂变量、表单、正则、图片/富文本输出
- WebDAV + 本地文件夹 + 包商店，形成完整数据闭环
- 局域网 HTTP API 方便批量管理和自动化
- 代码结构清晰，Navigation 3 + Compose 现代化

### 待完善项

1. **缺失变量类型**：`javascript`（JS 脚本变量）尚未实现
2. **测试覆盖**：增加 UI 测试、集成测试、模拟无障碍事件测试
3. **包商店体验**：国内 GitHub 访问不稳定，可考虑镜像源或更完整的离线导入流程
4. **权限恢复**：Android 重启后无障碍服务状态、Shizuku 授权恢复流程可更稳定
5. **性能与耗电**：无障碍服务长时间运行、频繁同步的电量消耗需持续优化
