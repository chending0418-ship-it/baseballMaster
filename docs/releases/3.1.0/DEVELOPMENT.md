# 3.1.0 开发交接

2026-10-20，分支 `codex/dev-3.1.0`，以已推送 `main` 的 `82c23b4` 为开发基线，工程 **3.1.0（Build 1）**。唯一项目目录仍为 `/Users/JasonChan/Documents/BaseballMaster`。

## 已实现

- 开局与赛前准备选择基础版／Pro 版，一局练习提供两种体验；旧比赛保持原流程。
- 按最新 V4 demo 实现常用／全部／按过程入口，七种球型与落点、第一位处理人、传接路线、独立失误责任在同一球场记录；安打也必记守备或显式待补。
- 统一核对每名跑者的去向、原因和附加处理，支持沿用路线、复合安打出局、第三出局时序；“补充这球”替换原操作而不重复记球。
- 观察随同一球保存、撤销／重做、重启、历史纠错、备份及共享；取消草稿不改比分和统计。
- 点击双方当前打者查看本场／年度表现及击球图，按模式、球员身份、比赛年度和共享正式版本汇总，区分位置覆盖。
- 直播显示数字防守路线及当时守位、姓名；现场球场显示姓名和背号。
- 文字记录、既有 PDF／直播投影保留观察文字；完整备份和球队档案保留结构化观察。未扩大为网页年度门户或网页／PDF 击球图。

交互及数据边界见 [实现说明](../../design/3.1/IMPLEMENTATION.md)，原任务编号沿用 [唯一规划](../../plans/3.1-BATTED-BALL.md)。验证见 [ACCEPTANCE.md](ACCEPTANCE.md)。

## 关键文件

- `BaseballMaster/SituationRecording.swift`：V4 主流程、同场守备、跑者核对、补充本球与练习内存更正。
- `BaseballMaster/BattedBall.swift`：观察、击球图、打者查看、年度查询及历史观察编辑。
- `Models.swift`、`GameStore.swift`、`HistoryCorrection.swift`：事务、历史重放与路线快照。
- `ScorekeepingViews.swift`、`SetupAndRosterViews.swift`、`ProfileViews.swift`：正式流程与两种练习入口。
- `LocalBackup.swift`、`PersistenceController.swift`、`SharedMatches.swift`：备份 7／语义 8／共享及新版球队档案格式 2。
- `collaboration/games.mjs`、`archive.mjs`：新字段验证、旧端写入及档案兼容保护；`server.mjs` 公布兼容能力。

## Git 与发布材料

2026-10-20 本轮完成源码保存、合并并推送 main，实际提交及远端核对回执见 `output/validation/3.1.0/release-2026-10-20/git-sync.json`。开发分支为 `codex/dev-3.1.0`，开发基线为 `82c23b4`；最终工程为 3.1.0（Build 1）。

签名归档、商店五份 TXT、隐私填写依据、上传步骤与三套各六张截图已整理，交付入口见 [发布交接](REVIEW-HANDOFF.md)。签名归档和审核 ZIP 保存在 `output/releases/3.1.0/`，不提交构建缓存或私有数据。

## 发布边界

用户随后要求先部署直播相关内容：共享服务兼容更新已于 2026-10-20 部署至 `3.1.0-20261020`；独立直播和观看页无需改动。详见 [部署记录](../../../collaboration/deploy/2026-10-20-live-compatibility-3.1.0.md)。签名归档和 App Store 素材已准备；Apple 上传、服务端验证和审核提交尚未执行。3.1.0 不自动关闭旧单次邀请，仍等待用户此前约定的通知。

新版 App 共享写入使用格式 2，生产共享服务现已兼容。防守路线姓名由 App 生成，服务先行部署不会让旧 App 自动产生该文字；新版 App 发布前仍需完成客户端发布准备。旧客户端可继续操作未升级的格式 1 场次；已被新版写入的场次，旧客户端写回将被服务拒绝，云端新资料保留。接手记分也须升级兼容客户端。
