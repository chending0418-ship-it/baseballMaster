package cc.baseballmaster.core

import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Source-backed local import. Read-only history is replay-checked; edits use the strict lossless subset.
 * The complete original envelope remains authoritative; this never calls the native wire exporter.
 */
class AppleSourceBackup private constructor(
    private val bytes: ByteArray,
    private val inspection: AppleBackupInspection,
    private val projected: List<Game>,
    private val rosters: List<Team>,
) {
    val digest: String = hash(bytes)
    val currentTeamID: String? get() = inspection.snapshot["currentTeamID"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(::appleUUID)
    val name: String get() = rosters.firstOrNull { it.id == currentTeamID }?.name ?: "iOS 比赛备份"
    fun export(): ByteArray = bytes.copyOf()
    fun games(sourceID: String = digest): List<Game> {
        require(Regex("[a-f0-9]{64}").matches(sourceID))
        return projected.map { GameJson.decodeFromString<Game>(GameJson.encodeToString(it)).copy(appleSourceID = sourceID) }
    }
    fun teams(): List<Team> = rosters.map { GameJson.decodeFromString(GameJson.encodeToString(it)) }

    fun appendPitch(gameID: String, action: PitchAction, operationID: String, atMillis: Long): AppleSourceBackup {
        require(projected.any { it.id == appleUUID(gameID) }) { "来源中没有这场比赛" }
        val next = AppleGameContinuation.create(bytes, gameID).appendPitch(action, operationID, atMillis).exportBackup()
        return prepare(next)
    }

    companion object {
        fun prepare(input: ByteArray): AppleSourceBackup {
            val bytes = input.copyOf()
            val inspection = AppleBackupEnvelope.inspect(bytes)
            val snapshot = inspection.snapshot
            require(snapshot.keys.all { it in setOf("teams", "opponentTeams", "currentTeamID", "seasons", "playerGameRecords", "games", "archivedTeams") }) { "备份含尚不支持的数据类别，未导入任何资料" }
            require(snapshot.getValue("playerGameRecords").jsonArray.isEmpty()) { "此备份包含旧式个人比赛记录，暂不能完整导入，原文件未修改" }
            val rosters = AppleRosterBackup.decode(inspection)
            val teams = rosters.map { it.id }.toSet()
            val ownTeams = rosters.filter { !it.isOpponent }.map { it.id }.toSet()
            val games = snapshot.getValue("games").jsonArray.map { raw ->
                val stored = raw.jsonObject
                val id = stored.getValue("id").jsonPrimitive.content
                require(stored.getValue("status").jsonPrimitive.int != 2) { "尚未开始的预告比赛暂不能完整导入，整批未写入" }
                require(stored["historySourceID"]?.takeUnless { it == JsonNull } == null) { "带历史来源关联的比赛暂不能安全去重，整批未写入" }
                val game = AppleReplayPrototype.replay(stored)
                require(game.ourTeamID in ownTeams && game.ourTeamID in listOf(game.home.id, game.away.id) && game.home.id in teams && game.away.id in teams) { "比赛的本队或对手不在备份名单中，未导入任何资料" }
                require(stored.getValue("isHome").jsonPrimitive.boolean == (game.ourTeamID == game.home.id)) { "本队与主客场标记不一致，整批未写入" }
                stored["opponentTeamID"]?.takeUnless { it == JsonNull }?.let { opponent -> require(appleUUID(opponent.jsonPrimitive.content) == (if (game.ourTeamID == game.home.id) game.away.id else game.home.id)) { "对手身份不一致，整批未写入" } }
                fun date(key: String) = stored[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let {
                    BigDecimal(it).add(BigDecimal("978307200")).movePointRight(3).longValueExactOrFloor()
                }
                // Dates and clock values are source facts, not inferred from the last scoring command.
                game.copy(createdAt = requireNotNull(date("createdAt")), updatedAt = requireNotNull(date("updatedAt")),
                    scheduledAt = date("scheduledAt"), playedAt = date("playedAt"), startedAt = date("startedAt"),
                    statisticsIncomplete = game.statisticsIncomplete || stored.getValue("state").jsonObject["pitchingOutsReviewRequired"]?.jsonPrimitive?.booleanOrNull == true,
                    // Compatibility with current iOS StoredGame.yearID; do not change iOS policy.
                    playedTimeZoneID = stored["playedTimeZoneID"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "UTC",
                    endedAt = stored.getValue("state").jsonObject["endedAt"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { BigDecimal(it).add(BigDecimal("978307200")).movePointRight(3).longValueExactOrFloor() },
                    clockElapsedMs = stored.getValue("state").jsonObject["clockElapsedSeconds"]?.jsonPrimitive?.content?.let { BigDecimal(it).movePointRight(3).longValueExactOrFloor() } ?: 0,
                    clockRunningSince = stored.getValue("state").jsonObject["clockRunningSince"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { BigDecimal(it).add(BigDecimal("978307200")).movePointRight(3).longValueExactOrFloor() })
            }
            val originalOwners = mutableMapOf<String, String>()
            games.forEach { game -> (game.journal + game.historyRevisions.flatMap { it.previousRecords }).map { it.id.lowercase(java.util.Locale.ROOT) }.distinct().forEach { id ->
                require(originalOwners.put(id, game.id) == null) { "不同比赛的原始操作编号重复，整批未导入" }
            } }
            require(games.isNotEmpty()) { "备份中没有比赛，请使用仅导入名单" }
            return AppleSourceBackup(bytes, inspection, games, rosters)
        }
        /** A restored source cannot rewrite old history under the same game identities. */
        fun validateLineage(original: ByteArray, current: ByteArray): AppleSourceBackup {
            if (original.contentEquals(current)) return prepare(original)
            val old = prepare(original); val next = prepare(current)
            val a = old.inspection.snapshot; val b = next.inspection.snapshot
            require(JsonObject(a - "games") == JsonObject(b - "games")) { "来源备份的球队或其他资料被改写" }
            val oldGames = a.getValue("games").jsonArray.map { it.jsonObject }
            val newGames = b.getValue("games").jsonArray.map { it.jsonObject }
            require(oldGames.map { it.getValue("id") } == newGames.map { it.getValue("id") }) { "来源比赛身份或顺序被改写" }
            var additions = 0
            var expected = old
            oldGames.zip(newGames).forEach { (before, after) ->
                require(JsonObject(before - "state" - "revision" - "updatedAt") == JsonObject(after - "state" - "revision" - "updatedAt")) { "来源比赛资料被改写" }
                val oldState = before.getValue("state").jsonObject; val newState = after.getValue("state").jsonObject
                val mutable = setOf("balls", "strikes", "plateAppearancePitchCount", "pitching", "playLog", "scoringEvents", "historyJournal", "batting", "homeBatterIndex", "awayBatterIndex", "outs", "baseRunners", "runnerPitcherIDs", "unearnedRunnerIDs", "plateAppearances", "currentPlateAppearanceID")
                require(oldState.filterKeys { it !in mutable } == newState.filterKeys { it !in mutable }) { "来源局面或纠错审计被改写" }
                val oldJournal = oldState.getValue("historyJournal").jsonObject; val newJournal = newState.getValue("historyJournal").jsonObject
                require(JsonObject(oldJournal - "operations") == JsonObject(newJournal - "operations")) { "来源检查点被改写" }
                val oldOps = oldJournal.getValue("operations").jsonArray; val newOps = newJournal.getValue("operations").jsonArray
                require(newOps.take(oldOps.size) == oldOps.toList() && newOps.size >= oldOps.size) { "来源操作被改写或删除" }
                val added = newOps.size - oldOps.size; additions += added
                require(additions <= 10_000) { "来源续记过多，暂不能安全恢复" }
                newOps.drop(oldOps.size).forEach { raw ->
                    val operation = raw.jsonObject
                    val command = operation.getValue("command").jsonObject
                    require(command.keys == setOf("pitch")) { "来源包含尚不支持的新增操作" }
                    val action = PitchAction.entries.single { it.title == command.getValue("pitch").jsonObject.getValue("_0").jsonPrimitive.content }
                    val at = BigDecimal(operation.getValue("timestamp").jsonPrimitive.content).add(BigDecimal("978307200")).movePointRight(3).longValueExact()
                    expected = expected.appendPitch(before.getValue("id").jsonPrimitive.content, action, operation.getValue("id").jsonPrimitive.content, at)
                }
                val id = appleUUID(before.getValue("id").jsonPrimitive.content)
                require(next.projected.single { it.id == id }.revision == old.projected.single { it.id == id }.revision + added) { "来源修订与续记数量不一致" }
                for (key in listOf("playLog", "scoringEvents")) require(newState.getValue(key).jsonArray.take(oldState.getValue(key).jsonArray.size) == oldState.getValue(key).jsonArray.toList()) { "来源逐球历史被改写" }
            }
            require(additions > 0) { "未续记的来源文件被重新编码或改写" }
            require(expected.inspection.snapshot == next.inspection.snapshot) { "来源续记与独立转换结果不一致" }
            require(JsonObject(AppleWireJson.parse(original).jsonObject - "payload" - "checksum") == JsonObject(AppleWireJson.parse(current).jsonObject - "payload" - "checksum")) { "来源文件元数据被改写" }
            return next
        }

        private fun BigDecimal.longValueExactOrFloor(): Long = setScale(0, java.math.RoundingMode.FLOOR).longValueExact()
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
