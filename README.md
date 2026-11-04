# 棒球比赛记录大师

棒球与慢垒比赛记录、统计及球队协作。当前范围、版本、验证与发布状态统一从 [本工作目录 CURRENT](docs/CURRENT.md) 进入。

- [记录大师 · iOS](../baseball-scorekeeper/docs/CURRENT.md)
- [记录大师 · Android](../baseball-scorekeeper-android/docs/CURRENT.md)
- [需求、设计、验收与发布资料](docs/README.md)
- [开发规则](AGENTS.md) · [Git 分支与 PR](docs/BRANCHES.md)
- [工作区总导航](../../management/AGENT-GUIDE.md) · [品牌规范](../../brand/README.md)

iOS 固定在 `apps/baseball-scorekeeper/`，工程为 `BaseballMaster.xcodeproj`，源码及测试位于 BaseballMaster、BaseballMasterTests、BaseballMasterUITests。文字直播与球队服务在该目录的 live、collaboration；配置及部署记录从本版计划进入。

Android 固定在 `apps/baseball-scorekeeper-android/`，工程及构建说明见 [Android README](../baseball-scorekeeper-android/android/README.md)，平台工具在该目录 scripts/android。两端共用 Git 仓库、不同分支；按真实工作目录选择平台，分支保留的跨平台源码用于继承与对照。

本机输出沿用 [output 规则](output/README.md)。可编辑源稿受 Git 管理；缓存、签名、安装包和验证原件按原用途留存。构建或打包成功不代表已分发或上架。

整理前完整产品说明、构建步骤与历史任务在 [平台原文归档](docs/archive/workspace-2026-11-04/)；旧日期记录仅作追溯。此 README 不复制版本进度。
