# 项目文件夹去掉版本号与 2.3 规划启动

2026-10-03，用户要求开始 2.3 规划、列出已有 TODO，并将项目文件夹去掉版本号。

## 当前目录

| 项目 | 路径／状态 |
| --- | --- |
| 原目录 | `/Users/JasonChan/Documents/BaseballMaster-2.2`，改名后不存在 |
| 唯一项目目录 | `/Users/JasonChan/Documents/BaseballMaster` |
| Xcode 工程 | `/Users/JasonChan/Documents/BaseballMaster/BaseballMaster.xcodeproj` |
| 分支与基线 | `main`，`d3d4928d00642e74c8e8d7ed8ae493a082f248db` |
| 工程版本 | 2.2（Build 1）；本轮只规划，开始实现 2.3 时再调整 |
| 2.3 需求入口 | 根目录 [单份发布功能计划](<../../2.3 发布功能计划.md>) |

本次直接改名整个目录，未复制仓库、重建 Git、另建工作区或创建旧路径符号链接。目录 inode 相同；完整 Git、output 分类、归档、测试证据和素材随原目录保留。历史 2.2 归档和素材的文件名继续带其发布版本，历史日志和结果包中的原始路径保留。

## 核验

- 改名前工作区干净，当前分支为 main；改名后 HEAD 和全部 refs 与改名前一致，`git worktree list` 只显示新目录，`.git` 为完整独立目录。
- `git fsck --full` 返回 0，保留既有未被引用的历史对象；未执行清理对象操作。
- `xcodebuild -list -json -project BaseballMaster.xcodeproj` 返回 0，识别 BaseballMaster Scheme、Debug／Release 和 App／两个测试 Target。这是工程识别检查，本轮没有重新编译或新增业务测试。
- 工程配置、2.2 签名归档 Info.plist、商店素材 ZIP 及十四张截图，共 17 个文件的 SHA-256 前后一致。
- 2.3 的 32 项慢垒任务、8 项小组件任务、A01–A26 与 WA01–WA08 完整保留，均待实现；当前文档的相对链接有效，差异格式检查通过。
- 现用目录说明、TODO、版本计划、发布交接及本机材料入口已更新路径；分阶段开发／验证记录保留原工作区名称作为历史。

本机前后核验记录位于 `output/backups/maintenance/2026-10-03-folder-rename/before.json` 与 `verification.json`。

## 应用入口限制

Codex 的已保存项目入口仍指向旧目录，目录改名后未自动更新。现有项目工具支持读取但没有修改项目路径的能力，界面控制也禁止操作 Codex；需要用户在应用中重新选择 `/Users/JasonChan/Documents/BaseballMaster`。文件系统改名和仓库核验已完成。

## 发布状态

2.2 功能、模拟器／公网回归、Web 部署、签名归档与商店材料已经完成；Apple 上传、提交审核及上架未执行。本次规划启动和目录改名不改变这些发布状态。2.3 仍按 [单份计划](<../../2.3 发布功能计划.md>) 在同一目录顺序推进。
