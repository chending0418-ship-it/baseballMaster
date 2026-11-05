# 棒球比赛记录大师 · Android 当前入口

更新：2026-11-05。本文件是 Android 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 当前版本 | Android 0.8.2（Build 10），以 [Gradle 配置](../app/build.gradle.kts)为准 |
| 官网分发 | **用户于 2026-11-05 确认：0.8.2 可从官网下载**；本轮匿名 HTTPS 只读检查产品页两个下载入口均指向 0.8.2 Build 10 APK，HEAD 返回 200，未重建或重新分发 |
| 分发证据 | [PR #46](https://github.com/chending0418-ship-it/baseballMaster/pull/46)保存既有官网下载与完整哈希验证记录，仍保持开放；本次不合并该 PR |
| 本版计划 | [0.8.2 发布功能计划](<plans/0.8.2 发布功能计划.md>)、[0.8.1 发布功能计划](<plans/0.8.1 发布功能计划.md>) |
| 实现与验收 | [0.8.2 开发记录](releases/0.8.2-DEVELOPMENT.md)、[平台迁移及遗留](plans/ANDROID-APK.md)；历史记录的“官网仍为 0.8.1”为当时阶段状态 |
| 工程及产物 | Android Studio 打开 `android/`；APK 与验证仍在本工作树 `output/`，签名、包名和版本不变 |
| 平台边界 | 完整跨端互导、正式赛后归档／旧关联更新及实体厂商设备等继续按计划跟踪，不因样例移入共享目录就标记完成；未获得应用市场上传确认 |
| iOS／服务 | [iOS CURRENT](../../ios/docs/CURRENT.md)，共享服务继续从 iOS／服务入口维护 |
| 当前维护 | [文档与契约阶段一](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)，原 Android 工作目录仍在原分支，待 PR 合入后另行同步 |
