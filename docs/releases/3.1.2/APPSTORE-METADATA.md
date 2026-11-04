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
| 关键词 | [keywords-zh-Hans.txt](keywords-zh-Hans.txt)，UTF-8 97 bytes，不含文件结尾换行 |
| 审核备注主稿 | [review-notes-en.txt](review-notes-en.txt)，UTF-8 3697 bytes，不含文件结尾换行 |
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

共享球队整批新增依赖生产服务 `bulkRosterImport`。客户端和后端已实现、本地验证通过，生产尚未更新；当前文案保留“需球队服务支持”。服务部署与公网验证闭环前，不把共享整批新增写成已可用的无条件承诺。App 端失败时会保留草稿，不逐人补写。

Duo 有专项模拟器证据；文案可以说明支持范围，不写“真机全面验证”“所有姿态／分屏穷举”。备案与商店备案配置采用同期用户确认；上海已接管，香港受限迁移副本仍保留且无自动到期，日常上海球队备份按 30 天管理。新版隐私／支持页与官网内容已上线并核验匿名 HTTP 200；旧比赛原件处理、共享批量服务更新、签名归档和上传审核仍保留各自待办。

## 限制与来源

Apple 当前文档列出：宣传文本 170 characters、描述和更新说明各 4000 characters、关键词 100 bytes、审核 Notes 4000 bytes；支持页须提供实际联系方式。[Apple 平台版本资料](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)。本稿按字符及 UTF-8 字节分别检查，不沿用旧版超长中文关键词。

本地实现依据：`BaseballMaster.xcodeproj/project.pbxproj`、`BaseballMaster/BulkRosterImportView.swift`、`BaseballMaster/ResultsViews.swift`、`BaseballMaster/TeamCollaboration.swift`、`BaseballMaster/ConfigurableLaunchScreen.swift`、[版本计划](<../../plans/3.1.2 发布功能计划.md>)及[开发证据](DEVELOPMENT.md)。

## 2026-11-04 网站上线回执

批次 `website-20261104T125935Z` 已在上海完成静态网站发布：12 个文件的 HTTPS SHA-256 与发布清单一致，92 个其他静态文件未变，`nginx -t` 通过，后端代码及数据未修改。新四页、旧两页及产品页已另核验匿名 HTTP 200，两种语言隐私页具有 `rights` 锚点。公网多视口视觉验收由主代理另行记录，不将部署哈希核对替代视觉验收。

证据：[deployment-receipt.json](../../../output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json)。App 未因本次网页发布而归档、上传、提交审核或上架，共享批量服务仍未部署。
