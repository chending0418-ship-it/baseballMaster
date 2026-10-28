# iOS → Android 迁移对照与纠偏（2026-10-28）

本项目是现有 iOS 产品的原生 Android 迁移。Kotlin／Compose／Room 替换 Swift／SwiftUI／Core Data 属于实现方式变化；功能、规则、数据身份、权限和页面流程以现行 iOS 为依据，不能根据已有 API 自行拼出另一套产品。本文是源码对照和接手清单，**不是全功能或 UI 验收报告**。

功能冻结基线为 `40df3d2`、iOS 3.1.1；本轮直接阅读分支内现有 Swift 文件，其中 `TeamCollaborationViews.swift` 最近修改来自 `82c23b4`。接手先检查 Git；若上游 iOS 更新，需要追加差异，不能仅依赖旧协议或本文。当前所有新增改动限定 Android 及安卓文档，Swift、服务和公共网站保持原样。

## 本组已纠正的产品偏差

基线 `02abe9b` 的第六组先接通了接口，存在尚未逐页对照的偏差。本组按以下实际源码纠正；旧阶段记录保留当时证据，以本文为当前行为。

| iOS 实际依据 | Android 当前对应 | 本组处理／验证边界 |
| --- | --- | --- |
| `SetupAndRosterViews.swift` / `TeamRosterView` 顶部 `SharedTeamsEntryView` | `RosterScreen.kt` / `RosterScreen` | 从“我的”移至“球队”页本机球队管理之前；点击后进入共享页。未做像素／布局验收 |
| `TeamCollaborationViews.swift` / `SharedTeamsView` 的 `.task { directory.connect() }` | `TeamSharingScreen` 首次进入时刷新 | 去掉新增的独立“启用”步骤；启动／浏览本机球队不联网，明确进入共享页后才连接现行服务 |
| `CreateSharedTeamView` 来源选择、名单披露、共享范围确认 | `SharingEditor(kind=create)` | 排除已绑定来源，展示本次姓名／背号范围，确认后创建；本机队和历史不上传覆盖，不自动跟随本机修改 |
| 创建成功进入 `TeamRecoveryCardView` | 新建回执后进入恢复卡提醒 | 恢复卡默认掩码，明确显示／复制，标记已保存或稍后保存；离开前台隐藏秘密；系统效果未测 |
| `JoinSharedTeamView` | `SharingEditor(kind=join)` | 预览绑定原输入，显示球队／角色，再明确加入；不自动加入 |
| `InviteSharedTeamView` / `fixedInvite(teamID:rotate:)` | 固定链接、二维码、系统分享／复制、明确轮换 | 取代主要入口的早期单次邀请。固定链接可邀请多人，角色固定 member；加入后由管理员调整角色。旧单次邀请协议仍兼容但不作主流程 |
| `SharedTeamDetailView` → 邀请页 → 返回球队 → 返回列表 | `TeamSharingScreen` 返回逻辑 | 球队标题使用球队名，返回逐层回到列表；系统返回／嵌套对话框仍须设备验证 |

固定邀请 POST `/teams/{id}/team-invitation`，只发送已持久化的 `operationID, rotate`。服务端生成并返回秘密；`rotate=false` 复用仍有效的同一链接，`rotate=true` 作废旧链接但不移除已加入成员。Android 在独立加密目录保存响应，丢响应续取同一请求，不能为重试再次轮换。撤权、访问代次／恢复代次变化或撤销后清除活动秘密缓存。

二维码使用 ZXing Core 3.5.3（Apache-2.0），内容为现行 `https://baseballmaster.cc/join#<secret>`，M 级纠错，不在 HTTP query 放秘密。JVM 解码检查 128／256／512 像素图片的实际内容；这不等于手机相机扫码、深链接或跨安装接续已验证。

## F01–F14 接手导航

“已有”表示有对应实现／阶段证据，不表示当前版本已经完成全部首版验收。48 条原始验收场景在 `test-fixtures/android-parity/scope.json`，其 planned 状态不能直接改为 passed。

