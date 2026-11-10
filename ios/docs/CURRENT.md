# 棒球比赛记录大师 · iOS 当前入口

更新：2026-11-10。本文件是 iOS 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 当前发布 | **用户于 2026-11-05 确认：iOS 3.1.2 已通过审核并上架**。本轮未读取 App Store Connect，不推定实际审核通过时间或线上二进制的源码提交／Build |
| 源码配置 | 候选工程为 3.1.5（Build 1），见 [BaseballMaster.xcodeproj](../../BaseballMaster.xcodeproj/) 和 [已合入的整合 PR #57](https://github.com/chending0418-ship-it/baseballMaster/pull/57) |
| 本版计划 | [3.1.2 发布功能计划](<plans/3.1.2 发布功能计划.md>)；[上版计划](<plans/3.1.1 发布功能计划.md>) |
| 后续版本 | [3.1.5 发布功能计划](<plans/3.1.5 发布功能计划.md>)。转播交接与重启串场已修复，待用模拟器配合 OBS 检查及部署。总 Admin 已上线语音开启码管理和发放备注，App 单局面录入已接入。主屏幕改名已核对，Duo 外屏图标文字会截断。单投手球数可直接输入，球数与登板局数到限只提醒。六项改动已整合。同一个模拟器构建的 iPhone、iPad 和 Duo 回归共 22 项通过。 开赛前与练习语音入口已补齐，首轮三端 21 项专项通过。本轮语音核对卡使用中文提示，跑垒事实齐全时给出一垒安打建议。同一构建三端共 27 项专项通过，见 [草稿 PR #62](https://github.com/chending0418-ship-it/baseballMaster/pull/62)。 |
| 3.1.5 TestFlight | **3.1.5（Build 2）已上传**。2026-11-10 13:08:18（北京时间）Xcode 回执显示上传成功，Apple 正在处理。归档来自 `42be86c`，包含本轮语音优化。Build 1 保留作对照。 |
| 3.1.5 语音重做 | 用户反馈识别和操作流程未达到现场要求，语音待重新验收。Build 1 保留为对照。第一轮实现复杂局面优先核对、跑者就地修正、Pro 落点可选补录、已描述路线复用及转写中断恢复。同一构建三端共 46 项专项通过，场边识别和速度仍待对照。见 [语音重做计划](<plans/3.1.5 发布功能计划.md#语音重做计划2026-11-10>)。 |
| 3.1.5 送审材料 | [审核文案、截图与提交清单](releases/3.1.5/APPSTORE-填写清单.md)已备妥，App 已增加录音前同意流程，公开隐私政策和审核专用开启码待完成。 |
| 发布资料 | [3.1.2 材料入口](releases/3.1.2/README-先看这里.md)、[正式归档证据](releases/3.1.2/ARCHIVE.md)、[开发与验收](releases/3.1.2/DEVELOPMENT.md)；其中按日期保留的“未上传／未上架”为历史阶段 |
| 服务状态 | 用户同日确认服务一切正常；本轮匿名只读检查球队协议 1 健康接口及直播健康接口通过，未修改或部署服务 |
| 共享服务维护 | [collaboration](../../collaboration/PROTOCOL.md)、[live](../../live/README.md)仍由 iOS／服务工作入口管理 |
| Admin 实时直播 | 「打开观赛页」支持临时直播与共享球队直播。验证与上海生产部署回执见 [PR #50](https://github.com/chending0418-ship-it/baseballMaster/pull/50) |
| Android | [Android CURRENT](../../android/docs/CURRENT.md)，独立版本与发布计划，不在此复制进度 |
| 当前维护 | [文档与契约阶段一](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)，[PR #49](https://github.com/chending0418-ship-it/baseballMaster/pull/49) 已合入，两个正式工作目录已采用新布局 |
| 共享资料 | [契约](../../contracts/README.md)、[设计与需求](../../docs/README.md)；[历史 3.1.0 计划](<plans/3.1.0 发布功能计划.md>) |

真实旧比赛原件核对等产品遗留继续按本版计划跟踪。上架不自动代表这些事项完成。3.1.5 的签名测试与分发状态见候选版本计划。

本次审查整改及采用边界：[旧 CURRENT 分流与两个工作目录同步步骤](../../docs/maintenance/2026-11-05-stage1-adoption.md)。采用后新增状态写入对应平台的现行入口和版本计划。

本机原有维护工作已保全并恢复：开屏上传修复仍属 [PR #47](https://github.com/chending0418-ship-it/baseballMaster/pull/47)，未部署；证书维护的已执行回执与源码属于 [PR #48](https://github.com/chending0418-ship-it/baseballMaster/pull/48)，保持原已部署／未合并的阶段边界。iOS 正式工作目录中，旧 3.1.0 计划的两项本机进度勾选已迁入其新计划，原始补丁可恢复，不推定其他版本的发布状态。

上海 HTTPS 证书由 Certbot 和专属定时器自动续签。线上校验和回退说明见 [证书维护回执](../../collaboration/deploy/2026-11-05-certificate-auto-renew.md)，代码及回执见 [PR #48](https://github.com/chending0418-ship-it/baseballMaster/pull/48)。
