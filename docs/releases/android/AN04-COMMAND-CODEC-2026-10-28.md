# AN04 操作编码与原始 Swift 事务对照（2026-10-28）

本组扩展正式转换需要的命令适配器和对照工具，**没有接入正式 iOS 文件导出，没有完成 AN04**。既有 iOS／服务业务源码未修改，AN07 不运行。

## 新增内容

- core AppleHistoryCommandCodec 把原生操作参数映射为 Swift HistoryCommand 的 Codable 形状，包含真实跑者决定、守备、观察／责任详情、换人、换边、比赛决定、结束／恢复、延长／TB、情境和阵容等。它仅编码动作参数，不生成完整 StoredGame、事件／打席图或纠错审计。
- 研究用 AppleExportPrototype 未升级为产品导出。新 helper 仍不接 UI；其复核接口要求明确原始 wire 事件，不能用 native operationID 猜测 eventID。没有对应语义的日期／规则／预告等元数据操作明确拒绝，不伪装成 unavailable 或另一种记分命令。
- encodeAction 用于输入原始 Swift 动作执行器；encodeForJournal 额外拒绝赛前阵容。原 iOS 在赛前调整阵容时清 journal，后续动作从新检查点捕获；扩展打序不能作为普通赛中 lineup 操作塞入旧检查点。正式 writer 仍须实现这一转换。
- AppleCommandScenarioExportTest 从已有 109 组真实 iOS XCTest 冻结样本生成当前动作输入，保存原样本 SHA-256、原始初态和预期；脚本独立核对这些值，不接受编码器自行改写预期。

## 原始执行工具

`swift-core-check.py --scenario-directory` 编译原始 GameStore、HistoryCorrection 及原 XCTest 的 canonical 函数。ScenarioHarness 先在内存设置本地比赛／名册，然后调用原始 executeHistoryCommand，走原始事务、历史捕获和 LocalBackup 校验；之后对已有 journal 调用原始纠错预览重放，逐字段核对冻结预期。

依然使用已声明的 Linux Core Data／Combine／分享／直播／协作适配器：不访问数据库文件、不联网、不调用 iOS UI。动作参数输入的 id 用于来源跟踪；原始 Swift 事务生成自身真实事件、操作和打席编号。**这不证明 Kotlin 完整文件的多事件身份投影已经完成，也不是 iOS App／Core Data 测试。**

## 实际通过和未覆盖

99 组／420 步动作执行通过；其中 419 个已有 journal 的状态也通过原始重放比较。赛前扩展阵容那一步由原始事务重建检查点，不伪造一次普通 journal 重放。检查比分、球数、局次、出局、打序、当前投手、垒况、投手责任、非自责／自动跑者和全部逐人打／投／守统计；按样本有额外计时／不完整统计字段。

另外 10 组整组排除，明确记录在生成 manifest：5 组历史插入／撤回／统计核对、2 组复核原事件、3 组 undo／redo。它们仍有旧 AN03 规则回归，**本组没有验证其新 wire 转换**。编码器中的其他未出现在这 99 组的分支也不能视为已经过 Swift 执行验证。

首次工具将 Linux Foundation 桥接 Bool 比较误判，且把纠错预览当追加动作，0／99；第二次 98／99，剩余赛前阵容未走真实事务捕获。已改为真实动作事务、类型化 JSON 比较和动作后的只读预览，第三次 99／99。旧失败目录均保留，没有修改 iOS 业务来绕过失败。

## 开发回归及证据

完整开发任务在 `final/development.log` 通过；补赛前 journal 保护后，`final/checkpoint-and-return-fixed.log` 的 153 core、Swift 返回腿和 App 组装再次通过。新负向测试首次缺守位映射导致 1 失败，改为实际 reducer 产生的有效阵容后通过；失败 checkpoint-and-return.log 保留。

冻结常规开发 **287 项（153 core＋63 协作服务＋8 直播服务＋63 app），0 失败／错误／跳过**；Swift 返回腿另 1 项。App／AndroidTest 编译及 lint 通过，lint 0 错误／19 警告。99／420 不混加为 JUnit 项数。

- `output/validation/android/an04-command-wire/swift-third/`：99 组原始执行报告、原文件／提取／适配器哈希和编译版本。
- 最后 core 测试重生成输入后再次逐个核 SHA-256，99 份仍与实际执行输入完全一致；不是拿旧回执核对不同批数据。
- `final/source-backups/`：本轮 App 测试产生的 13 份实际来源导出重新经原始 Swift 逻辑检查，13／13，保留严格身份／检查点／审计，时间精度按此前执行对照文档；随后 Kotlin 返回腿通过。
- `final/freeze/`：XML、lint、合计及输入收据确认。

## 复现与下一步

```sh
# 先跑生产者，不与 Swift 消费并行。
bash scripts/android/gradle.sh :core:test
SWIFTC=<Swift-6.2.3-swiftc> python3 scripts/android/swift-core-check.py \
  --output output/validation/android/<全新目录> \
  --scenario-directory output/validation/android/an04-command-wire/kotlin/scenarios
```

继续：完整 GameState／非空检查点转换、全部命令读回、多事件／打席身份投影、正式导出与原始纠错／撤回保存；随后完成共享 payload、离线更新和冲突核对。当前 helper 要求调用方提供完整已知球员／守位快照，不能据本组结论直接对任意新建比赛开放导出。旧式个人记录、可编辑 bmteam、真实 v5、iOS App 导入和真实系统链路仍未完成／未验证，详见统一交接。
