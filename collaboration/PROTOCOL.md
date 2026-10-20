# 3.0 球队协作接口

2026-10-13。3.0 App 已完成开发、验证和签名归档，协作服务已部署到 `https://baseballmaster.cc/api/v3`。身份、名单、年度统计、比赛同步、交接、历史权限、配对、管理恢复、导入、冲突核对、完整档案与灾备核对均已接入。Apple 上传与审核由用户执行；部署、参数及边界见 [3.0 交接](../docs/releases/3.0/REVIEW-HANDOFF.md)。现有 `/livestreaming/novideo` 临时服务保持独立。

## 通用约定

- JSON，`X-BM-Protocol: 1`；不支持的协议返回 426。设备、球队、成员、邀请和操作使用 UUID，服务端输出小写；对象路径使用小写。
- 设备首次使用共享能力时，在本机生成 32 字节随机秘密，以无填充 Base64URL 编码。在发送之前写入仅本机 Keychain；请求使用 `Authorization: Bearer <设备秘密>`。不依赖昵称、手机号、邮箱、Apple 账号或 iCloud。凭证不进入比赛、普通备份或 URL。
- 注册和其余接口只保留设备秘密的 SHA-256 摘要。iOS 使用临时 URLSession、不接受重定向、不保存 HTTP 缓存／Cookie；普通运行只允许 HTTPS，Debug 可显式注入回环 HTTP 地址测试。
- 修改请求包含 `operationID`。相同设备、操作 ID、内容的重试返回原结果；复用 ID 改内容返回 409。操作记录与变更在同一 SQLite 事务提交，返回旧结果前也要核对当前授权；本人退出的终态响应可由同设备、同操作续取，不恢复访问权。App 已为身份／成员操作实现单个持久待处理项，未知结果时锁定输入、保留原操作重试；比赛按场保存独立持久队列，内容修改同样先保存后发送。实时直播的前台心跳是例外：只刷新仍有效的临时观看期限，不改比赛修订，不进入永久操作日志。
- API 元数据日期是 Unix 毫秒；`payload` 内日期沿用 Swift Codable 的 2001-01-01 起秒数，公开投影转换为 Unix 毫秒。邀请期限按服务端时间计算。`revision` 当前仅表示对应对象版本，不使用手机时间决定覆盖顺序。`recoveryEpoch` 是数据库代次；客户端已比较恢复代次并阻断旧队列，恢复时轮换代次，客户端须保留本机记录、停止旧队列并明确核对。
- 无权读取某队与不存在的队均返回 404；已入队但没有操作权限返回 403。接口返回普通语言和稳定错误码，App 将错误显示为普通语言。所有响应 `Cache-Control: no-store`，日志不记录凭证、邀请、正文或名单。

## 已实现接口

