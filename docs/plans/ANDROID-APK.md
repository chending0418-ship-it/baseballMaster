# BaseballMaster Android 全功能迁移与 APK 分发计划

更新日期：2026-10-27。状态：**AN01、AN02 已完成；工具链、原生工程与首个开发预览 APK 0.1.0-preview.2（2）已交付。AN03–AN08 待实施。** 本文为 Android 单份现行计划，取代 2026-10-13 的暂缓状态；原 AN01–AN08 编号继续使用。

## 1. 用户要求与交付目标

2026-10-27 用户要求在备案等待期间推进 Android，先做计划，再按计划开发：

1. 不上 Google Play 或任何应用市场。
2. 通过官网提供签名 APK 下载、安装及更新。
3. 开发方自行安装 Android 环境、运行模拟器并完成验证；不要求用户提供或购买安卓设备。
4. 面向 2024 年及以后主流 Android 手机尺寸，忽略过小屏幕。
5. 全功能迁移，按当前 iOS 已实现功能核对，包含 3.1.0 与 3.1.1 的新增能力。
6. 使用新的独立 Git 分支，与 iOS 和备案迁移现场隔离。

2026-10-27 开发中用户再次确认：Android 与 iOS 的共享球队及比赛数据必须互通，不同用户可加入同一共享球队，按角色查看、记录和交接。两个平台使用同一服务及授权规则，不建立 Android 专属球队数据空间。界面沿用现有 iOS 信息架构与设计，不以 Compose 默认样式重新设计；官网产品入口固定为 `https://baseballmaster.cc/scorekeeper.html`。

最终交付：可直接安装的签名 Release APK、完整功能对照及验收记录、升级说明、官网下载安装页面与版本描述文件。阶段 APK 用于检查进度，不降低最终全功能要求。

最初规划轮只建立分支和计划；用户随后明确授权完成 AN01／AN02，现已安装环境、建立工程、产出测试签名 APK，未修改 iOS 版本或部署官网／服务。备案等待期间可本地开发和隔离联调；生产迁移、最终公网联调和公开下载按各自发布安排执行，不将 Android 开发视为服务器迁移的新授权。

## 2. 基线、分支与目录

| 项目 | 本轮依据与安排 |
| --- | --- |
| 原工作现场 | `/Users/JasonChan/Documents/BaseballMaster.CC/apps/baseball-scorekeeper`，`codex/dev-3.1.1`；保留原有未提交备案迁移资料 |
| Android 分支 | `codex/android-migration` |
| 隔离工作区 | `/Users/JasonChan/.codex/worktrees/android-migration/baseball-scorekeeper`，同一 Git 仓库的 managed worktree，不另建独立仓库 |
| 功能盘点基线 | `40df3d2`，iOS 工程 3.1.1（Build 1）；包含 3.1.0 完整功能及 3.1.1 可配置开屏的本地实现，不等于已上架版本 |
| Android 源码 | 已建立同仓库 `android/`，沿用 BaseballMaster 产品名；现有 Swift 源码、Xcode 工程、Bundle ID 和版本保持 |
| 共用协议与样本 | 继续使用 `collaboration/`、`live/`；后续平台无关样本归 `test-fixtures/android-parity/`，只放合成数据 |
| 计划与证据 | 本文维护 TODO；交接归 `docs/releases/android/<版本>/`；截图、日志归 `output/validation/android/<阶段>/` |
| 构建与交付 | Gradle 构建输出及缓存配置到 `output/cache/android/`；APK、校验值及清单归 `output/releases/android/<版本>/` |

公共品牌与官网仍归 BaseballMaster.CC 根仓库，通过总导航定位真实路径；不能假定 worktree 的 `../../brand` 或 `../../website` 存在，不建立目录替身。SDK、IDE 采用本机标准安装位置；本机路径、签名及配置不进入代码。

Android 分支取自功能最完整的当前 3.1.1，因此包含尚未进入 main 的前置历史。后续先按各自 PR 处理 iOS／资料前置提交，再更新 Android 分支、审查最终差异并创建 Android PR，避免混入无关改动。进入 main 一律走 PR。本轮不推送、不建 PR、不合并。

