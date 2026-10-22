# Live protocol v1

Base: `/baseballmaster/live`。仅同源网页，不开放 CORS。所有响应 `Cache-Control: no-store`。JSON 正文最大 2 MiB。`LiveSnapshot`（Swift）与 `validateSnapshot`（Node）共同定义字段白名单，`schema=1`；可空字段显式为 `null`。时间统一为 Unix 毫秒。

| 方法／路径 | 权限 | 行为 |
| --- | --- | --- |
| POST `/api/sessions` | 新设备自行生成的 32 字节 bearer | `{requestID: UUID, createdAt, snapshot}`；首次 201，重复身份且密钥相同 200；仅进行中比赛，创建时间需在服务器 ±15 分钟内 |
| PUT `/api/sessions/<code>` | 原 bearer | 最新完整投影；revision 严格递增，同版本同正文可幂等重试，不同正文 409；旧版本 409 |
| POST `/api/sessions/<code>` | 原 bearer | 无正文心跳；更新 lastSeen，不改 revision；已终场不延长期限 |
| GET `/api/sessions/<code>?since=N` | 公开 | 当前 revision=N 时仅返回元数据及 `unchanged:true`，否则返回完整 `snapshot` |
| DELETE `/api/sessions/<code>` | 原 bearer | 删除该场直播；以后读取 404、写入 410 |
| DELETE `/api/sessions` | 原 bearer | `{requestID}`，用于开播响应丢失时撤销；同请求身份不存在仍 200，存在须密钥匹配 |
| GET `/health` | 公开 | `{"ok":true}`，无比赛列表／身份信息 |

统一元数据：`code, revision, serverTime, lastSeen, expiresAt`。发布凭证永不出现在响应或观赛 URL。过期／关闭的观赛路径本身返回通用 410 页面，不保留场次特定 HTML。

快照分为当前局面与时间线：

- `gameID/revision/mode/isFinal/endedAt`。
- `inning/isTop/balls/strikes/outs`，`home/away` 的队名、逐局分数和 R/H/E。
- 当前 `batter/pitcher`（ID、显示名、背号）、`pitchCount`，教练模式的 `appearancePitchCount/pitchLimit`；`bases`、`currentAppearanceID`、待决提示 `notice`。
- `entries`：稳定 ID、所属打席 ID、半局、类型、标签、摘要、球员、状态和逐球 `details`。新快照移除的 ID 在网页中也消失；不是往旧列表追加一份新的全场记录。

App 先完成 Core Data 保存，才触发发布。Keychain 保存开播身份、密钥、串码、上次确认修订及内容摘要；比赛本身保存最新快照的来源。失败时保留这个状态，重启后重新生成并补传最新版本。无需为每一个逐球操作复制完整网络请求。撤销也增加修订号。

接收正文是异步的，服务器会在正文收完后重新读取数据库行，在同一个同步步骤内完成鉴权、版本比较与写入；不能用开始接收时的旧版本覆盖后续已经提交的更新。创建、更新、删除不存在基于旧码的 upsert。

普通直播期限为最近发布／心跳 + 1 小时。终场后是 `min(endedAt, serverTime) + 1 小时`；终场修订和心跳不能推后已有终场期限。在截止前的新版本恢复比赛，改回心跳期限。达到截止立即失效。设备应保持自动时间，创建时钟差超过 15 分钟会拒绝。

备份不携带写入凭证。恢复备份后原绑定转为待关闭，避免旧历史自动覆盖正在直播的场次；若发现本地修订回退或同版内容变化，停止发布并要求用户重新开播。服务器返回 400/401/403/409/413 等确定错误也停止自动重试；网络与服务暂时错误按退避重试。关闭请求与在途网络响应竞态受保护，不会重新开启刚关闭的直播。