| 方法与路径（均相对 `/api/v3`） | 输入 | 返回／限制 |
| --- | --- | --- |
| POST `/devices` | `label`；Bearer 为本机已保存的新随机秘密 | `deviceID, protocol`；同秘密重复注册返回同 ID；撤销秘密不能重新注册 |
| GET `/teams` | 已注册设备授权 | `teams[{id,name,revision,role,memberID,accessEpoch,recoveryVersion}], recoveryEpoch`，仅当前有效成员关系 |
| POST `/teams` | `operationID, name, nickname`；可选 `recoveryToken`（新版 App 生成 32 字节随机值），以及 `profile{shortName,city,players[{sourceID,chineseName,englishName,numbers}]}` | `teamID, memberID, role=admin, revision=1, recoveryVersion`；同名球队保持不同 ID；初始名单同事务保存，最多 200 人，背号用字符串保留 0／00；服务器分配独立球员 ID，sourceID 只作来源映射 |
| GET `/teams/{teamID}` | 当前球队成员授权 | `team{id,name,revision,shortName,city,players}, role, memberID`；三种角色均可读共享名单；旧无 profile 球队回退为空名单 |
| POST `/teams/{teamID}/invitations` | `operationID, token, role` | `invitationID, expiresAt, role`；管理员专用；角色只允许 scorer／member |
| POST `/invitations/redeem` | `operationID, token, nickname` | `teamID, memberID, role`；角色从邀请读取，自报角色无效；单次原子兑换 |
| POST `/invitations/preview` | `token`，已注册设备 | `teamID, teamName, role, expiresAt`；核对后再明确加入，预览不消耗邀请也不授予球队访问 |
| GET `/teams/{teamID}/invitations` | 管理员授权 | 未过期、未使用、未撤销邀请的 ID／角色／期限，不返回秘密 |
| DELETE `/teams/{teamID}/invitations/{invitationID}` | `operationID` | `revoked=true`；管理员专用 |
| GET `/teams/{teamID}/members` | 管理员授权 | 当前有效成员的 `id,nickname,role,revision` |
| PATCH `/teams/{teamID}/members/{memberID}` | `operationID, baseRevision, role`；可选 `active=false` | 更新角色或移除；版本不符拒绝；禁止降级／移除最后管理员；每队最多两名有效管理员，第三名返回 409 `admin_limit` |
| POST `/teams/{teamID}/leave` | `operationID`，当前成员 | `left=true`；仅退出本人，保留球队档案，最后管理员拒绝退出，同操作重试可续取终态 |
| GET `/teams/{teamID}/audit` | 管理员授权 | 按 before/from/to/action 查询，每页 50 条；操作者称呼／设备名采用操作发生时快照，返回 hasMore 和下一 before，不返回凭证或正文 |

管理员上限按有效成员身份计算，不按配对设备数量计算。角色提升在幂等操作的同一事务内检查现有管理员数量；已有两名时以 `409 admin_limit` 拒绝，不修改角色或修订。降级／移除第二名后释放名额，最后一名保护不变。

邀请秘密也由客户端生成 32 字节随机值并保存用于同次请求重试；服务端只存摘要，有效期固定 24 小时。失效、撤销、已使用邀请返回 410。消费响应丢失时，同设备同操作可续取；成员已被移除后续取不能恢复授权。

App 的待处理请求、初始本机绑定、上次球队列表和最近邀请保存在独立 AES-GCM 文件，用仅本机设备秘密经 HKDF 导出加密钥匙，目录排除系统备份，不进入 `.bmbackup`。文件写入失败时不发送新操作；服务器已提交但本机保存失败时保留原操作继续重试。服务恢复代次变化时停止旧请求，保留待核对内容，数据库灾难恢复通过独立删除／撤权日志和 App 明确核对处理；这与已实现的球队管理恢复码流程分开。缓存列表只说明上次刷新时间，不冒充完整比赛同步。共享名单缓存可离线查看；App 启动／回到前台、球队刷新时先核对权限列表，再按球队修订仅下载变化的完整名单。管理员／记录员写入仍须服务端核对；撤销或降级不由缓存授权。

邀请二维码／链接为 `https://baseballmaster.cc/join#<邀请秘密>`；`website/public/join/` 提供无外部脚本、无分析／API 请求的落地页和 App Store 下载入口，秘密不进入 HTTP 路径、查询或下载地址。已安装 App 可通过 `baseballmaster://join#<邀请秘密>` 进入核对页；也支持复制粘贴。链接只接受固定域名与固定入口，打开后不会自动加入。网页切换 fragment 时重新校验，不能保留上一份邀请。页面、App Scheme、生产 HTTPS 路由和模拟器接入已验证；真实商店安装返回与实体设备链路未执行；设备配对使用同类 `/pair#<秘密>` 落地页与 `baseballmaster://pair#<秘密>` 入口。

服务端 `permissions.mjs` 将查看全队业务内容与修改／管理分开：三种角色都可读球队、赛程、比赛、统计及导出报告；新增／修改球员允许管理员与记录员；管理成员、球队资料、停用／恢复球员和历史身份对应仅管理员；比赛编辑还需要负责关系和当前设备写入权。身份、比赛和历史接口均在服务端校验当前对象权限；App 的只读隐藏不能替代服务端鉴权。

