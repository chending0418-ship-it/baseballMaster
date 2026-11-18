package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

private const val MAX_SCHEDULE_TIME = 253_402_300_800_000L
private const val MAX_WIRE_REVISION = 9_007_199_254_740_991L

@Serializable
data class TeamScheduleNotice(
    val teamID: String,
    val id: String,
    val startsAt: Long,
    val away: String,
    val home: String,
    val venue: String,
    val cancelled: Boolean,
    val revision: Long,
) {
    init {
        appleUUID(teamID)
        appleUUID(id)
        require(revision in 1..MAX_WIRE_REVISION)
        validateScheduleFields(startsAt, away, home, venue)
    }

    val key: String get() = "${appleUUID(teamID)}/${appleUUID(id)}"
}

@Serializable
data class TeamScheduleRequest(
    val operationID: String,
    val expectedRevision: Long,
    val startsAt: Long,
    val away: String,
    val home: String,
    val venue: String,
    val cancelled: Boolean,
) {
    init {
        appleUUID(operationID)
        require(expectedRevision in 0 until MAX_WIRE_REVISION)
        validateScheduleFields(startsAt, away, home, venue)
    }
}

@Serializable
internal data class ScheduleAuthorization(
    internal val deviceID: String,
    internal val memberID: String,
    internal val accessEpoch: Long,
    internal val recoveryEpoch: String,
    internal val role: TeamRole,
)

/** Stored in the device's encrypted team directory, outside the local backup. */
@Serializable
@ConsistentCopyVisibility
data class TeamSchedulePublication internal constructor(
    val teamID: String,
    val sourceID: String,
    val notice: TeamScheduleNotice? = null,
    val pending: TeamScheduleRequest? = null,
    val needsReview: Boolean = false,
    val needsLocalSync: Boolean = false,
    val local: Boolean = true,
    internal val authorization: ScheduleAuthorization? = null,
) {
    init {
        appleUUID(teamID)
        appleUUID(sourceID)
        require(notice == null || notice.teamID.equals(teamID, true) && notice.id.equals(sourceID, true))
        require((pending == null) == (authorization == null))
        require(pending == null || pending.expectedRevision == revision)
        require(!needsLocalSync || local && notice != null)
    }

    val revision: Long get() = notice?.revision ?: 0L
    val cancelled: Boolean get() = notice?.cancelled == true
    val key: String get() = "${appleUUID(teamID)}/${appleUUID(sourceID)}"
}

data class TeamScheduleFeed(
    val notices: List<TeamScheduleNotice> = emptyList(),
    val memberships: List<RemoteTeam> = emptyList(),
    val publications: Map<String, TeamSchedulePublication> = emptyMap(),
    val remotePublications: Map<String, TeamSchedulePublication> = emptyMap(),
    val bindings: List<SharedTeamBinding> = emptyList(),
    val message: String? = null,
) {
    fun teamFor(ownID: String?): RemoteTeam? {
        if (ownID == null) return null
        val cloudID = bindings.firstOrNull { it.localTeamID.equals(ownID, true) }?.cloudTeamID ?: ownID
        return memberships.singleOrNull { it.id.equals(cloudID, true) && it.role != TeamRole.member }
    }
}

@Serializable private data class TeamScheduleResponse(val schedules: List<TeamScheduleNotice>)
@Serializable private data class TeamScheduleReceipt(val id: String, val teamID: String, val revision: Long)

fun normalizeScheduleVenue(venue: String): String {
    val text = venue.trim()
    require(text.length <= 120 && text.none { it.isISOControl() }) { "场地请使用单行文字，最多 120 个字符。" }
    return text
}

private fun validateScheduleFields(startsAt: Long, away: String, home: String, venue: String) {
    require(startsAt in 1 until MAX_SCHEDULE_TIME) { "开赛时间无效。" }
    fun validName(text: String) = text.length <= 120 && text.isNotBlank() && text.none { it.code <= 31 || it.code == 127 }
    require(validName(away) && validName(home)) { "球队名称请使用单行文字，最多 120 个字符。" }
    require(venue.isEmpty() || validName(venue)) { "场地请使用单行文字，最多 120 个字符。" }
}

private fun TeamDirectoryClient.readSchedules(): List<TeamScheduleNotice> {
    val notices = TeamDirectoryJson.format.decodeFromJsonElement<TeamScheduleResponse>(
        transport.request("GET", "team-schedules", null),
    ).schedules
    require(notices.map { it.key }.toSet().size == notices.size) { "球队赛程包含重复编号。" }
    return notices
}

