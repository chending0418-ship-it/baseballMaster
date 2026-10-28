# Android 迁移统一交接（2026-10-28）

**这是继续开发入口，不是全功能完成或发布证明。** 当前 AN04 完整互导未验收，AN05 正在开发；AN06／AN07 尚未完成，AN08 正式签名发布须另行确认。接手时以分支最新 Git 和本文链接的阶段记录为准；不要只读最早 AN04-HANDOFF 后重复已做工作。

## 用户授权与边界

1. 原任务：拉取 `https://github.com/chending0418-ship-it/baseballMaster` 的 `codex/android-migration`，确认包含 `403e6f56ca2386d38f692722aa4c7f899c270e9e`，从 AN04 继续到 AN05／AN06／AN07，分阶段测试、提交、推送安卓分支。
2. 本轮是安卓迁移。涉及 iOS／全局规则／共享协议／品牌等统一修改只记录，等安卓和 iOS 都上线后统一处理。现行服务和 iOS 源码只读作为适配依据，不新建另一套后端。
3. 不购买、不合并或直接写 main、不操作生产、不发布中间 APK；正式签名和公开分发另行确认。可本地构建 debug／test APK，但不是交付／发布。
4. 用户先接手全部界面测试，最新又明确“界面测试能做就做，不做也没事”。因此可选择有条件的 UI 验证，但不能把未测写成通过；优先完成实际数据／协议功能。
5. 最新要求：今天连续推进开发、做好完整交接，按计划分阶段提交推送，不等最后一次性提交。无需每阶段重新索取同类授权。
6. 用户追问迁移方式：这是现有 iOS 的原生 Android 迁移。技术代码需移植，功能／页面流程以 iOS 为准；先读 [iOS 源码对照及缺口](IOS-PARITY-AUDIT-2026-10-28.md)，不能只把 API 接上就称为完成迁移。

先读根 AGENTS.md 及 [单份现行计划](../../plans/ANDROID-APK.md)。当前云端缺少上级工作区／品牌目录，不能创建替身或引用用户电脑上的绝对路径。根规则中已撤销的历史说明不作为当前开发限制。

## 仓库和检查点

云端目录 `/workspace/baseballMaster`；Android 为同仓库 `android/`。保留 `BaseballMaster/` 和 Xcode 工程原名，不修改 iOS 版本。工作分支 `codex/android-migration`，普通快进推送，无强推／PR 合并。