## 设备配对、单设备撤销与管理恢复

| 方法与路径（相对 `/api/v3`） | 语义 |
| --- | --- |
| POST `/pairings` | `operationID, token`，可选设备名称 `label`；新设备建立 10 分钟一次性请求，返回 `id,status,phrase,expiresAt,targetLabel`，取代本设备未完成的旧请求 |
| GET `/pairings/{id}` | 目标设备或仍有该成员身份的原授权设备查询；不凭配对 ID 或短语授予权限 |
| POST `/teams/{team}/pairings/preview` | 原设备提交 `token`，预览本队、本人身份、新设备名称及四词短语；不授权 |
| POST `/teams/{team}/pairings/{id}/approve` | `operationID,token,phrase,memberRevision`；原设备确认指定球队的本人身份，只进入待接收状态 |
| POST `/pairings/{id}/accept` | `operationID,phrase,memberRevision`；目标设备确认后，建立同一成员的新设备关系，凭证独立，不交接记分权 |
| POST `/pairings/{id}/cancel` | `operationID`；双方可撤销尚未完成的请求，已配对设备须另行移除 |
| GET `/teams/{team}/devices` | 管理员查看本队所有有效设备，其他成员仅查看本人设备；返回成员、角色、设备名称、关系修订及是否本机，无凭证 |
| DELETE `/teams/{team}/devices/{device}` | `operationID,memberID,baseRevision`；管理员移除本队设备，其他成员仅移除自己的设备；禁止移除最后一台有效管理设备 |
| GET `/teams/{team}/recovery` | 管理员读取恢复码版本与 `hasCode`，不返回秘密；已失效、待重新生成时为 false |
| POST `/teams/{team}/recovery/rotate` | `operationID,newToken,baseVersion,confirmReset=true`；当前管理员更换恢复码，旧码失效，但不撤销日常成员关系 |
| POST `/recovery/preview` | 已登记设备提交 `teamID,token`，校验恢复卡并返回球队及恢复码版本；未授权前不返回比赛内容 |
| POST `/recovery/redeem` | `operationID,teamID,token,newToken,nickname,baseVersion,confirmReset=true`；原子接回管理权并轮换恢复码，同设备同请求可续取响应 |

配对只关联选定球队的本人身份，不默认复制其他队，不合并不同成员，也不复制原设备秘密。原设备确认后，新设备还须核对球队、称呼与角色；过期、短语不符、原成员角色变化、原设备撤销时拒绝完成。已成功配对的新设备在移除原设备后继续有效。配对二维码秘密只放 URL fragment；下载页不向服务器、分析服务或 App Store 传递秘密。待授权结果及撤销入口可在重开 App 后继续查看。

移除设备仅撤销该队关系，其他球队不受影响；立即阻断该设备写入、未完成交接、成员浏览器票据与已有会话。相同成员的其他有效设备及其浏览器会话保留。旧版本无法关联具体设备的成员浏览器会话不继续放行，须重新取得票据。本人移除本机的确认回执可幂等续取，不重新授予访问。

新建球队由 App 在请求落盘前生成恢复码，并展示可保存的恢复卡 `BMREC1.<球队UUID>.<32字节Base64URL秘密>`。用户选择已保存或稍后保存；后者在球队详情持续提醒。恢复卡只在本机加密目录保存，普通备份／球队导出不携带。页面需显式显示，切到后台隐藏；复制仅本机有效、五分钟后过期。其他管理员不能从服务器读回原秘密，只能主动更换并保存新卡。

移除管理员设备、移除／降级管理员或管理员退出，都会使旧恢复码失效并增加恢复版本，避免已撤销人员凭旧卡取回管理权；剩余管理员须生成并保存新卡。App 刷新列表后清除过期本机卡。恢复码轮换、失效与兑换均留审计记录；服务端仅保留摘要。

