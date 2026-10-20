# 3.0 发布验证

2026-10-13。当前 3.0（1）功能开发、生产服务、网站、签名归档和发布材料完成。以原始结果和本页证据为准，不把早期失败或环境跳过写成通过。验收按用户要求使用 iPhone 16 及更新机型正常界面的模拟器／浏览器，不增加旧小屏测试。

## 本轮最终结果

证据根目录：`output/validation/3.0/release-completion/`。

| 范围 | 实际结果 |
| --- | --- |
| App 单元／集成 | `regression-06.xcresult` 272 项全部通过；另 `full-matches-01.xcresult` 完整三模式比赛 1 项、`native-production-02.xcresult` 原生正式 HTTPS 1 项通过，共 **274 个唯一成功方法** |
| 原生界面 | `ui-release-01` 中 9 个成功方法，加最终 `final-flows-01` 历史权限 1 项、`ui-03` 管理收尾 1 项，共 **11 个唯一成功流程**；前批功能证据保留在 DEVELOPMENT.md |
| 新服务 | `server-12.log`，75 项全部通过，含权限、并发、旧写入者、恢复、轮换、档案和容量 |
| 原直播 | `live-final.log`，17 项全部通过 |
| 本地历史观看 | `watch-local-01.log`、`../match-collaboration/browser/results.json` |
| 正式服务 | `production/acceptance.json`，20 场完整样本、三模式正文校对、30 并发历史读取、公开／受限／撤销／关闭与安装落地页 |
| 原生 HTTPS | `native-production-02` 真实 Swift 客户端登记、发布比赛、逐球更新、同步、全队档案导出校验与收尾解散；没有用 JS 客户端代替此结论 |
| 持久性与灾备 | `production/restart-cleanup.json`、`restore-drill.json`；程序重启保留完整记录，独立 COS 恢复后删除与撤权不复活 |
| 官网 | 本地与公网合计 36 组页面／主题／正常视口；59 文件公网哈希匹配，内部路径拒绝与原直播健康通过 |
| 归档 | `archive-release-02.log` 成功；`final-archive.json` 核对 3.0／1、签名、隐私文件与 dSYM UUID |
| 材料 | 27 张商店 PNG 分辨率／RGB 校验；九张真实界面、海报及三页 PDF 已实际查看；PDF 第 3 页补充升级后创建管理员及导入本机比赛步骤，文本与页码检查见 `output/validation/3.0/upgrade-guide/`；商店 TXT 长度通过 |

`final-ledger.json` 保存通过方法到结果包的对应，不把重跑累计多算。全部界面使用 iPhone 17 Pro／iOS 26.1；正常手机网页 402／440px，加桌面 1440px；浅色／深色验证。生产验证只使用合成记录，没有读取其他用户比赛。

## 2026-10-13 名单权限与两管理员上限补充

证据目录：`output/validation/3.0/roster-permissions/`，本节优先于上方首次交付的 75 项服务测试与旧最终归档信息。

- `server-02.log`：77 项全部通过。记录员可新增／修改球员，同步可读、版本冲突不覆盖、稳定身份、幂等重试及降级后拒绝重放均有覆盖；球队资料、停用／恢复、成员管理及历史球员对应仍管理员专用。并发提升管理员最多两名，跨球队独立计数，降级后名额释放，最后管理员保护保留。
- `app-02.xcresult`：3 项名单集成回归通过，包含新增的记录员冲突草稿重开与明确核对后保存；此前唯一单元／集成方法 274 个，加本项为 275 个。没有把本次重跑的另外两项重复计数。
- `app-01.xcresult`：原生 UI 方法 `testV30RosterConflictRefreshPreservesTypedPlayerAndIdentity` 通过，实际以记录员添加、修改姓名和背号、冲突后保留输入；没有球队管理或停用入口。提升为第二名管理员后显示 `2 / 2`，第三名的设置管理员按钮禁用。三张原始界面已查看，无裁切或布局异常。
- 首轮服务回归发现历史球员对应复用了名单权限，已拆为管理员专用 `linkPlayers` 后全量通过。首轮新增 App 集成测试遗漏设备登记而失败，补齐测试准备后 `app-02` 通过；`app-01` 的单元失败保留，不将整个结果包标为通过。
- `production.json`：正式 HTTPS 合成验证通过，记录员增改、普通成员只读、管理权限拒绝、两名管理员上限与降级释放名额；合成球队已解散。上线前只检查管理员数量汇总，没有读取其他用户名单；无球队超过两名。
- `website/`：本地和正式网站共 36 组页面／视口／主题通过，首页／支持／隐私三文件与公网哈希一致。旧直播和协作健康正常。
- `archive.log`、`final-archive.json`：新归档通过，Version 3.0／Build 1，严格签名验证和隐私文件一致，App 与 dSYM UUID 为 `4B72E9AA-35F4-38F7-9165-A79057698EA6`。两页 PDF 重新渲染并实际查看；材料包重算逐文件校验。

