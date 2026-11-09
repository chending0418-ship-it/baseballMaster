# 棒球比赛记录大师 · iOS 当前入口

更新：2026-11-05。本文件是 iOS 唯一现行状态入口。

| 项目 | 当前依据 |
| --- | --- |
| 当前发布 | **用户于 2026-11-05 确认：iOS 3.1.2 已通过审核并上架**。本轮未读取 App Store Connect，不推定实际审核通过时间或线上二进制的源码提交／Build |
| 源码配置 | 仓库工程为 3.1.2（Build 1）；工程仍在根目录 [BaseballMaster.xcodeproj](../../BaseballMaster.xcodeproj/)，不因文档整理改变 |
| 本版计划 | [3.1.2 发布功能计划](<plans/3.1.2 发布功能计划.md>)；[上版计划](<plans/3.1.1 发布功能计划.md>) |
| 发布资料 | [3.1.2 材料入口](releases/3.1.2/README-先看这里.md)、[正式归档证据](releases/3.1.2/ARCHIVE.md)、[开发与验收](releases/3.1.2/DEVELOPMENT.md)；其中按日期保留的“未上传／未上架”为历史阶段 |
| 服务状态 | 用户同日确认服务一切正常；本轮匿名只读检查球队协议 1 健康接口及直播健康接口通过，未修改或部署服务 |
| 共享服务维护 | [collaboration](../../collaboration/PROTOCOL.md)、[live](../../live/README.md)仍由 iOS／服务工作入口管理 |
| Admin 实时直播 | 「打开观赛页」支持临时直播与共享球队直播。验证与上海生产部署回执见 [PR #50](https://github.com/chending0418-ship-it/baseballMaster/pull/50) |
| OBS 转播叠加 | 共享球队页领取四条固定源。开赛同步、同队多场手动切换，本地试用。见 [OBS 本地试用](../../live/OBS.md) |
| Android | [Android CURRENT](../../android/docs/CURRENT.md)，独立版本与发布计划，不在此复制进度 |
| 当前维护 | [文档与契约阶段一](../../docs/maintenance/2026-11-05-docs-contracts-stage1.md)，[PR #49](https://github.com/chending0418-ship-it/baseballMaster/pull/49)，尚未合入 main；两个正式工作目录已从核准提交 `d6ba7b6` 在本机采用新布局 |
| 共享资料 | [契约](../../contracts/README.md)、[设计与需求](../../docs/README.md)；[历史 3.1.0 计划](<plans/3.1.0 发布功能计划.md>) |

真实旧比赛原件核对等产品遗留继续按本版计划跟踪。上架不自动代表这些事项完成。本轮不重新签名或生成分发包。

本次审查整改及采用边界：[旧 CURRENT 分流与两个工作目录同步步骤](../../docs/maintenance/2026-11-05-stage1-adoption.md)。本机采用已执行，远端 PR 仍未合并；归档快照不接收旧分支新增状态。

本机原有维护工作已保全并恢复：开屏上传修复仍属 [PR #47](https://github.com/chending0418-ship-it/baseballMaster/pull/47)，未部署；证书维护的已执行回执与源码属于 [PR #48](https://github.com/chending0418-ship-it/baseballMaster/pull/48)，保持原已部署／未合并的阶段边界。iOS 正式工作目录中，旧 3.1.0 计划的两项本机进度勾选已迁入其新计划，原始补丁可恢复，不推定其他版本的发布状态。

上海 HTTPS 证书由 Certbot 和专属定时器自动续签。线上校验和回退说明见 [证书维护回执](../../collaboration/deploy/2026-11-05-certificate-auto-renew.md)，代码及回执见 [PR #48](https://github.com/chending0418-ship-it/baseballMaster/pull/48)。
