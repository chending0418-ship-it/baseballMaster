# 2.3 上传与发布流程

2026-10-09 更新截图栏目说明。开发方负责模拟器验收、材料、签名归档、官网和兼容直播服务、main 推送。用户负责 Apple 分发验证、上传、提交审核与最终上线。

## 文件与工程

- 工程：`/Users/JasonChan/Documents/BaseballMaster/BaseballMaster.xcodeproj`，Scheme `BaseballMaster`。
- Version 2.3／Build 1，Bundle ID `com.jasonchen.baseballmaster`，Team `JUTVG7XR9H`。
- 既有 App Store App ID `6802063886`，iPhone，最低 iOS 16。
- 最终归档：`output/releases/2.3/archives/BaseballMaster-2.3-1-release.xcarchive`。
- 完整材料包：`output/releases/2.3/BaseballMaster-2.3-AppStore.zip`。
- 商店字段：[APPSTORE-METADATA.md](APPSTORE-METADATA.md)；隐私填写：[APP-PRIVACY.md](APP-PRIVACY.md)。

## Xcode 与 Connect

1. 打开最终 `.xcarchive`，在 Xcode Organizer 核对 2.3／1 与 Bundle ID。它是本机开发签名归档；选择 `Distribute App` → `App Store Connect`，由 Xcode 使用你的账户按 App Store 分发方式验证与上传。Apple 的在线分发校验尚未由开发方执行。
2. 若 Connect 中已存在 2.3 的 Build 1，将工程 Build 递增后重新归档；已有构建号不能重复使用。本次没有替你上传构建。
3. 在现有 App 6802063886 下创建 2.3 版本，等待上传构建处理完成，再选择正确版本的构建。
4. 从独立纯文本文件复制宣传文本、描述、新增内容、关键词和英文审核备注。若当前栏目提示 iPhone 6.1／6.3 英寸，上传 `app-store/iphone-6.3/` 中七张 1206 × 2622 PNG；6.9 英寸栏目使用 `iphone-6.9/`（1320 × 2868），6.5 英寸栏目使用 `iphone-6.5/`（1242 × 2688），不可跨栏目混用。886 × 1920 是 App 预览视频尺寸。HTML、总览图、原图目录和版本宣传海报不放入设备截图栏目。
5. 填账户的真实审核联系方式，无需登录测试账号。核对支持／隐私链接和实际 App 隐私问卷，依构建提示填写出口合规等 Connect 项目。已有问卷与账户资料没有被本任务修改。
6. 提交审核并选择你的上线方式。只有 Apple 接受构建、审核通过且实际发布，才算 App 已上架；Git main 推送和官网更新不替代此步骤。

审核人员可使用可编辑的示例球队现场创建比赛与临时直播，不提供永久直播链接。上架后将官网“准备发布”改为实际可下载状态，可使用同目录社交发布稿。

依据：[Apple 上传构建](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)、[版本信息字段](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information)、[截图规格](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)。截图尺寸与当前 Xcode／iOS SDK 元数据已在交付中核对；正式分发仍使用用户账户完成。
