# BaseballMaster · TestFlight 1.6（构建 1）发布交接

更新：2026-09-23；分支：`BM_1.1_dev`。沿用原 V1.1 文档的需求编号，本次实际 App 版本为 **1.6（1）**。01–08 的本地实现已完成；09 不再单独推进，直播首次部署并入下面的发布操作。

**当前已完成开发签名的 Release 归档；尚未导出 App Store 分发包、上传、分发或提交 Beta 审核。** 导出尝试返回 `No signing certificate "iOS Distribution" found`，Xcode 同时报账号凭据失效；需要在本机 Xcode 重新登录现有开发团队后完成分发签名。真实旧版真机覆盖更新及公网直播验收尚未完成。

## 交付内容

| 内容 | 位置／状态 |
| --- | --- |
| 新归档 | `output/releases/1.6-1-live-draft/archives/BaseballMaster-1.6-1-live-draft.xcarchive`，Archive 成功，签名校验通过 |
| 旧归档 | `output/releases/1.5-3/archives/BaseballMaster-1.5-3.xcarchive`，仍为 1.5（3），保留原文件 |
| 发布材料 | [output/releases/1.6-1-offline/testflight](../../../output/releases/1.6-1-offline/testflight/README.md)：测试说明、Beta 介绍／审核说明、隐私申报、截图及验收清单 |
| 宝塔服务包 | `output/releases/1.6-1-offline/testflight/BaseballMaster-live-baota.zip`；完整操作见 [live/README.md](../../../live/README.md) |
| 网站资料 | `output/releases/1.6-1-offline/testflight/baseballmaster-privacy.html`、`baseballmaster-support.html`；同步至 `output/releases/1.5-3/app-store/`，尚未上传网站 |
| 历史材料 | [docs/releases/1.5-3](../1.5-3/RELEASE_REVIEW.md)，仅供旧版追溯，不作为本次上传说明 |

本版主要变化：升级前独立数据保护；稳定打席 ID；球队删除保护和可空姓名／00 背号；半局得分上限与终场恢复；教练投手规则；普通投手本场球数；大屏单屏记分、小屏移除场地图；按打席的文字直播、链接／二维码分享、十秒刷新及终场一小时后删除云端内容。

## 发布配置与数据兼容

| 项目 | 值 |
| --- | --- |
| Bundle ID / Team | `com.jasonchen.baseballmaster` / `JUTVG7XR9H`，与旧归档相同 |
| Version / Build | `1.6` / `1`（Debug、Release、归档均已核对） |
| 最低系统 / 平台 | iOS 16 / iPhone |
| 构建工具 | Xcode 26.1.1（17B100）/ iOS 26.1 SDK |
| 数据库 / 备份格式 | Core Data V3 / Backup V1 兼容读取 |
| 归档签名 | Apple Development；Organizer 上传时需重新进行 App Store 分发签名 |
| 包内核验 | arm64、规则 PDF、隐私清单；本地 HTTP 测试开关和升级审计启动钩子均不在 Release 二进制中 |

Apple 香港商店公开查询在 2026-09-23 返回销售版本 **1.0**，App ID `6802063886`；本机历史归档为 **1.5（3）**。销售版本字符串不能证明当前用户手机安装的具体二进制／构建。App Store Connect 未登录，因此 **1.6（1）是否已被占用仍需核对**。

升级使用旧数据库工作副本，在首次可能写入前保存独立原库和设置，完整比较旧字段并读回验证后才切换。原库和升级恢复副本不参与日常三份自动备份轮换。升级失败进入恢复页，不重置为 Demo、不清空数据库。用户无需联网或开直播才能升级；另存手动备份只是额外保障。

