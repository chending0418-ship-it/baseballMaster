# BaseballMaster Android 工程

Android Studio 打开当前工作树的 `android/`。该目录包含 Gradle 工程；完整 Git 根是其上一级。两个平台的工作树都会包含整个仓库，日常 Android 修改使用工作区登记的 `apps/baseball-scorekeeper-android/`。

版本、发布状态、验证与遗留只看 [Android CURRENT](docs/CURRENT.md)，需求和每版范围见[平台计划](docs/plans/)，证据见[发布交接](docs/releases/)。本文不维护另一份进度表。

| 目录 | 用途 |
| --- | --- |
| `app/` | Compose 界面、ViewModel、Room 与 Android 平台接入 |
| `core/` | Kotlin 规则和数据实现，仍与 Swift 原生实现分别维护 |
| `app/schemas/` | Room 数据库迁移契约，受 Git 管理 |
| [共享样例](../contracts/README.md) | `contracts/fixtures/` 中的合成规则／互导样例及来源摘要 |
| [平台文档](docs/README.md) | CURRENT、版本计划和实际发布交接 |

在当前 Git 根目录运行必要检查及 Debug 构建：

```sh
scripts/android/gradle.sh :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

脚本通过自身位置确定 Git 根，使用 Android Studio 内的 JDK 和本机 Android SDK；可通过 `JAVA_HOME`／`ANDROID_HOME` 指定。编译版本及最低系统以 [App Gradle 配置](app/build.gradle.kts)为准，不在 README 重复锁定一组容易过期的工具版本。

需要共享服务联调时显式执行 `:core:teamServiceTest`／`:core:liveServiceTest`，使用 `scripts/android/` 下的内存数据库和本机回环 fixture，依赖项目要求的 Node 24；它们不读取生产配置。设备 UI、Release 打包和完整互导按所属版本计划另行执行，不由普通 JVM 测试代替。

构建缓存位于本工作树的 `output/cache/android/`，交付和验证保存在 `output/releases/android/`、`output/validation/android/`。本机 SDK 路径、IDE 状态、签名和缓存不入 Git。发行包的包名、签名、冻结摘要与取用位置以相应发布交接为准，不重新生成已发布 APK 来整理目录。

本文替代原来混合 AN03–AN06 阶段进度的工程说明；各阶段事实保留在上述发布交接及 [66700a0 的原 README](https://github.com/chending0418-ship-it/baseballMaster/blob/66700a08dafc7d336b09c3f85c88505fce9f54d1/android/README.md)，按原日期阅读。
