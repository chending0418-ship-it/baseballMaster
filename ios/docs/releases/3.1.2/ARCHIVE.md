# iOS 3.1.2 正式归档与 App Store 导出

2026-11-04。正式 Release 归档及 App Store 分发 IPA 已完成；尚未上传或提交审核。

| 项目 | 实际结果 |
| --- | --- |
| 版本／Build | 3.1.2（1） |
| 源码 | main `3d3d83a6aaf64be353e0f71c749a3e7e3ad879d6`，独立干净 worktree 构建，包含备案展示 |
| 工程／Bundle ID | BaseballMaster／com.jasonchen.baseballmaster |
| 工具链 | Xcode 27.2（27B52），Release，generic iOS device（arm64） |
| 签名团队 | JUTVG7XR9H，自动签名 |
| Archive | `output/releases/3.1.2/archives/BaseballMaster-3.1.2-build1.xcarchive` |
| App Store IPA | `output/releases/3.1.2/app-store-export/BaseballMaster.ipa` |
| 导出方式 | app-store-connect，destination=export，未使用upload |
| IPA SHA-256 | `f3f068b8f9dfe695f1902a2dc73affb1b54ce13d1966f532f69b18cd631cc572` |
| IPA 字节数 | 14,793,278 |
| dSYM UUID | `7402A2F3-3C4E-3DCE-9A3F-2245AB8D8EB1`，与App二进制一致 |

归档保留 Xcode 自动开发签名，用于后续分发；导出 IPA 已重新签为 **App Store 分发**，证书签名验证通过，`get-task-allow=false`，分发描述文件没有设备白名单，版本／Build／Bundle ID 不变。App内隐私清单已包含。不能因archive原签名为Development而把已正确重签导出的IPA称为开发包。

归档目录包含完整 `.xcarchive`，不是改名的压缩包或模拟器 `.app`；App Store截图／宣传总包与正式二进制分开。正式包、签名资料及完整日志不入Git。

验证回执：`output/validation/3.1.2/archive-2026-11-04/archive-receipt.json`、`export-receipt.json`、`archive.xcresult`；构建和导出日志同目录。归档内容共17个文件、63,379,707字节，内容清单摘要 `99ac86cc9345845362cc0000a1361479da8ddae1a62ef4dc641ee176b2883793`。

归档使用命令 `xcodebuild -project <干净main工作副本>/BaseballMaster.xcodeproj -scheme BaseballMaster -configuration Release -destination 'generic/platform=iOS' -archivePath <上述xcarchive> -derivedDataPath <独立缓存> -resultBundlePath <上述xcresult> -allowProvisioningUpdates archive`；导出使用 `xcodebuild -exportArchive -archivePath <上述xcarchive> -exportPath <上述IPA目录> -exportOptionsPlist output/releases/3.1.2/archives/ExportOptions-AppStore.plist -allowProvisioningUpdates`。导出设置保持版本／Build，不自动递增，不上传。

本轮没有运行App功能测试或代替App Store服务器校验；此前本版功能验收与本次Release编译／签名／导出验证分别记录。后续上传、选择构建、提交审核及上架以实际回执更新。
