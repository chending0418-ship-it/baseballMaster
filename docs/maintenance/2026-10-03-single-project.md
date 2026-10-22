# 2.2 发布后的单项目目录收拢

2026-10-03，按用户明确要求执行：先完成 2.2，后续 2.3 在同一文件夹开发；电脑只保留一个项目目录。

最终目录：`/Users/JasonChan/Documents/BaseballMaster-2.2`，当前分支 main。旧 `/Users/JasonChan/Documents/BaseballMaster` 已清理；没有创建 2.3 项目或工作区。

随后用户要求去掉文件夹版本号，以上保留仓库已原地改名为 `/Users/JasonChan/Documents/BaseballMaster`。本页记录原收拢过程，当前名称及改名核验见 [目录改名记录](2026-10-03-project-folder-rename.md)。

## 执行与验证

1. 2.2 功能、文案与站点提交 `433373b`，推送开发分支，快进合入并推送 main；远端两个引用与本地提交核对相同。
2. 原目录保持干净。将需要保留的发布归档、验证结果包／日志、宣传、部署、样例及源码备份迁到相同 output 分类；同名不同内容保存在维护备份中，不覆盖。
3. 28,242 个文件、1,660,112,130 字节逐文件核对 SHA-256；只排除可重建 output/cache 和 .DS_Store。本机 Xcode 用户界面状态另存备份，删除前确认一致。
4. 完整 Git 公共目录先打包备份，再复制到保留目录；切换当前 worktree 的 HEAD、索引与 HEAD 日志，移除复制副本中的 worktree 管理指向，使 `.git` 成为独立目录。
5. 所有 refs、提交对象和当前跟踪索引一致，干净状态一致；`git fsck --full` 通过，worktree 清单只显示保留目录，Git 公共目录为自身 `.git`。
6. 新目录 iPhoneOS Release 再次构建成功，2.2 归档与十四张交付图片保持完整；旧目录无业务改动、分支引用无变化、所有迁移文件仍存在，随后清理旧目录。
7. 根部历史生成物移动至 validation／cache，并更新当前引用；所有原始归档／测试包保留内容和历史路径，不改写内部日志。

## 本机证据

- `output/backups/maintenance/2026-10-03/material-migration.json`：完整文件迁移映射及校验值。
- `output/backups/maintenance/2026-10-03/git-common-before-consolidation.tar.gz`：原完整 Git 公共元数据，可恢复历史。
- `output/backups/maintenance/2026-10-03/git-migration.json`：Git 独立、refs／索引及 fsck 核验，旧目录不存在，迁移后构建通过。
- `output/backups/maintenance/2026-10-03/generated-moves.json`：早期根部生成物的新位置。
- `output/validation/2.2/release-preparation/post-consolidation-build.log`：迁移后 BUILD SUCCEEDED。

当前工程仍叫 BaseballMaster.xcodeproj，源码目录、Bundle ID、签名归档内容及品牌未改名。原 2.1 维护分支和全部远端引用保留，不将 2.2 功能反向合入该分支。此项完成不代表 Apple 已收到或审核通过 2.2。
