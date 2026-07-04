# 更新内容

- 重构为通用 APP 开发框架
- 移除选择器引擎、订阅管理、规则匹配等业务逻辑
- 移除 Room/KSP 数据库层
- 移除 selector 模块
- 保留 Shizuku、无障碍服务、HTTP Server、Compose UI 组件库等基础设施
- 编译验证通过 (`:app:compileEpsoDebugKotlin`)
