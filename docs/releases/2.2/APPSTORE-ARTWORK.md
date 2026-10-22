# 2.2 预览与截图

2026-10-03。沿用已确认品牌图标、深绿底色、BASEBALL 粗体＋MASTER 常规字重及中文副标题。七张图片均使用 2.2 实际 App 或配套观赛网页，合成球队／球员，不重绘 App 界面。

1. 现场记分：比分、球场、跑者和逐球操作。
2. 文字直播：部署后的紧凑记分牌、规则用时、投打对决与打席实况，明确标注手机网页观赛。
3. 直播阵容：部署后的双方当前打序、号码及守位。
4. 阵容调整：长按拖动入口及“已保存，可继续调整”状态。
5. 指定比赛统计：勾选参与统计的比赛。
6. 历史纠错：继承既有四类纠错，未宣称本版新增。
7. 数据与战报：本版绿色 Box Score 导出入口与报告列表。

目录：`output/releases/2.2/app-store/`。

| 文件 | 用途 |
| --- | --- |
| `iphone-6.9/` | 七张 1320 × 2868 RGB PNG，主上传尺寸 |
| `iphone-6.5/` | 七张 1242 × 2688 RGB PNG，备用尺寸 |
| `index.html`、`overview.png` | 可点击总览／选图预览；不作为设备截图上传 |
| `raw/` | 未修改内容的实际截图及来源记录 |
| `manifest.json` | 十四张图片的尺寸、无透明通道校验与 SHA-256 |

已核对 [Apple 截图规格](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)。两套文件应放入各自尺寸栏目。未自动上传商店，未生成视频。

App 原图来自 iPhone 17 Pro Max／iOS 26.1 当前构建，测试结果在 `output/validation/2.2/release-preparation/screenshots.xcresult` 与 `public-app.xcresult`，附件按 manifest 追溯。后者未签名公网凭证用例失败，但独立 Box Score 用例通过，截图来自该通过用例；正常签名公网重测已通过 `public-app-signed.xcresult`，详见发布交接。用例中的历史命名 AppStore21 不代表截图使用旧二进制。

网页原图由 `website/scripts/capture-release-2.2.mjs` 在正式 HTTPS 上采集，440 × 956 CSS px、3 倍比例，使用短暂合成比赛，拍摄后立即删除。没有永久有效二维码或观赛邀请。采集时显式设置 `LIVE_BASE` 和 `PLAYWRIGHT_MODULE`。

渲染：配置 `CODEX_ARTIFACT_NODE_MODULES` 后执行 `node website/scripts/render-appstore-2.2.cjs`。只在图片外加入品牌、标题和设备容器，以 HTML/CSS 生成成品，保留原图。五张官网轻量截图同时从这些原图渲染。2.1 的原始十四张素材继续保留。