应急恢复在同一事务中撤销该队旧成员与设备、所有邀请与配对、所有观看授权和待交接；关闭直播与历史，轮换历史入口，使旧公开链接在重新开放后也不能复活。比赛档案保持原内容和时间，记分代次、授权版本及球队 `accessEpoch` 单调增加，新管理员取得档案后再明确接管记分。新恢复码在发请求前持久保存；响应丢失时只允许同设备、同操作、同正文续取原恢复结果，旧码不能创建第二管理员。事务中途失败会完整回滚。

管理恢复会清理旧本机身份操作及下载资料；旧设备未同步记分只保留为隔离草稿，不自动覆盖正式比赛。普通数据库 `recoveryEpoch` 与本队管理 `accessEpoch` 分开，数据库灾难恢复仍属于未完成的部署／备份工作。离线缓存、已导出文件和截图无法远程收回。

恢复预览／兑换默认每设备 10 分钟最多 12 次，并受服务端通用请求限流；公网入口和部署侧限流仍须生产验收。本地验证允许更高通用请求配额以运行回归，不改变生产默认值。

## 历史比赛导入

| 方法与路径（相对 `/api/v3`） | 输入与语义 |
| --- | --- |
| POST `/teams/{team}/history-imports/preview` | 管理员提交 `formatVersion=1,payload,projection,sourceID,sourceTeamID,playerLinks`，只读核对，返回 `status,token,candidates` |
| POST `/teams/{team}/history-imports/commit` | 同一份预览资料，加 `operationID,previewToken,independent`；重新鉴权与核对预览，事务创建独立副本、来源映射、变更记录及幂等回执 |

`sourceID` 为原本机比赛 UUID；`payload.id`／`projection.gameID` 为客户端为本批固定生成的新副本 UUID，必须与原 ID 不同。服务器另分配云端 `gameID`。原球队／球员／逐球 UUID、规则、比赛日期与时区、观赛身份、未知字段和旧文字保持原样。只允许已结束的本机比赛，不能将带共享绑定的副本再次导入。`sourceTeamID` 须为比赛所属本机队；观赛记录可明确选择比赛一方，但仍保留观赛身份，不变为本队正式比赛。

`playerLinks[{sourceID,targetID}]` 对应当场球员 UUID 与本队正式名单中不可变的球员 `sourceID`，不使用姓名或背号作键。允许对应已停用球员；同一场两名球员不能对应同一个身份。相同 UUID 沿用原身份，无需另存对应；其余未关联球员保留独立身份。服务端只接收当场所选球队内的球员、当前正式名单中存在的目标，映射保存在 `history_imports` 表，正式比赛响应新增可选 `historySourceID,playerLinks` 元数据。App 以权威元数据生成本地 `sharedPlayerLinks`，用于年度统计／个人记录及报告；不把关联写进历史阵容或事件。常规比赛更新校验副本 `payload.id` 不变，不能通过更新正文改写导入来源或对应关系。

预览状态：

- `new`：可导入。
- `duplicate`：同来源且当前正式内容／有效球员关联一致，跳过。
- `conflict`：同来源但内容或关联不同，须核对云端记录后跳过；本接口不覆盖。
- `deleted`：同来源已删除，只能跳过；恢复须使用既有明确恢复流程。
- `similar`：不同来源但同日同对手，仅提示候选；必须明确 `independent=true` 才单独导入。比分和队名不决定自动合并。

候选包含 `id,revision,deleted,title,date,awayScore,homeScore`。同来源内容比较保留业务数据，忽略副本 ID、传输修订与更新时间；规范化 UUID 大小写、对象键和 Swift UUID 字典编码顺序，避免重新编码误判。`previewToken` 绑定原来源、副本 ID、内容、关联、球队修订／访问代次、恢复代次、候选修订与删除状态；提交时有变化返回 409 要求重新预览。同批前一场导入若产生新候选，后一场也必须重新核对。幂等回执重放仍检查当前管理员权限。

