# 文字直播隐藏点选操作提示

2026-11-02，按用户“直接部署”授权，已更新现有香港文字直播网页的 `public/app.js`。观众刷新原链接后，打席摘要与展开明细隐藏“点选位置／点选去向”，保留球型、明确选择的区域、守备路线、跑者信息及复核标记。

- 当前服务程序目录保持 `/www/server/baseballmaster-live/releases/3.0-20261013-admin`；本次只原子替换其静态 `public/app.js`，未重启直播进程，未更改 API、链接、数据库、DNS、Nginx 或 iOS 工程。
- 生产替换前版本与本地源码的差异仅为本次文案显示修正。旧文件保存在 `/www/server/baseballmaster-live/code-backups/3.1.1-contact-wording-20261102/app.before.js`，本地副本为部署证据中的 `app.before.js`。
- 旧文件 SHA-256：`2513fbec1f496c84f22ad9344831c0b9f6d411b97eac2c9e4f9208e06a6dd2ae`。
- 部署及公网文件 SHA-256：`afc218cb65c75d6d749fc34e93e0945af9eb13124f7119f5f8a559e656a7814e`，与通过四组本地 Chrome 浏览器验证的文件一致。
- 公网 `https://baseballmaster.cc/livestreaming/novideo/app.js` 返回 200，下载内容哈希匹配；`/livestreaming/novideo/health` 返回 `{"ok":true}`，systemd 服务 active，部署前后 MainPID 一致。
- 没有读取用户场次，没有创建生产测试比赛，也没有复制或改写比赛数据。

过程保留：首轮公网验收因本机 Python CA 配置失败并自动回退；第二轮验收脚本误用球队服务的健康返回格式，按现行直播接口修正前自动回退；第三轮单次公网请求超时后自动回退，补充有限次数的 HTTPS 重试后最终验收通过。所有尝试均未重启进程，最终成功证据为 `postflight.json`，失败日志保留，不当作成功。

本地部署证据：`output/deployments/live/3.1.1-contact-wording-20261102/`，含部署脚本、原文件、线上下载文件、安装与验收 JSON、各轮日志。浏览器证据：`output/validation/3.1.1/live-contact-wording/`，手机／桌面与浅色／深色共四组，无页面错误，原始快照不变。

如需回滚，仅将上述 `app.before.js` 复制至同目录临时文件后原子替换 `public/app.js`，校验旧哈希即可，无需重启或恢复数据库。iOS App 上传审核、上海迁移及 main 合并均未执行。
