# BaseballMaster iOS 原型

一个面向非专业家长记分员的中文棒球记分 SwiftUI 原型。当前版本使用内存模拟数据，重点验证低门槛的“记录 → 结果 → 统计”流程。

## 已实现

- 比赛首页、球队名单、添加球员、比赛设置和点选先发
- 逐球坏球/看振/挥空/界外记录，自动四坏、三振和三出局换边
- 完整球场视图，显示外野围墙、界线、内野、九名守备与垒上跑者
- 观察式击球录入：先选打者最终位置，再由系统建议安打、出局、失误或野手选择
- 触身球、故意保送、三振未接住、盗垒、跑垒出局、暴投/捕逸和投手犯规
- 无键盘现场修正，可恢复比分、局数、B/S/O、打者、投手和垒况
- 真实换投、代打、代跑和守位调整，支持撤销与中文比赛日志
- 按预设局数处理主队领先免打下半局、延长局和再见得分
- 动态逐局比分、打击/投手/守备 Box Score 和球员累计统计
- iPhone 小屏适配、深色模式、VoiceOver 标签和大点击区域
- SwiftUI Preview、单元测试和 UI 流程测试

## 运行

1. 使用 Xcode 26 或兼容版本打开 `BaseballMaster.xcodeproj`。
2. 选择任意 iPhone 模拟器。
3. 运行 `BaseballMaster` scheme。

命令行测试：

```sh
xcodebuild \
  -project BaseballMaster.xcodeproj \
  -scheme BaseballMaster \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  test
```

## 原型说明

- 最低部署版本为 iOS 16。
- 数据在关闭 App 后恢复为预置示例，不用于正式比赛存档。
- `MockGameStore` 和 `DemoGameState` 已按未来事件式记分引擎拆分，但当前不覆盖全部正式棒球规则。
- `--scorekeeping-preview`、`--boxscore-preview`、`--lineup-preview`、`--outcome-preview`、`--runner-preview` 启动参数可直接打开对应评审页面。

## 页面截图

- `Screenshots/01-home.png`
- `Screenshots/02-scorekeeping.png`
- `Screenshots/03-boxscore.png`
- `Screenshots/04-outcomes.png`
- `Screenshots/05-runners.png`
- `Screenshots/06-scorekeeping-compact.png`
- `Screenshots/07-home-dark.png`
- `Screenshots/08-scorekeeping-full-field.png`
- `Screenshots/09-scorekeeping-full-field-compact.png`
- `Screenshots/10-novice-scorekeeping.png`
- `Screenshots/11-observation-first.png`
