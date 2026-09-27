# BaseballMaster V1.1：01–06 实现与验证

日期：2026-09-23。开发分支：`BM_1.1_dev`。本次仅执行确认清单的 01–06，07–10 的直播、云部署及正式发布未执行。

## 已实施

| 范围 | 结果 |
| --- | --- |
| 01 数据基础 | 数据库 schema 3、备份格式 2（兼容读取 1）；当前球队可空；保留背号原文；打席 ID、事件关联、分段、单调修订号及发布队列基础字段持久化；完整记分和赛程开赛统一保存、失败回滚 |
| 02 球队名单 | 所有未结束比赛保护双方球队；最后一队可删除并保留历史访问；姓名可空，中文→英文→号码回退；区分 0／00、主号码排序、重号提示；首次 Demo 提示及合法空状态 |
| 03 现场布局 | 跑者使用金色、白边、号码标识；大屏优先一屏记分并隐藏底部导航，保留左上角返回；小屏移除整个场地图，用紧凑垒况条保留占垒信息 |
| 04 半局上限 | 默认关闭，启用默认 6 分，可设 1–99；按回本垒顺序计有效得分，结果页可调整多人得分顺序；本垒打归类保留、得分及打点封顶；继续本半局不再得分；手动结束半局及未完成打席分段 |
| 05 终场恢复 | 明显结束入口、原因和确认；免打下半局／再见／规定下半局结束先询问；恢复沿用比赛和打席身份，计时暂停；恢复后移出完赛汇总、再次结束只计一次；旧比赛恢复前核对局面 |
| 06 教练模式 | 默认 6 球，可设 1–20；坏球无四坏保送、触身继续且计球；真实三振与球数用尽区分，末球场内正常处理；禁用相应跑垒／投手判罚、DH 和投手负荷；P 位保留守备，投手统计不适用；模式筛选及 PDF／TXT |

小屏判定采用记分页可用宽度小于 390 pt 或可用高度小于 620 pt。页面保留比分、计时、投打双方、三垒占位、球数及操作按钮，不需要通过地图才能知道垒况。

## 升级数据保护

1. 打开旧版可写数据库前，复制完整 SQLite 文件族（含 WAL／SHM／外部附件）并逐文件校验；单进程首次启动时尚未挂载可写数据库。旧 JSON 和本地设置另存副本。备份失败即停止。
2. V1／V2 旧模型保留；仅在独立工作副本迁移。迁移前后按实体、原 ID、每个原有字段比较，名单及比赛载荷、逐球、统计、计时等原字段必须相同。
3. 回读完整快照再对照，保存校验清单与可恢复备份后，原子切换 `active-store-v3.json`。保留原库、校验和及迁移清单，不用清空数据库处理错误。
4. 打席身份补写统一保存；失败进入恢复页面，禁止正常编辑及自动备份。未知版本、损坏数据、缺失记录均不当成首次安装或补回 Demo。
5. 升级保留备份与日常最近三份备份分开管理，可导出。显式恢复前另存当前数据库，不静默回退丢弃升级后的记录。

**验证边界：**以下旧版测试使用工程保留的 V1／V2 模型及代表性夹具。尚未核对实际 App Store 已发布构建的数据来源，也未在装有旧版和真实数据的真机上执行覆盖安装、离线重启和实际杀进程测试。因此 D17 的完整故障矩阵、P02／P09 继续作为发布阻断项；模拟器通过不代表这项已完成。

## 自动化验证

- 单元测试 107 项，0 失败。最终结果：`/tmp/BM11-unit-final.xcresult`；[保留的测试摘要](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/unit-test-summary.json)。
- 覆盖既有计分、统计、报告和备份；新增空名单与 00 往返、双方删除保护、无本队观赛、打席 ID 撤销重做／重启、跨半局分段、代打身份、得分封顶、挤分打点、教练末球／真实三振、禁用规则、恢复统计，以及升级逐字段比较和各阶段故障重试。
- 升级故障测试在复制后、迁移后、校验后、切换前注入空间不足；另测打席补写失败以及记分事务保存失败。实际进程被系统终止的验证留在真机发布验收中。
- UI：iPhone SE（第 3 代）通过教练投手终场恢复、半局上限提示／继续／手动换边、满垒浅色／深色；iPhone 17 Pro 通过教练终场恢复、满垒浅色／深色、首页进入记分／隐藏导航／返回、空姓名与 00 编辑。
- 早先回归另通过未来赛程配置、记分核心操作、球队名单及统计分类。新增的旧备份恢复身份用例已通过；小屏从首页进入记分／隐藏导航／返回和统计分类回归均通过。

