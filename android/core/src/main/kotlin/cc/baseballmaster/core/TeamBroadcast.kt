package cc.baseballmaster.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class TeamBroadcastStatus(
    val code: String? = null,
    val activeGameID: String? = null,
    val activeMatch: String? = null,
    val selected: Boolean? = null,
) {
    init {
        require(code == null || Regex("[A-Za-z0-9_-]{32}").matches(code))
        activeGameID?.let(::appleUUID)
        require(activeMatch == null || activeMatch.length <= 410)
    }
    override fun toString() = "TeamBroadcastStatus(selected=$selected)"
}

enum class BroadcastSource(val title: String, val size: String, private val view: String, private val side: String? = null) {
    score("比分＋投手", "640 × 116", "score"),
    batter("当前打者", "600 × 108", "batter"),
    away("客队阵容", "440 × 540", "lineup", "away"),
    home("主队阵容", "440 × 540", "lineup", "home");

    fun url(code: String): String {
        require(Regex("[A-Za-z0-9_-]{32}").matches(code))
        return "https://baseballmaster.cc/livestreaming/novideo/overlay?view=$view" +
            (side?.let { "&team=$it" } ?: "") + "#team/$code"
    }
}

data class BroadcastTarget(val teamID: String, val gameID: String, val sharedDocumentID: String? = null)
data class BroadcastSelection(val expectedGameID: String?)

private fun broadcastPath(team: String) = "teams/${appleUUID(team)}/broadcast"

private fun TeamDirectorySession.broadcastRole(teamID: String, admin: Boolean = false): RemoteTeam {
    val state = snapshot()
    check(!state.credentialExpired && !state.requiresRecoveryReview && state.deviceID != null) { "请先核对球队授权" }
    val team = state.directory?.teams?.singleOrNull { it.id.equals(teamID, true) }
    require(team != null && (if (admin) team.role == TeamRole.admin else team.role != TeamRole.member)) { "当前角色无权管理转播源" }
    return team
}

fun TeamDirectorySession.broadcastTarget(game: Game): BroadcastTarget? = synchronized(storage.monitor) {
    if (game.practice || (game.ourTeamID == null && game.home.isOpponent && game.away.isOpponent) || game.isScheduled || game.scheduledSetupPending || game.appleSourceID != null)
        return@synchronized null
    val state = snapshot()
    if (state.deviceID == null || state.credentialExpired || state.requiresRecoveryReview) return@synchronized null
    val own = game.ourTeamID ?: return@synchronized null
    val id = game.sharedRosterOrigin?.teamID
        ?: state.bindings.singleOrNull { it.localTeamID.equals(own, true) }?.cloudTeamID
        ?: return@synchronized null
    val team = state.directory?.teams?.singleOrNull { it.id.equals(id, true) && it.role != TeamRole.member }
        ?: return@synchronized null
    BroadcastTarget(appleUUID(team.id), appleUUID(game.id))
}

fun TeamDirectorySession.broadcastSources(teamID: String): TeamBroadcastStatus = synchronized(storage.monitor) {
    refresh()
    broadcastRole(teamID)
    TeamDirectoryJson.format.decodeFromJsonElement<TeamBroadcastStatus>(client.transport.request("GET", broadcastPath(teamID), null))
        .also { requireNotNull(it.code) { "当前球队服务未提供固定转播源" } }
}

fun TeamDirectorySession.rotateBroadcast(teamID: String, expectedCode: String): TeamBroadcastStatus = synchronized(storage.monitor) {
    refresh()
    broadcastRole(teamID, admin = true)
    require(Regex("[A-Za-z0-9_-]{32}").matches(expectedCode))
    TeamDirectoryJson.format.decodeFromJsonElement<TeamBroadcastStatus>(client.transport.request("POST", broadcastPath(teamID) + "/rotate",
        buildJsonObject { put("expectedCode", expectedCode) })).also { requireNotNull(it.code) }
}

fun TeamDirectorySession.publishLocalBroadcast(game: Game, selection: BroadcastSelection): TeamBroadcastStatus? =
    sendLocalBroadcast(game, selection)

fun TeamDirectorySession.resumeLocalBroadcast(game: Game): TeamBroadcastStatus? =
    sendLocalBroadcast(game, null)

