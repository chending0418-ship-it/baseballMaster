# 记录大师工作规则

先读[工作区规则](../../AGENTS.md)与[总导航](../../management/AGENT-GUIDE.md)，核对真实 Git 根、分支和未提交内容，再进入对应平台 CURRENT。

- 本分支采用阶段一目录约定：iOS 唯一入口为 `ios/docs/CURRENT.md`，Android 为 `android/docs/CURRENT.md`。根 `docs/README.md` 只导航。本分支中这些平台路径替代上层规则的旧 `docs/CURRENT.md` 和根 `docs/plans/` 约定；尚未同步本 PR 的旧分支仍使用自己的旧入口。
- iOS 的每个开发、维护和发布版本在 `ios/docs/plans/<版本> 发布功能计划.md` 维护单份计划；Android 使用 `android/docs/plans/`。既有根目录 `3.1.0 发布功能计划.md` 原位保留。开发日志不替代计划。
- iOS／服务入口保持 `apps/baseball-scorekeeper/`，Android 保持 `apps/baseball-scorekeeper-android/`；分别在所属工作树修改。隔离的迁移评审 worktree 只承担本次跨目录整理。
- 新任务从最新 `main` 建立分支；平台、服务及契约改动通过 PR 合回共同 `main`。可使用 `codex/ios/`、`codex/android/`、`codex/services/`、`codex/contracts/`、`codex/repo/` 前缀。只有用户明确授权才合并，不直接推 main、不强推、不改写历史。
- 本阶段 iOS 工程、Swift／Kotlin 业务源码、包名、版本与签名保持原状；服务仍由 iOS／服务入口维护 `live/` 和 `collaboration/`，不移动、不部署。
- `contracts/fixtures/` 保存既有合成样例及原 manifest。Swift／Kotlin 实现分别维护，不宣称已有共用原生核心，也不把迁移或样例测试当作完整互导验收。
- 功能、验证与交付变化后同步所属版本计划和平台 CURRENT。发布状态以用户确认或实际回执为准，实施、打包、分发、服务部署、上传、审核与上架分别记录。
- 每个工作树的产物保留在其真实 `output/`；归档、签名、私有原件及测试证据继续被忽略。可编辑源稿纳入 Git，不建立旧路径软链接或目录替身，不覆盖其他聊天的改动。
- 历史原文与冻结产物保留日期及内容；迁移映射见 `docs/maintenance/2026-11-05-stage1-path-map.json`。品牌与宣传沿用工作区品牌规范。
- 本次只整理文档、契约及必要的工具路径，创建独立 PR；不合并、不部署、不上传、不触发长时性能或 soak 测试。
