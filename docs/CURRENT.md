# 棒球比赛记录大师当前入口

更新日期：2026-10-22。本文件是当前状态与资料的唯一导航入口，旧文档内的日期记录保留为历史。

| 项目 | 当前依据 |
| --- | --- |
| 源码版本 | `BaseballMaster.xcodeproj/project.pbxproj`：3.1.0（Build 1） |
| 最近完成开发 | [3.1.0 开发交接](releases/3.1.0/DEVELOPMENT.md)、[验收记录](releases/3.1.0/ACCEPTANCE.md) |
| 发布材料 | [3.1.0 发布交接](releases/3.1.0/REVIEW-HANDOFF.md)、`output/releases/3.1.0/` |
| 已确认上架 | 既有 README 记录用户确认 3.0 已上架；本轮没有连接 App Store Connect 核验新状态 |
| 3.1.0 上传与审核 | 现有交接为材料已准备、尚未上传或提交；后续以用户确认或回执更新 |
| 服务实现 | `live/`、`collaboration/` 随本项目维护，原公网 URL 保持 |
| 公共品牌与官网 | [品牌](../../../brand/README.md)、[官网](../../../website/README.md) |
| 目录整理 | [迁移记录](../../../management/MIGRATION.md)，本次不修改业务代码、版本号或部署 |

需求与后续设计入口：[3.1 规划](plans/3.1-BATTED-BALL.md)、[后续计划](plans/BACKLOG.md)、[产品设计](design/)。

App Store 与版本宣传脚本现位于 `scripts/release/`，海报模板位于 `scripts/templates/`。例如在本项目目录运行 `python3 scripts/release/package-release-3.1.0.py`，输出继续使用本项目原有 `output/` 分类。分享卡片部署脚本位于 `scripts/website/`，执行部署需要用户另行授权。

`website` 和 `docs/brand` 是指向公共目录的相对兼容链接，帮助旧的本地命令与文档读取同一份源稿；品牌和官网的修改在总目录仓库提交。单独克隆本 App 仍可编译原生工程，公共网页和品牌工具使用完整工作区。