App 入口为「球队 → 球队比赛 → 导入历史比赛」，每批最多 50 场。年度按比赛实际日期及既有时区规则自动计算，不新增赛季设置。选择与核对时不上传正式比赛，确认后先导出、复制并读回校验完整 `.bmbackup`，保存在 App 本机 `HistoryImports/<批次UUID>/导入前完整备份.bmbackup`；保存失败不开始写入。用户可分享保存该备份。每场原请求和操作 ID 在发送前进入设备绑定的加密队列，断网／回执丢失／重开后由用户继续同一批次；不自动后台上传。暂停完成当前在途请求的核对后停止，未知结果须续传确认后才能结束本批。备份恢复清空旧导入队列，异步旧响应不得安装到新状态。

导入副本由发起管理员设备持有写入权，历史观看默认 `closed`，实时直播关闭，旧直播链接不恢复。队内成员可按既有同步查看；日后更正只改共享副本，原本机比赛独立保留。导入留 `historyImported` 审计记录，不删除原比赛。全队档案导出、服务器灾难恢复与生产容量验收仍待实现／验收。

## 完整比赛、记分权与历史观看

| 方法与路径（相对 `/api/v3`） | 语义 |
| --- | --- |
| POST `/teams/{team}/games` | `operationID,formatVersion=1,payload,projection`；管理员／记录员主动发布单场，独立云端 ID，同队同来源 ID 不重复建场，历史默认不开放 |
| GET `/teams/{team}/games?cursor=N` | 当前成员可取按场归并的变更，最多 40 场，返回 `cursor,hasMore,recoveryEpoch`；包含删除标记 |
| GET `/teams/{team}/games/{game}` | 全部三种角色可取完整正式比赛、修订、当前设备、代次、摘要、有效操作能力及交接状态 |
| PUT `/teams/{team}/games/{game}` | `operationID,baseRevision,writerEpoch,formatVersion,payload,projection`；仅当前写入设备，授权、版本和交接状态在事务内核验；比赛与投影原子保存 |
| GET `…/participants` | 管理员／负责记录员可取有效接手成员与设备 |
| POST `…/handoff/propose` | 提交当前修订／代次和目标设备；暂停本机正式写入 |
| POST `…/handoff/accept` | 目标取得完整比赛后提交 `handoffID,digest,baseRevision,writerEpoch`；原子切换并增加代次 |
| POST `…/handoff/cancel` | 来源取消待确认交接；不靠超时切换 |
| POST `…/handoff/force` | 管理员明确 `confirmRisk=true` 后接管；旧设备离线草稿保留，但旧代次写入被拒绝 |
| POST `…/live` | 当前设备主动开播／关闭，匿名临时链接与历史入口独立；重新开播生成新链接 |
| POST `…/live-heartbeat` | 当前记分设备的前台保活，只延长未终场且尚未失效的直播；不重新开播，不延长终场期限 |
| POST `…/access` | 已结束比赛，管理员／负责记录员提交 `mode,baseAccessVersion`；三模式独立于比赛修订 |
| GET／POST `…/viewers` | 管理员／负责记录员查看、指定成员，核对队外观看者或逐人撤销；写入核对 `baseAccessVersion` |
| POST `…/viewer-invitations` | 32 字节随机令牌，仅存摘要，24 小时单次邀请；兑换后仍须邀请人核对 |
| POST `…/member-ticket` | 已被指定的有效成员申请 5 分钟单次浏览器票据；其他队内成员仍能在 App 看，网页不会默认放行 |
| POST `…/deletion` | 管理员提交版本／代次及 `deleted`；删除关闭链接并撤销授权，30 天内恢复仍保持不开放 |
| GET `/live/{liveCode}` | 有效直播链接匿名读取；关闭／到期返回无内容，不能通过此入口读取长期档案 |
| GET `/watch/{viewCode}` | 独立历史入口，永久开放可匿名，指定人员须有效已批准会话，不开放拒绝；不返回私有比赛包 |
| POST `/watch/{viewCode}/apply` | 单次邀请／成员票据 + 本浏览器随机秘密 + 称呼；同源申请后绑定 HttpOnly／Secure／SameSite Cookie，秘密仅存摘要 |

