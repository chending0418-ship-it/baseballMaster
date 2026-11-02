# Android 原生投球图与来源第三出局续记（2026-11-02）

本轮在 `codex/android-migration` 继续 AN04。完整 `.bmbackup`／`.bmteam` 可编辑互导仍未完成；本文只记录新增的写入范围与原始 Swift 对照，不将其作为正式全功能导出或 iOS App 验收。

## 本轮改动

- 来源保护的格式 7 标准比赛，三振现在支持普通第三出局换边，并可继续由新守方投球。第三出局产生 pitch／out／HALF-END 三事件及各自日志，由同一原始操作拥有；新打席沿用 Swift SHA-256 身份规则。规定局数确认、终场、特殊规则、换人和其他命令继续明确拒绝。
- 原始备份字节、旧检查点 Data、旧操作／事件／日志和纠错审计保持原样。新增操作必须经过原生记分、完整返回重放和独立来源血缘重建，失败不返回部分备份。恢复检查仍将整份重建结果与待恢复文件比较，不能靠重算 checksum 绕过来源保护。
- 新增内部 `AppleNativePitchExport`，从完整赛前检查点生成原生标准投球比赛的真实图：首球 PLAY＋pitch、保送／三振的 pitch＋result、普通第三出局三事件、跨半局续球及满垒挤分。它只复用旧编码器的标量／名单序列化，旧的一操作一事件图在验证前完整替换。
- 新原生检查点按 Swift `GameState.historyData` 的排序与数字表示生成；每次标准新打席／换边分别保留 Swift 的可空标志与责任字段区别，避免将 `nil` 误写为 `false` 或空数组。这个规范化只用于新的原生检查点，不重编码来源 Data。
- 原生 writer 仍为内部受限实现，没有接为产品完整比赛导出按钮。击球／跑垒／全部三模式／阵容、规则确认、终场、纠错／撤回及全部旧格式仍待完整图写入；不能上传当前 Android 内部 JSON 代替 iOS StoredGame。
- 原 Swift 核心验证工具已支持本机 macOS：使用真实系统 CryptoKit SHA-256，Linux 保持真实 OpenSSL；不修改原 Swift 业务文件。两平台共用内存／无操作平台适配器，因此结果仍不代表 Core Data 或 iOS App 已测。

## 验证与证据

5 项新增原生图回归覆盖稳定身份、满垒挤分、跨半局新投手、来源血缘、规则确认／其他操作／撤销分支拒绝。既有第三出局拒绝用例已更新为检查三事件归属、检查点和旧操作不变、原 plan 字节不变与完整血缘重建；规定局确认拒绝另有覆盖。

原 Swift 对照从 `scripts/android/swift-core-check.py` 重新提取、编译未改写的 `GameStore`／`HistoryCorrection` 及模型。核验二进制和来源 SHA-256，冻结 Kotlin 新生成的四份合成备份，再比较比分、操作／事件／打席身份、每个事件、每条日志、完整打席、journal 与检查点 Data。最终结果为 **4／4 通过**，脚本 9 项强制检查全部为真，事件与日志的额外 2 项精确相等结果也为真，最大时间差为 0：

- 冻结输入和 SHA-256：`output/validation/android/2026-11-02-native-graph-inputs/manifest.json`。
- 正式原 Swift 编译、来源哈希、逐比赛报告、返回备份与总结果：`output/validation/android/2026-11-02-native-graph-verified/`；总回执 `results.json`。
- 四份分别为 8 操作／11 事件、16／21、21／31、10／15，共 55 操作、78 事件；全部日志、打席和原检查点 Data 精确相等。计数只说明本轮图样本规模，不叠加为 JUnit 项数。
- 统一核心回归为 168 项、0 失败；完整构建和服务／UI 结果由本轮统一交接登记。单独 native＋terminal 回归日志保存在 `output/validation/android/native-pitch-check-4.log`。

复现命令：

```sh
python3 scripts/android/swift-core-check.py \
  --output output/validation/android/<新证据目录> \
  --input-directory output/validation/android/2026-11-02-native-graph-inputs
```

该命令重新编译并执行原始 Swift 核心，任一严格检查不符即失败；不是仅检验 JSON 可解码或比分相等。

此前调试证据保留于 `output/validation/android/2026-11-02-native-pitch-swift/` 和 `...-final/`，其中明确保留严格图不匹配结果；不能将这些调试轮当作通过。发现的问题已用于修复标准新打席 `extraFoulUsed` 和换边后责任字段的 `nil` 写法，以及新检查点的字节表示。

## 保持的边界

本轮没有修改原 iOS 业务／全局规则／公共协议，没有连接生产，没有制作或发布新的正式签名 APK。原生 writer 的受限范围不改变 AN04 完整互导及 AN05 赛后完整上传仍待完成的状态。真实 iOS 导入、GameStore／Core Data 持久化恢复和真实双端链路须独立验收。
