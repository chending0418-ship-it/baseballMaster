# BaseballMaster iOS App

正式版改造进度见 [`TODO.md`](TODO.md)，发布验证与 TestFlight 操作见 [`RELEASE_REVIEW.md`](RELEASE_REVIEW.md)。

一个面向非专业家长记分员的中文棒球记分 SwiftUI App。球队、球员、对手名单、未来比赛、进行中比赛、历史比赛和球员逐场统计均使用本地持久化，持续验证低门槛的“创建 → 记录 → 结果 → 统计”流程。

## 已实现

- 使用真实本地数据的比赛首页，区分未来比赛、进行中比赛和最近比赛
- 球队、球员、对手球队与对手名单增删改查
- 本队比赛与双方均非本队的观赛记录
- 当前或未来比赛创建；未来安排可只保存球队与时间，也可立即完成赛前设置
- 新建比赛后制作日式排版的比赛宣传海报：放大主客队名，展示时间、场地、局数，编辑标题和通知备注并分享高清 PNG
- 开赛前可补充双方名单，并确认局数、守备人数、时间、球数和投手局数限制
- 本队先发棒次拖动、本场守位、沿用历史阵容与替补球员处理；观赛可分别确认双方阵容
- 球队、球员、比赛与球员统计保存到 App 沙盒内的 Core Data SQLite，不依赖账号、网络或云端
- 球员中英文名、多个背号、赛季切换、比赛筛选和近 3/10 场表现汇总
- 统计总览按球队、赛季汇总已结束比赛，展示胜负平、得失分及打击／投手／守备数据
- 支持按姓名、英文名、多个背号搜索球员，切换统计指标升降序，并筛选有记录球员
- 从总览进入球员详情会保留赛季和分类；逐场筛选同时作用于打击、投手和守备统计
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
- 球队赛季、球员个人和单场战报均可导出 A4 横版 PDF，支持 App 内缩放预览、保存、打印及系统分享
- 报告包含打击／投手／守备统计、合计、统计口径与页码；长名单自动续表，延长赛逐局比分分组展示
- 整场逐打席和单个打席 PDF 速报：详细版包含前后垒况与事件过程；文字简版以 A4 竖版连续排列每个打席的文字描述
- 完整本地备份导出与恢复，保留最近 3 份自动备份；数据损坏时保护原文件并进入恢复页面
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
- 赛季统计直接读取 Core Data 中已结束的本队比赛快照，排除观赛、未来及进行中比赛；删除比赛或赛后复核后自动更新，不重复累计当前打开的比赛。
- 球队统计保留已删除或被替换球员的历史贡献。旧版独立个人打击记录继续显示在球员详情中，不混入缺少完整比赛信息的球队总览。
- 比率由累计原始数据计算；ERA 沿用 7 局口径，局数按出局数合计，分母为零的比率显示“—”。
- `GameStore` 和 `GameState` 使用结构化事件、稳定的打席归属、中文记录与前后局面快照驱动正式现场记分。
- `--backup-preview`、`--poster-preview` 可打开备份和海报评审页面，均不读写正式数据库。
- `--statistics-preview`、`--statistics-empty-preview` 可打开统计模块的跨赛季示例与空状态评审页面，示例仅使用内存 Store。
- `--profile-preview`、`--rules-preview`、`--pdf-preview`、`--practice-preview` 等启动参数可直接打开 04 模块评审页面。
- `--home-preview`、`--home-with-games-preview`、`--setup-preview`、`--schedule-preview`、`--pregame-preview`、`--opponents-preview`、`--scorekeeping-preview`、`--scorekeeping-timed-preview`、`--boxscore-preview`、`--lineup-preview`、`--observed-lineup-preview`、`--outcome-preview`、`--outcome-cause-preview`、`--runner-preview` 启动参数可直接打开比赛相关评审页面。

## PDF 报告

- 统计总览右上角“导出 PDF”：导出当前球队、当前赛季的全部已结束比赛及全体球员，不受搜索、排序或有记录筛选影响。
- 球员详情右上角“导出 PDF”：导出当前赛季与已勾选比赛的个人汇总及逐场明细。旧版个人记录缺失的投手／守备数据保留为 `-`。
- 比赛结果页“预览与分享 Box Score”：导出逐局比分、得分过程、双方打击／投手／守备与球队合计。进行中的比赛和待确认记录会明确标注。
- 比赛结果“记录”页：每个打席都可选择“详细版”或“文字简版”；页面底部“导出与分享”可导出整场两种版本。详细版保留局面图与事件表；文字简版只保留局次、打者、打席文字与待确认／未完成标注，多打席连续排版，长描述自动续页。“全部导出并分享”包含两版逐打席 PDF、Box Score 和完整 TXT。
- 报告使用可搜索的中文文本与固定浅色打印配色。每次导出生成独立文件，避免后续导出覆盖已打开的预览或分享。
- `output/pdf/team-season-report.pdf`、`output/pdf/player-report.pdf`、`output/pdf/game-box-score.pdf` 为内存示例比赛生成的展示样例，不包含真实用户数据。
- `testGeneratePresentationPDFSamplesFromRecordedGames` 可重新生成三份样例，文件位于测试 App 沙盒的 `Documents/PDFValidation/`。
- 文字简版样例为 `output/pdf/play-by-play-text-report.pdf`，同样使用内存示例比赛生成。

## 比赛宣传海报

- 最简未来比赛保存成功后，选择“制作宣传海报”；已有未来比赛从详情页进入，当前比赛从现场记分右上角图片按钮进入。
- 主客双方和开赛时间读取已保存的比赛。宣传标题、场地和通知备注只用于当前海报，不改写比赛信息。
- 采用米白、黑、朱红的日式赛事排版，队名占据主要版面，输出 1080 × 1440 PNG。系统分享可以保存到“文件”或交给已安装的分享 App；发送对象由用户选择。
- 展示样例：`output/images/match-notice-poster.png`；逐打席样例：`output/pdf/play-by-play-report.pdf`。均使用内存示例数据。

## 本地数据与备份

- 本版本不连接业务服务器，不注册账号，不启用 CloudKit、广告或分析 SDK。球队、球员、比赛、统计均在本机；外观等偏好保存在本机 UserDefaults。无需部署服务器。
- “我的 → 本地数据 → 备份与恢复”可导出 `.bmbackup`，在另一台设备用同一入口选择文件恢复。恢复替换本机全部数据，校验后须二次确认，不做合并。
- 备份包含球队、对手名单、赛季、历史个人记录、全部比赛和逐球事件；不包含 App 外观偏好。使用 SHA-256 检查文件完整性，文件本身未加密。
- 启动和退到后台时自动保存最近 3 份完整备份。自动备份在 App 沙盒中，卸载 App 会一并删除，应定期把备份导出到 App 之外。
- 数据库无法完整读取时停止正常编辑、保留原文件并显示恢复入口；不静默创建新数据覆盖原记录。恢复前先建立并验证新数据库，旧 SQLite、WAL、SHM 与外部存储另存于 `Recovery/`。
- 数据库版本为 V2，备份格式版本为 V1。支持现有 JSON／V1 SQLite 的迁移，拒绝无法识别的未来版本；未来新增 Schema 仍需要对应的迁移实现与测试。

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
- `Screenshots/12-statistics.png`
