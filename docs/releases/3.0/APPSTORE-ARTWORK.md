# 3.0 截图与宣传图

使用 3.0 实际运行界面，示例均为合成球队／比赛；没有制作虚构 App 界面。记分页保持现有布局，击球图与常用局面改版不在本版。

材料包内 `screenshots/` 有三套各九张 RGB PNG：

| 目录 | 分辨率 | 用法 |
| --- | --- | --- |
| iphone-6.3 | 1206 × 2622 | Connect 的 6.1／6.3 英寸对应栏目 |
| iphone-6.9 | 1320 × 2868 | 6.9 英寸栏目 |
| iphone-6.5 | 1242 × 2688 | Connect 仍显示此栏目时可用 |

顺序：现场记分、球队共享、记录交接、历史回看、年度统计、历史比赛导入、设备配对、更正比赛时间、阵容选人背号。每栏按 01–09 上传，不混用尺寸。提供 6.5 格式仅为了商店栏目兼容，不代表新增小屏开发或验收。

`overview.png` 是九张截图总览，`baseballmaster-3.0-update.png` 是 1440×1920 宣传海报，不用于设备截图栏目。`source-screenshots/` 为原始 App 截图，`manifest.json` 记录尺寸与 SHA-256；不需要上传 HTML 源文件或原始测试包。

按 [Apple 截图规格](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications) 准备。未制作 App Preview 视频。iPad、Duo、Android 和小组件不写入本次材料。

## 标题与搜索结果创意素材

新增两张标题图（5244×2950、3840×1646）及三张搜索结果图（5244×2950、3840×2560、1920×1280），均为无透明通道的 sRGB PNG。它们用于 Connect 的「标题与搜索结果」创意素材栏目，原有九张设备截图继续使用。

完整材料包的 `creative-assets/upload/` 包含原图，独立包 `BaseballMaster-3.0-AppStore-Creative.zip` 另含预览和可编辑源文件。推荐尺寸、裁切说明与上传步骤见 [CREATIVE-ASSETS.md](CREATIVE-ASSETS.md)。
