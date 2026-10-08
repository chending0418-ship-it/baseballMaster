# 2.3 模拟器验收

2026-10-08。用户明确以模拟器验收替代真机验收，由开发方彻底完成，Apple 上传／审核／发布交由用户。

最终单元／集成 209 项通过、0 失败、0 跳过；72 个唯一界面方法均有最后通过记录。初次全量 206＋71 个方法中有 8 个界面定位／流程失败，已逐项适配新开赛确认、页面名称、可滚动控件及唯一阵容标识后复跑通过，不把失败包当成全量成功。新增慢垒公网方法及专项计时、观赛／未来、十二棒状态用例已计入最终方法数，重跑次数不重复累加。

设备：iPhone 17 Pro、17 Pro Max、16e 与 SE 第三代／iOS 26.1。16e 与 SE 各补跑 3 项小屏、深色、大字体、长姓名／阵容和提醒用例，均通过。这里没有声称实体设备、蜂窝射频或微信客户端测试。

| 场景 | 内容 | 本轮验收范围 | 证据／用例提示 |
| --- | --- | --- | --- |
| A01 | 新建／本队／观赛／未来慢垒 | 即时与未来入口、未补齐规则的保存／拒绝开赛 | ObservedSlowImmediateAndScheduledDraft；NewOwnSlowGame |
| A02 | 默认一好一坏与合法自定义 | 实际投球与每个新打席初始值 | SlowPitchInitialCounts；ImmediateFoulAndInitialTwoStrikes |
| A03 | 双方独立且不同长度打序 | 十二棒／十一棒、保存、重开和循环 | BothScheduledLineups；DifferentOrdersFreeAndTimeGame |
| A04 | 十人守备与自由人 | 完整场图、守位、责任与两位数记谱 | FreeTwoDigitNotation；ExtraHitterRotatesIntoDefense |
| A05 | 虚拟球数与保送／三振 | 虚拟值不计实际投球、结算一次 | SlowPitchInitialCounts；IllegalPenaltySwingFairBall |
| A06 | 两种界外策略与界外接杀 | 额外机会、界外终结与实际出局 | SlowPitchImmediateFoul；FoulGraceSurvivesBallUndoBackup |
| A07 | 第三出局与打席中断 | 正常、跑者、未接住第三好球、提前换边 | FinalRunnerOut；FinalDroppedStrikeOut；ManualHalfEnd |
| A08 | 打席中换人和责任 | 十二棒代打／换投保留球数与机会 | TwelfthHitterReplacementAndPitchingChange |
| A09 | 暂停、恢复、重启、撤销重做 | 磁盘和 Keychain 恢复、规则与时间保留 | SlowRestart；LiveProductionHTTPSScoringCorrectionShareRelaunch |
| A10 | 球数与历史更正 | 影响预览、冲突与更正保存 | SlowHistoryReplay；HistoryCorrectionDraftPreview |
| A11 | 得分上限、换边、延长与 TB | 三模式既有规则回归、时间赛不自动终场 | RunLimitPrompt；DifferentOrdersFreeAndTimeGame；Tiebreak |
| A12 | 不适用快捷操作 | 慢垒触击／盗垒／未接住第三好球入口限制 | SlowModeGuards 与相关三模式用例 |
| A13 | 分模式和分比赛统计 | 真实投手统计、7 局 ERA 口径 | SelectedGamesMatchTeamPlayerAndPDF；SlowPitch 投手汇总 |
| A14 | 报告、小屏、深色与长打序 | PDF／TXT、分页、字号与十四张商店图 | FreeTwoDigitNotation；SmallDarkSlowScoring；报告 UI |
| A15 | 旧库、旧备份、新备份及降级拒绝 | 实际 2.2→2.3 覆盖安装、恢复、旧 App 保护原数据 | upgrade/validation.json；LocalBackup 回归 |
| A16 | 三模式直播与同步 | 本地及公网、旧链接、断网、历史修订、删除 | SlowLiveHTTP；SlowProductionHTTPS；两类 WebKit 回归 |
| A17 | 普通棒球与教练投手 | 完整既有单元和界面回归 | 全量单元／72 个界面场景 |
| A18 | 赛前增减、重排与沿用阵容 | 创建双方、长打序、保存／复开、撤销配置同步 | PregameSlowOrderCanGrowAndShrink；NewOwnSlowGame |
| A19 | 额外打者进入守备 | 十人场上、棒次和球数不变 | ExtraHitterRotatesIntoDefenseWithoutChangingOrderOrCount |
| A20 | 第十一／十二棒替换 | 十二棒代打、换投与备份后的状态保持 | TwelfthHitterReplacementAndPitchingChange |
| A21 | 界外机会起算、继承、重置 | 到达两好不消耗、换人继承、新打席重置 | FoulGrace；InitialTwoStrikes；TwelfthHitterReplacement |
| A22 | 时间、到时和可选上限 | 到时确认、无局数上限、默认剩余与用户显示选择 | TimedExpiration；TimeGameStartsWithRemainingTime；OptionalInningsLimit |
| A23 | 界外机会的恢复与纠错 | 撤销、重做、磁盘、备份和历史重放 | FoulGraceSurvivesBallUndoBackup；SlowHistoryReplay |
| A24 | Illegal 坏球与未投罚球 | 强迫进垒、保送一次、未投出不虚增 | IllegalBallForcesOnlyOnWalk；IllegalPenaltySwingFairBall |
| A25 | Illegal 挥棒、界外、击球、No Pitch | 裁判最终结果一次结算 | IllegalPenaltySwingFairBallAndNoPitch |
| A26 | Illegal 更改、恢复、报告、直播 | 宣告和结果保留、改判重放、同一修订一致 | SlowHistoryReplay；FreeTwoDigitNotation；SlowLiveHTTP |

所有用例完整名称与最后通过日志在 `output/validation/2.3/release-acceptance/final-ledger/tests.json`，而非将此表简称当成 XCTest 筛选名。最终单元结果在 `entry-matrix-verified/`；界面汇总含 `full/`、`ui-flow-fixed/`、`public-app/`、`final-ui-closure/` 与 `se-final/` 等。初始失败与检查器修正均保留。

服务端 17 项通过，本地 WebKit 4 组既有＋4 组慢垒通过，公网同样 8 组通过。正常签名模拟器的 HTTPS 公开投影、Safari 阵容、实际球数、额外界外机会、断网补传、草稿不上传、更正、重启、终场／恢复和关闭删除通过。只使用自建合成场次并在完成后删除，未读取或备份其他用户直播资料。

官网三页分别在 320、390、768、1440 px 做本地／公网检查，24 组通过；44 个公开文件逐字节匹配源码，HTTPS、HTTP 跳转、内部路径阻断与直播健康通过。发布包不包含内部文档、数据库或凭证。

验收抓到时间赛首次 Play Ball 重置为已进行显示的遗漏，已修复为默认剩余时间，同时验证暂停／继续保留用户选择。输入时长增加数值键盘“完成”，两队阵容行按球队／球员使用唯一无障碍标识。部分 iOS 26 工具栏自动点击返回无效命中点，最终从真实按钮中心操作并核对结果页面，没有绕过业务流程或改写截图。

旧版覆盖安装、继续记分、备份恢复和回到新版检查使用实际已编译的 2.2／2.3 App。旧版拒绝新资料的保护页面有原图和 OCR；重新安装 2.3 后全部逻辑状态一致，UUID 键字典按映射比较，避免把 JSON 成对数组的非语义顺序变化当作资料丢失。
