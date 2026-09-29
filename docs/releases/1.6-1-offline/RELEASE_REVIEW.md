# BaseballMaster · TestFlight 1.6（构建 1）发布交接

**V1.1.1 本轮进度：**开发与本地回归见[验证记录](</Users/JasonChan/Documents/BaseballMaster/docs/archive/plans/1.1.1/VALIDATION.md>)。开发与本地验收已完成，投手提醒布局已按用户决定关闭；当前请求核对实际发布版本、账号签名和测试手机。本轮尚未上传 TestFlight；不能把下方旧 1.6（1）归档当成本轮产物。

**2026-09-27 状态补充：V1.1 原定开发基本完成，先完成基线验收，再处理 V1.1.1；文字直播确定安排在 V1.2.1。** 详见[版本推进规划](</Users/JasonChan/Documents/BaseballMaster/docs/archive/plans/VERSION-ROADMAP-1.1-1.2.1.md>)。本文件下方签名／上传／真机结论保留为 **9 月 23 日的证据快照**；9 月 26 日实际测试包的版本、渠道及最新发布状态待回填，不能据此直接判断今日状态。

更新：2026-09-23；分支：`BM_1.1_dev`。本次使用 **1.6（1）**，沿用原 V1.1 需求编号。按最新决定，**文字直播延后到 V1.2.1 正式迭代，本次不部署、不显示入口、不上传比赛数据**。V1.2.1 是后续迭代称呼，其实际 App 版本号另行确定，不直接改变当前工程 1.6 版本号。

**新的离线候选已完成 Release 开发签名归档和回归；尚未上传或分发 TestFlight。** 最新自动签名导出返回 `No Accounts` 和 `No signing certificate "iOS Distribution" found`；Xcode 没有可用的开发者账号。需要在 Xcode 恢复现有团队账号并配置有效分发签名；本地归档成功不代表已上线。

## 本次交付

| 内容 | 位置／状态 |
| --- | --- |
| 当前唯一上传候选 | `output/build/BaseballMaster-1.6-1-offline.xcarchive` |
| 网站更新包 | [BaseballMaster-1.6-website.zip](output/website/1.6-offline/BaseballMaster-1.6-website.zip)，仅两份 HTML 和替换说明 |
| 隐私／支持原文件 | [网站文件目录](output/website/1.6-offline/README.md)，同步副本保留在 TestFlight 和 app-store 目录 |
| TestFlight 材料 | [交付目录](output/testflight/1.6-1/README.md)：测试说明、Beta 介绍／审核说明、申报依据、截图和真机清单 |
| 本次验证 | [QA 报告](output/testflight/1.6-1/qa/README.md)；最新证据在 `qa/offline/` |
| 已过时的直播候选 | `output/build/BaseballMaster-1.6-1.xcarchive`，**不要上传**；旧说明在 `docs/releases/1.6-1-live-draft/` |
| 1.5 历史归档 | `output/build/BaseballMaster.xcarchive`，仍为 1.5（3），保留 |

本次功能包括稳定打席 ID、球队删除保护、可空姓名／00 背号、半局得分上限、终场恢复、教练投手规则、普通投手球数，以及大屏一屏记分／小屏移除场地图。升级前独立备份、副本迁移、完整验证和失败恢复继续保留。直播代码及服务端成果保存供后续使用，本次没有启用。

## 版本、数据和验证

| 项目 | 值 |
| --- | --- |
| Bundle ID / Team | `com.jasonchen.baseballmaster` / `JUTVG7XR9H`，沿用旧版 |
| Version / Build | `1.6` / `1` |
| 系统 / 构建工具 | iOS 16 起、iPhone；Xcode 26.1.1（17B100）/ iOS 26.1 SDK |
| 数据格式 | Core Data V3 / Backup V1 兼容读取 |
| 归档签名 | Apple Development；上传前须完成 App Store 分发签名 |
| 隐私清单 | 不跟踪，收集数据类型为空；UserDefaults 理由 CA92.1 保留 |

当前全量 **118 项单元测试**、**4 项大屏定向 UI**和 **3 项小屏定向 UI 测试**通过。新增测试验证关闭直播后不读取／改写发布凭证，不启动同步，不发出请求；记分及结果页均无直播入口，关于页面与离线行为一致。保留的未来直播实现只在显式开启的 DEBUG 内存测试中联调，不进入本次 Release 启动参数。

此前完整基线为 117 项单元、44 项大屏 UI、6 项小屏 UI、11 项服务端测试通过。本次大小屏增量复测、截图和归档核验以 [QA 报告](output/testflight/1.6-1/qa/README.md) 为准，不将历史全量 UI 记录当作新归档的全量重跑。

历史 1.0（`b7b658b`）和 1.5（`0d1d1f2`）源码各自生成 V2 数据库后覆盖安装 1.6，旧字段／顺序、设置、原库哈希、重启、继续原比赛和备份恢复对照通过。证据保留在 `qa/upgrade-from-1.0/`、`qa/upgrade-from-1.5/`。这不等于已发布二进制在真机上的覆盖升级；合成样本的独立旧 playerGameRecords 集合为空，其他旧集合及故障情况由单元测试补充。

升级保护不依赖网络或用户先手动备份。原库与升级恢复副本不参与日常最近三份自动备份轮换；迁移失败停止写入并提供恢复入口，不清库或生成 Demo。真实升级验收仍待执行，须保留原 App 和数据。

