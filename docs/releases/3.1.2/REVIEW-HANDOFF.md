# iOS 3.1.2 发布审核交接

准备日期：2026-11-04。**3.1.2（Build 1）**，Bundle ID `com.jasonchen.baseballmaster`，最低 iOS 16，设备族 `1,2`。本交接包含文案、图片及已完成的正式 Release 归档／App Store IPA 导出；尚未上传、提交审核或上架。

## 资料取用

| 材料 | 入口 |
| --- | --- |
| 全部商店字段／账户待填项 | [APPSTORE-METADATA.md](APPSTORE-METADATA.md) |
| 宣传文本 | [promotional-text-zh-Hans.txt](promotional-text-zh-Hans.txt) |
| 产品描述 | [description-zh-Hans.txt](description-zh-Hans.txt) |
| 更新说明 | [whats-new-zh-Hans.txt](whats-new-zh-Hans.txt) |
| 关键词 | [keywords-zh-Hans.txt](keywords-zh-Hans.txt) |
| 审核备注英文主稿 | [review-notes-en.txt](review-notes-en.txt) |
| 审核备注中文对照 | [review-notes-zh-Hans.txt](review-notes-zh-Hans.txt) |
| 隐私申报依据 | [APP-PRIVACY.md](APP-PRIVACY.md) |
| 实现与设备验收证据 | [DEVELOPMENT.md](DEVELOPMENT.md) |
| 本版唯一发布计划 | [3.1.2 发布功能计划](<../../plans/3.1.2 发布功能计划.md>) |

英文／中文审核备注二选一粘贴，主用英文。不要把内部部署状态表、待填占位字段或两种语言全文一并粘贴到审核 Notes。

## 新增及继承范围

本版新增 iPad 原生工作台、iPhone Duo 三态布局与草稿保护、批量名单、统计页球员落点，并修复投手额度及球员对应后更正上传。继承 3.1.1 的本机完整原件、独立赛后确认上传、完整 Pro 结果及本机文字直播；继承此前基础版／Pro、球队共享、设备授权／交接、备份与统计能力。Pro 无订阅／内购。

本机球队、对手及共享球队均支持整批名单输入，上海共享批量服务已上线并公开 `bulkRosterImport`。管理员和记录员按权限操作，权限／修订检查、整批事务及幂等重试继续生效；旧客户端的单人编辑保持兼容。部署和九项公网专项均已通过，包括兼容、权限、整批新增、拒绝部分写入、幂等、旧单人入口与撤权保护，采用独立验收回执。

## 本轮实际检查

- 读取工程配置，确认版本／Build、Bundle ID、最低 iOS 和 iPhone／iPad 设备族，不更改工程。
- 对照批量名单、统计页、投手规则、共享能力检测、开屏请求与隐私清单源码，以及既有开发验收记录编写文案。
- 校验宣传文本、完整描述、更新说明的字符数，以及关键词／审核备注的 UTF-8 字节数。关键词 97 bytes，英文备注 3743 bytes（均去除文件末尾换行）。
- 素材验证未重新运行 App 功能／真机验收、构建或归档，未代改商店配置；上海接管与后续授权网站发布分别采用独立任务回执。既有各批次测试数不相加为本轮测试总数。

## 发布阶段保持分开

| 阶段 | 现状／要求 |
| --- | --- |
| 实现 | 3.1.2 功能及修复完成，详见版本计划；不据此推断服务／商店状态 |
| 构建验证 | 既有 Debug／未签名 Release 验证保留；新增本次 Release generic iOS device arm64 正式归档和导出回执，未重跑 App 功能测试 |
| 正式归档 | 已从干净 main `3d3d83a` 完成 Release 真机 arm64 `.xcarchive`，并导出重新签名的 App Store 分发 IPA；版本／Build／Bundle ID、签名、隐私清单及 dSYM 核对通过，见 [ARCHIVE.md](ARCHIVE.md) |
| 旧记录 | 真实两场旧比赛待本机原件核对；本轮不补传、不覆盖生产 |
| 备案 | 正式 App 备案 `沪ICP备2026058730号-2A` 已通过，用户确认商店备案配置完成；同期任务已本地补充关于页，尚未发布。本轮未独立读取商店回执 |
| 上海环境 | 独立迁移任务已由上海接管正式在线服务与主业务数据；香港受限迁移／回退副本仍保留，不作为另一套对外主服务。迁移完整副本目前无自动到期，日常球队备份按 30 天管理 |
| 服务更新 | 上海共享批量服务已上线，协议 1／最低 App 3.0 保持；管理员与记录员权限及旧单人编辑兼容保留。九项公网专项已通过 |
| 隐私／支持网页 | 中英文新四页已在上海上线，匿名 HTTP 200；旧 `/privacy.html` 与 `/support.html` 同样 HTTP 200 兼容。内容反映上海接管与两类备份保留，App 硬编码本轮不改 |
| 官网新版本介绍 | 已获授权在上海更新产品页，匿名 HTTP 200；使用透明底四设备主视觉，不代表 App 新版已上架 |
| App Store Connect | 未代填、未上传、未提交，联系人／版权／分级等使用账户真实值 |
| 审核／上架 | 待真实回执；宣传稿不等于已上线公告 |

