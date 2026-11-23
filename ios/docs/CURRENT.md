# 棒球比赛记录大师 · iOS 当前入口

更新：2026-11-23。本文件是 iOS 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 3.1.4 功能计划 | [3.1.4 发布功能计划](<plans/3.1.4 发布功能计划.md>) 已实现指派、双人记录、赛中补员及中途交接，见[开发 PR #76](https://github.com/chending0418-ship-it/baseballMaster/pull/76)。iPhone／iPad 双模拟器代跑得分联调通过；Android 联调、线上服务和分发待执行。 |
| 当前发布 | **用户于 2026-11-05 确认：iOS 3.1.2 已通过审核并上架**。本轮未读取 App Store Connect，不推定实际审核通过时间或线上二进制的源码提交／Build |
| 源码配置 | 本开发分支配置为 3.1.4（Build 1）；3.1.3 Build 6 的上传回执另见下方。3.1.5 另见 [草稿 PR #57](https://github.com/chending0418-ship-it/baseballMaster/pull/57) |
| 本版计划 | [3.1.2 发布功能计划](<plans/3.1.2 发布功能计划.md>)；[上版计划](<plans/3.1.1 发布功能计划.md>) |
| 3.1.3 TestFlight 候选 | [3.1.3 发布功能计划](<plans/3.1.3 发布功能计划.md>)。候选分支整合 [PR #67](https://github.com/chending0418-ship-it/baseballMaster/pull/67) 的记分、直播、纠错、统计和滚动改动，以及 [PR #68](https://github.com/chending0418-ship-it/baseballMaster/pull/68) 的[比赛分析与品牌 PDF](plans/比赛分析.md)。整合验收完成。Build 4 加入四垒跑者确认图和守备栏排版调整。用户于 2026-11-17 确认 Build 4 已从 TestFlight 安装，四个主 Tab 仍有卡顿。Build 5 已修正主 Tab 重复刷新与共享比赛的无变化写入，Pro 击球后复用四垒确认图，签名归档已交付，待用户上传及真机复核。线上球队服务已启用比赛分析。 |
| 3.1.3 Build 6 | 已完成 Release 签名归档。11 月 18 日 13:27（北京时间）上海球队服务已启用球队赛程。公网独立记分员和成员身份验证了发布、查看、改期、取消和重试去重。2026-11-18，用户确认已将 Build 6 上传至 TestFlight。Apple 构建处理、测试组分发及真机安装待确认。见[版本计划 I313-11](<plans/3.1.3 发布功能计划.md>) 与[草稿 PR #71](https://github.com/chending0418-ship-it/baseballMaster/pull/71)。 |
| 捕手守备统计 | [3.1.3 的 Build 3](<plans/3.1.3 发布功能计划.md>) 已支持捕手阻杀 CCS 和捕逸 PB 的记分、守备统计与 PDF。 |
| 后续版本 | [3.1.5 发布功能计划](<plans/3.1.5 发布功能计划.md>)。转播交接与重启串场已修复，待用模拟器配合 OBS 检查及部署。总 Admin 已上线语音开启码管理和发放备注，App 单局面录入已接入。主屏幕改名已核对，Duo 外屏图标文字会截断。单投手球数可直接输入，球数与登板局数到限只提醒。六项改动已整合。同一个模拟器构建的 iPhone、iPad 和 Duo 回归共 22 项通过。 |
| 3.1.5 送审材料 | [审核文案、截图与提交清单](releases/3.1.5/APPSTORE-填写清单.md)已备妥，App 已增加录音前同意流程，公开隐私政策和审核专用开启码待完成。 |
| 发布资料 | [3.1.2 材料入口](releases/3.1.2/README-先看这里.md)、[正式归档证据](releases/3.1.2/ARCHIVE.md)、[开发与验收](releases/3.1.2/DEVELOPMENT.md)；其中按日期保留的“未上传／未上架”为历史阶段 |
| 服务状态 | 上海球队服务于 2026-11-17 启用 `game-analysis`。球队健康检查通过，其他服务未重启。部署和在线验收见 3.1.3 计划 |
| 共享服务维护 | [collaboration](../../collaboration/PROTOCOL.md)、[live](../../live/README.md)仍由 iOS／服务工作入口管理 |
| Admin 实时直播 | 「打开观赛页」支持临时直播与共享球队直播。验证与上海生产部署回执见 [PR #50](https://github.com/chending0418-ship-it/baseballMaster/pull/50) |
| Android | [Android CURRENT](../../android/docs/CURRENT.md)，独立版本与发布计划，不在此复制进度 |
| 当前维护 | [文档与契约阶段一](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)，[PR #49](https://github.com/chending0418-ship-it/baseballMaster/pull/49) 已合入，两个正式工作目录已采用新布局 |
| 共享资料 | [契约](../../contracts/README.md)、[设计与需求](../../docs/README.md)；[历史 3.1.0 计划](<plans/3.1.0 发布功能计划.md>) |

真实旧比赛原件核对等产品遗留继续按本版计划跟踪。上架不自动代表这些事项完成。3.1.5 的签名测试与分发状态见候选版本计划。

本次审查整改及采用边界：[旧 CURRENT 分流与两个工作目录同步步骤](../../docs/maintenance/2026-11-05-stage1-adoption.md)。采用后新增状态写入对应平台的现行入口和版本计划。

本机原有维护工作已保全并恢复：开屏上传修复仍属 [PR #47](https://github.com/chending0418-ship-it/baseballMaster/pull/47)，未部署；证书维护的已执行回执与源码属于 [PR #48](https://github.com/chending0418-ship-it/baseballMaster/pull/48)，保持原已部署／未合并的阶段边界。iOS 正式工作目录中，旧 3.1.0 计划的两项本机进度勾选已迁入其新计划，原始补丁可恢复，不推定其他版本的发布状态。

上海 HTTPS 证书由 Certbot 和专属定时器自动续签。线上校验和回退说明见 [证书维护回执](../../collaboration/deploy/2026-11-05-certificate-auto-renew.md)，代码及回执见 [PR #48](https://github.com/chending0418-ship-it/baseballMaster/pull/48)。
