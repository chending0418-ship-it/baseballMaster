# 2.2 非直播 UI 开发验收

日期：2026-10-03。版本：2.2（Build 1）。分支：`codex/dev-2.2`。源码工作区：`/Users/JasonChan/Documents/BaseballMaster-2.2`。

B01–B04 与 E01–E07 已实现，当前非直播 UI 范围完成回归与截图检查。用户明确不做真机测试，由开发方完成模拟器测试。此记录保存当时非直播 UI 验收；随后用户已确认直播草图并开始正式开发，独立最新结果见 [直播 UI 验证](LIVE-UI-VALIDATION.md)。网页部署与审核事项仍单独记录。本轮没有归档上传、提交审核或部署新直播标题。

## 已实现的行为

- 手动终场后可返回比赛首页、切换页面、重新查看结果和恢复比赛；终场按钮改为紧凑的单行“返回首页”，右上角保留比赛结果。最近比赛卡片的空白区域可点击。
- 文字简版按历史局面显示实际投手与打席中换投顺序。教练投手显示“投手不适用”；缺失历史身份显示“未记录”。完整、单打席和批量导出共用报告生成逻辑。
- 直播网页首次 HTML 的标题、描述与 Open Graph 含双方队名；浏览器刷新时同步更新。特殊字符安全转义，不同比赛不串缓存，关闭／过期页面清除对阵名称。App 系统分享同时携带对阵文案与现有 URL。此改动尚未部署，之后随直播 UI 一起发布。
- 赛中双方阵容支持原生拖动排序，观赛双方赛前名单也支持拖动；保存后仍停留在编辑界面，并重新生成草稿。换投、代打、代跑、守备替补、守位及双换人成功后留在调整菜单，由用户主动返回比赛。未保存退出需选择保存或放弃。
- 首球前可多次互换先攻／后攻，对调队伍、打序、守备、投手、DH 和本队主客角色，保持 ID、计时与个人统计身份。每次保存可回放／撤销；首球事实独立于可撤销局面持久保存。逐球、击球、触身球等入口锁定；撤销、重启和备份恢复不能重新开放。旧进行中记录无法可靠判断时保守锁定，旧预约比赛正式开赛可初始化为零球。
- Box Score 预览／分享入口使用绿色；PDF 预览及系统分享正常。
- 统计可多选比赛、清空、全选和取消；汇总、排行、待确认数量、球员详情和 PDF 使用同一范围，比率从累计值重算。默认与自定义球队范围均排除旧版独立个人记录，旧记录仍在原个人入口保留。球员详情主动切换赛季时切换到该赛季数据；空选不误恢复为全部。

## 验证结果

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 单元与本地集成 | 170 项通过，0 失败，0 跳过；追加统计旧记录边界验证通过 | [闭环日志](../../../output/validation/2.2/feature-regression/final-regression-closed.log)、[统计边界日志](../../../output/validation/2.2/feature-regression/statistics-legacy-scope.log) |
| iOS 界面回归 | 65 个不同用例最终全部通过；覆盖 iPhone 17 Pro、16e、SE 3，iOS 26.1；追加赛季切换及筛选中文文案、勾选状态检查通过 | [结果汇总](../../../output/validation/2.2/feature-regression/final-summary.json)、[SE 小屏](../../../output/validation/2.2/feature-regression/se-small-regression.log)、[赛季切换](../../../output/validation/2.2/feature-regression/statistics-season-scope.log)、[筛选截图复查](../../../output/validation/2.2/feature-regression/statistics-selection-visual.log) |
| Release 真机目标编译 | 通过；未签名、未归档上传 | [构建日志](../../../output/validation/2.2/feature-regression/release-visual-handoff.log) |
| 2.1 → 2.2 覆盖安装 | 在模拟器安装已有 2.1 App、创建独立比赛并记球，再覆盖安装 2.2；全部业务字段和身份保持一致 | [升级比较](../../../output/validation/2.2/feature-regression/upgrade/upgrade-comparison.json) |
| 直播服务 | 14 项 HTTP 测试通过；首次标题、队名更新、转义、多比赛隔离、关闭与到期 | [服务日志](../../../output/validation/2.2/feature-regression/live-server-tests.log) |
| WebKit 浏览器 | 375px 浅色／深色、320px 窄屏、1440px 桌面共 4 组通过；标题、历史展开、更新、撤销、模式、终场与失效 | [浏览器日志](../../../output/validation/2.2/feature-regression/live-browser.log) |
| 直播模拟器公网 HTTPS | 开播、系统分享、模拟断网与补传、纠错、重启、终场、恢复及删除通过；测试使用合成比赛 | [16e 界面日志](../../../output/validation/2.2/feature-regression/final-small-ui-and-unit.log) |
| PDF | 短简版 1 页、150 次界外长简版 2 页、历史换投样张 1 页、所选两场球队报告 3 页；已渲染检查，无内容超出页面 | [报告检查](../../../output/validation/2.2/feature-regression/pdf-validation.json)、[样张](../../../output/validation/2.2/feature-regression/pdf-samples/) |

完整界面套件首次运行发现 7 项测试失败，涉及旧自动退出预期、默认空态／赛季文案兼容与直播异步状态等待。已保留默认文案、更新新的连续调整流程预期和异步等待，全部逐项复测通过；汇总逐个记录最终通过证据，不把首次失败的结果包改写为全绿。重复运行本地集成时曾触发生产默认的创建频率限制，最终使用独立高额度测试服务，默认生产限制不变，170 项全套重新通过。

覆盖安装比较对 Swift 字典的无序 JSON 编码做了规范化，仅排除编码排列差异，没有忽略比赛、统计、历史、人员或时间字段。原始前后快照与比较结果均保留。

## 关键截图

以下均来自实际模拟器测试附件，保留原始像素。

- [终场返回首页](../../../output/validation/2.2/key-pages/final-return-home.png)
- [首球前互换](../../../output/validation/2.2/key-pages/opening-swap.png)
- [长按拖动阵容](../../../output/validation/2.2/key-pages/lineup-drag.png)
- [保存后继续调整](../../../output/validation/2.2/key-pages/lineup-saved.png)
- [观赛双方赛前拖动](../../../output/validation/2.2/key-pages/observed-pregame-drag.png)
- [选择比赛](../../../output/validation/2.2/key-pages/statistics-selection.png)
- [两场比赛统计](../../../output/validation/2.2/key-pages/statistics-selected.png)
- [绿色 Box Score 导出入口](../../../output/validation/2.2/key-pages/export-box-score.png)
- [文字简版预览](../../../output/validation/2.2/key-pages/text-export-preview.png)

## 当前发布边界

直播 UI 没有提前改版。公网仍是 2.1 已部署网页，新增对阵标题的代码与本地验证已完成；部署、回滚与新公网页面验收归 R03，在直播 UI 完成后进行。微信客户端实际卡片缓存和真机蜂窝网络未实测，不将系统分享／WebKit／模拟断网结果当作这些渠道的验证。

本轮保留完整 Git 历史和旧工作区依赖，未提前删除旧项目。最终只保留 `BaseballMaster-2.2` 的迁移安排仍为 R05，在完整 2.2（含直播 UI）完成后执行。没有创建 2.3 项目或目录，也没有实现慢垒／小组件。
