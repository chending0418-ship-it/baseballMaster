# 2.1 文字直播正式部署与模拟器验收

日期：2026-09-30。在 `codex/release-2.1` 完成，随后合入 main。**Web／API 已上线，2.1 App 开关已启用；App 尚未上架，未上传或提交苹果审核。**

入口：[文字直播](https://baseballmaster.cc/livestreaming/novideo/) · [健康检查](https://baseballmaster.cc/livestreaming/novideo/health)。开播需使用支持直播的 2.1 App。官网、隐私和技术支持页面均同步为“Web 服务已部署、App 尚未发布”。

## 部署结果

| 项目 | 结果 |
| --- | --- |
| 服务器 | 现有腾讯云／OpenCloudOS 9.4，内核 6.6，沿用现有 HTTPS 证书 |
| Node | 官方 24.21.0 Linux x64，SHA-256 `fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6` |
| 监听 | `127.0.0.1:18088`；既有 gunicorn 8088 保持原样 |
| 守护 | systemd，专用不可登录用户 `baseballmaster-live`；已验证 active、enabled 及重启 |
| 程序目录 | `/www/server/baseballmaster-live/current`，独立发布目录和只读程序文件 |
| 数据目录 | `/www/server/baseballmaster-live-data`，tmpfs + noswap，700；SQLite 600 |
| 资源限制 | tmpfs 1536 MiB；进程 2 GiB；禁用 swap、core dump |
| Nginx | 只增加文字直播前缀代理；检查语法后 reload，保留官网静态白名单及敏感路径 404 |
| 日志 | 域名 HTTP／HTTPS／www 跳转关闭路径日志；Node 只记录启动及错误类别，不记录串码、凭证、球员资料 |
| 缓存 | 页面／API no-store、no-referrer、noindex；代理缓存关闭，直连解析，不经过 CDN |

数据挂载失败时服务不会启动，不会降级写入磁盘。内存数据库不会进入云磁盘快照；已检查现有主机定时任务，未发现直播目录备份。今后增加文件备份或同步时必须继续排除该目录。服务进程重启保留当前链接；整台机器重启清空直播，需重新开播和分享。没有重启整台服务器以验证，避免影响共用服务器的其他服务；此行为来自内存挂载机制，已写入公开说明。

## 验收

- 服务核心测试：本地与 Linux 生产运行时各 **13 项通过**，涵盖权限、快照白名单、过期／失联、重启清理、冲突及并发写入。
- 公网冒烟：创建 → 读取 → 更新 → 撤销 → 终场 → 主动删除。
- 公网安全与生命周期：匿名／错误发布凭证拒绝，250 个并发读取成功；服务进程重启后原链接可读；终场心跳不延长删除期限；合成场次到期 API 404、观赛页 410。内部源码、Git 文件和数据库不可访问。
- 正式域名 WebKit：375 浅色／深色、320 窄屏、1440 桌面四组通过。更正、撤销／重做、当前棒次、教练模式、历史阅读、新记录提示、终场、关闭均检查；品牌沿用已确认官网组合。
- 官网／隐私／支持／观赛入口：390 和 1440 两档共八组检查通过，无横向溢出。
- iPhone 17 Pro Max／iOS 26.1 模拟器：默认生产 HTTPS 客户端发布合成比赛，开播、二维码和系统分享菜单；投球、撤销重做；断网本地记分及恢复补传；纠错草稿不上传、保存后同步；杀掉 App 后用磁盘比赛与 Keychain 恢复原链接；终场、恢复继续沿用原链接；主动关闭后 API 404，本地比赛仍可操作。
- 断网通过 DEBUG 中受控的 `URLError.notConnectedToInternet` 注入，健康网络仍直连正式 HTTPS；该控制按钮、合成数据和测试启动参数均不进入 Release。没有模拟实际蜂窝／Wi-Fi 切换或微信客户端。
- App 回归：**161 项单元／集成测试通过，5 项相关 UI 场景通过**。包含纠错预览保存、守位／失误编辑、分享、隐私说明和正式服务联调。旧关闭功能测试改为显式禁用模式；新增稳定的投球编辑控件标识。
- iPhoneOS Release 编译通过（未签名），隐私清单 plist 格式通过；未归档、上传或提交审核。

## 文件和证据

所有本机生成文件按项目目录约定保存，不散放根目录：

- `output/deployments/live/2.1-2026-09-30/`：运行时校验文件、部署包及程序清单。
- `output/backups/live/2026-09-30/`：部署前 Nginx、官网页面备份；服务器也保留专用站点配置备份。不备份直播数据库。
- `output/validation/2.1/live-deployment/`：公网检查、浏览器截图、服务器配置审计、Release 日志及 xcresult。
- 最新模拟器流程：`simulator-final.xcresult`；161 项与隐私 UI：`simulator-retest.xcresult`；纠错编辑和本地分享 UI：`simulator.xcresult`。前两轮发现的旧测试预期／控件定位失败保留为排查证据，以后续通过的结果为准。

复测命令见 [运行维护说明](../README.md)。公网加强检查为 `LIVE_BASE=https://baseballmaster.cc/livestreaming/novideo node live/test/deployment.mjs`；加 `LIVE_RESTART_SSH_HOST=jingsen-prod` 会实际重启直播服务，只在维护窗口或确认无真实直播时使用。所有公网测试只使用合成资料，发布凭证仅在进程内，不写入报告或命令行。

## 更新与回滚

1. 将新程序解压到独立 release 目录，root 管理，专用用户只读。使用同一个数据路径，不复制数据库。
2. 原子切换 `current` 链接后 `systemctl restart baseballmaster-live.service`，核对健康与原链接。保存旧程序 release 用于回滚。
3. 失败时切回上一份程序，重启服务；**不得恢复直播数据库**。需要撤销代理时恢复 `/www/server/baseballmaster-live/config-backups/baseballmaster.cc-2026-09-30.conf`，检查 `nginx -t` 后 reload。
4. 不同时运行 systemd、PM2 或多个副本；不开放 18088 公网端口。升级 Node 时先校验官方文件、测试兼容性，再更新 service 的固定路径。

## 发布前仍待完成

- 真机蜂窝／Wi-Fi、Safari／微信内分享和实际比赛操作；模拟器不等同于真机。
- App Store Connect 隐私问卷及审核资料，见 [申报草稿](../../docs/releases/2.1/APP-PRIVACY.md)。
- 真机旧数据覆盖升级、最终签名归档、宣传素材核对和用户安排后的审核提交。
- 现有 HTTPS 证书在 2026-12-27 到期，仍需续期；旧站点内部资料访问控制沿用既有 TODO。
- 本次 2.1 合入 main；2.2 工作区有进行中的未提交慢垒规划，未自动覆盖或合并。
