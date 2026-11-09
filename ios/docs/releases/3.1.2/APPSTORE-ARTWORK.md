# iOS 3.1.2 商店图片

准备日期：2026-11-04。全部为本地评审素材，未上传。图片外侧沿用已确认品牌与深绿版式，画面内为实际 App 模拟器截图与合成比赛资料；不宣称物理设备拍摄或真机验证。图片不展示版本号。

## 已导出尺寸

| 目录 | 尺寸 px | 图片数 |
| --- | --- | --- |
| iphone-6.9 | 1320 × 2868 | 6 |
| iphone-6.3 | 1206 × 2622 | 6 |
| iphone-6.5 | 1242 × 2688 | 6 |
| duo-outer-landscape | 2034 × 1398 | 1 |
| duo-outer-portrait | 1398 × 2034 | 1 |
| duo-inner-landscape | 2853 × 2007 | 3 |
| duo-inner-portrait | 2007 × 2853 | 3 |
| ipad-12.9-landscape | 2732 × 2048 | 2 |
| ipad-12.9-portrait | 2048 × 2732 | 2 |
| ipad-13-landscape | 2752 × 2064 | 2 |
| ipad-13-portrait | 2064 × 2752 | 2 |

共 11 组、34 张 RGB PNG。每组是可选上传方案，横竖版不必同时上传。按 Connect 实际显示的设备栏目选择对应整组，勿把外屏和内屏尺寸混填。每个栏目最多十张的限制按实际 Connect 再核对。

原有三组 iPhone 尺寸保留；Duo 和 iPad 覆盖用户提供的全部截图尺寸。来源为 [Apple 截图规格](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)（2026-11-04 核对）。

## 取用与来源

- 输出：`output/releases/3.1.2/app-store/`；`index.html` 可浏览，`overview.png` 只供评审，设备子目录的 PNG 是截图候选。
- 唯一可编辑入口：[渲染器](../../../../scripts/release/render-appstore-3.1.2.cjs)、[内容与来源](../../../../docs/design/marketing/3.1.2/appstore/source/content.json)。源 PNG 纳入 Git，生成 HTML／PNG 留在 output。
- iPhone 六张：球场记分、两种方式、同场落点与传球、击球结果、批量名单、球员击球落点。前四张从本版最终 iPhone 回归 `.xcresult` 原始附件导出，后两张取自本版名单与统计验证；全部为 3.1.2 资料，不复用旧版球场。
- Duo：内屏工作台、页内 Pro、深色大字工作台；外屏 Pro。来自本版原生 display 1／3 取图，不把模拟器黑屏、系统桌面或错误显示附件作为宣传图。
- iPad：宽屏工作台、本机名单批量录入。工作台与名单为本版先前已验收批次；最终正式归档如改变可见 UI，提交前重新取图。
- 截图等比放入宣传画布；不裁掉 UI 来隐藏缺陷、不修改姓名比分按钮、不将另一平台界面套入当前设备。成图方向指宣传画布方向，内置截图不因此旋转或伪造另一姿态。

`manifest.json` 保存输出和源截图 SHA-256；`layout-checks.json` 保存文字边界、图片加载与重叠检查。本轮只提取既有验证附件并渲染图片，没有重新运行 App 测试。

## App 预览视频规格

本次用户请求为产品图片，未制作预览视频。App 预览是视频，不是 PNG：

| 设备 | 用户指定的预览视频尺寸 px |
| --- | --- |
| iPhone Duo | 1920 × 886 或 886 × 1920 |
| iPad 12.9／13 英寸 | 1600 × 1200 或 1200 × 1600 |

如果后续制作视频，应以真实操作录屏剪辑，并按 [Apple App 预览规格](https://developer.apple.com/help/app-store-connect/reference/app-information/app-preview-specifications/) 复核时长、编码与画面。不能把本次 PNG 更名为视频或上传到 App 预览栏。

## 提交边界

正式归档／上传前核对真实界面、截图设备栏、共享服务能力与隐私 URL。本轮不更改 App 版本／Build、设备声明、正式服务、商店配置；生成物不等于已提交审核。
