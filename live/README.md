# BaseballMaster 2.1 文字直播 · 腾讯云域名与宝塔部署

**2026-09-28 版本定位：当前版本为 2.0，文字直播计划随 2.1 上线。** 用户已在腾讯云购买 `baseballmaster.cc`，继续使用现有服务器。旧 V1.2.1 文档编号仅作为历史需求标识。

**状态：2026-09-28 根域名解析、官网独立站点及 HTTPS 已部署并通过公网验证；文字直播服务尚未部署。** 当前 App 的直播开关仍关闭，2.1 启用前需更新隐私资料、申报、版本和打包内容。证书有效至 2026-12-27，暂为手动换证，详见 [官网上线记录](../website/deploy/2026-09-28-https-deployment.md)。

部署目标：`https://baseballmaster.cc/livestreaming/novideo/<24位串码>`。用户于 2026-09-28 确认文字直播统一使用 `/livestreaming/novideo/`，替代旧 `/baseballmaster/live/` 路径。这是 2.1 的配套服务，包含网页和 API；**直播当前只在本地准备，生产服务器仅部署了官网与证书**。域名首页提供独立的 [App 官网](../website/README.md)。

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

## 2. 在宝塔准备 Node 环境

1. 在宝塔“软件商店”安装／打开 **Node.js 版本管理器**，安装 Node **24.x（≥24.12）**。本项目使用内置 SQLite，不能套用旧教程中的 Node 18/20。
2. 在终端确认 `node --version` 为上述版本；使用宝塔“网站 → Node 项目”的版本选择，使项目实际运行版本也一致。
3. 上传本目录的部署包到 **`/www/server/baseballmaster-live/`**，解压后此目录直接包含 `server.mjs`、`package.json`、`public/`、`test/`、`demo.mjs` 和 `ecosystem.config.cjs`。
4. 创建独立数据目录 `/www/server/baseballmaster-live-data/`。不要放在现有网站可直接访问的静态目录中。通过宝塔文件权限设置，让运行 Node 的用户（建议 `www`，不要以 root 运行）拥有程序目录的读取权限及数据目录的写入权限；数据目录权限设置为 `700`。
5. **从宝塔备份任务、整目录同步、云磁盘快照和 CDN 缓存中排除直播数据目录。** 程序发布包可保留，直播数据库不做备份；否则定时删除数据库内记录并不能删除外部备份中的副本。

先在终端进入程序目录执行：

```sh
node --version
npm test
```

出现 `node:sqlite` 的 ExperimentalWarning 是本地测试的 Node 24.12 的正常提示。测试应全部通过。

## 3. 启动并守护 Node 服务

宝塔不同版本的菜单名称稍有区别，使用“网站 → Node 项目 → 添加项目”或 PM2 项目管理均可。填写：

| 项目 | 值 |
| --- | --- |
| 名称 | `baseballmaster-live` |
| 项目目录 | `/www/server/baseballmaster-live` |
| Node 版本 | 24.x，至少 24.12 |
| 启动文件／命令 | `server.mjs` / `node server.mjs`（或 npm 的 `start`） |
| 运行用户 | `www` 或专用普通用户 |
| 端口 | `8088`，只绑定 `127.0.0.1` |
| 进程 | 1 个，fork 模式；启用异常重启、开机启动 |

设置以下环境变量：

```text
NODE_ENV=production
HOST=127.0.0.1
PORT=8088
TRUST_PROXY=1
LIVE_DB=/www/server/baseballmaster-live-data/live.sqlite
```

如果使用 PM2 配置文件，仓库的 `ecosystem.config.cjs` 已写好以上值，确认 PM2 使用 Node 24 后，在程序目录执行：

```sh
pm2 start ecosystem.config.cjs
pm2 save
```

**面板创建和 PM2 命令二选一，不要重复启动。** 开机守护按面板设置开启；自行管理 PM2 时，用 `pm2 startup` 按它输出的本机指令配置，并确保启动用户一致。

检查本机服务：

```sh
curl --fail http://127.0.0.1:8088/livestreaming/novideo/health
```

应返回 `{"ok":true}`。8088 不需要对公网开放，云安全组和系统防火墙保持关闭该端口。

## 4. 将新域名的直播路径代理到服务

先备份当前 `/www/server/panel/vhost/nginx/baseballmaster.cc.conf`，再将 [nginx-location.conf](deploy/nginx-location.conf) 的两个直播 `location` 块合并到 **同一新站点的 HTTPS `server { ... }` 内部**，保留已上线的 [官网静态配置](../website/deploy/nginx-homepage.conf)。此次站点通过 SSH 配置，尚未导入宝塔网站列表；若后续在宝塔维护，先按 [上线记录](../website/deploy/2026-09-28-https-deployment.md) 导入现有站点，避免覆盖当前配置。

