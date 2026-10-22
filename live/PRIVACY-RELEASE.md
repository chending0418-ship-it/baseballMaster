# 2.1 文字直播上线时的隐私资料

**当前 2.0 关闭直播，计划在 2.1 启用。** 对外域名确定为 `baseballmaster.cc`。当前 App 文案、PrivacyInfo.xcprivacy 和交付 HTML 均描述离线行为；不要把它们直接用于启用直播的版本。

启用直播前须完成：

- 更新 App 内说明、隐私清单、公开隐私／支持页及 App Store Connect 申报，描述实际上传的球员姓名、球队、背号、比赛过程和标识、持链接可看及保留／删除期限。
- 公开直播说明中的服务域名统一为 `baseballmaster.cc`，核对隐私／支持页及 App Store Connect 的实际公开地址；历史草稿中的旧域名不可直接沿用。
- 核对服务器、宝塔备份、云快照、CDN 和日志处理，不能只按应用代码推定生产环境不保留副本。
- 部署前重新生成程序包和政策资料，验证 HTTPS、观赛、撤销修订、断网恢复、关闭和到期删除。
- 重新构建并完成该版本审核／分发；不得使用本次离线政策配合直播开关。

历史直播政策草稿位于 [docs/releases/1.6-1-live-draft/baseballmaster-privacy.html](../docs/releases/1.6-1-live-draft/baseballmaster-privacy.html)，仅供后续修订参考，**不是本次要发布的网站文件**。

数据范围及部署设计见 [live/README.md](README.md)；申报依据见 [Apple App 隐私详情](https://developer.apple.com/app-store/app-privacy-details/)。可选直播仍须按实际收集申报。
