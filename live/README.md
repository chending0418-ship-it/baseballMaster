# BaseballMaster 文字直播 · 宝塔部署

**2026-09-27 版本定位：本服务属于 V1.2.1 文字直播。** 先完成 V1.1 收尾和 V1.1.1 现场修复，再接续 App／网页／服务端联调、生产部署及真机验收；详见[版本推进规划](</Users/JasonChan/Documents/BaseballMaster/update/BaseballMaster V1.1至V1.2.1 版本推进规划.md>)。本轮只规划，不执行部署。

**状态：直播延期至 V1.2.1 正式迭代，本次 TestFlight 1.6（1）不部署、不启用。以下为保留的后续部署方案，启用前需重新核对政策、版本和打包内容。**

部署目标：`https://jingsen.cc/baseballmaster/live/<24位串码>`。这是后续启用直播版本的配套服务，包含网页和 API；**当前只在本地验证，没有连接或修改你的生产服务器**。

## 1. 运行方式与保留规则

- Node.js **24.x，至少 24.12**，内置 HTTP + SQLite，无 npm 第三方运行依赖；单进程即可。无需 MySQL、Redis、对象存储、视频产品或另购域名。
- App 主动开播后上传该场只读投影，网页前台每 **10 秒**轮询。无新版本只返回同步时间等少量资料；后台暂停，返回后补拉。
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
curl --fail http://127.0.0.1:8088/baseballmaster/live/health
```

应返回 `{"ok":true}`。8088 不需要对公网开放，云安全组和系统防火墙保持关闭该端口。

## 4. 将现有域名的指定路径代理到服务

在宝塔“网站 → jingsen.cc → 设置 → 配置文件”中，先备份现有 Nginx 配置，再将 [nginx-location.conf](deploy/nginx-location.conf) 的两个 `location` 块加入 **现有 HTTPS `server { ... }` 内部**。

- 保留已有站点根目录、PHP／静态规则、证书和其他路径。
- 如果已经存在相同路径的 `location`，修改那一个，避免重复。
- `proxy_pass http://127.0.0.1:8088;` **末尾不加 `/`**，否则转发路径可能被截断。
- 不要通过面板再生成覆盖整个域名的 `/` 代理规则；我们只使用 `/baseballmaster/live/`。
- 保留／启用此站点 HTTPS 证书和 HTTP → HTTPS 跳转；App 生产环境只连接 HTTPS，不接受自签证书。
- 若使用 CDN，给 `/baseballmaster/live/*` 配置绕过缓存，API 同时不得缓存。不要为该路径注入分析或广告脚本。

在宝塔检查 Nginx 配置语法后重载 Nginx。通过浏览器访问：

- [观赛入口](https://jingsen.cc/baseballmaster/live/)
- [健康检查](https://jingsen.cc/baseballmaster/live/health)

入口应显示串码输入框，健康检查返回 `ok`。其他已有网页应仍正常。

## 5. 上线前验收

先使用合成数据检查 API 全链路（仅创建临时示例，测试结束自动删除）：

```sh
LIVE_BASE=https://jingsen.cc/baseballmaster/live node demo.mjs --smoke
```

应显示 `PASS: create → read → update → undo → finish → delete`。

要在手机 Safari／微信内看一场示例：

```sh
LIVE_BASE=https://jingsen.cc/baseballmaster/live node demo.mjs
```

终端会给出示例链接。保持进程运行，输入 `ball`、`undo` 可查看十秒轮询效果，`finish` 显示终场，`reopen` 恢复，`expire` 将该合成比赛设为已终场 59 分 45 秒（约 15 秒后删除），`quit` 关闭并删除。命令不显示或写盘保存发布密钥。示例不是常驻审核回放，链接不能永久使用。

最后安装本分支 App，在**测试比赛**中完成：

1. 现场记分右上角天线按钮 → 开启文字直播 → 分享链接，在另一部手机打开。
2. 记录投球、安打、换人、得分；网页约 10 秒后看到同一局面和对应打席；普通投手显示本场球数，教练模式显示本打席球数。
3. 撤销／重做，检查原打席卡片被更新而不是重复出现。
4. 断网记分，联网后确认“已同步”；App 强制关闭再启动可恢复已开启且未过期的直播。
5. 终场后等待“终场已同步”，确认网页给出删除时间；在一小时内恢复比赛测试原链接；再次终场后验证到期无法读取。
6. 手动关闭直播，确认旧链接失效、手机比赛仍在。
7. 在 Safari 和微信内置浏览器检查小屏、展开逐球记录、历史半局跳转以及“有新记录”提示。

前台网络上传已实现；iOS 不保证后台或锁屏后的持续执行，因此实际记分应保持 App 前台。部署、证书、域名 CDN 与真机微信仍需在你的服务器上线后完成，不能用本地模拟器测试代替。

## 6. 更新、回滚与维护

- 仅替换程序文件，保持 `LIVE_DB` 指向原数据目录。`pm2 restart baseballmaster-live --update-env` 或面板重启即可。停机期观众会看到重试，重启后过期场次先清除。
- 回滚程序用上一份部署包；**不回滚／恢复直播数据库**，避免恢复已经删除的比赛。若未来改变数据库格式，先安排无直播时的升级窗口；此版只包含一个初始 schema。
- 基础监控只需每分钟请求 `/health` 并检查进程、内存和磁盘；不记录观赛串码、Authorization、请求体或球员资料。健康检查路径本身无比赛资料。
- 服务默认最多同时 500 场，每 IP 每小时最多创建 30 场、每分钟最多 6000 次请求，单场写入每分钟 180 次；单份投影上限 2 MiB、10000 个时间线条目。超限会明确报错，不会裁掉本地比赛内容。
- 250 人每十秒刷新约 **25 次读取／秒**。本地验证了 250 请求集中读取无错误，但这不等于香港到生产服务器的网络表现；部署后再用真实网络检查延迟。建议从现有服务器的空闲资源开始，无需现在采购新机器。
- 服务单进程、SQLite 单库，不要直接设为 PM2 cluster 或多副本。规模明显增长时再进行数据库和发布鉴权扩展。

参考官方资料：[宝塔 Node.js／PM2 部署](https://docs.bt.cn/practical-tutorials/nodejs-pm2-deployment)、[宝塔反向代理配置](https://docs.bt.cn/user-guide/site/php/site-config/reverse-proxy)、[Node SQLite API](https://nodejs.org/api/sqlite.html)。
