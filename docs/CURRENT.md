# 棒球比赛记录大师当前入口

更新日期：2026-11-02。本文件是当前状态与资料的唯一导航入口，旧文档内的日期记录保留为历史。

| 项目 | 当前依据 |
| --- | --- |
| 源码版本 | `BaseballMaster.xcodeproj/project.pbxproj`：3.1.1（Build 1） |
| 当前开发 | `codex/dev-3.1.1`；本机完整记录、赛后确认上传、Pro 完整结果目录及完赛返回修复已完成；iOS 26.2 数据 74 项、UI 16 项通过，Debug／Release 构建通过；见 [3.1.1 开发记录](releases/3.1.1/DEVELOPMENT.md)、[配置说明](releases/3.1.1/CONFIGURABLE-LAUNCH.md) |
| 上版发布功能计划 | [3.1.0 发布功能计划](<../3.1.0 发布功能计划.md>)，保留上版范围与发布事项 |
| 上版开发与验收 | [3.1.0 开发交接](releases/3.1.0/DEVELOPMENT.md)、[验收记录](releases/3.1.0/ACCEPTANCE.md) |
| 发布材料 | [3.1.0 发布交接](releases/3.1.0/REVIEW-HANDOFF.md)、`output/releases/3.1.0/` |
| 已确认上架 | 既有 README 记录用户确认 3.0 已上架；本轮没有连接 App Store Connect 核验新状态 |
| 3.1.0 上传与审核 | 现有交接为材料已准备、尚未上传或提交；后续以用户确认或回执更新 |
| 原设备连接问题 | 2026-11-02 用户确认改动后无需继续排查，已关闭；两场旧比赛原件核对仍保留在 I311-05 |
| 3.1.1 发布边界 | 开屏服务已增量部署到现有香港服务（固定 URL）；App 未正式归档、上传或提交审核，备案与上海迁移仍等待 |
| 服务实现 | `live/`、`collaboration/` 随本项目维护，原公网 URL 保持 |
| 上海迁移准备 | 隔离验收、正式证书、上海 COS 备份恢复及正反向迁移演练已通过；正式候选配置已校验但未安装。用户决定等备案通过后再开始真实数据迁移，今晚 23:00 不执行维护；生产数据未复制、域名未切换、香港未操作，见 [迁移方案与阶段记录](plans/ICP-MIGRATION.md#14-真实数据迁移前的准备交接2026-10-23) |
| 公共品牌与官网 | [品牌](../../../brand/README.md)、[官网](../../../website/README.md) |
| 目录整理历史 | [迁移记录](../../../management/MIGRATION.md)，当次未修改业务代码、版本号或部署 |

需求与后续设计入口：[3.1.1 开机图](design/launch-screen/3.1.1/README.md)、[3.1.0 发布功能计划](<../3.1.0 发布功能计划.md>)、[3.1 原始详细规划](plans/3.1-BATTED-BALL.md)、[后续计划](plans/BACKLOG.md)、[产品设计](design)。

App Store 与版本宣传脚本现位于 `scripts/release/`，海报模板位于 `scripts/templates/`。例如在本项目目录运行 `python3 scripts/release/package-release-3.1.0.py`，输出继续使用本项目原有 `output/` 分类。分享卡片部署脚本位于 `scripts/website/`，执行部署需要用户另行授权。

公共品牌与官网分别直接使用工作区 brand/、website/ 的真实目录；App 内不设置目录替身。修改公共资料在总品牌仓库提交。完整资料位置见 [总导航](../../../management/AGENT-GUIDE.md)。

当前微信群宣传源稿：docs/design/marketing/3.1.0/wechat-2026-10-22/source/。在 App 根目录运行 node docs/design/marketing/3.1.0/wechat-2026-10-22/source/render.cjs；导出仍写入本 App 的 output/marketing/3.1.0/wechat-2026-10-22/。历史输出中的可编辑脚本与 SVG 已保存到 docs/archive/output-sources/，按其 manifest 追溯，不能将历史设计当作当前交互基准。
