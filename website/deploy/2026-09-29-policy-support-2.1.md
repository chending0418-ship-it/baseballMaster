# 2.1 隐私政策与技术支持页面更新

日期：2026-09-29。范围为官网内容更新；App 版本仍为 2.1（Build 1），直播开关关闭，直播服务未部署，未上传或提交苹果审核。

## 公开地址

- [隐私政策](https://baseballmaster.cc/privacy.html)
- [技术支持](https://baseballmaster.cc/support.html)

两页均说明当前 2.1 尚未正式发布、直播尚未开放，直播相关条款及操作在支持该功能的版本开放后适用；2.0 和未开播时仍按本地处理说明使用。上线直播及正式发布时需同步更新此状态提示。

## 内容变化

- 隐私政策：区分本地记分／历史纠错与主动开启的单场直播；列出球队、球员姓名与背号、比赛过程、公开标识及同步信息；说明持链接可看、发布权限、终场／失联一小时到期、联网关闭、停机恢复清理与本地数据保留。
- 补齐 PDF／TXT／图片分享、备份、隐私请求、未成年球员资料、网站必要网络信息及联系渠道；不再笼统描述所有版本均不会上传比赛。
- 技术支持：四类漏记的入口、草稿预览和保存、最新更正撤回；直播开播、二维码／链接分享、前台同步、错误提示、关闭、过期以及换机／备份与导出说明。
- 沿用已确认的图标、英文标识、中文副标题和深绿样式；只增加目录锚点和版本提示框。

文案按 `LiveSnapshot`、直播服务端期限与删除实现、纠错 UI 及当前生产站点配置核对。审核相关依据及剩余准备事项见 [2.1 隐私资料](../../live/PRIVACY-RELEASE.md)，网页更新不代表已完成 App 内说明、隐私清单或 App Store Connect 问卷。

## 部署与回滚

通过本机已有 SSH 配置连接当前服务器，仅发布以下三个公开文件：

| 文件 | SHA-256 |
| --- | --- |
| `privacy.html` | `104b6dc4b74c0654b1ae08f6cb1398b110ad6f359200500f3fa20729df3d2542` |
| `support.html` | `5bf22eb8d2b59f013bb7455408a5e42864fbff4fba8c1986fc528f3fe19c590a` |
| `assets/legal.css` | `cb040d5c003e0f5063d0ab4091e44491e20f6c9a7053faa261bea8cbe6fe44f1` |

公开根目录仍为 `/www/wwwroot/baseballmaster.cc`。发布前核对 24 个线上文件的校验值，先将原三文件备份，再逐个原子替换；其余 21 个公开文件保持一致。未修改 Nginx、证书、首页或直播路由，无需重载服务器。

服务器备份与操作记录：`/root/baseballmaster-deploy/20260929-154917-policy-2.1/`，目录仅 root 可访问，原文件在 `before/`。如需回滚，将该目录内三份原文件按相对位置恢复至公开根目录，并重新检查页面、样式和 HTTPS。

本机发布包：`output/deployments/website/2.1-policy/`。完整官网 ZIP 已重新生成，仍只包含 `website/public/` 的 24 个公开文件。

## 验证结果

- 本地与生产浏览器分别验证两页的 320、390、768、1440 px，共各 8 组；资源加载、无横向溢出、目录跳转、联系链接及两页互相跳转通过。
- 检查桌面和手机实际截图，沿用品牌样式，标题、正文、联系方式可读。
- 公网两页及 CSS 均返回 HTTPS 200，响应内容与本地逐字节一致；HTTP 正常跳转 HTTPS，系统证书验证通过。
- 首页仍返回 200；`/TODO.md`、`/README.md`、`/.git/config` 仍返回 404；尚未部署的直播路径仍返回 404。
- App 与测试代码、工程配置未变化；本次采用网页验证，不重复 App 测试。

原始验证资料：`output/validation/2.1/website-policy/`，包含 `local-browser.json`、`production/production-browser.json`、`deployment.json`、`public-verification.json` 和截图。部署前校验清单及页面副本位于 `output/backups/website/2026-09-29-policy/`。
