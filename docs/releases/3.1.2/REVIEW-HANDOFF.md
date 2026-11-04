# iOS 3.1.2 发布审核交接

准备日期：2026-11-04。**3.1.2（Build 1）**，Bundle ID `com.jasonchen.baseballmaster`，最低 iOS 16，设备族 `1,2`。本交接只记录文案与图片准备的交付边界，不代表正式归档、上传、审核或上架完成。

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

仅本地球队与对手的批量名单不依赖新增服务。共享批量能力虽已实现且后端本地验证通过，生产尚未更新，App 会检测 `bulkRosterImport` 并在未支持时保留草稿。正式提交前按用户安排更新共享生产服务，并完成兼容、整批新增／失败不部分写入与旧单人编辑验证；未闭环前保留商店文案中的条件说明。

## 本轮实际检查

- 读取工程配置，确认版本／Build、Bundle ID、最低 iOS 和 iPhone／iPad 设备族，不更改工程。
- 对照批量名单、统计页、投手规则、共享能力检测、开屏请求与隐私清单源码，以及既有开发验收记录编写文案。
- 校验宣传文本、完整描述、更新说明的字符数，以及关键词／审核备注的 UTF-8 字节数。关键词 97 bytes，英文备注 3697 bytes（均去除文件末尾换行）。
- 素材验证未重新运行 App 功能／真机验收、构建或归档，未代改商店配置；上海接管与后续授权网站发布分别采用独立任务回执。既有各批次测试数不相加为本轮测试总数。

## 发布阶段保持分开

| 阶段 | 现状／要求 |
| --- | --- |
| 实现 | 3.1.2 功能及修复完成，详见版本计划；不据此推断服务／商店状态 |
| 构建验证 | 既有 Debug／未签名设备 Release 证据可追溯；不是本次正式归档 |
| 正式归档 | 待本版最终源码及配置核对后执行；正式备案已通过，核对签名、版本、隐私清单和 dSYM |
| 旧记录 | 真实两场旧比赛待本机原件核对；本轮不补传、不覆盖生产 |
| 备案 | 正式 App 备案 `沪ICP备2026058730号-2A` 已通过，用户确认商店备案配置完成；同期任务已本地补充关于页，尚未发布。本轮未独立读取商店回执 |
| 上海环境 | 独立迁移任务已由上海接管正式在线服务与主业务数据；香港受限迁移／回退副本仍保留，不作为另一套对外主服务。迁移完整副本目前无自动到期，日常球队备份按 30 天管理 |
| 服务更新 | 共享批量名单尚未部署；已部署开屏服务不代表所有新增服务能力上线 |
| 隐私／支持网页 | 中英文新四页已在上海上线，匿名 HTTP 200；旧 `/privacy.html` 与 `/support.html` 同样 HTTP 200 兼容。内容反映上海接管与两类备份保留，App 硬编码本轮不改 |
| 官网新版本介绍 | 已获授权在上海更新产品页，匿名 HTTP 200；使用透明底四设备主视觉，不代表 App 新版已上架 |
| App Store Connect | 未代填、未上传、未提交，联系人／版权／分级等使用账户真实值 |
| 审核／上架 | 待真实回执；宣传稿不等于已上线公告 |

新网址为 `https://baseballmaster.cc/scorekeeper/privacy.html`、`https://baseballmaster.cc/scorekeeper/support.html`；英文独立页为同目录 `privacy-en.html`、`support-en.html`。新四页已上线，匿名 HTTPS 均为 HTTP 200；两种语言隐私页均有 `#rights` 锚点。旧 `/privacy.html`、`/support.html` 同步提供 HTTP 200 兼容内容，App 硬编码路径本轮不改。现可取用新 URL 填写商店；本轮未代填 App Store Connect 的网页 URL 或隐私问卷。

提交前核对最终归档与新三类设备素材、服务实际能力、隐私与支持公开 HTTPS 页面、备案主体／名称、审核联系方式及账户问卷一致。App 预览是可选视频，静态产品图应放截图栏；不要用一张 PNG 当预览视频。

Git 推送／PR、服务部署、App 上传、审核和上架分别记录。代码 PR #37 的合入状态见 CURRENT；本轮材料的最终分支／提交／PR 由主代理统一记录，不把旧功能 PR 当作本批材料提交回执。

## 2026-11-04 网站上线回执

批次 `website-20261104T125935Z` 已在上海完成静态网站发布：12 个文件的 HTTPS SHA-256 与发布清单一致，92 个其他静态文件未变，`nginx -t` 通过，后端代码及数据未修改。新四页、旧两页及产品页已另核验匿名 HTTP 200，两种语言隐私页具有 `rights` 锚点。公网多视口视觉验收由主代理另行记录，不将部署哈希核对替代视觉验收。

证据：[deployment-receipt.json](../../../output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json)。App 未因本次网页发布而归档、上传、提交审核或上架，共享批量服务仍未部署。
