# 3.0 服务运行、备份与恢复

2026-10-13。已部署到既有 `baseballmaster.cc` 服务器。以下是运维交接，不是对外无限保存、吞吐或故障恢复时长承诺。所有密钥均在材料包及 Git 之外。

## 当前部署

| 项目 | 实际配置 |
| --- | --- |
| HTTPS | `https://baseballmaster.cc/api/v3`，协议 1 |
| 服务 | `baseballmaster-collaboration.service`，独立 Linux 用户 `baseballmaster-team` |
| 当前程序 | `/www/server/baseballmaster-collaboration/current` → `releases/3.0-20261012` |
| 运行时 | `/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node` |
| 数据库 | `/www/server/baseballmaster-collaboration-data/collaboration.sqlite`，权限 0600 |
| 监听 | 仅 `127.0.0.1:8788`，由 Nginx HTTPS 转发 |
| 环境配置 | `/etc/baseballmaster-collaboration.env`，root 0600 |
| 网页 | `/www/wwwroot/baseballmaster.cc`；`join/`、`pair/`、`watch/` 与首页／隐私／支持已上线 |
| 路由 | `/www/server/panel/vhost/nginx/baseballmaster-v3.inc`；仅加入本域名 HTTPS 块 |
| 原直播 | `127.0.0.1:18088` 与 `/livestreaming/novideo/` 保持原程序及原临时库；没有复制或长期备份旧临时库 |

程序和数据库分目录；重启程序不切换数据文件。生产验证只创建合成资料，验证球队均已解散，30 天后自动清理；临时验证凭证已从电脑删除。没有修改其他站点或 `/lifan/tryout/`。

## 限额与保留

- 每队最多 5,000 场未彻底清除的比赛，比赛正文、网页投影和冲突草稿合计 2 GiB；达到限额时整次请求拒绝，提示先导出并联系支持，不截断记录。
- 每队最多两名有效管理员（按成员计，不按配对设备计），200 位活动成员、200 份有效邀请；每位成员每队最多 5 台设备。现役球员 200、停用球员 2,000；设备登记全局 100,000。
- Nginx 请求体上限 18 MiB，业务层另校验解压内容和比赛格式。生产每 IP 每分钟 600 请求，恢复猜测另有更严格限制。只信任回环代理传入的真实 IP。
- 主盘可用空间低于 2 GiB 时拒绝新写入；监测阈值为 10 GiB。失败不会向 App 返回已同步。
- 入队邀请 24 小时、配对 10 分钟、单次使用；凭证轮换准备 24 小时，App 每 90 天尝试更新认证秘密，存储加密钥匙独立保留。
- 删除比赛和解散球队保留 30 天恢复期。日常维护清除过期正文／关联／草稿，保留最小删除与旧操作标记；游标压缩后，旧客户端全量重建并保留个人草稿。
- 历史永久开放无自动到期；关闭观看不删除。原实时链接仍有原终场一小时／无心跳到期规则。

## 独立备份与监测

每日 04:00（Asia/Shanghai，随机延迟最多 5 分钟）运行 `baseballmaster-team-backup.timer`。先生成一致 SQLite 快照并压缩，以 AES-256-GCM 加密，重新解密并校验完整性后才确认成功。保留最近 30 天 `daily-*.sealed`；临时文件或其他目录不随清理删除。

备份存于独立 Tencent COS：`/lhcos-data/baseballmaster-private-v3/backups`；删除／撤权日志存于同一独立存储下的 `journal`。业务撤销事务在数据库确认前先写入加密日志；独立存储或身份核验失败时拒绝确认该事务，不能悄悄跳过。备份使用压缩后的完整快照，不宣称跨日期块去重或增量备份。

密钥：服务器 `.../baseballmaster-collaboration-data/backup.key`（0600）；独立电脑保管副本 `/Users/JasonChan/.config/baseballmaster/production-3.0-backup.key`（0600）。不要将其复制到官网、Git、审核包、邮件或聊天。新服务器灾备需要密钥和 COS 最新日志；丢失密钥不能解密。COS 挂载显示的 256 TiB 为文件系统虚拟容量，不是套餐额度。