按用户 2026-10-11 最新澄清，**终场不立即改变原直播链接的匿名观看方式**。原直播在终场后保留一小时；进行中一小时没有同步／前台心跳或主动关闭后失效。终场后更正与心跳不延长期限。历史三模式只控制留存档案的独立入口，不影响仍有效的直播链接；即使档案设为永久开放，过期直播入口也不会自动转为档案入口。两个 URL 分别为 `/watch/#live/<liveCode>` 与 `/watch/#<viewCode>`，不向实时观众泄露历史入口令牌。

单场上传为完整比赛快照，按本机场次修订发送，服务端不永久保留每球全量快照；增量指变更比赛集合，不是逐字段补丁。iOS 对大请求使用 RFC 1950 deflate，服务端支持 gzip 响应，解压后上限 18 MiB／比赛包上限 16 MiB。结构校验覆盖局面、模式、阵容引用、统计与公开比分投影；实际 App 三模式包联调，未知历史标记原样保存。公开网页仅返回当前比赛投影、当场阵容与白名单 Box Score，无设备秘密、全队库或其他比赛。

本机数据语义 7、备份格式 6 保存云端比赛绑定，认证／发送队列／观看秘密放设备绑定的加密文件。保存失败不发送，服务器响应丢失用原操作重试；确认后再取当前正式副本，避免幂等旧响应恢复过时权限。前台每 10 秒刷新，后台不承诺持续同步。离线原写入设备可继续本地记分；重新联网发现旧代次时保留独立草稿，显示比较与补录参考，不自动覆盖正式版本。

移除成员后清除下载的球队比赛；原发布者保留共享前的本机原稿。未同步内容单独留档。共享旧备份恢复后先核对云端，不能整体发布旧版本。游标压缩／过期重建、长期库灾难恢复、清理与全队导出仍待后续完成；当前增量日志尚不主动过期。

历史页面不使用第三方资源或分析，邀请在 fragment，页面和 API 均 `no-store`。撤销、隐藏页面、重新进入或请求失败清空受限内容，重新取权再显示；不宣称能收回观看者已截屏或另存的副本。浏览器授权只代表核对后的设备会话，不代表实名身份。

## 存储与运行

独立 SQLite，WAL、外键、写入事务和版本标记。保存设备摘要、球队、初始名单、成员、多设备关系、邀请摘要、幂等结果、完整比赛最新修订及其公开投影、交接、观看授权、变化游标和审计。兼容性新增的 `team_profiles` 表启动时创建，旧身份数据保持可读；初始名单没有读写比赛或统计的权限含义。现有临时直播数据库不混入长期球队库。默认绑定回环地址 `127.0.0.1:8788`，生产 HTTPS 反代已配置。默认文件在 `collaboration/data/`，不纳入 Git。

```sh
npm --prefix collaboration test
npm --prefix collaboration start
```

本地联调可通过 `BM_TEAM_PORT=18788 BM_TEAM_DATABASE=:memory:` 启动临时服务；仅合成数据。另启现有 `live/server.mjs` 的内存服务在 18088，运行 App 测试可覆盖旧直播兼容。

测试工厂默认每分钟 180 次；生产入口配置 600 次／分钟／IP，仅信任明确启用的回环代理。每设备最多加入 30 队。单队 5,000 场和 2 GiB，成员、设备、名单及全局登记有限额；磁盘低于 2 GiB 拒绝写入。过期清理、独立备份与日志、凭证轮换、监测和完整样本并发验证已完成，参数和未实测的长期运行边界见运行手册。

## 后续协议工作

