package li.mofanx.epso.service

internal val API_DOCS = """
# Epso HTTP API 文档

## 基础信息
- 默认地址：`http://<设备IP>:8888`
- 端口设置：`SettingsStore.httpServerPort`（默认 8888）
- 自动启动：`SettingsStore.httpServerAutoStart`（默认 true），启动时会检查通知和前台服务权限
- 服务启动后访问 `GET /` 会返回 "Epso LAN API"
- 文档端点：`GET /api/v1/docs`（返回本文档）

## 认证
- 非本机请求需要在 Header 中携带 token：
  - `Authorization: Bearer <httpApiToken>`
  - 或 `X-Epso-Token: <httpApiToken>`
- `httpApiToken` 存储在设置中，首次启动会自动生成
- 来自 `127.0.0.1`、`::1` 或 `localhost` 的请求（ADB reverse/forward）免 token

## 通用响应
- 成功：`{ "ok": true }`（ApiResult）
- 失败：`{ "message": "...", "unknown": false }`（RpcError，HTTP 400/401/404）

## 端点

### GET /api/v1/status
服务状态。

响应示例：
```json
{
  "server": { "device": {...}, "appInfo": {...} },
  "expansionEnabled": true,
  "ruleCount": 10,
  "fileCount": 3
}
```

### GET /api/v1/rules
列出所有规则（扁平化）。返回 `ApiRule` 数组。

```json
[
  {
    "file": "base.yml",
    "index": 0,
    "triggers": [":hello"],
    "replace": "world",
    "regex": "",
    "label": "示例"
  }
]
```

### POST /api/v1/rules
向指定 YAML 文件新增一条规则。

请求体：
```json
{
  "file": "base",
  "rule": {
    "trigger": "hi",
    "replace": "hello"
  }
}
```

- `file` 为相对路径，不含扩展名
- 若目标文件不存在会自动创建
- `rule` 中的 `index` 字段不需要填写

### PUT /api/v1/rules
按索引修改指定 YAML 文件中的单条规则。

请求体：
```json
{
  "file": "base",
  "index": 0,
  "rule": {
    "trigger": "hi",
    "replace": "hello, world"
  }
}
```

- `file` 为相对路径，不含扩展名
- `index` 为该规则在 `GET /api/v1/rules` 中返回的 `index`（即文件内的顺序）
- 若 `index` 越界或 `rule` 无效，返回 400

### DELETE /api/v1/rules
按索引删除指定 YAML 文件中的单条规则。

请求体：
```json
{
  "file": "base",
  "index": 0
}
```

- `file` 为相对路径，不含扩展名
- 若 `index` 越界，返回 400

### POST /api/v1/reload
重新从磁盘加载规则仓库。

### POST /api/v1/expansion
开启或关闭扩展功能。

请求体：`{ "enabled": true }`

### POST /api/v1/expand
模拟一次文本扩展，返回匹配到的触发词和展开后的文本，不执行真实的 Accessibility 替换。

请求体：
```json
{
  "text": ":hello world",
  "packageName": "com.example.notes",
  "className": "",
  "title": ""
}
```

响应示例：
```json
{
  "matched": true,
  "trigger": ":hello",
  "triggerStart": 0,
  "triggerEnd": 6,
  "replacement": "你好，世界",
  "fullText": "你好，世界 world",
  "format": "text",
  "cursor": -1,
  "imagePath": null,
  "needsInteraction": false,
  "message": "ok"
}
```

- `format` 可能为 `text`、`markdown`、`html`、`image`、`form`、`choice`
- `needsInteraction` 为 `true` 时表示需要用户填写表单或选择项
- 可用于自动化测试规则匹配、变量展开、大小写传播、光标定位等逻辑

### GET /api/v1/files
列出工作区所有 YAML 文件。

响应：`[ { "path": "base", "size": -1 } ]`

### POST /api/v1/files
创建新 YAML 文件。

请求体：`{ "path": "newfile" }`
响应：`{ "file": "newfile.yml" }`

### GET /api/v1/files/{path...}
读取 YAML 文件原始文本（`text/plain; charset=utf-8`）。

### PUT /api/v1/files/{path...}
写入/覆盖 YAML 文件原始文本。

- 请求体为原始 YAML 文本
- 服务端会先解析校验，非法则返回 400
- 成功后会自动 reload + autoPush

### DELETE /api/v1/files/{path...}
删除 YAML 文件。

## Match 模型字段参考
```json
{
  "trigger": "",
  "triggers": [],
  "replace": "",
  "regex": "",
  "prefix": null,
  "word": false,
  "left_word": false,
  "right_word": false,
  "propagate_case": false,
  "uppercase_style": "uppercase",
  "force_clipboard": false,
  "force_mode": "",
  "vars": [],
  "form": null,
  "form_fields": {},
  "markdown": null,
  "html": null,
  "image_path": null,
  "paragraph": false,
  "search_terms": [],
  "comment": null,
  "label": null,
  "filter_title": null,
  "filter_exec": null,
  "filter_class": null,
  "filter_os": null,
  "enable": null,
  "preserve_clipboard": null,
  "restore_clipboard_delay": null,
  "clipboard_threshold": null,
  "word_separators": [],
  "undo_backspace": null,
  "pre_paste_delay": null
}
```

规则有效性要求：`trigger`/`triggers` 至少一个非空，或 `regex` 非空；且 `replace`、`form`、`markdown`、`html`、`image_path` 中至少一个非空。
""".trimIndent()
