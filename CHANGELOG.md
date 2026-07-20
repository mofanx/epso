# 更新内容

## 最新

- 新增文本扩展引擎，兼容 espanso YAML 配置格式
- 支持触发匹配、变量求值、表单弹窗、规则管理、YAML 文件同步
- 新增包商店、WebDAV / 本地文件夹同步、HTTP REST API
- 编译验证通过 (`:app:compileEpsoDebugKotlin`)

## 历史

- 重构为通用 APP 开发框架
- 移除选择器引擎、订阅管理、规则匹配等业务逻辑
- 移除 Room/KSP 数据库层
- 移除 selector 模块
- 保留 Shizuku、无障碍服务、HTTP Server、Compose UI 组件库等基础设施
