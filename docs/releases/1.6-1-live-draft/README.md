# TestFlight 1.6（1）交付目录

按仓库根目录的 [发布交接](../../../RELEASE_REVIEW.md) 操作。开发签名归档位于 `output/build/BaseballMaster-1.6-1.xcarchive`；当前没有成功导出的 `.ipa`，没有上传或分发。

| 文件 | 使用方式 |
| --- | --- |
| `what-to-test-zh.txt` | TestFlight 的测试内容 |
| `beta-app-description-zh.txt` | Beta App 介绍 |
| `beta-review-notes-en.txt` | 外部 Beta 审核说明草稿；部署服务并落实规则资料处理后使用 |
| `baseballmaster-privacy.html` / `baseballmaster-support.html` | 替换现有网站对应页面；当前只是本地文件 |
| `app-privacy-checklist.md` | App Store Connect 隐私申报和网站更新步骤 |
| `physical-upgrade-checklist.md` | 已发布旧版真机覆盖升级、备份及公网观赛验收 |
| `BaseballMaster-live-baota.zip` | 服务、网页、Nginx 配置和部署说明；不包含直播数据库 |
| `ExportOptions.plist` | App Store Connect 导出配置，保留版本／构建号；需有效发布签名 |
| `screenshots/` | 1.6 的 1284 × 2778 原始模拟器截图及总览，无裁切／重画 |
| `qa/` | 回归结果、合成升级前后对照、归档核验、服务端日志及限制说明 |

本次目标为 TestFlight，正式 App Store 上架另行处理。旧 `output/app-store/` 的 1.5 截图仍保留，不将其当成 1.6 新截图。`screenshots/` 用独立内存示例打开实际页面，部分页面作为预览根页面，因此没有前级导航返回按钮；正常比赛返回路径另由 UI 回归验证。现有八张是页面与布局验收素材，直播分享界面另保存在 `qa/ui-screenshots/`，其中的本机地址不是线上观赛链接。