## A01–A49 对应证据

下表“通过”指已列的 API／App／模拟器／浏览器测试范围。原规划中的实体设备、真实安装商店版本、蜂窝、微信容器、生产整机断电与长周期观测没有冒充已执行；限制见最后一节。

| 编号 | 验收结果及主要证据 |
| --- | --- |
| A01 | 通过：`server.test.mjs` 的同名独立建队、名单修订；App `testV30TeamClientCreateInviteJoinAndReadAcrossTwoDevices` |
| A02 | 通过：App `testV30TeamCredentialPersistsLocallyAndDoesNotSynchronize`；创建／恢复原生流程不使用 Apple 或注册账号 |
| A03 | 通过（模拟器／公网网页范围）：邀请预览、过期／撤销服务测试；HTTPS join/pair 下载与 Scheme 链接；真实商店安装返回未执行 |
| A04 | 通过：`server.test.mjs` invitation concurrent redemption／lost response；App 持久重试与落盘失败测试 |
| A05 | 通过：固定角色矩阵、member/scorer management 拒绝、archive 管理员限制及正式环境越权请求 |
| A06 | 通过：`security.test.mjs` pairing／device removal；`ui-release-01` 设备批准、接收和移除 |
| A07 | 通过：server、security、imports、player-links 各接口跨球队隔离；本机加密队列设备绑定 |
| A08 | 通过：事务内权限复核、撤权后的请求重试拒绝；App `testV30OfflineScoringThenForceTakeoverPreservesConflictAndRejectsOldWriter` |
| A09 | 通过：最后管理员保护、转交与降级、`lifecycle.test.mjs` 解散恢复；`ui-03` 明确确认 |
| A10 | 通过：security 恢复并发、丢响应、失败回滚、轮换；App `testV30ManagementRecoveryLostReplyRelaunchRetainsNewCodeAndIsolatesOldScoringDraft` |
| A11 | 通过：roster、player-links；App 三模式正式身份关联、停用与解除；`../player-links/ui-06.xcresult` |
| A12 | 通过：App `testV30PlayerLinksAcrossModesDevicesStatisticsAndReportsKeepOriginalMatch`；成员只读结果 UI |
| A13 | 通过：完整比赛独立 ID／版本同步测试；App 多场同步与年度不重复贡献 |
| A14 | 通过：games 单场写入者／代次，完整比赛双设备 App 联调 |
| A15 | 通过：`testV30FullMatchSyncHandoffAndReadOnlyScoringUseActualAppPayload`；`ui-release-01` 双方交接及继续记分 |
| A16 | 通过：games 两方交接取数摘要、丢响应重试、旧代次拒绝；App 持久队列续传 |
| A17 | 通过：force takeover、App 离线冲突保留；`ui-03` 管理员核对原草稿 |
| A18 | 通过：本机离线记分、发布前落盘、取权须在线；离线接管冲突测试 |
| A19 | 通过：操作 ID 同内容重试、改内容拒绝、陈旧版本、服务器期限；credentials 丢响应与轮换 |
| A20 | 通过：`drafts.test.mjs` 游标压缩；App `testV30ExpiredCursorRebuildKeepsDraftAndAllOfficialGames` |
| A21 | 通过：drafts 不可变原稿／补录须新正式修订／陈旧核对拒绝；App 冲突提交和 `ui-03` 完整核对 |
| A22 | 通过：272 项核心回归、完整三模式六局样本、共享历史更正和报告；正式 20 场完整包校验 |
| A23 | 通过：App 丢响应重启同操作、历史导入重复／冲突；只计正式最新修订 |
| A24 | 通过：`imports.test.mjs`；App 三模式导入、暂停续传、磁盘失败、重复来源；原生导入流程 |
| A25 | 通过：年度时区与改年、观赛排除、未关联单独保留、未知统计兼容；年度原生页面 |
| A26 | 通过：watch 只返回授权投影、跨队接口拒绝；本地和正式 WebKit 受限查看 |
| A27 | 通过：games 删除标记／恢复，maintenance 超期清理，旧导入不得复活；App 删除统计排除 |
| A28 | 通过（持久存储／模拟器范围）：旧备份核对、设备绑定、凭证缺失重新接入；未宣称所有 iOS 卸载／系统备份情形均真机执行 |
| A29 | 通过：关闭页面保留正文，成员离队／设备移除撤销绑定会话，解散关闭所有入口 |
| A30 | 通过：秘密位于 fragment／Keychain／加密队列，公开落地页无第三方请求；普通档案与日志不含权限秘密 |
| A31 | 通过（模拟器／WebKit范围）：深浅色关键页面、可访问标识、前台刷新、原记分布局；实体 Safari／微信和 VoiceOver 听读未执行 |
| A32 | 通过：核心旧备份／格式保护回归、协议拒绝未知版本、原直播 17 项、公开旧服务健康；不支持语义不写回 |
| A33 | 通过（服务重启／故障注入范围）：生产重启记录哈希一致；容量与保留磁盘阈值整次拒绝；未使共享生产主机整盘写满或断电 |
| A34 | 通过（隔离恢复范围）：备份／日志损坏、缺日志隔离、独立 COS 恢复删除与撤权；App 明确恢复核对；完整换机 RTO 未实测 |
| A35 | 通过：archive 一致性清单与完整 JSONL 校验；App 档案往返、损坏拒绝、权限不恢复；首次发布的暂停／向前修复预案见 OPERATIONS |
| A36 | 通过：限流、恢复猜测、日志脱敏、操作者快照与分页筛选；称呼不冒充实名验证 |
| A37 | 通过：games 可控时钟跨期限和重启持久归档、手动关闭；生产服务重启；不是等待七天的墙钟实测 |
| A38 | 通过：指定成员／队外申请确认、转发不授权、仅有昵称不可看；本地及正式浏览器 |
| A39 | 通过：逐人撤销、离队、设备移除和无会话拒绝；正式已打开页面被撤销后清空 |
| A40 | 通过：三种模式切换、旧会话失效、不开放保留；本地／公网 WebKit 与 `final-flows-01` |
| A41 | 通过：live 独立终场一小时／失效不回退到档案、默认历史关闭；旧本机导入明确确认；灾备旧链接全部关闭 |
| A42 | 通过：`testV30BackfillSurvivesPlayBallAndClockForAllModesAndObservation`、重启与跨年；赛前补填 UI |
| A43 | 通过：独立比赛时间／计时器、更正保留比分与终场；三模式 App 回归 |
| A44 | 通过：日期更正、取消／磁盘失败、排序与输出；年度按用户后来确认的规则随日期自动变化（原“不改变年度”已修正） |
| A45 | 通过：共享当前写入者限制、陈旧版本、撤权／冲突草稿；日期与历史权限独立 App 测试 |
| A46 | 通过：旧档日期回退、时区编码、备份与三模式历史导入、跨设备保留 |
| A47 | 通过：选人标签多背号／0／00／缺失兼容；原生双方阵容菜单，沿用球员 ID；辅助功能标签可读，未真机听读 |
| A48 | 通过：本轮练习提示显示／隐藏与投手界面原生流程；九名守备完整，不改变正式数据 |
| A49 | 通过：所有换投路径确认／取消／陈旧／磁盘失败；登板 0 球额度 1 局，统计 IP 0.0；两出局后上场额度 1 局而 IP 0.1，重返／跨局／继承跑者回归 |

## 修复和证据边界

本轮补齐管理员集中处理草稿、游标重建、审计查询、解散恢复、旧备份核对、独立灾备、凭证轮换和可迁移球队档案。修复了管理员角色提升后本机缓存权限未更新的问题。

初轮 App 回归因本地旧直播服务创建配额被测试耗尽而失败；仅提高本地验证启动器配额后，272 项全量通过，没有提高生产旧直播配额。历史权限 UI 原检查器在长列表顶部按钮滚出后没有向上滚动，`final-flows-01` 修正测试定位后通过。原生生产专用测试初次未传入显式环境标记而跳过，`native-production-02` 通过 xctestrun 环境启用并实际执行。网站检查器的输出文件作用域笔误修正后，本地与公网均通过。失败材料均保留，不删除来制造全绿。

实体设备无线网络、锁屏、商店安装回到邀请、微信浏览器、VoiceOver 听读、整台生产主机断电、实际 30 天备份增长、套餐带宽／账单未执行或未读取。正常开发功能、模拟器／公网验证和资料交接完成，这些限制不转为要求用户代做开发验收，也不写成已测。