`baseballmaster-team-monitor.timer` 每 15 分钟检查健康、磁盘、最近备份不超过 26 小时以及备份文件大小；失败写入 systemd 状态／日志。当前没有配置短信、邮件或聊天推送，不将其写成已具备外部告警。可查看：

```sh
systemctl status baseballmaster-collaboration.service
systemctl list-timers 'baseballmaster-team-*'
journalctl -u baseballmaster-team-backup.service -u baseballmaster-team-monitor.service --since today
```

备份／监测服务是 oneshot，成功后显示 inactive 且退出码 0 属正常；timer 应为 active。持久 timer 支持服务器错过计划后的补跑。

## 恢复流程

1. 停止共享写入，保留故障主库及 WAL／SHM，不直接用旧文件覆盖在线库。确认 COS 挂载、备份来源、最新撤销日志与密钥可用。
2. 在隔离目录将备份恢复到**不存在的新文件**，使用当前兼容程序 `backup.mjs restore INPUT OUTPUT`，环境提供 `BM_TEAM_BACKUP_KEY` 和 `BM_TEAM_SAFETY_JOURNAL`。工具拒绝覆盖现有文件，验证加密和数据库完整性。
3. 有完整日志时，先重放删除／撤权，生成新恢复代次，关闭全部旧直播／历史链接、邀请和配对，阻断旧操作重放。日志缺失时只产生隔离库，不能启用；损坏或错误密钥直接拒绝恢复。
4. 在隔离服务核对行数、代表性完整比赛、删除和已移除成员。確認 `quarantined=false`、新恢复代次、需要设备核对。停止服务后再切换已验证数据库路径，保持原故障库只读备查。
5. App 重连后先保存本机较新内容为待核对草稿，停止旧队列，拉取正式资料并明确核对。不会自动把旧备份上传覆盖球队；恢复完成不自动重新开放历史链接。
6. 验证读写、只读成员、关闭网页和旧设备拒绝结果，创建新的恢复点，再恢复正常运行。

本次从独立 COS 恢复至隔离数据库的演练通过：约 35.0 MB 数据，恢复操作 1.59 秒；删除比赛仍删除、被移除成员仍失效、全部外部链接关闭、设备须明确核对。该时间只包含解密／落盘／重放，不代表换机、下载、排障和恢复服务的全部 RTO。日备份目标 RPO ≤24 小时；运行处置目标 RTO ≤24 小时，未宣称完整灾难换机已实测到此目标。主机重启丢失风险通过持久 DB 与程序服务重启验证；未对承载其他业务的整台生产主机断电。

## 发布与回退

先部署兼容服务与网页，再上传 App。旧 2.x 临时直播继续原路径；新共享数据使用独立库。回退代码只切换到已验证能读取当前 schema／协议的程序，不能降回不理解新语义的版本；禁止通过恢复旧库“回退代码”。本版为协作服务首次上线，暂无更老的兼容生产版本：需要暂停时停止该服务并返回维护状态，保留数据库、日志与 App 本机草稿，修复后向前部署。App 旧版本遇到不支持格式应只读／要求更新，不能写回旧格式。

静态网页每文件原子替换，部署前文件位于 `/root/baseballmaster-deploy/20261012-website-3.0/previous`；对照 installed.json 恢复匹配文件，不覆盖此后新修改，不动其他网站。生产 Nginx 原配置备份 `/root/baseballmaster-deploy/nginx-before-v3-20261012.conf`；撤下新入口前须先停止新共享写入，不能删除长期数据。

套餐标称带宽、流量计费和真实 30 天增长仍需日常运维观察；本次没有更改云资源套餐或付费购买。测量见 [容量记录](CAPACITY.md)。

### 2026-10-13 权限补丁部署

当前代码目录为 `/www/server/baseballmaster-collaboration/releases/3.0-20261013-roles`，`current` 已切换；未变更数据库结构、运行配额、密钥或 nginx 配置。部署前一致性加密备份完成，旧代码目录保留。服务端新增两名管理员限制，允许记录员新增／修改球员；名单权限不会授予历史身份关联、球队资料或停用权限。回退旧程序会失去这些新规则，应优先向前修复。部署证据为 `output/validation/3.0/roster-permissions/deploy.log` 与 `production.json`。
