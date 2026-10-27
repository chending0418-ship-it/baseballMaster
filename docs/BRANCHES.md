# Android 迁移分支（2026-10-27）

用户要求先重新规划全功能 Android 迁移，并切新分支。已从 `codex/dev-3.1.1` 的 `40df3d2` 建立 `codex/android-migration`，使用隔离 worktree `/Users/JasonChan/.codex/worktrees/android-migration/baseball-scorekeeper`。原目录及其未提交备案迁移资料保留；这不是另一个独立仓库或磁盘分区。建立分支时仅更新计划；用户随后授权 AN01／AN02，现已安装工具链、建立工程、产出预览 APK 并完成两套模拟器验证，见 [阶段交接](releases/android/AN01-AN02.md)。iOS 版本和生产部署保持原状态。

本条取代历史中的 Android 暂缓状态和早期单目录开发安排；原记录继续按日期保留。唯一计划为 [Android 全功能迁移与 APK 分发](plans/ANDROID-APK.md)，当前状态仍从 [CURRENT](CURRENT.md) 进入。Android 基线含尚未进入 main 的前置提交，后续先按各自 PR 处理前置改动，再更新 Android 分支、审查差异并创建 Android PR。本轮未推送或合并。

# 3.1.1 开发分支（2026-10-22）

用户已确认产品深绿 BM 开机图并授权接入 3.1.1；当前分支为 `codex/dev-3.1.1`，从当前工作区资料整理基线 `3eb09df` 建立，工程为 3.1.1（Build 1）。实现、验证与发布边界见 [3.1.1 开发记录](releases/3.1.1/DEVELOPMENT.md)，当前入口仍为 [CURRENT](CURRENT.md)。本轮不直接改 main，原迁移和版本历史记录保留如下。

# 本次迁移分支（2026-10-22）

目录整理在 codex/workspace-migration-20261022 进行，所有原分支和 main 提交保留。最新品牌规范已先保存为提交 6b0fc97。当前资料与工作区路径见 [CURRENT.md](CURRENT.md) 及 [迁移记录](../../../management/MIGRATION.md)。本次未直接修改 main。

# 版本分支与工作目录

更新日期：2026-10-22。按用户要求顺序开发，唯一项目目录已去掉版本号，现为 `/Users/JasonChan/Documents/BaseballMaster.CC/apps/baseball-scorekeeper`。

## main 合入流程

2026-10-22 用户明确要求：以后合并 main 都走 PR，不再直接推送 main。此规则适用于代码、修复、文档和发布材料。

1. 从最新 main 建立独立分支，完成改动及适用验证。
2. 只推送工作分支，创建目标为 main 的 GitHub PR，说明改动、验证结果和发布边界。
3. 按用户授权通过 PR 合并，再同步本地 main。不得通过本地合并后直接推送、强推或直接写入 main 的 API 绕过 PR。

截至本次补录，原 main 的 25 个第一父链节点及其中 4 个旁支提交均已有 PR 追溯，见 [完整历史 PR 索引](maintenance/2026-10-22-pr-history.md)。PR #2–#25 按已落地关闭；3.1.0 的 PR #1 随后按用户明确要求重新打开，并于 2026-10-22 正式合并为 `9584a63`。不伪造过去的 GitHub 合并状态、不重写既有历史。此后的变更使用正常 PR 流程，不继续用历史补录代替合入。

## 当前状态

| 内容 | 分支／目录 | 状态 |
| --- | --- | --- |
| 2.2 发布基线 | `d3d4928`（main 历史），`/Users/JasonChan/Documents/BaseballMaster.CC/apps/baseball-scorekeeper` | 2.2（Build 1），功能与回归、Web 部署、归档及商店材料完成；App 上传和提交按既有交接记录 |
| 2.2 开发记录 | `codex/dev-2.2` | 已推送并快进合入 main，保留历史分支 |
| 2.1 历史维护 | `codex/release-2.1` | 完整本地／远端分支保留，不反向混入 2.2 功能 |
| 2.3 交付基线 | `8e54f23`（main 历史），根目录 [单份计划](<plans/2.3 发布功能计划.md>) | 工程 2.3（Build 1），209 项单元／集成与 72 项唯一界面验收完成，官网／兼容直播已上线，资料与归档齐备；`8e54f23` 已推送，Apple 发布由用户执行，小组件已延期 |
| 3.1.0 交付基线 | main 历史中的 `df2cb87` | 2026-10-20 已合入并推送，工程 3.1.0（Build 1）；3.0／3.0.1 前置基线为 `82c23b4` |
| 当前工作区 | `main`，同一项目目录 | 以 `82c23b4` 开发 3.1.0（Build 1），见 [开发交接](releases/3.1.0/DEVELOPMENT.md)；3.1.0 完成合并推送 main，签名归档和审核资料见 [发布交接](releases/3.1.0/REVIEW-HANDOFF.md)；共享服务兼容已部署，App 未上传审核 |
| 3.0.1 修复 | [单份计划](<plans/3.0.1 发布功能计划.md>) | 已获开发授权；[验收](releases/3.0.1/ACCEPTANCE.md) 记录本机／共享隔离及其他修复，兼容服务及邀请页已部署，签名归档／审核材料已准备，App 未上传提交 |

