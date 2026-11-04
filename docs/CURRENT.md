# 棒球比赛记录大师当前入口

更新日期：2026-11-04。本文件是当前状态与资料的唯一导航入口，旧文档内的日期记录保留为历史。

| 项目 | 当前依据 |
| --- | --- |
| 平台工作目录 | 本目录为 iOS；Android 已独立迁至可见目录 [baseball-scorekeeper-android](../../baseball-scorekeeper-android/docs/CURRENT.md)，Gradle 工程位于该目录 `android/`。本次仅整理本机路径，既有版本和 PR 状态不变 |
| 跨平台计划维护 | iOS、Android 各版本分别维护发布功能计划；Android [0.8.1](https://github.com/chending0418-ship-it/baseballMaster/blob/codex/android-0.8.1/docs/plans/0.8.1%20%E5%8F%91%E5%B8%83%E5%8A%9F%E8%83%BD%E8%AE%A1%E5%88%92.md)／[0.8.2](https://github.com/chending0418-ship-it/baseballMaster/blob/codex/android-0.8.2/docs/plans/0.8.2%20%E5%8F%91%E5%B8%83%E5%8A%9F%E8%83%BD%E8%AE%A1%E5%88%92.md) 由各自分支维护 |
| Android 当前版本 | `codex/android-0.8.2`，0.8.2（Build 10）；示例名单、折叠跑垒分列与 PDF 对齐已完成，本轮经 PR #39 整合；[单份计划](<plans/0.8.2 发布功能计划.md>)、[开发交接](releases/android/0.8.2-DEVELOPMENT.md) |
| Android 分发与遗留 | 官网仍为 0.8.1（Build 9）测试包，0.8.2 固定 APK 未重新分发；正式赛后归档／旧关联更新、完整跨端互导及实体厂商设备仍待后续，未上传应用市场 |
| 源码版本 | `BaseballMaster.xcodeproj/project.pbxproj`：3.1.2（Build 1） |
| PR 整合授权 | 2026-11-04 用户授权整理并合入 #34、#37，再整合验证 #39；#34 已合并为 `e58ea84`，#37 已合并为 `510021b`；#39 在本分支完成整合验证后通过 PR 合入。整合验证详见 [记录](maintenance/2026-11-04-pr-integration.md)。本轮合并不包含真实迁移、服务部署或商店上传 |
| 当前开发 | `codex/dev-3.1.2`，3.1.2（Build 1）；iPhone／iPad、批量名单与统计页球员落点保留；Duo 适配及专项模拟器验收已完成；投手额度已修复换局提前计入，30 项专项数据回归通过；3.1.1 未完成项全部纳入，见 [3.1.2 开发与发布待办](releases/3.1.2/DEVELOPMENT.md) |
| 继承问题与验收 | P2 已修复；91 项 iOS 数据、iPhone 6 项 UI 分批通过，iPad Pro 8 项、mini 2 项 UI 通过；Debug／未签名 Release 成功。批量名单覆盖本机／对手／共享，共享后端 107 项通过但未部署；统计页球员落点已接入统一球场，新增专项验证通过；旧记录、备案及迁移仍待办，见 [3.1.2 开发记录](releases/3.1.2/DEVELOPMENT.md) |
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
| 上海迁移准备 | 隔离验收、正式证书、上海 COS 备份恢复及正反向迁移演练已通过；正式候选配置已校验但未安装。用户决定等备案通过后再开始真实数据迁移，今晚 23:00 不执行维护；生产数据未复制、域名未切换、香港未操作，见 [迁移方案与阶段记录](plans/ICP-MIGRATION.md#14-真实数据迁移前的准备交接2026-10-23) |
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

发布材料已推送至独立分支，目标 main 的 [Draft PR #41](https://github.com/chending0418-ship-it/baseballMaster/pull/41) 已建立，尚未合并。首个远端材料提交为 `fbb32a9b70a42944d06679638895de0485dd0c5a`，直接以 main `b415236dff77b472294b6825e9699778962c194d` 为唯一父提交；其 tree `73c2a316c3cd8ad30bb76328778a0c02015c8096` 与本地最终评审提交 `80063c529b2131f8ea64b8370c82e9e811f1815a` 完全一致。被撤回的旧材料提交未进入远端父链；此处仅补充交付链接与回执，图片、脚本和发布边界不变。

## 2026-11-04 网页正式上线与审核资料统一交付

用户追加授权上海发布后，官网产品介绍、中英文隐私／支持已完成部署；新 `/scorekeeper/privacy.html`、`support.html` 与两份英文页已上线，旧隐私／支持保留 200 和原锚点。官网使用四设备透明底效果图，无双重底色及宣传版本号；首页底部网站备案已在公网手机视口确认。批次 `website-20261104T125935Z`，12 个文件 HTTPS 摘要通过、92 个其他静态文件保持，Nginx 检查后平滑重载，未修改业务后台或 DNS。部署与公网证据：`output/validation/3.1.2/website-publish-2026-11-04/`。

此状态替代上文“官网只策划／新网址未上线”。隐私据上海现状更新，区分30天日常球队备份与暂无自动到期的迁移／完整副本及香港旧副本。全部取用入口与字段见 `docs/releases/3.1.2/APPSTORE-填写清单.md`，总包 `output/releases/3.1.2/发布准备资料.zip`。App 正式归档、上传审核、上架与共享批量后台部署仍未执行；网页上线不替代这些发布步骤。