D02 首版已完成：包含持续名单、年度、设备配对与管理恢复、恢复代次、游标重建、协议与语义版本拒绝保护。2026-10-13 用户明确 Android 客户端实现及真实跨平台联调暂缓，后续版本待定，原方案见 [Android 规划](../docs/plans/ANDROID-APK.md)。3.1 击球记录的新字段兼容按 [其规划](../docs/plans/3.1-BATTED-BALL.md) 在正式开发时细化，当前协议未增加观察字段。旧实时直播保持原控制，不把新历史权限反向施加于实时观众。

## 球队资料、持续名单与年度（2026-10-12）

`PATCH /teams/{teamID}/roster`：管理员与记录员可新增／修改球员；`kind=profile` 球队资料与球员 `archived` 状态变更仅管理员，普通成员不可写。共用 `operationID`、`baseRevision`（球队当前修订），`kind=profile` 携带 `name,shortName,city`；`kind=player` 携带 `player{id,sourceID,chineseName,englishName,numbers,archived}`。返回与 GET 球队详情相同的结构。所有正文先保存到设备加密队列，重试沿用相同操作号与正文；401／403／404 不恢复旧授权。

同事务更新单项资料、递增球队修订、保存幂等回执及审计。版本冲突返回 `409 roster_conflict`，不采用最后写入覆盖；App 保留修改草稿，显示云端现值，由用户刷新核对后重新保存。客户端不以成功的旧回执降低已缓存的较新名单版本。GET 全量名册包含停用球员，修订不变时客户端不重复下载；单个名单最多 200 名现役、2000 份含停用档案。

云端 `id` 与来源 `sourceID` 均不可更换；同队来源 ID 不重复，不按姓名、背号合并。管理员或记录员新建球员时在发送前生成两个 UUID；创建球队时已有来源 UUID 沿用本机球员 ID，云端 ID 由服务器分配。所有设备在本地名单投影中使用同一 `sourceID`，保持比赛逐球引用及统计一致。停用不会删除球员或重写已开始／已结束比赛，恢复使用原 ID。姓名最多 80 字符，背号为字符串 0–99／00，保留 0 与 00 的区别。

不新增 Season 表或年度写接口。年度等于比赛实际时间在比赛保存时区中的 Gregorian year；安排比赛使用安排时间，完赛统计只包含已结束且非观赛比赛的最新正式副本。日期和时区随完整比赛包同步，更正跨年时各设备从同一修订重新归年，不累加设备端汇总；年度界面、球员统计和 PDF 共用该筛选逻辑。旧季节标签仅作来源兼容保留，不决定年度。旧未写时区的本机比赛升级时固定设备时区，未知时区的旧共享包用 UTC 回退。

共享名册与本机名册独立；云端修改不重写原本机队。使用共享名册开始比赛时，本地快照中的球队 ID 为云端球队 ID，球员 ID 为来源映射 ID；比赛先本机保存，由用户在球队比赛页共享，复用已有离线发送队列。不同来源的旧比赛或临时球员不凭名字关联。历史批量导入及导入后的单场球员对应均已实现，接口与边界见本文件对应章节。

## 完赛后的球员对应与正式统计（2026-10-12）

`POST /teams/{team}/games/{game}/player-links` 接收 `operationID,baseRevision,writerEpoch,baseRosterRevision,playerLinks[{sourceID,targetID}]`。仅当前管理员可更正已结束的本队比赛，观赛、未结束、已删除或交接中的比赛拒绝。来源为本场本队球员 UUID，目标为该共享球队正式名册不可变的 `sourceID`（含已停用球员）；同名不自动关联，一场两位球员不能对应同一身份，包括目标已作为原身份参加本场的情况。空数组解除所有显式对应；同 UUID 沿用原身份。

