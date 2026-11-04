# 记录大师工作规则

先读 [工作区规则](../../AGENTS.md)、[资料导航](../../management/AGENT-GUIDE.md)，再从 [CURRENT](docs/CURRENT.md) 进入本工作目录当前版本。本文只保留有效规则；[整理前原文](docs/archive/workspace-2026-11-04/) 用于追溯，不授权旧任务。

- 工作平台由真实目录决定。iOS 固定在 `apps/baseball-scorekeeper/`，Android 固定在 `apps/baseball-scorekeeper-android/`，Gradle 工程在后者 `android/`。两端共用 Git 仓库、不同分支；修改前确认 Git 根目录、分支及未提交内容，不覆盖其他聊天的工作。
- 保持工程名、Bundle ID／包名和版本；整理目录不改业务、不改历史、不合并 main。跨平台参考源码继续保留，不误在本目录开展另一平台的业务开发。
- 主代理可按任务需要自主委派独立子任务，负责文件边界、审查及最终验证，避免同时修改同一内容。
- iOS／Android 每个开发、维护、发布版本各在对应分支维护单份 `docs/plans/<版本> 发布功能计划.md`；正文注明平台、版本／Build、增量与继承范围、实际验证及证据、遗留转版、打包／分发／服务／商店状态、分支及 PR。既有根目录 3.1.0 计划原位保留，不重复建立。
- 功能、修复、验证、范围或交付变化后更新本版计划及 `docs/CURRENT.md`，随所属分支提交推送、更新 PR。两平台版本号分别管理；开发日志不替代发布功能计划。
- 所有进入 main 的改动先通过独立分支和目标 main 的 PR，说明验证与发布边界；仅在用户明确授权后通过 PR 合并。不直接推 main、不强推、不用 API 绕过 PR。历史说明见 [BRANCHES](docs/BRANCHES.md) 与 [PR 索引](docs/maintenance/2026-10-22-pr-history.md)。
- 实现、构建、Git 推送、服务部署、App 上传、审核与上架分别记录；发布状态以用户确认或回执为准。本次整理不授权部署、上传或合并。
- 品牌及宣传先读 [品牌入口](../../brand/README.md) 和对应品牌／宣传规范，现行完整规范由该入口导航。已确认的教练口袋中文名、产品 Logo、BM 缝线、母品牌藏青及英文字体组合继续沿用；常规迭代不重新设计品牌。
- 输出只写本目录真实 `output/`，沿用 releases、validation、cache、marketing、deployments、samples、backups 分类；不在根层散放生成物，不新建目录替身。可编辑源稿保存在 Git 内的 docs/design 或 scripts；保留发布归档、失败及最终验证、私有原件。
- 公共品牌和官网属于总仓库；iOS 的 live／collaboration 继续由 iOS 目录管理。资料归属与旧路径映射见工作区导航及 management/MIGRATION.md。
