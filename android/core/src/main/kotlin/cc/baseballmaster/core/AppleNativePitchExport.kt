package cc.baseballmaster.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/** Native-to-Swift graph writer for the verified standard-pitch subset.
 *
 * This is deliberately separate from the old one-event-per-operation prototype.
 * The opening command owns PLAY plus its pitch, and terminal pitches own pitch
 * plus result. Unknown commands, corrections and regulation decisions fail closed. It
 * is not the full-match product export and must not be presented as one.
 */
internal object AppleNativePitchExport {
    /** Complete graph for a standard history within the explicitly supported command set.
     * This stays internal until all native commands, audits and rule decisions are covered.
     */
    fun encodeStandardHistory(game: Game): ByteArray {
        require(game.appleSourceID==null && game.historySourceID==null && !game.practice && !game.isScheduled && !game.incompletePrefix)
        require(game.historyRevisions.isEmpty() && game.correctionRevisions.isEmpty() && game.dateRevisions.isEmpty()) { "完整审计导出尚未覆盖此比赛" }
        require(game.cursor==game.journal.size && game.entries.size==game.cursor && game.cursor>0 && game.revision==game.cursor.toLong()) { "撤销分支或历史不完整" }
        require(game.journal.all {!it.inserted && !it.edited && !it.acceptContext && it.additionalErrors.isEmpty() && it.statisticsRuling==null}) { "此比赛含尚未支持的历史审计" }
        val tailIndex=game.journal.indexOfFirst {it.command.kind!="pitch"}.let {if(it<0) game.cursor else it}
        val checked=History.run(game,game.journal,tailIndex,strict=true)
        require(checked.canSave) {checked.conflict ?: "原生历史不能严格重放"}
        var bytes=if(tailIndex==0) {
            require(game.journal.first().command.kind=="play") { "首次开赛操作尚未支持完整导出" }
            val first=MatchEngine.apply(requireNotNull(checked.game).copy(journal=emptyList(),cursor=0,revision=0),game.journal.first().command,game.journal.first().id)
            openingPlaySeed(first)
        } else {
            val prefix=requireNotNull(checked.game).copy(journal=game.journal.take(tailIndex),cursor=tailIndex,
                revision=game.revision-(game.cursor-tailIndex))
            encode(prefix)
        }
        for(record in game.journal.drop(tailIndex)) bytes=AppleSourceBackup.prepare(bytes).appendCommand(game.id,record.command,record.id).export()
        val result=AppleSourceBackup.prepare(bytes).games().single()
        val keys=setOf("inning","isTop","balls","strikes","outs","bases","homeBatterIndex","awayBatterIndex","homeRunsByInning","awayRunsByInning",
            "homeHits","awayHits","homeErrors","awayErrors","batting","pitching","fielding","runnerPitcherIDs","unearnedRunnerIDs","appearancePitches",
            "isFinal","endedAt","endReason","clockElapsedMs","clockRunningSince")
        require(GameJson.encodeToJsonElement(result).jsonObject.filterKeys {it in keys}==GameJson.encodeToJsonElement(game).jsonObject.filterKeys {it in keys} &&
            result.journal.map {it.id}==game.journal.map {it.id}) { "原生终场图与完整比赛不一致，未导出部分比赛" }
        return bytes
    }

    private fun openingPlaySeed(first:Game):ByteArray {
        // Reuse only Codable scalars/rosters from the old encoder. No prototype
        // event or log is admitted into the seed or final native graph.
        val template=AppleBackupEnvelope.inspect(AppleExportPrototype.encode(first))
        val stored=template.snapshot.getValue("games").jsonArray.single().jsonObject
        val journal=stored.getValue("state").jsonObject.getValue("historyJournal").jsonObject
        val checkpoint=AppleWireJson.parse(Base64.getDecoder().decode(journal.getValue("checkpoint").jsonPrimitive.content)).jsonObject
        require(checkpoint.getValue("scoringEvents").jsonArray.isEmpty() && checkpoint.getValue("playLog").jsonArray.isEmpty())
        val state=JsonObject(checkpoint+("recordsLineupSnapshots" to JsonPrimitive(true)))
        val history=JsonObject(journal+mapOf("checkpoint" to JsonPrimitive(Base64.getEncoder().encodeToString(canonicalCheckpoint(state).toString().toByteArray())),"operations" to JsonArray(emptyList())))
        val seed=JsonObject((stored-"startedAt")+mapOf("state" to JsonObject(state+("historyJournal" to history)),"revision" to JsonPrimitive(0),
            "updatedAt" to stored.getValue("createdAt"),"openingPitchRecorded" to JsonPrimitive(false)))
        val payload=JsonObject(template.snapshot+("games" to JsonArray(listOf(seed)))).toString().toByteArray()
        val result=buildJsonObject {
            put("format","BaseballMaster Backup");put("version",7);put("createdAt",stored.getValue("createdAt"))
            put("payload",Base64.getEncoder().encodeToString(payload));put("checksum",AppleLifecycleAppend.hash(payload))
        }.toString().toByteArray()
        AppleSourceBackup.prepare(result)
        return result
    }

