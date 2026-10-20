# 3.1.0 上传与审核

共享直播所需的格式兼容已先行部署；观看页、独立直播和邀请共存机制继续沿用。App 归档及审核材料已准备。上传、Apple 服务端验证、提交审核和发布由你操作。

1. 在 Finder 打开 `output/releases/3.1.0/archives/BaseballMaster-3.1.0-1-final.xcarchive`，进入 Xcode Organizer。核对 Version **3.1.0**、Build **1**、Bundle ID **com.jasonchen.baseballmaster**、Team **JUTVG7XR9H**。归档使用本机 Apple Development 签名，选择 App Store Connect 分发时由 Xcode 按商店方式重新签名。
2. 选择 Distribute App → App Store Connect，先验证再上传。在已有 App（ID **6802063886**）下创建／选择 **3.1.0**，构建处理完成后选取该构建。不要上传旧版归档。如 Connect 已有 3.1.0 Build 1，需要先递增 Build 并重新归档。
3. 从材料包复制 `whats-new-zh-Hans.txt`、`review-notes-en.txt`，按 `APPSTORE-METADATA.md` 核对其他文案。截图有三套各六张，按对应设备栏目选择同一尺寸的一套，顺序为 01–06。原商店中仍准确的其他创意素材可以继续保留。
4. 登录要求选择“不需要登录”。审核人员可自行创建共享球队，无需固定账号或会过期的审核邀请。联系姓名、电话使用账户真实资料。隐私类别沿用 3.0，依据见 `APP-PRIVACY.md`；核对账户内出口合规、分级等项目。
5. 提交审核并按你的安排发布。共享服务保留旧格式兼容，旧版仍可操作尚未升级的场次；已经由 3.1.0 写入的场次，需要新版 App 继续记分。

本机已核对签名、版本、隐私文件和 dSYM，不代表 Apple 服务端已验证、已上传或已批准。
