# 2.3 发布与审核交接

2026-10-08。按用户要求，功能、模拟器替代真机的验收、生产直播兼容、官网更新、商店资料、宣传图和最终签名归档均已完成。Apple 分发验证、上传、审核与最终上线由用户操作。当前公开 App Store 版本为 2.2，官网正确标注 2.3 准备发布。

## 交付入口

- [商店文案](APPSTORE-METADATA.md)：宣传文本、描述、新增内容、关键词与英文审核备注，独立 TXT 可直接复制。
- [截图与宣传图说明](APPSTORE-ARTWORK.md)：三套各七张实际界面，6.1／6.3 英寸栏目用 1206×2622；6.9 英寸用 1320×2868，6.5 英寸用 1242×2688，均为 RGB PNG。2026-10-09 已补齐当前栏目所需尺寸。
- [上传流程](UPLOAD-GUIDE.md)：Xcode Organizer、App Store 分发、Connect 填写、截图、审核与上线步骤。
- [隐私填写依据](APP-PRIVACY.md)：沿用本地资料与可选文字直播的实际处理，未修改 Connect 问卷。
- [完整模拟器验收](SIMULATOR-ACCEPTANCE.md)：A01–A26 映射、三模式、旧版覆盖安装、恢复、报告、网络与证据边界。
- 完整材料包：`output/releases/2.3/BaseballMaster-2.3-AppStore.zip`；同内容的版本化副本为本目录 `BaseballMaster-2.3-AppStore.zip`。
- 截图总览：`output/releases/2.3/app-store/index.html`、`overview.png`。
- 宣传 PNG：`output/marketing/2.3/release-poster/baseballmaster-2.3-update.png`，1440×1920；同时保存在本目录 `baseballmaster-2.3-update.png`。
- 签名归档：`output/releases/2.3/archives/BaseballMaster-2.3-1-release.xcarchive`。

## 工程与验证

| 项目 | 已核对内容 |
| --- | --- |
| 工程 | `/Users/JasonChan/Documents/BaseballMaster/BaseballMaster.xcodeproj` |
| Scheme | BaseballMaster |
| Version／Build | 2.3／1 |
| Bundle ID／Team | com.jasonchen.baseballmaster／JUTVG7XR9H |
| App Store ID／最低系统 | 6802063886／iPhone iOS 16 |
| 单元／集成 | 最新全量 209 项通过，0 失败、0 跳过 |
| 界面 | 72 个唯一方法均有最后通过记录；16e、SE 各 3 项补测通过，重跑不重复累计 |
| 服务器／浏览器 | 服务端 17 项通过；本地与公网各 8 组 WebKit 通过 |
| 归档 | iPhoneOS Release 成功，严格签名通过，二进制与 dSYM UUID 相同 |
| 签名用途 | 本机 Apple Development，上传时由 Xcode 按 App Store 分发方式重新签名 |

正常签名模拟器 App 已完成公网 HTTPS、Keychain／磁盘重启、实际慢垒字段和 Safari 阵容验证。旧版本无法读取新语义库时显示保护页面；回到 2.3 后完整逻辑状态保持。规则、阵容、球数、界外机会、统计、报表与备份按同一保存状态处理。

此轮修复复查发现的未来比赛对手阵容丢失、赛前撤销初始配置不同步、历史更正丢失投手修复标记，补齐慢垒投手提醒；验收又修复时间赛开赛重置倒计时的问题。初始失败记录和检查器修正保留，不声称失败的首轮全量包通过。按用户要求使用模拟器，未声称实体设备、蜂窝射频或微信客户端验收。

完整证据在 `output/validation/2.3/release-acceptance/`；最终结果索引为 `final-ledger/validation.json` 与 `tests.json`。

## 线上内容

[官网](https://baseballmaster.cc/) · [支持](https://baseballmaster.cc/support.html) · [隐私](https://baseballmaster.cc/privacy.html) · [文字直播](https://baseballmaster.cc/livestreaming/novideo/) · [2.3 宣传图](https://baseballmaster.cc/assets/baseballmaster-2.3-update.png)。

直播程序已切换至 `releases/2.3-2026-10-08`，旧链接跨进程升级保留，既有及新慢垒投影均可读写；只验证自建合成场次，完成后删除。没有备份、复制或读取其他用户直播数据。当前验证通过的版本与旧 release 均保留，后续已发布 2.3 App 使用期间应保持慢垒兼容，不能回到不接收新字段的旧服务。

官网首页、支持、隐私同步上线，24 组本地／公网三页视口检查通过；44 个公开文件逐字节匹配源码，HTTPS、HTTP 跳转、内部路径不可访问及直播健康通过。更新不提前宣称 2.3 App 已上架，小组件继续为后续待办。

部署记录：[兼容直播](../../../live/deploy/2026-10-08-production-2.3.md)、[官网与宣传图](../../../website/deploy/2026-10-08-release-2.3.md)。

## 用户的最后操作

1. 打开最终归档，在 Organizer 核对版本与 Bundle ID，按 App Store 分发方式验证并上传。若 Connect 已存在同版本 Build 1，递增 Build 后重新归档。
2. 在既有 App 下填写 2.3 版本，选择正确构建、复制独立 TXT、上传一套对应设备尺寸的七张 PNG。
3. 核对真实审核联系方式、支持／隐私链接和问卷；无需登录账号。
4. 提交审核并按自己的安排上线。App 实际上架后可使用社交发布稿，并将官网准备发布状态改为可下载状态。

## Git 执行记录

2.3 功能、修复、模拟器／公网材料、官网与发布资料提交 `8e54f23` 已快进推送到 `origin/main`，远端 SHA 核对一致，本地 main 同步至该提交。版本化资料 ZIP 与宣传 PNG 已纳入仓库；签名归档和完整原始测试包保存在本机 output，避免把构建缓存放入 Git。

共享工作区中的另一项 3.0 规划文档及入口编辑保留为未提交改动，没有纳入 2.3 提交，也没有删除或覆盖。后续发布记录提交只更新本次交接。

Apple 分发验证、上传、审核与实际发布由用户执行。
