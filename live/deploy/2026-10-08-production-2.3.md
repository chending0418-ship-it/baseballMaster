# 2.3 兼容文字直播部署

2026-10-08。按本轮完整交付要求，生产 `current` 原子切换至 `/www/server/baseballmaster-live/releases/2.3-2026-10-08`。保持 Node 24.21、既有 systemd 普通用户、127.0.0.1:18088、tmpfs 数据库和 Nginx／证书配置，只重启直播进程。

部署前创建自有合成旧形状场次，切换后同链接及投影保持；同一链接升级至 2.2 普通投影和 2.3 慢垒字段均验证通过。网页资源哈希与源码一致，no-store 保持，合成场次最终删除。没有读取其他用户场次，没有备份、恢复或复制直播数据库。

服务端本地 17 项、三模式本地及公网 WebKit 各 8 组通过；正常签名模拟器 App 的 HTTPS 同步、断网补传、纠错、Keychain／磁盘重启、终场／恢复、关闭删除及慢垒 Safari 阵容通过。没有声称实体射频或微信客户端测试。

源码部署器：`deploy-release-2.3.mjs`。包及 manifest：`output/deployments/live/2.3-2026-10-08/`。执行证据：`output/validation/2.3/release-acceptance/production-live/deployment.json`、`public-app/`、`public-slow-browser/`、`public-legacy-browser/`。

原 release `2.2-2026-10-03` 保留。发布 2.3 App 后，兼容维护与回退须继续接受 slowPitch 及其可选规则字段，不恢复或回滚临时数据库内容。官网更新与 Apple App 发布状态独立记录。
