package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Preserve the whole server document, including unknown payload/history fields.
 * Reading and handing off an opaque document does not claim Android can edit it.
 */
@Serializable data class SharedMatchDocument(val raw: JsonObject) {
    val id: String get() = appleUUID(raw.getValue("id").jsonPrimitive.content)
    val teamID: String get() = appleUUID(raw.getValue("teamID").jsonPrimitive.content)
    val revision: Long get() = raw.getValue("serverRevision").jsonPrimitive.long
    val writerEpoch: Long get() = raw.getValue("writerEpoch").jsonPrimitive.long
    val writerDeviceID: String get() = appleUUID(raw.getValue("writerDeviceID").jsonPrimitive.content)
    val digest: String get() = raw.getValue("digest").jsonPrimitive.content
    val deleted: Boolean get() = raw.getValue("deleted").jsonPrimitive.boolean
    val canWrite: Boolean get() = raw.getValue("canWrite").jsonPrimitive.boolean
    val payload: JsonObject? get() = raw["payload"]?.takeUnless { it == JsonNull }?.jsonObject
    val handoff: JsonObject? get() = raw["handoff"]?.takeUnless { it == JsonNull }?.jsonObject
    init {
        id; teamID; writerDeviceID; appleUUID(raw.getValue("responsibleMemberID").jsonPrimitive.content)
        require(revision > 0 && writerEpoch > 0 && Regex("[a-f0-9]{64}").matches(digest))
        deleted; canWrite
        handoff?.let { h -> for (key in listOf("id","sourceDeviceID","targetDeviceID")) appleUUID(h.getValue(key).jsonPrimitive.content) }
        payload?.let { appleUUID(it.getValue("id").jsonPrimitive.content) }
    }
    override fun toString() = "SharedMatchDocument(id=$id, revision=$revision, writerEpoch=$writerEpoch)"
}
@Serializable data class CachedSharedMatch(val document: SharedMatchDocument, val memberID: String, val accessEpoch: Long?, val recoveryEpoch: String)
@Serializable enum class MatchControl { propose, accept, cancel, force }
@Serializable data class PendingMatchControl(
    val id: String, val teamID: String, val gameID: String, val kind: MatchControl,
    val body: JsonObject, val memberID: String, val accessEpoch: Long?, val recoveryEpoch: String,
) {
    init { listOf(id,teamID,gameID,memberID,recoveryEpoch).forEach(::appleUUID); require(body["operationID"]?.jsonPrimitive?.content == id) }
    override fun toString() = "PendingMatchControl(id=$id, kind=$kind)"
}
@Serializable data class CompletedMatchControl(val request: PendingMatchControl, val response: SharedMatchDocument)

private fun matchPath(teamID: String, gameID: String) = "teams/${appleUUID(teamID)}/games/${appleUUID(gameID)}"
private fun TeamDirectorySession.member(teamID: String): Pair<TeamDirectory,RemoteTeam> {
    val directory = refresh()
    check(!storage.load().requiresRecoveryReview) { "请先核对恢复后的共享资料" }
    return directory to (directory.teams.singleOrNull { it.id.equals(teamID,true) } ?: error("当前设备无权访问这支球队"))
}

fun TeamDirectorySession.match(teamID: String, gameID: String): SharedMatchDocument = synchronized(storage.monitor) {
    val (directory,member) = member(teamID)
    val document = try { SharedMatchDocument(client.transport.request("GET",matchPath(teamID,gameID),null)) } catch (fault: TeamServiceFault) {
        if (fault.status in setOf(401,403,404)) {
            val state=storage.load()
            storage.save(state.copy(matches=state.matches.filterValues { !it.document.teamID.equals(teamID,true) },matchCursors=state.matchCursors - appleUUID(teamID)))
        }
        throw fault
    }
    require(document.id == appleUUID(gameID) && document.teamID == appleUUID(teamID))
    val (final,finalMember) = member(teamID); val state = storage.load()
    check(directory.recoveryEpoch == final.recoveryEpoch && member == finalMember) { "权限或球队资料正在变化，请重新读取" }
    val old = state.matches[document.id]
    check(old == null || document.revision >= old.document.revision && document.writerEpoch >= old.document.writerEpoch) { "比赛修订或记分代次倒退，请核对服务恢复状态" }
    storage.save(state.copy(matches=state.matches + (document.id to CachedSharedMatch(document,member.memberID,member.accessEpoch,directory.recoveryEpoch))))
    document
}

/** Incremental metadata with tombstones. Payloads are retained only for equal
 * digests; never associate a changed score with an earlier private document.
 */
