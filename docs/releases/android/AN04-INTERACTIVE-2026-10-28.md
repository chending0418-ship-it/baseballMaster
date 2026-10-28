# AN04 原始 Swift 交互核心对照（2026-10-28）

本组补 AN04 开发验证，AN07 不执行。**完整互导、原生 Android 正式 iOS 文件输出仍未完成。** 本组不新增产品文件导出入口，不改 iOS／后端源码；这是原始 Swift 核心在 Linux 内存适配器中运行，不是 iOS App、Core Data、模拟器或真机联调。

## 结果与差异

- 109 份原有冻结 `*-scoring.json`，共 109 个初始状态＋489 步＝598 帧。Kotlin 与实际 Swift 动作结果均按原始 XCTest 基准逐字段一致；当前 Swift 状态也通过 `AppleStateProjection` 返回读取核对（审计保持原文，未转换为 Native history）。
- 7 次补记／多条补记／统计纠错／撤回、2 次人工复核、6 次 undo/redo 均实际执行原始 Swift API。复核先从真实操作的 `eventIDs` 查唯一待复核事件，不猜测操作与事件编号。
- 原始 Swift 纠错预览重放 488 次：487 次匹配；**1 次已知差异**，测试严格固定该用例、该步骤和三项字段。通过的是“正确结果＋已知差异仍被完整记录”的检查，不能写成 488 次全部一致或全量互导通过。
- 每次纠错／撤回核对新增审计的 `previousJournal.checkpoint` 原始 Data、操作 ID、事件 ID、日志 ID、nextAppearanceID；之前的纠错审计 ID 必须全部保留。旧日志／事件完整对象保存在每帧的原始 `storedGame`，不以 Native entries 重建它们。

### 需要两端上线后统一处理的来源行为

`history/history-lineup-after-missed-pitcher-scoring.json`：记录坏球→二游阵容调整→好球→在阵容调整前补记换投。第一次 `saveCorrection` 当前状态与冻结基准一致。立即再次 `makeCorrectionDraft`／`previewCorrection`：`homeOrder`、`pitcherID`、`pitching` 不一致，漏记投手的替换在第二次重放中丢失。

证据：`sessions/results.json.knownSourceReplayDifferences` 保存预期／实际三项；对应逐帧文件保存完整来源当前状态与第二次预览。当前原始 `HistoryCorrection.swift` 的预览会更新 operation.context，再次处理 lineup 时按该 context 映射；这是本地原始核心复现，未称为已在 iOS App 复现。**不在本轮改 Swift、云端、全局规则或自动改写该来源文件。** 以后完整来源可编辑实现必须检测这类重放不一致并阻断写入，不可把预览结果覆盖原始统计。

## 可复现入口

源码：`scripts/android/swift-core/InteractiveHarness.swift`、`ScenarioHarness.swift`、`HarnessMain.swift`、`swift-core-check.py`；显式 Kotlin 测试 `AppleSwiftSessionTest`，普通 `:core:test` 不依赖 Swift，也不静默跳过缺少的二进制。

```sh
# 仓库根目录；先配置免费官方 Android/JDK/Swift/OpenSSL 工具链。
SWIFTC=<swiftc绝对路径> python3 scripts/android/swift-core-check.py \
  --output output/validation/android/<全新目录> --compile-only
bash scripts/android/gradle.sh :core:swiftSessionTest \
  -PswiftCoreBinary=<上述目录的绝对路径>/swift-core
```

编译脚本会把源文件 SHA、抽取边界、适配器 SHA 和 Swift 版本存入目录；测试先核对二进制及每个当前源码 SHA，过期二进制直接失败。每份用例独立进程、独立内存 GameStore，标准输入／输出只作本机逐行 JSON 通信，没有网络、Core Data、真实 Keychain。`--compile-only` 明确输出 `testsRun:false`，编译成功不能代替测试。

本机证据：`output/validation/android/an04-interactive/`；最终编译 `swift-build-second`，执行 `final.log`，逐帧 `sessions/`，冻结 XML `freeze/`。`output/` 被 Git 忽略，新 agent 应按上面重建，不能假设云端输出在远端。

## 失败与最终核对

1. 首轮 107/109：多条补记工具错误地要求第一个未完成的重新上场草稿可执行；另外第二次 Swift 预览存在来源差异。
2. 修正工具，完整组装补记后一次性保存，不能修改生产规则迎合测试。第二轮 108/109，来源差异实际涉及三项，不只打序。
3. 第三轮 109/109 实际动作匹配，同时单独记录一处三字段重放差异；加入二进制／当前源码哈希检查后重跑最终核对。

本组普通 core 仍为 158 项；显式交互检查另 1 项聚合测试。之前 63 协作服务＋8 直播服务＋63 app 的结果没有因本组测试工具变更重新执行，不称为全部重跑。产品代码、UI、设备、签名、生产地址均未操作；此前 lint 19 条警告也不是本组重测结果。

下一步仍是完整 wire graph 的 Android 映射和正式文件输出：多事件／打席／换边／终场／复核、非空检查点、审计撤回、旧格式及 `.bmteam`，之后才能开放完整共享记分写入。不要把本组原始核心验证工具带进 Android 产品运行时，也不要用 Linux Swift 子进程作为 Android 文件生成方式。