private fun TeamDirectoryClient.writeSchedule(teamID: String, sourceID: String, request: TeamScheduleRequest): TeamScheduleNotice {
    val body = TeamDirectoryJson.format.encodeToJsonElement(request) as JsonObject
    val receipt = TeamDirectoryJson.format.decodeFromJsonElement<TeamScheduleReceipt>(
        transport.request("PUT", "teams/$teamID/schedules/$sourceID", body),
    )
    check(receipt.id.equals(sourceID, true) && receipt.teamID.equals(teamID, true) &&
        receipt.revision == request.expectedRevision + 1) { "发布结果需要重新核对。" }
    return TeamScheduleNotice(teamID, sourceID, request.startsAt, request.away, request.home, request.venue,
        request.cancelled, receipt.revision)
}

private fun TeamDirectoryState.scheduleFeed(
    notices: List<TeamScheduleNotice> = emptyList(),
    message: String? = null,
): TeamScheduleFeed {
    val members = scheduleMembers()
    val memberIDs = members.map { appleUUID(it.id) }.toSet()
    val adminIDs = members.filter { it.role == TeamRole.admin }.map { appleUUID(it.id) }.toSet()
    return TeamScheduleFeed(
        notices = notices.filter { appleUUID(it.teamID) in memberIDs },
        memberships = members,
        publications = schedulePublications.values.filter { it.local && it.teamID in memberIDs }.associateBy { it.sourceID },
        remotePublications = schedulePublications.filterValues { it.teamID in adminIDs },
        bindings = bindings.filter { appleUUID(it.cloudTeamID) in memberIDs },
        message = message,
    )
}

private fun TeamDirectoryState.scheduleMembers(): List<RemoteTeam> =
    if (deviceID != null && !credentialExpired && !requiresRecoveryReview) directory?.teams.orEmpty() else emptyList()

private fun TeamDirectoryState.localPublication(source: String): TeamSchedulePublication? =
    schedulePublications.values.singleOrNull { it.local && it.sourceID == source }

fun TeamDirectorySession.scheduleState(): TeamScheduleFeed = snapshot().scheduleFeed()

fun TeamDirectorySession.confirmScheduleLocalSync(notice: TeamScheduleNotice) = synchronized(storage.monitor) {
    val state = snapshot()
    val publication = state.schedulePublications[notice.key]
    if (publication?.notice == notice && publication.needsLocalSync) {
        savePublication(state, publication.copy(needsLocalSync = false))
    }
}

/** An ordinary home visit does not enroll a device or grant a team membership. */
fun TeamDirectorySession.teamSchedules(): TeamScheduleFeed = synchronized(storage.monitor) {
    val before = snapshot()
    if (before.scheduleFeed().memberships.isEmpty()) return@synchronized before.scheduleFeed()
    try {
        refresh()
        val state = snapshot()
        if (state.scheduleFeed().memberships.isEmpty()) return@synchronized state.scheduleFeed()
        state.scheduleFeed(client.readSchedules().sortedWith(compareBy({ it.startsAt }, { it.key })))
    } catch (_: Exception) {
        snapshot().scheduleFeed(message = "球队赛程暂时无法读取，请刷新重试。")
    }
}

private fun TeamDirectoryState.scheduleAuthorization(teamID: String, adminOnly: Boolean): ScheduleAuthorization {
    val device = deviceID
    val member = scheduleMembers().singleOrNull {
        it.id.equals(teamID, true) && if (adminOnly) it.role == TeamRole.admin else it.role != TeamRole.member
    }
    val accessEpoch = member?.accessEpoch
    val epoch = directory?.recoveryEpoch
    check(device != null && member != null && accessEpoch != null && epoch != null) { "当前球队或设备没有发布赛程的权限。" }
    return ScheduleAuthorization(appleUUID(device), appleUUID(member.memberID), accessEpoch, appleUUID(epoch), member.role)
}

fun TeamDirectorySession.publishSchedule(
    ownID: String,
    sourceID: String,
    startsAt: Long,
    away: String,
    home: String,
    venue: String,
    cancelled: Boolean = false,
    expectedRevision: Long? = null,
): TeamSchedulePublication = synchronized(storage.monitor) {
    val source = appleUUID(sourceID)
    val state = snapshot()
    val team = state.scheduleFeed().teamFor(ownID) ?: error("当前球队或设备没有发布赛程的权限。")
    val id = appleUUID(team.id)
    val old = state.localPublication(source)
    check(old == null || old.teamID == id) { "球队关联已改变，请核对原赛程。" }
    val publication = old ?: state.schedulePublications["$id/$source"] ?: TeamSchedulePublication(id, source)
    if (publication.pending == null && expectedRevision != null && publication.revision != expectedRevision) {
        throw TeamServiceFault(409, "schedule_changed")
    }
    stageSchedule(state, publication, startsAt, away, home, venue, cancelled, adminOnly = false)
}