fun TeamDirectorySession.matches(teamID: String): List<SharedMatchDocument> = synchronized(storage.monitor) {
    val id = appleUUID(teamID); val (directory,member) = member(id); var state = storage.load()
    var cursor = state.matchCursors[id] ?: 0; var matches = state.matches; var restarted = false; var finished = false
    repeat(128) {
        if (finished) return@repeat
        val page = try { client.transport.request("GET","teams/$id/games?cursor=$cursor",null) } catch (fault: TeamServiceFault) {
            if (fault.status == 410 && fault.code == "cursor_expired" && !restarted) {
                restarted=true; cursor=0; matches=matches.filterValues { it.document.teamID != id }
                return@repeat
            }
            throw fault
        }
        check(page.getValue("recoveryEpoch").jsonPrimitive.content == directory.recoveryEpoch) { "服务恢复代次正在变化，未应用这批比赛" }
        val next = page.getValue("cursor").jsonPrimitive.long; val more = page.getValue("hasMore").jsonPrimitive.boolean
        check(next >= cursor && (!more || next > cursor)) { "比赛游标无效，原资料已保留" }
        for (raw in page.getValue("games").jsonArray) {
            val incoming = SharedMatchDocument(raw.jsonObject); require(incoming.teamID == id)
            val old = matches[incoming.id]?.document
            check(old == null || incoming.revision >= old.revision && incoming.writerEpoch >= old.writerEpoch) { "共享比赛修订倒退，未应用列表" }
            val preserved = if (old != null && old.digest == incoming.digest && !incoming.deleted) SharedMatchDocument(JsonObject(old.raw + incoming.raw)) else incoming
            matches = matches + (incoming.id to CachedSharedMatch(preserved,member.memberID,member.accessEpoch,directory.recoveryEpoch))
        }
        cursor=next; finished=!more
    }
    check(finished) { "比赛数量超过本次同步上限，游标未提前确认" }
    val (final,finalMember) = member(id); state=storage.load()
    check(directory.recoveryEpoch == final.recoveryEpoch && member == finalMember) { "权限或球队资料正在变化，未应用列表" }
    val teamMatches=matches.filterValues { it.document.teamID == id }
    storage.save(state.copy(matches=state.matches.filterValues { it.document.teamID != id } + teamMatches,matchCursors=state.matchCursors + (id to cursor)))
    matches.values.filter { it.document.teamID == id }.map { it.document }
}

fun TeamDirectorySession.matchParticipants(teamID: String, gameID: String): JsonArray = synchronized(storage.monitor) {
    member(teamID); client.transport.request("GET",matchPath(teamID,gameID)+"/participants",null).getValue("devices").jsonArray
}

/** The caller supplies the version actually reviewed. A changed version requires
 * another explicit review; no silent force takeover against a newer snapshot.
 */
fun TeamDirectorySession.prepareMatchControl(expected: SharedMatchDocument, kind: MatchControl, targetDeviceID: String? = null, confirmRisk: Boolean = false): String = synchronized(storage.monitor) {
    check(storage.load().matchPending[expected.id] == null) { "这场比赛已有结果未明的操作，请先续取" }
    val current = match(expected.teamID,expected.id); val state=storage.load(); val directory=requireNotNull(state.directory)
    val member = directory.teams.single { it.id.equals(current.teamID,true) }
    check(!current.deleted && current.revision == expected.revision && current.writerEpoch == expected.writerEpoch && current.digest == expected.digest && current.handoff == expected.handoff) { "比赛或交接已有变化，请重新核对" }
    val ownDevice = requireNotNull(state.deviceID)
    val id=UUID.randomUUID().toString()
    val body = buildJsonObject {
        put("operationID",id); put("baseRevision",current.revision); put("writerEpoch",current.writerEpoch)
        when (kind) {
            MatchControl.propose -> { require(current.canWrite && current.writerDeviceID == ownDevice && targetDeviceID != null && !targetDeviceID.equals(ownDevice,true)); put("targetDeviceID",appleUUID(targetDeviceID)) }
            MatchControl.accept -> {
                val h=requireNotNull(current.handoff); require(h.getValue("targetDeviceID").jsonPrimitive.content.equals(ownDevice,true))
                check(current.payload != null) { "必须先保存完整比赛，不能只接收比分摘要" }
                put("handoffID",appleUUID(h.getValue("id").jsonPrimitive.content)); put("digest",current.digest)
            }
            MatchControl.cancel -> { val h=requireNotNull(current.handoff); require(h.getValue("sourceDeviceID").jsonPrimitive.content.equals(ownDevice,true)); put("handoffID",appleUUID(h.getValue("id").jsonPrimitive.content)) }
            MatchControl.force -> { require(confirmRisk && member.role == TeamRole.admin && targetDeviceID != null) { "接管需要管理员明确核对旧设备未同步风险" }; put("targetDeviceID",appleUUID(targetDeviceID)); put("confirmRisk",true) }
        }
    }
    storage.save(state.copy(matchPending=state.matchPending + (current.id to PendingMatchControl(id,current.teamID,current.id,kind,body,member.memberID,member.accessEpoch,directory.recoveryEpoch))))
    id
}

fun TeamDirectorySession.sendMatchControl(gameID: String): CompletedMatchControl = synchronized(storage.monitor) {
    val id=appleUUID(gameID); val pending=requireNotNull(storage.load().matchPending[id])
    val (directory,member)=member(pending.teamID); val state=storage.load()
    check(directory.recoveryEpoch == pending.recoveryEpoch && member.memberID.equals(pending.memberID,true) && member.accessEpoch == pending.accessEpoch) { "共享权限代次已变化，原操作保留待核对" }
    val response=SharedMatchDocument(client.transport.request("POST",matchPath(pending.teamID,id)+"/handoff/${pending.kind.name}",pending.body))
    require(response.id == id && response.teamID == pending.teamID)
    val receipt=CompletedMatchControl(pending,response)
    // Old idempotent responses prove receipt only. They never revive cached
    // authority; a separate authoritative GET must restore canWrite.
    val old=state.matches[id]
    val safe = old?.copy(document=SharedMatchDocument(JsonObject(old.document.raw + ("canWrite" to JsonPrimitive(false)))))
    storage.save(state.copy(matchPending=state.matchPending - id,matchReceipts=state.matchReceipts + (id to receipt),matches=if(safe==null) state.matches else state.matches + (id to safe)))
    runCatching { match(pending.teamID,id) }
    receipt
}
