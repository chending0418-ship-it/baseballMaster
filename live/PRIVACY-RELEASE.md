# 2.1 文字直播与隐私资料

更新日期：2026-09-29。当前 Git 工程为 **2.1（Build 1）**，生产直播开关关闭，直播服务尚未部署，App Store 审核暂缓。

## 已更新的公开页面

- 隐私政策：<https://baseballmaster.cc/privacy.html>。
- 技术支持：<https://baseballmaster.cc/support.html>。
- 本次说明涵盖本地历史纠错、主动开播、公开字段、持链接可看、同步、保留与删除、备份、未成年人资料及支持请求。两页都明确当前服务尚未开放；发布新页面不会启用 App 直播。
- 页面、桌面／手机验证与部署证据见 [2.1 官网更新记录](../website/deploy/2026-09-29-policy-support-2.1.md)。

## 文案依据

| 公开说明 | 实现依据 |
| --- | --- |
| 只上传主动开启的单场公开内容 | `BaseballMaster/LiveBroadcast.swift` 的 `LiveSnapshot` 字段白名单与 `LiveBroadcastManager`，`live/server.mjs` 的快照验证 |
| 姓名、背号、球员／比赛／事件标识，比分、垒况及比赛过程 | `LiveSnapshot.Person`、`Entry` 和顶层字段；不将完整本地库或修订档案上传 |
| 持链接可看；观看与发布权限分开 | 公开 GET、经发布凭证验证的写入／删除；凭证存设备 Keychain，服务端只存校验值 |
| 草稿不上传，保存后的更正同步 | `HistoryCorrectionViews.swift` 的预览／保存流程及保存后的直播同步 |
| 终场时间起一小时、连续一小时无同步到期 | `live/server.mjs` 的 `deadline`、在线确认及 `sweep`；终场更正不续期 |
| 关闭需请求到达服务器；停机恢复先清理 | `LiveBroadcastManager.synchronize` 的关闭重试，以及服务启动／请求前清理 |
| 网站请求数据与必要日志 | 已部署 Nginx 的官网访问／错误日志；直播应用的内存 IP 限流。不能把“本地记分不上传”等同于“网站不处理网络信息” |

## 启用直播及提交审核前仍需完成

- [ ] 部署直播服务与 HTTPS 代理；实测发布、观赛、更正、断网恢复、主动关闭、终场／失联到期及重启清理。
- [ ] 核查生产日志、宝塔备份、云快照、目录同步及 CDN，防止在直播数据库外长期保留副本。直播内容不做备份，不以代码中的删除行为代替基础设施验收。
- [ ] 更新 `BaseballMaster/ProfileViews.swift` 的离线隐私文案，增加容易找到的公开隐私政策入口；当前“本版不提供直播”的说明只适用于开关关闭的构建。
- [ ] 核对并更新 `PrivacyInfo.xcprivacy`、开播告知和 App Store Connect 隐私问卷。目前清单的收集类型为空，不能原样用于已启用直播的发布构建。
- [ ] 按实际处理范围申报姓名、比赛内容及相关标识，核对关联性、用途、网络信息和受托服务提供方；不因“无需账号”“可选直播”或“仅保留一小时”直接填为不收集。
- [ ] 服务开放时同步两页的状态提示、官网介绍及审核备注；不得在仍关闭服务时把上线验收标为完成。
- [ ] 完成真机公网使用、旧数据升级、备份恢复和最终构建检查，待用户安排再提交苹果审核。

核对依据：[Apple 审核指南 1.5、5.1.1](https://developer.apple.com/app-store/review/guidelines/)、[App 隐私详情](https://developer.apple.com/app-store/app-privacy-details/)。Apple 要求提供有效联系渠道及易于访问的隐私政策，明确处理范围、用途、保留／删除和撤回方式；可选功能是否需要申报仍取决于实际收集行为及其披露条件。网页更新不等于已完成商店隐私问卷或 App 内隐私清单。

历史直播政策草稿保留于 `docs/releases/1.6-1-live-draft/`，仅供追溯，不能替代当前官网文件。域名与路径统一使用 `baseballmaster.cc` 和 `/livestreaming/novideo/`。
