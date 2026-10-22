# 2.3 App 隐私与审核准备

2026-10-08。2.3 延续本地记分与可选文字直播的数据处理方式，慢垒、初始球数、界外规则、Illegal、Free 和赛前阵容没有新增账号、广告、定位或音视频采集。此文件是填写依据，尚未修改 App Store Connect 问卷。

| 类别 | 主动直播上传的实际范围 | 用途 | 关联身份 | 追踪 |
| --- | --- | --- | --- | --- |
| 联系信息 → 姓名 | 球员姓名或昵称 | App 功能：展示比赛 | 是 | 否 |
| 用户内容 → 其他用户内容 | 球队、背号、当前阵容和守位、比分、规则、计时、投打表现、比赛事件 | App 功能：观赛与同步 | 是 | 否 |
| 标识符 → 用户 ID | 球员 UUID 与比赛／事件关联标识；没有注册账号 | App 功能：保持事件与球员对应 | 是 | 否 |

`PrivacyInfo.xcprivacy` 延续上述声明和 UserDefaults 的 CA92.1 必要理由。未开播的比赛、完整本地库、备份、PDF/TXT 不上传；公开投影仍需要在问卷按实际功能申报，不能因可选直播或短期保留而直接填“不收集”。没有第三方广告或行为分析 SDK，不传输现场音频、视频。

发布凭证保存在设备 Keychain，观看链接和二维码不包含写入密钥；临时安全限流使用内存信息，直播响应不缓存。服务端只保留当前投影，主动关闭成功删除，终场或连续未同步一小时到期删除。手机原比赛和备份保留。进程重启保留当前直播；服务器重启可能使临时直播失效，用户可重新开播。

上线前在 Connect 核对既有实际问卷、用途、关联性与追踪选项；账户已有选择不由此文档推定。姓名、电话等审核联系资料使用真实账户信息，无需注册或审核账号。

公开政策：https://baseballmaster.cc/privacy.html
支持：https://baseballmaster.cc/support.html

依据：[Apple App 隐私详情](https://developer.apple.com/app-store/app-privacy-details/)、[管理 App 隐私](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy)。工程声明、公开政策及开播提示是核对来源；本任务不代表问卷已在 Connect 发布。
