# BaseballMaster 3.0 App Store 创意素材

2026-10-13。标题与搜索结果创意素材共五张，已按原尺寸导出并检查。**本次交付为简体中文静态 PNG，尚未上传 App Store Connect 或提交 Apple 审核。** 原有九张设备截图继续使用；创意素材在独立栏目配置。

## 选择哪张图

建议先使用宽幅标题 `header-3840x1646.png` 和标准搜索图 `search-3840x2560.png`。其余三张提供相同比例用途的备选尺寸，不必把五张全部安排在页面上。

| 用途 | 文件名 | 像素尺寸 |
| --- | --- | --- |
| 标题，宽幅 | header-3840x1646.png | 3840 × 1646 |
| 标题，通用比例 | header-5244x2950.png | 5244 × 2950 |
| 搜索结果，标准比例 | search-3840x2560.png | 3840 × 2560 |
| 搜索结果，较小文件 | search-1920x1280.png | 1920 × 1280 |
| 搜索结果，通用比例 | search-5244x2950.png | 5244 × 2950 |

尺寸与格式依据 [Apple 创意素材规格](https://developer.apple.com/help/app-store-connect/reference/app-information/creative-assets-specifications)。全部为 8 位 RGB、sRGB 色彩配置、不含透明通道的 PNG。1920 尺寸是商店素材规格，与 App 的机型适配范围无关。

## 设计与裁切

标题突出「同一支球队，一起记，一起看」，配合实际球队共享页面；搜索图突出「棒球记分／球队共享」及「逐球记录 · 年度统计」，配合实际现场记分页面。沿用已确认的球场图标、英文标识、中文副标题与深绿品牌色。示例球队和比赛为合成资料，界面取自 3.0 运行截图，未绘制虚构功能。

关键文字、品牌和界面均放在 Apple Photoshop 模板标出的安全区内，周围用球场图形延展。因此完整画布留有较大空间，尤其是 5244 × 2950 通用版本。`preview/` 同时提供完整画布和安全区主体预览；后者只是检查主体的裁切示意，并非 App Store 实际显示截图，也不能作为上传原图。

设计原则及模板来自 [Apple 素材最佳实践](https://developer.apple.com/app-store/asset-best-practices/)。实际页面以 Connect 的 Preview 为准，上传后核对其标题、搜索结果与语言预览。

## 上传步骤

1. 在 App Store Connect 打开 BaseballMaster，选择 iOS 3.0 版本和简体中文。
2. 在 Product Page Information（产品页面信息）打开 Header and Search Results（标题与搜索结果）。在 Header 中放入标题原图，在 Search Results 中放入搜索结果原图，保存。
3. 选择 Preview，确认文字、图标和关键界面完整。使用本次两张独立设计时，分别选择文件。
4. 与 3.0 版本一起提交审核。也可以先在 Asset Library（素材库）点击加号，选择 Upload Creative Assets 上传，再从对应位置选择 Browse Assets。

入口与流程依据 [Apple 管理 App Store 素材说明](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-your-app-store-assets)。本次共享功能属于 3.0，建议随 3.0 版本提交，避免在旧版本页面提前展示。是否获批及最终呈现由 Apple 审核和商店处理决定。

## 材料位置

- 独立素材包：`BaseballMaster-3.0-AppStore-Creative.zip`，内含五张原图、预览、可编辑 HTML/CSS/SVG 排版及原始界面图、说明和校验清单。
- 独立包的上传目录为 `upload/header/` 和 `upload/search/`；打开 `index.html` 查看五种尺寸及安全区主体。
- 完整上线材料包 `BaseballMaster-3.0-AppStore.zip` 中，五张原图位于 `creative-assets/upload/`，同时包含本说明和素材清单。
- 项目内生成目录：`output/marketing/3.0/app-store-creative/`。
- 可重复生成脚本：`website/scripts/render-creative-assets-3.0.cjs`；打包脚本：`website/scripts/package-creative-assets-3.0.py`。
- 本地规格、布局与 ZIP 校验：`output/validation/3.0/creative-assets/`。Apple 原始 PSD 模板只存于该目录的 `references/`，不重复装入交付 ZIP。

`upload/` 才是商店上传用图。不要上传预览图、安全区裁切、素材总览、HTML 或校验 JSON。此批素材无需重新构建 App 或部署网站。
