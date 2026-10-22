# 2.1 官网与审核发布准备

日期：2026-09-30。用户要求准备官网、隐私／支持、审核文案与一份更新宣传图，由用户自行在 Xcode 归档上传并提交。公开 App Store 版本仍为 2.0，本次没有操作 Apple 提交。

## 已部署内容

- [官网](https://baseballmaster.cc/)：更新的 2.1 现场记分页，文字直播、链接／二维码分享、四类记分纠错、更正影响预览四张实际截图；保留 2.0 特点。2.2 成人慢垒单独预告，明确 10 人守备、自由人、超过 10 人的打序和初始球数，不宣称包含在 2.1。
- [隐私政策](https://baseballmaster.cc/privacy.html)：覆盖本地数据、可选上传、公开范围、凭证、终场／失联一小时保留、联网关闭及删除、本地备份、未成年人、支持渠道和腾讯云基础设施。
- [技术支持](https://baseballmaster.cc/support.html)：实际功能入口、纠错与保存、同步与后台限制、链接到期、导出及升级指南；新增 [直播内容反馈](https://baseballmaster.cc/support.html#live-report) 和邮件草稿入口，由用户自行发送。
- [观赛页](https://baseballmaster.cc/livestreaming/novideo/)：页脚直接连接直播内容反馈。未变更协议、数据库、服务端代码或 App。

沿用用户确认的品牌。官网标注“2.1 即将上线”，与当前公开商店状态一致。用户明确更新宣传图在上架后发送，因此海报使用“2.1 正式上线”，作为待发布素材，不据此改变当前官网状态。

## 素材与审核交接

设备截图直接复用 9 月 29 日七张图片及两套尺寸，14 张成品 SHA-256 均与原清单一致；复用的五张原图也与任务开始时校验值一致。官网只生成 660 px 宽的 JPEG 副本，原图不改变。

- [审核交接与遗漏清单](../../docs/releases/2.1/REVIEW-HANDOFF.md)
- [商店字段、更新说明和英文审核备注](../../docs/releases/2.1/APPSTORE-METADATA.md)
- [隐私问卷草稿](../../docs/releases/2.1/APP-PRIVACY.md)
- 新海报：`output/marketing/2.1/release-poster/baseballmaster-2.1-update.png`，1440 × 1920 RGB PNG。模板与渲染器随 Git 保存；原图直接嵌入，未重画 App 界面。

宣传文本 84 字符，描述 968 字符，更新说明 284 字符，关键词含文件换行 90 字节，英文审核备注含换行 3,918 字节。按 Apple 当前字段上限核对通过。

## 部署与回滚

独立公开根目录 `/www/wwwroot/baseballmaster.cc`。ZIP 仅含 `website/public/` 的 30 个公开文件，不包含仓库、文案、海报源文件或验证资料。

本次更新首页、隐私、支持三页，新增五张 2.1 网页 JPEG 和 `assets/release-2.1.css`，合计九个静态文件。原 21 个其余文件与本地一致，未删除旧资产。部署先备份，再逐文件原子替换。

观赛页仅替换 `/www/server/baseballmaster-live/releases/2.1-2026-09-30-final/public/index.html`，保留现有 `current` 目标；服务逐次读取该文件，无需重启。本次没有改变 Nginx、证书、运行服务或数据库，未产生公开测试场次。

服务器备份与部署清单：`/root/baseballmaster-deploy/20260930-033405-release-2.1/`（权限 0700）；`before/website/` 为原公开目录，`before/viewer-index.html` 为原观赛页，另有 `before.tar.gz`、前后校验值及 `deployment.json`。

回滚时先核对当前发布路径仍相同，将 `before/website/` 三份旧 HTML 恢复，并删除本次新增六个资产，再恢复原观赛页；如后续有新部署，不应直接覆盖。随后复核页面与服务。原始备份另保存于本机 `output/backups/website/2.1-release-preparation/`。

完整官网包：`output/deployments/website/baseballmaster-home/baseballmaster-home.zip`，由 `website/scripts/package.py` 生成。

## 验证

- 本地 WebKit：三个页面 × 320／375／390／768／1440 px，共 15 组；图片加载、横向溢出、目录锚点、手机导航、截图版本标签、键盘切换和 Escape 关闭均通过。
- 公网 WebKit：上述 15 组，以及 320／390／1440 px 观赛页与反馈链接跳转，共 18 组通过。已检查手机和桌面实际页面。
- HTTPS 30 个公开文件及观赛 HTML 逐字节匹配本地；健康检查正常。HTTP → HTTPS 返回 308；`/TODO.md`、`/README.md`、`/.git/config`、`/assets/` 均返回 404。观赛响应继续采用 no-store／no-referrer。
- 工程仍为 2.1（1），Bundle ID、iPhone 设备族、iOS 16、加密声明与已构建 Release 包一致。Xcode 26.1.1／iOS 26.1 SDK 已核对；本轮仅网页和资料修改，不重复 App 全套测试。
- 海报尺寸、无透明通道、图片加载、文本布局和原图哈希通过；所有 App Store 图片及复用原图保持不变。

原始证据：`output/validation/2.1/release-preparation/` 内的 `local-browser.json`、`production-browser.json`、`public-verification.json`、`deployment.json`、`material-checks.json`、`bundle-config.json` 及页面截图。

剩余事项包括真机旧数据升级和网络／分享验收、规则 PDF 分发权利材料、Connect 隐私／年龄问卷和真实构建状态、用户归档上传与提交。公开内容按 Apple 1.2 审核的可能性及当前未实现过滤／用户封禁的情况，已如实写入交接。SSL 到期前换证，当前不把未来维护误写成已完成。
