# BaseballMaster 2.1 文字直播 · 腾讯云域名与宝塔部署

**状态：2026-09-30 文字直播网页与 API 已部署并通过公网及模拟器联调，2.1 App 直播开关已启用。App 尚未上架，暂不提交苹果审核。**

观赛入口：[baseballmaster.cc/livestreaming/novideo/](https://baseballmaster.cc/livestreaming/novideo/)。单场地址为入口加 24 位串码。开播需使用支持直播的 2.1 App；官网、隐私和技术支持页面已同步实际状态。当前生产运行记录、验收证据及回滚步骤见 [2026-09-30 部署记录](deploy/2026-09-30-production.md)。

使用现有腾讯云服务器、独立域名和现有 HTTPS 证书，不重复建立站点。证书有效至 2026-12-27，当前为手动换证，详见 [官网上线记录](../website/deploy/2026-09-28-https-deployment.md)。

## 0. 腾讯云解析与站点隔离

当前根域名 A 记录已指向 `124.156.173.204` 并验证生效，独立官网和 HTTPS 已上线。以下保留从零配置参考，不要重复创建现有站点。

1. 登录腾讯云的 DNSPod／云解析 DNS 控制台，进入 `baseballmaster.cc` 的记录管理页面，添加下列记录：

   | 主机记录 | 类型 | 线路 | 记录值 | TTL |
   | --- | --- | --- | --- | --- |
   | `@` | `A` | 默认 | 现有服务器的公网 IPv4，以服务器控制台为准 | 600 秒 |

   记录值只填 IP，不填网址或端口。不要使用指向旧域名的 CNAME 或 URL 转发；无需添加 `www` 即可使用当前方案。若 DNS 尚未委派给 DNSPod，先按控制台提示设置域名 DNS 服务器。新注册和解析变更需等待公共 DNS 生效。
2. 在宝塔新建静态站点，绑定 `baseballmaster.cc`，使用独立目录（例如 `/www/wwwroot/baseballmaster.cc`）。按 [官网部署说明](../website/README.md) 只上传 `website/public/` 的内容。不要把新域名直接添加为旧站点的别名，不要把整个仓库、TODO、备份或内部网页上传到该目录。
3. 为 `baseballmaster.cc` 配置独立 HTTPS 证书和自动续期，确认服务器 80／443 端口可访问。启用 HTTP → HTTPS 跳转。不要使用同时包含旧域名的证书。
4. 旧站点的 TODO 等内部资料需登录验证或其他访问控制，不能依赖隐藏域名。检查默认站点和直接 IP 访问不会返回内部资料，也不能通过服务的其他公网端口绕过验证。共用服务器不能保证两个域名无法被关联。

域名与站点准备完成后，再执行以下直播部署步骤。腾讯云操作依据：[A 记录](https://intl.cloud.tencent.com/zh/document/product/1295/76974?lang=zh)。

## 1. 运行方式与保留规则

- Node.js **24.x，至少 24.12**，内置 HTTP + SQLite，无 npm 第三方运行依赖；单进程即可。无需 MySQL、Redis、对象存储或视频产品，使用已购买的独立域名。
- App 主动开播后上传该场只读投影，网页前台每 **10 秒**轮询。无新版本只返回同步时间等少量资料；后台暂停，返回后补拉。
- 当前投打区显示“第 X 棒 · 打者”；棒次与 App 共用计算，阵容调整、换人、换边和撤销重做随快照同步。终场不显示当前棒次；旧开发快照缺少字段时仍可观看，兼容约定见 [协议](PROTOCOL.md)。
- 观看串码与写入密钥分开。任何持链接者可看；只有原发布设备可写。写入密钥位于设备 Keychain，不放进链接、二维码、本地比赛导出或网页。
- 服务端只保留**当前版本**，不建立版本历史。结束时间 + 1 小时自动删除，重复终场、心跳和赛后修正不延长期限。在期限内“恢复继续”可沿用原链接；过期后旧链接不能复活。
- 设备断网或被系统挂起时，服务器无法获知新记录。为避免遗留，**连续一小时未同步也删除**。断网期间可继续本地记分，联网补传；旧链接过期后需要重新开播、重新分享。
- 删除任务每秒执行，也在每次请求和服务启动前执行。到期即禁止读取；服务停机期间无法物理执行删除，恢复时先清理再提供服务。
- 删除仅作用于服务器；**手机中的原比赛、统计和备份保留**。不做云端直播备份，不接入 CDN 内容缓存，不存浏览器本地数据库，不保留回放。

## 2. 当前生产运行方式

本机通过 `ssh jingsen-prod` 管理现有服务器，使用 **systemd** 守护，未安装 PM2，也不改变其他服务的 Node 环境。

| 项目 | 当前值 |
| --- | --- |
| Node | 官方 Node 24.21.0 Linux x64，SHA-256 已验证；24.x ≥24.12 |
| 运行时 | `/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node` |
| 程序 | `/www/server/baseballmaster-live/current`，链接到独立 release 目录 |
| 服务 | `baseballmaster-live.service`，开机启动、失败自动重启 |
| 运行用户 | `baseballmaster-live`，普通不可登录账户；程序文件由 root 管理 |
| 地址 | `127.0.0.1:18088`，不对公网开放；8088 属于其他现有服务 |
| 数据 | `/www/server/baseballmaster-live-data/live.sqlite`，tmpfs，noswap，目录 700、库 600 |

服务文件：[baseballmaster-live.service](deploy/baseballmaster-live.service)。内存挂载模板：[live-data.mount.template](deploy/live-data.mount.template)，部署时将 `@LIVE_UID@`、`@LIVE_GID@` 替换成运行用户的数字 UID/GID，文件名为 `www-server-baseballmaster\x2dlive\x2ddata.mount`。服务依赖该挂载；挂载失败时不会降级到磁盘存储。需要 Linux 6.4+ 的 tmpfs `noswap` 支持，当前生产内核为 6.6。

内存目录限额 1536 MiB，服务总内存上限 2 GiB，禁用服务交换及 core dump。这样直播数据库不会进入云磁盘快照。不得手动或通过文件备份任务复制直播数据目录。当前服务器定时任务已核查，无直播目录备份；未来新增备份须继续排除该目录。

**服务进程重启保留当前直播；整台服务器重启清空当前直播，旧链接失效，需重新开播。** 手机原比赛始终保留。此取舍已写入公开隐私政策、技术支持及开播说明。

## 3. 健康检查与守护

```sh
systemctl is-active baseballmaster-live.service
systemctl is-enabled baseballmaster-live.service
findmnt -T /www/server/baseballmaster-live-data
curl --fail http://127.0.0.1:18088/livestreaming/novideo/health
```

期望服务为 active/enabled、挂载类型为 tmpfs 且含 noswap，健康返回 `{"ok":true}`。运行环境由 service 文件固定：`NODE_ENV=production`、`HOST=127.0.0.1`、`PORT=18088`、`TRUST_PROXY=1`，`LIVE_DB` 指向内存目录。

PM2 配置仅作为其他环境的参考，不能与当前 systemd 服务同时启动。不要直接以 root 启动服务，或为 18088 新增安全组开放规则。

## 4. HTTPS 与公开路径

已在现有 `/www/server/panel/vhost/nginx/baseballmaster.cc.conf` 的 HTTPS server 中合并 [nginx-location.conf](deploy/nginx-location.conf)，`proxy_pass http://127.0.0.1:18088` 不加尾部 `/`。仅直播前缀转发到 Node，官网仍从独立静态目录提供，其他路径默认 404。

当前域名的 HTTP、HTTPS 和 www 跳转均关闭访问／错误路径日志，避免在跳转阶段泄漏观赛串码。Node 只记录启动及不含比赛资料的错误类别。直播响应 `no-store`、`no-referrer`、`noindex`，Nginx 不使用代理缓存，当前解析直接指向服务器，未走 CDN。

变更前备份配置，执行 `nginx -t` 成功后再 `systemctl reload nginx`。不修改其他站点、HTTPS 证书、ACME 规则或现有 8088 服务。后续若接入 CDN，必须让全部直播页面／API 绕过缓存，不能注入分析、广告脚本或记录串码。

公开检查：[观赛入口](https://baseballmaster.cc/livestreaming/novideo/) · [健康检查](https://baseballmaster.cc/livestreaming/novideo/health) · [隐私政策](https://baseballmaster.cc/privacy.html) · [技术支持](https://baseballmaster.cc/support.html)。

## 5. 上线前验收

先使用合成数据检查 API 全链路（仅创建临时示例，测试结束自动删除）：

```sh
LIVE_BASE=https://baseballmaster.cc/livestreaming/novideo node demo.mjs --smoke
```

应显示 `PASS: create → read → update → undo → finish → delete`。

要在手机 Safari／微信内看一场示例：

```sh
LIVE_BASE=https://baseballmaster.cc/livestreaming/novideo node demo.mjs
```

终端会给出示例链接。保持进程运行，输入 `ball`、`undo` 可查看十秒轮询效果，`finish` 显示终场，`reopen` 恢复，`expire` 将该合成比赛设为已终场 59 分 45 秒（约 15 秒后删除），`quit` 关闭并删除。命令不显示或写盘保存发布密钥。示例不是常驻审核回放，链接不能永久使用。

安装已启用直播的 2.1 测试 App，按 [2.1 隐私资料](PRIVACY-RELEASE.md) 核对后，在**测试比赛**中完成（2.1 直播开关已启用）：

1. 现场记分右上角天线按钮 → 开启文字直播 → 分享链接，在另一部手机打开。
2. 记录投球、安打、换人、得分；网页约 10 秒后看到同一局面和对应打席；普通投手显示本场球数，教练模式显示本打席球数。
3. 撤销／重做，检查原打席卡片被更新而不是重复出现。
4. 断网记分，联网后确认“已同步”；App 强制关闭再启动可恢复已开启且未过期的直播。
5. 终场后等待“终场已同步”，确认网页给出删除时间；在一小时内恢复比赛测试原链接；再次终场后验证到期无法读取。
6. 手动关闭直播，确认旧链接失效、手机比赛仍在。
7. 在 Safari 和微信内置浏览器检查小屏、展开逐球记录、历史半局跳转以及“有新记录”提示。

前台网络上传已实现；iOS 不保证后台或锁屏后的持续执行，因此实际记分应保持 App 前台。服务、网页和公网接口已验收，模拟器直接连接正式 HTTPS 完成联调。模拟器断网使用开发构建中受控的网络错误注入，恢复后仍走正式 HTTPS；真机蜂窝／Wi-Fi、Safari／微信分享与使用体验仍待实测。不得将模拟器结果写成真机验收。

## 6. 更新、回滚与维护

- 仅替换程序文件，保持 `LIVE_DB` 指向原数据目录。`systemctl restart baseballmaster-live.service` 即可。停机期观众会看到重试，重启后过期场次先清除。
- 回滚程序用上一份部署包；**不回滚／恢复直播数据库**，避免恢复已经删除的比赛。若未来改变数据库格式，先安排无直播时的升级窗口；此版只包含一个初始 schema。
- 如需配置监控，可每分钟请求 `/health` 并检查进程、内存和磁盘；不记录观赛串码、Authorization、请求体或球员资料。健康检查路径本身无比赛资料。
- 服务默认最多同时 500 场，每 IP 每小时最多创建 30 场、每分钟最多 6000 次请求，单场写入每分钟 180 次；单份投影上限 2 MiB、10000 个时间线条目。超限会明确报错，不会裁掉本地比赛内容。
- 250 人每十秒刷新约 **25 次读取／秒**。本地验证了 250 请求集中读取无错误，但这不等于香港到生产服务器的网络表现；部署后再用真实网络检查延迟。建议从现有服务器的空闲资源开始，无需现在采购新机器。
- 服务单进程、SQLite 单库，不要直接设为 PM2 cluster 或多副本。规模明显增长时再进行数据库和发布鉴权扩展。

参考官方资料：[宝塔 Node.js／PM2 部署](https://docs.bt.cn/practical-tutorials/nodejs-pm2-deployment)、[宝塔反向代理配置](https://docs.bt.cn/user-guide/site/php/site-config/reverse-proxy)、[Node SQLite API](https://nodejs.org/api/sqlite.html)。
