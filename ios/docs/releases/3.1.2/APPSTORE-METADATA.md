# iOS 3.1.2 App Store 元数据

准备日期：2026-11-04。工程 **3.1.2（Build 1）**，最低 iOS 16，设备族 iPhone／iPad。当前分支 `codex/dev-3.1.2`。本文为待提交资料；用户已确认 App Store Connect 备案配置完成，其余账户字段与最终隐私问卷仍按发布流程核对，不代表构建已上传或审核已通过。

## 可直接取用的字段

| 字段 | 内容／源文件 |
| --- | --- |
| 简体中文名称 | 棒球比赛记录大师（沿用；与正式 App 备案名称再次核对） |
| 简体中文副标题 | 棒球慢垒记分、球队共享与统计 |
| 版本 | 3.1.2 |
| Bundle ID | `com.jasonchen.baseballmaster`，不变 |
| App Store ID | `6802063886`，沿用原 App，不新建产品 |
| 宣传文本 | [promotional-text-zh-Hans.txt](promotional-text-zh-Hans.txt) |
| 完整描述 | [description-zh-Hans.txt](description-zh-Hans.txt) |
| 此版本新增内容 | [whats-new-zh-Hans.txt](whats-new-zh-Hans.txt) |
| 关键词 | [keywords-zh-Hans.txt](keywords-zh-Hans.txt)，UTF-8 98 bytes，不含文件结尾换行 |
| 审核备注主稿 | [review-notes-en.txt](review-notes-en.txt)，UTF-8 3743 bytes，不含文件结尾换行 |
| 审核备注中文对照 | [review-notes-zh-Hans.txt](review-notes-zh-Hans.txt)，用于核对；不与英文一起粘贴 |
| 营销 URL | `https://baseballmaster.cc/scorekeeper.html`，产品页已在上海更新，匿名 HTTPS HTTP 200 |
| 支持 URL | `https://baseballmaster.cc/scorekeeper/support.html`，新页已上线，匿名 HTTPS HTTP 200 |
| 隐私政策 URL | `https://baseballmaster.cc/scorekeeper/privacy.html`，新页已上线，匿名 HTTPS HTTP 200 |
| 隐私选择 URL | `https://baseballmaster.cc/scorekeeper/privacy.html#rights`，新页已上线，匿名 HTTPS HTTP 200，`rights` 锚点已核对 |
| 英文支持 URL | `https://baseballmaster.cc/scorekeeper/support-en.html`，独立英文页，新页已上线，匿名 HTTPS HTTP 200 |
| 英文隐私 URL | `https://baseballmaster.cc/scorekeeper/privacy-en.html`，独立英文页，新页已上线，匿名 HTTPS HTTP 200 |
| 审核联系邮箱 | `chending0418@gmail.com` |
| 登录要求 | 不需要登录，不填虚构用户名／密码；审核人员可创建虚构共享球队 |
| 定价／内购 | 沿用账户现有定价；本版无内购／订阅。Pro 是记录方式名称 |

新网址使用产品独立路径 `/scorekeeper/`，中英文各有独立页面。上海已接管正式在线服务；以上新四页与产品介绍已按用户授权上线，匿名 HTTPS 访问均返回 HTTP 200，中文及英文隐私页具有 `rights` 锚点。旧 `/privacy.html`、`/support.html` 同步返回 HTTP 200 并提供兼容内容，避免旧 App 和历史链接失效。现可按本表取用新 URL 填写 App Store Connect；本轮未代填商店网页字段或隐私问卷。App 关于页与开播告知仍硬编码旧路径，本轮不改源码；新旧路径须表达一致的实际处理规则。网页发布已由本轮独立部署流程执行并保留回执，本稿不代为修改 App 链接或商店配置。

## 提交时填写的账户项目

| 项目 | 填写方式／本轮状态 |
| --- | --- |
| 审核联系人姓名与电话 | 使用开发者账户的真实资料；电话含 `+` 与国家／地区代码。本地资料不足以确认最终提交值，不填占位电话 |
| Copyright | 沿用账户既有权利人和取得权利年份；不根据邮箱、昵称或本地用户名推定运营主体 |
| 主／次类别 | 沿用现有产品配置；此次无变更要求，不代填账户值 |
| 年龄分级 | 根据当前问卷及实际内容填写，包含用户生成的球队／球员／比赛资料；不凭体育工具属性直接写固定年龄 |
| App 备案 | 正式 App 备案已通过：`沪ICP备2026058730号-2A`，名称“棒球比赛记录大师”；同期迁移记录载明用户确认 App Store Connect 备案配置已完成。本轮未独立读取商店回执；不以网站备案号代替 App 号 |
| 出口合规 | 源码 `AppInfo.plist` 的 `ITSAppUsesNonExemptEncryption=false`；最终按待上传归档和账户问卷核对，不宣称 Apple 已接受 |
| 发布选项 | 待实际发布阶段确定；本轮不设置自动发布、不提交审核 |
| 无障碍声明 | 不根据大字／深色适配或少量场景验证直接勾选完整支持；按最终 Apple 问卷和证据填写 |
| App 隐私 | 见 [APP-PRIVACY.md](APP-PRIVACY.md)，本轮未修改后台问卷 |
| 截图／App 预览 | 使用本版材料清单；静态产品图是截图素材，不能作为 App 预览视频上传 |

## 文案口径与依赖

已实现的增量：iPad 宽屏现场工作台、iPhone Duo 内外屏／半折叠草稿保护、批量名单预览与整批新增、统计页球员击球落点、投手额度修复。继承 3.1.1 的本机完整原件、独立赛后确认上传、Pro 结果与统一球场保留。

