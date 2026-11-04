# 开屏背景上传修复：准备完成，待部署授权

日期：2026-11-04（北京时间）。归属：记录大师 iOS／共用服务。分支：`codex/fix-launch-upload-20261104`，PR 待创建，目标 `main`；App 保持 3.1.2（Build 1）。

## 已确认的原因

公网向 `/admin/api/launch-screen/image` 提交 8192 字节、无认证的合成请求得到 HTTP 413、`text/html`；同样请求经上海回环地址直接访问球队后台则得到 HTTP 401 JSON。当前 `/etc/baseballmaster-production/routes-team.inc` 的 `/admin/` 统一限制为 `client_max_body_size 4k`，页面及应用服务均允许 3 MiB 图片；前端直接调用 `response.json()`，因此把网关 HTML 误当成 JSON。

## 已实现

- `collaboration/deploy/nginx-v3.conf`：仅为图片上传添加精确匹配路由，3 MiB 上限并关闭请求缓冲；其他后台接口保持 4 KiB，认证、来源和应用层图片校验继续生效。
- `collaboration/admin/public/app.js`：非 JSON 响应使用中文提示，413 指明大小／服务器上传限制，其他状态附 HTTP 状态；HTML 401 也返回登录页，后台 JSON 错误继续显示原文。
- `collaboration/admin/public/launch.js`：上传失败后清空文件选择，允许重新选择相同文件重试；原发布配置和预览保留。

## 本次验证

- `node --test collaboration/test/launch-screen.test.mjs collaboration/test/admin.test.mjs`：19／19 通过。
- 现有 `scripts/validation/validate-launch-editor.cjs` 增补浏览器场景：10 组检查通过，覆盖 HTML 413／502、同文件重试成功、上传预览、发布、北京时间、桌面／手机、停用／恢复默认及 HTML 401；只访问本机合成服务。
- `scripts/validation/validate-launch-upload-gateway.py`：在上海临时回环高端口运行独立 Nginx 和合成上游，5 项通过：图片 4097 字节及恰好 3 MiB 抵达上游，3 MiB＋1 返回 413；其他后台请求 4096 字节放行、4097 返回 413。测试进程和临时目录已退出清理；生产 Nginx 配置及服务未更改。
- 证据：登记的 iOS 主工作目录下 `output/validation/3.1.2/launch-upload-2026-11-04/{summary.json,browser-checks.json,gateway-checks.json}`。

## 已准备的上线范围

补丁包位于登记的 iOS 主工作目录 `output/deployments/collaboration/launch-upload-20261104/`，保存当前线上路由原文／摘要、两份 JS 线上基线摘要及修订后文件。两份线上 JS 与本轮修复前 Git 内容一致。

获得部署授权后，仅修改上海：

1. `/etc/baseballmaster-production/routes-team.inc` 增加精确上传路由，代理继续指向 `127.0.0.1:18788`；保留现行其他全部路由。不能用历史香港完整配置覆盖上海配置。
2. `runtime.json` 指向的 `codeRoot` 内 `team/collaboration/admin/public/{app.js,launch.js}` 更新至本补丁。先核对全部源码清单无未解释漂移，备份路由、JS、源码清单及 runtime；更新源码清单并同步其 runtime 摘要。现网服务动态读取 JS，无需重启球队服务。
3. 先对实际 Nginx 配置执行 `nginx -t`，通过后按专用 PID／配置平滑 reload。部署异常恢复上述文件和清单，检查语法后 reload；不恢复旧数据库。
4. 公网验证 8 KiB／3 MiB 无认证请求得到 401 JSON，3 MiB＋1 得到 413，其他后台接口仍保持 4 KiB；核对两份 JS 公网摘要、服务健康与原公开开屏配置未变。合成大请求仅用于验证网关，不能据此宣称已认证图片入库／发布通过；实际图片发布由管理员操作。

当前尚未部署／合并，未改 DNS、真实数据库或 App 二进制。部署与 Git PR 合并分别等待用户授权；本地通过不代表线上问题已消失。
