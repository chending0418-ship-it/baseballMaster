# Android 后续开发清单与接手顺序（2026-10-28）

**本清单仍有实质开发任务。AN04／AN05 未完成，不能说只剩 UI；AN06 主要代码已接入但系统链路和发布依赖未闭合。用户明确本轮不做 AN07。** 不以跳过验收代替实现；下列空框也不能因单个 API 测试通过就勾选。

继续开发入口为 [统一交接](ANDROID-HANDOFF-2026-10-28.md)，单份现行计划 [ANDROID-APK](../../plans/ANDROID-APK.md)。不要从最早 AN04-HANDOFF 重新开发，不要重做已接入的配对／重接入／服务恢复核对／直播／观看权限。

## 1. AN04：优先完成正式写入

- [ ] 原生 Android 新比赛→正式 iOS StoredGame／`.bmbackup`。当前 `AppleExportPrototype` 仍为研究实现，不可直接改名或连按钮称为完成。`AppleHistoryCommandCodec` 已有命令编码；当前读回入口 `AppleReplayPrototype` 已支持较广 journal。**缺口是多事件／日志／打席图的正式生成及身份对应**，不能假定一个操作只产生一条事件。
- [ ] 全部三模式／基础版／Pro／特殊阵容和规则操作的写入：PLAY 开场、投球终结、第三出局、换边、规则确认、终场／中断／恢复、换人／DH／双重换人／双向球员、TB、取消上一结果和复核。写入使用既有 Swift enum／日期／UUID 字典，日期仍为 2001 起秒数；不修改 Swift 或公共协议。
- [ ] iOS 来源续记从 `AppleGameContinuation` 当前的受限 pitch 扩展到完整命令。仍保留原始文件、旧事件／日志／打席／操作 ID、原检查点 Data、未编辑比赛及旧审计。新增 ID 按原 `HistoryCorrection.stableHistoryID` SHA-256 规则生成；不能用 UUID v3 替代。`AppleSourceBackup.validateLineage` 也须同步独立验证新动作，不放松为“新 checksum 合法即可”。
- [ ] 在原始审计链上写入补记／纠错／撤回。当前能读到 Native 历史、也能验证原始 Swift 动作；**尚未把 Native 更正写回原始完整 wire graph**。上一版 full bytes 不能丢；更正后的旧 journal、操作归属和返回 iOS 的再次重放需单独验证。
- [ ] 旧式 `playerGameRecords`、seasons／年度去重、真实旧版边界和 `.bmteam` 正式映射。真实 1／2／3／4／6／7 样本已有；真实 v5 缺失，需找到真实来源或原版本工具生成并明确来源，不能改 envelope.version 伪造。
- [ ] 已存在跨端同场比赛的明确合并／冲突流程。当前整批拒绝重复身份，保护原库；不能改成覆盖或按队名／背号合并。`0` 和 `00` 不同。
- [ ] `.bmteam` 目前仅私有只读原文库，尚不进入 `.bmandroid`。若补入本机备份，需设计文件库与 Room 的完整恢复／失败回滚和资源上限，不能恢复一半后显示成功。当前 UI 已明确要求另存原文件，见 `TeamArchiveStore`／`TeamArchiveLibrary`／`DeviceBackupControls`。

代码起点：`android/core/src/main/kotlin/cc/baseballmaster/core/Apple{HistoryCommandCodec,StateProjection,ReplayPrototype,GameContinuation,SourceBackup,ExportPrototype,ArchiveHistory,TeamArchiveIntegrity}.kt`；App `Storage.kt`、`AppleImportStorage.kt`、`AndroidDeviceBackup.kt`、`TeamArchiveStore.kt`。原生 `Game` 由 `Scoring.kt` 定义，操作／纠错由 `MatchEngine.kt`／`History.kt` 处理。

写入完成条件：Native→实际原始 Swift 核心动作／纠错重放→Kotlin 返回逐字段核对；针对所有生成的事件／日志／打席和 previousJournal 检查身份，不只比较比分。来源原文保留和 Room 事务、重试、恢复失败保护仍须通过。原始 Swift 通过不等于 iOS App/Core Data 已测；AN07 未执行项保持未测。

### 必须保留的来源异常

`history-lineup-after-missed-pitcher` 的第二次原始 Swift 预览丢失替换，打序／当前投手／投球统计三项变化。当前 Kotlin 导入该不一致 journal 会拒绝，不覆盖原统计；原文件可留作档案。复现见 [交互验证](AN04-INTERACTIVE-2026-10-28.md)。按用户指令只记录，等两端上线后统一调查，不在本轮更改 iOS／后端／全局规则。

## 2. AN05：依赖完整 payload 的共享记分