    fun encode(game: Game): ByteArray {
        require(game.appleSourceID == null && game.historySourceID == null) { "来源比赛必须保留原始血缘" }
        require(!game.practice && !game.isScheduled && !game.isFinal && !game.incompletePrefix) { "此比赛状态尚未支持原生投球导出" }
        require(game.historyRevisions.isEmpty() && game.correctionRevisions.isEmpty() && game.dateRevisions.isEmpty()) { "更正或日期审计必须使用完整历史导出" }
        require(game.cursor == game.journal.size && game.cursor > 0 && game.entries.size == game.cursor) { "存在撤销分支或缺失历史" }
        require(game.journal.all { it.command.kind == "pitch" && !it.inserted && !it.edited && !it.acceptContext && it.additionalErrors.isEmpty() && it.statisticsRuling == null }) { "当前投球导出不支持其他操作或审计" }
        val checkpoint = GameJson.decodeFromString<Game>(requireNotNull(game.checkpoint))
        require(checkpoint.entries.isEmpty() && checkpoint.journal.isEmpty() && checkpoint.inning == 1 && checkpoint.isTop &&
            checkpoint.balls == 0 && checkpoint.strikes == 0 && checkpoint.outs == 0 && checkpoint.bases.all { it == null } &&
            checkpoint.batting.isEmpty() && checkpoint.pitching.isEmpty() && !checkpoint.clockHasStarted && !checkpoint.openingPitchRecorded) { "原生导出检查点不是完整赛前状态" }
        require(checkpoint.rules.mode == GameMode.standard && !checkpoint.rules.timed && !checkpoint.rules.dhEnabled && !checkpoint.rules.twoWayEnabled) { "特殊规则需要完整比赛导出" }
        val checked = History.run(game, game.journal, game.cursor, strict = true)
        require(checked.canSave) { checked.conflict ?: "原生历史不能严格重放" }
        val first = game.journal.first()
        val initial = MatchEngine.apply(checkpoint, first.command, first.id)
        val seed = openingBackup(initial)
        var continuation = AppleGameContinuation.create(seed, game.id)
        for (operation in game.journal.drop(1)) {
            continuation = continuation.appendPitch(requireNotNull(operation.command.pitch), operation.id, operation.command.at)
        }
        // Compare the complete score/statistics projection independently of the
        // writer's own changed-field allowlist. Never silently export a prefix.
        val projected = continuation.preview()
        require(projected.inning == game.inning && projected.isTop == game.isTop && projected.balls == game.balls &&
            projected.strikes == game.strikes && projected.outs == game.outs && projected.bases == game.bases &&
            projected.homeRunsByInning == game.homeRunsByInning && projected.awayRunsByInning == game.awayRunsByInning &&
            projected.homeBatterIndex == game.homeBatterIndex && projected.awayBatterIndex == game.awayBatterIndex &&
            projected.batting == game.batting && projected.pitching == game.pitching && projected.fielding == game.fielding &&
            projected.runnerPitcherIDs == game.runnerPitcherIDs && projected.unearnedRunnerIDs == game.unearnedRunnerIDs &&
            projected.appearancePitches == game.appearancePitches && projected.journal.map { it.id } == game.journal.map { it.id }) { "原生导出与正式比赛不一致，未生成不完整备份" }
        return continuation.exportBackup()
    }

