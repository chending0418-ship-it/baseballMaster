# iOS 3.1.2 发布资料取用

2026-11-04。本轮准备商店图片、审核文字、隐私／支持内容、官网策划，以及双平台社交宣传。工程仍为 3.1.2（Build 1）。全部对外宣传图片和文案不展示版本号；内部目录、审核元数据与验证记录保留版本用于追溯。

## 六项交付

| 用户事项 | 本轮完成内容 | 入口／取用 |
| --- | --- | --- |
| App Store 产品图 | 原有三组 iPhone 加 Duo 四组、iPad 四组，共 11 组 34 张 PNG；保持真实 UI 与比例 | [尺寸与来源](APPSTORE-ARTWORK.md)、`output/releases/3.1.2/app-store/index.html` |
| 全部发布审核文字 | 宣传文本、描述、关键词、更新说明、中英文审核备注、账户待填项与隐私申报依据 | [元数据](APPSTORE-METADATA.md)、[审核交接](REVIEW-HANDOFF.md)、[隐私申报](APP-PRIVACY.md) |
| 新 privacy／support | 上海中英文新页已上线，匿名 HTTP 200；旧路径以 HTTP 200 提供兼容内容 | [网址与网页交接](WEB-CONTENT-HANDOFF.md) |
| 官网介绍 | 产品页已在上海更新，四设备安全主视觉采用真正透明底；保持已实现功能与 App 待上架边界 | [官网策划](../../design/marketing/3.1.2/web/website-plan.md) |
| 小红书图片与文案 | 6 张 1080×1440；四设备摄影产品主视觉（iPad、iPhone、Duo、Android 手机）与经审查的独立功能页；含 Android 测试版宣传 | [文案与边界](../../design/marketing/3.1.2/social/copy.md)、`output/marketing/3.1.2/xiaohongshu/2026-11-04/` |
| 微信一页图 | 1080×1800 PNG／JPG，介绍两平台及折叠支持，无版本号 | `output/marketing/3.1.2/wechat/2026-11-04/` |

总取用页：`output/releases/3.1.2/index.html`。总包：`output/releases/3.1.2/发布准备资料.zip`，仅包含本轮可取用图片与文字，不含私有原件、旧拒绝稿或独立官方机壳素材。

## 网站目标

- 中文隐私：`https://baseballmaster.cc/scorekeeper/privacy.html`
- 中文支持：`https://baseballmaster.cc/scorekeeper/support.html`
- 英文隐私／支持：同目录 `privacy-en.html`／`support-en.html`

**以上四个新地址已在上海上线，匿名 HTTPS 访问均返回 HTTP 200。** 旧 `/privacy.html` 与 `/support.html` 同样返回 HTTP 200，提供兼容内容供 App 现有硬编码继续使用，本轮不改 App 链接。上海已接管正式在线服务与主业务数据；新版隐私正文同时说明上海日常球队备份按 30 天管理、迁移完整副本和香港回退副本目前无自动到期。官网只使用 `four-devices-transparent-{640,960,1448}.webp` 三档真正透明底主视觉，社交渠道原不透明画面不再用于官网。

本轮已核对新四页、旧两页与产品页的匿名 HTTP 200；中文和英文隐私页的 `#rights` 锚点可达，可取用下方对应网址填写商店。网页上线不表示已代填 App Store Connect 的 URL 或隐私问卷。备案已通过且用户已确认商店备案配置完成。

## 本轮验证

- 34 张商店 PNG、6 张小红书 PNG／JPG 与微信 PNG／JPG：尺寸、无透明、图片加载、文字边界、标题无标点和输出 SHA-256 均检查；分组与单张视觉复核完成。社交新版 6 张与微信均逐张打开复核。
- 小红书正文／图片无版本号、网址、下载提示、联系信息或二维码；Android 明确测试状态。对外图片都不含版本号；版本字段只留审核和内部追溯。
- 主海报用内置 ImageGen 保留原摄影构图最小编辑为四设备产品效果图，并标注效果图；真实开机画面、无球员资料的 Android 规则页及泛化演示标记避免使用真实姓名。单功能页机身及界面来源可追溯，不以外壳或效果图表示真机验收。
- Apple 许可由用户明确同意后提取，原壳与蒙版排除 Git 和交付 ZIP。旧简化示意稿移入本机输出的 `drafts/illustrated-rejected/` 和源稿 archive，不再交付。
- 商店文本按 Apple 当前字符／UTF-8 字节上限检查；中英网页及内部链接核对。完整记录由 `scripts/release/package-release-3.1.2.py` 写入 `output/validation/3.1.2/release-materials-2026-11-04/final-validation.json`。
- 素材准备没有重新构建 App、执行 App 功能／真机测试或正式归档。截图从既有本版验证附件和原生显示屏导出，不将复用验证说成重新测试；后续获授权的网站发布单独记录部署及公网回执。

## 保留到发布阶段的事项

