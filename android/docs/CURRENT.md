# 棒球比赛记录大师 · Android 当前入口

更新于 2026-11-24。本文件是 Android 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 0.8.6 本地候选 | **Build 33**。Android 可管阵容、记局面或接任局面员。已在模拟器实录 iOS 管阵容／Android 记局面、双 Android 换投与交接，以及 iOS → Android → iOS 逐球历史往返。覆盖 Build 32 后保留原有比赛和球队。[版本计划与验收](<plans/0.8.6 发布功能计划.md#android-build33>) · [签名 APK](../../output/releases/android/0.8.6/build33/BaseballMaster-Android-0.8.6-build33.apk) · [微信单图《分工协作记录》](../../output/marketing/0.8.6/wechat/2026-11-24/build33/Android-0.8.6-分工协作记录.png) · [iOS 与 Android 共用微信宣传图](../../output/marketing/ios-3.1.4-android-0.8.6/wechat/iOS-Android-分工协作记录.png) |
| 官网分发 | **0.8.5（Build 31）**，北京时间 2026-11-18 22:12 发布。首屏与下载区均已切换，公网整包校验通过。官网 Build 29 联网打开首页已实测提示升级并保留球队数据。[官网下载](https://baseballmaster.cc/scorekeeper#download) · [签名 APK](https://baseballmaster.cc/assets/android/0.8.5-build31/BaseballMaster-Android-0.8.5-build31.apk) · [SHA-256](https://baseballmaster.cc/assets/android/0.8.5-build31/SHA256SUMS.txt) |
| 0.8.5 验收 | 239 项已逐条登记证据、适用构建及分期。Build 30 完成球队赛程双向联调，Build 31 完成在线比赛分析及四路球队固定 OBS 转播源联调。347 项 JVM 测试通过。按用户要求使用模拟器。[版本计划与实拍](<plans/0.8.5 发布功能计划.md>) · [PR #69](https://github.com/chending0418-ship-it/baseballMaster/pull/69) |
| 0.8.3 维护版 | 共享球队统计、官网更新提醒、可读的 PDF 文件名、共享历史打席标签和紧凑分类切换已交付。覆盖升级和发布证据见 [0.8.3 发布功能计划](<plans/0.8.3 发布功能计划.md>) |
| 1.0.0 发布 | 用户已于 2026-11-16 确认暂缓。1.0.x 现有实现及历史发布记录按 [PR #58](https://github.com/chending0418-ship-it/baseballMaster/pull/58)、[PR #60](https://github.com/chending0418-ship-it/baseballMaster/pull/60) 追溯 |
| 工程及产物 | Android Studio 打开 `android/`。包名与签名沿用官网 0.8.2，验证与交付产物归本工作树 `output/` |
| 0.8.5 Build 30 球队赛程验收 | 球队赛程对照 iOS 3.1.3 Build 6，两端各自实际发布、改期、取消，并在另一端用普通成员核对。核心和应用 338 项通过。最终 APK、逐项截图与同库流畅度回执见 [Build 30 版本计划](<plans/0.8.5 发布功能计划.md#android-build30>)。 |
| 0.8.5 Build 31 官网构建 | 新增在线分析三种范围和四路球队固定 OBS 源。关联球队开赛自动启用空闲来源，已有比赛需确认切换。347 项 JVM 测试通过，模拟器覆盖 Build 30 保留 48 场比赛和 2 支球队。[验收明细及双端实拍](<plans/0.8.5 发布功能计划.md#android-build31>)。 |
| iOS 3.1.3 功能对齐 | [Android 三项服务接入清单](<plans/0.8.5 发布功能计划.md#ios313-services>)：球队赛程已在 Build 30 完成双向联调，Build 31 新增单场、全年与勾选场次在线分析，以及球队四路固定 OBS 浏览器源。[Build 31 联调及截图](<plans/0.8.5 发布功能计划.md#android-build31>)。 |
| 平台边界 | 完整跨端互导及正式赛后归档上传仍由 [Android 总计划](plans/ANDROID-APK.md) 跟踪。0.8.3 的正式共享统计使用已有球队服务读取接口 |
| iOS／服务 | [iOS CURRENT](../../ios/docs/CURRENT.md)，共享服务由 iOS／服务工作区维护 |

阶段一文档与契约归位已通过 [PR #49](https://github.com/chending0418-ship-it/baseballMaster/pull/49) 采用。历史归档记录保留在 [维护说明](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)。
