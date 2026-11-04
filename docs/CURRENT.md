# 棒球比赛记录大师 · Android 当前入口

更新日期：2026-11-04。当前版本与交付从本页进入；iOS 状态只看其独立目录，不在这里维护平行进度。

| 项目 | 当前依据 |
| --- | --- |
| 平台工作目录 | 当前 Android 工作根目录为 `/Users/JasonChan/Documents/BaseballMaster.CC/apps/baseball-scorekeeper-android/`，工程入口 [android/README](../android/README.md)；iOS 在独立可见目录 [baseball-scorekeeper](../../baseball-scorekeeper/docs/CURRENT.md)。下列 iOS 行仅是本分支继承基线，当前 iOS 状态以该目录为准 |
| 目录调整交接 | [2026-11-04 平台目录与计划维护](maintenance/2026-11-04-platform-workspaces.md)，含旧路径映射、迁移验收及提交边界 |
| 本平台版本 | Android 0.8.2（Build 10），配置以 `android/app/build.gradle.kts` 为准；继承的 iOS 工程版本不代表 Android 版本 |
| Android 发布功能计划 | [0.8.2 发布功能计划](<plans/0.8.2 发布功能计划.md>)、[0.8.1 发布功能计划](<plans/0.8.1 发布功能计划.md>)；每个平台、每个版本各维护单份计划，开发日志不替代版本计划 |
| Android 0.8.2 开发 | 分支 `codex/android-0.8.2`；恢复新比赛示例名单入口、跑者／出局宽面板三列布局；比赛／逐打席／文字／年度／个人 PDF 按 iOS 统一版式与分页。0.8.2（Build 10）Release 已打包，298 项单元、7 项最终包专项及 13 项 PDF 内容检查通过；已推送 [PR #39](https://github.com/chending0418-ship-it/baseballMaster/pull/39)，已于 2026-11-04 合入 main（`b415236`），无自动合并；完整交付及边界见 [开发记录](releases/android/0.8.2-DEVELOPMENT.md)。官网下载仍为 0.8.1（Build 9） |
| Android 0.8.1 开发 | 分支 `codex/android-0.8.1`，0.8.1（Build 9）最终包已冻结；296 项单元测试、27 项产品专项、26 项旧回归及恢复／折叠验证通过，手机／展开各 47 场景已实拍并人工审查，125 页 PDF／HTML 与逐项验证报告已完成，见 [开发与验收](releases/android/0.8.1-DEVELOPMENT.md)。已推送 [PR #38](https://github.com/chending0418-ship-it/baseballMaster/pull/38)，已由 #39 承接，2026-11-04 关闭／未单独合并；官网下载已更新至 0.8.1（Build 9）；服务器文件哈希通过，按用户要求停止公网完整取回等待 |
| Android 迁移既有基线 | 0.8.0（Build 8）为已验证前版，当前官网下载以 0.8.1 为准；保留“测试中”及 App Store 入口；按 iOS 3.1.1 对照并保留批量名单录入，核心 183 项、App JVM 83 项及 API 31 标准视口 19 项设备流程通过。见 [0.8.0 交接](releases/android/0.8.0-TEST-HANDOFF.md)、[官网下载](https://baseballmaster.cc/scorekeeper.html#download)。分支 `codex/android-0.8.0` 已推送，[PR #36](https://github.com/chending0418-ship-it/baseballMaster/pull/36) 随 #39 进入 main，GitHub 已自动标记合并／关闭。完整互导暂缓，正式赛后云端归档／旧关联更新仍待后续，完整迁移未标完成 |
| 界面与文案 | 用户要求两端统一精简：数据与操作优先，删重复解释，公式／图例按需查看；见 [页面密度与文案](plans/ANDROID-APK.md#17-页面密度与文案2026-11-02-用户反馈)。本轮先修改 Android，iOS 界面未改；用户 2026-11-03 明确两端不用考虑小屏，Android 按标准尺寸布局对照，不保留小屏专用简化版 |
| 本轮 PR 整合 | #34、iOS #37 与 Android #39 已依次合入；Android 298 项、iOS 46 项及后端 107 项回归通过，见 [记录](maintenance/2026-11-04-pr-integration.md)。本轮未部署／分发／上传商店 |
| 公共品牌与官网 | [品牌](../../../brand/README.md)、[官网](../../../website/README.md) |
| 工作区整理 | [2026-11-04 入口与缓存整理](../../../management/2026-11-04-workspace-tidy.md)，源码、固定 APK 与验证证据保留；文档维护见 [PR #40](https://github.com/chending0418-ship-it/baseballMaster/pull/40)，本次未重新打包或分发 |

需求、设计及发布资料见 [文档导航](README.md)。整理前的跨平台混合入口保存在 [原文归档](archive/workspace-2026-11-04/android/CURRENT-before.md)，继承的 iOS 历史不作为本平台当前版本。后续版本计划各在对应分支单份维护，状态随交付更新。