2.2 发布代码提交为 `433373b`，已推送到 main 与开发分支；目录及交接收尾另随 Git 保存。App 工程、源码名称和 Bundle ID 保持不变，使用 `BaseballMaster.xcodeproj`。

## 3.0 开发阶段

2026-10-11 用户明确开始开发。当前已从 `main` 建立 `codex/dev-3.0`，工程 3.0（Build 1），仍使用同一项目目录；此前未提交规划和 2.3 材料保留。本条取代下方早期只规划安排。当前实现与证据见 [开发进度](releases/3.0/DEVELOPMENT.md)，没有推送或合并；2026-10-13 网站与服务已部署，最新交付见 [发布交接](releases/3.0/REVIEW-HANDOFF.md)。

2026-10-08 用户确认将球队协作定为 3.0，并要求本轮只做规划和 TODO。文档位于 [3.0 球队协作规划](plans/3.0-TEAM-COLLABORATION.md)，包含免注册身份、自有服务、队内权限、跨设备同步、单场分享与恢复；不使用 iCloud／CloudKit。

以下为规划阶段历史，当前状态以上文 2026-10-11 开发记录为准。

此前规划编写时使用 `codex/dev-2.3`；2.3 已合入后，当时目录 `/Users/JasonChan/Documents/BaseballMaster.CC/apps/baseball-scorekeeper` 位于 `main`。2026-10-09 用户要求开始 3.0，先交付页面截图和使用流程 PDF，大致确认后实际开发。[页面与流程提案 v2](design/3.0/README.md) 已按反馈修订，主要流程已确认，D01 继续细化。本阶段不创建或切换分支、不创建工作区或版本专属项目目录，不修改工程 2.3（Build 1）；业务开发阶段再安排分支和版本。3.0 队伍权限及平台无关协议仍需定稿；2.3 的实际发布状态见其交接。小组件不因 3.0 规划自动纳入交付。

2026-10-10 最新要求：比赛时间补填与赛后更正只做评估并放入 TODO；用户明确 3.0 尚未开始开发，实际开发等待另行指示。此次仅更新规划及文档入口，仍在当前 `main`，不修改业务代码、工程版本或部署。

## 3.1 击球记录改版规划阶段

2026-10-13 用户确认击球记录改版需要做，正式纳入 [3.1 单份规划](plans/3.1-BATTED-BALL.md)，覆盖球型／去向、打者本场／年度表现与击球图、常用局面入口。Android 记分客户端、真实跨平台验证、APK 分发与更新暂缓，后续版本待定；原方案保留在 [Android 规划](plans/ANDROID-APK.md)。本条取代 10 月 8 日的 Android 3.1 安排。

本轮仅维护规划与入口，当前仍为 `codex/dev-3.0`、工程 3.0（Build 1）。3.1 正式业务开发、分支与工程版本在开始实施时安排；沿用唯一项目目录。

## 单目录收拢已执行

- 原 2.1 项目目录已在收拢时清理。保留仓库的 `.git` 现为完整独立目录，不再指向旧目录中的 worktree 管理文件；随后按用户要求将 `BaseballMaster-2.2` 原地改名为 `BaseballMaster`，新名称对应本次保留的完整仓库。
- 完整提交对象、全部分支／远端引用及当前索引已核对，`git fsck --full` 通过。原 Git 公共元数据完整备份保存在 `output/backups/maintenance/2026-10-03/git-common-before-consolidation.tar.gz`。
- 原签名归档、测试包及原始日志、宣传图片、部署包和源码备份共 28,242 个文件（1,660,112,130 字节）逐文件 SHA-256 验证后迁入相应 `output/` 分类。只有可重建缓存和 `.DS_Store` 不迁移；Xcode 本机界面状态单独保留。
- 旧材料若与当前同路径不同内容，保留到维护备份的 `legacy-output/`，不覆盖。完整映射与校验在 `output/backups/maintenance/2026-10-03/material-migration.json`。
- 早期根部生成的纠错同步日志／测试包及构建缓存已归入 validation／cache，映射保存在同一备份目录的 `generated-moves.json`。
- 迁移后 Release 再次编译通过，然后才删除原项目目录；源码及交付资料保留。详见 [维护记录](maintenance/2026-10-03-single-project.md)。

## 后续开发

开始修改前检查当前分支和工作目录。在这个目录内沿用已交付的 `main`，2.3 原本地开发分支保留，工程已为 2.3（Build 1），不建立专属文件夹。目录改名记录见 [维护记录](maintenance/2026-10-03-project-folder-rename.md)。2.2 实际上传／审核／发布以 [交接文档](releases/2.2/REVIEW-HANDOFF.md) 的证据为准。

## 历史基线

2026-09-29 建立 2.1／2.2 工作区；9 月 30 日完成 2.1 文字直播部署和审核准备，用户于 10 月 1 日确认 2.1 已上线。2.2 从 main 的 `8b7c99d` 继承 2.1。10 月 2 日用户将慢垒及小组件整体延期至单份 2.3 计划，并明确顺序开发及单目录要求。10 月 3 日直播 UI、正式服务与发布准备完成，随后收拢目录。历史归档和验证内容不改写成新的发布结论。