现行依据是当前源码、[3.1.0 验收](../releases/3.1.0/ACCEPTANCE.md)、[3.1.1 开发记录](../releases/3.1.1/DEVELOPMENT.md)与[开屏配置](../releases/3.1.1/CONFIGURABLE-LAUNCH.md)。[协作协议](../../collaboration/PROTOCOL.md)连同后续修订和实现读取，不能只取早期 3.0 段落。后续 iOS 有变更时记录增量和基线提交，迁移结束前再核对一次。

## 3. 技术路线

已采用 **Kotlin + Jetpack Compose + Room 原生 Android App**，延续遗留方案。现有主体为 SwiftUI、Swift 记分逻辑、Core Data 和 Apple PDF／分享接口，没有可直接编译为 Android 的客户端核心。保留 iOS，通过同组比赛样本约束两端行为，控制本次改动范围。

| 层次 | 实现安排 |
| --- | --- |
| 规则与统计 | 独立纯 Kotlin 模块，移植记分、操作日志／重放、纠错、统计和公开投影，不依赖界面或网络 |
| 界面 | Compose、ViewModel、状态流及系统导航；沿用产品流程、基础版／Pro 版名称和已确认球场设计，适配系统返回、键盘与安全区 |
| 本地数据 | Room 事务保存正式记录、历史和恢复状态；DataStore 保存普通设置；迁移失败保留原库并提供恢复入口 |
| 凭证和队列 | Android Keystore 保护密钥，加密保存设备绑定与待发操作；排除系统备份，秘密不进普通业务备份、日志或导出包 |
| 网络 | Kotlin 协程及明确超时的 HTTP 客户端，沿用 `/api/v3`、角色／修订／幂等／记分权和格式协商 |
| 平台能力 | Android PDF／Canvas、文件选择器、FileProvider 与分享面板；浏览器承担既有网页内容，主记分界面原生实现 |
| 生命周期 | 先落盘再确认操作；进程恢复、回前台与重连后核对同步；后台重试按系统允许执行，不承诺后台一直在线 |

不重写 iOS，不新建重复后端。复用直播网页、后台和协议，Android 下载入口、深链接及版本描述做必要扩展。依赖选稳定版本，记录许可证；本机记分、同步和升级不依赖 Google 登录、Google Play 服务或市场内更新。品牌资源沿用当前确认基准，实施界面／宣传前按工作区规则读取相应规范。

## 4. 首版全功能对照

下表全部属于首版；AN01 已细化为 48 条页面与操作用例，实现与验收分别登记。

