# BaseballMaster Android

首个开发预览为 0.1.0-preview.2；最近已交付为 0.1.0-preview.4（versionCode 4），当前源码为 0.1.0-preview.5（versionCode 5）待验收候选。2026-10-27 应用户要求暂停，恢复前先读 [暂停检查点](../docs/releases/android/AN03.md#2026-10-27-暂停检查点)。最低 Android 12／API 31，编译及目标 API 37。功能和发布状态从 [CURRENT](../docs/CURRENT.md) 与 [单份迁移计划](../docs/plans/ANDROID-APK.md) 进入。

源码结构：`core/` 为不依赖 Android 的规则／数据模块；`app/` 为 Compose、ViewModel、Room、Keystore 及平台接入。AN01／AN02 已完成；AN03 已新增通用记分、打投守统计、重放／纠错、名单、特殊阵容、终场恢复、练习与表现图，仍待完整最终验收。Pro 落点、路线和失误责任球场已按 iOS V4 实现并验证；AN03 其余用例及 AN04–AN08 尚未完成。内部 JSON 快照不直接上传为 iOS 的共享比赛 payload；共享协议适配与双向协作在后续阶段接入同一服务。

本地已安装 Apple Silicon Android Studio Rabbit 1（2026.2.1.8）、JBR 25.0.3、SDK Command-line Tools 22.0、Build Tools 37.0.0、Platform Tools 37.0.1 及 Emulator 37.2.12。Gradle 9.6.0（wrapper SHA-256 固定）、AGP 9.4.0、Kotlin／Compose compiler 2.2.10、Compose BOM 2026.09.00、Room 2.8.5、KSP 2.3.12。构建 JDK 为 25，产物 JVM 目标为 17。

在仓库根目录构建：

```sh
scripts/android/gradle.sh :core:test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug -PpreviewVersionCode=5
```

脚本默认使用 `/Applications/Android Studio.app` 内 JDK 和 `~/Library/Android/sdk`；可通过标准 `JAVA_HOME`／`ANDROID_HOME` 覆盖。构建输出和 Gradle 用户缓存均在 `output/cache/android/`。Room 的 `schemas/` 是迁移契约，应提交；`.gradle`、`.kotlin`、IDE、本机路径与签名文件不提交。

预览包名为 `cc.baseballmaster.scorekeeper.preview`，使用 Android 本机测试签名，Release 为不可调试的预览构建。正式包名 `cc.baseballmaster.scorekeeper` 与长期签名另在 AN08 固定；不把测试密钥用作对外发行密钥。APK 是包含 ARM64 在内的完整包，不依赖 Google 服务；当前 manifest 没有 INTERNET 权限。

当前 AN03 原生验收使用下方脚本，新增合成比赛且不清空现有资料。AN02 的旧基线按其交接及对应提交复现；不把旧 APK 与当前仪器测试 APK 混用。脚本检查仪器测试正文，不能只看 adb 返回码。Android 17 使用 UiAutomator，避开旧 Espresso 的 InputManager 反射问题。

初始 Room schema 1→2 只增加比赛 payloadVersion，2→3 新增独立纠错草稿表；两步都保留原比赛 JSON。当前本机 payloadVersion 为 4，旧 1／2／3 可读取且仅在实际修改时升级。未来 schema 或未知 payload 拒绝读取／编辑并保留原库；不使用 destructive migration。操作 ID 和比赛修订在同一事务落盘，失败回滚，重试不能多记一球。

App 运行依赖 AndroidX、Kotlin／kotlinx 均采用 Apache-2.0 许可；工具链和测试依赖按各自许可使用，Android SDK 许可由本机 SDK 管理器记录。本轮未加入分析、广告、登录或商业服务 SDK。详细阶段交接见 [AN01／AN02](../docs/releases/android/AN01-AN02.md)。

AN03 当前候选关键原生流程（恢复后使用同轮构建的 App／仪器测试 APK，输出使用新目录）：

```sh
python3 scripts/android/validate-scoring.py --serial emulator-5554 \
  --output output/validation/android/an03-repeat \
  --apk output/cache/android/build/app/outputs/apk/release/app-release.apk
```

此脚本新增合成比赛，不清空已有资料；包含落点与路线、纠错、终场／恢复、外部杀进程和事务验证。已有 AN02 的 `storage-receipt.json` 及凭证指纹时可加 `--legacy`，只读核对原资料，并在独立数据库副本验证续记、重放和 payload 1→4。`payloadVersion` 是 Android 本机格式版本，与服务端比赛 `formatVersion` 分别管理。旧阶段 APK 应使用其对应提交构建的仪器测试 APK，不能混用当前新增 API 的测试包。

历史纠错草稿、外部杀进程恢复、更正审计／撤回及中断续记：

```sh
python3 scripts/android/validate-history.py --serial emulator-5554 \
  --output output/validation/android/an03-history-repeat \
  --apk output/cache/android/build/app/outputs/apk/release/app-release.apk
```

纠错在独立草稿中预览，不直接写正式记录；保存时比赛、操作 ID、审计记录与草稿删除在同一事务中完成。练习草稿只保留在当前进程。

当前交付和待办见 [AN03 开发记录](../docs/releases/android/AN03.md)。
