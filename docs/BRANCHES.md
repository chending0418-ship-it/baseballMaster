# 版本分支与工作目录

更新日期：2026-10-08。按用户要求顺序开发，唯一项目目录已去掉版本号，现为 `/Users/JasonChan/Documents/BaseballMaster`。

## 当前状态

| 内容 | 分支／目录 | 状态 |
| --- | --- | --- |
| 2.2 发布基线 | `d3d4928`（main 历史），`/Users/JasonChan/Documents/BaseballMaster` | 2.2（Build 1），功能与回归、Web 部署、归档及商店材料完成；App 上传和提交按既有交接记录 |
| 2.2 开发记录 | `codex/dev-2.2` | 已推送并快进合入 main，保留历史分支 |
| 2.1 历史维护 | `codex/release-2.1` | 完整本地／远端分支保留，不反向混入 2.2 功能 |
| 当前活动仓库／2.3 交付 | `main`，根目录 [单份计划](<../2.3 发布功能计划.md>) | 工程 2.3（Build 1），209 项单元／集成与 72 项唯一界面验收完成，官网／兼容直播已上线，资料与归档齐备；`8e54f23` 已推送，Apple 发布由用户执行，小组件已延期 |

2.2 发布代码提交为 `433373b`，已推送到 main 与开发分支；目录及交接收尾另随 Git 保存。App 工程、源码名称和 Bundle ID 保持不变，使用 `BaseballMaster.xcodeproj`。

## 单目录收拢已执行

- 原 2.1 项目目录已在收拢时清理。保留仓库的 `.git` 现为完整独立目录，不再指向旧目录中的 worktree 管理文件；随后按用户要求将 `BaseballMaster-2.2` 原地改名为 `BaseballMaster`，新名称对应本次保留的完整仓库。
- 完整提交对象、全部分支／远端引用及当前索引已核对，`git fsck --full` 通过。原 Git 公共元数据完整备份保存在 `output/backups/maintenance/2026-10-03/git-common-before-consolidation.tar.gz`。
- 原签名归档、测试包及原始日志、宣传图片、部署包和源码备份共 28,242 个文件（1,660,112,130 字节）逐文件 SHA-256 验证后迁入相应 `output/` 分类。只有可重建缓存和 `.DS_Store` 不迁移；Xcode 本机界面状态单独保留。
- 旧材料若与当前同路径不同内容，保留到维护备份的 `legacy-output/`，不覆盖。完整映射与校验在 `output/backups/maintenance/2026-10-03/material-migration.json`。
- 早期根部生成的纠错同步日志／测试包及构建缓存已归入 validation／cache，映射保存在同一备份目录的 `generated-moves.json`。
- 迁移后 Release 再次编译通过，然后才删除原项目目录；源码及交付资料保留。详见 [维护记录](maintenance/2026-10-03-single-project.md)。

## 后续开发

开始修改前检查当前分支和工作目录。在这个目录内沿用已交付的 `main`，2.3 原本地开发分支保留，工程已为 2.3（Build 1），不建立专属文件夹。目录改名记录见 [维护记录](maintenance/2026-10-03-project-folder-rename.md)。2.2 实际上传／审核／发布以 [交接文档](releases/2.2/REVIEW-HANDOFF.md) 的证据为准。

## 历史基线

2026-09-29 建立 2.1／2.2 工作区；9 月 30 日完成 2.1 文字直播部署和审核准备，用户于 10 月 1 日确认 2.1 已上线。2.2 从 main 的 `8b7c99d` 继承 2.1。10 月 2 日用户将慢垒及小组件整体延期至单份 2.3 计划，并明确顺序开发及单目录要求。10 月 3 日直播 UI、正式服务与发布准备完成，随后收拢目录。历史归档和验证内容不改写成新的发布结论。
