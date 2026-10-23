# 3.1.1 开机图与可配置开屏

2026-10-22 用户确认深绿设计并授权接入；2026-10-23 进一步授权固定 BM／App 图标、后台配置背景和文案，并明确只开发、不部署。工程保持 **3.1.1（Build 1）**，Bundle ID 与工程名沿用原值。现行配置行为与接口见 [可配置开屏](CONFIGURABLE-LAUNCH.md)，下方保留此前静态版本的验证记录。

## 3.1.1 TODO

- [x] 接入深绿开机图，固定 BM 与底部 App 图标。
- [x] 完成后台配置背景和文案、App 缓存与兜底，并通过本地验证。
- [ ] **配合服务器迁移完成**（本版最后一项 TODO，2026-10-23 用户补充）：按既有迁移流程完成后的实际环境，配合验证原域名和链接、球队同步、邀请／配对及直播兼容；核对最终发布基线中可配置开屏的接口、素材、配置持久化与备份，并补齐迁移后联调和 3.1.1 交接记录。

迁移实施与阶段证据沿用既有迁移方案，状态从 [当前入口](../../CURRENT.md) 查看，不另建迁移执行清单。该待办须在实际迁移和相关回归完成后勾选；上海隔离准备及本地测试不算迁移完成。当前仍只开发、不部署，可配置开屏的服务器更新与迁移后线上联调等待后续授权。

## 实现

- 使用比赛记录大师深绿 **#092A2A**、原始 BM 图形的反白版、Arial BASEBALL 800／MASTER 400 与 −0.045em 字距；保留产品中文名称、低对比缝线与球场纹理，以及底部“每一球 都有迹可循”。
- `BaseballMaster/LaunchScreen.storyboard` 由系统显示，使用全屏背景、等比例图文和独立 App 图标三个 PNG 图层与 Auto Layout；可变短句在 App 内显示。图文不会被背景的等比例铺满裁切，底部按安全区留白。
- 用户后续要求在底部补充 App Logo，使用原有球场／棒球图标（44 × 44 pt、圆角 10 pt），图标下方 14 pt 放短句。最初静态稿整体为 180 × 74 pt；10 月 23 日起系统只使用固定图标，App 内独立显示可配置短句。版本保持 3.1.1（Build 1）。
- App 自有资源位于 `BaseballMaster/Assets.xcassets/Launch*`，包含 1x／2x／3x 分辨率和固定深绿色；构建不依赖公共品牌目录或本地 output。
- Debug／Release 均设置 `UILaunchStoryboardName = LaunchScreen`，撤除原空白自动启动页配置。
- 启动阶段状态栏使用浅色以适应深绿背景；进入 App 后继续由现有页面控制状态栏外观。
- 最初静态版本的启动时间由系统和实际加载决定；10 月 23 日按用户授权增加可跳过的 App 内开屏，默认 0.8 秒、最多 2 秒，网络不阻塞进入首页。比赛存储与恢复逻辑保留，邀请／配对链接立即退出开屏。

设计源稿与可复现导出方式见 [开机图设计](../../design/launch-screen/3.1.1/README.md)。

## 验证

验证证据统一保存至 `output/validation/3.1.1/launch-screen/`，Xcode 26.1.1／iOS 26.1 模拟器。下表为初次深绿启动页接入记录；底部 App 图标补充的增量验证另见后文。

| 检查 | 结果与证据 |
| --- | --- |
| Debug／Release 构建 | 均通过；`debug-final-build.log`、`release-final-build.log`，LaunchScreen 已编译和打包，版本为 3.1.1（1） |
| 既有关键界面回归 | 2 项通过、0 失败：首页本地空状态；新建比赛进入对手管理。`startup-smoke.log`、`startup-smoke.xcresult` |
| 大屏原生启动页 | iPhone 17 Pro，1206 × 2622；`iphone17pro-light-launch.png`、`iphone17pro-dark-launch.png` |
| 小屏原生启动页 | iPhone SE（第三代），750 × 1334；`iphonese-dark-launch.png`，图文及短句完整、比例正常 |
| 固定产品主题 | 大屏浅色与深色外观下，排除系统状态栏和手势条的内容区域像素完全相同；`validation-manifest.json` |
| 正常冷启动过渡 | Release 本地签名构建正常显示开机图并进入首页；`signed-normal-cold-launch.mov`、`signed-cold-contact-sheet.png`，大小屏首页另存 `*-release-home.png` |
| 设计与资源 | 1x／2x／3x 尺寸正确；参考尺寸分层合成相对批准稿最大通道差 1／255，仅透明抗锯齿舍入；`asset-verification.json` |
| 工程与源稿 | plist／工程格式检查、两份 Node 源稿语法检查、Git diff 空白检查通过 |