- 使用新站点的独立根目录，保留证书及证书验证配置；不继承旧站点的 PHP、静态文件或路径代理规则。
- 如果已经存在相同路径的 `location`，修改那一个，避免重复。
- `/` 提供 App 官网，公开静态文件仅限官网配置列出的页面和资源；`/livestreaming/novideo/` 代理直播服务，其他路径默认返回 404。证书续期所需的 `/.well-known/` 验证规则需单独保留并核验。旧的首页跳转规则应替换为官网规则。
- `proxy_pass http://127.0.0.1:8088;` **末尾不加 `/`**，否则转发路径可能被截断。
- 不要通过面板再生成覆盖整个域名的 `/` 代理规则；我们只使用 `/livestreaming/novideo/`。
- 保留／启用此站点 HTTPS 证书和 HTTP → HTTPS 跳转；App 生产环境只连接 HTTPS，不接受自签证书。
- 若使用 CDN，给 `/livestreaming/novideo/*` 配置绕过缓存，API 同时不得缓存。不要为该路径注入分析或广告脚本。

在宝塔检查 Nginx 配置语法后重载 Nginx。通过浏览器访问：

- [观赛入口](https://baseballmaster.cc/livestreaming/novideo/)
- [健康检查](https://baseballmaster.cc/livestreaming/novideo/health)

直播入口应显示串码输入框，健康检查返回 `ok`，域名首页显示 App 介绍。新域名的 `/TODO.md`、`/.git/config` 等未开放路径应返回 404 或 403，不能返回任何内部资料；旧站点的内部内容应要求验证。检查页面、接口、跳转、二维码及证书均不引用旧域名。

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

完成 [2.1 隐私资料](PRIVACY-RELEASE.md) 更新并启用直播后，构建安装 2.1 测试 App，在**测试比赛**中完成（当前 2.0 直播开关仍关闭）：

1. 现场记分右上角天线按钮 → 开启文字直播 → 分享链接，在另一部手机打开。
2. 记录投球、安打、换人、得分；网页约 10 秒后看到同一局面和对应打席；普通投手显示本场球数，教练模式显示本打席球数。
3. 撤销／重做，检查原打席卡片被更新而不是重复出现。
4. 断网记分，联网后确认“已同步”；App 强制关闭再启动可恢复已开启且未过期的直播。
5. 终场后等待“终场已同步”，确认网页给出删除时间；在一小时内恢复比赛测试原链接；再次终场后验证到期无法读取。
6. 手动关闭直播，确认旧链接失效、手机比赛仍在。
7. 在 Safari 和微信内置浏览器检查小屏、展开逐球记录、历史半局跳转以及“有新记录”提示。

前台网络上传已实现；iOS 不保证后台或锁屏后的持续执行，因此实际记分应保持 App 前台。官网和证书已经上线，直播服务部署、直播接口公网验收、域名 CDN 核查与真机微信验证仍未完成，不能用本地模拟器测试代替。

## 6. 更新、回滚与维护

- 仅替换程序文件，保持 `LIVE_DB` 指向原数据目录。`pm2 restart baseballmaster-live --update-env` 或面板重启即可。停机期观众会看到重试，重启后过期场次先清除。
- 回滚程序用上一份部署包；**不回滚／恢复直播数据库**，避免恢复已经删除的比赛。若未来改变数据库格式，先安排无直播时的升级窗口；此版只包含一个初始 schema。
- 基础监控只需每分钟请求 `/health` 并检查进程、内存和磁盘；不记录观赛串码、Authorization、请求体或球员资料。健康检查路径本身无比赛资料。
- 服务默认最多同时 500 场，每 IP 每小时最多创建 30 场、每分钟最多 6000 次请求，单场写入每分钟 180 次；单份投影上限 2 MiB、10000 个时间线条目。超限会明确报错，不会裁掉本地比赛内容。
- 250 人每十秒刷新约 **25 次读取／秒**。本地验证了 250 请求集中读取无错误，但这不等于香港到生产服务器的网络表现；部署后再用真实网络检查延迟。建议从现有服务器的空闲资源开始，无需现在采购新机器。
- 服务单进程、SQLite 单库，不要直接设为 PM2 cluster 或多副本。规模明显增长时再进行数据库和发布鉴权扩展。

参考官方资料：[宝塔 Node.js／PM2 部署](https://docs.bt.cn/practical-tutorials/nodejs-pm2-deployment)、[宝塔反向代理配置](https://docs.bt.cn/user-guide/site/php/site-config/reverse-proxy)、[Node SQLite API](https://nodejs.org/api/sqlite.html)。
