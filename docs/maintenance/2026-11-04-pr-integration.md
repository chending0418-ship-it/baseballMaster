# 2026-11-04 双平台 PR 整合与验证

用户明确授权顺序：整理 #34 → 合入 #37 → #39 同步最新 main、解决冲突并完成必要验证 → 合入 #39 → 关闭被承接的旧 PR。全部 main 改动经 GitHub PR，不直接推送 main。

- #34 文档整理后合并为 `e58ea84`，保留原调查并明确 iOS 实现承接、真实旧比赛和迁移遗留。
- #37 合并为 `510021b`，iOS 3.1.2（Build 1）；仅同步方案和索引，没有新增业务修改。并发工作区整理草稿未纳入本轮。
- #39 的整合父基线为 Android `8cc5a40` 与 main `510021b`。保留双方测试，按实际平台目录描述规则，统一分支、当前导航和后续计划。#38 唯一独立文档提交中的 0.8.1 计划与 #39 完全一致，导航语义也已保留；#35／#36 全部提交原已被 #39 继承。
- #33 是旧共享副本开赛方案，不合入代码；本机记分和赛后上传已由 #34／#37 承接。旧 PR 关闭依据为替代关系，不将关闭写成旧方案验收或直接 PR 合并。

## 本轮实际验证

| 验证 | 结果与本机证据 |
| --- | --- |
| Android 单元 | Core 205／205，App JVM 93／93，失败／错误／跳过均为 0；Android `output/validation/android/0.8.2/pr-integration-2026-11-04/unit-results.json` |
| Android 构建／lint | Release 构建通过；lintRelease 0 Error／Fatal、11 Warning；同目录 `gradle.log`、`static-checks.json` |
| iOS 合并测试编译 | 在 iOS 目录用合并后的 GameStoreTests 执行 build-for-testing 成功，生产源码与 main 父基线一致；iOS `output/validation/3.1.2/pr-integration-2026-11-04/build-for-testing.log` |
| iOS 专项执行 | iPhone 16e／iOS 26.2，46 项通过，0 失败；包括 Android 分支新增的 15 项参考／数据测试、投手额度 30 项、赛后自动提示 1 项；同目录 `integration.xcresult`、`test.log`、`test-selection.json` |
| 共享后端 | `node --test collaboration/test/*.test.mjs` 107 项通过，0 失败／取消／跳过；iOS 同目录 `backend-tests.log` |
| 合并完整性 | iOS 现有测试与 Android 新增测试方法均保留，合并后 363 个测试方法名称无重复；生产树 android/app、android/core 与 Android 父提交一致（android/README.md 仅更新合入说明），BaseballMaster、工程、collaboration、live 与 main 父提交一致；Git diff 空白与残留冲突标记检查通过 |
| 固定安装包 | Android 0.8.2 原交付 APK SHA-256 仍为 `2c19ff1e3f5d3d1eedbd497005fee8d8178173e69eaa47f8e00aef3ca05be934`，未替换交付文件或重新分发 |

本轮未重新执行完整设备／UI 矩阵，不把既有图审和设备验收冒充重跑。两平台版本、包名、签名和商店状态不变；官网仍为 Android 0.8.1 测试包。未部署网站／业务服务、迁移生产、补传真实比赛、正式归档或上传商店。备案、真实旧记录、生产批量名单接口和迁移继续按版本计划等待。

#39 的实际合入提交及各旧 PR 最终关闭状态以 [GitHub PR #39](https://github.com/chending0418-ship-it/baseballMaster/pull/39) 和相关 PR 回执为准；不在候选提交中预填尚未产生的合并 SHA。
