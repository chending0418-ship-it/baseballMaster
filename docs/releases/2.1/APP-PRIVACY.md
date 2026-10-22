# 2.1 App 隐私申报准备

更新日期：2026-09-30。工程为 2.1（1），文字直播已启用并接入正式 HTTPS 服务。**此文件是 App Store Connect 填报草稿，尚未修改商店问卷，也未提交审核。**

## 本次已经更新

- `PrivacyInfo.xcprivacy` 声明姓名、其他用户内容、用户标识，均关联身份、仅用于 App 功能、不用于追踪；保留 UserDefaults 的 CA92.1 原因。
- 关于页面说明可选直播，提供隐私政策和技术支持链接；开播前说明公开范围、持链接可看、保留与删除及服务器重启可能导致链接提前失效。
- 正式页面：[隐私政策](https://baseballmaster.cc/privacy.html) · [技术支持](https://baseballmaster.cc/support.html)。

## 商店问卷建议

| 数据类别 | 实际范围 | 用途 | 关联身份 | 追踪 |
| --- | --- | --- | --- | --- |
| 联系信息 → 姓名 | 主动直播中的球员姓名或昵称 | App 功能：展示比赛 | 是，投影没有去标识化 | 否 |
| 用户内容 → 其他用户内容 | 球队名称、背号、比分、局面、打席与比赛事件 | App 功能：公开观赛及同步更正 | 是，可与姓名关联 | 否 |
| 标识符 → 用户 ID | 球员 UUID，以及对应的比赛／事件关联标识；没有注册账号 | App 功能：维护投影中球员与事件的对应关系 | 是，和姓名同时上传 | 否 |

比赛内容来自真实体育记录，按其他用户内容准备；不把本地球数统计当作健康数据或行为分析。没有广告、分析或崩溃 SDK，不采集 IDFA 或设备标识，不收集现场音视频。

未开播的球队库、其他比赛、完整备份、PDF/TXT 均不上传。直播仍需申报：上传的投影会在服务器持续保存至关闭或到期，不能因为功能可选、无需账号或保留较短就填“不收集”。

发布凭证保存在设备 Keychain，服务端只保存校验值；不出现在观看链接、二维码、导出备份或日志里。IP 仅在内存用于安全限流；不用于定位、账号关联或画像，不形成持久访问日志。本域名使用现有腾讯云服务器，不接入 CDN 内容缓存。

## 仍待发布时执行

- 在 App Store Connect 核对并填写上述实际收集类型、关联性、用途与追踪选项；检查网络安全临时处理信息的问卷口径及邮件／微信支持渠道的实际范围。
- 隐私政策 URL 填 `https://baseballmaster.cc/privacy.html`，支持 URL 填 `https://baseballmaster.cc/support.html`。
- 审核说明写明：无需账号；进行中的比赛可主动开播，网页仅只读，终场或失联一小时到期；关闭成功立即删除，手机原比赛保留。审核测试应现场生成临时直播，不提供永久回放链接。
- 完成真机、旧数据升级和签名归档；按用户后续安排提交。不得将隐私清单和此草稿视为已完成商店问卷。

依据：[Apple App 隐私详情](https://developer.apple.com/app-store/app-privacy-details/)、[隐私清单数据类型](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatype)、[清单用途](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatypepurposes)。