Apple 香港商店 2026-09-23 公开查询返回销售版本 1.0（App ID `6802063886`），本机旧归档为 1.5（3）；销售版本字符串不证明实际安装构建。Connect 未登录，**1.6（1）是否已占用仍需核对**。当前工具链符合已核对的 Xcode 26／iOS 26 SDK 上传要求，最终以 Apple 校验为准。[Apple 上传要求](https://developer.apple.com/news/upcoming-requirements/?id=04282026a)

## 更新与上传步骤

### 1. 网站 HTML（用户已完成发布，待核对公开 URL）

2026-09-23 用户确认两份网页均已发布。实际公开 URL 尚待提供，因此未将 HTTPS、页面内容／链接和 Connect 地址一致性标为已核验。下面保留替换操作，后续只需完成地址与线上申报核对。

1. 下载 [网站更新包](output/website/1.6-offline/BaseballMaster-1.6-website.zip)。宝塔 → 网站 → jingsen.cc → 根目录，定位 App Store Connect 当前隐私政策和技术支持 URL 对应的文件，先备份旧文件。
2. 替换 `baseballmaster-privacy.html` 和 `baseballmaster-support.html`，保持原公开 URL。两页放同一目录；若使用不同文件名，修改支持页底部的相对隐私链接。README 不必上传。
3. 手机浏览器打开两个 HTTPS URL，确认“适用于当前离线版本（含 TestFlight 1.6）”、联系方式、升级／备份说明和支持页的隐私链接。若使用 CDN，只刷新这两个页面的缓存；日期不同则修改更新日期。
4. 若改变 URL，同时修改 Connect 的 Privacy Policy URL／Support URL；未改变则保留原值。本次无需 Node、直播 API 或反向代理。
5. 按 [隐私更新清单](output/testflight/1.6-1/app-privacy-checklist.md) 核对线上申报。本版无 App 自行收集／上传比赛资料，不能继续套用先前直播候选的姓名／用户内容收集申报；TestFlight 平台测试信息和用户主动联系支持另有说明。

### 2. 处理签名和构建号

Xcode → Settings → Accounts，重新登录现有 Apple Developer 账号，确认团队 `JUTVG7XR9H`；在 Manage Certificates／Organizer 使用有效 Apple Distribution 或团队允许的云端管理签名。权限不足时由该团队负责人配置。保持 Bundle ID 和 Team 不变。

登录 App Store Connect 的现有 App → TestFlight，确认 **1.6（1）** 尚未上传。若已经占用，需要确定新的构建号并重新归档，不能覆盖同号构建。本次未擅自递增。

### 3. 上传新离线归档

1. 用 Xcode 打开 `output/build/BaseballMaster-1.6-1-offline.xcarchive`，核对版本 **1.6（1）**；不要选旧直播候选。
2. Organizer → Distribute App → App Store Connect（或 Custom → App Store Connect → Upload），完成分发签名和 Validate。
3. **关闭 Manage version and build number**，上传摘要保持 1.6（1）。`ExportOptions.plist` 已关闭自动调整，但不会修复缺失的证书。
4. 等 Apple 处理完成，使用交付目录中的 Beta 介绍和测试说明，核对反馈邮箱，先向负责验收的内部测试人员开放。

TestFlight 与正式 App Store 上架分别管理；本次无需发布新的销售版本或准备线上直播示例。[Apple 上传构建](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds)、[TestFlight 测试说明](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)

### 4. 真机覆盖升级后扩大测试

按 [真机清单](output/testflight/1.6-1/physical-upgrade-checklist.md)，在保留旧数据的手机上直接安装测试版。首次启动离线核对记录，再重启、继续原比赛及导出备份；检查记分和结果页无直播入口。不要卸载重装代替覆盖升级。已配对手机上次检查未连接，未执行这项验收。

通过后扩大测试。外部测试按 Connect 提示提交 Beta App Review，填写真实联系人，使用 `beta-review-notes-en.txt`。规则 PDF 仍保留原内容，公开来源已核对但再分发许可尚未确认；该旧有资料问题继续独立跟踪，不在审核说明中声明已获授权。[Apple 测试信息要求](https://developer.apple.com/help/app-store-connect/test-a-beta-version/provide-test-information)

## 2026-09-23 发布状态记录（最新状态待回填）

| 阶段 | 状态 |
| --- | --- |
| 直播延期及关闭入口／同步 | 已完成并测试；代码保留 |
| 隐私／支持 HTML 发布 | 用户已确认两份均发布；待提供公开 URL 并核验 |
| App 隐私清单和 Beta 资料 | 本地完成；Connect 隐私答案及地址尚待核对 |
| 新离线 Release 归档 | 成功，开发签名校验通过 |
| App Store 分发导出 | 自动签名重试失败：No Accounts／缺 Distribution 证书，未生成 IPA；见 `qa/offline/export-auto.log` |
| Connect 构建号核对、上传、Apple 处理 | 未完成 |
| 已发布旧版真机覆盖升级、内部可安装验收 | 未执行 |
| 外部 Beta 审核／正式上架 | 未提交 |
| 直播生产部署、公网观赛、到期删除验收 | **延后到 V1.2.1，不是本次 TestFlight 前置条件** |
