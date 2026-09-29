# 官网截图来源

采集日期：2026-09-28。当前工作区 2.0（1）源码通过 Xcode Debug 模拟器构建后，安装至独立的 `BaseballMaster Website 2.0` 模拟器（iPhone 17 Pro Max / iOS 26.1）。所有比赛、球员和统计均来自 App 内置的内存示例，不读取真实用户比赛资料。

以下 App 图片为原始 1320 × 2868 截图的 JPEG 版本，仅做图片编码压缩，无重绘、拼接或界面文字修改。官网缩略图由 CSS 展示，点击可查看大图。旧的 `scoring.jpg`、`statistics.jpg`、`box-score.jpg` 已移出公开目录，项目其他位置的原始商店素材保持不变。

| 文件（public/assets） | 当前 App 的采集入口 |
| --- | --- |
| home-2.0.jpg | `--home-with-games-preview` |
| scoring-2.0.jpg、limits-2.0.jpg | `--v111-limits-preview`，显示投手球数临近上限 |
| observation-2.0.jpg | `--outcome-preview` |
| coach-2.0.jpg | `--v11-coach-preview` |
| time-2.0.jpg | `--scorekeeping-timed-preview`，开始比赛、暂停并切换剩余时间 |
| appearance-report-2.0.jpg | `--boxscore-preview` → 记录 → 第 2 打席 → 导出本打席·详细版 |
| statistics-2.0.jpg | `--statistics-preview` |
| box-score-2.0.jpg | `--boxscore-preview` |
| rules-2.0.jpg | `--rules-preview` |

启动参数中的历史版本命名只是已有示例入口名称；截图运行的是此次新构建的 2.0 App。报告图为 App 当场生成的真实 PDF 预览。

构建命令：

```sh
xcodebuild -project BaseballMaster.xcodeproj -scheme BaseballMaster \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/BaseballMaster-website-2.0 CODE_SIGNING_ALLOWED=NO build
```

原始 PNG、构建日志、二进制 SHA-256、采集入口记录保存在 `output/website/baseballmaster-home/screenshots-2.0/`。这些开发记录不进入公开部署包。

## 2.1 文字直播预览

2026-09-28 从当前 `live/` 的真实观赛网页采集：

- `public/assets/live-desktop-preview.png`：960 × 1080，电脑观赛界面。
- `public/assets/live-mobile-preview.png`：390 × 960，手机观赛界面。

采集时通过 `createLiveServer` 启动仅绑定本机的临时内存服务，使用 `live/test/fixture.mjs` 内置合成比赛，展开当前打席的逐球记录。浏览器直接截图，无重绘或文字替换。示例包含比分、逐局得分、垒况、投打对阵、投球数及打席记录。

采集后已关闭临时服务；发布的仅是静态 PNG，未部署直播 API、开放观赛入口或启用 App 直播功能。官网与放大弹窗均标注开发版／2.1 预览。

原始截图、导航和页面预览、来源文件哈希、响应式检查记录位于 `output/website/baseballmaster-home/live-preview/`，不进入公开部署包。