系统启动页截图使用模拟器的 `--wait-for-debugger` 暂停取证，正常启动视频不使用此参数。此暂停仅为验证手段，App 未加入等待代码。初次未签名模拟器构建被 SplashBoard 资源校验拒绝（-67056），其黑屏和视频证据按 unsigned 命名保留；对模拟器产物做本地 ad hoc 签名并重新安装后，原生启动截图及正常冷启动均通过。正式工程的自动签名配置未改动，此签名不属于发布归档。

### 底部 App 图标补充

按用户后续要求加入原有 App 图标，源稿输出为 v3，增量证据归入 `output/validation/3.1.1/launch-screen/app-icon-footer/`。图标直接使用原 AppIcon-1024.png，未改变其原图形、配色或 App 图标设置。

- Debug 与 Release 重新构建通过；底部图层 1x／2x／3x 均为 180 × 74 pt 对应像素尺寸。
- 分层合成相对 v3 设计最大通道差 1／255，App 图标来源 SHA-256 与当前工程原图一致；`asset-verification.json`。
- iPhone 17 Pro 浅色与 iPhone SE（第三代）深色的系统原生截图均确认底部 App 图标完整显示、留白正常，正常启动均进入首页；`iphone17pro-light-launch.png`、`iphonese-dark-launch.png`、`*-home.png` 与 `validation-manifest.json`。
- 原生截图及正常启动取证使用同一版本的本地签名模拟器构建；同版本开发中的旧启动页缓存通过重启专用测试模拟器刷新，不增加业务代码或强制清理用户资料的逻辑。
- 本次只更新图片、静态约束与源稿，未修改业务 Swift 代码；既有两项界面回归不重复运行。

### 可配置开屏本地开发（2026-10-23）

- 系统阶段只保留静态背景与固定品牌，不显示可变文案；App 内开屏支持背景、纯文本、启停、北京时间有效期与时长，固定 Logo 不随文案长度移动。
- 后台增加配置页、上传与效果预览；配置和受限数量的背景图保存在新增 SQLite 表中。旧业务权限、协议、资料导出和迁移脚本保留。
- 本地后端完整测试 **100 项通过**；App 缓存／校验／排期／断网／网络挂起等单元测试 **10 项通过**，默认／自定义开屏与旧首页流程 **3 项 UI 测试通过**。桌面与手机后台的登录、上传、发布、排期、启停、恢复默认和布局检查通过。
- Debug／Release 本地构建通过。真实回环 HTTP 服务已完成图片下载、SHA 校验、跨启动缓存和停服断网展示联调；大小屏分别检查固定品牌、两行文案及跳过入口。
- 证据：`output/validation/3.1.1/configurable-launch/`，包括 `backend-tests.log`、`app-final-tests.log`／xcresult、`browser-checks.json`、`native-http-cache.json`、后台与 App 截图。最终资源重建另见 `debug-packaging-build.log`、`release-packaging-build.log`。

## Git 与发布边界

在同一项目目录建立 `codex/dev-3.1.1`，沿用当前 `3eb09df` 工作区资料整理基线。原有未提交的 `3.1.0 发布功能计划.md` 修改保留，不计入本次开机图改动。main 继续按 PR 流程合入。

本功能仅本地开发与验证；未连接生产服务、未部署、未归档、未上传 App Store Connect 或提交审核。服务器迁移由用户既有流程独立进行，不将该流程的在途资料或 CURRENT 中的迁移记录纳入本功能提交。
