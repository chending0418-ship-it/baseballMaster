# 本机输出文件

这里保存编译、测试、截图和交付过程中生成的文件。按用途分类，每类再按版本、日期或任务整理。当前目录布局更新于 2026-09-29。

## 最常用的入口

- [2.1 App Store 七张宣传图总览](releases/2.1/app-store/index.html)
- [6.9 英寸宣传图下载包](releases/2.1/app-store/baseballmaster-2.1-app-store-6.9.zip)
- [6.5 英寸宣传图下载包](releases/2.1/app-store/baseballmaster-2.1-app-store-6.5.zip)
- [2.1 纠错测试证据](validation/2.1/corrections/)
- [按日期保存的 App 截图与 Logo 素材](marketing/screenshots/)

## 分类

| 文件夹 | 内容 | 使用方式 |
| --- | --- | --- |
| `releases/` | 按版本整理的上架截图、交付包和 `.xcarchive` 归档 | 发布时按版本查找；旧版及 live-draft 不能当成本次发布包 |
| `validation/` | 测试日志、`.xcresult`、检查摘要、UI 截图和本地联调资料 | 用于验证与追溯，包含失败的历史尝试，不按“有结果包”判断通过 |
| `marketing/` | 微信群、抖音、小红书宣传素材，以及原始截图／Logo | 按渠道和日期查找，保留各版制作过程 |
| `samples/` | PDF、比赛海报等合成数据展示样例及渲染检查 | 演示和版式检查，不是用户的正式比赛记录 |
| `deployments/` | 官网等部署包及对应验收材料 | 只使用明确的公开部署包；不能将整个项目或本目录上传成网站 |
| `cache/` | Xcode 构建缓存与派生文件 | 可重新编译生成；迁移后旧缓存中的绝对路径可能失效，需要重建 |
| `backups/` | 源码检查点、整理前副本与工作区记录 | 独立保留，不归入可清理的编译缓存 |

`.xcresult` 是 Xcode 测试结果包；`.xcarchive` 是 App 发布归档；`.log` 是命令和测试的输出记录。

## 后续存放约定

- 不直接在 `output/` 根目录创建日志、截图或构建目录。
- 测试输出：`validation/<版本>/<任务>/`，日志与同名结果包放在一起。
- 构建缓存：`cache/xcode/<版本>-<配置>/`。
- 发布交付：`releases/<版本>/<用途>/`，包括 `app-store/`、`archives/`。
- 宣传素材：`marketing/<渠道>/<YYYY-MM-DD>/`；同一天多次修改用 `-v2`、`-v3` 区分。
- 目录使用小写英文加连字符；版本号统一为 `2.1`，不再混用 `v2.1`、`21`。用户阅读的中文标题与编号截图可继续使用中文。
- 新生成文件默认不进入 Git；本说明、已有受版本控制的旧交付包和样例继续跟踪。

历史 `.log`、`.xcresult`、签名归档和源码压缩包保留原始内容，其中出现的旧路径按 [迁移对照表](validation/maintenance/2026-09-29/moves.json) 查找。源码、文档和生成入口的现用路径已更新。历史文件名中用于辨别运行次数或版本的编号保留。

本次整理的文件清单、哈希验证和备份分别位于 `validation/maintenance/2026-09-29/` 与 `backups/maintenance/2026-09-29/`。本目录中的生成文件主要是本机材料，其他 Git 工作区不自动复制它们。

## 2.2 发布交付

当前唯一项目目录为 `/Users/JasonChan/Documents/BaseballMaster`。

- `releases/2.2/`：两套截图、预览、商店文案、签名归档及素材 ZIP。
- `validation/2.2/release-preparation/`：发布阶段模拟器、公网、官网和迁移后构建证据。
- `backups/maintenance/2026-10-03/`：单目录收拢前完整 Git 备份及逐文件迁移校验。

原 2.1 及更早版本的交付／验证内容已保留在相应分类，详见 `docs/maintenance/2026-10-03-single-project.md`。
