# 2.3 商店截图与宣传图片

2026-10-08。沿用用户已确认的 App 图标、深绿色品牌底色、BASEBALL 粗体＋MASTER 常规字重及中文副标题。画面使用本版实际 App 与正式配套观赛网页，全部为合成比赛，不重绘界面。

| 编号 | 内容 |
| --- | --- |
| 01 | 成人慢垒：十人场图、Free、倒计时、实际投球与界外机会 |
| 02 | 双方阵容：示例队伍完整十二棒，含 Free、额外打者与替补 |
| 03 | 本场规则：初始球数、界外策略、时间赛与可选投手提醒 |
| 04 | Illegal：实际投出／未投罚球及裁判最终结果菜单 |
| 05 | 手机网页观赛：来自模拟器 App 实际上传的慢垒公开投影 |
| 06 | 历史纠错：既有四类入口，2.3 继续适配三种模式 |
| 07 | 完整比赛与战报：实际记录六个出局，两队投手各 1.0 局 |

- 主上传：`output/releases/2.3/app-store/iphone-6.9/`，七张 1320 × 2868 RGB PNG。
- 备用：`output/releases/2.3/app-store/iphone-6.5/`，七张 1242 × 2688 RGB PNG。
- 总览：同目录 `index.html` 与 `overview.png`，可点击预览，不作为商店设备截图上传。
- 原图与来源：同目录 `raw/`、`manifest.json`；2.3 原始 App 图来自 iPhone 17 Pro Max／iOS 26.1 当前构建的 XCTest 附件，网页从正式 HTTPS 采集。
- 传播图：`output/marketing/2.3/release-poster/baseballmaster-2.3-update.png`，1440 × 1920 RGB PNG；官网公开副本为 `website/public/assets/baseballmaster-2.3-update.png`。

尺寸与无透明通道经程序核对并作视觉检查，规格对照 [Apple 截图说明](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)。原图仅在界面外加品牌、标题和设备容器；官网 JPEG 只作等比例编码压缩。宣传图沿用既有可编辑 HTML 模板，没有重新设计品牌，也未用生成式图片改写实际界面。

工具：`website/scripts/render-appstore-2.3.cjs`、`render-release-2.3-poster.cjs`、`capture-release-2.3.mjs`。网页源投影由通过的 `testV23SlowProductionHTTPSAndSafariViewer` 保存；采集后删除临时公开场次，素材不含发布凭证。最终商店图已打入材料 ZIP，审核人员按文档现场创建自己的临时直播，不使用截图中的过期场次。