备案 `沪ICP备2026058730号-2A` 已通过、商店备案配置据用户确认完成，上海已接管正式在线服务。正式 Release 归档及 App Store IPA 导出已完成；其余 App Store 账户字段、真实旧比赛核对、迁移收尾与副本清理、上传／审核／上架仍按各自真实流程记录；共享批量服务已在上海上线，九项公网专项已通过；新隐私／支持页及官网更新已完成部署与 HTTPS 哈希核验。App 预览视频未制作，用户列举的视频尺寸已独立记录，不将 PNG 算作视频。

本轮素材不表示 iOS 已上架新功能；社交稿待相应功能实际可下载后传播。Android 采用当前官网测试包的已验收界面，不把尚未分发的新包写成已发布。两端互导及全部云端能力一致未完成，不作跨平台数据完全同步的宣传承诺。

私人资料更正：旧 Android 测试截图含真实未成年人资料，先前“合成资料”判断错误，全部撤出当前资产树与交付包。当前 Android 仅使用无姓名、无球队资料的通用规则／设置截图；球员落点单页改用 iOS 张三演示记录。41 张当前 PNG 执行本地 OCR 排除检查，未命中被禁止的名单；人工复核仍是必要检查。

材料源稿：[PR #41](https://github.com/chending0418-ship-it/baseballMaster/pull/41)，目标 main；用户已授权本 PR 合并，最终状态以 GitHub 回执为准。上海接管由独立迁移任务完成；后续官网与新隐私／支持页已于本轮正式上线。本材料 PR 不替代 App 归档、上传、审核、上架或社交平台发布。

## 2026-11-04 网站上线回执

批次 `website-20261104T125935Z` 已在上海完成静态网站发布：12 个文件的 HTTPS SHA-256 与发布清单一致，92 个其他静态文件未变，`nginx -t` 通过，后端代码及数据未修改。新四页、旧两页及产品页已另核验匿名 HTTP 200，两种语言隐私页具有 `rights` 锚点。公网多视口视觉验收由主代理另行记录，不将部署哈希核对替代视觉验收。

证据：[deployment-receipt.json](../../../output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json)。App 未因本次网页发布而归档、上传、提交审核或上架，共享批量服务在该次网页发布时点尚未部署；随后上线状态见下节。

## 2026-11-04 共享批量服务上线（当前）

上海共享批量后台已按用户新增授权部署，批次 `bulk-3.1.2-20261104T131831Z`。仅 `server.mjs`、`roster.mjs` 两文件更新并核对 SHA，部署前加密备份成功；33 张表无 DDL、停服期间未恢复或改写数据库，启动后完整性通过；其他五个服务 PID 未变。协议保持 1、最低 App 版本保持 3.0，`bulkRosterImport` 能力已开启；权限、修订、事务和幂等校验继续保留，旧客户端单人编辑兼容。九项公网合成专项已全部通过，合成球队按正常流程解散、临时凭据删除；部署后日备份和完整状态备份成功，配置备份下载、解密和摘要核验通过。最终回执已冻结，可按用户授权经本 PR 合入 main。

部署证据：[deployment-receipt.json](../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json)。 公网证据：[public-verification.json](../../../output/validation/3.1.2/bulk-backend-deploy-2026-11-04/public-verification.json)。后续网站批次 `website-20261104T132137Z` 的 12 文件摘要、92 其他静态文件、18 项公网视口及启动清单刷新均通过。此状态替代前述网站发布时点的批量待部署说明。用户已授权本版通过 PR 合入 main；该后台发布时点尚未正式归档；随后已完成正式 Release 归档与 App Store IPA 导出，见 [ARCHIVE.md](ARCHIVE.md)。App 上传、提交审核及上架仍未执行。

## 2026-11-04 正式 Release 归档与 App Store IPA 导出（当前）

已从干净 main `3d3d83a6aaf64be353e0f71c749a3e7e3ad879d6` 完成 iOS 3.1.2（Build 1）Release generic iOS device（arm64）归档，并以 `app-store-connect`／`destination=export` 导出 App Store 分发 IPA，未使用上传。签名、版本／Build／Bundle ID、设备族、最低 iOS、隐私 plist 与 dSYM 一致性核对通过；导出包 `get-task-allow=false`，无设备白名单。

正式目录：`output/releases/3.1.2/archives/BaseballMaster-3.1.2-build1.xcarchive`；IPA：`output/releases/3.1.2/app-store-export/BaseballMaster.ipa`，14,793,278 bytes，SHA-256 `f3f068b8f9dfe695f1902a2dc73affb1b54ce13d1966f532f69b18cd631cc572`。源归档的自动开发签名与导出后的 App Store 分发重签分别记录；正式二进制／签名不进入 Git 或宣传 ZIP。详见 [ARCHIVE.md](ARCHIVE.md)。

本段替代此前按日期保留的“未正式归档”阶段状态。App 尚未上传、提交审核或上架，归档／导出不代表 Apple 服务端验证通过。本次没有重跑 App 功能测试、修改源码或版本。
