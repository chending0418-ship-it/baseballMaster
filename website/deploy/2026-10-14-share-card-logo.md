# 链接分享 App Logo 配置与部署

2026-10-14。用户授权先完成并部署分享卡片 Logo，独立于 App 3.0.1。网页改动当日已上线；2026-10-19 用户已确认微信实际分享卡片 Logo 显示正常，客户端验证通过。

## 已部署的内容

文字直播、历史观看、球队邀请和设备配对四个入口的初始 HTML 均加入 App Logo 的分享图片信息：Open Graph 图片、图片类型与 256 × 256 尺寸、Twitter 图片、`image_src`、网页图标及 Apple 图标入口。使用既有 `https://baseballmaster.cc/assets/app-icon.png?v=20261014`，原图片未修改，沿用 [品牌基准](../../docs/brand/BRAND.md)。

动态直播继续由现有服务器输出双方队名与简介。页面布局、分享链接、邀请规则、观看权限及 App 3.0（Build 1）均保持原状，本次只安装四份 HTML。

## 部署与回退

部署脚本：[deploy-share-card-logo.py](../scripts/deploy-share-card-logo.py)。先核对本机与生产原文件哈希，再备份并逐文件原子替换。直播模板位于现用 `3.0-20261013-admin/public/index.html`，每次请求即时读取；其他三个模板位于站点 `watch/`、`join/` 和 `pair/` 下。

成功部署时间：2026-10-14 12:15（北京时间）。生产备份为 `/root/baseballmaster-deploy/share-card-logo-20261014T041551Z/previous/`；本机部署包与安装清单为 `output/deployments/website/share-card-logo-20261014T041551Z/`。两项服务 PID 前后相同，未重启服务、切换程序、修改 Nginx、读取或复制业务数据库。首页、隐私、支持、原 Logo 和直播服务代码的保护哈希均保持一致。

回退时先核对 `installed.json` 中的目标路径和当前安装哈希，再从同一备份的 `previous/` 恢复对应 HTML；遇到后续部署更改须先核对，不覆盖新内容。无需恢复数据库或整份旧程序。

首次部署因本机 Python HTTPS 校验缺少证书链，按脚本自动恢复了四个原文件，并核对恢复哈希。证据保留在 `output/deployments/website/share-card-logo-20261014T041411Z/`。改用系统 `curl` 的正常 HTTPS 证书校验并通过预检查后重新部署；没有关闭证书验证。

## 验证结果

- 直播既有 17 项服务端回归全部通过。
- 四份 HTML 的图片元数据解析通过；本地动态直播保留双方队名、简介，预览中不包含发布凭证。
- 四个公网入口逐字节匹配安装源码；Logo 无需登录即可通过 HTTPS 获取，类型为 PNG、尺寸 256 × 256，哈希与既有品牌图片一致。
- 新建并删除一场合成直播，普通浏览器及微信 User-Agent 的初始 HTML 都包含 Logo、正确队名和简介；验证场次已删除。
- macOS LinkPresentation 从公网直播入口成功取得 256 × 256 图片，导出图片视觉核对为正确 App Logo。
- 直播与协作健康接口均返回 200。

验证材料保存在 `output/validation/3.0/share-card-logo/2026-10-14/`：`live-tests.log`、`local-html.json`、`local-live.json`、`public-live.json`、`system-link-preview.json` 和 `system-link-preview-logo.png`。部署的七项公网检查在安装包的 `public-checks.json`。

首次部署时仅完成 HTTP 和 macOS 系统预览检查，没有向微信联系人发送消息。2026-10-19 用户补充确认：微信实际分享卡片 Logo 已正常显示，实际客户端验证通过；该结论来自用户实测。
