# 通用 APK 开发框架重构计划

> 目标：将 epso 项目重构为通用 Android APP 开发框架，保留基础设施能力（Shizuku、无障碍服务、状态通知、权限管理、UI 组件库），删除所有 epso 业务逻辑（选择器引擎、订阅管理、规则匹配、快照等）。

---

## 一、模块级决策

| 模块 | 决策 | 理由 |
|---|---|---|
| `:selector` | **删除** | epso 选择器引擎，纯业务逻辑 |
| `:hidden_api` | **保留** | 通用隐藏 API 桥接，Shizuku 依赖 |
| `:app` | **重构** | 保留框架，删除 epso 业务代码 |

---

## 二、文件级分类

### 🗑️ 删除（epso 专有）

#### 根目录
- `OpenFileActivity.kt` — epso 备份导入入口

#### `a11y/`
- `A11yFeat.kt` — epso 截屏/订阅更新/规则匹配事件处理
- `A11yRuleEngine.kt` — epso 规则引擎

#### `data/` (删除大部分)
- `A11yEventLog.kt`, `ActionLog.kt`, `ActivityLog.kt`, `AppVisitLog.kt`
- `AppConfig.kt`, `AppRule.kt`, `AttrInfo.kt`, `CategoryConfig.kt`
- `ComplexSnapshot.kt`, `BaseSnapshot.kt`, `Snapshot.kt`
- `AnkAction.kt`, `GlobalRule.kt`, `NodeInfo.kt`
- `RawSubscription.kt`, `ResolvedGroup.kt`, `ResolvedRule.kt`
- `SubsConfig.kt`, `SubsItem.kt`, `SubsVersion.kt`
- `GithubPoliciesAsset.kt`, `RpcError.kt`, `Value.kt`

#### `db/`
- `AppDb.kt` — 清空所有 epso 实体，保留空数据库框架

#### `service/` (删除 epso 专有服务)
- `ActivityService.kt`, `ActivityTileService.kt` — 活动日志
- `ButtonService.kt`, `ButtonTileService.kt` — 快照按钮
- `EventService.kt`, `EventTileService.kt` — 事件日志
- `ExposeService.kt` — 外部调用
- `MatchTileService.kt` — 规则匹配
- `SnapshotTileService.kt` — 快照
- `TrackService.kt` — 轨迹

#### `ui/` (删除 epso 专有页面)
- `A11yEventLogPage.kt`, `A11yEventLogVm.kt`
- `A11yScopeAppListPage.kt`, `A11yScopeAppListVm.kt`
- `ActionLogPage.kt`, `ActionLogVm.kt`
- `ActivityLogPage.kt`, `ActivityLogVm.kt`
- `AppConfigPage.kt`, `AppConfigVm.kt`
- `BlockA11yAppListPage.kt`, `BlockA11yAppListVm.kt`
- `EditBlockAppListPage.kt`, `EditBlockAppListVm.kt`
- `SlowGroupPage.kt`
- `SnapshotPage.kt`, `SnapshotVm.kt`
- `SubsAppGroupListPage.kt`, `SubsAppGroupListVm.kt`
- `SubsAppListPage.kt`, `SubsAppListVm.kt`
- `SubsCategoryGroupPage.kt`, `SubsCategoryGroupVm.kt`
- `SubsCategoryPage.kt`, `SubsCategoryVm.kt`
- `SubsGlobalGroupExcludePage.kt`, `SubsGlobalGroupExcludeVm.kt`
- `SubsGlobalGroupListPage.kt`, `SubsGlobalGroupListVm.kt`
- `UpsertRuleGroupPage.kt`, `UpsertRuleGroupVm.kt`

#### `ui/icon/` (删除 epso 专有图标)
- `DragPan.kt`, `LockOpenRight.kt`, `ResetSettings.kt`
- `SportsBasketball.kt`, `ToggleMid.kt`

#### `util/` (删除 epso 专有工具)
- `Github.kt` — epso GitHub 集成
- `SnapshotExt.kt` — 快照工具
- `SubsState.kt` — 订阅状态
- `Upgrade.kt` — epso 更新逻辑

---

### ✏️ 改造（保留框架，删除 epso 逻辑）

#### 根目录
| 文件 | 改造内容 |
|---|---|
| `App.kt` | 移除 `initA11yFeat`/`initSubsState`/`initA11yWhiteAppList`/`clearHttpSubs`/`syncFixState`，移除 epso URL 引用 |
| `MainActivity.kt` | 移除 epso 专有路由注册，保留框架导航 |
| `MainViewModel.kt` | 移除订阅/规则/更新逻辑，保留导航栈+对话框+权限框架 |
| `OpenSchemeActivity.kt` | 简化 scheme 处理 |

#### `a11y/`
| 文件 | 改造内容 |
|---|---|
| `A11yCommonImpl.kt` | 移除 `ruleEngine` 引用 |
| `A11yContext.kt` | 移除 epso 专有上下文逻辑，保留通用 a11y 连接管理 |
| `A11yExt.kt` | 移除 epso 专有扩展 |
| `A11yState.kt` | 保留 TopActivity 跟踪，移除规则匹配/订阅引用 |

#### `service/`
| 文件 | 改造内容 |
|---|---|
| `A11yService.kt` | 移除 ruleEngine，保留通用无障碍服务框架 |
| `StatusService.kt` | 简化状态通知逻辑，移除 epso 专有状态 |
| `AnkTileService.kt` | 重命名为通用 TileService |
| `OverlayWindowService.kt` | 保留悬浮窗能力，移除 epso 专有逻辑 |
| `HttpService.kt` | 保留 HTTP 服务框架，移除 epso 专有路由 |
| `ScreenshotService.kt` | 保留（通用截屏能力） |