| ID | 迁移范围 | 当前源码依据 | 验收重点 |
| --- | --- | --- | --- |
| F01 | 首页、四栏导航、球队／对手、球员中英文姓名和背号、赛季与归档管理 | `MainViews`、`SetupAndRosterViews`、`ProfileViews` | ID 和关联稳定，保留 0／00、长姓名、同名球员和历史 |
| F02 | 新建／预告比赛、双方阵容、打序、换人换守位、换投、首球前攻守互换、时间补填及赛后更正 | `Models`、`GameStore`、`SetupAndRosterViews` | 三模式均可从赛前进入比赛；时间、时区和更正记录一致 |
| F03 | 标准棒球、教练投手、成人慢垒的全部规则和配置 | `Models`、`GameStore`、2.3 交付 | 含慢垒 10 人／Free、扩展打序、初始球数、界外策略、Illegal、时间赛及各模式既有规则 |
| F04 | 基础版／Pro 版记分、练习、完整记录、补充本球、进垒出局、传球路线、失误责任及待确认事件 | `ScorekeepingViews`、`NoviceScoringViews`、`SituationRecording` | 取消不记球、补充不增球、练习不生成正式比赛；Pro 沿用现有免费记分方式 |
| F05 | 撤销重做、历史纠错／重放、漏换人／守位／局面／失误修正、中断恢复、终场与返回首页 | `HistoryCorrection`、`GameStore`、`UpgradeProtection` | 杀进程后恢复；纠错重算比分、责任与第三出局有效得分 |
| F06 | 球型、方向、落点、守备观察、路线姓名快照、本场／年度击球图与表现 | `BattedBall`、`SituationRecording`、3.1.0 验收 | 观察不擅改正式统计，换人后历史姓名保留，慢垒拒绝非法触击 |
| F07 | 历史比赛、日期／赛季／模式筛选、个人与球队打投守统计、年度统计及共享去重 | `Statistics`、`StatisticsViews`、`ResultsViews` | 同输入得到同统计、过滤集合和年度范围 |
| F08 | 比赛报告、逐球 PDF、个人／球队统计 PDF、海报、文件导出及分享 | `ReportPDFRenderer`、`PlayByPlayPDFReport`、`StatisticsPDFReports`、`GamePoster` | 中文字体、长名单、多页表格和击球图完整，文件可打开与分享 |
| F09 | 本机备份／恢复、旧格式、升级保护、球队完整档案及历史导入 | `LocalBackup`、`BackupViews`、`UpgradeProtection`、`SharedMatches` | 双向互导，校验失败不改原库；不覆盖本机原稿、不恢复撤销权限 |
| F10 | 免注册身份、建队／入队、固定多人邀请、角色与管理员上限、名单／成员、退出与解散恢复 | `TeamCollaboration`、`TeamSharingStore`、3.0.1 验收 | 按当前三名管理员上限；本机与共享副本隔离 |
| F11 | 发布／同步、双方记分交接与强制接管、冲突核对、离线队列、撤权及恢复代次 | `SharedMatches`、协作服务 | 同场单设备获权写入，iOS ↔ Android 双向交接，旧代次不能覆盖正式比赛 |
| F12 | 设备配对、管理恢复卡、撤销、安全操作和审计 | `TeamSecurity`、`TeamSecurityViews` | 换设备明确授权，恢复／撤权后旧凭证失效，秘密不进业务备份 |
| F13 | 独立／共享直播、队名分享、实时阵容、历史观看三种权限、指定人员及关闭入口 | `LiveBroadcast`、`LiveBroadcastViews`、`SharedMatchViews`、两个服务目录 | 报告／正式比赛／网页一致；实时期限和长期档案权限分开；撤销即拒绝 |
| F14 | 规则阅读、设置／关于／支持／隐私、固定品牌及 3.1.1 可配置开屏 | `ProfileViews`、`ConfigurableLaunchScreen`、3.1.1 文档 | 同源图片校验、缓存／有效期／跳过／断网兜底一致；不等网络、不阻挡邀请 |

正式备案号与隐私说明按实际取得和最终环境接入 Android，核对包名、签名和备案资料一致，不使用占位编号。网站总后台继续使用既有网页，不额外开发 Android 管理 App。iOS 尚未实现的小组件、iPad／Duo 待办等不自动成为本次新增功能。

## 5. 系统与尺寸基准

以下为 AN01 已冻结基准；实际工具、镜像与已测范围见 [AN01／AN02 交接](../releases/android/AN01-AN02.md)：

