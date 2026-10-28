# AN06 第四组：共享直播与历史观看（2026-10-28）

对应现行 SharedMatchViews／SharedMatches 和 collaboration/games.mjs 的观看功能；服务、iOS、网站均未改动。新增共享比赛只读列表、历史和观看管理入口；**不因此开放共享比赛记分、正式上传或交接。AN04／AN05 仍有开发缺口，AN07 本轮不做。**

## 入口与协议

“球队 → 球队共享 → 选择球队 → 球队比赛与观看设置”。列表读现有增量游标，展示服务提供的对阵、状态和删除标记；选中后下载完整文件并再次核对成员／恢复代次。只读历史沿用原始事件／纠错阅读器，不将服务 payload 转成 Android Game 再回写。

- 当前记分设备可明确开启／关闭实时直播、分享现有 `/watch/#live/<code>`。前台每十秒只为本机缓存中已经开启、仍属于本设备且仍有记分权的进行中直播保活，每次先读取当前权限和完整文件。空缓存、不属于本机、恢复待核对／失效授权不注册或连接；保活不新建／重开链接。离开观看页面后仍随 App 前台维持，停止前台即停止定时器。终场或过期不通过心跳续期。
- 管理员或本场负责记录员可在终场后切换永久开放、仅指定人员、不开放。关闭会同时撤销旧指定成员、外部设备和观看邀请，档案完整保留；再次开启指定人员不复活旧授权。
- 指定队内成员可获准或被撤销网页观看；成员可取得五分钟单次观看票据，不因此获得管理权。
- 外部单次邀请一天内有效，填写称呼后仍需明确批准；可拒绝待确认申请、撤销已批准设备；已撤销设备必须重新申请。
- 链接、邀请及票据只在 URL fragment 中，不放 query。沿用原网页、二维码／系统分享／复制和浏览器打开。秘密不存 SavedState，安全窗口显示，进入后台隐藏。二维码真实解码回归通过，真实浏览器／系统分享未运行。

## 持久化与权限

PendingMatchWatch 按场单独加密保存原 operationID、body、权限／恢复代次和记分代次；同场交接与观看操作互相阻断。请求先存盘再发送，续取保持同一 token／编号。自己的操作已经改变 accessVersion 时，丢响应仍能按原编号续取服务回执，不擅自换内容重试。

回执只证明请求结果，不授予当前权限；展示链接前按最新完整文件核对观看模式、版本、管理范围和到期时间。明确的失败可经用户确认保留为待核对，未知结果不能换编号重做。请求、回执、链接和失败均在独立 TeamDirectoryFile 内，不进入 Room、普通备份／报告或日志。

全库恢复或确定 401 时先保留旧观看请求及相关完整比赛，再清授权／链接；新身份重接入、恢复后核对不重放它们。人工保留记录在“恢复后核对”可见，不打印请求中的邀请秘密。本组未更改角色／观看的全局规则。

## 开发验证

冻结 **283 项：149 core＋63 原协作服务＋8 原直播服务＋63 app，0 失败／错误／跳过**，App／AndroidTest 编译及 lint 通过；lint 0 错误／18 警告。`output/validation/android/an06-shared-watch/freeze` 有 XML、lint 和摘要。

- final.log：全部开发任务通过；presence-final.log 在补齐跨页面前台保活和存储测试后，协作服务、app 测试、两种编译和 lint 再次通过。普通 core／独立直播未因只有 App 跟进而无意义重跑。
- 初次 3 项失败是匿名测试请求漏现行 X-BM-Protocol: 1，返回 426；补头后通过，保留 initial.log。前台跟进首次编译遇跨模块 nullable smart cast，改为稳定局部 directory 后通过，失败 presence-followup.log 保留。
- 只使用 127.0.0.1 动态端口、内存 SQLite 和现有原服务。覆盖公开／指定／关闭、外部申请后批准、撤销即时拒绝、关闭再开不恢复旧设备、成员票据不授予管理、写前存盘、丢网络／本机回执后同编号续取、明确冲突保留、恢复／撤销后的请求隔离、共享直播开关及过期不复活。请求层测试手动带网页协议头／Origin／Cookie，不代表真实浏览器联调。
- API31 Android JVM 验证邀请／回执／票据／保留记录的加密重开、写失败回滚，以及前台维护不注册且只保活已有本机直播。原始比赛完整 payload 在权限变更前后逐值相同。

```sh
bash scripts/android/gradle.sh :core:test :core:teamServiceTest :core:liveServiceTest \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

## 继续与未执行

未运行 Activity／API31、37 画面、真实分享／浏览器、真实双端切换、真机前后台／杀进程／Keystore 或 AN07；没有访问生产、发布 APK、正式签名或网站变更。共享完整比赛写入、离线更新、冲突补录及交接产品入口仍受 AN04 全量 payload 支持限制。新安装后的实际浏览器续接及 HTTPS 域名关联仍需真实网站／正式证书和后续验证，不能把加密 intent 待办说成安装前已恢复。

主要入口：core SharedMatchWatching；App SharedMatchesScreen／AndroidTeamSharing／MainActivity 的前台维护；TeamRecoveryReview 和 TeamDeviceReconnect 负责旧请求保留。下一阶段优先回到 AN04 正式 wire 转换和完整历史／纠错，再完成 AN05 共享完整记分，不重复本组观看协议。
