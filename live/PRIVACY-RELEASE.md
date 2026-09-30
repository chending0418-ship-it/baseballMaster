# 2.1 文字直播与隐私资料

更新日期：2026-09-30。当前 Git 工程为 **2.1（Build 1）**，正式直播 Web／API 已部署，App 直播开关已启用，App Store 审核仍暂缓。

## 已更新的公开页面

- 隐私政策：<https://baseballmaster.cc/privacy.html>。
- 技术支持：<https://baseballmaster.cc/support.html>。
- 本次说明涵盖本地历史纠错、主动开播、公开字段、持链接可看、同步、保留与删除、备份、未成年人资料及支持请求。两页已同步 Web 服务部署状态，并明确 App 尚未正式发布；页面更新本身不会替旧版 App 开启直播。
- 9 月 29 日页面改写见 [官网更新记录](../website/deploy/2026-09-29-policy-support-2.1.md)；本次服务、隐私状态、App 配置及验收见 [正式部署记录](deploy/2026-09-30-production.md)。

## 文案依据

| 公开说明 | 实现依据 |
| --- | --- |
| 只上传主动开启的单场公开内容 | `BaseballMaster/LiveBroadcast.swift` 的 `LiveSnapshot` 字段白名单与 `LiveBroadcastManager`，`live/server.mjs` 的快照验证 |
| 姓名、背号、球员／比赛／事件标识，比分、垒况及比赛过程 | `LiveSnapshot.Person`、`Entry` 和顶层字段；不将完整本地库或修订档案上传 |
| 持链接可看；观看与发布权限分开 | 公开 GET、经发布凭证验证的写入／删除；凭证存设备 Keychain，服务端只存校验值 |
| 草稿不上传，保存后的更正同步 | `HistoryCorrectionViews.swift` 的预览／保存流程及保存后的直播同步 |
| 终场时间起一小时、连续一小时无同步到期 | `live/server.mjs` 的 `deadline`、在线确认及 `sweep`；终场更正不续期 |
| 关闭需请求到达服务器；停机恢复先清理 | `LiveBroadcastManager.synchronize` 的关闭重试，以及服务启动／请求前清理 |
| 网站请求数据与必要日志 | 本域名关闭 HTTP／HTTPS／www 跳转路径日志，应用仅有启动及错误类别；IP 在内存用于限流。不能把“本地记分不上传”等同于“网站不处理网络信息” |

## 已完成及发布前仍需完成

- [x] 部署服务及代理，公网与模拟器验证发布、观赛、更正、受控断网恢复、主动关闭、终场恢复及到期；服务测试覆盖失联与启动清理。
- [x] 专用 tmpfs + noswap、进程 MemorySwapMax=0、禁用 core dump；不进入云磁盘快照。当前任务无直播目录备份、直连无 CDN、域名关闭路径日志。未来文件备份仍须排除该目录。
- [x] 更新 App 内文案、隐私与支持入口及开播告知，实际控件验证通过。
- [x] 更新隐私清单为姓名、其他用户内容和用户标识；均关联身份、仅用于 App 功能、无追踪。
- [ ] 在 App Store Connect 按 [2.1 申报草稿](../docs/releases/2.1/APP-PRIVACY.md) 更新问卷；此项尚未执行。
- [ ] 按实际处理范围申报姓名、比赛内容及相关标识，核对关联性、用途、网络信息和受托服务提供方；不因“无需账号”“可选直播”或“仅保留一小时”直接填为不收集。
- [x] 同步官网和两页服务状态；审核备注草稿已准备，未提交。
- [ ] 完成真机公网使用、旧数据升级、备份恢复和最终构建检查，待用户安排再提交苹果审核。

核对依据：[Apple 审核指南 1.5、5.1.1](https://developer.apple.com/app-store/review/guidelines/)、[App 隐私详情](https://developer.apple.com/app-store/app-privacy-details/)。Apple 要求提供有效联系渠道及易于访问的隐私政策，明确处理范围、用途、保留／删除和撤回方式；可选功能是否需要申报仍取决于实际收集行为及其披露条件。网页更新不等于已完成商店隐私问卷或 App 内隐私清单。

历史直播政策草稿保留于 `docs/releases/1.6-1-live-draft/`，仅供追溯，不能替代当前官网文件。域名与路径统一使用 `baseballmaster.cc` 和 `/livestreaming/novideo/`。
