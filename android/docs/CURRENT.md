# 棒球比赛记录大师 · Android 当前入口

更新：2026-11-09。本文件是 Android 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 当前开发 | Android 1.0.0（Build 11），以 [Gradle 配置](../app/build.gradle.kts)为准；分支 `codex/android/1.0.0` |
| 1.0.0 计划 | [发布功能计划](<plans/1.0.0 发布功能计划.md>)：投手限额、品牌 1.3、原生语音、球队固定转播及名单批量提交已实现，已通过模拟器专项验证；候选版回归待做 |
| 官网分发 | **0.8.2（Build 10）**，用户于 2026-11-05 确认可从官网下载。见 [PR #46](https://github.com/chending0418-ship-it/baseballMaster/pull/46)、[0.8.2 发布功能计划](<plans/0.8.2 发布功能计划.md>) |
| 工程及证据 | Android Studio 打开 `android/`；1.0.0 验证在本工作树 `output/validation/1.0.0/`；包名及签名配置沿用 |
| 平台边界 | 完整跨端互导、正式赛后归档／旧关联更新及实体厂商设备等继续按计划跟踪，不因样例移入共享目录就标记完成；未获得应用市场上传确认 |
| iOS／服务 | [iOS CURRENT](../../ios/docs/CURRENT.md)，共享服务继续从 iOS／服务入口维护 |
| 发布阶段 | 1.0.0 1.0.0（Build 11）签名候选已打包，真实腾讯 ASR、混元与升级回归证据已记录。等待上海球队服务上线 PR #52 的转播交接与恢复修复。[草稿 PR #58](https://github.com/chending0418-ship-it/baseballMaster/pull/58) |