当前工具链符合 Apple 的 Xcode 26／iOS 26 SDK 最低上传要求；上传时仍以 Apple 当时的校验结果为准。[Apple 上传要求](https://developer.apple.com/news/upcoming-requirements/?id=04282026a)

## 本地验证与边界

- 两条历史源码覆盖升级：`b7b658b`（1.0）、`0d1d1f2`（1.5）。仅加生成测试数据的启动钩子，使用各自原业务／Core Data V2 代码写库，再直接覆盖安装 1.6。每组包含 30 名球员、3 支球队、2 个赛季、4 场各状态比赛、11 条结构化事件和非零比赛统计。旧字段及顺序全部保留，设置保留；原 SQLite／WAL／SHM 的 SHA-256 不变；重启、继续原比赛、再重启、备份恢复及恢复后重启均通过。证据在 `qa/upgrade-from-1.0.json`、`qa/upgrade-from-1.5.json` 及各自完整合成快照中。
- 这些是历史源码二进制的模拟器验证，**不是已上架二进制在真机上的覆盖更新**；合成数据的独立旧 `playerGameRecords` 集合为空，非零打击／投手数据及守备集合在比赛状态中。旧记录集合、早期数据库、坏数据及故障注入另有单元测试覆盖。
- 117 项单元、44 项大屏 UI、6 项小屏关键 UI 及 11 项服务端测试全部通过。最终结果见 [验证报告](../../../output/releases/1.6-1-offline/testflight/qa/README.md)。覆盖记分、统计、PDF、备份、新规则、直播协议、数据恢复及大小屏显示；不把模拟器结果写成真机结果。
- 已配对 iPhone 16 Pro 当前未连接。P09 真机覆盖升级、Safari／微信公网观赛、真实网络重试和部署后的到期删除仍需按步骤执行。
- 规则 PDF 的公开来源已核对，但再分发许可仍未确认。当前归档保留原 PDF；若决定改为官方在线入口，需修改并重新归档，不能在审核说明里声称已获授权。

## 你需要执行的更新步骤（按顺序）

### 1. 部署直播并更新网站

按 [宝塔部署说明](../../../live/README.md) 上传本次 ZIP，使用 **Node 24.x（至少 24.12）**，单进程监听 `127.0.0.1:8088`；独立数据目录不公开且不做云端备份。将包内 `deploy/nginx-location.conf` 的两个 `location` 加入 jingsen.cc 现有 HTTPS 站点配置，保留其他路径。

确认 `https://jingsen.cc/baseballmaster/live/health` 返回 `{"ok":true}`，运行 `LIVE_BASE=https://jingsen.cc/baseballmaster/live node demo.mjs --smoke`。再用 `node demo.mjs` 的 `expire` 命令验证合成比赛约十五秒后失效，避免用真实比赛改时间测试。

把本次隐私／支持 HTML 覆盖到 App Store Connect 当前 URL 对应的站点文件；如果更换 URL，同步更新 Connect。按 [隐私更新清单](../../../output/releases/1.6-1-offline/testflight/app-privacy-checklist.md) 修改线上数据申报。**不能继续使用“完全不收集数据”的旧版答案。**

### 2. 修复本机发布签名并核对构建号

Xcode → Settings → Accounts，重新登录现有开发者 Apple ID，确认团队 `JUTVG7XR9H`。在 Manage Certificates／Organizer 分发流程中使用有效的 Apple Distribution 或团队允许的云端管理签名；如权限不足，由该团队 Account Holder／Admin 配置。不要换 Bundle ID、团队或创建另一个 App。

登录 App Store Connect，进入现有 App 的 TestFlight，确认 **1.6（1）** 未上传过。若已占用，Apple 不接受替换同一构建号；先确定新构建号再重新归档，本次没有擅自递增。

### 3. 上传归档，仅先开放内部验收

1. 用 Xcode 打开 `output/releases/1.6-1-live-draft/archives/BaseballMaster-1.6-1-live-draft.xcarchive`，在 Organizer 核对版本 **1.6（1）**。
2. Distribute App → App Store Connect；如走自定义路径，选择 Custom → App Store Connect → Upload。完成分发签名／Validate。
3. **关闭 Manage version and build number**，上传摘要必须仍是 `1.6（1）`。已提供 `ExportOptions.plist`，其自动调整版本开关为 false；该文件不会修复失效的账号／证书。
4. 上传并等 Apple 处理完成后，填入 `beta-app-description-zh.txt`、`what-to-test-zh.txt`，核对测试反馈邮箱。先只添加负责验收的内部测试人员。

TestFlight 不等于正式 App Store 上架，不需要为了内部测试立即发布新的销售版本。内部／外部测试资格及人数以账号页面实际显示为准。[Apple 上传构建](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds)、[TestFlight 说明](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)

### 4. 真机升级与公网直播验收

按 [真机清单](../../../output/releases/1.6-1-offline/testflight/physical-upgrade-checklist.md)，在保留旧版数据的手机上直接安装测试版，首次启动断网核对数据，再重启、继续原比赛及导出备份。不要卸载重装来代替覆盖升级。

使用独立测试比赛开播，另一部手机在 Safari 和微信打开分享链接，检查十秒更新、撤销修订、断网补传、关闭和终场删除。不要让测试操作改动已有真实比赛。

### 5. 扩大测试／外部 Beta 审核

上述验收通过、规则资料处理方案落实后，再扩大 TestFlight 测试范围。外部测试按 Connect 提示提交 Beta App Review，填写真实审核联系人，粘贴 `beta-review-notes-en.txt`。其流程指导审核员自行创建新比赛开播，避免填入一小时后失效的固定链接；可在审核需要时重新开播提供临时示例。

App Store 宣传截图不是内部 TestFlight 上传的前置条件；本次已补充 1.6 的实际页面截图供验收及后续商店更新，旧 1.5 素材保留。[Apple 测试信息要求](https://developer.apple.com/help/app-store-connect/test-a-beta-version/provide-test-information)

## 状态记录

| 阶段 | 状态 |
| --- | --- |
| 功能实现及本地回归 | 已执行；详细结果见验证报告 |
| 1.6（1）Release 归档／本地签名校验 | 已完成 |
| App Store 分发包导出 | 已尝试，缺 Distribution 证书／账号凭据失效，未成功 |
| 公网直播部署／政策网页发布 | 待用户按宝塔步骤执行 |
| TestFlight 上传／Apple 处理 | 未执行 |
| 已发布旧版的真机覆盖升级 | 未执行；保留为验收条件 |
| 外部 Beta 审核／正式上架 | 未提交；不以本地归档成功代替 |