#### `notif/`
| 文件 | 改造内容 |
|---|---|
| `Notif.kt` | 保留 Notif 类 + abNotif，删除 epso 专有通知定义 |
| `NotifChannel.kt` | 保留 Default 频道，删除 Snapshot 频道 |

#### `store/`
| 文件 | 改造内容 |
|---|---|
| `SettingsStore.kt` | 精简为通用设置（深色模式、动态配色、通知、Shizuku） |
| `StoreExt.kt` | 精简为 storeFlow，移除 epso 专有 flow |

| `db/`
| 文件 | 改造内容 |
|---|---|
| `AppDb.kt` | 已删除（Room/KSP 完全移除） |

#### `ui/home/`
| 文件 | 改造内容 |
|---|---|
| `HomePage.kt` | 简化为 2-3 个示例 Tab |
| `ControlPage.kt` | 重写为示例首页 |
| `SettingsPage.kt` | 精简设置项 |
| `ScaffoldExt.kt` | 保留 |

#### `ui/` 页面
| 文件 | 改造内容 |
|---|---|
| `AboutPage.kt` | 精简，移除 epso 专有信息 |
| `AdvancedPage.kt` | 精简为通用高级设置 |
| `WebViewPage.kt` | 保留 |

#### `ui/component/`
| 文件 | 改造内容 |
|---|---|
| `Hooks.kt` | 移除 `useSubs`/`useSubsGroup` |

#### `ui/share/`
| 文件 | 改造内容 |
|---|---|
| `BaseViewModel.kt` | 移除 `mapSafeSubs` 和 subsMapFlow 引用 |

#### `util/`
| 文件 | 改造内容 |
|---|---|
| `Constants.kt` | 移除 epso URL/常量 |
| `CoroutineExt.kt` | 移除 `InterruptRuleMatchException`/`RpcError` 引用 |
| `FolderExt.kt` | 移除 subsFolder/snapshotFolder，保留通用目录 |
| `LifecycleCallbacks.kt` | 保留通用生命周期，移除 a11y 专有引用 |
| `LogUtils.kt` | 移除 epso 专有路径引用 |
| `Option.kt` | 保留通用选项，移除 epso 专有选项 |
| `Others.kt` | 移除 epso 专有工具函数 |
| `Toast.kt` | 移除 epso 专有 toast 逻辑 |
| `AppInfoState.kt` | 保留应用信息加载，移除 epso 专有状态 |
| `BackupUtils.kt` | 已删除 |
| `IntentExt.kt` | 保留通用 intent 扩展 |

---

### ✅ 保留（无需修改）

#### `permission/`
- `PermissionState.kt` — 通用权限框架
- `PermissionDialog.kt` — 通用权限对话框

#### `shizuku/`
- 全部 17 个文件 — 通用 Shizuku API 桥接

#### `ui/style/`
- `Theme.kt`, `Padding.kt`, `Color.kt`, `TextTransformation.kt` — 设计系统

#### `ui/component/` (大部分)
- `PerfIcon.kt`, `PerfSwitch.kt`, `PerfTopAppBar.kt`
- `TextSwitch.kt`, `TextMenu.kt`, `SettingItem.kt`
- `ScaffoldDialog.kt`, `FullscreenDialog.kt`, `DialogOptions.kt`
- `EmptyText.kt`, `AnimationFloatingActionButton.kt`
- `Animation.kt`, `AnimatedBooleanContent.kt`
- `TooltipIconButtonBox.kt`, `CustomIconButton.kt`
- `PerfIconButton.kt`, `AppIcon.kt`, `AppCheckBoxCard.kt`
- 等其他通用组件

#### `ui/share/` (大部分)
- `FixedWindowInsets.kt`, `ListPlaceholder.kt`
- `LocalExt.kt`, `ModifierExt.kt`, `StateExt.kt`
- `AppFilter.kt`

#### `util/` (通用)
- `AndroidTarget.kt`, `BarUtils.kt`, `CollectionExt.kt`
- `FlowExt.kt`, `ImageUtils.kt`, `KeyboardUtils.kt`
- `LinkLoad.kt`, `LoadStatus.kt`, `MutexState.kt`
- `NetworkExt.kt`, `NetworkUtils.kt`, `ScreenUtils.kt`
- `ScreenshotUtil.kt`, `Singleton.kt`, `TimeExt.kt`
- `Unit.kt`, `UriUtils.kt`, `ZipUtils.kt`

#### 构建配置
- `build.gradle.kts` (root) — 保留，移除 selector 引用
- `gradle/libs.versions.toml` — 保留，移除不需要的依赖
- `stability_config.conf` — 保留
- `proguard-rules.pro` — 保留

---

## 三、执行顺序

1. **Phase 1**: ✅ 批量删除 epso 专有文件
2. **Phase 2**: ✅ 改造核心文件 (App.kt, MainViewModel, MainActivity, SettingsStore)
3. **Phase 3**: ✅ 改造 a11y/service 层
4. **Phase 4**: ✅ 改造 UI 页面和组件
5. **Phase 5**: ✅ 改造 util 工具函数
6. **Phase 6**: ✅ 更新构建配置和 AndroidManifest
7. **Phase 7**: ✅ 更新文档
8. **验证**: ✅ 编译测试通过 (`:app:compileAnkDebugKotlin` BUILD SUCCESSFUL)
