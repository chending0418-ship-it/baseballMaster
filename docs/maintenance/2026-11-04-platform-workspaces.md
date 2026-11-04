# iOS／Android 可见工作目录及版本计划维护

执行日期：2026-11-04。用户要求 iOS 与 Android 分别使用可见目录，并将目录调整、版本发布功能计划随分支提交推送、更新 PR，保持不合并。本文记录产品仓库内可追溯的目录安排和验收；全局目录权威仍是品牌工作区 `management/projects.json`、`management/AGENT-GUIDE.md` 与 `management/MIGRATION.md`，不另建平行总导航。

## 当前平台入口

以下路径相对 `/Users/JasonChan/Documents/BaseballMaster.CC/`。

| 平台／用途 | 可见目录 | 分支／Git 关系 |
| --- | --- | --- |
| iOS 当前开发及所属服务 | `apps/baseball-scorekeeper/` | `codex/dev-3.1.2`，主 Git 工作目录；iOS 3.1.2 继续使用已有 PR #37 |
| Android 当前开发与交付 | `apps/baseball-scorekeeper-android/`，Gradle 工程在其 `android/` | `codex/android-0.8.2`，同一 Git 仓库的独立 worktree；PR #39 |
| Android 历史迁移现场 | Android 目录内 `output/backups/worktrees/android-migration/` | detached `e04afb3`，只作历史备份 |

当前 Android 原路径为 `/Users/JasonChan/.codex/worktrees/android-remaining/baseball-scorekeeper/`；旧迁移现场原路径为 `/Users/JasonChan/.codex/worktrees/android-migration/baseball-scorekeeper/`。两者使用 `git worktree move` 整体搬移，旧隐藏位置不建立软链接。分支、提交历史、包名、版本及既有安装包保持；跨平台参考源码可能随 Git 分支存在，不代表允许在另一平台目录修改业务。

源码、APK、原始验证材料随 Android 工作现场管理，当前 Gradle 依赖缓存位于 Android `output/cache/android/gradle-user/`；AVD 现用路径已更新。路径相关旧项目／构建缓存、iOS 中仅剩缓存的 `android/` 目录等保存在 Android `output/backups/tooling/2026-11-04-visible/`，新工作目录重新生成缓存。历史 PDF、日志与交付清单中的旧路径不批量改写，按此映射追溯。

## 已完成验证

- 新路径重新编译及 Core **205／205**、App JVM **93／93** 通过；没有为目录整理重建发布 APK或更改工程版本。
- 原 API31 折叠模拟器从新路径冷启动成功，可打开原已安装的 0.8.2（10），原测试数据保留。
- 路径、Git 关联、当前文档链接、原 APK 哈希等 **23 项检查通过**。iOS 同时有另一开发聊天修改五个 Swift 文件，因此其冻结源码哈希检查未记作通过；本迁移没有回退、暂存或写入这些业务文件。
- 固定 0.8.2 APK SHA-256 仍为 `2c19ff1e3f5d3d1eedbd497005fee8d8178173e69eaa47f8e00aef3ca05be934`；打包源码为 `a306b7b`，后续目录／文档提交不重新打包。
- 本机迁移原始证据在品牌工作区 `local/validation/organization/2026-11-04-android-visible/`；上述测试属于此前迁移验收，本次文档整理不冒充再次执行。

## 版本发布功能计划规则

iOS 和 Android 各自的每个开发、维护和发布版本均须维护单份 `docs/plans/<版本> 发布功能计划.md`，注明平台和 Build。既有 3.1.0 根目录计划原位保留，不复制。计划须覆盖增量及继承范围、实际验证与证据、未完成项／转版归属、构建与交付、服务／商店状态、分支及 PR；功能、修复、验证和发布阶段变化后更新，交付前随相应分支提交推送。开发日志和交接是证据，不能代替版本计划。

当前已有的 iOS 3.1.1／3.1.2 计划分别由其分支维护；Android 0.8.1／0.8.2 已按当版实际结果补齐。0.8.x 是 Android 版本，不能写成 iOS 版本。各平台 CURRENT 提供入口，历史分支不反填后续版本的业务修复。

## 提交及发布边界

目录和版本计划属于文档／工作区维护；本轮不修改业务、版本、安装包，不部署官网／服务、不上传应用市场、不合并或启用自动合并。官网仍按既有 0.8.1（9）测试分发记录，0.8.2（10）为已交付的本地固定 APK。

品牌根导航是独立 Git 仓库，目前未配置远端，因此根导航在该仓库本地提交；本产品目录规则、迁移说明与版本计划随产品远端 PR 推送，不擅自新建品牌远端仓库。其他既有未提交的品牌、官网、迁移及 iOS 业务工作不混入本次提交。
