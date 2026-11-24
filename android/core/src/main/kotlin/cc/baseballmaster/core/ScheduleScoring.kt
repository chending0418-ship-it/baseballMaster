package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ScheduleRole(
    val epoch: Long, val role: String, val active: Boolean, val closed: Boolean, val holdsDevice: Boolean,
    val scorer: String? = null, val lineup: String? = null,
    val scorerName: String? = null, val lineupName: String? = null,
    val canPrepare: Boolean = false, val openingReady: Boolean = false, val openingConfirmed: Boolean = false,
    val transfer: ScheduleTransfer? = null, val hasCheckpoint: Boolean = false,
) {
    val label get() = when {
        scorer == null -> "待指派 · 创建比赛的管理员负责"
        lineup == null -> "整场记录 · ${scorerName ?: "已指派"}"
        else -> "局面 · ${scorerName ?: "已指派"}　阵容 · ${lineupName ?: "已指派"}"
    }
}

@Serializable data class ScheduleTransfer(val id: String, val targetName: String)
@Serializable data class ScheduleReceipt(val id: String, val applied: Boolean)
@Serializable data class ScheduledLineupDraft(
    val member: String, val device: String, val recovery: String, val accessEpoch: Long?,
    val epoch: Long, val proposal: JsonObject,
) {
    val summary get() = proposal.getValue("lineup").jsonObject.getValue("changes").jsonArray.joinToString("、") { it.jsonPrimitive.content }
    val id get() = proposal.getValue("id").jsonPrimitive.content
}

data class ScheduleScoringDetail(
    val access: ScheduleRole, val version: Long, val frame: ScheduleField?,
    val pending: String?, val receipt: ScheduleReceipt?, val draft: ScheduledLineupDraft?,
    val memberID: String, val deviceID: String?, val recoveryEpoch: String?, val accessEpoch: Long?,
)

private val scoringJson get() = TeamDirectoryJson.format
private fun TeamDirectoryState.member(notice: TeamScheduleNotice): RemoteTeam {
    check(!credentialExpired && !requiresRecoveryReview && deviceID != null) { "请先核对球队设备身份" }
    return directory?.teams?.singleOrNull { it.id.equals(notice.teamID, true) } ?: error("当前不在这支球队")
}

internal data class CachedScheduleRead(val key: String, val member: RemoteTeam, val device: String?,
    val recovery: String?, val detail: ScheduleScoringDetail)

private fun TeamDirectorySession.readScoring(notice: TeamScheduleNotice, draft: ScheduledLineupDraft?): ScheduleScoringDetail {
    val state = snapshot()
    val member = state.member(notice)
    val previous = lastScheduleRead?.takeIf { it.key == notice.key && it.member == member && it.device == state.deviceID &&
        it.recovery == state.directory?.recoveryEpoch }?.detail?.takeIf { it.frame != null }
    val path = "teams/${appleUUID(notice.teamID)}/schedules/${appleUUID(notice.id)}/scoring"
    val query = previous?.let { "?version=${it.version}&epoch=${it.access.epoch}" }.orEmpty()
    val raw = try { client.transport.request("GET", path + query, null) }
    catch (e: TeamServiceFault) {
        if (e.status in listOf(401,403,404,426)) lastScheduleRead = null
        throw e
    }
    fun field(key: String) = raw[key]?.takeUnless { it == JsonNull }
    var frame = field("frame")?.let { scoringJson.decodeFromJsonElement<ScheduleField>(it) }
    if (field("reuseFrame")?.jsonPrimitive?.boolean == true) frame = requireNotNull(previous?.frame)
    if (field("reusePlayers")?.jsonPrimitive?.boolean == true) {
        val old = requireNotNull(previous?.frame)
        val fresh = requireNotNull(frame)
        val teams = listOf("homeTeam","awayTeam").associateWith { side ->
            val team = fresh.state.getValue(side).jsonObject
            val oldTeam = old.state.getValue(side).jsonObject
            check(team["id"] == oldTeam["id"])
            JsonObject(team + ("players" to oldTeam.getValue("players")))
        }
        frame = fresh.copy(state = JsonObject(fresh.state + teams))
    }
    val detail = ScheduleScoringDetail(scoringJson.decodeFromJsonElement(raw), raw.getValue("version").jsonPrimitive.long,
        frame, field("proposal")?.jsonObject?.getValue("id")?.jsonPrimitive?.content,
        field("receipt")?.let { scoringJson.decodeFromJsonElement(it) }, draft, member.memberID, state.deviceID, state.directory?.recoveryEpoch, member.accessEpoch)
    lastScheduleRead = if (field("compactSupported")?.jsonPrimitive?.boolean == true && frame != null)
        CachedScheduleRead(notice.key, member, state.deviceID, state.directory?.recoveryEpoch, detail) else null
    return detail
}

fun TeamDirectorySession.scoringDetail(notice: TeamScheduleNotice, checkCapability: Boolean = true): ScheduleScoringDetail = synchronized(storage.monitor) {
    val state = snapshot()
    val member = state.member(notice)
    if (checkCapability) {
        val supported = client.transport.request("GET", "capabilities", null)["capabilities"]?.jsonArray.orEmpty()
        check(JsonPrimitive("schedule-scoring") in supported) { "球队服务尚未支持单场岗位，本场仅供查看" }
    }
    val draft = state.lineupDrafts[notice.key]?.takeIf { it.member == member.memberID && it.device == state.deviceID }
    readScoring(notice, draft)
}

