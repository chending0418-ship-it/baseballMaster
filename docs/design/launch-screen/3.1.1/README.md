# 3.1.1 开机图概念设计

2026-10-22。用户已确认第二轮深绿设计，原话：“这个可以，就用这版深绿，接入 3.1.1 的开机图。”随后要求“在下面最好放一个 app 的 logo。这样完整一点。”当前第三轮在底部加入原有 App 图标。本目录保存设计源稿与 App 资源导出工具，原生接入和验证见 [3.1.1 开发记录](../../../releases/3.1.1/DEVELOPMENT.md)。

第一轮曾提供藏青与米白两种概念。用户随后明确提醒这是 App 产品，应注意产品主题色。当前采用**比赛记录大师深绿 #092A2A**，以反白 BM、固定英文组合、产品中文名称及低对比度棒球缝线／球场线条形成开机画面。第三轮底部使用现有球场／棒球 App 图标，44 × 44 pt、10 pt 圆角，与短句相距 14 pt，替换原小本垒装饰。图标取自同仓库 AppIcon.appiconset，完整保留原图形与配色。画面不显示版本号、进度条或交互按钮。

标识源自公共品牌规范 1.1 的 `brand/specifications/1.1/assets/parent-mark.png`，本目录保存逐字节相同副本 `source/assets/bm-approved.png`。图形使用原始透明轮廓；本次按用户的产品主题要求，以反白呈现在产品深绿底上。英文使用 Arial、BASEBALL 800 / MASTER 400、字距 −0.045em。本稿是产品开机图应用，公共母品牌藏青与 App 图标规范保持原有用途。

可编辑源稿为 `source/render.cjs`。在 App 根目录执行：

```sh
node docs/design/launch-screen/3.1.1/source/render.cjs
node docs/design/launch-screen/3.1.1/source/export-app-assets.cjs
```

依赖沿用 Codex bundled Node、Playwright 和 sharp，可用 `CODEX_ARTIFACT_NODE_MODULES` 指定包路径。当前生成结果位于 `output/samples/3.1.1/launch-screen/2026-10-22/v3/`，包括一张 1206 × 2622 产品深绿开机图、缩小预览、带设备外框的效果图及文字／布局／背景色／App 图标加载检查。第一、二轮结果保留在 v1、v2 供追溯。设备外框、灵动岛、手势横线只在设备效果图中出现。

画面由可编辑 HTML/CSS/SVG 与批准标识直接渲染，以保持 Logo、文字与色值准确；没有使用 AI 重绘品牌标识。导出工具将画面分成全屏背景、260 × 223 pt 图文组合和 180 × 74 pt 底部 App 图标／短句三个图层，写入 App 自有 Assets.xcassets，提供 1x／2x／3x PNG。产品深绿另存固定命名颜色。App 构建不依赖工作区公共品牌目录或被忽略的 output。

2026-10-23 用户进一步授权后台配置背景与文案，并明确只开发、不部署。系统 `LaunchScreen.storyboard` 保留静态深绿、固定 BM 与 App 图标，App 图标距底部安全区 81 pt；可变文案仅在 App 内开屏显示，避免先闪出旧文案。App 内开屏沿用既定比例和固定品牌位置，支持缓存背景、最多两行文案、0.3–2 秒展示及跳过，默认仍为本目录深绿设计。生成脚本同时提供独立 `LaunchAppIcon` 与后台预览资源。完整行为、接口、验证和迁移边界见 [可配置开屏](../../../releases/3.1.1/CONFIGURABLE-LAUNCH.md)。
