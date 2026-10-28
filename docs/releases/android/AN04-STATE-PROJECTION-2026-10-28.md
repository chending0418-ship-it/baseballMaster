# AN04 当前状态与非空检查点读取（2026-10-28）

本组补跨端重放需要的纯读取层，**未接产品导入／写回，不是正式 wire 导出，不授权共享编辑；AN04 仍未完成，AN07 不运行。** 原始 iOS／服务未改。

## 已实现

AppleStateProjection 可检查当前 GameState 或选定检查点，不再要求所有比分、垒况、统计和历史前缀为空。读取三模式规则、打序／守位、DH／双向球员标志、比赛决定、结束、延长／TB、投手责任、逐人统计、计时和日期元数据；保留 00。iOS 旧式“整半局已安排”TB 标记会在 Native 视图展开为逐垒标记，避免重复放人，原文不变。

它保存原始 StoredGame／所选 state 的 JsonObject，Native 视图深复制。未知字段、事件／日志／打席图、检查点 Data 和纠错原文由原对象保留，明确列为未投影字段；不把这些内容压成一个 Native 操作。**JsonObject 不是原始文件字节**：文件摘要及字节仍须由原 Envelope／Room 来源保管，不可用 toString 重算原校验码。

登板局数采用原始 GameState.pitcherInningUsage 的 before／after 计入规则；统计 IP 仍取保存的实际 outsRecorded，二者分开。不齐全的登板历史有明确 warning。还保留观赛、共享未授权、旧投手出局口径、空背号、源时区缺失等检查信息，不偷偷修复旧资料。Native 视图使用既有时区缺省值，源缺字段仍在原文中保持缺失，全局时区口径继续延后。

限制：没有构造 Native 的完整历史／纠错记录；没有证明可以从该 Game 重建原文件。教练模式总投球数不能只凭 pitching 字典恢复，明确标记需要 journal 重放；历史垒上球员快照、示例标记和更多 metadata 仍需原文。不能把这个读取层直接当成任意文件的可编辑导入。

## 独立验证

- 普通测试核对全部 109 份原始 iOS 初态的冻结计分字段，另测未知字段保留、视图独立、旧 TB 标记、重复字典拒绝和 Int 总分溢出拒绝。
- 原始 Swift 工具重新执行 99 组／420 步，额外调用未修改的 pitcherInningUsage 和原 canonical 函数，并解码自身实际检查点。
- 显式 `:core:swiftCommandStateTest` 返回腿读取 **420 份真实当前状态＋419 份检查点**，对比原始 Swift 的局面、全部逐人统计、责任、阵容、登板局数及完整性；先核输入 SHA-256，缺产物或不匹配失败，不跳过。不是用读取器自行生成的预期。

原始执行在 `output/validation/android/an04-state-projection/swift/`，返回腿 return-initial.log／final.log；本组没有真实运行失败。普通 core 158 项、返回腿 1 项均通过，App／AndroidTest 组装与 lint 通过。**当前常规开发累计 292 项（158 core＋63 协作服务＋8 直播服务＋63 app），返回腿另计**；本组未重跑未受影响的服务／app 单测，冻结保留其最近已通过 XML。lint 0 错误／19 警告。证据和摘要见 freeze。

```sh
# 严格先完成输入生产者，再执行 Swift，最后返回核对。
bash scripts/android/gradle.sh :core:test
SWIFTC=<Swift-6.2.3-swiftc> python3 scripts/android/swift-core-check.py \
  --output output/validation/android/<全新目录> \
  --scenario-directory output/validation/android/an04-command-wire/kotlin/scenarios
bash scripts/android/gradle.sh :core:swiftCommandStateTest \
  -PswiftCommandDirectory=<上述目录的绝对路径>
```

## 下一步

继续补全部命令读回与完整 journal 重放、10 组纠错／撤回／复核的 wire 参数验证、多事件和打席身份投影，再接正式导出／来源可编辑写回。不得把当前状态读对称为完整可编辑历史已迁移。旧式个人记录、可编辑 bmteam、真实 v5、共享 payload／离线队列、真实 iOS App／Core Data／安卓界面和系统链路仍按统一交接继续；AN07 本轮不做。
