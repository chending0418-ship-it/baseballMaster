# 3.1.2 隐私、支持 URL 与官网内容交接

2026-11-04。iOS 3.1.2（Build 1）。用户后续已授权在迁移后的上海环境发布隐私／支持及官网介绍。正式页面已实现，部署与公网验收结果见文末更新；App 内链接、App Store Connect 和 App 发布仍为独立步骤。

## 目标网址

用户本次要求新的产品专属入口；采用同一既有域名，方便未来与母品牌和教练口袋区分：

| 用途 | 新目标 URL | 文稿 |
| --- | --- | --- |
| 隐私政策（中文） | https://baseballmaster.cc/scorekeeper/privacy.html | [中文](../../design/marketing/3.1.2/web/privacy-zh-Hans.md) |
| 技术支持（中文） | https://baseballmaster.cc/scorekeeper/support.html | [中文](../../design/marketing/3.1.2/web/support-zh-Hans.md) |
| 隐私政策（英文） | https://baseballmaster.cc/scorekeeper/privacy-en.html | [英文](../../design/marketing/3.1.2/web/privacy-en.md) |
| 技术支持（英文） | https://baseballmaster.cc/scorekeeper/support-en.html | [英文](../../design/marketing/3.1.2/web/support-en.md) |
| 官网产品介绍 | https://baseballmaster.cc/scorekeeper.html（沿用） | [编排与文案策划](../../design/marketing/3.1.2/web/website-plan.md) |

旧 https://baseballmaster.cc/privacy.html 与 https://baseballmaster.cc/support.html 保留 **200 同等内容** 兼容，不在审核期间重定向。既有 App `ProfileViews.swift`、`LiveBroadcastViews.swift` 仍硬编码旧隐私页，且关于页链接旧支持页；本轮不改 App。未来如要改为新地址，应在新页先上线验证后由对应 App 发布任务处理。商店新 URL 亦只在内容与环境核对完成、HTTPS 匿名可读后填写，不以规划代替可访问性。

品牌规范 1.1 原要求旧支持／隐私地址保持，本轮按用户“新的网址”设新产品入口，同时保留原 URL 和内容可用性。旧支持页锚点至少保留 `version-310`、`team-sharing`、`slow-pitch`、`improvements`、`corrections`、`live`、`data`、`reports`、`live-report`、`contact`；旧隐私锚点 `local`、`teams`、`live`、`retention`、`rights`、`contact` 继续映射相应章节。语言切换使用显式链接，无需 Cookie 或登录。

## 当前核对事实与本版新增披露

- 联系方式沿用现有 `website/public/privacy.html`、`support.html`：Jason Chen、chending0418@gmail.com、微信号 p18616870148，不编造客服号码或服务时限。备案正式运营主体尚需核对。
- `PrivacyInfo.xcprivacy` 声明姓名、其他用户内容、用户 ID、设备 ID、产品交互；用于 App 功能、与身份关联、非追踪。不能因无注册而填整款 App“不收集”。商店问卷详见本版 APP-PRIVACY。
- `ConfigurableLaunchScreen.swift`：默认启动后异步 GET `/api/v3/app/launch-screen`，必要时下载同域图像；临时会话禁 Cookie、无共享鉴权、阻止重定向，不包含名单或比赛正文。文稿明确区分“不自动上传本机比赛”与“可能发起开屏网络请求”。
- `BulkRosterImportView.swift`、`BulkRosterImport.swift`：由用户主动输入／粘贴，每批最多 200 人，预览／取消不新增，确认只新增。`TeamSharingStore.swift` 保存必要批次与重试状态；`TeamCollaboration.swift` 先核对服务端批量能力。共享批量生产服务已在上海上线；管理员和记录员按权限操作，保留修订、事务和幂等检查，旧客户端单人编辑保持兼容。
- `TeamCollaboration.swift`／`TeamCollaborationViews.swift` 与既有公开隐私：无手机号／邮箱／Apple 账号注册、角色授权、摘要凭据、设备配对、历史观看与删除路径。无个人注册账户不意味着无成员身份和数据删除义务。
- `collaboration/maintenance.mjs`：30 天回收／解散后内容清除逻辑；旧政策曾按日备份推算删除后至多 60 天；新正文已区分日常与迁移完整备份，删除该通用承诺。`collaboration/backup.mjs` 为加密备份及撤权／删除重放。最终发布前须核对实际生产任务配置。
- `live/server.mjs` 与迁移方案：临时文字直播为独立范围，不是音视频；到期与持久球队档案分开。正式服务已在同日迁往上海，旧香港迁移副本受限保留，新正文与迁移回执一致。

## 正式环境事实（2026-11-04 更新）

