# 上海 HTTPS 自动续签与自动部署（2026-11-05）

用户授权配置 baseballmaster.cc 自动续签和自动部署，上海 101.43.112.8 已完成。使用 Let's Encrypt 免费 DV 证书、Ubuntu Certbot 2.9.0 和 systemd；无需腾讯云 API 密钥。用户已添加 www A 记录指向上海。香港 jingsen.cc 未作配置变更。

## 当前状态

SAN 为 baseballmaster.cc、www.baseballmaster.cc；签发链 YR1，序列号 06E8C5B8560F7935B4FED1F02AC90F01E660；有效至北京时间 2027-02-02 23:05:55。旧 TrustAsia 证书并未被吊销；已在 root 私有目录 /var/backups/baseballmaster-tls/20261104T160429610653Z/ 备份。

自定义 baseballmaster-certificate-renew.timer 已启用，每日北京时间 03:00、15:00 检查，加 0–30 分钟随机延迟，支持错过后补执行；此次新安装的通用 certbot.timer 已停用，避免重复。近到期才申请，不会每次检查都签发。首次 oneshot 执行成功，下一次为 2026-11-05 03:01:59 CST。

## 自动化与文件

- Certbot 续签配置：/etc/letsencrypt/renewal/baseballmaster.cc.conf；私钥仅在服务器受限目录，禁止进 Git。
- 签发方式：HTTP-01 webroot；两个现有 ACME location 单独指定 /var/lib/baseballmaster-acme-webroot/，不把临时验证文件写进受完整性保护的网站源码。其余 Nginx 路由保持；80/443、两个域名 DNS 及 ACME 路径需持续可达。
- 部署 hook：/usr/local/sbin/baseballmaster-deploy-certificate，源码见 ../../scripts/operations/deploy-renewed-certificate.py。校验证书信任链、两域名、余期和私钥匹配，加锁、备份旧证书对、替换现行 /etc/baseballmaster-production/tls/ 下文件，nginx -t 后只平滑 reload 专属网关，校验实际 TLS 叶证书与首页 HTTPS；失败恢复旧文件并 reload。
- 定时 service 除运行 certbot renew，还在成功后核对／补部署，处理曾经签发成功但 hook 部署失败的情况。已部署则不重复备份和 reload。旧线上证书过期不阻止修复。
- 最近成功部署回执：/var/lib/baseballmaster-acme/last-deployment.json；定时器及脚本源码同目录 scripts/operations/。失败可从 journalctl -u baseballmaster-certificate-renew.service 和 /var/log/letsencrypt/letsencrypt.log 排查，未配置外部告警。

## 验证

主域签发演练成功；随后两域正式签发、hook 部署、严格 HTTPS 验证成功。certbot renew --cert-name baseballmaster.cc --dry-run --run-deploy-hooks --no-random-sleep-on-renew 成功，部署 hook 在演练后正确核对当前正式证书，没有部署测试证书。systemd 单元语法验证通过，timer enabled/active，oneshot Result=success、ExecMainStatus=0。从香港另一服务器访问两域，严格 TLS 校验结果均为 0，www 跳转后的最终首页均 200。没有 App 版本、业务代码或数据库变更。

## 手动恢复与重建

部署源码安装为 root:root 0700；两个 systemd 单元安装至 /etc/systemd/system/ 后 daemon-reload，再启用专属 timer。若重建机器，先恢复专属 Nginx、为两个 ACME location 设置上述 root，然后使用：

```sh
certbot certonly --webroot -w /var/lib/baseballmaster-acme-webroot \
  --cert-name baseballmaster.cc -d baseballmaster.cc -d www.baseballmaster.cc \
  --key-type rsa --rsa-key-size 2048 --agree-tos \
  --register-unsafely-without-email --non-interactive \
  --deploy-hook /usr/local/sbin/baseballmaster-deploy-certificate
systemctl enable --now baseballmaster-certificate-renew.timer
```

本次 ACME 账户未绑定邮箱，不提供邮件通知保证；故障查看日志与 timer 状态。日常核查可执行 systemctl list-timers baseballmaster-certificate-renew.timer。需要手工恢复旧证书时，先核对备份仍有效及无后续操作，暂停专属 timer，恢复备份的 fullchain.pem 与 privkey.pem（root 0600），专属 nginx -t 后 reload，并严格验证两域。备份旧证书到期后不可作为有效回滚目标；不要运行通用 nginx.service 或覆盖其他服务配置。

腾讯云当前这两域对应的旧证书已不再用于上海站点；自动续费可由用户在确认后关闭，不删除或吊销历史证书。证书源文件、私钥与 ACME 账户均不进 Git。
