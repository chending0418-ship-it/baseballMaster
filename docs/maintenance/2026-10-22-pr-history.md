# 历史 PR 索引

2026-10-22。用户要求补齐此前未通过 PR 保存的历史交付记录，并规定以后所有改动通过 PR 合入 main，不再直接推送 main。现行流程见 [版本分支说明](../BRANCHES.md#main-合入流程)。

## 补录范围与边界

补录基线为 `df2cb87859cab2243076b035382027303ef671f1`。仓库为完整历史，非浅克隆；按 main 第一父链逐节点建立记录，覆盖 25 个历史节点，以及原合并节点带入的 4 个旁支提交，共 29 个原始提交。3.1.0 的 PR #1 沿用此前补录，本次新增 PR #2–#25。

各补录提交均在本次新建，源码树与对应原始提交完全一致，差异与原提交相对其第一父提交的差异一致。提交日期只表示 Git 中记录的原始提交时间，不能据此推断当时的推送或合入时间。历史 PR 均按已落地关闭，GitHub 状态为 Closed、非 Merged；不补造审核、合并日期或 Apple 发布结论，也不重新合入旧代码。

初始建库没有父提交，PR #2 单独面向新建的空树分支 `codex/pr-history-empty-baseline`，用于展示初始完整源码；其余历史 PR 均以 main 为目标。所有补录分支保留以供追溯。

## 完整节点清单

| 原始提交日期 | 历史交付 | 原始提交 | 补录 PR |
| --- | --- | --- | --- |
| 2026-08-16 | 初始 iOS 记分 MVP | [`cf12bde`](https://github.com/chending0418-ship-it/baseballMaster/commit/cf12bde683518be6406e8c1fd762904ca1a216d2) | [#2](https://github.com/chending0418-ship-it/baseballMaster/pull/2) |
| 2026-09-05 | dev0.2：正式 App 功能更新 | [`b7b658b`](https://github.com/chending0418-ship-it/baseballMaster/commit/b7b658b5efd5b5523ad7a02ae4b20a6762df5b27) | [#3](https://github.com/chending0418-ship-it/baseballMaster/pull/3) |
| 2026-09-17 | 1.5（3）：统计、报告与发布准备 | [`0d1d1f2`](https://github.com/chending0418-ship-it/baseballMaster/commit/0d1d1f27d97f3f0ea9f4011cd4f0225c7b79870f) | [#4](https://github.com/chending0418-ship-it/baseballMaster/pull/4) |
| 2026-09-18 | 1.5：竖版 App Store 截图 | [`354f705`](https://github.com/chending0418-ship-it/baseballMaster/commit/354f7052f995beb69df150f0cd194a0db2991ad1) | [#5](https://github.com/chending0418-ship-it/baseballMaster/pull/5) |
| 2026-09-27 | 1.1.1：功能整合与场地修复 | [`31c2e95`](https://github.com/chending0418-ship-it/baseballMaster/commit/31c2e956526055588d9ed107b6aed0575048db9d) | [#6](https://github.com/chending0418-ship-it/baseballMaster/pull/6) |
| 2026-09-27 | 2.0（1）：商店版本与审核说明 | [`bf8fab0`](https://github.com/chending0418-ship-it/baseballMaster/commit/bf8fab0cc2029c058f31f52def5d1b2109bc8cb8) | [#7](https://github.com/chending0418-ship-it/baseballMaster/pull/7) |
| 2026-09-29 | 2.1：维护基线 | [`b814101`](https://github.com/chending0418-ship-it/baseballMaster/commit/b814101bab9ae277a627f6af49acf743b50e26ce) | [#8](https://github.com/chending0418-ship-it/baseballMaster/pull/8) |
| 2026-09-29 | 2.1：带审计的历史记分纠错 | [`4fa2b2b`](https://github.com/chending0418-ship-it/baseballMaster/commit/4fa2b2b9ee1d8918cf6f47b4619111ff985903e6) | [#9](https://github.com/chending0418-ship-it/baseballMaster/pull/9) |
| 2026-09-29 | 2.1：纠错交付与商店素材 | [`a8e3d68`](https://github.com/chending0418-ship-it/baseballMaster/commit/a8e3d68d54ce4485fb6cd27ec485c7dba799726c) | [#10](https://github.com/chending0418-ship-it/baseballMaster/pull/10) |
| 2026-09-29 | 2.1：交付目录整理与开发流程验证 | [`2536756`](https://github.com/chending0418-ship-it/baseballMaster/commit/25367560241bfd67eed1a33cd47e5652f4f696b7) | [#11](https://github.com/chending0418-ship-it/baseballMaster/pull/11) |
| 2026-09-29 | 2.1 / 2.2：基线合并与后续规划 | [`16be0fd`](https://github.com/chending0418-ship-it/baseballMaster/commit/16be0fdc3fbc7904bae080909a4ae32018c1902d) | [#12](https://github.com/chending0418-ship-it/baseballMaster/pull/12) |
| 2026-09-29 | 2.1：隐私政策与支持页面 | [`526571e`](https://github.com/chending0418-ship-it/baseballMaster/commit/526571eb09eea313a6aa98eabd1076276d598407) | [#13](https://github.com/chending0418-ship-it/baseballMaster/pull/13) |
| 2026-09-30 | 2.1：文字直播部署与公网联调 | [`fda40e8`](https://github.com/chending0418-ship-it/baseballMaster/commit/fda40e82a12d31d688dc7939d8e882aec4ff53fd) | [#14](https://github.com/chending0418-ship-it/baseballMaster/pull/14) |
| 2026-09-30 | 2.1：官网、审核文案与宣传素材 | [`1b7190f`](https://github.com/chending0418-ship-it/baseballMaster/commit/1b7190f7fe1aee76000fee36928ce29a04872d04) | [#15](https://github.com/chending0418-ship-it/baseballMaster/pull/15) |
| 2026-09-30 | 2.1：宣传图截图与文案对齐 | [`8b7c99d`](https://github.com/chending0418-ship-it/baseballMaster/commit/8b7c99da0ef17109741737efaf7c92a579e8ff78) | [#16](https://github.com/chending0418-ship-it/baseballMaster/pull/16) |
| 2026-10-03 | 2.2：记分优化与紧凑直播界面 | [`433373b`](https://github.com/chending0418-ship-it/baseballMaster/commit/433373b4b52a814e0698dd3d4a597f4836bae946) | [#17](https://github.com/chending0418-ship-it/baseballMaster/pull/17) |
| 2026-10-03 | 2.2：发布交接与项目历史收拢 | [`c50cf6d`](https://github.com/chending0418-ship-it/baseballMaster/commit/c50cf6d6080abd16f3bdf6819746bb4e38d0e800) | [#18](https://github.com/chending0418-ship-it/baseballMaster/pull/18) |
| 2026-10-03 | 2.2：官网更新与宣传海报 | [`d3d4928`](https://github.com/chending0418-ship-it/baseballMaster/commit/d3d4928d00642e74c8e8d7ed8ae493a082f248db) | [#19](https://github.com/chending0418-ship-it/baseballMaster/pull/19) |
| 2026-10-08 | 2.3：慢垒、赛前阵容与投手出局 | [`8e54f23`](https://github.com/chending0418-ship-it/baseballMaster/commit/8e54f23e8c0f75905abefe3e28b0fe07a7c7d613) | [#20](https://github.com/chending0418-ship-it/baseballMaster/pull/20) |
| 2026-10-08 | 2.3：main 交接与最终发布材料包 | [`615ba4e`](https://github.com/chending0418-ship-it/baseballMaster/commit/615ba4ec33aacf24d2b5a544eaa4ec012b40ce7b) | [#21](https://github.com/chending0418-ship-it/baseballMaster/pull/21) |
| 2026-10-08 | 官网：3.0 球队协作预告 | [`9491175`](https://github.com/chending0418-ship-it/baseballMaster/commit/9491175819048f0dddd64972f7a49aec43189dd0) | [#22](https://github.com/chending0418-ship-it/baseballMaster/pull/22) |
| 2026-10-09 | 2.3：补齐 6.1 / 6.3 英寸商店截图 | [`1dbe11e`](https://github.com/chending0418-ship-it/baseballMaster/commit/1dbe11e8aa7f42655c44683662ed33e9b07130f1) | [#23](https://github.com/chending0418-ship-it/baseballMaster/pull/23) |
| 2026-10-09 | 2.3：商店截图补充棒球记分与统计 | [`b9b898d`](https://github.com/chending0418-ship-it/baseballMaster/commit/b9b898db979aa3142040dbf584017a164d74cc91) | [#24](https://github.com/chending0418-ship-it/baseballMaster/pull/24) |
| 2026-10-20 | 3.0 / 3.0.1：球队协作与修复基线 | [`82c23b4`](https://github.com/chending0418-ship-it/baseballMaster/commit/82c23b402b5a0bb2c4b37d0b26d2a3dad85a9fdb) | [#25](https://github.com/chending0418-ship-it/baseballMaster/pull/25) |
| 2026-10-20 | 3.1.0：基础版 / Pro 版记录与发布准备 | [`df2cb87`](https://github.com/chending0418-ship-it/baseballMaster/commit/df2cb87859cab2243076b035382027303ef671f1) | [#1](https://github.com/chending0418-ship-it/baseballMaster/pull/1) |

1.5 → 1.1.1 → 2.0 的顺序沿用实际 Git 历史与原始版本命名，不按版本数字重排。3.0／3.0.1 的成果在原历史中合为 `82c23b4` 一个提交，PR #25 整体记录，不伪造两个独立原提交。

## 旁支覆盖

原合并提交 `16be0fd` 的第一父提交为 `2536756`，第二父提交为 `14bf026`。下列 4 个旁支提交已在 [PR #12](https://github.com/chending0418-ship-it/baseballMaster/pull/12) 列出，最终合并差异以该 PR 的 Files changed 为准：

- [`6079d4c`](https://github.com/chending0418-ship-it/baseballMaster/commit/6079d4c9bdcf71e79a54d90c09c167546ee81a5d)：chore: initialize 2.2 development workspace and slow-pitch plan。
- [`90ce911`](https://github.com/chending0418-ship-it/baseballMaster/commit/90ce911989e93b7b09c647f4de314c679c768ebf)：feat: add audited historical scorekeeping corrections for 2.1。
- [`b392553`](https://github.com/chending0418-ship-it/baseballMaster/commit/b3925530199954a78ac997d4ad7328f02e16b361)：docs: preserve 2.2 roadmap and record correction sync。
- [`14bf026`](https://github.com/chending0418-ship-it/baseballMaster/commit/14bf026df3b5316978d28dc28874761343468db6)：docs: preserve 2.2 roadmap and release planning before baseline sync。

## 本次核对

- 24 个新增历史 PR 已逐项读取 GitHub 状态，确认全部关闭，原始差异文件数、目标分支、补录分支及提交 SHA 一致。
- 补录前后原 main 保持在 `df2cb87`，未重写原历史、未部署或更改 Apple 状态。流程约定和本索引作为新的文档改动，另走正常 PR 合入。
- 25 个第一父链节点与 4 个旁支提交的并集等于补录基线全部 29 个祖先提交，无遗漏。
- 本次只验证 Git 差异与记录关系，没有重新运行所有旧版本测试；各版历史测试与发布边界见相应 PR 的交接文档链接。

本机详细回执保存在 `output/validation/maintenance/2026-10-22-pr-history/`，不将生成记录或缓存提交到源码仓库。
