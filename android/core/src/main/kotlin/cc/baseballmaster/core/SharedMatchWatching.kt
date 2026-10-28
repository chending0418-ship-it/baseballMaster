package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class HistoricalWatchMode { closed, public, restricted }
@Serializable enum class MatchWatchAction(val resource: String) {
    access("access"), viewer("viewers"), invitation("viewer-invitations"), ticket("member-ticket"), live("live")
}
@Serializable data class PendingMatchWatch(
    val id: String,val teamID: String,val gameID: String,val action: MatchWatchAction,val body: JsonObject,
    val memberID: String,val accessEpoch: Long?,val recoveryEpoch: String,val writerEpoch: Long,
) {
    init {listOf(id,teamID,gameID,memberID,recoveryEpoch).forEach(::appleUUID);require(body["operationID"]?.jsonPrimitive?.content==id && writerEpoch>0)}
    override fun toString()="PendingMatchWatch(id=$id, action=$action)"
}
@Serializable data class CompletedMatchWatch(val request: PendingMatchWatch,val response: JsonObject) {
    override fun toString()="CompletedMatchWatch(id=${request.id}, action=${request.action})"
}
@Serializable data class MatchWatchLink(val gameID: String,val operationID: String,val token: String,val expiresAt: Long,val accessVersion: Long,val memberTicket: Boolean) {
    init {appleUUID(gameID);appleUUID(operationID);TeamSecrets.validate(token);require(expiresAt>0 && accessVersion>0)}
    override fun toString()="MatchWatchLink(gameID=$gameID, memberTicket=$memberTicket)"
    fun url(document: SharedMatchDocument,now: Long=System.currentTimeMillis()): String? =
        if(document.id==gameID && !document.deleted && document.watchMode==HistoricalWatchMode.restricted && document.accessVersion==accessVersion && expiresAt>now &&
            (memberTicket || document.canManageWatch)) "${document.watchURL}/$token" else null
}
@Serializable data class MatchViewer(val id: String,val nickname: String,val status: String,val memberID: String?=null) {
    init {appleUUID(id);teamText(nickname,true);require(status in setOf("pending","approved","revoked"));memberID?.let(::appleUUID)}
}
@Serializable data class MatchViewerMember(val id: String,val nickname: String,val granted: Int) {
    init {appleUUID(id);teamText(nickname,true);require(granted in 0..1)}
}
@Serializable data class MatchViewers(val viewers: List<MatchViewer>,val members: List<MatchViewerMember>) {
    init {require(viewers.map {it.id}.distinct().size==viewers.size && members.map {it.id}.distinct().size==members.size)}
}
val SharedMatchDocument.watchMode: HistoricalWatchMode get()=HistoricalWatchMode.valueOf(raw.getValue("accessMode").jsonPrimitive.content)
val SharedMatchDocument.accessVersion: Long get()=raw.getValue("accessVersion").jsonPrimitive.long.also {require(it>0)}
val SharedMatchDocument.canManageWatch: Boolean get()=raw.getValue("canManageAccess").jsonPrimitive.boolean
val SharedMatchDocument.watchURL: String get()="https://baseballmaster.cc/watch/#"+watchCode(raw.getValue("viewCode").jsonPrimitive.content)
val SharedMatchDocument.sharedLiveURL: String? get()=raw["liveCode"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content?.let(::watchCode)?.let {"https://baseballmaster.cc/watch/#live/$it"}
val SharedMatchDocument.liveAvailable: Boolean get()=raw["liveEnabled"]?.jsonPrimitive?.boolean==true && (raw["liveExpiresAt"]?.jsonPrimitive?.longOrNull ?: 0)>System.currentTimeMillis()
val SharedMatchDocument.finalForWatching: Boolean get()=payload?.get("state")?.jsonObject?.get("isFinal")?.jsonPrimitive?.boolean==true
private fun watchCode(value: String)=value.also {require(Regex("[A-Za-z0-9_-]{32}").matches(it))}
private fun watchPath(p: PendingMatchWatch)="teams/${appleUUID(p.teamID)}/games/${appleUUID(p.gameID)}/${p.action.resource}"

fun TeamDirectorySession.matchViewers(teamID: String,gameID: String): MatchViewers = synchronized(storage.monitor) {
    val before=match(teamID,gameID);require(before.canManageWatch && !before.deleted)
    val result=TeamDirectoryJson.format.decodeFromJsonElement<MatchViewers>(client.transport.request("GET","teams/${before.teamID}/games/${before.id}/viewers",null))
    val after=match(teamID,gameID)
    check(after.canManageWatch && !after.deleted && before.accessVersion==after.accessVersion) {"观看授权仍在变化，请刷新核对"}
    result
}

private fun TeamDirectorySession.prepareWatching(expected: SharedMatchDocument,action: MatchWatchAction,fields: JsonObjectBuilder.()->Unit): String = synchronized(storage.monitor) {
    val current=match(expected.teamID,expected.id);val state=storage.load()
    check(!state.credentialExpired && !state.requiresRecoveryReview && state.pending==null && current.id !in state.watchPending && current.id !in state.matchPending) {"请先核对已有待处理操作"}
    require(!current.deleted && current.accessVersion==expected.accessVersion && current.writerEpoch==expected.writerEpoch && current.digest==expected.digest) {"比赛或观看设置已变化，请刷新后重试"}
    if(action==MatchWatchAction.live) require(current.canWrite) {"仅当前记分设备可开关实时直播"}
    else if(action!=MatchWatchAction.ticket) require(current.canManageWatch) {"当前角色不能管理本场观看方式"}
    if(action in setOf(MatchWatchAction.access,MatchWatchAction.viewer,MatchWatchAction.invitation,MatchWatchAction.ticket)) require(current.finalForWatching) {"终场后才能管理历史观看"}
    if(action in setOf(MatchWatchAction.viewer,MatchWatchAction.invitation,MatchWatchAction.ticket)) require(current.watchMode==HistoricalWatchMode.restricted) {"请先核对指定人员观看设置"}
    val member=requireNotNull(state.directory).teams.single {it.id==current.teamID}
    val id=UUID.randomUUID().toString();val body=buildJsonObject {put("operationID",id);fields()}
    val pending=PendingMatchWatch(id,current.teamID,current.id,action,body,member.memberID,member.accessEpoch,state.directory.recoveryEpoch,current.writerEpoch)
    storage.save(state.copy(watchPending=state.watchPending+(current.id to pending),watchFailures=state.watchFailures-current.id))
    id
}
fun TeamDirectorySession.prepareWatchMode(expected: SharedMatchDocument,mode: HistoricalWatchMode,confirmed: Boolean): String {
    require(confirmed) {"请先确认观看范围；关闭时会撤销指定人员及旧邀请"}
    return prepareWatching(expected,MatchWatchAction.access) {put("mode",mode.name);put("baseAccessVersion",expected.accessVersion)}
}
fun TeamDirectorySession.prepareViewerMember(expected: SharedMatchDocument,memberID: String,allow: Boolean): String =
    prepareWatching(expected,MatchWatchAction.viewer) {put("memberID",appleUUID(memberID));put("allow",allow);put("baseAccessVersion",expected.accessVersion)}
fun TeamDirectorySession.prepareExternalViewer(expected: SharedMatchDocument,viewer: MatchViewer,allow: Boolean): String {
    require(viewer.memberID==null && (!allow || viewer.status=="pending")) {"已撤销的观看设备需重新申请"}
    return prepareWatching(expected,MatchWatchAction.viewer) {put("viewerID",appleUUID(viewer.id));put("allow",allow);put("baseAccessVersion",expected.accessVersion)}
}
fun TeamDirectorySession.prepareWatchInvitation(expected: SharedMatchDocument): String = prepareWatching(expected,MatchWatchAction.invitation) {put("token",TeamSecrets.random())}
fun TeamDirectorySession.prepareWatchTicket(expected: SharedMatchDocument): String = prepareWatching(expected,MatchWatchAction.ticket) {put("token",TeamSecrets.random())}
fun TeamDirectorySession.prepareSharedLive(expected: SharedMatchDocument,enabled: Boolean,confirmed: Boolean): String {
    require(confirmed && (!enabled || !expected.finalForWatching)) {"请核对直播公开范围；终场不能重新开播"}
    return prepareWatching(expected,MatchWatchAction.live) {put("enabled",enabled)}
}
fun TeamDirectorySession.sharedLiveHeartbeat(teamID: String,gameID: String): SharedMatchDocument = synchronized(storage.monitor) {
    val current=match(teamID,gameID)
    require(current.canWrite && !current.finalForWatching && !current.deleted) {"仅当前记分设备可维持进行中的直播"}
    val result=SharedMatchDocument(client.transport.request("POST","teams/${current.teamID}/games/${current.id}/live-heartbeat",buildJsonObject {}))
    require(result.id==current.id && result.teamID==current.teamID)
    match(teamID,gameID)
}

/** Explicit retries retain request/token identity. A terminal receipt is evidence,
 * never permission: callers re-read the current fenced document before sharing.
 */
fun TeamDirectorySession.sendWatching(gameID: String): CompletedMatchWatch = synchronized(storage.monitor) {
    val id=appleUUID(gameID);val pending=requireNotNull(storage.load().watchPending[id]) {"没有待确认的观看操作"}
    val current=match(pending.teamID,id);var state=storage.load()
    val member=state.directory?.teams?.singleOrNull {it.id==pending.teamID}
    check(!state.requiresRecoveryReview && !state.credentialExpired && state.directory?.recoveryEpoch==pending.recoveryEpoch &&
        member?.memberID==pending.memberID && member.accessEpoch==pending.accessEpoch) {"球队授权已变化，旧观看操作已保留，不会自动重做"}
    if(pending.action==MatchWatchAction.live) check(current.writerEpoch==pending.writerEpoch) {"记分设备已变化，旧直播操作已保留"}
    if(id in state.watchFailures) {state=state.copy(watchFailures=state.watchFailures-id);storage.save(state)}
    val response=try {client.transport.request("POST",watchPath(pending),pending.body)} catch(fault: TeamServiceFault) {
        val failed=storage.load();storage.save(failed.copy(watchFailures=failed.watchFailures+(id to DirectoryFailure(pending.id,fault.status,fault.code)),requiresRecoveryReview=failed.requiresRecoveryReview || fault.requiresRecoveryReview));throw fault
    }
    when(pending.action) {
        MatchWatchAction.access,MatchWatchAction.live -> SharedMatchDocument(response).also {require(it.id==id && it.teamID==pending.teamID)}
        MatchWatchAction.viewer -> require(response["updated"]?.jsonPrimitive?.boolean==true)
        MatchWatchAction.invitation -> {appleUUID(response.getValue("invitationID").jsonPrimitive.content);require(response.getValue("expiresAt").jsonPrimitive.long>0)}
        MatchWatchAction.ticket -> require(response.getValue("expiresAt").jsonPrimitive.long>0)
    }
    val completed=CompletedMatchWatch(pending,response)
    var links=state.watchLinks
    if(pending.action in setOf(MatchWatchAction.invitation,MatchWatchAction.ticket)) links=links+(id to MatchWatchLink(id,pending.id,pending.body.getValue("token").jsonPrimitive.content,
        response.getValue("expiresAt").jsonPrimitive.long,current.accessVersion,pending.action==MatchWatchAction.ticket))
    if(pending.action==MatchWatchAction.access) links=links-id
    storage.save(state.copy(watchPending=state.watchPending-id,watchReceipts=state.watchReceipts+(id to completed),watchFailures=state.watchFailures-id,watchLinks=links))
    completed
}

fun TeamDirectorySession.parkWatching(gameID: String,confirmed: Boolean) = synchronized(storage.monitor) {
    val id=appleUUID(gameID);val state=storage.load();val pending=requireNotNull(state.watchPending[id]);val failure=state.watchFailures[id]
    require(confirmed && failure?.operationID==pending.id && failure.status in setOf(400,401,403,404,409,410,413,426)) {"结果尚不确定，请先续取；不能换编号重试"}
    val preserved=state.preservingRecoveryWork(state.directory?.recoveryEpoch ?: pending.recoveryEpoch)
    storage.save(preserved.copy(watchPending=preserved.watchPending-id,watchFailures=preserved.watchFailures-id))
}
