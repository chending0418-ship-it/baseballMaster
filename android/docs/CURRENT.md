# 棒球比赛记录大师 · Android 当前入口

更新：2026-11-10。本文件是 Android 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 已合并版本 | Android 1.0.1（Build 12），[PR #60](https://github.com/chending0418-ship-it/baseballMaster/pull/60) 已合入 `main`。见[版本计划](<plans/1.0.1 发布功能计划.md>) |
| 修复候选 | Android 1.0.2（Build 13），保留语音识别的跑者去向，缩短击球确认流程。见[版本计划](<plans/1.0.2 发布功能计划.md>)和 [Gradle 配置](../app/build.gradle.kts) |
| 1.0.0 计划 | [发布功能计划](<plans/1.0.0 发布功能计划.md>)：投手限额、品牌 1.3、原生语音、球队固定转播及名单批量提交已实现；模拟器专项与候选验收证据见计划 |
| 官网分发 | **1.0.1（Build 12）**，2026-11-10 已发布[签名 APK](https://baseballmaster.cc/assets/android/1.0.1-build12/BaseballMaster-Android-1.0.1-build12.apk)，[下载入口](https://baseballmaster.cc/scorekeeper#download)与[隐私说明](https://baseballmaster.cc/privacy.html)已同步 |
| 工程及证据 | Android Studio 打开 `android/`；1.0.0 验证在本工作树 `output/validation/1.0.0/`；包名及签名配置沿用 |
| 平台边界 | 完整跨端互导、正式赛后归档／旧关联更新及实体厂商设备等继续按计划跟踪，不因样例移入共享目录就标记完成；未获得应用市场上传确认 |
| iOS／服务 | [iOS CURRENT](../../ios/docs/CURRENT.md)，共享服务继续从 iOS／服务入口维护 |
| 发布阶段 | 上海球队服务已发布 PR #52 的转播交接与恢复修复，公网核验通过。签名 APK 已打包；真实腾讯 ASR、混元和覆盖升级的模拟器证据已记录。 |

1.0.2 在 Android 模拟器验收，测试证据位于本工作树 `output/validation/1.0.2/`。
