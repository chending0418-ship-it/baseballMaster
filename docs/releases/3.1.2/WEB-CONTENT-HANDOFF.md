# 3.1.2 隐私、支持 URL 与官网内容交接

2026-11-04。iOS 3.1.2（Build 1）。本次完成文字源稿、目标路由及官网介绍策划；**所有新增 URL 均未创建或验证上线**，不得据本地文件直接填写为已可用的 App Store Connect 地址。官网只策划，不部署；现有 App 内链接、官网源码、DNS 和服务均未修改。

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
- `BulkRosterImportView.swift`、`BulkRosterImport.swift`：由用户主动输入／粘贴，每批最多 200 人，预览／取消不新增，确认只新增。`TeamSharingStore.swift` 保存必要批次与重试状态；`TeamCollaboration.swift` 先核对服务端批量能力。共享生产服务尚未更新，不把客户端完成写成已对所有用户开放。
- `TeamCollaboration.swift`／`TeamCollaborationViews.swift` 与既有公开隐私：无手机号／邮箱／Apple 账号注册、角色授权、摘要凭据、设备配对、历史观看与删除路径。无个人注册账户不意味着无成员身份和数据删除义务。
- `collaboration/maintenance.mjs`：30 天回收／解散后内容清除逻辑；既有公开政策为独立日备份 30 天、删除后至多 60 天备份到期。`collaboration/backup.mjs` 为加密备份及撤权／删除重放。最终发布前须核对实际生产任务配置。
- `live/server.mjs` 与迁移方案：临时文字直播为独立范围，不是音视频；到期与持久球队档案分开。既有地域为中国香港。同期任务已推进上海离线预迁移，但正式流量未切换，故草稿第七节仍写当前香港生产事实，未声称线上已迁上海。

## 上海完成后的隐私地域替换段

本轮两种语言的隐私正文使用当前可证实的中国香港事实，不能在未来迁移后原样发布。正式迁移并验收后，须根据回执用以下结构替换第七节首段，并同时更新两种语言、旧新 URL：

> 网站、在线服务和独立备份使用腾讯云基础设施。正式在线服务的主数据存储于中国上海，日备份存储于腾讯云中国上海地域的独立私有对象存储。原香港环境中为迁移回退保留的旧副本，按下述已确认范围和期限受限保存：[由迁移交接填写旧数据库／安全日志／备份范围、保留期限与清除安排]。这些副本不继续对外提供独立写入服务。提供方在托管、网络、存储和安全所需范围处理请求。

> The website, online services and independent backups use Tencent Cloud infrastructure. Primary production service data is stored in Shanghai, China, and daily backups are stored in independent private object storage in Tencent Cloud’s Shanghai region. Old copies retained in Hong Kong for migration rollback are restricted to the following confirmed scope and retention period: [complete from the migration handoff, including old databases, security journals, backups and deletion arrangements]. They do not continue to operate as a separate externally writable production service. The provider processes requests as needed for hosting, networking, storage and security.

**方括号是内部编辑门槛，不能发布。** 当前原香港数据与备份计划保留，不能笼统写“所有数据只在上海”或“已删除境外副本”。迁移临时包及离线副本的范围／清理亦按既有迁移方案逐项核对，不在隐私正文公开服务器 IP、桶名、内部路径或凭据位置。若上线时真实迁移未完成，则保留当前事实段，不提前换成上海段。

## 发布前集中待核对

1. 正式运营主体／开发者展示名与备案主体的一致性、正式 App 与网站备案号、查询链接；不使用占位号。已有 Jason Chen 与邮箱为历史公开事实，但须确认发布后持续有效。
2. 实际主数据地域、独立备份地域、旧香港回退副本范围和保留／清除安排；实际日备份周期、30 天保留与删除重放是否启用。
3. 生产 Nginx 访问／错误日志、运行日志及 IP 限流配置。草稿不无条件承诺“所有网络层绝不留日志”。如有新的保留项，按实际期限补充。
4. 共享批量名单生产能力、旧新 App 兼容、原单人入口、重复请求与回退验收；未更新前支持稿说明能力受环境限制，官网主文案仅写本机／对手已可用部分。
5. 新旧共六个语言／兼容页面匿名 HTTPS 200、内容日期与两种语言一致、语言与联系链接有效；移动端无溢出。App 关于页旧地址仍能读到新正文。
6. App Store Connect 新 Privacy/Support URL、审核备注和隐私问卷与实际内容一致；本次没有登录或更新商店，也没有提交审核。

## 交付和验证边界

源稿位于 `docs/design/marketing/3.1.2/web/`，以本版真实功能与既有公开内容为依据；官网产品页正式实现归总品牌仓库。此处没有生成网站部署包、上传文件、调用生产接口或迁移真实数据。文稿完整性、关键能力、链接和缺失字段已作文本检查，不能据此记为线上网页验收。

Apple 资料已于 2026-11-04 查阅：[App Review Guidelines 5.1](https://developer.apple.com/app-store/review/guidelines/)、[App privacy 字段](https://developer.apple.com/help/app-store-connect/reference/app-information/app-privacy/)、[App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/)。政策需说明实际收集与用途、共享及保留／删除，并在 App 和商店提供可用入口；最终审核结果以 Apple 回执为准。

同期备案进展：根据 2026-11-04 迁移任务的当前记录，网站 `沪ICP备2026058730号-1`、App `沪ICP备2026058730号-2A` 已通过，用户确认商店备案配置完成。正式网页需沿用共享页脚的网站号，并与 App 号分开；本轮不保存个人证件或地址，也不发布官网。上海离线预迁移不是正式流量切换。
