# 3.0 协作服务部署记录

2026-10-12–13，用户明确授权同步上线网站及服务。`https://baseballmaster.cc/api/v3` 已部署，Node 24.21.0、独立 SQLite、独立服务用户与持久目录。Nginx 仅新增本域名 `/api/v3/`、`/join/`、`/pair/`、`/watch/` 路由；原直播与其他业务保持。

程序发布包与安装日志：`output/deployments/collaboration/3.0-20261012/`。初始 tar 中静态路由的 allowlist 仅允许 style.css，部署核查发现邀请页实际使用 join.css，已修正为 `[a-z-]+\.(js|css)` 并通过 Nginx 检查和公网验证；当前权威配置是本目录 `nginx-v3.conf` 与线上 `baseballmaster-v3.inc`，不要用原 tar 的旧配置覆盖。

正式服务 20 场完整合成比赛、30 并发读取、App 原生 HTTPS、角色负向验证与 WebKit 观看授权全部通过。已完成服务重启保留、独立 COS 加密备份／恢复、删除和撤权不复活验证。首轮检查器误期待成员写入返回 403，实际正确返回 writer_changed 409；已修正重跑。清理脚本最后使用了多余 `/api/` 的旧直播健康 URL，已修正为 `/livestreaming/novideo/health` 后通过；清理和备份此前已成功，未重复解散。

验证球队全部解散、旧访问关闭，验证凭证已移除。保留合成记录至恢复期结束，不进行可能影响其他用户的全库删除。旧临时直播库未纳入备份。独立密钥不在发布包或 Git 中。

实际目录、参数、恢复与回退说明见 [运行手册](../../docs/releases/3.0/OPERATIONS.md)；测量见 [容量记录](../../docs/releases/3.0/CAPACITY.md)。服务已上线不代表 App 已提交审核。
