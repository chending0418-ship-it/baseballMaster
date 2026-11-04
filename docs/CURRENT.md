# 棒球比赛记录大师当前入口

更新日期：2026-11-04。本文件是当前状态与资料的唯一导航入口，旧文档内的日期记录保留为历史。

| 项目 | 当前依据 |
| --- | --- |
| 平台工作目录 | 本目录为 iOS；Android 已独立迁至可见目录 [baseball-scorekeeper-android](../../baseball-scorekeeper-android/docs/CURRENT.md)，Gradle 工程位于该目录 `android/`。本次仅整理本机路径，既有版本和 PR 状态不变 |
| 跨平台计划维护 | iOS、Android 各版本分别维护发布功能计划；Android [0.8.1](https://github.com/chending0418-ship-it/baseballMaster/blob/codex/android-0.8.1/docs/plans/0.8.1%20%E5%8F%91%E5%B8%83%E5%8A%9F%E8%83%BD%E8%AE%A1%E5%88%92.md)／[0.8.2](https://github.com/chending0418-ship-it/baseballMaster/blob/codex/android-0.8.2/docs/plans/0.8.2%20%E5%8F%91%E5%B8%83%E5%8A%9F%E8%83%BD%E8%AE%A1%E5%88%92.md) 由各自分支维护 |
| Android 当前版本 | `codex/android-0.8.2`，0.8.2（Build 10）；示例名单、折叠跑垒分列与 PDF 对齐已完成，本轮经 PR #39 整合；[单份计划](<plans/0.8.2 发布功能计划.md>)、[开发交接](releases/android/0.8.2-DEVELOPMENT.md) |
| Android 分发与遗留 | 官网已于2026-11-04更新为0.8.2（Build 10）测试包，固定 APK 哈希保持；正式赛后归档／旧关联更新、完整跨端互导及实体厂商设备仍待后续，未上传应用市场 |
| 源码版本 | `BaseballMaster.xcodeproj/project.pbxproj`：3.1.2（Build 1） |
| 正式归档／导出 | iOS 3.1.2（Build 1）已从干净 main `3d3d83a` 完成 Release 真机 arm64 Archive 与 App Store IPA 导出；签名／dSYM／隐私清单核对通过，尚未上传／提交审核／上架，见 [归档交付](releases/3.1.2/ARCHIVE.md) |
| PR 整合状态 | #34、iOS #37 与 Android #39 已合入，main 基线 `b415236`。用户新增授权完成共享批量后台后，将本版完整 iOS 收尾及资料通过 [PR #41](https://github.com/chending0418-ship-it/baseballMaster/pull/41) 合入 main；最终公网回执现已冻结，可按本次用户授权经 PR 合并，App 上传／审核／上架分别记录 |
| 当前发布 | iOS 3.1.2（Build 1），功能代码已通过 #37 进入 main；本轮补齐备案展示、上海迁移源码／回执、已上线网页和共享批量服务，再整合 #41 资料。安全收尾分支 `codex/release-3.1.2-final-20261104` 从远端 #41 建立，不带旧拒绝素材父链 |
| 继承问题与验收 | P2 已修复；91 项 iOS 数据、iPhone 6 项 UI 分批通过，iPad Pro 8 项、mini 2 项 UI 通过；Debug／未签名 Release 成功。批量名单覆盖本机／对手／共享，共享后端 107 项通过，上海批量服务已上线、协议与旧客户端单人编辑兼容保持，九项公网专项已通过；统计页球员落点已接入统一球场，新增专项验证通过；旧记录仍待原件；备案展示已实现、服务器迁移已完成，App 发布仍待办，见 [3.1.2 开发记录](releases/3.1.2/DEVELOPMENT.md) |
| 设备兼容 | iPhone／iPad 已有验证，Duo 内外屏及半折叠 5 项专项 UI 场景已通过；默认展开首屏完整显示主要操作，页内 Pro 自然展开；原研究保留为 [兼容评估](plans/ADAPTIVE-DEVICE-SUPPORT.md)，实际进度及 Duo 下载入口见 [3.1.2](releases/3.1.2/DEVELOPMENT.md) |
| 本版发布功能计划 | [3.1.2 发布功能计划](<plans/3.1.2 发布功能计划.md>)；上版范围与承接见 [3.1.1 发布功能计划](<plans/3.1.1 发布功能计划.md>) |
| 上版发布功能计划 | [3.1.0 发布功能计划](<../3.1.0 发布功能计划.md>)，保留上版范围与发布事项 |
| 上版开发与验收 | [3.1.0 开发交接](releases/3.1.0/DEVELOPMENT.md)、[验收记录](releases/3.1.0/ACCEPTANCE.md) |
| 发布材料 | [3.1.0 发布交接](releases/3.1.0/REVIEW-HANDOFF.md)、`output/releases/3.1.0/` |
| 已确认上架 | 既有 README 记录用户确认 3.0 已上架；本轮没有连接 App Store Connect 核验新状态 |
| 3.1.0 上传与审核 | 现有交接为材料已准备、尚未上传或提交；后续以用户确认或回执更新 |
| 直播点选提示 | 已部署香港文字直播网页并通过公网哈希与健康检查；隐藏点选操作提示，保留球型与已选区域，刷新原链接生效；见 [部署记录](../live/deploy/2026-11-02-contact-wording.md) |
| 原设备连接问题 | 2026-11-02 用户确认改动后无需继续排查，已关闭；两场旧比赛原件核对仍保留在 I311-05 |
| 3.1.1 历史发布边界 | 开屏服务已增量部署到现有香港服务（固定 URL）；App 未正式归档、上传或提交审核，备案与上海迁移仍等待 |
| 服务实现 | `live/`、`collaboration/` 随本项目维护，原公网 URL 保持 |
| 上海迁移完成 | 2026-11-04 按用户后续停机迁移与切换确认正式接管：域名现指向上海 101.43.112.8，官网／后台／lifan／两套语音正常，产品无后缀地址与旧 HTML 均兼容；341 项隔离测试、九项公网 HTTPS、正式 COS 完整备份及配置恢复通过；同日新版中英隐私／支持页另获授权上线并同步静态摘要，用户手机已有球队／云端比赛／原二维码实测正常。香港棒球维护及旧写入围栏保留，jingsen.cc 正常，详见 [迁移第 15 节](plans/ICP-MIGRATION.md#15-备案通过后的核验与授权执行2026-11-04) |
| 公共品牌与官网 | [品牌](../../../brand/README.md)、[官网](../../../website/README.md) |
| 目录整理历史 | [迁移记录](../../../management/MIGRATION.md)，当次未修改业务代码、版本号或部署 |

需求与后续设计入口：[3.1.1 开机图](design/launch-screen/3.1.1/README.md)、[3.1.0 发布功能计划](<../3.1.0 发布功能计划.md>)、[3.1 原始详细规划](plans/3.1-BATTED-BALL.md)、[后续计划](plans/BACKLOG.md)、[产品设计](design)。

App Store 与版本宣传脚本现位于 `scripts/release/`，海报模板位于 `scripts/templates/`。例如在本项目目录运行 `python3 scripts/release/package-release-3.1.0.py`，输出继续使用本项目原有 `output/` 分类。分享卡片部署脚本位于 `scripts/website/`，执行部署需要用户另行授权。

公共品牌与官网分别直接使用工作区 brand/、website/ 的真实目录；App 内不设置目录替身。修改公共资料在总品牌仓库提交。完整资料位置见 [总导航](../../../management/AGENT-GUIDE.md)。

当前微信群宣传源稿：docs/design/marketing/3.1.0/wechat-2026-10-22/source/。在 App 根目录运行 node docs/design/marketing/3.1.0/wechat-2026-10-22/source/render.cjs；导出仍写入本 App 的 output/marketing/3.1.0/wechat-2026-10-22/。历史输出中的可编辑脚本与 SVG 已保存到 docs/archive/output-sources/，按其 manifest 追溯，不能将历史设计当作当前交互基准。

## 2026-11-04 发布资料与宣传准备

本轮材料从 [3.1.2 发布资料](releases/3.1.2/README-先看这里.md) 取用：商店 11 组尺寸共 34 张；审核文字及中英隐私／支持新网址内容；官网只策划；小红书 6 张双平台图与微信一页图。按用户反馈，主海报按用户认可的原摄影构图作四设备产品效果图（iPad、iPhone、Duo、Android 手机），仅保留 Duo 为折叠机型；单功能页使用经审查的实际界面，所有对外宣传不显示版本号，Android 保留测试状态。

材料分支 `codex/release-3.1.2-materials-20261104` 独立保存；现有 iOS 工作目录的其他未提交文档和 App 修改保留。本轮只准备、验证和打包素材，不构建 App、不部署网站或服务、不进行真实数据迁移、不上传／提交商店或社交平台、不合并。新隐私／支持目标 URL 尚未上线，正式环境与账户字段待发布流程核对。

同日最终宣传修订：保留原摄影主视觉的构图、光影、角度与比例，仅去掉 Android 折叠屏，留下 iPad、iPhone、Duo、Android 手机四台。iPhone 展示原开机图，Android 展示无球员资料的规则页；Duo 仅留很轻折痕。主海报与标题整体居中、无逐台标签、无版本号。旧 Android 比赛／名单／统计含真实未成年人资料，已撤回先前“合成”判断，当前 Git 资产树与交付包不再包含，41 张当前 PNG 的名单 OCR 排除检查通过。

同期状态：备案已通过（App 沪ICP备2026058730号-2A），用户确认商店备案配置已完成；上海预迁移由独立任务推进，正式切换和本批隐私／支持新地址上线不由本轮素材准备替代。

发布材料已推送至独立分支，目标 main 的 [PR #41](https://github.com/chending0418-ship-it/baseballMaster/pull/41) 已建立，尚未合并。首个远端材料提交为 `fbb32a9b70a42944d06679638895de0485dd0c5a`，直接以 main `b415236dff77b472294b6825e9699778962c194d` 为唯一父提交；其 tree `73c2a316c3cd8ad30bb76328778a0c02015c8096` 与本地最终评审提交 `80063c529b2131f8ea64b8370c82e9e811f1815a` 完全一致。被撤回的旧材料提交未进入远端父链；此处仅补充交付链接与回执，图片、脚本和发布边界不变。

## 2026-11-04 网页正式上线与审核资料统一交付

用户追加授权上海发布后，官网产品介绍、中英文隐私／支持已完成部署；新 `/scorekeeper/privacy.html`、`support.html` 与两份英文页已上线，旧隐私／支持保留 200 和原锚点。官网使用四设备透明底效果图，无双重底色及宣传版本号；首页底部网站备案已在公网手机视口确认。批次 `website-20261104T125935Z`，12 个文件 HTTPS 摘要通过、92 个其他静态文件保持，Nginx 检查后平滑重载，未修改业务后台或 DNS。部署与公网证据：`output/validation/3.1.2/website-publish-2026-11-04/`。

此状态替代上文“官网只策划／新网址未上线”。隐私据上海现状更新，区分30天日常球队备份与暂无自动到期的迁移／完整副本及香港旧副本。全部取用入口与字段见 `docs/releases/3.1.2/APPSTORE-填写清单.md`，总包 `output/releases/3.1.2/发布准备资料.zip`。App 正式归档、上传审核、上架与共享批量后台部署仍未执行；网页上线不替代这些发布步骤。

## 2026-11-04 共享批量后台上线与完整版本整合（当前）

用户新增授权后，上海共享批量后台已部署，批次 `bulk-3.1.2-20261104T131831Z`；仅更新 `server.mjs` 与 `roster.mjs`，部署前加密备份成功，33 张表无 DDL／未恢复数据库，启动完整性通过，其他五服务 PID 不变。协议 1、最低 App 3.0 及旧客户端单人名单编辑兼容保持；共享批量仍要求管理员或记录员权限，事务、修订与幂等检查继续生效。部署回执：`output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json`。九项公网合成专项已全部通过，权限、无效批次不写入、幂等、旧单人接口和撤权保护通过；合成球队已正常解散、临时凭据删除。部署后日备份／完整状态备份及配置备份独立下载解密摘要核验通过，最终回执已冻结。

此状态替代前述按日期保留的“批量后台待部署／本轮不合并”阶段安排。用户已授权将本版完整 iOS 收尾及资料通过 [PR #41](https://github.com/chending0418-ship-it/baseballMaster/pull/41) 合入 main；最终公网与部署记录已冻结，可按本次用户授权执行，最终合并状态以 GitHub 回执为准。工作区整理 #40、Android 业务及总仓库网站源码不混入本 PR；App 工程仍为 3.1.2（Build 1），正式签名归档、上传、审核、上架分别待真实流程。


本次迁移工具、备案展示及运行交接保存为 [Draft PR #42](https://github.com/chending0418-ship-it/baseballMaster/pull/42)，分支 `codex/icp-shanghai-cutover-review-20261104-d7700543`；未合并、未上传 App。品牌官网及语音说明在各自无远端的本机仓库独立提交，未混入其他聊天的工作区提交。

### 微信宣传一页图功能区居中修正

2026-11-04 用户指出底部四组功能文字未居中。仅为 `.all-family section` 增加居中排版，标题与说明在各自等宽列内共享中心线；增加 `--wechat-only` 导出选项，微信1080×1800 PNG/JPG、预览及微信／总资料ZIP已重新生成并验证，未修改App或线上服务。

### 简体中文推广文本与关键词ASO调整

2026-11-04 按用户要求重写推广文本（130字符）与关键词（98 UTF-8字节），保留名称／副标题，区分推广文本的下载转化用途和关键词的搜索覆盖用途。与副标题重复的完整词位已减少，无版本号／竞品词／虚构热度或排名，字段及资料总包同步更新。依据与取词逻辑记在本版APPSTORE-METADATA；未代填App Store Connect。

## 2026-11-04 正式 Release 归档与 App Store IPA 导出（当前）

已从干净 main `3d3d83a6aaf64be353e0f71c749a3e7e3ad879d6` 完成 iOS 3.1.2（Build 1）Release generic iOS device（arm64）归档，并以 `app-store-connect`／`destination=export` 导出 App Store 分发 IPA，未使用上传。签名、版本／Build／Bundle ID、设备族、最低 iOS、隐私 plist 与 dSYM 一致性核对通过；导出包 `get-task-allow=false`，无设备白名单。

正式目录：`output/releases/3.1.2/archives/BaseballMaster-3.1.2-build1.xcarchive`；IPA：`output/releases/3.1.2/app-store-export/BaseballMaster.ipa`，14,793,278 bytes，SHA-256 `f3f068b8f9dfe695f1902a2dc73affb1b54ce13d1966f532f69b18cd631cc572`。源归档的自动开发签名与导出后的 App Store 分发重签分别记录；正式二进制／签名不进入 Git 或宣传 ZIP。详见 [归档交付](releases/3.1.2/ARCHIVE.md)。

本段替代此前按日期保留的“未正式归档”阶段状态。App 尚未上传、提交审核或上架，归档／导出不代表 Apple 服务端验证通过。本次没有重跑 App 功能测试、修改源码或版本。

## Android 官网下载更新（2026-11-04）

用户授权后，上海官网两个 Android 下载入口已切换至0.8.2（Build 10），三文件HTTPS哈希与103个受保护静态文件检查通过；本地四视口及点击完整下载校验通过。固定 APK 未重新构建，旧版下载与 App Store 保留。公网四视口及实际点击完整下载SHA验证也已通过，分发回执见 [0.8.2计划](<plans/0.8.2 发布功能计划.md>) 末尾记录；未上传应用市场。

分发文档已在 `codex/android-0.8.2-download` 推送，交接 [PR #46](https://github.com/chending0418-ship-it/baseballMaster/pull/46) 保持开放、未合并；官网分发本身已完成。