- **最低 Android 12／API 31**，重点 Android 14–17，同时覆盖 12、13 的安装及关键回归。购买年份不直接决定系统版本，也不据此承诺具体市场占比。
- 已固定 `compileSdk`／`targetSdk` **37（Android 17）**；使用正式稳定工具与 SDK，建工程时复核 SDK 元数据、AGP／Gradle／JDK 兼容关系，不把预览依赖作为发行前提。本轮已核对 [Android 17 SDK 配置](https://developer.android.com/about/versions/17/setup-sdk)。
- 手机以竖屏为主，重点可用宽度 **360、393、412、432 dp**，搭配不同高度及密度。小于 360 dp 的窄窗口不做专门布局和发布验收；不按英寸或 1080／1440 像素硬编码。
- 600／840 dp 以上窗口提供可用的受限宽度或分区布局；横屏、分屏、折叠变化保留状态且主要操作可达，不另做平板／折叠屏专属操作体系。按 [Android 窗口尺寸类别](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)适配实际窗口。
- 覆盖手势／三键导航、状态栏和挖孔安全区、键盘、字体 1.0／1.3／1.5、浅深色及长名单；不能靠缩小全部文字塞进球场。
- 官网提供一个独立完整 APK。若运行依赖含 `.so`，至少含 `arm64-v8a`，核对原生库与 **16 KB 页大小**兼容；纯 Kotlin／Java 无须人为分 ABI。按 [16 KB 官方说明](https://developer.android.com/guide/practices/page-sizes)检查并实测可用镜像，不要求用户安装 split APK 或 Google Play 组件。
- 本范围针对能运行 Android APK 的系统；不具备 APK 能力的纯鸿蒙系统需另一个客户端，不列为此 APK 已支持设备。

交付按系统、窗口及生命周期矩阵证明适配，不宣称已完成小米、OPPO、vivo、荣耀、三星各厂商真机实测。有可用远程厂商设备时可补测安装、分享、链接和后台恢复，非本次必需前提，不要求用户准备手机；未测行为如实写入交接。

## 6. 环境与模拟器

已安装 Apple Silicon Android Studio Rabbit 1（2026.2.1.8）、JBR 25.0.3、Command-line Tools 22.0、Build Tools 37.0.0、Platform Tools 37.0.1、Emulator 37.2.12 及 API 31／37.0 ARM64 镜像；Hypervisor.Framework 加速实际检查通过。Gradle／AGP 和运行依赖已固定，见 [工程说明](../../android/README.md)。

环境与后续扩展按以下方式维护（本轮已完成主测／最低版安装及独立 AVD，其他镜像与跨平台服务联调随对应阶段执行）：

1. 安装 Apple Silicon 稳定版 Android Studio 及匹配 JDK，配置 SDK Command-line Tools、Platform Tools、Build Tools、正式 SDK／系统镜像和 Gradle Wrapper。
2. 用 `emulator -accel-check` 与实际开机验证加速；Apple Silicon 采用 ARM64 镜像，参考 [官方模拟器加速说明](https://developer.android.com/studio/run/emulator-acceleration)。先装最低版和主测版，其他按需补齐；初期工程空间预算约 40–60 GB，实际占用另记。
3. 构建 Debug／Release、安装启动并保存工具版本及可复现命令，建立构建／测试／截图脚本。
4. 两套独立 Android AVD 与 iOS 模拟器连接本地合成数据服务，使用不同凭证测试角色及交接。资源不足时顺序执行，分别保存状态，不把同一身份伪装成不同设备。
5. 保留至少一套不含 Google 服务的镜像，验证离线记分、联网协作、浏览器下载和覆盖安装。某 API 缺少 ARM64／16 KB 镜像时记录限制，采用可用等效环境补足，缺失组合不写成通过。

AN02 的完成条件是“安装 APK → 创建本机球队 → 记一球 → 杀进程 → 重启恢复”，不是仅装好 IDE。

## 7. 数据及规则一致性

Swift 与 Kotlin 两套实现的主要风险是规则分叉，先建立平台无关输入／结果样本，再实现界面：

- 从 `BaseballMasterTests/GameStoreTests.swift`、服务 fixture 及三模式基准比赛抽取合成用例，保存规则／名单、操作序列、最终状态、逐人统计、投影及纠错结果。iOS 运行生成参考，Kotlin 用相同输入比较；发现已有问题单独处理，不静默改参考值迁就新实现。
- 重点覆盖第三出局与得分、封杀／触杀／接杀、双杀三杀、失误责任、换投与投手实际出局数、慢垒规则、补充不增球、撤销重做、历史重放和计时恢复。界面测试不能代替规则验证。
- 共享 API 保持协议 1，新比赛 `formatVersion: 2`。遵守能力协商及旧端写入保护；未知语义保留或明确拒绝编辑，不能解码丢字段后覆盖云端。
- 显式兼容 Swift Codable 枚举关联值、整数键、UUID、Base64 和时间：**API 元数据为 Unix 毫秒，比赛 payload 日期为自 2001-01-01 起的秒数**。比较只规范化明确无业务含义的差异，不排除身份、时间或历史操作。
- 当前 iOS Core Data schema 8，`.bmbackup` 为格式 7、读取 1–7；Android 使用 Room 自己的 schema，不直接读取 Core Data SQLite。用各版合成备份验证 iOS → Android，再将 Android 兼容备份由当前 iOS 读取，覆盖双向及重复导入。对原 payload 字节校验 SHA-256 后再解析，不用重新序列化的字节冒充原文。
- 同步验证 operationID、修订、writer／access／recovery epoch、游标、删除标记及响应丢失重试。本机原稿、共享正式副本和冲突草稿分别保留；恢复备份不授予球队权限。
- 配对和管理恢复沿用授权流程，不能通过复制 Keychain／普通备份自动登录；复制模拟器快照产生的相同秘密不能代表两个独立设备。

## 8. 阶段计划与 TODO

按 AN01 → AN02 → AN03 → AN04 → AN05 → AN06 → AN07 → AN08 推进。测试随功能建立；AN02 提前预演测试签名升级，AN08 完成正式发布。每阶段交付可查看结果并更新本文，不把阶段 APK 叫作全功能最终版。

| 阶段 | 完成条件 | 可审阅交付 | 当前状态 |
| --- | --- | --- | --- |
| AN01 范围冻结 | F01–F14 细化为页面／规则／格式／用例，固定支持系统、尺寸、包名及技术栈 | 对照矩阵、首批三模式样本、环境与依赖清单 | 已完成：48 条用例、三模式 iOS 样本及参数冻结 |
| AN02 环境和首条链路 | 安装环境、建工程、核心／存储分层、凭证和恢复基础；记一球并杀进程恢复 | 阶段 APK、首页／记分截图、工具清单及恢复证据 | 已完成：0.1.0-preview.2，API 31／37 验证通过 |
| AN03 完整比赛核心 | F01–F06：三模式、阵容、基础版／Pro、练习、观察／路线、撤销／纠错／终场 | 完整离线比赛 APK、规则对照、关键页截图 | 待实施 |
| AN04 历史与输出 | F07–F09 及规则阅读：统计／年度图、PDF／海报／分享、备份／互导 | 完整离线功能 APK、导出样例及互导报告 | 待实施 |
| AN05 球队协作 | F10–F12：身份、邀请／权限、同步／交接、冲突／撤权、配对／恢复、导入／档案 | iOS＋Android 联调 APK、双向交接及权限证据 | 待实施 |
| AN06 直播与系统接入 | F13–F14：直播／观看权限、安装后继续加入、开屏、关于／隐私、系统入口 | 功能齐全候选 APK、邀请／直播／分享链路证据 | 待实施 |
| AN07 全量验收 | 第 9 节矩阵、全部功能及负向用例、跨平台／数据／候选 Release 回归 | 用例结果、截图／录屏、阻断问题清零及未测范围 | 待实施 |
| AN08 发布交付 | 正式签名／保管、版本／升级、下载页／描述文件、最终包；复核实际分发要求 | 签名 APK、SHA-256、证书指纹、官网候选材料与交接 | 待实施 |

- [x] 找回旧计划，核对当前功能和环境，建立独立分支及隔离 worktree。
- [x] **AN01** 细化功能／样本并冻结首版基线、系统／尺寸、Android 标识与工具组合。
- [x] **AN02** 建环境和工程，通过本机记分／进程恢复并预演测试签名连续升级。
- [ ] **AN03** 三模式、两种记分方式、换人／纠错／恢复、击球图和路线通过规则对照。
- [ ] **AN04** 历史统计、导出、旧备份及双向互导通过。
- [ ] **AN05** 同队跨平台权限、同步、记分交接、撤销与恢复通过。
- [ ] **AN06** 直播／观看、邀请和配对链接、开屏及其余系统入口接通。
- [ ] **AN07** 验收矩阵通过，阻断问题清零，残余限制逐项记录。
- [ ] **AN08** 正式签名、覆盖升级、官网候选材料与 APK 完整交付，公开下载按实际执行登记。

AN02 已形成可重复构建和验证链路，三模式投球子集对照通过；完整规则／纠错的 AN03 与权限同步的 AN05 仍是主要工作。后续按这些阶段交付，不将当前 APK 视为全功能迁移完成，也不将尚未实现的工作绑定到承诺日期。

## 9. 验收矩阵

| 环境 | 主要检查 |
| --- | --- |
| Android 12／API 31，360 dp | 最低系统，安装、三模式核心、保存恢复及文件访问 |
| Android 13／API 33，393 dp | 文件／分享、权限与升级 |
| Android 14／API 34，393／412 dp | 全功能主回归、生命周期及备份 |
| Android 15／API 35，412 dp | 边到边安全区、返回、可用 16 KB 镜像安装及核心回归 |
| Android 16／API 36，412／432 dp | 字体／显示缩放、键盘、横屏及状态恢复 |
| Android 17／API 37，412／432 dp | targetSdk 37 行为、全功能 Release 候选及升级 |
| 600／840 dp 以上窗口 | 尺寸变化、主要操作、草稿和弹窗状态 |
| 无 GMS 镜像及独立设备身份 | 无 Google 服务依赖、Android 双设备与 iOS 协作 |

全功能用例至少在 Android 14 与最新支持稳定版执行；其他版本执行安装、三模式关键链路、恢复、分享／链接／升级及版本差异用例。尺寸按布局变化选代表场景，不重复全部 API × 全部尺寸组合。

必须证明：

- F01–F14 无静默删减；核心基准比赛比分、球员归属、打投守统计、纠错和投影一致。
- 断网、超时、冲突、重复点击、响应丢失、杀进程及重启后，已确认操作可恢复，待发不丢失、不重复正式写入；存储满或迁移失败不报假成功。
- iOS ↔ Android 角色一致，正常交接和失联接管可用；旧设备、降级／移除成员、撤销观看及跨队访问被服务端拒绝，旧格式写入边界明确。
- 时间赛、跨年／时区、日期更正与年度过滤、长名单、同名／00 背号、慢垒 10 人及复杂跑垒通过；两端互读合成备份及球队档案。
- Android 发起直播可由 iOS／浏览器观看，iOS 交接后 Android 可继续；期限、历史三模式、转发和撤销符合当前行为。
- 浏览器首次下载可安装，更新经过浏览器／系统安装器实际确认，不能仅用 `adb install -r` 代替用户升级流程；adb 另外用于自动化回归。
- 同一发布签名旧版 → 新版覆盖保留比赛、设置及授权，Room schema 升级通过；错签名、降版本、损坏下载与迁移失败可解释且保护原资料，不以卸载为正常更新办法。
- 候选 Release 构建、静态检查、核心单元测试、必要的 Compose／集成测试及实际安装通过；APK 不含调试入口、测试服务地址、私有凭证或源码备份；PDF／海报实际打开并检查排版。

证据登记镜像、窗口、版本、结果及路径；失败和未测不写成通过。默认只用合成数据和隔离服务，不连接生产或导入真实用户资料。不安排长时 soak；GitHub 工作流若新增只保留 `workflow_dispatch`。

## 10. 签名、官网与升级

AN01 已固定 Android `applicationId` 为 `cc.baseballmaster.scorekeeper`，首个正式 `versionName` 为 `1.0.0`、`versionCode` 从 1 递增。Android 1.0.0 表示首个 Android 正式版，功能对齐本文基线；iOS 编号保持。首次对外分发前固定包名和发布证书，内部测试用不同包名／密钥。

正式密钥在 AN08 建立，由产品所有者长期持有，密钥及密码不进 Git／官网／普通输出；使用受限私有目录，制作加密备份并验证可恢复。未落实保管只能标为测试 APK。按 [Android 签名说明](https://developer.android.com/studio/publish/app-signing)执行 `apksigner verify` 和指纹核对，正式更新沿用同一证书。

官网及 App 安排：

- 官网下载入口固定为现有产品页 `https://baseballmaster.cc/scorekeeper.html`，在其既有 `#download` 区增加 Android APK 下载与安装指引；不新增 `/android/` 独立产品入口。源码归根仓库 `website/public/`；APK 使用带版本的不可变资源 URL，保留旧包。页面显示版本／日期、最低系统、大小、更新说明、SHA-256、隐私／支持及安装步骤。
- 版本描述提供 versionName／versionCode、minSdk、APK 地址、字节数、SHA-256、日期和说明；HTTPS 同源，发布顺序保证不会指向未上传完成的包。
- App 检查更新后打开官网下载，经浏览器与系统安装器确认；不静默安装、不要求 root，不以开发者模式作为正常安装前提。首版不自行下载并请求 App 安装权限。
- 依 [Android 官网分发说明](https://developer.android.com/distribute/marketing-tools/alternative-distribution)，用户需允许浏览器／文件来源安装未知应用；验证拒绝、取消与重试，说明内置浏览器必要时需在系统浏览器打开。
- 邀请／配对保留原地址及 fragment 令牌，评估 App Links 和 `assetlinks.json`（只公开包名／证书指纹）。未安装时进入下载页，安装后再次打开原邀请或明确粘贴继续；不承诺令牌自动穿过安装，不把秘密放进查询、下载 URL 或分析日志。
- 原 iOS 入口、邀请和观看链接仍可用；官网根仓库与 App 仓库分别提交、验证并通过 PR 进入各自 main。

不上市场不代表永远免于开发者验证。按 2026-10-27 查阅的[验证总览](https://developer.android.com/developer-verification)和[官方 FAQ](https://developer.android.com/developer-verification/guides/faq)，2026-09-30 首阶段针对指定地区参与商店，直接侧载当时尚不适用该阶段要求；2027 计划扩大。AN08 按实际地区及届时规则复核，必要时使用市场外分发的 Android Developer Console，不改为入驻 Google Play。本轮不注册或产生费用。

官网候选材料可在备案期间本地完成。正式下载上线与服务器迁移分别执行：最终环境、备案资料和发布指令明确后再开放，记录实际时间；本地已签名不等于已公开下载。

## 11. 风险及控制

| 风险 | 处理阶段 |
| --- | --- |
| 两端规则／统计分叉 | AN01 建样本，AN03 逐规则对照，AN07 整场回归 |
| 老协议与当前代码、待上线开屏状态混淆 | 固定提交并看后续修订；AN06 验证接口缺失兜底，不提前部署 |
| Swift 序列化和旧备份不兼容 | AN01 格式样本，AN04 字节校验、往返互导和原库保护 |
| 生命周期及网络造成丢球／双写 | AN02 事务恢复，AN05 代次／持久队列，AN07 故障注入 |
| 字体／PDF／球场坐标在不同尺寸裁切 | AN03／04 实际渲染，AN07 字体／窗口矩阵 |
| 模拟器不能证明厂商安装限制、后台策略或真实性能 | 明确未测范围，不依赖长后台，不宣称全品牌真机验收，可后续补远程设备 |
| 密钥丢失、包名变化导致无法升级 | AN02 预演，AN08 固定、备份恢复和覆盖安装 |
| 备案／服务器迁移未完成 | 本地／隔离开发继续，最终公网联调与公开下载按实际安排 |

## 12. 本轮完成与下一步

AN01、AN02 已完成，详细实现、验证与边界统一见 [阶段交接](../releases/android/AN01-AN02.md)。48 条迁移用例与三模式真实 iOS 样本已固定；原生 Android 工程、Room／Keystore 与本机建队→记一球→杀进程恢复链路已建立。

首个 APK 为 `output/releases/android/0.1.0-preview.2/BaseballMaster-0.1.0-preview.2.apk`，使用独立预览包名及测试证书；已在 Android 12 无 GMS／360 dp 与 Android 17／约 412 dp 安装验证。同签名覆盖、Room 1→2、比赛 payload 校验和设备凭证保留通过。最终关键截图在 `output/validation/android/an01-an02/screenshots-final/`。

当前 APK 尚未接入共享服务；同一共享球队的 iOS↔Android 加入、查看、记录与交接仍为 AN05 必须通过项。官网正式下载入口沿用产品页 `/scorekeeper.html#download`，本轮无网站／服务部署和公开下载。下一阶段为 AN03 完整比赛核心；当前入口见 [CURRENT](../CURRENT.md)。

## 13. 历史安排

- 2026-10-08：曾安排 Android／APK 为后续 3.1，建议 Kotlin／Compose／Room，建立 AN01–AN08。
- 2026-10-13：用户要求 Android、跨平台与 APK 暂缓，移出 iOS 3.1，保留旧方案。
- 2026-10-27：重新启动规划，明确不上市场、官网 APK、开发方自行模拟、2024 年后主流尺寸、全功能及新分支；取代暂缓状态，本轮仍只做计划。
- 2026-10-27：用户授权完成 AN01／AN02，并再次强调 iOS 视觉延续、共享球队与比赛双向互通，以及现有官网产品页为唯一下载入口；两阶段已按交接完成。
