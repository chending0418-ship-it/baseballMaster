# BaseballMaster 2.1 · App Store 宣传图

2026-09-29，简体中文。沿用已确认的深绿品牌底、原始 App 图标、BASEBALL 粗体＋MASTER 常规字重及中文副标题。

## 七张顺序

1. 更新的现场记分页：包含历史纠错与直播入口。
2. 2.1 文字直播：手机网页观赛、比分、垒况、当前打者与打席过程。
3. 2.1 直播分享：实际 App 二维码和分享链接面板。
4. 2.1 四类记分纠错：换人、守位、局面、失误。
5. 2.1 更正影响预览：核对后续局面与统计，再保存。
6. 阵容调整：展示 2.0 已有的比赛中调整功能。
7. 数据统计／PDF 战报：按用户选择展示 2.0 已有功能，不宣称在 2.1 新增。

本机交付目录：`/Users/JasonChan/Documents/BaseballMaster/output/releases/2.1/app-store/`。生成图片不纳入 Git；下方文件名均相对此目录。

## 文件

- `iphone-6.9/`：7 张 1320 × 2868 PNG，主上传尺寸。
- `iphone-6.5/`：同一组 7 张 1242 × 2688 PNG，备用尺寸。
- `index.html`：可点击查看原图的总览。
- `七张宣传图总览.png`：仅供选图，不能作为 App Store 截图上传。
- `raw/`：未经内容修改的原始界面截图。
- `source/` 与各图片同名 HTML：可重新编辑文案与生成。
- `manifest.json`：尺寸、透明通道与来源验证记录。

尺寸依照 [Apple Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications) 核对。成品为无透明通道的 RGB PNG，主尺寸与备用尺寸分别导出；未上传 App Store Connect。

## 直播发布状态

当前生产文字直播开关关闭，公网直播服务尚未部署。02、03 以及包含直播入口的 01 是为 2.1 直播发布准备的素材，需完成公网部署、隐私申报、直播启用及真机验收后，与对应发布构建一起提交。其余图片对应已实现的本地功能。

界面来自真实运行的开发版与本地 HTTP 直播服务，使用合成球队和球员资料。分享截图的发布地址采用已配置的正式域名，二维码中的示例场次未在公网发布，不是可用观赛邀请。没有重绘或替换 App 内的界面内容。

## 重新生成

使用 Node.js、Playwright、Sharp 和本机 Chrome。设置 `CODEX_ARTIFACT_NODE_MODULES` 为依赖模块目录后，运行 `node source/render.cjs`。渲染器会检查图片完整加载、画布尺寸、透明通道和设备框底部边界。UI 原图通过 `BaseballMasterUITests` 的 AppStore／History 截图用例重新获取；网页图通过本地服务和 `source/capture-live.cjs` 获取。
