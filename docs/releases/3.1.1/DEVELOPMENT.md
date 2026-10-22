# 3.1.1 产品深绿开机图

2026-10-22。用户确认设计并授权接入：“这个可以，就用这版深绿，接入 3.1.1 的开机图。”工程版本为 **3.1.1（Build 1）**，Bundle ID 与工程名沿用原值。

## 实现

- 使用比赛记录大师深绿 **#092A2A**、原始 BM 图形的反白版、Arial BASEBALL 800／MASTER 400 与 −0.045em 字距；保留产品中文名称、低对比缝线与球场纹理，以及底部“每一球 都有迹可循”。
- `BaseballMaster/LaunchScreen.storyboard` 由系统显示，使用独立的全屏背景、等比例图文和底部短句三个 PNG 图层与 Auto Layout。图文不会被背景的等比例铺满裁切；底部按安全区留白。
- App 自有资源位于 `BaseballMaster/Assets.xcassets/Launch*`，包含 1x／2x／3x 分辨率和固定深绿色；构建不依赖公共品牌目录或本地 output。
- Debug／Release 均设置 `UILaunchStoryboardName = LaunchScreen`，撤除原空白自动启动页配置。
- 启动阶段状态栏使用浅色以适应深绿背景；进入 App 后继续由现有页面控制状态栏外观。
- 启动时间由系统和实际加载决定，没有增加等待时间或覆盖业务页面；比赛、存储、恢复、邀请链接与外观设置处理不变。

设计源稿与可复现导出方式见 [开机图设计](../../design/launch-screen/3.1.1/README.md)。

## 验证

本轮验证证据统一保存至 `output/validation/3.1.1/launch-screen/`，Xcode 26.1.1／iOS 26.1 模拟器。

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

## Git 与发布边界

在同一项目目录建立 `codex/dev-3.1.1`，沿用当前 `3eb09df` 工作区资料整理基线。原有未提交的 `3.1.0 发布功能计划.md` 修改保留，不计入本次开机图改动。main 继续按 PR 流程合入。

本次仅接入开机图并验证；未归档、未上传 App Store Connect、未提交审核，也未改动线上服务。