新网址为 `https://baseballmaster.cc/scorekeeper/privacy.html`、`https://baseballmaster.cc/scorekeeper/support.html`；英文独立页为同目录 `privacy-en.html`、`support-en.html`。新四页已上线，匿名 HTTPS 均为 HTTP 200；两种语言隐私页均有 `#rights` 锚点。旧 `/privacy.html`、`/support.html` 同步提供 HTTP 200 兼容内容，App 硬编码路径本轮不改。现可取用新 URL 填写商店；本轮未代填 App Store Connect 的网页 URL 或隐私问卷。

提交前核对最终归档与新三类设备素材、服务实际能力、隐私与支持公开 HTTPS 页面、备案主体／名称、审核联系方式及账户问卷一致。App 预览是可选视频，静态产品图应放截图栏；不要用一张 PNG 当预览视频。

Git 推送／PR、服务部署、App 上传、审核和上架分别记录。代码 PR #37 的合入状态见 CURRENT；本轮材料的最终分支／提交／PR 由主代理统一记录，不把旧功能 PR 当作本批材料提交回执。

## 2026-11-04 网站上线回执

批次 `website-20261104T125935Z` 已在上海完成静态网站发布：12 个文件的 HTTPS SHA-256 与发布清单一致，92 个其他静态文件未变，`nginx -t` 通过，后端代码及数据未修改。新四页、旧两页及产品页已另核验匿名 HTTP 200，两种语言隐私页具有 `rights` 锚点。公网多视口视觉验收由主代理另行记录，不将部署哈希核对替代视觉验收。

证据：[deployment-receipt.json](../../../output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json)。App 未因本次网页发布而归档、上传、提交审核或上架，共享批量服务在该次网页发布时点尚未部署；随后上线状态见下节。

## 2026-11-04 共享批量服务上线（当前）

上海共享批量后台已按用户新增授权部署，批次 `bulk-3.1.2-20261104T131831Z`。仅 `server.mjs`、`roster.mjs` 两文件更新并核对 SHA，部署前加密备份成功；33 张表无 DDL、停服期间未恢复或改写数据库，启动后完整性通过；其他五个服务 PID 未变。协议保持 1、最低 App 版本保持 3.0，`bulkRosterImport` 能力已开启；权限、修订、事务和幂等校验继续保留，旧客户端单人编辑兼容。九项公网合成专项已全部通过，合成球队按正常流程解散、临时凭据删除；部署后日备份和完整状态备份成功，配置备份下载、解密和摘要核验通过。最终回执已冻结，可按用户授权经本 PR 合入 main。

部署证据：[deployment-receipt.json](../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json)。 公网证据：[public-verification.json](../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/public-verification.json)。后续网站批次 `website-20261104T132137Z` 的 12 文件摘要、92 其他静态文件、18 项公网视口及启动清单刷新均通过。此状态替代前述网站发布时点的批量待部署说明。用户已授权本版通过 PR 合入 main；该后台发布时点尚未正式归档；随后已完成正式 Release 归档与 App Store IPA 导出，见 [ARCHIVE.md](ARCHIVE.md)。App 上传、提交审核及上架仍未执行。

## 2026-11-04 正式 Release 归档与 App Store IPA 导出（当前）

已从干净 main `3d3d83a6aaf64be353e0f71c749a3e7e3ad879d6` 完成 iOS 3.1.2（Build 1）Release generic iOS device（arm64）归档，并以 `app-store-connect`／`destination=export` 导出 App Store 分发 IPA，未使用上传。签名、版本／Build／Bundle ID、设备族、最低 iOS、隐私 plist 与 dSYM 一致性核对通过；导出包 `get-task-allow=false`，无设备白名单。

正式目录：`output/releases/3.1.2/archives/BaseballMaster-3.1.2-build1.xcarchive`；IPA：`output/releases/3.1.2/app-store-export/BaseballMaster.ipa`，14,793,278 bytes，SHA-256 `f3f068b8f9dfe695f1902a2dc73affb1b54ce13d1966f532f69b18cd631cc572`。源归档的自动开发签名与导出后的 App Store 分发重签分别记录；正式二进制／签名不进入 Git 或宣传 ZIP。详见 [ARCHIVE.md](ARCHIVE.md)。

本段替代此前按日期保留的“未正式归档”阶段状态。App 尚未上传、提交审核或上架，归档／导出不代表 Apple 服务端验证通过。本次没有重跑 App 功能测试、修改源码或版本。