    /** GameState.historyData uses sorted JSON keys and shortest numeric tokens.
     * Reproduce that byte contract for a new native checkpoint; source Data is
     * never normalized or rewritten by this helper. */
    private fun canonicalCheckpoint(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalCheckpoint(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalCheckpoint))
        is JsonPrimitive -> if (!value.isString && value.booleanOrNull == null && value != JsonNull)
            GameJson.parseToJsonElement(BigDecimal(value.content).stripTrailingZeros().toPlainString()) else value
    }

    private fun openingBackup(first: Game): ByteArray {
        // Reuse only the existing scalar/roster Codable serialization. Its
        // single-event graph is replaced in full before validation or use.
        val template = AppleBackupEnvelope.inspect(AppleExportPrototype.encode(first))
        val stored = template.snapshot.getValue("games").jsonArray.single().jsonObject
        val state = stored.getValue("state").jsonObject
        val history = state.getValue("historyJournal").jsonObject
        val operation = history.getValue("operations").jsonArray.single().jsonObject
        val id = operation.getValue("id").jsonPrimitive.content
        fun stable(component: String): JsonPrimitive {
            val hex = MessageDigest.getInstance("SHA-256").digest((id.uppercase() + component).toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            return JsonPrimitive("${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}".uppercase())
        }
        val originalPitch = state.getValue("scoringEvents").jsonArray.single().jsonObject
        val action = requireNotNull(first.journal.single().command.pitch)
        val actionTitle = when (action) { PitchAction.calledStrike -> "好球 - 看"; PitchAction.swingingStrike -> "好球 - 挥空"; else -> action.title }
        val pitchTitle = "${first.batter.name}：$actionTitle（${first.balls}坏 ${first.strikes}好）"
        val pitch = JsonObject(originalPitch + mapOf("id" to stable("event1"), "logEntryID" to stable("log1"), "title" to JsonPrimitive(pitchTitle)))
        val clock = buildJsonObject {
            put("id", stable("event0")); put("logEntryID", stable("log0"))
            for (key in listOf("inning", "isTop", "timestamp")) put(key, pitch.getValue(key))
            put("category", "clock"); put("title", "Play Ball，比赛计时开始"); put("notation", "PLAY")
            put("runnerMovements", JsonArray(emptyList())); put("needsReview", false)
            put("beforeSituation", pitch.getValue("beforeSituation")); put("afterSituation", pitch.getValue("beforeSituation"))
        }
        val logTemplate = state.getValue("playLog").jsonArray.single().jsonObject
        val clockLog = JsonObject(logTemplate + mapOf("id" to stable("log0"), "text" to JsonPrimitive("Play Ball，比赛计时开始")))
        val pitchLog = JsonObject(logTemplate + mapOf("id" to stable("log1"), "text" to JsonPrimitive(pitchTitle)))
        val updatedOperation = JsonObject(operation + mapOf("title" to JsonPrimitive(pitchTitle), "eventIDs" to JsonArray(listOf(stable("event0"), stable("event1"))), "logIDs" to JsonArray(listOf(stable("log0"), stable("log1")))))
        // Existing snapshots include lineups; declare that choice explicitly so
        // original Swift replay reproduces exactly the same situation schema.
        val checkpoint = AppleWireJson.parse(Base64.getDecoder().decode(history.getValue("checkpoint").jsonPrimitive.content)).jsonObject
        val updatedCheckpoint = JsonObject(checkpoint + ("recordsLineupSnapshots" to JsonPrimitive(true)))
        val updatedHistory = JsonObject(history + mapOf("checkpoint" to JsonPrimitive(Base64.getEncoder().encodeToString(canonicalCheckpoint(updatedCheckpoint).toString().toByteArray())), "operations" to JsonArray(listOf(updatedOperation))))
        val updatedState = JsonObject(state + mapOf("scoringEvents" to JsonArray(listOf(clock, pitch)), "playLog" to JsonArray(listOf(clockLog, pitchLog)), "historyJournal" to updatedHistory, "recordsLineupSnapshots" to JsonPrimitive(true)))
        val updatedStored = JsonObject(stored + ("state" to updatedState))
        AppleStoredGameValidation.validate(updatedStored)
        val payload = JsonObject(template.snapshot + ("games" to JsonArray(listOf(updatedStored)))).toString().toByteArray()
        val bytes = buildJsonObject {
            put("format", "BaseballMaster Backup"); put("version", 7)
            put("createdAt", JsonPrimitive(BigDecimal.valueOf(first.createdAt).movePointLeft(3).subtract(BigDecimal("978307200"))))
            put("payload", Base64.getEncoder().encodeToString(payload))
            put("checksum", MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })
        }.toString().toByteArray()
        AppleBackupEnvelope.inspect(bytes)
        return bytes
    }
}
