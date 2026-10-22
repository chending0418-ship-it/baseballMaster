# 目录与命名约定

2026-09-29 整理。适用于 `/Users/JasonChan/Documents/BaseballMaster/`。

## 根目录保留什么

```text
BaseballMaster/
├── 2.1 发布功能计划.md       日常补充需求与发布计划
├── README.md                项目入口
├── TODO.md                  开发与发布状态
├── AGENTS.md                协作约定
├── BaseballMaster.xcodeproj/ Xcode 工程
├── BaseballMaster/          App 源码和资源
├── BaseballMasterTests/     单元与集成测试
├── BaseballMasterUITests/   界面测试
├── live/                    文字直播服务与网页源码
├── website/                 官网源码及部署脚本
├── docs/                    功能、发布、品牌与历史文档
└── output/                  本机生成文件
```

App 源码目录、工程名、资源引用、Bundle ID、品牌名称及 Git 工作区保持既有结构。目录整理不调整业务功能。

## 文档规则

- 根目录的中文版本计划保持易找、易编辑；新增想法写到计划顶部的“随手补充区”。
- 当前功能和发布文档放 `docs/releases/<版本>/`，常用技术文档采用清楚的英文文件名，如 `CORRECTION-TODO.md`、`RELEASE-HANDOFF.md`。
- 已被新方案替代的草案、历史需求与记录放 `docs/archive/plans/<版本>/`，不与当前待办混放。
- 旧截图放 `docs/archive/screenshots/`；当前交付截图放相应版本的发布目录。
- 链接优先使用相对路径；重命名后同步更新引用。链接到另一个本机工作区时须明确标注。

## 生成文件规则

具体入口见 [output 目录说明](../output/README.md)。根目录不再新建 `DerivedData/`、`tmp/`、`Screenshots/` 或 `update/`。

| 内容 | 位置 |
| --- | --- |
| Xcode 派生文件 | `output/cache/xcode/<版本>-<配置>/` |
| 测试日志与结果包 | `output/validation/<版本>/<任务>/` |
| App Store 截图和压缩包 | `output/releases/<版本>/app-store/` |
| App 发布归档 | `output/releases/<版本>/archives/` |
| 官网部署包 | `output/deployments/website/` |
| 社交平台宣传素材 | `output/marketing/<渠道>/<日期>/` |
| 展示 PDF 和海报 | `output/samples/` |
| 源码检查点、整理前副本 | `output/backups/` |

目录采用小写英文加连字符，版本段统一为 `2.1`、`2.2`，日期统一为 `YYYY-MM-DD`。中文宣传标题和已经成套编号的截图不为形式统一而重复改名。`.xcarchive`、`.xcresult` 作为完整包移动，不拆分内部文件。

## 保留与清理

发布归档、测试证据、源码备份和交付图片分别保留。缓存可以按需重建，但缓存中的旧测试日志若仍用于验收，应先归档再删除。不能因某份结果是失败尝试就直接抹去验证历史。

Git 元数据与已有分支不参与文件整理。新生成文件由 `.gitignore` 排除，已有跟踪文件迁移后继续受版本控制。原始日志、结果包、归档中的旧路径不改写，使用维护记录的迁移表追溯。

## 常用操作

以下命令在项目根目录执行；继续使用 Xcode 打开 `BaseballMaster.xcodeproj` 的方式不变。

```sh
# 本机模拟器 Debug 编译，派生文件统一放入缓存目录
xcodebuild -project BaseballMaster.xcodeproj -scheme BaseballMaster \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath output/cache/xcode/2.1-debug \
  CODE_SIGNING_ALLOWED=NO build

# 官网打包，自动输出到 output/deployments/website/
python3 website/scripts/package.py

# App Store 图片重生成；先配置 CODEX_ARTIFACT_NODE_MODULES 指向依赖目录
node output/releases/2.1/app-store/source/render.cjs
```

测试使用具体模拟器；指定 `-resultBundlePath` 时，结果放入 `output/validation/<版本>/<任务>/`，每次使用尚不存在的 `.xcresult` 路径。素材渲染需要 Node.js、Playwright、Sharp 和 Chrome；直播原图采集还需先启动本地直播服务。

移动后的实际编译与操作验证见 [目录整理与回归记录](maintenance/2026-09-29-directory-cleanup.md)。
