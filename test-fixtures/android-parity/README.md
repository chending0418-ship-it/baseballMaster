# Android 对照样本

AN01 以 iOS `40df3d2` 为业务基线。`scope.json` 将 F01–F14 细化为页面、触发操作、预期结果和所属阶段；其中的 planned 代表后续完整迁移用例，不能当作已通过。

三个 `*-pitch-seed.json` 由当前 iOS GameStore 在专用模拟器中实际运行 `testAndroidParitySeedExport` 导出，覆盖 24 次投球和 3 个初始状态。`iosInitialPayload` 为实际 Swift Codable 的完整初始样本，用于后续协议适配；`initial`／`steps.expected` 为跨平台比较的明确子集。AN02 的 Kotlin 测试逐步比较此子集，尚不证明整场比赛或所有统计一致。

重生成：运行 `scripts/android/export-ios-fixtures.sh`。输出和 XCTest 附件保存在 output；只有明确核对差异后才更新本目录样本及 manifest SHA-256。动态比赛／打席 ID 和时间保留实际值；不能通过忽略业务差异迁就 Android。

样本均为合成球队，无服务器访问、真实用户记录或设备秘密。Android 内部快照不是直接可上传的 Swift payload，格式适配在 AN04／AN05 实现。
