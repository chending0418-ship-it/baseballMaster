# 棒球比赛记录大师当前入口

更新日期：2026-11-04。本文件是当前状态与资料的唯一导航入口，旧文档内的日期记录保留为历史。

| 项目 | 当前依据 |
| --- | --- |
| 源码版本 | `BaseballMaster.xcodeproj/project.pbxproj`：3.1.1（Build 1） |
| iOS 开发基线 | `codex/dev-3.1.1`；固定 BM／App 图标、后台配置背景与文案的开屏已接入，见 [3.1.1 开发记录](releases/3.1.1/DEVELOPMENT.md)、[配置说明](releases/3.1.1/CONFIGURABLE-LAUNCH.md) |
| Android 0.8.2 开发 | 分支 `codex/android-0.8.2`；恢复新比赛示例名单入口、跑者／出局宽面板三列布局；比赛／逐打席／文字／年度／个人 PDF 按 iOS 统一版式与分页。用户已授权打包 0.8.2（Build 10）、推送与创建 PR，不合并；打包验证及边界见 [开发记录](releases/android/0.8.2-DEVELOPMENT.md)。官网下载仍为 0.8.1（Build 9） |
| Android 0.8.1 开发 | 分支 `codex/android-0.8.1`，0.8.1（Build 9）最终包已冻结；296 项单元测试、27 项产品专项、26 项旧回归及恢复／折叠验证通过，手机／展开各 47 场景已实拍并人工审查，125 页 PDF／HTML 与逐项验证报告已完成，见 [开发与验收](releases/android/0.8.1-DEVELOPMENT.md)。已推送 [PR #38](https://github.com/chending0418-ship-it/baseballMaster/pull/38)，目标 main，保持未合并／无自动合并；官网下载已更新至 0.8.1（Build 9）；服务器文件哈希通过，按用户要求停止公网完整取回等待 |
| Android 迁移既有基线 | 0.8.0（Build 8）为已验证前版，当前官网下载以 0.8.1 为准；保留“测试中”及 App Store 入口；按 iOS 3.1.1 对照并保留批量名单录入，核心 183 项、App JVM 83 项及 API 31 标准视口 19 项设备流程通过。见 [0.8.0 交接](releases/android/0.8.0-TEST-HANDOFF.md)、[官网下载](https://baseballmaster.cc/scorekeeper.html#download)。分支 `codex/android-0.8.0` 已推送，[PR #36](https://github.com/chending0418-ship-it/baseballMaster/pull/36) 为 Draft，不合并 main。完整互导暂缓，正式赛后云端归档／旧关联更新仍待后续，完整迁移未标完成 |
| 界面与文案 | 用户要求两端统一精简：数据与操作优先，删重复解释，公式／图例按需查看；见 [页面密度与文案](plans/ANDROID-APK.md#17-页面密度与文案2026-11-02-用户反馈)。本轮先修改 Android，iOS 界面未改；用户 2026-11-03 明确两端不用考虑小屏，Android 按标准尺寸布局对照，不保留小屏专用简化版 |
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
