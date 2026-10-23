# 3.1.1 可配置开屏

2026-10-23。用户授权“BM 和 App 图标固定，背景图和文案可以在后台配置”，并明确“先开发，不做任何的部署，我们正在做服务器的迁移”。本功能只在本机开发与合成数据环境验证，工程保持 **3.1.1（Build 1）**。

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

本功能开发未连接生产服务、未部署、未修改 DNS、未复制生产数据、未上传或提交审核。服务器迁移由既有流程独立进行，本功能没有修改迁移规划或脚本。

后续另行授权发布时，服务包需包含新 `launch-screen.mjs`、更新的 server/admin 入口、`admin/public/launch.js` 和固定预览资源。迁移中已经确认的旧基线不自动改为本功能版本；不能只更新 server.mjs 而漏掉新模块。此说明不是部署命令或部署授权。