请求同时核验比赛与名单修订，在事务中保存单场对应、递增比赛正式修订、写入前后关系、操作者设备和审计。同操作同正文只执行一次，权限收回后不能重放；陈旧或失败请求不部分写入。比赛原始 payload、当场姓名／背号、逐球引用、比分、公开投影、记分权与观看设置均不改写。其他比赛不跟随关联。普通比赛修订继续保留关联；若新正文删掉已关联的来源球员或改变其本队身份，拒绝并要求先核对关联。

`GET …/player-links` 供有效队内成员查看最近 50 次对应修改的前后关系、修订、队内称呼、设备名称和时间；外队拒绝，不返回设备秘密。`game_player_links` 保存当前对应，`player_link_changes` 保留修改记录；原 `history_imports` 中的导入关联作为兼容起点，后续修正独立保存。重复导入检查使用当前正式对应，不能通过重新导入撤回后来的人工修正。

App 入口为「球队 → 球队比赛 → 共享比赛 → 核对当场球员与正式名单」。选好后展示修改前后差异，再确认保存；取消不发送。提交复用设备加密持久队列，响应丢失重开后沿用原请求核对，重新刷新保留未提交选择，不自动以新的比赛版本重新提交旧选择。普通成员和记录员可只读查看对应与修改记录。

年度球队统计、个人逐场数据、筛选及 PDF 统一从已收到的正式比赛副本和对应关系重算。进行中、观赛、删除和未同步的本机草稿不计入共享汇总；同场最新修订替换贡献，重复拉取不重复加总。单场 TXT／Box Score 保留当时资料。页面及报告说明采用最近收到的正式版本，不将缓存冒充实时数据；全队刷新会发现该设备所有已加入球队的比赛，新设备无需先有本地比赛。


## 发布收尾协议（2026-10-13）

- `POST /devices/credential-prepare` 与 `POST /devices/credential-commit` 两阶段认证轮换：旧秘密准备、新秘密证明持有后提交，丢响应可续接；旧摘要进入拒绝重放集合。恢复待核对状态也阻断未确认的轮换。App 将认证秘密与文件加密材料分开，更新认证后原缓存仍能读取。实际路径以 server.mjs 路由为准。
- 管理员 `GET /teams/{id}/archive` 获取 profile、游戏修订与删除／清除标记、恢复代次和清单摘要。`GET /teams/{id}/archive/{game}?snapshot=...` 必须匹配同一清单，`POST /teams/{id}/archive/finish` 再次核对并写审计。期间有任何正式变化则拒绝混合导出；不包含成员／设备秘密或观看授权。
- App `.bmteam` 为逐条校验的 JSONL 完整档案，尾部链摘要、唯一对象及总数校验，单行 32 MiB、最多 5,000 场。导入到独立只读库，保留比赛、规则、完整名单及来源对应；不会覆盖当前数据库或恢复云端权限。
- `GET/POST /teams/{team}/games/{game}/drafts[/{draft}]` 保存不可变原稿及处理记录。管理员核对双方记录，补录走原更正流程；补录处理须存在更新的正式修订，决定再产生修订，保留原草稿和来源。
- `POST /teams/{id}/dissolve` 明确名称、修订和确认，立即关闭权限、直播与历史；`GET /dissolved-teams` 仅原管理员设备看到 30 天恢复入口，`POST /teams/{id}/restore` 只接回该管理员，旧分享与成员不自动恢复。超过期限清理正文，保留拒绝旧请求的最小标记。
- 变化游标小于已压缩下界返回 `410 cursor_expired`。App 保留本机未同步内容后重新拉取完整正式对象及删除标记。
- 独立日志在主库撤销事务确认前写入 COS。恢复时重放撤销、轮换服务恢复代次、关闭旧外部入口并要求 `POST /devices/recovery-review` 核对；没有可信日志的恢复只可隔离查看，不能上线。App 不重发旧代次队列，较新本机记录保留供人工比较。

活动球队必要审计保留用于记录归属和纠错；解散 30 天到期清理球员／成员名称，审计中的操作者名称匿名化，最小对象／动作／删除标记保留。运维日志不保存比赛正文和授权秘密。
