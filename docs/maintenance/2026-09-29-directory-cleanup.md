# 2026-09-29 项目目录整理

本次整理 `/Users/JasonChan/Documents/BaseballMaster/`，保持 2.1 App 源码、工程结构、品牌和版本不变。用户已有的发布计划、README、TODO 修改在整理前另存副本并完整保留。

## 结果

- `output/` 原有 49 个顶层项目归为 `releases`、`validation`、`marketing`、`samples`、`deployments`、`cache`、`backups` 七类，并增加可点击的 [输出目录说明](../../output/README.md)。
- 当前 2.1 七张宣传图统一放在 `output/releases/2.1/app-store/`，ZIP 统一为小写英文文件名，总览统一为 `overview.png`。
- 松散测试日志与结果包按版本和任务归档；失败尝试保留，不修改原始日志或结果包。
- 根目录 `update/` 中当前资料移入 `docs/releases/2.1/`，旧需求与草案按版本放入 `docs/archive/plans/`，统一文件名。
- 根目录 TODO 保留当前事项，完整历史移至 `docs/archive/plans/DEVELOPMENT-TODO-HISTORY.md`；README 的截图入口统一指向当前图集和历史归档。
- 根目录早期 `Screenshots/` 归入 `docs/archive/screenshots/initial-ui/`；2.0 交接文档归入 `docs/releases/2.0/RELEASE-HANDOFF.md`。
- 根目录 `tmp/`、`DerivedData/` 与原 `output/build/` 分拆为正式归档、验证资料、源码备份和编译缓存。
- Xcode 派生目录中的 `Logs` 和 `TestResults` 单独保留在 `output/validation/<版本>/xcode-logs/`；可重建的编译产物、索引和模块缓存移入本机废纸篓，可恢复。
- 修改文档导航和生成入口：官网打包输出到 `output/deployments/website/`；直播浏览器验证输出到 `output/validation/2.1/live-browser/`。
- 2.2 工作区只修复指向主工作区已迁移资料的绝对链接；其已有未提交计划和自身目录布局保留。
- 保留原有受 Git 跟踪的交付文件和样例。新生成文件仍被忽略，输出目录说明加入版本管理范围。

## 保全与验证

迁移后核对 40,279 个文件的存在及大小，其中 22,072 个非缓存文件的 SHA-256 一致。此项在更新导航文档前执行，用于确认搬迁没有损坏原文件。原始 `.log`、`.xcresult`、`.xcarchive` 和源码检查点不改写；随后对明确的导航文档及当前交付说明更新路径。

2.1 两个截图 ZIP 只刷新使用说明，原 ZIP 已另存备份；14 张 PNG 的 SHA-256 与交付清单一致。官网包重新生成并逐个核对 24 个公开文件，不包含内部资料。直播网页的手机浅色、手机深色、窄屏和桌面四组回归通过。

现用 Markdown 链接、App Store 图库资源和改动格式检查见本机验证摘要。App 业务代码、测试源码及 Xcode 工程没有变更。

## 移动后实际运行验证

为确认后续开发和常用操作正常，在清理后的新目录重新构建并执行下列验证：

| 操作 | 结果 |
| --- | --- |
| 从空派生目录编译 Debug 并测试 | 161 项单元／集成测试全部通过 |
| iPhone 17 Pro Max 模拟器界面回归 | 4 项全部通过，覆盖四类纠错及预览、守位与失误编辑、记分操作、PDF 预览与分享入口 |
| iPhoneOS Release 编译 | `BUILD SUCCEEDED`，使用独立的新派生目录 |
| App Store 宣传图重生成 | 两种尺寸共 14 张，SHA-256 与原交付图片逐张一致 |
| 本地直播网页截图 | 从项目根目录以外的位置执行成功，生成真实本地观赛页截图 |
| 小红书与抖音素材重生成 | 在验证副本中分别生成 6 张 PNG 及总览，导出成功 |
| 官网打包与直播网页回归 | 24 个公开文件一致；手机浅／深色、窄屏及桌面 4 组通过 |

直播截图脚本原先依赖执行命令时所在的目录，已改为从脚本位置自动查找项目根目录；App Store 渲染器每次生成后会保留图片 SHA-256 清单。其余现用路径和文档引用已更新为新目录。

这次验证覆盖现有开发、编译、测试、素材生成、官网打包与 PDF 分享入口；Release 为关闭签名的编译验证，不包含真机签名安装、App Store 上传或公网部署。2.1 和 2.2 的 Git 工作区位置与分支均保持有效。清理编译缓存后的首次编译会重新生成索引和缓存，耗时可能增加。

日志、测试结果包、生成副本与机器可读摘要保存在 `output/validation/2.1/post-cleanup/`，摘要为 `validation.json`。首次社交素材复现因验证副本漏拷 Logo 而中断，补齐后重新导出成功；首次日志仍保留，原素材没有缺失。

## 对照与恢复

- [第一阶段迁移对照表](2026-09-29-directory-moves.json)：原位置到归档位置。
- 本机验证和完整文件清单：`output/validation/maintenance/2026-09-29/`。
- `cache-retirement.json`：第二阶段缓存到废纸篓的位置，以及日志和测试结果的保留位置。缓存没有永久删除。
- 整理前文本文档与脚本副本、未提交差异、原 ZIP：`output/backups/maintenance/2026-09-29/`。
- 发布归档保留在 `output/releases/<版本>/archives/`，源码检查点保留在 `output/backups/`。Git 元数据和分支未清理或删除。

历史原始证据中的路径反映当时的执行环境，按以上迁移表查找即可；不会为消除旧路径而改写原始测试日志或签名归档。