| 提交 | 已做内容 | 结果入口 |
| --- | --- | --- |
| `403e6f5` | 用户指定的原 AN04 断点 | [云端第三组](AN04-CLOUD-2026-10-28.md#第三组续开发从-403e6f5-恢复) |
| `df142ec` | 原始纠错审计保留、档案历史阅读、严格本机备份 JSON | 同上 |
| `9e2f502` | 原生研究导出纠错／撤回、打席／换人，与原 Swift 模型互读 | [模型互读](AN04-WIRE-2026-10-28.md) |
| `1d0615a` | 来源保护比赛实际导入、Room 3→4、Android 来源备份格式 2 | [来源导入](AN04-IMPORT-2026-10-28.md) |
| `b0669e5` | 不挤分保送、非第三出局三振的双事件续记 | 同上第六组；156 项 JVM＋11 份 Swift 互读 |
| `4452a86` | AN05 身份／目录／邀请与加密持久请求 | [AN05 第一组](AN05-DIRECTORY-2026-10-28.md)；171 项测试 |
| `617c969` | AN05 共享名册、成员管理、独立绑定、冲突草稿和撤权清理 | [AN05 第二组](AN05-ROSTER-2026-10-28.md) |
| `416b645` | F11 比赛 JSON／游标读取、独立持久交接控制、旧回执后核对权限 | [AN05 第三组](AN05-MATCHES-2026-10-28.md)；190 项测试 |
| `4fab89b` | 固定本机加密秘密、现行两阶段设备凭证轮换、90 天维护和写失败保护 | [AN05 第四组](AN05-CREDENTIALS-2026-10-28.md) |
| `7807c8c` | 配对／设备移除／管理恢复、唯一新卡持久续取与过期请求归档 | [AN05 第五组](AN05-SECURITY-2026-10-28.md) |
| `02abe9b` | F10 菜单、建队／入队／名单／成员／邀请管理和恢复卡查看／轮换；UI 未执行 | [AN05 第六组](AN05-UI-2026-10-28.md) |
| 第七组（包含本表更新的提交） | 对照 iOS 校正球队页入口、连接／建队流程，补固定多人邀请、二维码／系统分享；226 项非 UI 回归 | [AN05 第七组](AN05-PARITY-2026-10-28.md) |

接手先检查，不覆盖未提交工作：

```sh
git status --short
git branch --show-current
git fetch origin codex/android-migration
git merge-base --is-ancestor 403e6f56ca2386d38f692722aa4c7f899c270e9e HEAD
git rev-list --left-right --count HEAD...origin/codex/android-migration
git log -10 --oneline
```

输出 `0 0` 才是与远端一致。目录重置／新环境先 clone 对应分支；不要从 main 重新起步丢掉迁移增量。`output/` 全部忽略，不在远端；阶段 Markdown 和可复现测试源码会推送，云端生成证据需要重建。

## 已有架构与不可越过的保护

| 位置 | 作用／保护 |
| --- | --- |
| `android/core/.../Scoring.kt`、`MatchEngine.kt`、`History.kt` | 三模式规则、逐操作记录、纠错／重放；AN03 已有 iOS 合成基准，不擅自改全局计分规则 |
| `AppleSourceBackup.kt` | 实际 iOS 文件导入门面；全文件保留、整批拒绝不兼容、来源摘要与 Android 内部标记；严格校验原来源与当前续记血缘 |
| `AppleGameContinuation.kt` | 对源文件做受限追加，保留原事件／日志／打席／操作／检查点／纠错审计身份；不支持的命令明确拒绝 |
| `AppleReplayPrototype.kt` | 历史类名保留，现用于只读投影／核对，不得拿投影重写原 iOS 历史 |
| `AppleExportPrototype.kt` | **原生 Android → iOS 研究编码，未正式接入产品导出**；带来源标记的导入比赛明确拒绝走此路径 |
| App `Storage.kt`、`AppleImportStorage.kt` | Room schema 4，3→4 只新增来源 BLOB／关联／原操作 ID 索引；不能使用 destructive migration |
| `AndroidDeviceBackup.kt` | 无来源仍为原格式 1，含来源使用格式 2；恢复只接受空库、整批事务，删除来源再重算哈希不能绕过保护 |
| `TeamDirectoryClient.kt`、`SharedTeamRoster.kt` | 当前 `/api/v3` 的 Kotlin 客户端和 DTO；API 日期为 Unix 毫秒，比赛 payload 仍为 Swift 2001 起秒数 |
| `TeamDirectorySession.kt`、`SharedMatchDirectory.kt` | 单笔持久身份操作及每场独立交接控制、固定 operationID、权限／代次核对、缓存、绑定、冲突草稿；不是离线记分更新队列 |
| App `CredentialVault.kt`、`TeamDirectoryFile.kt` | Keystore 分别保护可轮换认证秘密和固定的本机加密秘密，noBackupFilesDir 下单独 AES-GCM；不进入普通备份／报告／导出或日志 |
| App `AndroidTeamSharing.kt` | IO 协程应用 API，读取真实本机球队后准备发布，保持原 Room 记录不变；F10 入口见 TeamSharingScreen |
| `collaboration/`、Swift `TeamCollaboration`／`TeamSharingStore`／`SharedMatches`／`TeamSecurity` | 只读参考现行角色／权限／版本、同步、交接、配对和恢复；不要只按旧 PROTOCOL 段落猜测行为 |

共享球队／云端球员 ID 与本机来源 ID 不相同。共享比赛使用云端球队 ID、球员 sourceID；不能按名字或背号合并。`0` 与 `00` 必须区分。本机、共享缓存及原 iOS 文件保持独立；原始历史和纠错不能因为新客户端字段不同而丢失。

## 当前功能缺口与继续顺序

### AN04（未完成）

应用内已能来源保护地导入可重放比赛、查看旧格式／终场、保存原文或受限续记文件、进行 Android 格式 2 备份恢复。已支持普通格式 7 的非终结投球、不挤分保送和非第三出局三振。源文件原始纠错审计仍保留。

仍需：全部命令／三种规则／特殊阵容的多事件对应、挤分与换边、换人／结束／恢复、在原始审计上真正写入纠错／撤回、已有跨端比赛更新的明确合并、原生新比赛正式 iOS 导出、`.bmteam` 完整可编辑映射、旧式个人记录。旧真实样本有 1／2／3／4／6／7，**真实 v5 缺失**。不能把改版本号的文件当作真实 v5，也不能把只读或研究往返称为完整互导。

实际 iOS GameStore／Core Data 导入和重放未执行：Linux 无 Xcode。Swift 模型可读不等于 iOS App 行为相同。`.bmteam` 只读文件库未纳入 `.bmandroid`，目前必须另行导出保管。详细限制以 AN04-IMPORT 和 WIRE 为准。

### AN05（进行中）

F10 基础及名单／成员协议已接“球队 → 球队共享”，含建队／入队／名单／成员／固定多人邀请及恢复卡查看／轮换；按当前 iOS 校正来源披露、范围确认、创建后提示保存卡片及返回层级。固定邀请已有二维码和系统分享代码，界面／系统行为未执行。仍缺完整绑定管理、解散恢复和生命周期全面验证。当前原生建赛仍仅接受本机球队；不能只把 `source` 改成 local 绕过共享保护。

F11 已接 JSON 读取、增量游标、交接／强制接管协议及每场控制队列，详见第三组。产品交接入口仍关闭：接收比赛的完整安卓记分／恢复能力尚缺。继续读取 Swift `SharedMatches.swift` 的 wire 模型及持久队列、现行 `collaboration/games.mjs`／`drafts.mjs`。继续完成球队／比赛绑定、正式发布、离线记分更新队列、formatVersion、撤权／离线冲突草稿和恢复隔离；不要重复已有游标和控制队列。完整比赛写入受 AN04 payload 转换约束；不能上传 Android 内部 Game JSON 冒充 iOS StoredGame。

F12 已实现两阶段凭证轮换、90 天维护、旧密文兼容与 SharedPreferences 写失败后阻断（第四组），以及配对／设备移除／管理恢复协议和持久状态（第五组）。继续：配对／恢复兑换／设备移除的产品界面、配对二维码和系统链接、恢复卡导出及复制的实际系统验证、撤销 Bearer 后明确重新接入、全库 recoveryEpoch 变化后的人工核对和审计界面。旧请求遇 recoveryEpoch／accessEpoch 变化仍阻断，未提供自动解除；先按现行服务实现明确核对，不直接清空标志重放。

### AN06／AN07／AN08

AN06：共享与独立直播、历史观看三种权限、分享、邀请／配对深链接、未安装继续加入、开屏／关于／隐私及系统入口；使用既有入口／协议，不能传邀请秘密到 query／下载 URL。

AN07：按计划 48 条用例逐项登记，不把 planned 当 passed。做可执行的数据／隔离服务／构建回归；设备 UI 按用户最新许可尽力，做不了明确留下。iOS／Android 真双端权限和交接不能用两个 Kotlin 测试客户端替代。

AN08：正式包名、长期证书、保管、覆盖升级、官网候选材料。正式签名、生产配置、网站和下载上线仍另行确认，当前不执行。

## 工具链和复现

本云端：Linux、JDK 25.0.5、SDK platform 37.0、build-tools 37.0.0；AGP 9.4.0、Gradle wrapper 9.6.0、Kotlin 2.2.10、KSP 2.3.12；Node 24.19.0。Robolectric 4.16，API31 Native SQLite。第七组冻结 **226 项（134 core＋44 原服务＋48 app），0 失败／错误／跳过**，App／AndroidTest 构建和 lint 通过，lint 15 警告。默认脚本原为 macOS/zsh；Linux 可显式用 bash 执行。

若同一工作区仍在，工具变量位于 `output/cache/android/tooling/env.sh`，先读取路径并 source（其中只是本机 JDK／SDK 和代理设置，不是用户凭证）。新环境此文件不会随 Git 存在，须配置免费官方 JDK／Android SDK 的 JAVA_HOME、ANDROID_HOME；不要将某台机器的绝对路径写回源码。遵循云端网络代理和 CA，不关闭 TLS 验证、不打印环境秘密。

```sh
# 在仓库根目录；先按实际环境配置 JAVA_HOME / ANDROID_HOME。
bash scripts/android/gradle.sh :core:test :core:teamServiceTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

`:core:teamServiceTest` 需 Node 24，只运行 `scripts/android/an05-local-service.mjs` 启动的 127.0.0.1 动态端口和内存 SQLite；不读生产配置。普通 core 测试不要求 Node。不要启动 `collaboration/npm start`，它会选择持久数据库默认路径；也不要运行任何 deploy／verify-production 脚本。默认共享入口指向既有 HTTPS，**当前明确打开共享页就会连接**，UI 自动化不得直接打开；应给 TeamSharingScreen 注入独立测试 API／存储和 fake transport，详见第六／七组。

Swift 6.2.3 Linux 位于本机 `output/cache/android/tooling/swift/`。显式设置 SWIFTC 后，**严格按顺序**执行：

```sh
# 上述 core/app 测试先完成，冻结它们生成的样本，不同时重跑生产者。
python3 scripts/android/swift-wire-check.py --output output/validation/android/<新的目录> --input-directory output/validation/android/an04-source-roundtrip/kotlin
bash scripts/android/gradle.sh :core:swiftWireTest -PswiftWireDirectory=<上述目录的绝对路径>
```

输出目录必须不存在。返回核对验证实际消费的 payload、源哈希、全部身份、检查点 Data 和 Kotlin 重放。普通测试会重新生成带新 UUID 的文件，因此旧 Swift 回执可能已过期，不能硬用或删除哈希检查。原始 Swift 模型只拷贝到 output 做非 UI Codable 检查，不能修改真实 Swift 文件来让测试通过。

构建目录 `output/cache/android/build/`；JUnit XML 在各模块 `test-results/<task>/`；阶段日志及冻结 XML 在 `output/validation/android/`。失败 XML 可能很大，不要整段输出（含整份比赛）；用 Python 只提取失败测试名、简短消息及差异字段。

## 验证范围、已知失败与未验证项

- AN03 早期设备回归已在历史阶段完成，不能冒充本轮最新包验证。
- 当前云端没有 `/dev/kvm`；AOSP31／37 镜像存在但软件模拟很慢，之前出现系统 ANR。最新轮未安装或启动 UI 测试。设备恢复、真写满、锁屏下 Keystore、真实进程中断和厂商行为仍未验证。
- Room 集成使用 API31 Android JVM＋Native SQLite，未启动 Activity；注入 SQLiteFullException 只证明事务回滚，不能称为实际磁盘已填满。
- 已保留失败记录：旧 UI stale node／系统 ANR、Swift 与样本生产重叠导致校验错批、测试对原球数／revision 的错误假设、跨模块 nullable smart cast 编译错误。修复与最终结果各阶段文档分开登记，不把失败当通过。
- 确认所有 Gradle 任务最终 BUILD SUCCESSFUL、XML 无 failures/errors/skipped，再登记通过。UP-TO-DATE 只代表复用相同输入，不说成重新运行全部测试。每阶段提交推送后用 ls-remote 核对远端提交，保留干净工作树。
- 全局遗留：缺省时区接口差异、ERA 7 局口径、旧协议管理员上限和固定邀请秘密来源描述。只在计划第 14 节记录，等两端上线后统一处理，不在迁移中改全局。

接手结束时更新本文及对应阶段文档，说明实际提交、通过／失败／未测、下一处可修改的入口。不要把 AN04 未完成说成仅剩界面，不要把 AN05 原服务集成说成真实 iOS 联调，也不要把本地构建说成正式发布。
