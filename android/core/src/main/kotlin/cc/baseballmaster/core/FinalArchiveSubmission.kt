package cc.baseballmaster.core

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A frozen, explicit post-game import request. It never serializes a native Game as wire. */
@Serializable data class PendingFinalArchive(
    val id: String, val teamID: String, val sourceID: String, val localRevision: Long,
    val sourceDigest: String, val memberID: String, val accessEpoch: Long?, val recoveryEpoch: String,
    val body: JsonObject, val previewReady: Boolean = false,
) {
    init {
        listOf(id,teamID,sourceID,memberID,recoveryEpoch).forEach(::appleUUID)
        require(localRevision >= 0 && Regex("[a-f0-9]{64}").matches(sourceDigest))
        require(body.getValue("operationID").jsonPrimitive.content == id && body.getValue("sourceID").jsonPrimitive.content == sourceID)
    }
    val key get() = "$teamID:$sourceID"
    override fun toString() = "PendingFinalArchive(id=$id, previewReady=$previewReady)"
}
@Serializable data class FinalArchiveReceipt(val request: PendingFinalArchive, val document: SharedMatchDocument) {
    override fun toString() = "FinalArchiveReceipt(id=${request.id}, revision=${request.localRevision})"
}
private fun digestArchive(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Only a replay-validated complete source envelope is accepted. Native export remains separately gated.
 * A local finish, app startup, roster selection or old shared match never calls this implicitly.
 */
fun TeamDirectorySession.stageFinalArchive(bytes: ByteArray, gameID: String, teamID: String, confirmed: Boolean): PendingFinalArchive = synchronized(storage.monitor) {
    require(confirmed) { "请明确选择共享这份完整终场档案" }
    val source = AppleSourceBackup.prepare(bytes)
    val game = source.games().single { it.id == appleUUID(gameID) }
    require(game.isFinal && !game.practice && game.endedAt != null) { "只能提交已经完整保存在本机的终场档案" }
    val team = appleUUID(teamID); val key = "$team:${game.id}"; val hash = digestArchive(bytes)
    storage.load().finalArchivePending[key]?.let {
        check(it.sourceDigest == hash && it.localRevision == game.revision) { "已有另一版本结果待确认，原请求保留" }
        return@synchronized it
    }
    check(storage.load().finalArchiveReceipts[key] == null) { "该来源已提交；请核对已有关联，不能另建副本" }
    check(storage.load().recoveryWork.none { work -> work.finalRequests.any { it.key == key } || work.finalReceipts.any { it.request.key == key } }) { "该来源有恢复前的提交记录，请先人工核对，不能重新建赛" }
    val profile = roster(team, true); val state = storage.load(); val directory = requireNotNull(state.directory)
    val member = directory.teams.single { it.id == team }
    require(member.role != TeamRole.member) { "当前角色不能提交球队档案" }
    val own = listOf(game.home,game.away).single { it.id == game.ourTeamID }
    val rosterSources = profile.players.map { appleUUID(it.sourceID) }.toSet()
    require(own.players.all { it.id in rosterSources }) { "球员对应仍需核对，本轮只接受原身份一致的完整档案" }
    val original = AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.map { it.jsonObject }
        .single { appleUUID(it.getValue("id").jsonPrimitive.content) == game.id }
    require(listOf("sharedTeamID","sharedGameID","sharedPlayerLinks","historySourceID").all { original[it] == null || original[it] == JsonNull }) { "已关联旧共享比赛请先核对，不能自动补传或新建副本" }
    val id = UUID.randomUUID().toString(); val copyID = UUID.randomUUID().toString()
    val payload = JsonObject(original + mapOf("id" to JsonPrimitive(copyID), "minimumWriterFormat" to JsonPrimitive(2)))
    val body = buildJsonObject {
        put("operationID",id);put("formatVersion",2);put("payload",payload)
        put("projection",LiveProjection.create(game.copy(id=copyID)))
        put("sourceID",game.id);put("sourceTeamID",own.id)
        // The service target is roster.sourceID, not the roster row's editing ID.
        put("playerLinks",buildJsonArray {own.players.forEach { player -> add(buildJsonObject {
            put("sourceID",player.id);put("targetID",profile.players.single {appleUUID(it.sourceID)==player.id}.sourceID)
        }) }})
    }
    val pending = PendingFinalArchive(id,team,game.id,game.revision,hash,member.memberID,member.accessEpoch,directory.recoveryEpoch,body)
    storage.save(state.copy(finalArchivePending=state.finalArchivePending + (key to pending)))
    pending
}

private fun TeamDirectorySession.authorizeFinalArchive(expected: PendingFinalArchive): TeamDirectoryState {
    val directory = refresh(); val state = storage.load()
    check(!state.requiresRecoveryReview && !state.credentialExpired) { "请先核对恢复后的授权，原提交意图保留" }
    val member = directory.teams.singleOrNull { it.id == expected.teamID }
        ?: error("当前无权访问球队，原终场档案保留待核对")
    check(directory.recoveryEpoch == expected.recoveryEpoch && member.memberID == expected.memberID &&
        member.accessEpoch == expected.accessEpoch && member.role != TeamRole.member) { "共享权限代次已变化，原终场档案保留待核对" }
    check(state.finalArchivePending[expected.key] == expected) { "终场提交请求已变化，请重新读取" }
    return state
}

/** Preview is read-only. A stale token must be reviewed explicitly; send never re-previews itself. */
fun TeamDirectorySession.previewFinalArchive(key: String, independent: Boolean = false): JsonObject = synchronized(storage.monitor) {
    val pending = requireNotNull(storage.load().finalArchivePending[key]); authorizeFinalArchive(pending)
    check(!pending.previewReady) { "提交快照已固定，请先续取原结果" }
    val preview = finalArchiveRequest(pending,"preview")
    val status = preview.getValue("status").jsonPrimitive.content
    if (status == "new" || status == "similar" && independent) {
        val state = authorizeFinalArchive(pending)
        val body = JsonObject(pending.body + mapOf("previewToken" to preview.getValue("token"), "independent" to JsonPrimitive(independent)))
        storage.save(state.copy(finalArchivePending=state.finalArchivePending + (key to pending.copy(body=body,previewReady=true))))
    }
    preview
}

/** Retrying reuses the exact request and operation ID after timeout, response loss or process restart.
 * Receipt only acknowledges sentLocalRevision; it never installs or overwrites a newer local Game.
 */
fun TeamDirectorySession.sendFinalArchive(key: String): FinalArchiveReceipt = synchronized(storage.monitor) {
    val pending = requireNotNull(storage.load().finalArchivePending[key]); val state = authorizeFinalArchive(pending)
    check(pending.previewReady) { "请先预览重复来源和球员对应" }
    val raw = finalArchiveRequest(pending,"commit")
    val document = SharedMatchDocument(raw)
    require(document.teamID == pending.teamID)
    val receipt = FinalArchiveReceipt(pending,document)
    storage.save(state.copy(finalArchivePending=state.finalArchivePending - key, finalArchiveReceipts=state.finalArchiveReceipts + (key to receipt)))
    receipt
}

private fun TeamDirectorySession.finalArchiveRequest(pending: PendingFinalArchive, action: String): JsonObject = try {
    client.transport.request("POST","teams/${pending.teamID}/history-imports/$action",pending.body)
} catch (fault: TeamServiceFault) {
    if (fault.requiresRecoveryReview) storage.save(storage.load().copy(requiresRecoveryReview=true))
    // A 401 is routed through TeamDirectoryClient.authorizationExpired, which archives old work.
    throw fault
}

/** Isolate revoked/access-changed requests without discarding their full snapshot. */
internal fun TeamDirectoryState.isolateFinalArchives(epoch: String, accessible: (String)->Boolean): TeamDirectoryState {
    val pending=finalArchivePending.filterValues { !accessible(it.teamID) }
    val receipts=finalArchiveReceipts.filterValues { !accessible(it.request.teamID) }
    if(pending.isEmpty() && receipts.isEmpty()) return this
    val saved=PreservedRecoveryWork(UUID.randomUUID().toString(),directory?.recoveryEpoch,epoch,System.currentTimeMillis(),
        finalRequests=pending.values.filterNot {p -> recoveryWork.any {w -> w.finalRequests.any {it.id==p.id}}},
        finalReceipts=receipts.values.filterNot {p -> recoveryWork.any {w -> w.finalReceipts.any {it.request.id==p.request.id}}})
    val work=if(saved.finalRequests.isEmpty() && saved.finalReceipts.isEmpty()) recoveryWork else recoveryWork+saved
    return copy(finalArchivePending=finalArchivePending-pending.keys,finalArchiveReceipts=finalArchiveReceipts-receipts.keys,recoveryWork=work)
}
