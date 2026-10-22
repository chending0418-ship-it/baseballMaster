# 3.1.0 发布交接

2026-10-20。3.1.0 功能、服务兼容、签名归档与审核材料已准备；完整源码已合并并推送至 main，实际提交回执见下方 Git 说明。**App 上传、Apple 服务端验证、审核提交和商店发布尚未执行，由用户操作。**

## 本次更新

- 开局、赛前与一局练习可选基础版 / Pro 版。基础版保留原有记分输入流程，共用球场与展示功能有更新。
- Pro 版按 V4 交互实现：同场选择球型、落点、第一位处理人和实际传球，独立指定失误责任。
- 统一核对跑者去向、原因与附加处理，支持第三出局时序及“补充这球”，不重复增加投球或打席。
- 点击双方当前打者查看本场／年度表现与击球图；年度仅代表已记录且有权查看的范围。
- 防守路线加入当时球员姓名；球场显示姓名与背号，扩大内野、重排姓名牌并保留十人慢垒。
- 新资料随保存、撤销重做、纠错、备份与共享保留。Pro 为记录方式名称，没有订阅或内购。

## 交付

| 材料 | 路径 |
| --- | --- |
| 签名归档 | output/releases/3.1.0/archives/BaseballMaster-3.1.0-1-final.xcarchive |
| 审核材料 ZIP | output/releases/3.1.0/BaseballMaster-3.1.0-AppStore.zip |
| 上传步骤 | [UPLOAD-GUIDE.md](UPLOAD-GUIDE.md) |
| 商店文案 | [APPSTORE-METADATA.md](APPSTORE-METADATA.md) 与五份 TXT |
| 隐私依据 | [APP-PRIVACY.md](APP-PRIVACY.md) |
| 三套各六张截图 | [APPSTORE-ARTWORK.md](APPSTORE-ARTWORK.md) |
| 服务状态 | [OPERATIONS.md](OPERATIONS.md) |
| 验证明细 | [ACCEPTANCE.md](ACCEPTANCE.md) |

Version **3.1.0 / Build 1**，Scheme BaseballMaster，Bundle ID `com.jasonchen.baseballmaster`，Team `JUTVG7XR9H`，App Store ID `6802063886`，最低 iOS 16。严格签名验证通过，App 与 dSYM UUID 均为 `6A3834E7-E07E-3072-B340-12AB9A042FD5`，隐私清单与上一版相同。归档使用本机 Apple Development 签名，Xcode App Store Connect 分发时重新签名；本机检查不代表已通过 Apple 服务端验证。

## 服务与兼容

共享服务兼容已部署至 `3.1.0-20261020`，协议仍为 1，最低 App 版本仍为 3.0，接收格式 1／2。旧格式比赛仍可由旧端记录；被新版写入的场次需兼容新版格式的客户端继续写入，防止资料丢失。新版球队档案明确版本 2，旧客户端不能将其当作完整旧档案恢复。

独立直播与观看页沿用现有版本，路线姓名由本版 App 生成。新旧邀请继续共存，旧单次邀请下线仍等待用户此前约定的通知。本轮不另行部署网站、不替用户上传或提交 Apple。

## Git 与材料回执

main 的实际同步提交、远端核对与工作区状态写入 `output/validation/3.1.0/release-2026-10-20/git-sync.json`，审核包内另附 git-sync.json 与 manifest.json。归档代码与本次合入源码一致；后续打包与文档校验不修改业务代码。归档、构建缓存、数据库和私有凭证不提交 Git，材料 ZIP 不包含签名归档或私有服务备份。

## 本轮最终验证

核心回归 302 项中 301 项通过、1 项生产专用先跳过；随后独立显式运行该项，正式 HTTPS 同步与档案验证通过。六项最终 UI 回归通过。共享服务 93 项、直播服务 17 项及 12 组公网展示验证沿用本次已部署记录。三套 18 张商店截图尺寸与透明通道检查通过，归档严格签名与 dSYM 一致性通过。
