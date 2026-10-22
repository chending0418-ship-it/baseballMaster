**最新安排（2026-10-19）：** [3.0.1 修复](<../3.0.1 发布功能计划.md>) 已完成并通过模拟器回归；重点为本机原稿与共享副本独立。见 [验收与发布边界](releases/3.0.1/ACCEPTANCE.md)。兼容服务及新旧邀请共存页面已部署，签名归档／审核材料已准备；[上传交接](releases/3.0.1/REVIEW-HANDOFF.md)，App 尚未提交。

**3.0 交付入口（2026-10-13）：** [审核材料、最终归档和上传步骤](releases/3.0/REVIEW-HANDOFF.md) · [验证](releases/3.0/ACCEPTANCE.md) · [运行手册](releases/3.0/OPERATIONS.md)。网站与服务已部署，App 上传审核由用户执行。

# 项目文档

[网站总后台使用说明](releases/3.0/ADMIN-CONSOLE.md)：已上线，独立密码登录后查看和下载全部云端业务资料，与 App 的球队角色和本次审核提交独立。

日常新增需求写入对应的 [2.1 计划](<../2.1 发布功能计划.md>)、[2.2 现有功能优化计划](<../2.2 发布功能计划.md>)、[2.3 慢垒与已有功能优化计划](<../2.3 发布功能计划.md>)、[3.0 发布功能计划](<../3.0 发布功能计划.md>)、[3.0.1 共享球队修复计划](<../3.0.1 发布功能计划.md>) 或 [3.1 击球记录改版规划](plans/3.1-BATTED-BALL.md)；具体开发与发布状态见 [总 TODO](../TODO.md)。

| 目录／文件 | 用途 |
| --- | --- |
| [PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md) | 项目目录、命名和输出位置约定 |
| [BRANCHES.md](BRANCHES.md) | 当前分支、唯一项目目录及后续版本的开发约定 |
| [历史 PR 索引](maintenance/2026-10-22-pr-history.md) | 全部历史提交的补录 PR 与覆盖关系；后续改动均通过 PR 合入 main |
| [releases/2.1/](releases/2.1/) | 已上线 2.1 的功能、验证、截图、宣传材料及审核准备记录 |
| [正式直播部署](../live/deploy/2026-09-30-production.md) | 公网服务、模拟器联调、运维与回滚 |
| [releases/2.2/](releases/2.2/) | 完整开发 TODO；导航、导出／标题／阵容／统计、直播 UI、回归与发布 |
| [2.3 需求与验收清单](<../2.3 发布功能计划.md>) | 慢垒与已有功能优化的单份计划、开发和验收记录 |
| [3.0 发布功能计划](<../3.0 发布功能计划.md>) | 本版功能、已确认规则、完成清单、待提交事项和交付入口，含记录员编辑与两管理员限制 |
| 3.0.1 共享修复 | [唯一计划](<../3.0.1 发布功能计划.md>) · [验收](releases/3.0.1/ACCEPTANCE.md)；八项修复与验证已完成，Logo 配置此前独立部署 |
| [3.0 球队协作详细规划](plans/3.0-TEAM-COLLABORATION.md) | 保留详细方案、62 项原编号 TODO、49 个验收场景；开发与部署完成，套餐核对／30 天增长为运行期事项 |
| [3.0 页面与流程提案](design/3.0/README.md) | v2：现有记分页基准、17 张静态提案与 12 页 PDF；主要流程已确认 |
| [3.1 击球记录改版规划](plans/3.1-BATTED-BALL.md) | 已确认版本范围：球型与去向、双方打者本场／年度表现及击球图、常用局面入口；BB01–BB08 待开发 |
| [Android 与 APK 暂缓规划](plans/ANDROID-APK.md) | 客户端、真实跨平台验证、APK 分发与更新暂缓，原 AN01–AN08 保留，版本待定 |
| [后续待办](plans/BACKLOG.md) | Android、小组件与设备适配等暂缓需求；击球记录已转入 3.1 主规划 |
| [releases/2.0/](releases/2.0/) | 2.0 发布流程与交接记录 |
| `releases/<旧版本>/` | 其他版本的发布与验收记录，按版本保留 |
| [brand/](brand/) | 已确认的 Logo 基准、[宣传图设计规范](brand/PROMOTIONAL-DESIGN.md)、参考成图与可复用源稿 |
| [research/](research/) | 独立研究资料 |
| [archive/plans/](archive/plans/) | 旧需求、草案、确认清单和历史验证记录 |
| [archive/screenshots/](archive/screenshots/) | 早期界面截图，不作为当前版本截图 |
| [maintenance/](maintenance/) | 仓库整理等维护记录 |

