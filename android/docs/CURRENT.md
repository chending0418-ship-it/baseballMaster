# 棒球比赛记录大师 · Android 当前入口

更新于 2026-11-17。本文件是 Android 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 官网分发 | **0.8.3（Build 15）**，2026-11-16 已发布。产品页首屏和下载区均指向新版签名 APK。[官网入口](https://baseballmaster.cc/scorekeeper#download) · [签名 APK](https://baseballmaster.cc/assets/android/0.8.3-build15/BaseballMaster-Android-0.8.3-build15.apk) |
| 0.8.5 开发 | **开发及逐项原生对照中**。39 组双端原生对照已整理。Build 23 手机及短窗实测两好球触击界外入口在两个结束操作正中。[0.8.5 发布功能计划](<plans/0.8.5 发布功能计划.md>) 逐项登记范围和 iOS 3.1.3 验收。前期 Build 16 签名候选及覆盖安装回执保留在计划的历史记录。[草稿 PR #69](https://github.com/chending0418-ship-it/baseballMaster/pull/69) |
| 0.8.3 维护版 | 共享球队统计、官网更新提醒、可读的 PDF 文件名、共享历史打席标签和紧凑分类切换已交付。覆盖升级和发布证据见 [0.8.3 发布功能计划](<plans/0.8.3 发布功能计划.md>) |
| 1.0.0 发布 | 用户已于 2026-11-16 确认暂缓。1.0.x 现有实现及历史发布记录按 [PR #58](https://github.com/chending0418-ship-it/baseballMaster/pull/58)、[PR #60](https://github.com/chending0418-ship-it/baseballMaster/pull/60) 追溯 |
| 工程及产物 | Android Studio 打开 `android/`。包名与签名沿用官网 0.8.2，验证与交付产物归本工作树 `output/` |
| 平台边界 | 完整跨端互导及正式赛后归档上传仍由 [Android 总计划](plans/ANDROID-APK.md) 跟踪。0.8.3 的正式共享统计使用已有球队服务读取接口 |
| iOS／服务 | [iOS CURRENT](../../ios/docs/CURRENT.md)，共享服务由 iOS／服务工作区维护 |

阶段一文档与契约归位已通过 [PR #49](https://github.com/chending0418-ship-it/baseballMaster/pull/49) 采用。历史归档记录保留在 [维护说明](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)。
