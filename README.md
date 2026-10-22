# BaseballMaster iOS App

正式版改造进度见 [`TODO.md`](TODO.md)。

一个面向非专业家长记分员的中文棒球记分 SwiftUI App。球队、球员、对手名单、未来比赛、进行中比赛、历史比赛和球员逐场统计均使用本地持久化，持续验证低门槛的“创建 → 记录 → 结果 → 统计”流程。

## 已实现

- 使用真实本地数据的比赛首页，区分未来比赛、进行中比赛和最近比赛
- 球队、球员、对手球队与对手名单增删改查
- 本队比赛与双方均非本队的观赛记录
- 当前或未来比赛创建；未来安排可只保存球队与时间，也可立即完成赛前设置
- 开赛前可补充双方名单，并确认局数、守备人数、时间、球数和投手局数限制
- 本队先发棒次拖动、本场守位、沿用历史阵容与替补球员处理；观赛可分别确认双方阵容
- 球队、球员、比赛与球员统计保存到 App 沙盒内的 Core Data SQLite，不依赖账号、网络或云端
- 球员中英文名、多个背号、赛季切换、比赛筛选和近 3/10 场表现汇总
- “我的”提供本地数据概览、外观设置、辅助提示和版本信息
- 棒球规则按基础、进攻、守备、投手、违规和记录分类说明
- 内置 229 页《棒球规则 2022 版》，使用 PDFKit 离线阅读，支持文字查找、定位和高亮
- 独立的一局练习模式，支持步骤提示和重置，不影响正式比赛数据
- 逐球坏球/看振/挥空/界外记录，自动四坏、三振和三出局换边
- 现场记分主页固定一屏；低频的跑垒、判罚、修正和换人收纳在二级点击面板
- `Play Ball` 开始比赛计时，支持正计时/剩余时间切换、暂停继续、限时提醒和本地恢复
- 完整球场视图，显示外野围墙、界线、内野、九名守备与垒上跑者
- 观察式击球录入：先选打者最终位置，再由系统建议安打、出局、失误或野手选择
- 触身球、故意保送、三振未接住、盗垒、跑垒出局、暴投/捕逸和投手犯规
- 无键盘现场修正，可恢复比分、局数、B/S/O、打者、投手和垒况
- 真实换投、代打、代跑、任意守备换人和双重换人；DH/大谷条款会实际改变打序、防守名单与换投结果
- TB 可在现场进入延长局时启用，支持按赛事规程放置一至三名跑者，自动跑者身份可跨代跑和换投正确计算自责分
- 复杂裁判判罚可覆写球状态、打者与全部跑者位置、上一比赛结果，以及打数、安打、失误、打点和自责分责任
- 待确认事件可赛后补充正式结果和责任人；保存前校验后续局面并同步修正技术统计
- 按预设局数处理主队领先免打下半局、延长局和再见得分
- 比赛结果显示双方逐局比分及打击/投手/守备 Box Score；中文记录按打席合并该打者期间的全部事件
- 完整中文比赛记录可导出为 TXT，专业 Box Score 可导出为 PDF，并通过 iOS 系统分享发送到微信等兼容 App
- iPhone 小屏适配、深色模式、VoiceOver 标签和大点击区域
- SwiftUI Preview、单元测试和 UI 流程测试

## 运行

1. 使用 Xcode 26 或兼容版本打开 `BaseballMaster.xcodeproj`。
2. 选择任意 iPhone 模拟器。
3. 运行 `BaseballMaster` scheme。

命令行测试：

```sh
xcodebuild \
  -project BaseballMaster.xcodeproj \
  -scheme BaseballMaster \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  test
```

## 实现说明

- 最低部署版本为 iOS 16。
- 球队、球员、对手名单、赛季、比赛进度、历史比赛和球员逐场统计使用 Core Data + 本地 SQLite 保存；不使用 CloudKit。
- 旧版 `roster-data.json` 会在首次启动时自动导入数据库，成功后保留为 `roster-data.migrated.json` 迁移备份。
- Core Data Schema 已升级至 V2，旧 V1 SQLite 数据库会在启动时迁移。
- 现场比赛每次记分后都会保存，App 中断或重新启动后可以继续；未来比赛需要从首页手动进入赛前准备并开始。
- 未来比赛默认允许最简安排；名单、规则、棒次和守位既可安排时提前设置，也可在开赛前补充或修改。
- 时间、单投手球数和单投手局数限制只做 App 内提醒，不会自动结束比赛或自动换投。
- 练习模式使用独立内存 Store，练习记录不会写入正式数据库。
- `GameStore` 和 `GameState` 使用结构化事件、稳定的打席归属、中文记录与前后局面快照驱动正式现场记分。
- `--profile-preview`、`--rules-preview`、`--pdf-preview`、`--practice-preview` 等启动参数可直接打开 04 模块评审页面。
- `--home-preview`、`--home-with-games-preview`、`--setup-preview`、`--schedule-preview`、`--pregame-preview`、`--opponents-preview`、`--scorekeeping-preview`、`--scorekeeping-timed-preview`、`--boxscore-preview`、`--lineup-preview`、`--observed-lineup-preview`、`--outcome-preview`、`--outcome-cause-preview`、`--runner-preview` 启动参数可直接打开比赛相关评审页面。

## 页面截图

- `Screenshots/01-home.png`
- `Screenshots/02-scorekeeping.png`
- `Screenshots/03-boxscore.png`
- `Screenshots/04-outcomes.png`
- `Screenshots/05-runners.png`
- `Screenshots/06-scorekeeping-compact.png`
- `Screenshots/07-home-dark.png`
- `Screenshots/08-scorekeeping-full-field.png`
- `Screenshots/09-scorekeeping-full-field-compact.png`
- `Screenshots/10-novice-scorekeeping.png`
- `Screenshots/11-observation-first.png`