2.1 常用文件：[审核提交交接](releases/2.1/REVIEW-HANDOFF.md)、[商店字段与可复制文案](releases/2.1/APPSTORE-METADATA.md)、[隐私申报](releases/2.1/APP-PRIVACY.md)、[纠错功能](releases/2.1/CORRECTIONS.md)、[纠错任务清单](releases/2.1/CORRECTION-TODO.md)、[同步回归记录](releases/2.1/SYNC-REGRESSION.md)、[验证摘要](releases/2.1/validation.json)、[宣传图说明](releases/2.1/APPSTORE-ARTWORK.md)。

2.2 常用文件：[完整开发 TODO](releases/2.2/DEVELOPMENT-TODO.md)、[导出、分享、阵容与统计技术 TODO](releases/2.2/PRODUCT-IMPROVEMENTS-TODO.md)、[直播 UI 独立任务](releases/2.2/LIVE-UI-TODO.md)、[直播 UI 验收与截图](releases/2.2/LIVE-UI-VALIDATION.md)、[导航与终场界面 TODO](releases/2.2/NAVIGATION-TODO.md)、[手动结束比赛导航修复](releases/2.2/MANUAL-END-FIX.md)。

2.3 需求统一保存在 [单份计划](<../2.3 发布功能计划.md>) 中：其中包含 [慢垒 TODO](<../2.3 发布功能计划.md#slow-pitch-todo>) 和 [方案与验收矩阵](<../2.3 发布功能计划.md#slow-pitch-plan>)；小组件已迁至 [后续待办](plans/BACKLOG.md#widget-todo)，版本未定。

3.0 先看根目录 [发布功能计划](<../3.0 发布功能计划.md>)，与 2.3 保持同样的查找入口。详细 [规划](plans/3.0-TEAM-COLLABORATION.md)、[开发 TODO](plans/3.0-TEAM-COLLABORATION.md#development-todo)、[验收矩阵](plans/3.0-TEAM-COLLABORATION.md#acceptance) 与 [发布条件](plans/3.0-TEAM-COLLABORATION.md#release-gates) 保存在同一份文档。D01 主要流程已确认，提案按反馈更新为 v2；2026-10-10 新增比赛时间补填与更正，用户明确只评估并加入 TODO。2026-10-11 用户随后明确开始开发，已在同一目录建立 `codex/dev-3.0`，工程 3.0（Build 1）。首批实现与验证见 [开发进度](releases/3.0/DEVELOPMENT.md)；现有 PDF 为此前提案，不代替实际验证。不使用 iCloud／CloudKit。

3.0 [服务器与容量评估](plans/3.0-TEAM-COLLABORATION.md#capacity-assessment) 已补充只读硬件／网络小样本和数据结构估算；沿用 `baseballmaster.cc` 及现有服务器。当时评估不等同于正式验收；2026-10-13 已补齐 [完整 App 样本测量](releases/3.0/CAPACITY.md) 与 [发布验证](releases/3.0/ACCEPTANCE.md)。

2026-10-13 用户调整 [3.1](plans/3.1-BATTED-BALL.md)：击球记录改版正式纳入，Android、真实跨平台验证、APK 分发与更新暂缓并移出本版。此前 Android 清单中的基础比赛、历史统计、球队协作与直播观看均指 Android 端工作，iOS 3.0 已有对应功能；原方案保留在 [Android 暂缓规划](plans/ANDROID-APK.md)。本轮仅更新规划与入口，3.1 尚未开始业务开发。

归档中的旧版本安排、测试数量、未完成事项和文件名仅用于追溯；以当前发布计划和最新验收记录判断状态。

## 2.2 发布入口

- [发布与审核交接](releases/2.2/REVIEW-HANDOFF.md)
- [预览和截图](releases/2.2/APPSTORE-ARTWORK.md)
- [推广、描述、新增内容和审核备注](releases/2.2/APPSTORE-METADATA.md)
- [正式直播部署](../live/deploy/2026-10-03-production-2.2.md)
