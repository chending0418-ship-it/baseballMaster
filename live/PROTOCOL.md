# Live protocol v1

Base: `/livestreaming/novideo`。仅同源网页，不开放 CORS。所有响应 `Cache-Control: no-store`。JSON 正文最大 2 MiB。`LiveSnapshot`（Swift）与 `validateSnapshot`（Node）共同定义字段白名单，`schema=1`；可空字段显式为 `null`。时间统一为 Unix 毫秒。

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
- 当前 `batter/pitcher`（ID、显示名、背号）、`batterOrder`（当前打者第几棒）、`pitchCount`，教练模式的 `appearancePitchCount/pitchLimit`；`bases`、`currentAppearanceID`、待决提示 `notice`。
- `entries`：稳定 ID、所属打席 ID、半局、类型、标签、摘要、球员、状态和逐球 `details`。新快照移除的 ID 在网页中也消失；不是往旧列表追加一份新的全场记录。

`batterOrder` 是 2.1 增加的 v1 可选字段：当前有打者时为从 1 开始的整数（协议上限 999），与 App 记分页共用计算逻辑，独立于球衣号码；终场或没有打者时新 App 发送 `null`。服务端兼容旧开发快照缺省或显式 `null`，网页仅显示“打者”，不会从号码推测棒次。有效整数只允许出现在进行中且有打者的快照，非法值拒绝写入并保留已发布版本。旧服务的字段白名单不接受新增字段，因此后续部署应先更新服务端和网页，再启用新版 App；协议编号仍为 `schema=1`。

阵容和资料更正沿用保存后的球员 ID。当前投打、垒上跑者和打席球员资料随更正更新，既有逐球原文保留原记录；新增阵容／更正事件说明变化。撤销与重做发布递增修订的完整快照，不重复追加记录。

## 2.2 兼容扩展

协议仍为 `schema=1`。以下字段可缺省或为 `null`，服务端继续接受 2.1 App 的投影；网页对未知统计、阵容、历史垒况明确显示“未记录”，不补造零值：

- 顶层 `hasStarted`、`rules`（实际局数、半局得分上限、限时）、`clock`（首次开赛／当前计时起点、累计秒数）、`batterStats`（打数、安打、完整性）、`nextBatters`（后两位的球员与棒次）。当前打者的打数来自比赛统计，保送不算打数。暂停和终场停止累计；断线／记分设备失联时冻结在最近确认状态。
- 球队 `shortName`、`playedInnings`（与逐局数组同长）、`lineup`（当前打序及实际守备者的姓名、号码、守位）和 `pitcherID`。不公开替补资料库；DH 的非打序投手单列、棒次为未知。未进行的半局显示 `-`，已开始的零得分显示 `0`。
- 时间线条目的 `order`、`situation`，逐球 `details` 的 `situation`。局面只包含好坏球、出局、占垒编号与半局结束标记，来源为对应历史记录；同一已保存操作中的 `HALF-END` 用于第三出局后清空垒包，不读取最新全场局面覆盖历史。老记录无局面时显示未知。

`metadata.lastSeen` 仍表示发布设备最近同步／心跳时间，不代表浏览器轮询时间或最后一次得分变化。网页标题与 Open Graph 使用实际双方队名；阵容查看期间也接受最新快照。

App 的 Keychain 若仍保存 2.1 投影摘要且修订号未变，先核对旧字段投影的 SHA-256 完全一致，再持久化递增修订号并上传新字段；沿用原链接。保存失败不上传。任何其他同版本内容冲突仍停止同步，不能借升级机制覆盖不同历史。

**部署顺序：先更新服务端字段白名单及网页，再交付 2.2 App。** 已安装 2.2 后若回滚 UI，继续保留兼容白名单的服务端；不能直接退回拒绝新字段的 2.1 服务。数据库结构不变，不复制、回滚或恢复直播数据库。发布包、兼容 UI 回滚包和公网验收安排见 [2.2 部署准备](deploy/2.2-preparation.md)。

App 先完成 Core Data 保存，才触发发布。Keychain 保存开播身份、密钥、串码、上次确认修订及内容摘要；比赛本身保存最新快照的来源。失败时保留这个状态，重启后重新生成并补传最新版本。无需为每一个逐球操作复制完整网络请求。撤销也增加修订号。

接收正文是异步的，服务器会在正文收完后重新读取数据库行，在同一个同步步骤内完成鉴权、版本比较与写入；不能用开始接收时的旧版本覆盖后续已经提交的更新。创建、更新、删除不存在基于旧码的 upsert。

普通直播期限为最近发布／心跳 + 1 小时。终场后是 `min(endedAt, serverTime) + 1 小时`；终场修订和心跳不能推后已有终场期限。在截止前的新版本恢复比赛，改回心跳期限。达到截止立即失效。设备应保持自动时间，创建时钟差超过 15 分钟会拒绝。

备份不携带写入凭证。恢复备份后原绑定转为待关闭，避免旧历史自动覆盖正在直播的场次；若发现本地修订回退或同版内容变化，停止发布并要求用户重新开播。服务器返回 400/401/403/409/413 等确定错误也停止自动重试；网络与服务暂时错误按退避重试。关闭请求与在途网络响应竞态受保护，不会重新开启刚关闭的直播。

## 2.3 慢垒可选字段

仍为 schema 1，新增 mode `slowPitch`；标准及教练旧快照继续接受。rules 可包含 competitionFormat（timed／innings）、inningsLimit、initialBalls（0–3）、initialStrikes（0–2）、twoStrikeFoulPolicy（outImmediately／oneExtraFoul）、fieldersCount、rulesVersion、extraFoulUsed。不提供字段时保持未知，网页不补造规则。当前 B/S、真实投球数、双方完整打序与自由人均来自同一保存修订。时间赛按实际局次显示，计时显示剩余时间。先部署兼容服务端与网页，再发布 2.3 App；当前改动尚未生产部署。
