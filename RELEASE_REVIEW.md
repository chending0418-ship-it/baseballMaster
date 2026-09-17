# BaseballMaster · TestFlight 发布交接

日期：2026-09-17。验收以正常字号和模拟器为范围；按项目负责人的明确要求，本轮跳过真机验收。上传与 TestFlight 分发由项目负责人操作，本次未上传。

## 本轮交付

- 比赛宣传图片：新建未来比赛成功后可直接制作，未来比赛详情和当前比赛记分页面也可进入。主客队名、时间和规则自动带入；标题、场地和备注可编辑。输出 1080 × 1440 PNG，采用米白、黑、朱红配色和大队名字体，无圆角卡片。
- 逐打席 PDF：整场或单打席导出，展示打席前后比分、球数、出局和垒况，以及逐球、跑垒、换人等事件。待确认、未完成、换边和缺失快照均有明确标注；不推测未记录的球速或球路。
- 逐打席文字简版：保留每个打席的局次、打者和文字描述，以 A4 竖版连续排版，省去局面图、表格和索引页；整场与单打席均可预览分享，“全部导出”也包含简版。长描述跨页时重复打席标题，保留待确认／未完成和复核说明。
- 本地备份与恢复：完整 `.bmbackup` 文件、校验、恢复前确认、最近 3 份自动备份、损坏数据库恢复入口及原文件归档。
- 发布配置：隐私清单、UserDefaults 使用原因、备份文件类型声明、出口加密声明、Release 归档配置。

## Review 中修复的问题

1. 海报与备份的分享面板首次打开可能为空：改为用实际导出文件驱动 sheet 的展示，相关 UI 流程已复测。
2. 数据库读取遇到损坏的比赛记录时可能跳过记录，或退回示例数据：现在严格检查数据库完整读取，保留原文件并进入恢复页面；无法识别的新版数据也拒绝加载。
3. V1 数据库迁移后的默认对手名单没有完整落库：迁移完成后一次性写入 V2 默认资料与版本，删除对手后重启不会重新补回。
4. 恢复文件不应直接覆盖当前数据库：先校验备份、构造并读回暂存数据库，再归档旧文件并替换；校验失败不更改已有数据。
5. 深色界面中部分彩色实心按钮上的白字对比不足：实心按钮采用稳定的深色底，并补齐球数、投打信息的辅助标签。

## 验证记录

- 单元测试：最终全量 **89 / 89 通过**，覆盖本轮备份、迁移与损坏数据库处理，以及文字简版内容、范围和续页。结果：`tmp/ReviewDerivedData/Logs/Test/Test-BaseballMaster-2026.09.17_23-44-48-+0800.xcresult`。
- 文字简版追加验证：**4 / 4 PDF UI 流程通过**，涵盖简版整场／单打席预览分享、原详细版、Box Score 和球队赛季报告；横竖页面提示分别核对。结果：`DerivedData/Logs/Test/Test-BaseballMaster-2026.09.17_23-43-55-+0800.xcresult`。普通简版样例 1 页、长描述样例 2 页均已渲染检查，无越界文字。
- 正常字号 UI：首次回归覆盖 35 项流程，发现 2 项首次分享面板问题；修复后，备份、海报、新建比赛、记分、统计及逐打席 PDF 等 **8 项相关流程复测全部通过**。结果：`DerivedData/Logs/Test/Test-BaseballMaster-2026.09.17_23-33-18-+0800.xcresult`。该次组合运行还包含一个当时未修复的数据库单元测试失败；修复后的全量单元测试结果见本节第一项，因此不将这次组合运行写为整体通过。
- 正常字号视觉复核：检查 21 个主要页面的深色模式截图，涵盖首页、球队／球员、比赛设置、阵容、记分、结果、统计、备份和海报。截图保存在 `tmp/release-normal/`。
- Release 签名归档：最终源码（含文字简版）生成 `output/build/BaseballMaster.xcarchive`，Archive 成功，`codesign --verify --deep --strict` 通过；包内规则 PDF、隐私清单及版本 `1.5 (3)` 已核验。构建日志见 `/tmp/baseball-release-archive-text-pdf.log`。App Store Connect 服务端验证与上传由项目负责人继续操作。

