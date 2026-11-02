# 3.1.1 可配置开屏

2026-10-23 初始开发范围：用户授权“BM 和 App 图标固定，背景图和文案可以在后台配置”，并明确“先开发，不做任何的部署，我们正在做服务器的迁移”。当时只在本机开发与合成数据环境验证。2026-11-02 按后续授权完成香港服务增量部署，见文末记录；工程保持 **3.1.1（Build 1）**。

## 用户体验

- 系统 Launch Screen 保留深绿背景、固定 BM 组合和 App 图标，系统阶段不显示可变文案，避免先闪出旧文案。
- App 内开屏沿用同一构图，BM 与 App 图标的图形、颜色、尺寸和位置不接受后台配置。文案为空或显示两行时，App 图标也不移动。
- 背景等比例铺满，叠加固定 66% 深色遮罩，保持白色品牌与浅色文案在亮图上的可读性，后台提供相同预览。
- 默认显示 0.8 秒，可设 0.3–2 秒，用户随时可跳过。只在冷启动显示，回前台不重播；邀请／配对链接到来立即结束开屏，资料恢复页优先显示。
- 启动只读本地已验证的内容；网络在后台更新配置与图片，下次启动采用。当前画面不会下载一半换图，也不等待网络结束才进入首页。
- 首装或缓存损坏时使用内置深绿版本；下载失败不覆盖有效缓存。未生效／已到期配置回到内置版本；已缓存的“关闭”配置直接进入首页。

后台新增“开屏配置”：上传／默认背景、文案、启停、北京时间有效期、显示时长与发布。恢复默认先填回默认内容，点击发布才生效。球队、比赛和直播业务资料仍为只读。

## 配置边界

| 项目 | 约束 |
| --- | --- |
| 背景 | 静态 PNG／JPEG，最多 3 MiB、1600 万像素，单边上限宽 4096／高 8192；建议竖图 1206 × 2622 |
| 文案 | 最多 40 个 Unicode 标量、两行，允许留空，按纯文本显示 |
| 时长 | 300–2000 ms，默认 800 ms |
| 时间 | UTC 毫秒；后台按北京时间输入；开始包含、结束不包含 |
| 更新 | 管理员发布后，设备先成功下载，在下一次冷启动采用 |

## 接口与持久化

App 实现在 `BaseballMaster/ConfigurableLaunchScreen.swift`，固定图形来自 App 自有 `LaunchIdentity` 与 `LaunchAppIcon`。现有保存、恢复、深链接和前后台服务处理保留。

后端 `collaboration/launch-screen.mjs` 挂接现有协作服务和总后台，无新外部服务或第三方依赖。客户端默认地址为 `https://baseballmaster.cc/api/v3/app/launch-screen`，沿用域名，不绑定迁移前后的 IP。旧服务尚无此接口时，App 静默使用缓存／内置版本。

| 接口 | 权限与作用 |
| --- | --- |
| `GET /api/v3/app/launch-screen` | 公开，无业务凭证；配置禁用 HTTP 缓存 |
| `GET/HEAD /api/v3/app/launch-assets/<sha256>.png或.jpg` | 公开背景图，按内容哈希寻址、不可变缓存，支持 ETag |
| `GET /admin/api/launch-screen` | 现有网站总后台会话，读取配置 |
| `PUT /admin/api/launch-screen` | 同一会话、严格同源检查、revision 冲突保护 |
| `POST /admin/api/launch-screen/image` | 同一会话与同源检查，原始 PNG／JPEG 请求体 |

配置协议：`schemaVersion:1`、`revision`、`updatedAt`、`enabled`、`message`、`displayDurationMs`、`startsAt`、`endsAt`、`backgroundImage`。时间可为 null；背景可为 null，或含 `path`、`sha256`、`mimeType`、`byteCount`、`width`、`height` 的服务器生成描述。不采纳客户端传入的外站地址。

App 限制相对路径、同一主机、HTTPS、大小、哈希、格式和尺寸，ImageIO 实际解码通过后才采用，禁止 HTTP 重定向。配置请求不附带业务凭证或后台 Cookie。

缓存位于 App 的 Caches/AppLaunchScreen，不进入用户资料备份。图片先按内容哈希原子写入，再原子切换配置，保留前一份图片。每次启动重新判断有效期，断网也不会继续展示已过期内容。

服务新增 `app_launch_settings`、`app_launch_assets` 两张 SQLite 表，沿用全库备份。保留已发布背景与最近八个候选素材，最多九张；恢复核对状态和磁盘预留不足时拒绝修改。球队导出仍只含业务资料，原业务协议和 schema 版本保持兼容。

## 本地验证与迁移边界

证据在 `output/validation/3.1.1/configurable-launch/`，最终结果见 [开发记录](DEVELOPMENT.md)。可复现命令：

```sh
node --test collaboration/test/*.test.mjs
node scripts/validation/validate-launch-editor.cjs
```

`serve-launch-fixture.mjs` 只创建合成数据与回环 HTTP 服务，用于模拟器真实下载／缓存／断网联调。Debug 的 `--launch-screen-local-preview` 仅接受回环 URL、固定路径与 UUID 隔离缓存；Release 不含此参数入口。其他开发预览及 XCTest 不请求生产配置。

初始本地开发阶段未连接生产服务、未部署、未修改 DNS、未复制生产数据、未上传或提交审核。服务器迁移由既有流程独立进行，本功能没有修改迁移规划或脚本。后续服务部署以文末实际记录为准。

后续另行授权发布时，服务包需包含新 `launch-screen.mjs`、更新的 server/admin 入口、`admin/public/launch.js` 和固定预览资源。迁移中已经确认的旧基线不自动改为本功能版本；不能只更新 server.mjs 而漏掉新模块。此说明不是部署命令或部署授权。