测试环境：Xcode 26.1 / iOS 26.1 模拟器。运行方式：

```sh
xcodebuild -project BaseballMaster.xcodeproj -scheme BaseballMaster \
  -destination 'platform=iOS Simulator,id=<simulator-id>' \
  -derivedDataPath /tmp/BM11Derived test \
  -only-testing:BaseballMasterTests CODE_SIGNING_ALLOWED=NO
```

## 发布前仍需完成

- 核对 App Store 当前销售版本和构建：确认清单同时出现 1.0 与 1.5 (3)，本次未擅自调整版本号。
- 在相同 App 身份的真机直接覆盖实际已发布版本，保留原数据，不卸载；对比全部数据、继续原比赛、重启、导出恢复，保存证据。
- 继续执行 07–10 的直播、部署及发布任务；本分支当前没有上线或送审。

## 截图

| 场景 | 实际截图 |
| --- | --- |
| 大屏从首页进入，左上角返回、底部导航隐藏 | [大屏记分](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/large-scoring-navigation.png) |
| 大屏满垒浅色／深色 | [浅色](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/large-bases-light.png) · [深色](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/large-bases-dark.png) |
| 大屏教练模式、二垒单跑者 | [教练记分](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/large-coach-second-base.png) |
| 小屏去掉场地图、满垒浅色／深色 | [浅色](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/small-bases-light.png) · [深色](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/small-bases-dark.png) |
| 小屏从首页进入，保留返回入口 | [小屏记分](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/small-scoring-navigation.png) |
| 小屏教练模式／二垒单跑者 | [教练记分](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/small-coach-second-base.png) · [深色](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/small-second-base-dark.png) |

除“从首页进入”的截图外，规则场景使用直达记分页的测试入口，因此这些截图没有上一级返回按钮；真实导航已另行验证。

## 最终检查记录

- `git diff --check` 通过。
- 最终单元测试：`/tmp/BM11-unit-final.xcresult`，107／107 通过。
- 小屏规则及布局：`/tmp/BM11-layout-small-final.xcresult`，3 项 UI 测试通过（含浅色／深色两种满垒截图）。
- 大屏规则及布局：`/tmp/BM11-layout-large.xcresult` 中教练恢复、满垒测试通过；该轮发现首页比赛卡片空白区域不能点入，已补齐整行点击区域。
- 大屏导航及号码编辑复验：`/tmp/BM11-navigation-final.xcresult`，2 项通过。
- 小屏导航及统计分类复验：`/tmp/BM11-complete-check.xcresult` 中 2 项 UI 测试通过；同轮旧备份新增测试因错误地对计时事件要求打席 ID 失败，修正断言为投球事件关联、计时事件独立后，最终 107 项单元测试全部通过。
- 当前 01–06 共 59 个任务，58 项已勾选；D17 的实际已发布格式核对与真机完整故障矩阵尚待发布阶段完成。没有把这部分标为已验收。

## 追加：现场投手用球数

按用户确认，在普通模式现场投手栏增加“已投 X 球”。直接读取当前投手本场持久化累计值，逐球、击球、触身、撤销／重做及换投后自动更新；不新增另一份计数。教练模式继续显示本打席球数，不给 P 位守备员显示投手用球数。

- 针对性数据测试：普通球员投球计数／换投／撤销重做／重启，以及教练模式不产生球员投手数据，2 项通过（`/tmp/BM11-pitch-count-check.xcresult`）。
- iPhone 17 Pro 和 iPhone SE（第 3 代）各通过 1 项完整 UI 验证：初始 0 球、投至 3 球、撤销／重做、换投为 0 球、投球增加、撤销换投恢复原球数；确认操作按钮在屏内、底部导航隐藏，教练模式不出现球员累计球数。
- 结果：`/tmp/BM11-live-pitches-large-final.xcresult`、`/tmp/BM11-live-pitches-small-final.xcresult`。
- 最新截图：[大屏用球数](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/large-pitch-count.png) · [小屏用球数](/Users/JasonChan/Documents/BaseballMaster/output/v1.1/qa/small-pitch-count.png)。