共享球队整批新增的上海生产服务已上线，公开能力包含 `bulkRosterImport`。管理员和记录员按权限操作，事务、修订与幂等保护保留；失败时保留草稿，不逐人补写。协议仍为 1、最低 App 版本仍为 3.0，旧客户端的原单人名单编辑继续兼容；新版批量入口需具备该入口的 App。部署回执及九项公网专项均已通过，记录分开保留。

Duo 有专项模拟器证据；文案可以说明支持范围，不写“真机全面验证”“所有姿态／分屏穷举”。备案与商店备案配置采用同期用户确认；上海已接管，香港受限迁移副本仍保留且无自动到期，日常上海球队备份按 30 天管理。新版隐私／支持页与官网内容已上线并核验匿名 HTTP 200；共享批量服务已完成部署，九项公网专项已通过；正式 Release 归档与 App Store IPA 导出已完成，见 [ARCHIVE.md](ARCHIVE.md)；旧比赛原件处理及上传审核仍保留各自待办。

## 限制与来源

Apple 当前文档列出：宣传文本 170 characters、描述和更新说明各 4000 characters、关键词 100 bytes、审核 Notes 4000 bytes；支持页须提供实际联系方式。[Apple 平台版本资料](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)。本稿按字符及 UTF-8 字节分别检查，不沿用旧版超长中文关键词。

本地实现依据：`BaseballMaster.xcodeproj/project.pbxproj`、`BaseballMaster/BulkRosterImportView.swift`、`BaseballMaster/ResultsViews.swift`、`BaseballMaster/TeamCollaboration.swift`、`BaseballMaster/ConfigurableLaunchScreen.swift`、[版本计划](<../../plans/3.1.2 发布功能计划.md>)及[开发证据](DEVELOPMENT.md)。

## 2026-11-04 网站上线回执

批次 `website-20261104T125935Z` 已在上海完成静态网站发布：12 个文件的 HTTPS SHA-256 与发布清单一致，92 个其他静态文件未变，`nginx -t` 通过，后端代码及数据未修改。新四页、旧两页及产品页已另核验匿名 HTTP 200，两种语言隐私页具有 `rights` 锚点。公网多视口视觉验收由主代理另行记录，不将部署哈希核对替代视觉验收。

证据：[deployment-receipt.json](../../../../output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json)。App 未因本次网页发布而归档、上传、提交审核或上架，共享批量服务在该次网页发布时点尚未部署；随后上线状态见下节。

## 2026-11-04 共享批量服务上线（当前）

上海共享批量后台已按用户新增授权部署，批次 `bulk-3.1.2-20261104T131831Z`。仅 `server.mjs`、`roster.mjs` 两文件更新并核对 SHA，部署前加密备份成功；33 张表无 DDL、停服期间未恢复或改写数据库，启动后完整性通过；其他五个服务 PID 未变。协议保持 1、最低 App 版本保持 3.0，`bulkRosterImport` 能力已开启；权限、修订、事务和幂等校验继续保留，旧客户端单人编辑兼容。九项公网合成专项已全部通过，合成球队按正常流程解散、临时凭据删除；部署后日备份和完整状态备份成功，配置备份下载、解密和摘要核验通过。最终回执已冻结，可按用户授权经本 PR 合入 main。

部署证据：[deployment-receipt.json](../../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json)。 公网证据：[public-verification.json](../../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/public-verification.json)。后续网站批次 `website-20261104T132137Z` 的 12 文件摘要、92 其他静态文件、18 项公网视口及启动清单刷新均通过。此状态替代前述网站发布时点的批量待部署说明。用户已授权本版通过 PR 合入 main；该后台发布时点尚未正式归档；随后已完成正式 Release 归档与 App Store IPA 导出，见 [ARCHIVE.md](ARCHIVE.md)。App 上传、提交审核及上架仍未执行。

## 2026-11-04 简体中文 ASO 调整

名称“棒球比赛记录大师”及副标题“棒球慢垒记分、球队共享与统计”保持本批材料原稿；本轮只重写推广文本与关键词，不代改商店字段。如实际填写的名称或副标题不同，需重新核对词位重复。

- 推广文本130字符：从赛前到赛后使用价值开头，再说明多屏、名单、击球落点、共享与文字直播，以及离线核心记分／无订阅内购，减少以设备更新为主的发布说明口吻。无版本号，不把App购买价格写成免费。
- 关键词98个UTF-8字节，英文逗号分隔、无空格。删除原列表里与名称／副标题重复的完整词位“慢垒、记分、球队、共享、统计”；“计分”补另一种写法，“垒球”补运动相关表达，“记分牌／记分册”保留独立工具意图，其他覆盖具体数据与场边使用场景。中文切词与跨字段组合不作保证，不宣称靠拆字一定命中某个完整查询。
- 这是基于产品事实与搜索意图的首轮词组，不是搜索量或难度榜单；未取得Apple Ads词量、自然关键词名次或竞品隐藏关键词，不编造这些数据，不加入竞品／商标／无关热词。
- 上线后按同等时间窗口观察App Store Search来源的展示、产品页浏览、下载与转化；排除广告投放、活动和评分变化等干扰后再判断是否保留词组。App Analytics用于来源效果观察，不当作逐词排名工具。

依据：[Apple App Store search](https://developer.apple.com/app-store/search/)说明推广文本不影响搜索排名，关键词应相关且避免重复名称／副标题；[Connect字段规范](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)规定推广文本170字符、关键词100字节。2026-11-04核对。本轮按更具体的100字节字段限制验证，不将概述页100 characters误作100个汉字。
