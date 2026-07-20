# epso 快速上手指南

> 5 分钟开始在 Android 上使用 epso 文本扩展。

## 一、首次使用

1. **安装并打开应用**
2. 在首页点击 **授权（Authorize）** → 开启 **无障碍服务**
3. （可选）点击 **增强授权** 通过 Shizuku 或 adb 命令授予 `WRITE_SECURE_SETTINGS`，实现一键开关无障碍
4. 返回首页，看到状态变为 **已就绪（Ready）** 即可使用

## 二、创建第一条规则

1. 点击底部 **扩展** Tab
2. 点击 **创建规则（Create rule）**
3. 填写：
   - **触发词**：例如 `:eml`
   - **替换为**：例如 `your.email@example.com`
4. 点击右上角 **保存（Save）**

现在随便打开一个输入框，输入 `:eml` 后按空格或标点，文本就会被替换。

## 三、触发方式说明

| 触发方式 | 示例 | 说明 |
|---|---|---|
| 普通触发词 | `:eml` | 输入完整触发词后自动替换 |
| 多触发词 | `:hi` / `:hey` | 多条触发词触发同一条规则 |
| 正则触发 | `\b\d{4}-\d{2}-\d{2}\b` | 匹配到日期格式自动替换 |
| 搜索触发 | `:s` | 弹出悬浮窗搜索规则 |
| 前缀 | `:` | 所有规则可共享默认前缀 |

## 四、变量

变量用 `{{name}}` 占位。示例：

```yaml
- trigger: ":date"
  replace: "今天是 {{today}}"
  vars:
    - name: today
      type: date
      params:
        format: "%Y-%m-%d"
```

支持的变量类型：

| 类型 | 用途 |
|---|---|
| `echo` | 固定文本 |
| `date` | 当前日期时间，支持 `strftime` 格式 |
| `clipboard` | 读取剪贴板 |
| `random` | 随机选择或随机数 |
| `choice` | 弹出选择列表 |
| `shell` | 执行 shell 命令 |
| `script` | 执行外部脚本 |
| `http` | 发送 HTTP 请求并取回响应 |
| `match` | 递归触发另一条规则 |

## 五、表单

```yaml
- trigger: ":greet"
  form: "Hello, [[name]]! You are [[age]] years old."
  form_fields:
    name:
      default: "World"
    age:
      type: list
      values:
        - "18"
        - "25"
        - "30"
```

输入 `:greet` 后会弹出悬浮窗，填写后自动回填。

## 六、YAML 文件

规则按 YAML 文件分组存放在工作区：

```
matches/
  base.yml        # 基础规则
  email.yml       # 邮件相关
  packages/       # 包商店安装
    xxx-package/
      package.yml
```

在 **文件（Files）** 页面可：
- 创建 / 重命名 / 删除文件
- 导入 / 导出 ZIP
- 编辑原始 YAML
- 配置同步

## 七、同步

1. 进入 **同步** Tab
2. 选择方式：
   - **本地文件夹**：用 SAF 授权一个本地目录（可配合 Syncthing / FolderSync）
   - **WebDAV**：输入 WebDAV URL、账号、密码
3. 选择冲突策略，点击 **测试连接**
4. 开启 **保存后自动推送** 即可实时同步

## 八、包商店

1. 进入 **扩展** Tab → **包商店（Package Store）**
2. 等待加载 espanso Hub 包列表
3. 点击安装，会自动下载并解压到 `packages/`
4. 安装后规则立即生效

> 如果网络访问 GitHub 较慢，可先从浏览器下载包 zip 到手机，再通过 **文件 → 导入** 手动安装。

## 九、HTTP API

应用默认在局域网 `http://<手机IP>:8888` 提供 REST API：

```bash
# 查看规则
curl http://<手机IP>:8888/api/v1/rules

# 创建规则
curl -X POST http://<手机IP>:8888/api/v1/rules \
  -H "Content-Type: application/json" \
  -d '{"file":"base","rule":{"trigger":":test","replace":"hello"}}'
```

> 非本机访问需要在设置中查看 HTTP API Token。

## 十、示例规则

`docs/examples/` 目录下包含可直接导入的示例：

- `base.yml`：常用文本、日期、签名
- `form.yml`：表单示例
- `regex.yml`：正则触发示例
- `global.yml`：全局变量示例

导入方式：**文件 → 导入 YAML**，选择示例 zip 或单个 yml。

## 十一、常见问题

**Q: 输入触发词后没有反应**
A: 检查无障碍服务是否开启；检查规则是否启用；检查应用是否被电池优化限制。

**Q: 如何关闭所有替换**
A: 在首页或设置中关闭 **文本扩展（Text expansion）** 开关。

**Q: 规则不生效在某些 App**
A: 检查规则是否设置了 `filter_exec` / `filter_title` / `enable` 等过滤条件。

**Q: 包商店打不开**
A: 国内访问 GitHub 可能受限，可尝试 WebDAV/本地同步或手动导入包。
