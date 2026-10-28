package cc.baseballmaster.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * Lossless append-only writer for the app's explicitly limited iOS continuation subset.
 *
 * Only local, ongoing standard games with supported pitch/single/no-out error journals are editable.
 * Supported correction journals must share the original checkpoint; audit/review flags remain intact.
 * Existing events/logs/appearances/checkpoint stay opaque and immutable AFTER replay verification;
 * newly appended nonterminal pitches are genuinely replayed and encoded, not a copy-only import.
 * Substitutions, terminal pitches, legacy histories and unknown selected-game fields
 * are rejected. Unedited snapshot records outside the selected game are preserved.
 */
internal class AppleGameContinuation private constructor(
    private val original: ByteArray,
    private val inspection: AppleBackupInspection,
    private val gameID: String,
    private val stored: JsonObject,
    private val game: Game,
    private val appended: List<Append>,
) {
    private data class Append(val id: String, val action: PitchAction, val at: Long)

    /** Detached preview: a caller cannot mutate the continuation through this Game. */
    fun preview(): Game = GameJson.decodeFromString(GameJson.encodeToString(game))

    fun appendNonterminalPitch(action: PitchAction, operationID: String, atMillis: Long): AppleGameContinuation {
        require(appended.size < 10_000) { "导入比赛续记操作过多" }
        val id = appleUUID(operationID)
        val state = stored.objectValue("state")
        val journal = state.objectValue("historyJournal")
        val allIDs = sourceIDs(inspection.snapshot) + (journal.arrayValue("operations").map { it.jsonObject.id("id") } +
            state.arrayValue("scoringEvents").map { it.jsonObject.id("id") } +
            state.arrayValue("playLog").map { it.jsonObject.id("id") } +
            state.arrayValue("plateAppearances").map { it.jsonObject.id("id") }).toSet()
        val eventID = stableID(id, "event0"); val logID = stableID(id, "log0")
        require(listOf(id, eventID, logID).distinct().size == 3 && listOf(id, eventID, logID).none { it in allIDs }) { "续记编号重复，原资料未修改" }
        val auditDates = state.optional("correctionRevisions")?.jsonArray.orEmpty().flatMap { raw ->
            val revision = raw.jsonObject
            listOf(revision.getValue("date")) + revision.objectValue("previousJournal").arrayValue("operations").flatMap { op -> listOfNotNull(op.jsonObject.optional("timestamp"), op.jsonObject.optional("correctedAt")) }
        }
        val sourceDates = auditDates + listOf(stored.getValue("updatedAt"), state.getValue("clockRunningSince")) +
            (journal.arrayValue("operations") + state.arrayValue("scoringEvents") + state.arrayValue("playLog")).flatMap { row -> listOfNotNull(row.jsonObject.optional("timestamp"), row.jsonObject.optional("correctedAt"), row.jsonObject.optional("reviewedAt")) }
        // Decimal tokens avoid accepting a backward sub-millisecond time due to Double rounding.
        val lastMillis = sourceDates.maxOf { BigDecimal(it.jsonPrimitive.content) }.add(BigDecimal("978307200")).movePointRight(3)
        require(atMillis > 0 && BigDecimal.valueOf(atMillis) >= lastMillis) { "续记时间早于原比赛" }
        require(when (action) {
            PitchAction.ball -> game.balls < 3
            PitchAction.calledStrike, PitchAction.swingingStrike -> game.strikes < 2
            PitchAction.foul -> true
        }) { "此导入比赛续记尚不支持保送或三振；原资料未修改" }
        val next = MatchEngine.apply(game, Command("pitch", pitch = action, at = atMillis), id)
        // Any unplanned reducer effect means this narrow wire projector is not suitable.
        val allowedChanges = setOf("balls", "strikes", "appearancePitches", "totalPitches", "revision", "entries", "journal", "cursor", "updatedAt", "pitching")
        fun unchanged(value: Game) = GameJson.encodeToJsonElement(value).jsonObject.filterKeys { it !in allowedChanges }
        require(unchanged(game) == unchanged(next)) { "续记影响了未支持的比赛状态" }
        val expectedPitching = game.pitching.mapValues { it.value.copy() }.toMutableMap()
        expectedPitching.getOrPut(game.pitcher.id) { PitchingLine() }.apply { pitches++; if (action != PitchAction.ball) strikes++ }
        require(next.pitching == expectedPitching && next.appearancePitches == game.appearancePitches + 1) { "续记投球统计不一致" }
        verifyCurrentAppearance(state, game)
        val oldPitchCount = state.optional("plateAppearancePitchCount")?.jsonPrimitive?.int ?: 0
        require(oldPitchCount == game.appearancePitches) { "打席投球数不一致" }
        val titleAction = when (action) { PitchAction.calledStrike -> "好球 - 看"; PitchAction.swingingStrike -> "好球 - 挥空"; else -> action.title }
        val title = "${game.batter.name}：$titleAction（${next.balls}坏 ${next.strikes}好）"
        val timestamp = JsonPrimitive(BigDecimal.valueOf(atMillis).movePointLeft(3).subtract(BigDecimal("978307200")))
        val notation = when (action) { PitchAction.ball -> "B"; PitchAction.calledStrike -> "C"; PitchAction.swingingStrike -> "S"; PitchAction.foul -> "F" }
        val log = buildJsonObject {
            put("id", logID.uppercase()); put("inning", game.inning); put("isTop", game.isTop)
            put("timestamp", timestamp); put("text", title); put("isIncomplete", false)
        }
        val event = buildJsonObject {
            put("id", eventID.uppercase()); put("logEntryID", logID.uppercase()); put("inning", game.inning); put("isTop", game.isTop)
            put("timestamp", timestamp); put("category", "pitch"); put("title", title); put("notation", notation)
            put("primaryPlayerID", game.pitcher.id.uppercase()); put("secondaryPlayerID", game.batter.id.uppercase())
            put("plateAppearanceBatterID", game.batter.id.uppercase()); put("plateAppearanceID", state.getValue("currentPlateAppearanceID"))
            put("runnerMovements", JsonArray(emptyList())); put("needsReview", false)
            put("beforeSituation", situation(state)); put("afterSituation", situation(state.with("balls", JsonPrimitive(next.balls)).with("strikes", JsonPrimitive(next.strikes))))
        }
        val operation = buildJsonObject {
            put("id", id.uppercase()); put("command", buildJsonObject { put("pitch", buildJsonObject { put("_0", action.title) }) })
            put("context", context(state)); put("timestamp", timestamp); put("title", title)
            put("eventIDs", JsonArray(listOf(JsonPrimitive(eventID.uppercase())))); put("logIDs", JsonArray(listOf(JsonPrimitive(logID.uppercase()))))
            put("nextAppearanceID", state.getValue("currentPlateAppearanceID")); put("inserted", false); put("edited", false)
            put("acceptsNewContext", false); put("errors", JsonArray(emptyList()))
        }
        val pitching = state.arrayValue("pitching").toMutableList()
        val pitcherIndex = pitching.indices.step(2).firstOrNull { uuid(pitching[it]) == game.pitcher.id }
        if (pitcherIndex == null) {
            pitching += JsonPrimitive(game.pitcher.id.uppercase()); pitching += GameJson.encodeToJsonElement(next.pitching.getValue(game.pitcher.id))
        } else {
            val old = pitching[pitcherIndex + 1].jsonObject
            pitching[pitcherIndex + 1] = old.with("pitches", JsonPrimitive(next.pitching.getValue(game.pitcher.id).pitches))
                .with("strikes", JsonPrimitive(next.pitching.getValue(game.pitcher.id).strikes))
        }
        val updatedState = state.with("balls", JsonPrimitive(next.balls)).with("strikes", JsonPrimitive(next.strikes))
            .with("plateAppearancePitchCount", JsonPrimitive(oldPitchCount + 1)).with("pitching", JsonArray(pitching))
            .with("playLog", JsonArray(state.arrayValue("playLog") + log)).with("scoringEvents", JsonArray(state.arrayValue("scoringEvents") + event))
            .with("historyJournal", journal.with("operations", JsonArray(journal.arrayValue("operations") + operation)))
        val updated = stored.with("state", updatedState).with("revision", JsonPrimitive(next.revision)).with("updatedAt", timestamp)
        AppleStoredGameValidation.validate(updated)
        val verified = AppleReplayPrototype.replay(updated)
        require(verified.journal.last().command.at == atMillis && coreState(verified) == coreState(next)) { "导出续记重放不一致" }
        return AppleGameContinuation(original, inspection, gameID, updated, next, appended + Append(id, action, atMillis))
    }

    fun exportBackup(): ByteArray {
        if (appended.isEmpty()) return original.copyOf()
        val games = inspection.snapshot.arrayValue("games").map { if (it.jsonObject.id("id") == gameID) stored else it }
        val payload = inspection.snapshot.with("games", JsonArray(games)).toString().toByteArray(Charsets.UTF_8)
        val outer = AppleWireJson.parse(original).jsonObject
        val bytes = outer.with("payload", JsonPrimitive(Base64.getEncoder().encodeToString(payload))).with("checksum", JsonPrimitive(hash(payload)))
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= SOURCE_LIMIT) { "续记备份超过 100 MB" }
        AppleBackupEnvelope.inspect(bytes)
        return bytes
    }

    /** A checksummed, restartable research session. Hashes are not proof of authenticity; no authority is installed. */
    fun serialize(): ByteArray {
        val payload = buildJsonObject {
            put("source", Base64.getEncoder().encodeToString(original)); put("sourceChecksum", hash(original)); put("gameID", gameID)
            put("appended", buildJsonArray { appended.forEach { a -> add(buildJsonObject { put("id", a.id); put("action", a.action.name); put("atMillis", a.at) }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        return buildJsonObject {
            put("format", SESSION_FORMAT); put("version", 1); put("payload", Base64.getEncoder().encodeToString(payload)); put("checksum", hash(payload))
        }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= SESSION_LIMIT) { "研究会话过大" } }
    }

    companion object {
        private const val SOURCE_LIMIT = 100_000_000
        private const val SESSION_LIMIT = 190_000_000
        private const val SESSION_FORMAT = "BaseballMaster iOS Continuation Research"

        fun create(originalBackup: ByteArray, gameID: String): AppleGameContinuation {
            val original = originalBackup.copyOf()
            val inspection = AppleBackupEnvelope.inspect(original)
            require(inspection.version == 7) { "导入比赛续记暂只支持格式 7；旧版不会自动改写" }
            val id = appleUUID(gameID)
            val stored = inspection.snapshot.arrayValue("games").singleOrNull { it.jsonObject.id("id") == id }?.jsonObject ?: error("比赛不存在")
            supportedSource(stored)
            val game = AppleReplayPrototype.replay(stored)
            verifySourceContexts(stored, game)
            val revisions = stored.objectValue("state").optional("correctionRevisions")?.jsonArray.orEmpty()
            require(revisions.size == game.historyRevisions.size)
            revisions.zip(game.historyRevisions).forEach { (raw, revision) ->
                val previous = History.run(game.copy(checkpoint = revision.previousCheckpoint), revision.previousRecords, revision.previousRecords.size, strict = true)
                require(previous.canSave) { previous.conflict ?: "更正前历史不能严格重放" }
                verifySourceContexts(stored, requireNotNull(previous.game), raw.jsonObject.objectValue("previousJournal"))
            }
            require(game.rules.mode == GameMode.standard && !game.isFinal && game.pendingDecision == null && !game.halfEndedAwaitingDecision) { "比赛不能在此导入比赛续记" }
            val state = stored.objectValue("state")
            verifyCurrentAppearance(state, game)
            require(game.clockHasStarted && game.clockRunningSince != null) { "重放时钟未启动，不能导入比赛续记" }
            require((state.optional("plateAppearancePitchCount")?.jsonPrimitive?.int ?: 0) == game.appearancePitches) { "原始打席投球数不一致" }
            return AppleGameContinuation(original, inspection, id, stored, game, emptyList())
        }

        fun restore(bytes: ByteArray): AppleGameContinuation {
            require(bytes.size <= SESSION_LIMIT) { "研究会话过大" }
            val outer = AppleWireJson.parse(bytes).jsonObject
            outer.only(setOf("format", "version", "payload", "checksum"))
            require(outer.string("format") == SESSION_FORMAT && outer.getValue("version").jsonPrimitive.let { !it.isString && it.intOrNull == 1 }) { "研究会话版本不受支持" }
            val payload = Base64.getDecoder().decode(outer.string("payload"))
            require(hash(payload) == outer.string("checksum")) { "研究会话校验失败" }
            val session = AppleWireJson.parse(payload).jsonObject
            session.only(setOf("source", "sourceChecksum", "gameID", "appended"))
            val source = Base64.getDecoder().decode(session.string("source"))
            require(hash(source) == session.string("sourceChecksum")) { "原备份校验失败" }
            var continuation = create(source, session.string("gameID"))
            val entries = session.arrayValue("appended"); require(entries.size <= 10_000)
            entries.forEach { raw ->
                val row = raw.jsonObject; row.only(setOf("id", "action", "atMillis"))
                val at = row.getValue("atMillis").jsonPrimitive
                require(!at.isString && at.longOrNull != null) { "续记时间无效" }
                continuation = continuation.appendNonterminalPitch(PitchAction.valueOf(row.string("action")), row.string("id"), at.long)
            }
            return continuation
        }

        private fun supportedSource(stored: JsonObject) {
            stored.only(setOf("id", "createdAt", "updatedAt", "scheduledAt", "startedAt", "playedAt", "playedTimeZoneID", "dateRevisions", "seasonID", "ourTeamID", "opponentTeamID", "isHome", "isSpectator", "rules", "lineup", "secondaryLineup", "status", "state", "openingPitchRecorded", "revision"))
            stored.checkBooleans("isHome", "isSpectator", "openingPitchRecorded")
            require(stored.getValue("status").jsonPrimitive.int == 0 && stored.optional("isSpectator")?.jsonPrimitive?.boolean != true && stored.optional("openingPitchRecorded")?.jsonPrimitive?.boolean == true) { "仅支持已开始的本机普通比赛" }
            require(stored.optional("dateRevisions")?.jsonArray.isNullOrEmpty()) { "日期更正尚未支持导入比赛续记" }
            checkRules(stored.objectValue("rules"))
            val state = stored.objectValue("state"); checkStateShape(state)
            val runningSince = state.optional("clockRunningSince")?.jsonPrimitive
            require(runningSince != null && !runningSince.isString && runningSince.doubleOrNull?.isFinite() == true) { "导入比赛续记暂不支持暂停或未启动的时钟" }
            require(stored.optional("startedAt") != null) { "比赛开始时间缺失，不能导入比赛续记" }
            val corrections = state.optional("correctionRevisions")?.jsonArray.orEmpty()
            corrections.forEach { raw ->
                val revision = raw.jsonObject
                revision.only(setOf("id", "date", "reason", "previousJournal", "appliedOperationIDs", "summary"))
                val previous = revision.objectValue("previousJournal")
                previous.only(setOf("version", "checkpoint", "operations", "legacyPrefix"))
                require(!previous.getValue("legacyPrefix").jsonPrimitive.boolean) { "旧版更正前缀不能导入比赛续记" }
                val checkpoint = AppleWireJson.parse(Base64.getDecoder().decode(previous.string("checkpoint"))).jsonObject
                checkStateShape(checkpoint)
                require(checkpoint == AppleWireJson.parse(Base64.getDecoder().decode(state.objectValue("historyJournal").string("checkpoint"))).jsonObject) { "更正更换过检查点，尚不支持导入比赛续记" }
                previous.arrayValue("operations").forEach { checkSupportedOperation(it.jsonObject) }
            }
            require(state.optional("pitchingOutsReviewRequired")?.jsonPrimitive?.boolean != true) { "投手出局待复核不能导入比赛续记" }
            require(state.optional("statisticsIncomplete")?.jsonPrimitive?.boolean != true || corrections.isNotEmpty()) { "非更正产生的待复核比赛尚不支持导入比赛续记" }
            require((state.arrayValue("playLog").none { it.jsonObject.getValue("isIncomplete").jsonPrimitive.boolean } &&
                state.arrayValue("scoringEvents").none { it.jsonObject.getValue("needsReview").jsonPrimitive.boolean }) ||
                state.optional("statisticsIncomplete")?.jsonPrimitive?.boolean == true) { "待复核标记与比赛状态不一致" }
            require(state.optional("pendingDecision") == null && state.optional("halfEndedAwaitingDecision")?.jsonPrimitive?.boolean != true) { "比赛有未处理确认" }
            require(state.optional("homeDesignatedHitterID") == null && state.optional("awayDesignatedHitterID") == null && state.optional("walkResponsiblePitcherID") == null && state.optional("strikeoutResponsibleBatterID") == null) { "特殊阵容／统计责任暂不支持" }
            require(listOf("automaticRunnerIDs", "homeExitedPlayerIDs", "awayExitedPlayerIDs").all { state.optional(it)?.jsonArray.isNullOrEmpty() }) { "特殊跑者或换人暂不支持" }
            val journal = state.objectValue("historyJournal"); journal.only(setOf("version", "checkpoint", "operations", "legacyPrefix"))
            require(!journal.getValue("legacyPrefix").jsonPrimitive.boolean) { "旧版历史前缀不能导入比赛续记" }
            val checkpoint = AppleWireJson.parse(Base64.getDecoder().decode(journal.string("checkpoint"))).jsonObject
            checkStateShape(checkpoint)
            val sourceRules = stored.objectValue("rules")
            require(state.optional("rulesSnapshot") == sourceRules && checkpoint.optional("rulesSnapshot") == sourceRules &&
                state.getValue("scheduledInnings") == sourceRules.getValue("scheduledInnings") && checkpoint.getValue("scheduledInnings") == sourceRules.getValue("scheduledInnings")) { "比赛与检查点规则不一致，不能导入比赛续记" }
            val ownHome = stored.getValue("isHome").jsonPrimitive.boolean
            val own = state.objectValue(if (ownHome) "homeTeam" else "awayTeam").id("id")
            val opponent = state.objectValue(if (ownHome) "awayTeam" else "homeTeam").id("id")
            require(stored.id("ourTeamID") == own && stored.id("opponentTeamID") == opponent) { "本队／对手身份与主客场不一致" }
            for (side in listOf("home", "away")) {
                require(state.getValue("${side}Team") == checkpoint.getValue("${side}Team") &&
                    state.getValue("${side}BattingOrderIDs") == checkpoint.getValue("${side}BattingOrderIDs") &&
                    state.getValue("${side}FieldingPlayerIDs") == checkpoint.getValue("${side}FieldingPlayerIDs")) { "来源含尚未支持的名单或阵容变化" }
            }
            fun checkLineup(key: String, home: Boolean) {
                stored.optional(key)?.jsonArray?.let { assignments ->
                    val rows = assignments.map { it.jsonObject }.sortedBy { it.getValue("battingOrder").jsonPrimitive.int }
                    rows.forEachIndexed { index, row ->
                        row.only(setOf("playerID", "battingOrder", "position"))
                        require(row.getValue("battingOrder").jsonPrimitive.int == index + 1) { "初始打序不连续" }
                    }
                    require(rows.map { it.id("playerID") } == checkpoint.arrayValue(if (home) "homeBattingOrderIDs" else "awayBattingOrderIDs").map(::uuid)) { "初始阵容与检查点不一致" }
                }
            }
            checkLineup("lineup", ownHome); checkLineup("secondaryLineup", !ownHome)
            val operations = journal.arrayValue("operations").map { it.jsonObject }; require(operations.isNotEmpty()) { "首球与开赛时钟暂不支持" }
            operations.forEach(::checkSupportedOperation)
            val events = state.arrayValue("scoringEvents"); val logs = state.arrayValue("playLog")
            val prefixEvents = checkpoint.optional("scoringEvents")?.jsonArray ?: JsonArray(emptyList()); val prefixLogs = checkpoint.arrayValue("playLog")
            require(events.take(prefixEvents.size) == prefixEvents.toList() && logs.take(prefixLogs.size) == prefixLogs.toList()) { "检查点事件或日志不一致" }
            require(operations.flatMap { it.arrayValue("eventIDs").map(::uuid) } == events.drop(prefixEvents.size).map { it.jsonObject.id("id") } &&
                operations.flatMap { it.arrayValue("logIDs").map(::uuid) } == logs.drop(prefixLogs.size).map { it.jsonObject.id("id") }) { "操作与事件／日志不是完整有序映射" }
            val eventsByID = events.associateBy { it.jsonObject.id("id") }
            operations.forEach { operation ->
                val ownedLogs = operation.arrayValue("logIDs").map(::uuid)
                require(operation.arrayValue("eventIDs").all { eventsByID.getValue(uuid(it)).jsonObject.optional("logEntryID")?.let(::uuid) in ownedLogs }) { "事件日志归属不一致" }
            }
            state.id("currentPlateAppearanceID")
        }

        private fun checkSupportedOperation(operation: JsonObject) {
            operation.only(setOf("id", "command", "context", "timestamp", "title", "eventIDs", "logIDs", "nextAppearanceID", "inserted", "edited", "acceptsNewContext", "errors", "correctedAt"))
            operation.checkBooleans("inserted", "edited", "acceptsNewContext")
            require(!operation.getValue("acceptsNewContext").jsonPrimitive.boolean && operation.arrayValue("errors").isEmpty()) { "重新指定上下文或补记失误暂不支持导入比赛续记" }
            val command = operation.objectValue("command"); require(command.size == 1)
            when (command.keys.single()) {
                "pitch" -> { val body = command.objectValue("pitch"); body.only(setOf("_0")); require(body.string("_0") in PitchAction.entries.map { it.title }) }
                "play" -> {
                    val body = command.objectValue("play"); body.only(setOf("_0", "_1", "_2")); require(body.string("_0") in setOf(Outcome.single.title, Outcome.error.title)) { "研究来源暂仅支持基础一垒安打或失误上垒" }
                    body.optional("_1")?.jsonObject?.let { defense ->
                        defense.only(setOf("id", "title", "notation", "routePositions", "assistPositions", "putoutPositions", "errorPosition"))
                        // Empty route with one assigned error is the supported no-out error play.
                        require(body.string("_0") == Outcome.error.title &&
                            listOf("routePositions", "assistPositions", "putoutPositions").all { defense.arrayValue(it).isEmpty() } &&
                            defense.getValue("errorPosition").jsonPrimitive.let { !it.isString && it.intOrNull in 1..9 }) { "此守备操作尚未支持导入比赛续记" }
                    }
                    body.arrayValue("_2").forEach { raw ->
                        val decision = raw.jsonObject; decision.only(setOf("id", "player", "origin", "destination")); checkPlayer(decision.objectValue("player"))
                        require(decision.id("id") == decision.objectValue("player").id("id"))
                        for ((key, choices) in listOf("origin" to setOf("batter", "base"), "destination" to setOf("hold", "out", "score", "base"))) {
                            val variant = decision.objectValue(key); require(variant.size == 1 && variant.keys.single() in choices)
                            val fields = variant.values.single().jsonObject
                            if (variant.keys.single() == "base") { fields.only(setOf("_0")); require(fields.getValue("_0").jsonPrimitive.let { !it.isString && it.intOrNull in 1..3 }) }
                            else require(fields.isEmpty())
                        }
                    }
                }
                else -> error("原比赛含尚未支持的操作，不能导入比赛续记")
            }
            operation.objectValue("context").only(setOf("inning", "isTop", "batterIndex", "homeOrder", "awayOrder", "homeFielders", "awayFielders", "positions", "runners"))
        }

        private fun checkRules(value: JsonObject) {
            value.only(setOf("scheduledInnings", "fieldersCount", "timeLimitMinutes", "timeWarningMinutes", "pitchLimit", "pitchWarningRemaining", "pitcherInningsLimit", "usesDesignatedHitter", "allowsTwoWayPlayer", "mode", "halfInningRunLimit", "coachPitchLimit", "initialBalls", "initialStrikes", "twoStrikeFoulPolicy", "competitionFormat", "inningsLimit", "rulesVersion", "recordingMethod"))
            value.checkBooleans("usesDesignatedHitter", "allowsTwoWayPlayer")
            value.optional("rulesVersion")?.let { require(it.jsonPrimitive.let { p -> !p.isString && p.intOrNull in 1..2 }) { "规则版本不受支持" } }
            require(value.optional("mode")?.jsonPrimitive?.content in setOf(null, "standard") && value.getValue("fieldersCount").jsonPrimitive.int == 9 && value.optional("competitionFormat")?.jsonPrimitive?.content in setOf(null, "innings") && value.optional("usesDesignatedHitter")?.jsonPrimitive?.boolean != true && value.optional("allowsTwoWayPlayer")?.jsonPrimitive?.boolean != true) { "导入比赛续记暂不支持特殊比赛规则" }
        }

        private fun checkStateShape(state: JsonObject) {
            state.only(setOf("homeTeam", "awayTeam", "scheduledInnings", "rulesSnapshot", "extraFoulUsed", "pitchingOutsAccountingVersion", "pitchingOutsReviewRequired", "pitchingOutsBeforeRepair", "inning", "isTop", "balls", "strikes", "outs", "homeRunsByInning", "awayRunsByInning", "homeHits", "awayHits", "homeErrors", "awayErrors", "homeBatterIndex", "awayBatterIndex", "baseRunners", "batting", "pitching", "fielding", "playLog", "isFinal", "activeHomePitcherID", "activeAwayPitcherID", "homeBattingOrderIDs", "awayBattingOrderIDs", "scoringEvents", "homeExitedPlayerIDs", "awayExitedPlayerIDs", "automaticRunnerIDs", "homeFieldingPlayerIDs", "awayFieldingPlayerIDs", "homeDesignatedHitterID", "awayDesignatedHitterID", "clockRunningSince", "clockElapsedSeconds", "clockDisplayMode", "plateAppearances", "currentPlateAppearanceID", "plateAppearancePitchCount", "pendingDecision", "halfEndedAwaitingDecision", "canResumePrecisely", "recordsLineupSnapshots", "historyJournal", "correctionRevisions", "statisticsIncomplete", "runnerPitcherIDs", "unearnedRunnerIDs", "walkResponsiblePitcherID", "strikeoutResponsibleBatterID"))
            state.checkBooleans("extraFoulUsed", "pitchingOutsReviewRequired", "isTop", "isFinal", "halfEndedAwaitingDecision", "canResumePrecisely", "recordsLineupSnapshots", "statisticsIncomplete")
            state.optional("pitchingOutsAccountingVersion")?.let { require(it.jsonPrimitive.let { p -> !p.isString && p.intOrNull in 1..2 }) { "投手出局数核算版本不受支持" } }
            require(state.optional("pitchingOutsBeforeRepair") == null && state.optional("extraFoulUsed")?.jsonPrimitive?.boolean != true) { "特殊统计状态暂不支持" }
            checkReferences(state)
            state.optional("rulesSnapshot")?.jsonObject?.let(::checkRules)
            for (key in listOf("homeTeam", "awayTeam")) state.objectValue(key).let { team -> team.only(setOf("id", "name", "shortName", "city", "players", "isDemo")); team.checkBooleans("isDemo"); team.arrayValue("players").forEach { checkPlayer(it.jsonObject) } }
            state.arrayValue("baseRunners").chunked(2).forEach { checkPlayer(it[1].jsonObject) }
            for ((key, sample) in listOf("batting" to GameJson.encodeToJsonElement(BattingLine()).jsonObject, "pitching" to GameJson.encodeToJsonElement(PitchingLine()).jsonObject, "fielding" to GameJson.encodeToJsonElement(FieldingLine()).jsonObject)) state.arrayValue(key).chunked(2).forEach { it[1].jsonObject.only(sample.keys) }
            state.arrayValue("playLog").forEach { it.jsonObject.only(setOf("id", "inning", "isTop", "text", "timestamp", "isIncomplete")); it.jsonObject.checkBooleans("isIncomplete") }
            state.optional("scoringEvents")?.jsonArray?.forEach { raw ->
                val event = raw.jsonObject
                event.only(setOf("id", "logEntryID", "inning", "isTop", "timestamp", "category", "title", "notation", "primaryPlayerID", "secondaryPlayerID", "plateAppearanceBatterID", "plateAppearanceID", "effectiveScorerIDs", "runnerMovements", "needsReview", "ballStatus", "resolvedOutcome", "automaticRunnerRuns", "beforeSituation", "afterSituation", "reviewedAt", "reviewNote", "defensiveRoute"))
                event.checkBooleans("needsReview")
                event.optional("effectiveScorerIDs")?.jsonArray?.forEach(::uuid)
                event.arrayValue("runnerMovements").forEach { it.jsonObject.only(setOf("playerID", "origin", "destination")) }
                for (key in listOf("beforeSituation", "afterSituation")) event.optional(key)?.jsonObject?.let { snapshot ->
                    snapshot.only(setOf("inning", "isTop", "balls", "strikes", "outs", "homeRunsByInning", "awayRunsByInning", "homeBatterIndex", "awayBatterIndex", "baseRunners", "activeHomePitcherID", "activeAwayPitcherID", "homeBattingOrderIDs", "awayBattingOrderIDs", "runnerPitcherIDs", "unearnedRunnerIDs", "extraFoulUsed"))
                    snapshot.checkBooleans("isTop", "extraFoulUsed")
                    checkReferences(snapshot)
                    snapshot.arrayValue("baseRunners").forEach { raw ->
                        val occupied = raw.jsonObject; occupied.only(setOf("base", "playerID")); occupied.id("playerID")
                        require(occupied.getValue("base").jsonPrimitive.let { !it.isString && it.intOrNull in 1..3 }) { "历史垒位无效" }
                    }
                }
            }
            state.optional("plateAppearances")?.jsonArray?.forEach { it.jsonObject.only(setOf("id", "inning", "isTop", "batterIDs", "completed", "interrupted", "previousSegmentID")) }
        }
        private fun JsonObject.checkBooleans(vararg keys: String) {
            keys.forEach { key -> optional(key)?.jsonPrimitive?.let { require(!it.isString && it.booleanOrNull != null) { "$key 不是布尔值" } } }
        }
        private fun checkReferences(value: JsonObject) {
            for (key in listOf("activeHomePitcherID", "activeAwayPitcherID", "currentPlateAppearanceID", "homeDesignatedHitterID", "awayDesignatedHitterID", "walkResponsiblePitcherID", "strikeoutResponsibleBatterID")) value.optional(key)?.let(::uuid)
            for (key in listOf("homeBattingOrderIDs", "awayBattingOrderIDs", "homeFieldingPlayerIDs", "awayFieldingPlayerIDs", "unearnedRunnerIDs", "automaticRunnerIDs", "homeExitedPlayerIDs", "awayExitedPlayerIDs")) value.optional(key)?.jsonArray?.let { array ->
                val ids = array.map(::uuid); require(ids.size == ids.distinct().size) { "$key 编号重复" }
            }
            value.optional("runnerPitcherIDs")?.jsonArray?.let { array ->
                require(array.size % 2 == 0) { "跑者投手对应不完整" }
                val pairs = array.chunked(2).map { uuid(it[0]) to uuid(it[1]) }
                require(pairs.map { it.first }.distinct().size == pairs.size) { "跑者投手编号重复" }
            }
        }
        private fun verifyCurrentAppearance(state: JsonObject, game: Game) {
            val appearanceID = state.id("currentPlateAppearanceID")
            val appearance = state.arrayValue("plateAppearances").single { it.jsonObject.id("id") == appearanceID }.jsonObject
            require(!appearance.getValue("completed").jsonPrimitive.boolean && !appearance.getValue("interrupted").jsonPrimitive.boolean &&
                appearance.getValue("inning").jsonPrimitive.int == game.inning && appearance.getValue("isTop").jsonPrimitive.boolean == game.isTop &&
                appearance.arrayValue("batterIDs").lastOrNull()?.let(::uuid) == game.batter.id) { "当前打席局次或打者不适合续记" }
        }
        private fun checkPlayer(value: JsonObject) {
            value.only(setOf("id", "chineseName", "englishName", "numbers", "jerseyNumbers", "primaryPosition"))
            value.id("id"); value.string("chineseName"); value.string("englishName")
            require(value.getValue("primaryPosition").jsonPrimitive.let { !it.isString && it.intOrNull in 1..10 }) { "球员守位无效" }
            val numbers = value.arrayValue("numbers").map { raw -> raw.jsonPrimitive.also { require(!it.isString && it.intOrNull in 0..99) }.int }
            value.optional("jerseyNumbers")?.jsonArray?.let { raw ->
                val labels = raw.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
                require(labels.isNotEmpty() && labels.distinct().size == labels.size && labels.all { it == "00" || it.toIntOrNull()?.let { number -> number in 0..99 && number.toString() == it } == true } && labels.map(String::toInt) == numbers) { "球员背号原文无效" }
            }
        }
        private fun verifySourceContexts(stored: JsonObject, game: Game, rawJournal: JsonObject = stored.objectValue("state").objectValue("historyJournal")) {
            val state = stored.objectValue("state")
            val positions = (state.objectValue("homeTeam").arrayValue("players") + state.objectValue("awayTeam").arrayValue("players"))
                .associate { it.jsonObject.id("id") to it.jsonObject.getValue("primaryPosition").jsonPrimitive.int }
            // AppleReplayPrototype restores its original records after a non-strict replay. Those
            // contexts are not independent evidence. Re-run strictly and compare normalized output.
            val checked = History.run(game, game.journal, game.journal.size, strict = true)
            require(checked.canSave) { checked.conflict ?: "原历史上下文无法严格重放" }
            val operations = rawJournal.arrayValue("operations")
            require(operations.size == checked.records.size) { "原历史操作数量不一致" }
            operations.zip(checked.records).forEach { (raw, record) ->
                val context = raw.jsonObject.objectValue("context"); val actual = requireNotNull(record.context)
                require(context.getValue("inning").jsonPrimitive.int == actual.inning && context.getValue("isTop").jsonPrimitive.boolean == actual.isTop && context.getValue("batterIndex").jsonPrimitive.int == actual.batterIndex &&
                    context.arrayValue("homeOrder").map(::uuid) == actual.homeOrder && context.arrayValue("awayOrder").map(::uuid) == actual.awayOrder &&
                    context.arrayValue("homeFielders").map(::uuid).toSet() == actual.homePositions.values.toSet() && context.arrayValue("awayFielders").map(::uuid).toSet() == actual.awayPositions.values.toSet()) { "原操作局次或阵容与重放不一致" }
                val sourcePositions = context.arrayValue("positions").chunked(2).associate { uuid(it[0]) to it[1].jsonPrimitive.int }
                val runners = context.arrayValue("runners").chunked(2).associate { it[0].jsonPrimitive.int to uuid(it[1]) }
                val expectedRunners = record.bases.mapIndexedNotNull { index, id -> id?.let { index + 1 to it } }.toMap()
                require(sourcePositions == positions && runners == expectedRunners) { "原操作守位或垒况与重放不一致" }
            }
        }
        private fun situation(state: JsonObject) = buildJsonObject {
            for (key in listOf("inning", "isTop", "balls", "strikes", "outs", "homeRunsByInning", "awayRunsByInning", "homeBatterIndex", "awayBatterIndex", "activeHomePitcherID", "activeAwayPitcherID", "runnerPitcherIDs", "unearnedRunnerIDs", "extraFoulUsed")) state.optional(key)?.let { put(key, it) }
            put("baseRunners", buildJsonArray { state.arrayValue("baseRunners").chunked(2).forEach { (base, player) -> add(buildJsonObject { put("base", base); put("playerID", player.jsonObject.getValue("id")) }) } })
            // iOS emits lineup snapshots only when recordsLineupSnapshots is enabled.
            if (state.optional("recordsLineupSnapshots")?.jsonPrimitive?.boolean == true) for (key in listOf("homeBattingOrderIDs", "awayBattingOrderIDs")) put(key, state.getValue(key))
        }
        private fun context(state: JsonObject) = buildJsonObject {
            put("inning", state.getValue("inning")); put("isTop", state.getValue("isTop"))
            put("batterIndex", state.getValue(if (state.getValue("isTop").jsonPrimitive.boolean) "awayBatterIndex" else "homeBatterIndex"))
            for (side in listOf("home", "away")) { put("${side}Order", state.getValue("${side}BattingOrderIDs")); put("${side}Fielders", state.getValue("${side}FieldingPlayerIDs")) }
            put("positions", buildJsonArray { for (side in listOf("homeTeam", "awayTeam")) state.objectValue(side).arrayValue("players").forEach { add(it.jsonObject.getValue("id")); add(it.jsonObject.getValue("primaryPosition")) } })
            put("runners", buildJsonArray { state.arrayValue("baseRunners").chunked(2).forEach { (base, player) -> add(base); add(player.jsonObject.getValue("id")) } })
        }
        private fun coreState(game: Game): JsonObject = GameJson.encodeToJsonElement(game).jsonObject.let { value -> JsonObject(value.filterKeys { it !in setOf("revision", "updatedAt", "entries", "journal", "cursor", "checkpoint") }) }
        private fun sourceIDs(snapshot: JsonObject): Set<String> {
            val ids = mutableSetOf<String>()
            val pattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            fun scan(value: JsonElement) {
                when (value) {
                    is JsonObject -> {
                        value.values.forEach(::scan)
                        // Checkpoint IDs may not be present in the current corrected state.
                        if (value["operations"] is JsonArray && value.optional("checkpoint") != null)
                            scan(AppleWireJson.parse(Base64.getDecoder().decode(value.string("checkpoint"))))
                    }
                    is JsonArray -> value.forEach(::scan)
                    is JsonPrimitive -> if (value.isString && pattern.matches(value.content)) ids += appleUUID(value.content)
                }
            }
            scan(snapshot)
            return ids
        }
        private fun stableID(operationID: String, component: String): String {
            // Same SHA-256 prefix rule as iOS HistoryCorrection.stableHistoryID, not UUID v3/MD5.
            val bytes = MessageDigest.getInstance("SHA-256").digest((operationID.uppercase() + component).toByteArray(Charsets.UTF_8))
            val hex = bytes.take(16).joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun uuid(value: JsonElement) = appleUUID(value.jsonPrimitive.also { require(it.isString) }.content)
        private fun JsonObject.id(key: String) = uuid(getValue(key))
        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
        private fun JsonObject.optional(key: String) = this[key]?.takeUnless { it == JsonNull }
        private fun JsonObject.objectValue(key: String) = getValue(key).jsonObject
        private fun JsonObject.arrayValue(key: String) = getValue(key).jsonArray
        private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
        private fun JsonObject.only(keys: Set<String>) = require(this.keys.all { it in keys }) { "导入比赛续记不支持字段：${this.keys - keys}" }
    }
}
