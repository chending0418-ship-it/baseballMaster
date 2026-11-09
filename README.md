# 棒球比赛记录大师

同一 Git 仓库维护 iOS、Android、文字直播与球队服务，共用 `main`，各平台独立开发和发布。

- [iOS 当前状态](ios/docs/CURRENT.md)与[平台文档](ios/docs/README.md)。Xcode 工程仍在根目录 `BaseballMaster.xcodeproj`。
- [Android 当前状态](android/docs/CURRENT.md)与[平台文档](android/docs/README.md)。Android Studio 打开 `android/`。
- [共享契约与对照样例](contracts/README.md)、[共享文档](docs/README.md)。
- [直播服务](live/README.md)、[球队服务](collaboration/PROTOCOL.md)：源码及运行配置保持原位。
- [阶段一迁移与验证](docs/maintenance/2026-11-05-docs-contracts-stage1.md)、[本地采用与状态分流](docs/maintenance/2026-11-05-stage1-adoption.md)、[分支协作](docs/BRANCHES.md)。

本机继续使用工作区登记的 iOS／服务与 Android 两个可见工作目录；两者都是完整仓库工作树。是否采用新布局以工作区登记和该目录实际文件为准。用户已授权先在本机采用核准的 PR #49 头，不以 GitHub 已合并作为本地采用前提；本地切换不会合并或部署。临时评审 worktree 不成为新的产品入口。外层工作目录名称保持，文档和契约在目录内部归位。

公共品牌、官网和独立语音模块的归属见[工作区导航](../../management/AGENT-GUIDE.md)。本仓库只保存产品运行资源及其来源，不复制公共规范。
