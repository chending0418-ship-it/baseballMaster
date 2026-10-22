# 2.2 发布与审核交接

2026-10-03。**开发、模拟器回归、发布材料、官网及文字直播服务已完成；2.2 App 尚未上传、提交审核或上架。** 2.1 已上线，旧版历史记录保留。本次按用户要求推送并合入 main，Git 与目录收拢的最终结果见本页末尾执行记录。

## 可直接使用的交付

- [推广文本、描述、新增内容、英文审核备注](APPSTORE-METADATA.md)：四个独立纯文本文件及关键词，已核对长度。
- [预览与截图](APPSTORE-ARTWORK.md)：七张实际界面，6.9 英寸与 6.5 英寸共十四张；`output/releases/2.2/app-store/index.html` 可点击预览，`overview.png` 为总览。
- 汇总文案：`output/releases/2.2/发布文案.txt`。
- 上传素材包：`output/releases/2.2/BaseballMaster-2.2-AppStore.zip`，包含两套设备截图和纯文本；不包含发布凭证或用户比赛。
- 签名归档：`output/releases/2.2/archives/BaseballMaster-2.2-1.xcarchive`。

## 工程与归档

| 项目 | 已核对内容 |
| --- | --- |
| 唯一活动工程 | `/Users/JasonChan/Documents/BaseballMaster-2.2/BaseballMaster.xcodeproj` |
| Scheme | BaseballMaster |
| Version／Build | 2.2／1 |
| Bundle ID | com.jasonchen.baseballmaster |
| Team | JUTVG7XR9H |
| App Store ID | 6802063886 |
| 设备／最低版本 | iPhone／iOS 16 |
| Release 归档 | xcodebuild Archive 成功，arm64 |
| 本机签名 | Apple Development；上传时由 Xcode 按 App Store 分发方式重新签名 |
| 完整性 | codesign 严格校验通过；二进制与 dSYM UUID 相同 |

归档已准备，尚未执行 Apple 分发验证或上传；开发签名归档不等同于已获得分发签名的 IPA。测试启动参数及合成数据入口仅限 DEBUG，不进入 Release 功能入口。

## 验证结论

- 非直播 UI 完整回归：170 项单元／集成、65 个唯一 UI 场景通过；iPhone 17 Pro、16e、SE 第三代，包含小屏、深色、统计／报告、导航、阵容、首球锁定和实际 2.1→2.2 覆盖安装数据保持。详见 [开发验收](DEVELOPMENT-VALIDATION.md)。
- 最终直播代码：180 项单元／本地 HTTP 集成测试、16 项服务端测试通过；WebKit 四组视口／配色及三个模拟器 Safari 观赛／双方阵容／返回／删除流程通过。iPhoneOS Release 编译通过。详见 [直播 UI 验证](LIVE-UI-VALIDATION.md)。
- 发布阶段：iPhone 17 Pro Max 采集和复核五个截图流程通过；Box Score 预览／系统分享通过；正常签名 App 的正式 HTTPS 开播、同步、断网补传、纠错保存、Keychain 与磁盘重启恢复、终场／恢复及关闭删除通过。
- 公网 WebKit 四组通过；匿名／错误凭证拒绝、250 并发读取、到期删除、旧链接跨升级保持及同链接 2.2 字段升级通过。官网／支持／隐私／观赛入口分别检查 390 与 1440 两档。
- 素材十四张尺寸与无透明通道校验通过，沿用品牌；生成材料使用合成比赛。

发布阶段证据：`output/validation/2.2/release-preparation/`。未签名模拟器的首轮公网用例无法读 Keychain，改用正常签名构建后同一完整流程通过；原失败证据保留。网站检查器首次把未使用的弹窗图片当成损坏图，随后又使用了错误的断言措辞；修正检查器后复核实际页面。没有删除失败记录，也没有将其计作通过。

以上不包含实体 iPhone、蜂窝网络切换或微信客户端缓存测试。用户已明确无需自行真机测试，本次模拟器和公网验证由开发方完成。

## 正式服务与隐私

[官网](https://baseballmaster.cc/) · [支持](https://baseballmaster.cc/support.html) · [隐私](https://baseballmaster.cc/privacy.html) · [文字直播入口](https://baseballmaster.cc/livestreaming/novideo/)。

新版直播 Web／API 已先于 App 上线，兼容旧 App、旧场次及新字段；保留独立兼容回滚程序，不回滚数据库，见 [实际部署记录](../../../live/deploy/2026-10-03-production-2.2.md)。官网已把慢垒／小组件预告改为 2.3，App 的状态明确为准备发布。

开播提示、关于页和正式隐私政策已披露本场当前阵容与守位、比赛规则计时、投打表现及赛况。当前隐私清单继续声明姓名、其他用户内容、用户标识，均用于 App 功能、关联身份、不追踪。Connect 问卷需核对当前真实选项，不能将旧版草稿或此交接当成已发布问卷。未开播的比赛库及备份不上传；没有登录要求，不填写永久直播链接或虚构账号。

## 用户在 Xcode 与 Connect 的最后操作

1. 打开上述归档，在 Xcode Organizer 核对 Version 2.2／Build 1／Bundle ID，执行 Validate App，并按 App Store 分发方式上传。若 Connect 已存在同版本 Build 1，应先递增 Build 后重新归档；本任务未上传该构建。
2. 在既有 App 6802063886 下创建／填写 2.2 版本，选择实际处理完成的构建，复制四份文本；上传 `iphone-6.9/` 七张 PNG，备用尺寸在 `iphone-6.5/`。不上传总览或 HTML，不混用尺寸。
3. 审核联系资料使用账户真实姓名、电话及既有邮箱。无需登录账号；核对支持／隐私链接及实际隐私问卷。
4. 由用户提交审核，并按自己的发布安排选择上线方式。归档成功、Git 合并与网页上线不代表 Apple 已接受构建、审核通过或 App 已上架。

## Git 与目录执行记录

2.2 代码提交 `433373b` 已推送 `codex/dev-2.2` 并快进合入／推送 main；远端 SHA 核对一致。单目录迁移与文档收尾另随提交保存。现在只保留 `/Users/JasonChan/Documents/BaseballMaster-2.2`，完整 Git、原签名归档、测试证据、宣传图与源码备份已保留并校验，迁移后 Release 编译通过。详见 [迁移维护记录](../../maintenance/2026-10-03-single-project.md) 与 [版本目录说明](../../BRANCHES.md)。

## 官网与传播素材补充

2026-10-03 08:11，官网 2.2 完整介绍与更新海报已同步上线，修正遗留版本文字并新增海报保存／预览入口。沿用 2.1 品牌版式，使用三张实际 2.2 界面，宣传 PNG 为 1440 × 1920。官网三页在本地与公网四档宽度共 24 组检查通过，37 个公开文件与源码逐字节匹配，直播服务保持正常。当前 App Store 公开版本仍为 2.1，文案明确区分网页上线与 App 发布；没有改动归档或商店截图。来源、回滚与验证记录见 [官网及传播图片更新](../../../website/deploy/2026-10-03-website-marketing-2.2.md)。