private fun TeamDirectorySession.scoringWrite(notice: TeamScheduleNotice, action: String, epoch: Long,
    id: String = UUID.randomUUID().toString(), fields: JsonObjectBuilder.() -> Unit = {}) {
    snapshot().member(notice)
    val body = buildJsonObject { put("operationID", id); put("epoch", epoch); fields() }
    val path = "teams/${appleUUID(notice.teamID)}/schedules/${appleUUID(notice.id)}/scoring/$action"
    check(client.transport.request("POST", path, body)["ok"]?.jsonPrimitive?.boolean == true) { "请刷新核对操作结果" }
}

fun TeamDirectorySession.assignScorers(notice: TeamScheduleNotice, epoch: Long, scorer: String?, lineup: String?) = synchronized(storage.monitor) {
    check(snapshot().member(notice).role == TeamRole.admin) { "只有球队管理员可以指派" }
    require(lineup == null || scorer != null && !lineup.equals(scorer, true)) { "两项岗位须由不同的队员担任" }
    scoringWrite(notice, "assign", epoch) {
        scorer?.let { put("scorer", appleUUID(it)) }
        lineup?.let { put("lineup", appleUUID(it)) }
    }
}

fun TeamDirectorySession.prepareSchedule(notice: TeamScheduleNotice, detail: ScheduleScoringDetail, game: Game) = synchronized(storage.monitor) {
    check(detail.access.canPrepare && game.id.equals(notice.id, true) && game.isScheduled) { "请由发布人提供本场先发" }
    val frame = ScheduleField.opening(game)
    scoringWrite(notice, "prepare", detail.access.epoch) {
        put("version", detail.version); put("frame", scoringJson.encodeToJsonElement(frame))
    }
}

fun TeamDirectorySession.claimLineup(notice: TeamScheduleNotice, epoch: Long) = synchronized(storage.monitor) {
    scoringWrite(notice, "joinLineup", epoch)
}

fun TeamDirectorySession.confirmLineup(notice: TeamScheduleNotice, detail: ScheduleScoringDetail) = synchronized(storage.monitor) {
    scoringWrite(notice, "confirmLineup", detail.access.epoch) { put("version", detail.version) }
}

fun TeamDirectorySession.saveLineupDraft(notice: TeamScheduleNotice, detail: ScheduleScoringDetail, command: Command) = synchronized(storage.monitor) {
    val state = snapshot()
    val member = state.member(notice)
    check(detail.access.role == "lineup" && detail.access.holdsDevice && !detail.access.closed && detail.access.transfer == null &&
        member.role != TeamRole.member && member.memberID.equals(detail.access.lineup, true)) { "阵容岗位已变化" }
    check(detail.memberID == member.memberID && detail.deviceID == state.deviceID &&
        detail.recoveryEpoch == state.directory?.recoveryEpoch && detail.accessEpoch == member.accessEpoch) { "球队身份已变化，请重新核对阵容" }
    val previous = state.lineupDrafts[notice.key]
    check(previous == null || detail.receipt?.id == previous.id) { "请先核对上一笔阵容草稿" }
    val proposal = requireNotNull(detail.frame).proposal(notice.id, command, detail.version)
    val draft = ScheduledLineupDraft(member.memberID, requireNotNull(state.deviceID), requireNotNull(state.directory).recoveryEpoch,
        member.accessEpoch, detail.access.epoch, proposal)
    storage.save(state.copy(lineupDrafts = state.lineupDrafts + (notice.key to draft)))
}

fun TeamDirectorySession.sendLineupDraft(notice: TeamScheduleNotice) = synchronized(storage.monitor) {
    val state = snapshot()
    val member = state.member(notice)
    val draft = requireNotNull(state.lineupDrafts[notice.key]) { "没有待发送的草稿" }
    check(draft.member == member.memberID && draft.device == state.deviceID && draft.accessEpoch == member.accessEpoch &&
        draft.recovery == state.directory?.recoveryEpoch) { "球队身份已变化，原草稿已保留" }
    val detail = readScoring(notice, draft)
    if (detail.receipt?.id == draft.id || detail.pending == draft.id) return@synchronized
    check(detail.access.epoch == draft.epoch && detail.access.holdsDevice && detail.access.role == "lineup") { "岗位已变化，原草稿已保留" }
    scoringWrite(notice, "propose", draft.epoch, draft.id) { put("proposal", draft.proposal) }
}

fun TeamDirectorySession.discardLineupDraft(notice: TeamScheduleNotice, id: String) = synchronized(storage.monitor) {
    val current = scoringDetail(notice)
    val draft = requireNotNull(current.draft)
    check(draft.id == id && current.pending != id) { "已送达的阵容操作需等候回执" }
    val state = snapshot()
    storage.save(state.copy(lineupDrafts = state.lineupDrafts - notice.key))
}