用户已确认迁移与 DNS 切换。隐私中英正文现写明上海正式主服务、上海私有日备份；原香港旧数据、日志及备份受限保留用于迁移核验和回退，不继续作为独立可写主服务。日常球队备份保留 30 天；完整状态、配置与迁移副本目前无自动到期，不再泛称全部备份最长 60 天。迁移收尾按必要范围清理，无虚构完成日期。公开内容不包含 IP、桶名、内部路径或凭据。

## 发布前集中待核对

1. 正式运营主体／开发者展示名与备案主体的一致性、正式 App 与网站备案号、查询链接；不使用占位号。已有 Jason Chen 与邮箱为历史公开事实，但须确认发布后持续有效。
2. 实际主数据地域、独立备份地域、旧香港回退副本范围和保留／清除安排；实际日备份周期、30 天保留与删除重放是否启用。
3. 生产 Nginx 访问／错误日志、运行日志及 IP 限流配置。草稿不无条件承诺“所有网络层绝不留日志”。如有新的保留项，按实际期限补充。
4. 共享批量名单生产能力已部署，协议与最低客户端版本保持；继续以本轮公网专项回执核对权限、旧新 App 兼容、原单人入口、重复请求和回退保护。
5. 新旧共六个语言／兼容页面匿名 HTTPS 200、内容日期与两种语言一致、语言与联系链接有效；移动端无溢出。App 关于页旧地址仍能读到新正文。
6. App Store Connect 新 Privacy/Support URL、审核备注和隐私问卷与实际内容一致；本次没有登录或更新商店，也没有提交审核。

## 交付和验证边界

源稿位于 `docs/design/marketing/3.1.2/web/`，以本版真实功能与既有公开内容为依据；官网产品页正式实现归总品牌仓库。最初仅准备稿件；用户后续授权官网部署，实际网页实现与部署单独记录。该次静态网页发布没有执行新的真实数据迁移或业务服务发布；随后共享批量后台按新增授权单独上线，采用独立部署回执。

Apple 资料已于 2026-11-04 查阅：[App Review Guidelines 5.1](https://developer.apple.com/app-store/review/guidelines/)、[App privacy 字段](https://developer.apple.com/help/app-store-connect/reference/app-information/app-privacy/)、[App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/)。政策需说明实际收集与用途、共享及保留／删除，并在 App 和商店提供可用入口；最终审核结果以 Apple 回执为准。

同期备案进展：根据 2026-11-04 迁移任务的当前记录，网站 `沪ICP备2026058730号-1`、App `沪ICP备2026058730号-2A` 已通过，用户确认商店备案配置完成。正式网页需沿用共享页脚的网站号，并与 App 号分开；本轮不保存个人证件或地址。迁移当前状态与本批页面发布分别记录，不将网页发布当作 App 上架。


## 2026-11-04 正式上线回执

用户后续授权后，已发布产品介绍、四份新中英文页面及两份旧兼容页。批次 `website-20261104T125935Z`；12文件HTTPS摘要一致，92其他静态文件保持，Nginx检查后平滑重载。上海公网匿名可读，新旧路径均200，旧锚点、备案及语言链接保持。产品无后缀入口 `/scorekeeper` 与既有 `.html` 均保持，保留并行任务的路由及首页／教练口袋改动。官网四设备图现用真实alpha透明底，去掉矩形摄影背景；社交海报背景不因此改变。部署证据在 `output/validation/3.1.2/website-publish-2026-11-04/`，公开源稿在总仓库website。

新网址可用于本轮填写，但本轮未登录或修改 App Store Connect，未上传／提交 App；此前发布前清单的账号及业务能力项仍按实际提交核对。

## 2026-11-04 共享批量服务上线（当前）

上海共享批量后台已按用户新增授权部署，批次 `bulk-3.1.2-20261104T131831Z`。仅 `server.mjs`、`roster.mjs` 两文件更新并核对 SHA，部署前加密备份成功；33 张表无 DDL、停服期间未恢复或改写数据库，启动后完整性通过；其他五个服务 PID 未变。协议保持 1、最低 App 版本保持 3.0，`bulkRosterImport` 能力已开启；权限、修订、事务和幂等校验继续保留，旧客户端单人编辑兼容。九项公网合成专项已全部通过，合成球队按正常流程解散、临时凭据删除；部署后日备份和完整状态备份成功，配置备份下载、解密和摘要核验通过。最终回执已冻结，可按用户授权经本 PR 合入 main。

部署证据：[deployment-receipt.json](../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json)。 公网证据：[public-verification.json](../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/public-verification.json)。后续网站批次 `website-20261104T132137Z` 的 12 文件摘要、92 其他静态文件、18 项公网视口及启动清单刷新均通过。此状态替代前述网站发布时点的批量待部署说明。用户已授权本版通过 PR 合入 main；正式签名归档、App 上传、审核和上架仍分别记录，本轮尚未执行。