- 覆盖核心记分规则、换人、DH/TB、历史复核、统计口径、导出、迁移与备份往返。
- 性能样本：150 场比赛、约 7,500 条逐球事件，统计聚合及完整备份往返在模拟器上的初次测量平均约 0.34 秒；此数字不是实机性能保证。
- PDF 样例已渲染检查中文、分页、页码、事件顺序和前后垒况；海报已检查队名、时间、场地及备注版面。
- UI 自动化包含正常字号下的创建比赛、阵容、记分、统计、PDF 预览、系统分享、备份文件选择和新建比赛后制作海报。
- 图标已核对为 1024 × 1024；App 内规则 PDF 与隐私清单纳入构建资源。

## 数据位置

所有业务数据均保存在 App 沙盒内的 Core Data SQLite。偏好保存在本机 UserDefaults；报表与海报在本机生成。没有账号系统、云端同步、广告、分析 SDK 或业务服务器请求，当前版本不需要部署服务器。

自动备份也在 App 沙盒内，卸载时会被删除。需要长期保留的数据应从“我的 → 本地数据 → 备份与恢复”导出到 App 之外。恢复替换全量业务数据，不合并，不包含外观偏好。备份未加密；SHA-256 仅用于完整性校验。

## 发布配置

| 项目 | 当前值 |
| --- | --- |
| Bundle ID | `com.jasonchen.baseballmaster` |
| 版本 / Build | `1.5` / `3` |
| 开发团队 | `JUTVG7XR9H` |
| 最低系统 | iOS 16 |
| 构建工具 | Xcode 26.1.1 / iOS 26.1 SDK |
| 归档位置 | `output/build/BaseballMaster.xcarchive` |
| 数据库 / 备份格式 | Core Data V2 / Backup V1 |

当前构建工具满足 Apple 自 2026-04-28 起的最低上传 SDK 要求，参见 [Apple 上传要求](https://developer.apple.com/news/upcoming-requirements/?id=04282026a)。

`PrivacyInfo.xcprivacy` 声明不跟踪、不收集上传用户数据，以及 App 自身 UserDefaults 的 `CA92.1` 使用原因；相关定义见 [Apple Required Reason API](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)。`ITSAppUsesNonExemptEncryption` 为 `false`，本 App 不实现非豁免加密功能；备份校验哈希不用于加密数据。

## 上传 TestFlight

1. 在 Xcode 打开 `output/build/BaseballMaster.xcarchive`，进入 Organizer。
2. 选中版本 **1.5 (3)** 的归档，确认对应现有 App 的 Bundle ID。项目的 Debug / Release 均已设置 Version `1.5`、Build `3`，可在 **TARGETS → BaseballMaster → General → Identity** 核对。项目设置变更不会修改已经生成的旧归档，应使用本次重新生成的归档。
3. 选择 **Distribute App → App Store Connect**，用你的开发者账号完成签名检查、Validate 和 Upload。若需严格保留 Build `3`，使用 **Custom → App Store Connect → Upload**，在分发选项中取消 **Manage version and build number**，避免 Xcode 自动调整构建号；提交前确认上传摘要为 `1.5 (3)`。若此构建号已经上传过，应递增 Build 并重新归档。相关说明见 [Apple 版本与构建号设置](https://help.apple.com/xcode/mac/current/en.lproj/devba7f53ad4.html)及[分发准备](https://developer.apple.com/documentation/xcode/preparing-your-app-for-distribution)。
4. 等待 Apple 处理构建，在 App Store Connect 的 TestFlight 中填写测试说明、联系方式并选择测试组。
5. 外部测试按 App Store Connect 的实际提示提交 Beta App Review。

发布签名、账号权限、Apple 服务端验证与构建处理结果以 Organizer / App Store Connect 返回为准。本地 Archive 成功不等于已上传或通过 Beta 审核。Apple 操作说明：[分发用于测试的 App](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases)。

建议测试说明：

> 本版提供离线棒球记分、球队与球员管理、赛季统计、专业 PDF 战报与逐打席速报，以及比赛宣传图片。请重点验证创建比赛、记分后重启恢复、比赛结束后的统计、海报与 PDF 分享、完整备份导出与恢复。数据仅存本机，不需要注册账号。

## 已确认事项

- 项目负责人于 2026-09-17 明确确认：已取得内置《中国棒球协会棒球规则 2022 版》随 App / TestFlight 分发的授权。
- App 保留规则资料名称、版本与来源说明，PDF 原文未改动；比赛以赛事规程和现场裁判为准。
- 本轮不进行真机验收，按项目负责人要求直接推进模拟器验证与发布准备。
