# AN06 第三组：独立文字直播（2026-10-28）

迁移 iOS LiveBroadcast／LiveBroadcastViews 的独立比赛直播到 Android，沿用既有 `/livestreaming/novideo/api/sessions` 和网页，不新增后端、不改 iOS／生产。**AN06 整体未完成，尚缺共享直播及历史观看三种权限；AN07 不做。**

## 产品与数据

入口“比赛 → 现场记分 → 更多 → 文字直播”。仅独立、进行中、非练习／预告比赛可明确开启；不会因启动 App 自动创建直播。展示公开范围、前台联网要求和终场／失联一小时期限；支持二维码、队名分享、最近同步、手动重试和明确关闭。关闭和过期不删除本机比赛。二维码编码后有实际解码测试；真实系统分享与页面未执行。

LiveProjection 明确列出 schema 1 公共字段，只包含本场当前参与者、比赛过程、规则、计时和投打表现；不序列化整个 Game／Team，不包含替补库、原始备份、纠错检查点、来源摘要、认证或私有审计。保留 00，普通／教练／慢垒均由原服务严格 schema 校验。过程使用现有 Android AppearanceProjection；尚未由真实 iOS App 完整比赛逐条对照，不能据此宣称 AN04 完整互导已通过。

当前前台每十秒补传或保活，停止前台时请求最后一次同步，不注册后台常驻服务。每次 Room 比赛变化也请求同步，合并排队请求；断网不阻断本机保存。公共开屏读取仍是独立的既有后台请求；没有直播绑定时不发直播网络请求。

## 安全及恢复

- 每场独立 32 字节随机发布凭证，与球队 Bearer 分开。LiveCredentialFile 位于 noBackupFilesDir 的 standalone-live，AES-GCM／独立 HKDF 域及 AAD，AtomicFile、fd.sync 和回读确认；稳定加密密钥不随球队凭证轮换／重新接入改变。不进入 Room、bmandroid、报告或日志。
- 开播意图、requestID 和 token 先持久保存，再 POST；丢响应或重启只重试同一身份。尚未取得 code 的关闭使用原 requestID DELETE，不新开另一个链接。回执写失败停止本实例上传，重启核对。
- 同修订不同内容或本机修订倒退会阻断上传，不能覆盖已发布记录；明确关闭仍可用。HTTP 拒绝重定向、Cookie、缓存和自动请求重试，地址／code／token／体积有约束，异常不显示服务原文。
- 恢复备份前先持久标记旧绑定 closing 并递增代次。开播请求中的旧响应不能复活链接；恢复后和重启只续做删除，不拿恢复的旧记录覆盖现有直播。保存 closing 失败则不进入恢复。
- 最后同步的 serverTime／expiresAt 差值换成本机到期时间。终场心跳不能延长期限，404／410 清本机失效绑定，不恢复旧链接。

## 开发验证

`output/validation/android/an06-standalone-live/initial.log` 中 core／原协作服务／原直播服务／Android JVM、App／AndroidTest 编译及 lint 全部通过。之后新增公开字段／终场／二维码和开播中恢复保护测试，重跑 core 和 liveServiceTest，见 followup.log。冻结合计 **273 项：148 core＋56 协作服务＋8 直播服务＋61 app，0 失败／错误／跳过**；lint 0 错误／18 警告。JUnit XML、lint XML 和摘要见 freeze。

真实服务测试只启动 `scripts/android/an06-local-live.mjs`：原 live/server.mjs、127.0.0.1 动态端口、内存 SQLite、合成比赛，无生产配置。覆盖三模式、公开白名单和 00、丢创建／更新响应、按原身份关闭、同修订冲突、终场一小时、恢复后删除和开播中恢复不再 PUT。Android JVM API31 验证独立加密文件、凭证轮换后解密、错误密钥／篡改拒绝、原文件保留和原子保存回滚。不是实际 Keystore／设备测试。

```sh
bash scripts/android/gradle.sh :core:test :core:teamServiceTest :core:liveServiceTest \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

未执行 Activity／API31、37 画面、真实浏览器／分享、真机杀进程、锁屏、后台厂商策略、真实磁盘填满或生产链接。没有发布 APK、签名正式包或修改网站。继续共享比赛列表、共享直播／历史权限；完整共享记分上传仍依赖 AN04 正式 payload。
