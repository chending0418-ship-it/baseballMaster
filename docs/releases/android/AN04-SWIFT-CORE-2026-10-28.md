# AN04 原始 Swift 记分逻辑对照（2026-10-28）

本组补可复现的逻辑验证工具，**没有完成完整互导，也没有运行 iOS App、Core Data 或界面**。现有业务 Swift 文件未修改。AN07 按用户要求不做。

## 工具与隔离

`scripts/android/swift-core-check.py` 以唯一文本锚点提取仓库原始 GameStore、HistoryCorrection、Models、Statistics、情境记录及关联类型。业务函数体原样编译，仅移除 Linux 不提供的平台 import。锚点不唯一立即失败，不猜测声明。每次输出原文件及提取文件 SHA-256、适配器 SHA-256、Swift 版本和编译日志。

`scripts/android/swift-core/LinuxAdapters.swift` 明确替代 Combine 通知、颜色、Core Data、共享协调、直播、报告导出和安全作用域文件接口。仅允许 `GameStore(persistenceURL:nil)`；文件数据库打开／替换和报告导出会拒绝，网络共享和直播关闭。SHA-256 使用 OpenSSL 真计算，驱动先核对已知 abc 摘要。**这些替代实现不构成平台功能验证**。

驱动对每场本地现代 journal 比赛调用原始 makeCorrectionDraft 和 previewCorrection；拒绝共享、旁观和无 journal 输入。检查实际记分等价、操作编号和完整 journal、检查点 Data、打席、事件／日志及其引用。输出只是参考文件，不回写输入文件、数据库或产品导出。

## 结果与精度

冻结输入是当前 Room 集成测试实际产生的 13 份 bmbackup，包含 15 场次；普通续记、保送、三振、满垒挤分、多比赛和恢复样本均通过。`an04-swift-core-reference-3/results.json` 为 13／13；Kotlin 返回腿 `:core:swiftWireTest` 1 项通过，确认消费当前源 payload、SHA-256、原身份、检查点和返回后重放。新增负向检查篡改比分，原 Swift 重放能够报告不等价。

精确字段检查发现两份多比赛样本的原事件／日志 timestamp 与操作 timestamp 相差约 0.205 毫秒。原始 Swift 重放使用操作时间，因此精确事件／日志比较为 false。报告保留这两个精确布尔值；通过标准明确允许 **最多 1 毫秒且仅 timestamp 不同**，其余事件／日志字段必须逐值相等。最大时间差逐场记录。journal、检查点、打席和计分均不放宽。不能把参考重放输出当作无损替换原文件；产品仍保留原始字节和审计。

首次脚本编译因未去除 SharedMatches 的 Combine import 失败，已修复；首次严格对照为 11／13，时间差调查及上限如上。失败目录 an04-swift-core-reference、-2 保留。不是将整个状态比较降为比分比较。

## 复现与继续

```sh
# 先完成 core/app 样本生产，之后不要同时重跑生产者。
SWIFTC=<Swift-6.2.3-swiftc> python3 scripts/android/swift-core-check.py \
  --output output/validation/android/<全新目录> \
  --input-directory output/validation/android/an04-source-roundtrip/kotlin
bash scripts/android/gradle.sh :core:swiftWireTest \
  -PswiftWireDirectory=<上述目录的绝对路径>
```

Linux 需 OpenSSL 开发头文件／libcrypto。输出目录必须不存在，避免混批。Swift 5 语言模式对应现行 Xcode 配置，非 Swift 6 并发迁移。普通 Gradle 测试会重建 UUID 样本，旧结果不能核对新源。

下一步用此工具扩充多事件、换边、纠错等真实执行对照，再开发 Kotlin 正式 wire 编码；当前原生研究导出不可直接升级为正式导出。真实 v5、完整命令互导、可编辑 bmteam／个人记录、共享完整 payload 及 iOS App 实际导入仍未完成，详见统一交接。
