# BaseballMaster Android

最新阶段交付为 0.1.0-preview.5（versionCode 5）。最低 Android 12／API 31，编译及目标 API 37。功能和发布状态从 [CURRENT](../docs/CURRENT.md) 与 [单份迁移计划](../docs/plans/ANDROID-APK.md) 进入。

源码结构：`core/` 为不依赖 Android 的规则／数据模块；`app/` 为 Compose、ViewModel、Room、Keystore 及平台接入。AN03 离线比赛核心已实现并通过阶段回归：名单、三模式记分、重放／纠错、特殊阵容、练习、表现图、当前球队与年度战绩；Pro 球场采用 iOS V4 设计，手机仅竖屏。48 条全量用例矩阵仍待 AN07 逐项登记；完整互导仍在 AN04，AN05 已启动协作基础层，内部 JSON 快照不直接上传为 iOS 的共享比赛 payload。

本地已安装 Apple Silicon Android Studio Rabbit 1（2026.2.1.8）、JBR 25.0.3、SDK Command-line Tools 22.0、Build Tools 37.0.0、Platform Tools 37.0.1 及 Emulator 37.2.12。Gradle 9.6.0（wrapper SHA-256 固定）、AGP 9.4.0、Kotlin／Compose compiler 2.2.10、Compose BOM 2026.09.00、Room 2.8.5、KSP 2.3.12。构建 JDK 为 25，产物 JVM 目标为 17。

在仓库根目录构建：

```sh
scripts/android/gradle.sh :core:test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug -PpreviewVersionCode=5
```

脚本默认使用 `/Applications/Android Studio.app` 内 JDK 和 `~/Library/Android/sdk`；可通过标准 `JAVA_HOME`／`ANDROID_HOME` 覆盖。构建输出和 Gradle 用户缓存均在 `output/cache/android/`。Room 的 `schemas/` 是迁移契约，应提交；`.gradle`、`.kotlin`、IDE、本机路径与签名文件不提交。

预览包名为 `cc.baseballmaster.scorekeeper.preview`，使用 Android 本机测试签名，Release 为不可调试的预览构建。正式包名 `cc.baseballmaster.scorekeeper` 与长期签名另在 AN08 固定；不把测试密钥用作对外发行密钥。APK 是包含 ARM64 在内的完整包，不依赖 Google 服务；AN05 已声明 INTERNET 权限，但当前启动／本机记分不发起协作请求，未接共享 UI 或默认生产连接。

当前 AN03 原生验收使用下方脚本，新增合成比赛且不清空现有资料。AN02 的旧基线按其交接及对应提交复现；不把旧 APK 与当前仪器测试 APK 混用。脚本检查仪器测试正文，不能只看 adb 返回码。Android 17 使用 UiAutomator，避开旧 Espresso 的 InputManager 反射问题。

初始 Room schema 1→2 只增加比赛 payloadVersion，2→3 新增独立纠错草稿表；两步都保留原比赛 JSON。当前本机 payloadVersion 为 4，旧 1／2／3 可读取且仅在实际修改时升级。未来 schema 或未知 payload 拒绝读取／编辑并保留原库；不使用 destructive migration。操作 ID 和比赛修订在同一事务落盘，失败回滚，重试不能多记一球。

App 运行依赖 AndroidX、Kotlin／kotlinx 和 AN05 新增 OkHttp 4.12.0／传递依赖 Okio 均采用 Apache-2.0 许可；工具链和测试依赖按各自许可使用，Android SDK 许可由本机 SDK 管理器记录。本轮未加入分析、广告、登录或商业服务 SDK。详细阶段交接见 [AN01／AN02](../docs/releases/android/AN01-AN02.md)。

AN03 关键原生流程（使用同轮构建的 App／仪器测试 APK，输出使用新目录）：

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

AN04 另有不运行界面的 Swift 原始数据模型互读链路，生成合成文件并显式执行 `:core:swiftWireTest`，复现命令、实际结果及未完成边界见 [Swift 数据模型互读记录](../docs/releases/android/AN04-WIRE-2026-10-28.md)。研究编码器尚未接入正式互导，不将 Codable 可解码等同于 iOS 记分历史重放通过。界面测试按用户最新要求留待统一进行。

AN04 已增加应用内来源保护导入及原文／续记文件保存，Room 3→4 新增来源文件、来源映射和原始操作保留索引；含来源的 Android 本机备份使用格式 2，无来源时维持格式 1 的字段形状。旧格式和终场比赛当前只读，符合条件的格式 7 本机进行中比赛可追加投球，以及不挤分的保送和非第三出局的三振；满垒挤分、第三出局换边及其余编辑仍拒绝。保护标记仅在 Android 内部，不改变 iOS 或服务协议。完整支持边界、Robolectric Native SQLite 回归及未验证项见 [AN04 来源导入](../docs/releases/android/AN04-IMPORT-2026-10-28.md)。


AN05 已开始设备身份／球队目录／邀请 API 和独立加密请求存储，使用现有 `/api/v3`，不自动上传本机球队。当前基础层未接产品共享入口，完整比赛同步／交接、配对恢复仍待继续。显式本地原服务集成：`scripts/android/gradle.sh :core:teamServiceTest`（需 Node 24；使用 `scripts/android/an05-local-service.mjs` 仅在 127.0.0.1 动态端口和内存 SQLite 运行，不读取部署配置）。普通 `:core:test` 不要求 Node，不发外部请求。范围与证据见 [AN05 首批开发](../docs/releases/android/AN05-DIRECTORY-2026-10-28.md)。

AN05 第二组已扩展共享名单、成员／邀请管理、独立本机来源绑定、冲突草稿与撤权缓存清理；应用调用从 `AndroidTeamSharing` 进入，尚未接产品菜单或共享建赛。换 agent 继续开发请先读 [安卓统一交接](../docs/releases/android/ANDROID-HANDOFF-2026-10-28.md)，不要把基础 API 完成当作 AN05 整体验收。

AN05 第三组已增加完整共享 JSON／游标读取与每场持久交接控制，当前 190 项回归通过。正式发布／PUT 记分更新、Room 共享比赛及产品交接入口仍未接通，须先完成 AN04 转换和 F11 编辑恢复；见 [第三组范围](../docs/releases/android/AN05-MATCHES-2026-10-28.md)。独立协作文件上限现为 100 MiB，HTTP 按现行接口为 18 MiB。
