# 棒球比赛记录大师 · Android 当前入口

更新于 2026-11-18。本文件是 Android 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 官网分发 | **0.8.5（Build 29）**，2026-11-18 已发布。已实测 0.8.3 首页提示升级。产品页首屏和下载区均指向新版签名 APK。[官网入口](https://baseballmaster.cc/scorekeeper#download) · [签名 APK](https://baseballmaster.cc/assets/android/0.8.5-build29/BaseballMaster-Android-0.8.5-build29.apk) |
| 0.8.5 验收 | **Build 29 已官网分发，239 项均已登记对照结果及未覆盖条件**。本轮修复教练投触身说明、首球撤销后的先发投手登板局和损坏 PDF 的导出入口。模拟器完成官网 Build 15 原地覆盖和续记，核心及应用短测试 330 项通过，双端 900 球比分与记分状态一致。按用户要求本轮只做模拟器验收。[0.8.5 发布功能计划](<plans/0.8.5 发布功能计划.md>) 记录双端实拍、各构建与剩余条件。[草稿 PR #69](https://github.com/chending0418-ship-it/baseballMaster/pull/69) |
| 0.8.3 维护版 | 共享球队统计、官网更新提醒、可读的 PDF 文件名、共享历史打席标签和紧凑分类切换已交付。覆盖升级和发布证据见 [0.8.3 发布功能计划](<plans/0.8.3 发布功能计划.md>) |
| 1.0.0 发布 | 用户已于 2026-11-16 确认暂缓。1.0.x 现有实现及历史发布记录按 [PR #58](https://github.com/chending0418-ship-it/baseballMaster/pull/58)、[PR #60](https://github.com/chending0418-ship-it/baseballMaster/pull/60) 追溯 |
| 工程及产物 | Android Studio 打开 `android/`。包名与签名沿用官网 0.8.2，验证与交付产物归本工作树 `output/` |
| 0.8.5 Build 30 本地签名候选 | 球队赛程对照 iOS 3.1.3 Build 6，两端各自实际发布、改期、取消，并在另一端用普通成员核对。核心和应用 338 项通过。最终 APK、逐项截图与同库流畅度回执见 [Build 30 版本计划](<plans/0.8.5 发布功能计划.md#android-build30>)。 |
| iOS 3.1.3 功能对齐 | [Android 三项服务接入清单](<plans/0.8.5 发布功能计划.md#ios313-services>)：球队赛程已在 Build 30 完成双向联调，在线比赛分析与球队固定 OBS 转播源已列入待接入项。 |
| 平台边界 | 完整跨端互导及正式赛后归档上传仍由 [Android 总计划](plans/ANDROID-APK.md) 跟踪。0.8.3 的正式共享统计使用已有球队服务读取接口 |
| iOS／服务 | [iOS CURRENT](../../ios/docs/CURRENT.md)，共享服务由 iOS／服务工作区维护 |

阶段一文档与契约归位已通过 [PR #49](https://github.com/chending0418-ship-it/baseballMaster/pull/49) 采用。历史归档记录保留在 [维护说明](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)。
