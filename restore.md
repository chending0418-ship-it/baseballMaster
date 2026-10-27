# 旧分支恢复说明

`branches.json` 保存清理前 39 个远端分支名称和完整提交 SHA。`branches.bundle` 包含清理前所有 42 个远端分支引用及提交对象；工作区未提交文件与被忽略的 APK／日志不在此 bundle。

GitHub 标签 `archive/branches-2026-10-27` 指向含同一清单的归档提交，其父提交包含全部旧分支头，保证 GitHub 上也保留可恢复对象。要恢复某一分支，先确认目标名未被其他人使用，再从标签提交中的 branches.json 查完整 SHA，并用 `git push origin <SHA>:refs/heads/<原分支名>` 恢复。也可先从本机 bundle 取回 `refs/remotes/origin/<原分支名>`。

历史补录 PR #2 使用删除前的空基线分支作为目标；删除该分支可能影响 GitHub 对该 PR 的比较视图。恢复该基线分支可供后续检查；不更改 PR 的历史状态。
