# 棒球比赛记录大师当前入口

更新日期：2026-10-27。本文件是当前状态与资料的唯一导航入口，旧文档内的日期记录保留为历史。

| 项目 | 当前依据 |
| --- | --- |
| 源码版本 | `BaseballMaster.xcodeproj/project.pbxproj`：3.1.1（Build 1） |
| iOS 开发基线 | `codex/dev-3.1.1`；固定 BM／App 图标、后台配置背景与文案的开屏已接入，见 [3.1.1 开发记录](releases/3.1.1/DEVELOPMENT.md)、[配置说明](releases/3.1.1/CONFIGURABLE-LAUNCH.md) |
| Android 迁移 | `codex/android-migration`，从 `40df3d2` 建立隔离 worktree；按 iOS 3.1.1 全功能迁移；AN01／AN02 已完成，环境、原生工程及开发预览 APK 0.1.0-preview.2（2）已产出，Android 12 无 GMS／360 dp 与 Android 17／约 412 dp 验证通过，见 [阶段交接](releases/android/AN01-AN02.md)；AN03–AN08 仍见 [单份计划](plans/ANDROID-APK.md)，共享互通尚待 AN05，官网未发布下载 |
| 上版发布功能计划 | [3.1.0 发布功能计划](<../3.1.0 发布功能计划.md>)，保留上版范围与发布事项 |
| 上版开发与验收 | [3.1.0 开发交接](releases/3.1.0/DEVELOPMENT.md)、[验收记录](releases/3.1.0/ACCEPTANCE.md) |
| 发布材料 | [3.1.0 发布交接](releases/3.1.0/REVIEW-HANDOFF.md)、`output/releases/3.1.0/` |
| 已确认上架 | 既有 README 记录用户确认 3.0 已上架；本轮没有连接 App Store Connect 核验新状态 |
| 3.1.0 上传与审核 | 现有交接为材料已准备、尚未上传或提交；后续以用户确认或回执更新 |
| 3.1.1 发布边界 | 本功能按要求仅开发与本地验证，未部署、归档、上传或提交审核；服务器迁移独立进行 |
| 服务实现 | `live/`、`collaboration/` 随本项目维护，原公网 URL 保持 |
| 公共品牌与官网 | [品牌](../../../brand/README.md)、[官网](../../../website/README.md) |
| 目录整理历史 | [迁移记录](../../../management/MIGRATION.md)，当次未修改业务代码、版本号或部署 |

需求与后续设计入口：[3.1.1 开机图](design/launch-screen/3.1.1/README.md)、[3.1.0 发布功能计划](<../3.1.0 发布功能计划.md>)、[3.1 原始详细规划](plans/3.1-BATTED-BALL.md)、[后续计划](plans/BACKLOG.md)、[产品设计](design)。

App Store 与版本宣传脚本现位于 `scripts/release/`，海报模板位于 `scripts/templates/`。例如在本项目目录运行 `python3 scripts/release/package-release-3.1.0.py`，输出继续使用本项目原有 `output/` 分类。分享卡片部署脚本位于 `scripts/website/`，执行部署需要用户另行授权。

公共品牌与官网分别直接使用工作区 brand/、website/ 的真实目录；App 内不设置目录替身。修改公共资料在总品牌仓库提交。完整资料位置见 [总导航](../../../management/AGENT-GUIDE.md)。

当前微信群宣传源稿：docs/design/marketing/3.1.0/wechat-2026-10-22/source/。在 App 根目录运行 node docs/design/marketing/3.1.0/wechat-2026-10-22/source/render.cjs；导出仍写入本 App 的 output/marketing/3.1.0/wechat-2026-10-22/。历史输出中的可编辑脚本与 SVG 已保存到 docs/archive/output-sources/，按其 manifest 追溯，不能将历史设计当作当前交互基准。
