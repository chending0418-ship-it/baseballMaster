# App Store 填写与宣传资料总清单

更新：2026-11-04。对应 iOS 3.1.2（Build 1）；仅 App Store 版本字段与审核备注需要版本号，公开宣传不写版本号。

## 按字段取用

| App Store Connect 字段 | 直接取用 |
| --- | --- |
| 名称 | 棒球比赛记录大师 |
| 副标题 | 棒球慢垒记分、球队共享与统计 |
| 宣传文本 | [复制本文件全文](promotional-text-zh-Hans.txt) |
| 描述 | [复制本文件全文](description-zh-Hans.txt) |
| 此版本新增内容 | [复制本文件全文](whats-new-zh-Hans.txt) |
| 关键词 | [复制本文件全文](keywords-zh-Hans.txt)，不额外加空格或文件名 |
| App 审核信息 → 备注 | [英文主稿](review-notes-en.txt)；[中文对照](review-notes-zh-Hans.txt)，二者择一，不同时粘贴 |
| 需要登录 | 否；不填虚构审核账号 |
| 营销 URL | https://baseballmaster.cc/scorekeeper |
| 支持 URL | https://baseballmaster.cc/scorekeeper/support.html |
| 隐私政策 URL | https://baseballmaster.cc/scorekeeper/privacy.html |
| 隐私选择 URL（如填写） | https://baseballmaster.cc/scorekeeper/privacy.html#rights |
| 英文支持 | https://baseballmaster.cc/scorekeeper/support-en.html |
| 英文隐私 | https://baseballmaster.cc/scorekeeper/privacy-en.html |
| App 备案 | 沪ICP备2026058730号-2A；用户已确认商店配置完成 |
| 审核邮箱 | chending0418@gmail.com |

以上网页已部署上海并通过匿名 HTTPS；首页底部网站备案为沪ICP备2026058730号-1，两种编号不要混填。旧隐私／支持地址继续可读。

## 商店图片

取 `output/releases/3.1.2/AppStore截图.zip`，共 11 组 34 张。解压后按 Connect 实际设备栏目选择对应子目录：iPhone 三档、Duo 内外屏横竖四档、iPad 两种分辨率横竖四档。每档图片按文件名前的数字排序；不要上传 overview.png 或 index.html，不把不同设备尺寸混为一组。详见 [尺寸表](APPSTORE-ARTWORK.md)。App 预览视频本轮未制作，PNG 上传到截屏栏。

## App 隐私

根据 [逐类填写依据](APP-PRIVACY.md) 核对姓名、其他用户内容、用户 ID、设备 ID、产品交互五项；用途为 App 功能、与身份关联、不用于追踪。不要选择整款 App“不收集数据”。服务处理及最终商店问卷须与当前上海隐私正文、最终构建保持一致。

## 宣传取用

- 小红书：`output/marketing/3.1.2/xiaohongshu/2026-11-04/宣传图片与文案.zip`，6 张 PNG/JPG 与可复制正文。
- 微信：`output/marketing/3.1.2/wechat/2026-11-04/宣传图片与文案.zip`，一页 PNG/JPG 与可复制正文。
- 官网已用四设备透明底效果图；社交海报保留完整摄影背景。四台为 iPad、iPhone、Duo、Android 手机，折叠主视觉只留 Duo。
- 当前素材已排除被指认的真实未成年人资料；不使用旧 Android 比赛、名单或统计截图。所有宣传无版本号，Android标注测试中。

## 提交前仍由实际发布流程完成

这批文字、图片和网页可以取用，未代您填写 App Store Connect、上传新构建或提交审核。审核联系人姓名／电话、Copyright、类别、分级、定价和发布方式沿用并核对真实账户信息，不填占位值。iOS 正式 Release 归档与 App Store IPA 导出已完成（见 [ARCHIVE.md](ARCHIVE.md)），尚未上传、提交审核或上架；共享球队批量导入后台已在上海上线，有权限的管理员与记录员可整批新增，旧客户端单人编辑保持兼容；部署后的九项公网专项已通过并保留独立回执。社交宣传安排在相应 App 功能实际可下载后发布。

## 2026-11-04 共享批量服务上线（当前）

上海共享批量后台已按用户新增授权部署，批次 `bulk-3.1.2-20261104T131831Z`。仅 `server.mjs`、`roster.mjs` 两文件更新并核对 SHA，部署前加密备份成功；33 张表无 DDL、停服期间未恢复或改写数据库，启动后完整性通过；其他五个服务 PID 未变。协议保持 1、最低 App 版本保持 3.0，`bulkRosterImport` 能力已开启；权限、修订、事务和幂等校验继续保留，旧客户端单人编辑兼容。九项公网合成专项已全部通过，合成球队按正常流程解散、临时凭据删除；部署后日备份和完整状态备份成功，配置备份下载、解密和摘要核验通过。最终回执已冻结，可按用户授权经本 PR 合入 main。

部署证据：[deployment-receipt.json](../../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json)。 公网证据：[public-verification.json](../../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/public-verification.json)。后续网站批次 `website-20261104T132137Z` 的 12 文件摘要、92 其他静态文件、18 项公网视口及启动清单刷新均通过。此状态替代前述网站发布时点的批量待部署说明。用户已授权本版通过 PR 合入 main；该后台发布时点尚未正式归档；随后已完成正式 Release 归档与 App Store IPA 导出，见 [ARCHIVE.md](ARCHIVE.md)。App 上传、提交审核及上架仍未执行。

## 2026-11-04 正式 Release 归档与 App Store IPA 导出（当前）

已从干净 main `3d3d83a6aaf64be353e0f71c749a3e7e3ad879d6` 完成 iOS 3.1.2（Build 1）Release generic iOS device（arm64）归档，并以 `app-store-connect`／`destination=export` 导出 App Store 分发 IPA，未使用上传。签名、版本／Build／Bundle ID、设备族、最低 iOS、隐私 plist 与 dSYM 一致性核对通过；导出包 `get-task-allow=false`，无设备白名单。

正式目录：`output/releases/3.1.2/archives/BaseballMaster-3.1.2-build1.xcarchive`；IPA：`output/releases/3.1.2/app-store-export/BaseballMaster.ipa`，14,793,278 bytes，SHA-256 `f3f068b8f9dfe695f1902a2dc73affb1b54ce13d1966f532f69b18cd631cc572`。源归档的自动开发签名与导出后的 App Store 分发重签分别记录；正式二进制／签名不进入 Git 或宣传 ZIP。详见 [ARCHIVE.md](ARCHIVE.md)。

本段替代此前按日期保留的“未正式归档”阶段状态。App 尚未上传、提交审核或上架，归档／导出不代表 Apple 服务端验证通过。本次没有重跑 App 功能测试、修改源码或版本。
