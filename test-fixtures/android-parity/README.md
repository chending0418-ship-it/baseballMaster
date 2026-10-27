# Android 对照样本

AN01 以 iOS `40df3d2` 为业务基线。`scope.json` 将 F01–F14 细化为页面、触发操作、预期结果和所属阶段；其中的 planned 代表后续完整迁移用例，不能当作已通过。

三个 `*-pitch-seed.json` 由当前 iOS GameStore 在专用模拟器中实际运行 `testAndroidParitySeedExport` 导出，覆盖 24 次投球和 3 个初始状态。`iosInitialPayload` 为实际 Swift Codable 的完整初始样本，用于后续协议适配；`initial`／`steps.expected` 为跨平台比较的明确子集。AN02 的 Kotlin 测试逐步比较此子集，尚不证明整场比赛或所有统计一致。

重生成：运行 `scripts/android/export-ios-fixtures.sh`。输出和 XCTest 附件保存在 output；只有明确核对差异后才更新本目录样本及 manifest SHA-256。动态比赛／打席 ID 和时间保留实际值；不能通过忽略业务差异迁就 Android。

样本均为合成球队，无服务器访问、真实用户记录或设备秘密。Android 内部快照不是直接可上传的 Swift payload，格式适配在 AN04／AN05 实现。

AN03 的 `scoring/` 冻结 30 组、179 个状态，由 `testAndroidScoringParityExport` 导出，比较所有非零打投守统计及有效跑者责任。`history/` 另有 6 组、30 个状态，由 `testAndroidHistoryParityExport` 导出，包含中断续记、补记换投及撤回、代跑重新上场、守位、人工责任和漏记换投后的阵容映射。两次 XCTest 的证据位置登记在 AN03 记录及 history manifest；新增第六组时保留前五组原始字节。

历史样本使用 `historyInsert`／`historyInsertMany`／`historyAmend`／`historyRevert` 表达测试步骤，这些是测试驱动指令，不是共享服务的 wire format。人工期望值不替代 iOS 实际运行结果。