private fun TeamDirectorySession.sendLocalBroadcast(game: Game, selection: BroadcastSelection?): TeamBroadcastStatus? = synchronized(storage.monitor) {
    val target = broadcastTarget(game) ?: return@synchronized null
    if (selection != null) { refresh(); broadcastRole(target.teamID) }
    if (game.isPregame || game.isFinal && selection != null) return@synchronized null
    val snapshot = LiveProjection.create(game)
    val pitching = if (game.rules.mode == GameMode.coachPitch) null else (game.pitching[game.pitcher.id] ?: PitchingLine()).let {
        buildJsonObject { put("strikes", it.strikes); put("strikeouts", it.strikeouts) }
    }
    publishBroadcast(target, snapshot, pitching, null, selection ?: BroadcastSelection(target.gameID))
}

fun TeamDirectorySession.publishSharedBroadcast(teamID: String, documentID: String, selection: BroadcastSelection? = null): TeamBroadcastStatus? =
    synchronized(storage.monitor) {
        val doc = match(teamID, documentID)
        val state = snapshot()
        broadcastRole(teamID)
        if (doc.deleted || !doc.canWrite || !doc.writerDeviceID.equals(state.deviceID, true) || doc.handoff != null)
            return@synchronized null
        val source = doc.payload ?: return@synchronized null
        if (source["isSpectator"]?.jsonPrimitive?.booleanOrNull == true) return@synchronized null
        val projection = doc.raw["projection"] as? JsonObject ?: return@synchronized null
        val snapshot = projection["snapshot"] as? JsonObject ?: return@synchronized null
        if (selection != null && snapshot["isFinal"]?.jsonPrimitive?.booleanOrNull == true) return@synchronized null
        val pitcherID = (snapshot["pitcher"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        val line = ((projection["box"] as? JsonObject)?.get("pitching") as? JsonArray)?.firstOrNull {
            ((it as? JsonObject)?.get("player") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.equals(pitcherID, true)
        } as? JsonObject
        val values = line?.get("values") as? JsonObject
        val pitching = if (pitcherID == null || snapshot["mode"]?.jsonPrimitive?.content == "coachPitch") null
            else buildJsonObject {
                put("strikes", values?.get("strikes")?.jsonPrimitive?.intOrNull ?: 0)
                put("strikeouts", values?.get("strikeouts")?.jsonPrimitive?.intOrNull ?: 0)
            }
        val target = BroadcastTarget(teamID, appleUUID(source.getValue("id").jsonPrimitive.content), doc.id)
        publishBroadcast(target, snapshot, pitching, doc.writerEpoch, selection ?: BroadcastSelection(target.gameID))
    }

private fun TeamDirectorySession.publishBroadcast(
    target: BroadcastTarget, snapshot: JsonObject, pitching: JsonObject?, epoch: Long?, selection: BroadcastSelection,
): TeamBroadcastStatus? {
    if (snapshot["hasStarted"]?.jsonPrimitive?.boolean != true) return null
    val currentBatter = (snapshot["batter"] as? JsonObject)?.get("id")
    val entries = snapshot.getValue("entries").jsonArray.map { it.jsonObject }.filter {
        it["status"]?.jsonPrimitive?.content == "completed" &&
            (it["player"] as? JsonObject)?.get("id") == currentBatter
    }.takeLast(3).map { JsonObject(it + ("details" to JsonArray(emptyList()))) }
    val compact = JsonObject(snapshot + mapOf("gameID" to JsonPrimitive(target.gameID), "entries" to JsonArray(entries),
        "notice" to JsonPrimitive(""), "nextBatters" to JsonArray(emptyList())))
    val response = client.transport.request("PUT", broadcastPath(target.teamID), buildJsonObject {
        put("snapshot", compact)
        put("expectedGameID", selection.expectedGameID?.let(::appleUUID)?.let(::JsonPrimitive) ?: JsonNull)
        if (epoch != null) put("writerEpoch", epoch)
        if (pitching != null) put("pitching", pitching)
    })
    return TeamDirectoryJson.format.decodeFromJsonElement<TeamBroadcastStatus>(response)
}