| 范围 | iOS 依据 | Android 对应与当前缺口 |
| --- | --- | --- |
| F01 首页／球队／名单 | `MainViews`、`SetupAndRosterViews`、`ProfileViews` | `MainActivity`、`RosterScreen`、`Storage`；本机基础已有，共享来源入口本组校正；完整共享绑定／建赛隔离尚缺 |
| F02 建赛／阵容／时间 | `SetupAndRosterViews`、`GameStore` | `GameSetup`、`AdvancedPanels`、core 阵容／计时；离线阶段见 AN03；共享球队新建比赛尚未开放 |
| F03 三种规则 | `Models`、`GameStore` | core `Scoring`／`MatchEngine`；已有冻结 iOS 合成场景对照，不代表导入任意旧比赛都能继续全部操作 |
| F04 记分／练习 | `ScorekeepingViews`、`NoviceScoringViews`、`SituationRecording` | `ScoringPanels`、`PlayFlow`、`SituationField`；离线阶段已有；所有来源／共享比赛编辑受 AN04／F11 能力边界限制 |
| F05 纠错／撤回／恢复 | `HistoryCorrection`、`GameStore`、`UpgradeProtection` | core `History`、Room 原子操作；本机功能已有，来源文件原始审计保留；在原始审计上完整追加纠错／撤回未完成 |
| F06 球型／落点／路线 | `BattedBall`、`SituationRecording` | `SituationField`、`PerformanceSheet`、core 观察快照；离线样本已有，跨端全部事件／姓名快照往返未完成 |
| F07 历史／统计 | `Statistics`、`StatisticsViews`、`ResultsViews` | `StatisticsScreen`、core 统计／来源只读投影；年度已有样本，共享正式副本去重及旧个人记录尚未完整接通 |
| F08 PDF／海报／文件 | `ReportPDFRenderer`、`PlayByPlayPDFReport`、`StatisticsPDFReports`、`GamePoster` | `ReportExport`、`GamePoster`；阶段实现已有，长名单／实际分享面板及来源历史全面验收未完成 |
| F09 互导／备份／旧版 | `LocalBackup`、`BackupViews`、`UpgradeProtection`、`HistoricalImportView` | `AppleBackupLibrary`、`AppleImportStorage`、core `AppleSourceBackup`／`AppleGameContinuation`；受限续记、只读旧版、原文保留已接，完整可编辑双向互导和 `.bmteam` 正式映射未完成，真实 v5 缺失 |
| F10 球队协作 | `TeamCollaborationViews`、`TeamSharingStore` | `TeamSharingScreen`、`TeamDirectorySession`；建队／加入／名单／成员／固定邀请／退出已有，第十组已接解散／恢复；完整绑定管理及共享建赛仍缺 |
| F11 比赛协作 | `SharedMatchViews`、`SharedMatches` | `SharedMatchDirectory` 读取／游标／交接控制基础已有；产品比赛列表／正式发布／PUT／完整内容编辑／离线更新和冲突核对尚缺，不开放产品交接 |
| F12 安全／设备／恢复 | `TeamSecurityViews`、`TeamSecurity` | `TeamSecuritySession`、`TeamCredentialRotation`、`CredentialVault`；第八组已接配对、设备管理、管理恢复兑换和卡片分享，第九组补服务恢复核对，第十组补审计；撤销 Bearer 后明确重接入仍缺。界面未执行，AN07 用户明确不做 |
| F13 直播／历史观看 | `LiveBroadcastViews`、`SharedMatchViews` | AN06 第三组已接独立直播／公共字段／加密发布凭证／重试和恢复保护，273 项开发回归通过；共享直播和历史观看三种权限仍缺，界面未执行，见 AN06-STANDALONE-LIVE。复用现有服务与网页 |
| F14 规则／设置／开屏 | `ProfileViews`、`ConfigurableLaunchScreen` | AN06 第二组已接同源开屏／配置／缓存、外观／辅助提示、本地数据、关于／隐私／支持入口；规则沿用现有离线资料。UI／系统效果未执行，AN07 用户明确不做，详见 AN06-LAUNCH |

## 仍未迁移的共享页面

继续开发先读下列具体 View 和背后的 Store，再补 Android。接口测试通过不替代产品流程：

- `SharedTeamDetailView`：共享新建比赛、共享比赛列表、冲突草稿、完整绑定。不能将 `source=shared` 改成 local 绕过现有仓库保护。
- `NewDevicePairingView`、`ApproveDevicePairingView`、`TeamDevicesView`：第八组已对应 `TeamSecurityScreen` 的 newDevice／approve／devices，双端确认、四词短语和设备移除已接；界面未执行。
- `RecoverTeamManagementView`：第八组已对应 `TeamSecurityScreen` 的 recover，预览、失效范围确认、唯一新卡保存和续取已接；界面未执行。
- `ServiceRecoveryReviewView`：第九组已接明确人工核对，先保留旧工作、读取当前完整资料、核对授权和服务确认，成功持久化后才解除恢复阻断；旧请求不会发送。普通访问代次变化的保护不能借此绕过。
- `DissolvedTeamsView`、`DissolveTeamView`、`TeamAuditView`：第十组已对应 TeamLifecycleScreen 的 restore／dissolve／audit，角色、时限、终结回执及审计分页按原服务实现，UI 未执行。
- `SharedMatchesListView`、`PublishSharedMatchView`、`SharedMatchDetailView`、`PreservedMatchDraftsView`、`SharedConflictReviewView`、`SharedConflictDetailView`、`HistoricalImportView`、`SharedPlayerLinksView`：读取 API 基础不足以开放编辑／交接，先解决完整内容和持久恢复。

优先顺序仍是完整互导和历史纠错 → 协作内容与同步 → 直播／系统入口 → 全量回归；不把 AN05 的阶段增量当作 AN04 已通过。全局规则或协议说明矛盾记入计划第 14 节，等两端上线后统一改。

## 本组证据和未验证项

验证命令沿用统一交接：`:core:test :core:teamServiceTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。原服务测试仅使用 `127.0.0.1` 动态端口及内存数据库。最终数值、日志和冻结位置见 [第七组](AN05-PARITY-2026-10-28.md)。

**未执行**：API31／37 Activity 与真实 UI、像素／字体／键盘／返回、相机扫码、系统分享和剪贴板、截图保护、进程恢复、iOS App/Core Data 互导、真实 iOS↔Android 联网协作、生产验证。默认应用服务地址为现行生产 HTTPS，不能直接打开它跑联网 UI。应注入独立测试 API／凭证／目录或 fake transport；禁止为了测试复用生产身份。

接手入口：[完整交接](ANDROID-HANDOFF-2026-10-28.md)。若只需要解释迁移方式，应明确：Android 原生代码需要移植实现，产品以 iOS 为准；目前仍有实质开发缺口，绝非只剩界面测试。
