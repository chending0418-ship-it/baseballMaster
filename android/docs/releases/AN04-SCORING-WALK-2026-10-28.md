# AN04 满垒保送续记（2026-10-28，第七组）

基线 `5c5543d`。继续 iOS → Android 迁移，补充来源保护比赛的满垒保送；**AN04 完整互导仍未完成**。只改 Android 适配、测试和安卓文档，不改 iOS／规则／服务，不发布中间 APK。

## 实际行为

此前来源型格式 7 比赛的第四坏球遇到挤分就拒绝。本组可在受支持的普通进行中比赛追加挤分保送，仍是一笔操作、两条事件和两条日志，保留第四坏球的中间局面与最终 BB 局面。得分对应实际三垒跑者，写入 effectiveScorerIDs、得分移动、每局比分、跑者得分、打者打点、负责投手的失分及自责分，并生成稳定的新打席身份。

依据为现行 `GameStore.swift` 的 `recordPitch`、`applyWalkWithoutUndo`、`forcedAdvanceDecisions`、`resolveRunners`、`addLog` 及 `RunnerDestination.title`。原 iOS 的责任映射和非自责标记在得分后继续保留，半局切换时再清理；安卓沿用该行为，不在迁移中改规则。

原文件、旧事件／日志、操作前缀、检查点、原纠错审计不变。来源血缘校验允许每局得分字段发生变化，但仍从原始文件独立重新执行新增投球，要求整份当前 snapshot 精确一致；不能只改比分并重算校验和绕过保护。Room 在同一事务提交来源文件、比赛及幂等账本；写失败整笔回滚，续取同一编号不再得第二分。`.bmandroid` 格式 2 保留原始／当前来源并校验恢复。

**继续拒绝**：第三出局换边、保送触发规则确认／半局上限、再见终场、特殊阵容或不支持的来源结构等。没有开放原始审计上的纠错／撤回、换人或全部三种规则续记；原生 Android 新比赛正式 iOS 导出仍未接通。

## 验证与已修正的失败

新增核心测试分别从真实冻结的普通安打来源和已纠错的失误来源追加合成保送，核对自责／非自责、有效得分、原始审计、前缀及重新打开后继续记录。另以明确标注的合成规则变体验证得分上限确认仍拒绝且原字节不变；该变体不是新找到的真实旧 iOS 样本。

新增 API31 Android JVM／Native SQLite 集成验证注入 SQLiteFullException 时比分／来源／账本全部回滚，同编号重试只记一次，Android 格式 2 恢复后重复请求仍幂等。数据库实际导出新增的挤分及恢复后两份文件，再顺序送入原始 Swift Codable 模型读写及 Kotlin 返回核对。**这不等于真实 iOS GameStore／Core Data 执行**。

首轮测试错误地要求得分后删除投手责任／非自责历史映射；查阅现行 iOS `resolveRunners` 后按现有保留行为修正断言。第二轮血缘白名单尚未纳入每局比分，导致新得分恢复被拒绝；补充允许字段，保留后续独立重放及整份 snapshot 相等检查。失败日志分别为 `scoring-walk-core.log` 和 `scoring-walk-focused.log`，不计为通过。`scoring-walk-focused-2.log` 为修正后的定向通过结果。

最终执行 `:core:test :core:teamServiceTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，BUILD SUCCESSFUL。**core 136、原服务 44、app 49，合计 229 项，0 失败／错误／跳过**；lint 0 错误、15 警告。未变输入的任务有复用，不冒充全部重新执行。数据库输出的 13 份文件经原始 Swift 模型读写及缺字段负向检查全部通过；返回核对另有单项聚合测试。

全部产物位于 `output/validation/android/an04-source-roundtrip/`：综合日志 `scoring-walk-final.log`、冻结 XML／lint／源码与样本哈希 `scoring-walk-freeze/`、Swift 原始结果 `scoring-walk-swift/`、返回日志 `scoring-walk-return.log`。不推送生成文件，源码和复现命令随分支提交。先完成综合测试冻结生产者，再运行 `swift-wire-check.py`，最后 `:core:swiftWireTest -PswiftWireDirectory=<本组新目录绝对路径>`，不要重跑生产者后使用旧回执。

## 接续与未验证

继续按 [iOS 对照](IOS-PARITY-AUDIT-2026-10-28.md) 补完整命令／多事件映射、第三出局与换边、来源历史纠错／撤回、双向更新合并、正式导出、`.bmteam` 编辑和旧个人记录。真实 v5 仍缺。没有实际设备／Activity UI、系统杀进程、真磁盘写满、实际 iOS 导入或跨端协作；这些不是已通过项。用户最新允许 UI 可做则做，本组仍只做可复现的非界面验证。