- [ ] 原 iOS `PublishSharedMatchView`／`SharedMatches` 的正式发布流程：权限核对、本机比赛来源绑定、玩家 sourceID→共享 roster ID 关联、完整 payload 和现行 formatVersion；不上传 Android 内部 Game JSON。
- [ ] 已授权共享球队建赛／快照、共享比赛进入完整记分和跨端恢复。当前 `GameRepository.createGame` 明确只接受 local；不能简单把 shared 改成 local 绕过限制。
- [ ] 持久离线更新队列和正式 PUT，固定请求身份、baseRevision、writer/access/recovery epoch。现有 `SharedMatchDirectory` 的队列主要是交接控制，不能冒充离线记分队列。
- [ ] 拒绝写入／撤权／换记录员／访问代次变化后的草稿保留、明确冲突核对、可重试确认和本机提交失败恢复；旧请求不得在恢复核对后自动重放。
- [ ] 接通产品交接／强制接管、档案导入、成员关联与比赛删除等现行 iOS 入口。目前交接协议基础存在，但完整接收编辑能力缺失，产品入口仍关。

F10 的建队、固定邀请、角色／名册、退出、解散／恢复和管理员审计，以及 F12 的凭证轮换、配对／移除、管理恢复、401 重接入及服务恢复核对已经有代码和隔离测试。**当前 iOS 没有独立手动绑定本机队的 API；不要根据交接中的“绑定管理”自行设计新全局功能。** 继续依据原创建／发布过程的绑定和现有球员链接语义处理。

代码：core `TeamDirectory{Client,Session}`、`SharedTeamRoster`、`SharedMatchDirectory`、`TeamRecoveryReview`、`TeamDeviceReconnect`、`TeamSecuritySession`；App `AndroidTeamSharing`、`TeamSharingScreen`、`SharedMatchesScreen`、`CredentialVault`、`TeamDirectoryFile`。先读真实 Swift View／Store 和现行 `collaboration/{server,games,drafts}.mjs`，早期 PROTOCOL 的管理员上限／邀请描述有旧段落。

## 3. AN06：系统闭环与已知依赖

- [x] 代码已接：首次／重复 intent 的邀请和配对、加密待办及明确继续；同源开屏配置／缓存和“我的”；独立直播及公共投影／加密凭证；共享实时直播／三种历史权限、指定成员／外部观看者／持久请求和前台保活。
- [ ] 完整开发收口：结合 AN04／AN05 正式共享记分后的新 payload，检查直播投影及 writer 变化／恢复的联动，不自动注册／上传或开播。
- [ ] HTTPS 域名关联与新安装后的继续加入：当前 intent 消费和已安装接续已实现；真实网站工作区、正式证书对应 assetlinks 和浏览器安装后接续仍有依赖。仓库没有总网站工作区，不创建替身或部署生产；AN08 签名和网站上线另行确认。邀请秘密始终放 fragment，不进入 query、下载 URL 或日志。

当前启动会请求**公共开屏配置**；不带球队 Bearer。已有明确启用的共享／独立直播可在前台按当前授权继续保活，不存在“启动完全不联网”的承诺。新连接共享页会请求现行默认服务，测试必须注入 fake 或隔离服务；不打开默认生产入口跑 UI。

## 4. 最新验证与复现

`333b0ef` 扩展读取提交：常规 **296（161 core＋63 协作服务＋8 直播服务＋64 app）**、显式 Swift session 聚合另 1，0 failures/errors/skipped；App／AndroidTest 构建和 lint 通过，19 warnings。109 冻结用例共 598 帧实际动作匹配；487 份 journal 重放＋产品导入成功；已知来源差异 1 帧单独拒绝。不把聚合下的帧数叠加到 JUnit 总数。

```sh
# 当前工作区工具路径在 output/cache/android/tooling/env.sh；新环境需重装免费官方工具。
bash scripts/android/gradle.sh :core:test :core:teamServiceTest :core:liveServiceTest \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# Swift 原始核心：先用全新输出目录编译，再串行启动显式 session 检查。
SWIFTC=<swiftc绝对路径> python3 scripts/android/swift-core-check.py \
  --output output/validation/android/<新目录> --compile-only
bash scripts/android/gradle.sh :core:swiftSessionTest \
  -PswiftCoreBinary=<上述新目录绝对路径>/swift-core
```

Node 24 服务测试只在回环动态端口和内存 SQLite 运行，不启动默认持久库，不运行部署或生产核验脚本。当前 Linux Swift 使用 OpenSSL SHA-256 和内存／no-op 平台适配器，不代表 iOS 系统行为。

普通 Room 测试会重新生成部分 UUID 文件，先前 Swift 13 份文件的回执可能过期；需要返回腿时先完成 producer、冻结新一批，再跑 Swift 和对应 Kotlin return task。**不能拿旧回执核对新文件或删除哈希校验**。`output/` 均被 Git 忽略，日志／XML／逐帧文件需要在新工作区重建。

未验证：本轮 Activity／UI、API31／37 设备、系统分享／剪贴板／相机／新安装浏览器、真实进程中断／磁盘填满、iOS App/Core Data、真实双端权限和交接、生产。AN07 用户明确跳过；AN08 正式签名、证书、发布和下载部署未获本轮授权。不购买、不操作 main、不合并、不发布中间 APK。