## 2026-11-02 部署准备与暂停断点

用户本日授权完成可配置开屏并部署，固定现有 URL；备案号、上海真实迁移、迁移后隐私／联调及最终 App 审核继续等待。拟在现有香港协作服务增量部署，不切上海、不改 DNS／Nginx、不更新独立直播或学习网站。随后用户要求“先暂停一下，晚点继续”，本轮已停止，不继续任何部署或验证。

- 当前分支 `codex/dev-3.1.1`，基线提交 `4ba620c`。已有其他用户未提交的发布／迁移资料保留；本次新增和修改未提交。
- 后端全部 **101 项测试通过，0 失败／跳过**；新增开屏配置和图片的实际加密全库备份恢复回归，未修改运行模块。测试文件 `collaboration/test/launch-screen.test.mjs`。
- 本地桌面／手机后台浏览器测试通过：登录、上传、预览、北京时间排期、发布、启停与恢复默认。因恢复后的机器缺 Playwright 自带 Chromium，改用已安装 Google Chrome；验证脚本支持浏览器路径和输出目录参数，启动失败也会关闭合成服务。图片已目视核对。
- 生产只读基线：香港当前协作版本 `/www/server/baseballmaster-collaboration/releases/3.1.0-20261020`，48 文件，开屏两表尚不存在；协作、独立直播和学习网站均 active。沿用现有 `jingsen-prod` SSH 配置，未读取或输出秘密。
- 新脚本 `collaboration/deploy/deploy-launch-3.1.1.py` 默认只准备包，`--execute` 才部署。9 个运行文件包、哈希清单、候选安装与只回退代码的方案已准备并经独立审查；已修复 stop 命令异常时必须恢复服务的处理。计划停协作写入后仅初始化两张开屏表，核对旧表摘要，保留业务和所有旧配置；前后运行现有加密备份。
- **尚未部署。** 第一次执行在 SSH `install -d` 暂存目录步骤 30 秒超时，未执行 SCP、运行安装脚本、切换程序或重启服务。暂存目录是否创建未进一步核实。后续 SSH 连通核对退出 255，三次公开 HTTPS 检查也均连接失败，不能据此判断生产服务故障或宣称仍已实测健康。暂停前未开始新的生产操作。

续做入口：先重新核实本机网络、SSH 与公开 HTTPS，再重读生产版本、保护文件哈希及服务状态；确认仍符合基线后才继续。不要把本次本地成功当作生产已发布；若基线变化先复核再生成包。部署继续沿用固定 `https://baseballmaster.cc/api/v3/app/launch-screen` 和 `https://baseballmaster.cc/admin/#launch`；配置与背景数据保存在原协作库，未来迁移需把本次实际安装版本和两表加入候选核对。

证据：`output/validation/3.1.1/launch-deployment-20261102/`（测试、桌面／手机截图、公开连接失败记录）；`output/deployments/collaboration/3.1.1-launch-20261102/`（只读 baseline、9 文件 manifest、overlay、install.py、首次 SSH 超时日志）。已保留失败证据和所有未提交修改。

## 2026-11-02 恢复与香港服务部署

用户恢复工作后继续此前已授权的开屏服务部署。恢复时公开 capabilities 返回 200、SSH 恢复；再次只读获取现网版本、48 个运行文件哈希、3 个保护配置哈希、服务 PID、表清单和 Node 版本，与暂停前 baseline 全部一致。旧暂存目录与候选 release 均不存在，确认此前超时未留下运行变更。

- 北京时间 **2026-11-02 19:17:32** 安装完成，现行服务为 `/www/server/baseballmaster-collaboration/releases/3.1.1-launch-20261102`，原 `3.1.0-20261020` 完整保留。
- 包仅覆盖 9 个开屏运行文件；43 个未替换文件哈希一致，20 个网站／配置保护文件通过核对。仅停止和启动协作服务，服务初始化至健康恢复窗口为 **5.271 秒**。
- 停写期间恰好新增 `app_launch_settings`、`app_launch_assets` 两表；旧业务表 schema 与全部行摘要一致，后续 SQLite 完整性检查为 `ok`。没有恢复旧数据库或迁移生产资料。
- 部署前后现有加密备份任务均成功（19:17:18、19:17:29）。协议仍为 `protocol:1`、`matchFormat:2`；协作服务 active，独立直播与学习服务 active 且 PID 未变。
- DNS、Nginx、环境配置与 systemd 配置未修改；没有切换上海、填写备案号、上传 App 或提交审核。配置地址固定为 `https://baseballmaster.cc/api/v3/app/launch-screen`，后台固定为 `https://baseballmaster.cc/admin/#launch`。
- 部署脚本收紧为必须恰好新增两表，stop 失败及回退切换异常时确保尝试启动服务；手动代码回退在停服务前检查路径，并用 finally 恢复启动。回退仅切换旧代码，保留新表及部署后的写入；本次未实际执行回退。

公网分项结果见 `output/validation/3.1.1/launch-deployment-20261102/public-after.json`。首次部署后 curl 已成功读取开屏配置、capabilities、后台入口及 launch.js；官网根页面一次请求 15 秒超时，未重试，不据此声称官网根页验收通过。本机 Python urllib 缺本地 CA 链，改用保持证书验证的系统 curl；未放宽 TLS。

本地部署证据：`output/deployments/collaboration/3.1.1-launch-20261102/baseline-resumed.json`、`manifest.json`、`install.py`、`installed.json`、`postflight.json`、`rollback-code-only.py` 和 `deploy-resumed.log`。未来真实迁移需纳入本次 release、开屏两表及同库背景素材；上海迁移和备案仍等待。没有提交本地改动。