/** An administrator can manage a notice received from another device without creating a local game. */
fun TeamDirectorySession.updateRemoteSchedule(
    notice: TeamScheduleNotice,
    startsAt: Long,
    venue: String,
    cancelled: Boolean = false,
): TeamSchedulePublication = synchronized(storage.monitor) {
    val state = snapshot()
    val team = appleUUID(notice.teamID)
    state.scheduleAuthorization(team, adminOnly = true)
    val publication = state.schedulePublications[notice.key] ?: TeamSchedulePublication(team, appleUUID(notice.id), notice, local = false)
    if (publication.notice != null && publication.revision > notice.revision) throw TeamServiceFault(409, "schedule_changed")
    val known = if (publication.pending == null && publication.revision < notice.revision) publication.copy(notice = notice) else publication
    stageSchedule(state, known, startsAt, notice.away, notice.home, venue, cancelled, adminOnly = true)
}

private fun TeamDirectorySession.stageSchedule(
    state: TeamDirectoryState,
    publication: TeamSchedulePublication,
    startsAt: Long,
    away: String,
    home: String,
    venue: String,
    cancelled: Boolean,
    adminOnly: Boolean,
): TeamSchedulePublication {
    val authorization = state.scheduleAuthorization(publication.teamID, adminOnly)
    if (publication.pending != null) return retrySchedule(state, publication, adminOnly)
    if (publication.needsReview) {
        refresh()
        val refreshed = snapshot()
        check(refreshed.scheduleAuthorization(publication.teamID, adminOnly) == authorization) { "设备或球队授权已变化，请重新核对。" }
        val current = client.readSchedules().singleOrNull { it.key == publication.key }
        check(current != null && current.revision >= publication.revision) { "暂时无法核对球队赛程的最新版本。" }
        savePublication(refreshed, publication.copy(notice = current, needsReview = false, needsLocalSync = publication.local))
        throw TeamServiceFault(409, "schedule_changed")
    }
    val previous = publication.notice
    require(if (cancelled) previous != null && !previous.cancelled else startsAt > System.currentTimeMillis()) {
        if (cancelled) "只有已发布的有效赛程可以取消。" else "请先将开赛时间改到未来。"
    }
    val request = TeamScheduleRequest(
        operationID = UUID.randomUUID().toString(), expectedRevision = publication.revision,
        startsAt = if (cancelled) requireNotNull(previous).startsAt else startsAt,
        away = if (cancelled) requireNotNull(previous).away else away,
        home = if (cancelled) requireNotNull(previous).home else home,
        venue = if (cancelled) requireNotNull(previous).venue else normalizeScheduleVenue(venue),
        cancelled = cancelled,
    )
    val staged = publication.copy(pending = request, authorization = authorization, local = publication.local || !adminOnly)
    savePublication(state, staged)
    return retrySchedule(state, staged, adminOnly)
}

fun TeamDirectorySession.retrySchedulePublication(sourceID: String, teamID: String? = null): TeamSchedulePublication = synchronized(storage.monitor) {
    val source = appleUUID(sourceID)
    val before = snapshot()
    val publication = if (teamID == null) before.localPublication(source) else
        before.schedulePublications["${appleUUID(teamID)}/$source"]
    retrySchedule(before, publication ?: error("没有待确认的球队赛程。"), adminOnly = teamID != null)
}

private fun TeamDirectorySession.retrySchedule(
    before: TeamDirectoryState,
    publication: TeamSchedulePublication,
    adminOnly: Boolean,
): TeamSchedulePublication {
    val team = publication.teamID
    val authorization = before.scheduleAuthorization(team, adminOnly)
    val request = publication.pending ?: return publication
    check(authorization == publication.authorization) { "设备或球队授权已变化，原发布请求已保留。" }
    refresh()
    val authorized = snapshot()
    check(authorized.scheduleAuthorization(team, adminOnly) == authorization) { "设备或球队授权已变化，原发布请求已保留。" }
    val notice = try {
        client.writeSchedule(team, publication.sourceID, request)
    } catch (fault: TeamServiceFault) {
        if (fault.status == 400 || fault.status == 409) {
            val current = client.readSchedules().singleOrNull { it.key == publication.key && it.revision >= publication.revision }
            val state = snapshot()
            val reviewed = publication.copy(notice = current ?: publication.notice, pending = null, authorization = null,
                needsReview = current == null && fault.code == "schedule_changed",
                needsLocalSync = publication.local && (current != null || publication.needsLocalSync))
            if (reviewed.notice == null && !reviewed.needsReview) {
                storage.save(state.copy(schedulePublications = state.schedulePublications - publication.key))
            } else {
                savePublication(state, reviewed)
            }
        }
        throw fault
    }
    val settled = publication.copy(notice = notice, pending = null, authorization = null, needsLocalSync = publication.local)
    savePublication(snapshot(), settled)
    return settled
}

private fun TeamDirectorySession.savePublication(state: TeamDirectoryState, publication: TeamSchedulePublication) {
    storage.save(state.copy(schedulePublications = state.schedulePublications + (publication.key to publication)))
}
