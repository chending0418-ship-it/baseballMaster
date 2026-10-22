# 官网 HTTPS 上线记录

2026-09-28 已在现有服务器 `124.156.173.204` 部署并完成验证。

- 公开地址：`https://baseballmaster.cc/`，HTTP 返回 308 并跳转至 HTTPS。
- 站点根目录：`/www/wwwroot/baseballmaster.cc`，仅部署 `website/public/` 的 21 个公开文件。
- Nginx 配置：`/www/server/panel/vhost/nginx/baseballmaster.cc.conf`；对应仓库模板为 [nginx-baseballmaster-full.conf](nginx-baseballmaster-full.conf)。
- 证书：`/www/server/panel/vhost/cert/baseballmaster.cc/fullchain.pem`；私钥同目录 `privkey.pem`。目录权限 700，两份文件权限 600，所有者为 root。
- 腾讯云已签发证书覆盖 `baseballmaster.cc` 和 `www.baseballmaster.cc`，签发者为 TrustAsia DV TLS RSA CA 2024。有效期至 **2026-12-27 10:59:59（北京时间）**。
- 仅根域名 DNS 已验证。`www` 尚无 A 记录；已预留证书与跳转配置，但此次未新增 DNS 记录。
- 旧站点配置、证书及私钥的 SHA-256 均与部署前一致；旧站点 HTTPS 仍返回 200。
- 文字直播未部署，相关路径目前返回 404，App 的直播开关保持关闭。

本次通过 SSH 安装 Nginx 独立站点文件，未写入宝塔网站管理数据库。后续直接维护上述配置文件；如需纳入宝塔网站列表，应先备份并导入现有站点，避免“新建网站”覆盖当前路由或证书。

## 验证

`nginx -t` 通过后平滑重载。公网直连和普通域名访问均返回 HTTPS 200，证书链与主机名验证成功，首页内容与本地最新文件逐字节一致。浏览器已打开线上官网。

首页、隐私页、支持页和图片可访问；`/TODO.md`、`/README.md`、`/.git/config`、`/.env`、`/privkey.pem`、`/assets/` 均返回 404。只发布官网文件，不发布源码和部署资料。

证书 SHA-256 指纹：`AF:E4:F9:C9:DF:D4:90:B5:65:31:78:29:82:3F:07:9E:97:22:05:1F:05:71:49:65:EB:EF:69:FB:2C:F8:00:1D`。

验证记录：`output/deployments/website/baseballmaster-home/ssl-deploy/verification.json`。服务器端安装暂存、原配置和校验记录：`/root/baseballmaster-deploy/20260928-ssl/`，仅 root 可访问。

## 后续换证

**本证书目前为手动安装，未配置自动续期。** 到期前需在腾讯云取得新证书，核对域名、有效期、完整证书链及私钥匹配，备份当前文件后替换上述 `fullchain.pem` 和 `privkey.pem`，保持权限，再执行 `nginx -t`、`nginx -s reload` 和公网验证。预留 ACME 验证路径不代表已有自动续期任务。

证书和私钥不进入 Git、官网目录或公开 ZIP。腾讯云下载包保存在用户 Downloads，文件权限已设为 600。
