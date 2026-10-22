# BaseballMaster 2.1 功能补齐与同步回归

日期：2026-09-28。范围：用户要求完成直播功能补齐、基于 2.0 的同步回归，并将顶部比分／局面改为紧凑设计。功能与本地验证已完成；部署和真机验收按用户要求留到后续。

## 完成内容

- 直播当前投打区域增加“第 X 棒 · 打者”。`GameState.currentBattingOrder` 同时供 App 记分页和直播投影使用，避免两处逻辑漂移；棒次不依赖球衣号码。
- 直播 v1 投影新增可选 `batterOrder`，App 写入整数或显式 `null`，服务端校验字段，网页兼容旧快照缺省。终场不显示当前棒次。后续须先更新服务端和网页再启用 App，见 [协议](../../../live/PROTOCOL.md)。
- 验证双方阵容调整、重排棒次、守位换投、打者／投手 A→B→A 再次上场、姓名／号码更正、撤销重做、三出局及触击换边。更正更新当前人物资料，既有逐球原文继续保留。
- 验证保存失败不会发布未保存内容，断网后的阵容更正可在重启后补传。全套既有记分、统计、备份、迁移和直播关闭用例继续通过。
- 单场分享结构为 `https://baseballmaster.cc/livestreaming/novideo/<24位唯一串码>`；本地联调也使用相同路径。
- 顶部分数、逐局表、B/S/O、垒况与投打信息收紧留白，投手球数与标签同排。375px 手机比分卡片约从 528px 缩至 317.5px（减少约 40%），首屏可看到前两条打席记录；桌面卡片约 352.3px。信息保留，无整页水平溢出。品牌与原有配色沿用。

## 本地验收结果

| 检查 | 最新结果 | 证据 |
| --- | --- | --- |
| App 全量单元 | 142/142 通过，无跳过；新增 5 项直播回归，其中 4 项通过真实 URLSession → 本地 Node 服务 → 观赛读取比对，另一项覆盖保存失败及断网重启 | `output/validation/2.1/earlier-regression/unit-and-live-final.xcresult`、同名 `.log` |
| App 开播／二维码／分享／关闭 UI | 通过，iPhone 17 Pro 模拟器 | 同上，`testLiveBroadcastStartShareAndCloseWithLocalService` |
| 未启用直播时的入口／隐私 UI | 通过，iPhone 17 Pro 模拟器 | `output/validation/2.1/earlier-regression/offline-ui-final.xcresult`、同名 `.log` |
| 服务端 | 13/13 通过；含棒次更新、撤销、旧快照兼容、非法字段拒绝，以及原鉴权／修订／删除／并发测试 | `npm test`；用例在 `live/test/server.test.mjs` |
| 本地完整冒烟 | 创建→读取→更新→撤销→终场→删除通过 | `LIVE_BASE=http://127.0.0.1:18088/livestreaming/novideo node live/demo.mjs --smoke` |
| 网页交互与视觉 | WebKit 320px 浅色、375px 浅／深色、1440px 桌面全部通过；棒次、资料更正、历史阅读提示、展开保留、撤销重做、教练模式、旧快照、终场及关闭 | `output/validation/2.1/earlier-regression/browser-compact-final/results.json`、同目录 PNG、`output/validation/2.1/earlier-regression/browser-compact-final.log` |
| 编译与差异 | Debug 模拟器构建通过；`git diff --check` 通过 | Xcode 日志与本地检查 |

App 的 142 项单元与开播 UI 来自同一结果包；该包另有一个过时的离线 UI 断言失败，随后单独修正并通过，不能将原结果包整体称为成功。上表按用例最新结果统计。

初次回归暴露的测试问题已关闭：教练模式坏球按既有规则不累计 B，测试原先误按普通模式期待 1；离线 UI 仍硬编码“版本 1.6（1）”，已改为直接等待并验证隐私说明；网页脚本曾重复删除已关闭会话，后续清理已修正；紧凑布局使桌面短比赛不足以滚动 400px，历史浏览测试改用更长的合成比赛。失败记录保留在 `output/validation/2.1/earlier-regression/`，均未以删除用例或跳过方式处理。

## 复跑方式

在独立终端启动仅使用内存数据的本地服务：

```sh
cd live
PORT=18088 LIVE_DB=:memory: node server.mjs
```

另一个终端从仓库根目录运行 `BaseballMasterTests`；新增 HTTP 用例需要上述服务，缺少服务会明确标记跳过。检查结果必须确认没有跳过。

```sh
xcodebuild -project BaseballMaster.xcodeproj -scheme BaseballMaster \
  -destination 'platform=iOS Simulator,id=7A06FC2B-4469-4BDA-981B-EF103169BAEB' \
  -only-testing:BaseballMasterTests CODE_SIGNING_ALLOWED=NO test
```

服务端在 `live/` 运行 `npm test`。网页验收在 `live/` 执行 `node test/viewer.browser.mjs`，需要 Playwright 与 WebKit；可用 `PLAYWRIGHT_MODULE` 指定已有 Playwright 包的绝对目录。浏览器检查只启动本地临时服务并使用合成数据，不连接生产域名。

## 后续边界

生产服务、Nginx 和官网未部署本轮修改；App 直播开关仍关闭，工程版本仍为 2.0（1）。真实手机 Safari／微信、公网弱网、锁屏／杀进程、一小时真实等待、旧版覆盖升级与发布资料更新仍按后续任务处理。本地 WebKit 和模拟器结果不替代这些验收。

## 同日追加：主客队打席编号修复

用户指出打席序号原先按两队共同累计。根因是 `plateAppearanceRecords()` 使用整场打席数组的 `offset + 1`；现改为客／主队各自累计，记录仍按比赛时间排列，跨局不重置。例如一局上客队 1、2、3，一局下主队 1、2、3，二局上客队接续 4。

- 结果页、TXT、详细版 PDF 的索引／页标题、文字简版 PDF 统一显示主客队及其打席编号。
- UI 自动化标识包含主／客队，避免两个“第 1 打席”冲突；业务导出仍使用打席 UUID 选择原记录。
- 编号为读取时生成的展示值，不修改数据库、事件 ID、原始过程、PA／AB 统计或棒次。旧比赛在升级后自动使用修正后的展示，无需数据迁移。
- 新增 3 项回归：分别累计至第四局（客队第 10 打席时棒次已回到第 1 棒）；重复换人、撤销重做及备份恢复后身份与编号一致；主客队同号时 TXT 与两种 PDF 单打席导出范围正确。
- 最新完整回归：**145/145 单元、3/3 结果与 PDF UI 通过，无失败、无跳过**。本轮继续运行本地直播服务，既有 HTTP 联调用例正常执行。证据为 `output/validation/2.1/earlier-regression/team-sequences.xcresult`、`.log` 和 `team-sequences-results.json`。
- 使用 App 测试实际输出的详细报告 5 页与文字简版 1 页进行逐页渲染检查，新增队别、索引和页标题无裁切或重叠。内部 QA 样本与渲染保存在 `output/validation/2.1/earlier-regression/team-sequence-qa/`。

此项为 2.1 待发布修复，部署与真机验收安排不变。
