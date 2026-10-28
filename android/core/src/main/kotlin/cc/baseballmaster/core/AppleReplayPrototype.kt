package cc.baseballmaster.core

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Research-only replay. This restricted prototype is not used for backup import or export. */
internal object AppleReplayPrototype {
    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: error("缺少 $key")
    private fun JsonObject.int(key: String, fallback: Int? = null): Int = this[key]?.jsonPrimitive?.intOrNull ?: fallback ?: error("缺少 $key")
    private fun JsonObject.bool(key: String, fallback: Boolean = false) = this[key]?.jsonPrimitive?.booleanOrNull ?: fallback
    private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: error("缺少 $key")
    private fun id(s: String) = appleUUID(s)
    private fun date(value: JsonElement?) = value?.jsonPrimitive?.doubleOrNull?.let { require(it.isFinite()); ((it + 978307200.0) * 1000.0).toLong() }
    private fun ids(a: JsonArray) = a.map { id(it.jsonPrimitive.content) }
    private fun team(o: JsonObject, opponent: Boolean): Team {
        val players = o.array("players").map { raw ->
            val p = raw.jsonObject
            val nums = p.array("numbers").map { it.jsonPrimitive.int }
            val labels = p["jerseyNumbers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: nums.map(Int::toString)
            require(labels.isNotEmpty() && labels.map { it.toIntOrNull() } == nums)
            val cn = p.text("chineseName"); val en = p.text("englishName")
            Player(id(p.text("id")), cn.ifBlank { en }, labels.first(), en, p.int("primaryPosition"), labels, cn).also(Roster::validate)
        }
        return Team(id(o.text("id")), o.text("name"), players, isOpponent = opponent, city = o.text("city"), shortName = o.text("shortName"))
    }
    private fun rules(o: JsonObject): Rules {
        val mode = GameMode.valueOf(o["mode"]?.jsonPrimitive?.content ?: "standard")
        val fielders = o.int("fieldersCount")
        require(fielders == if (mode == GameMode.slowPitch) 10 else 9)
        return Rules(mode = mode, initialBalls = o.int("initialBalls", if (mode == GameMode.slowPitch) 1 else 0),
            initialStrikes = o.int("initialStrikes", if (mode == GameMode.slowPitch) 1 else 0),
            coachPitchLimit = o.int("coachPitchLimit", 6), oneExtraFoul = o["twoStrikeFoulPolicy"]?.jsonPrimitive?.content != "outImmediately",
            innings = o.int("scheduledInnings"), timed = (o["competitionFormat"]?.jsonPrimitive?.content ?: if (mode == GameMode.slowPitch) "timed" else "innings") == "timed",
            timeLimitMinutes = o["timeLimitMinutes"]?.jsonPrimitive?.intOrNull, inningsLimit = o["inningsLimit"]?.jsonPrimitive?.intOrNull,
            halfRunLimit = o["halfInningRunLimit"]?.jsonPrimitive?.intOrNull, pitchLimit = o["pitchLimit"]?.jsonPrimitive?.intOrNull,
            pitcherInningsLimit = o["pitcherInningsLimit"]?.jsonPrimitive?.intOrNull, recordingMethod = o["recordingMethod"]?.jsonPrimitive?.content ?: "traditional",
            designatedHitter = o.bool("usesDesignatedHitter"), twoWayPlayer = o.bool("allowsTwoWayPlayer"),
            timeWarningMinutes = o["timeWarningMinutes"]?.jsonPrimitive?.intOrNull, pitchWarningRemaining = o["pitchWarningRemaining"]?.jsonPrimitive?.intOrNull)
    }
    private fun decision(raw: JsonElement): Decision {
        val d = raw.jsonObject
        val origin = d["origin"]!!.jsonObject.let { o -> if ("batter" in o) 0 else o["base"]!!.jsonObject.int("_0") }
        val destination = d["destination"]!!.jsonObject.let { o -> when {
            "hold" in o -> 0; "out" in o -> -1; "score" in o -> 4; "base" in o -> o["base"]!!.jsonObject.int("_0"); else -> error("跑垒结果不受支持")
        } }
        return Decision(id(d.text("id")), origin, destination)
    }
    private fun fielding(f: JsonObject) = Fielding(route = f.array("route").map { it.jsonPrimitive.int }, errorPosition = f["errorPosition"]?.jsonPrimitive?.intOrNull,
        status = f["status"]?.jsonPrimitive?.content ?: error("缺少守备状态"))
    private fun participants(f: JsonObject): List<Participant>? = f["participants"]?.jsonArray?.map { raw ->
        val p = raw.jsonObject; Participant(p.int("position"), id(p.text("playerID")), p.text("name"), p.text("number"))
    }
    private fun command(op: JsonObject): Command {
        val sourceObservation = op["battedBall"]?.jsonObject
        val detailRows = sourceObservation?.get("runnerDetails")?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val details = detailRows.associateBy { id(it.text("playerID")) }
        require(details.size == detailRows.size) { "跑者详情编号重复" }
        val observation = sourceObservation?.let { o ->
            Observation(type = o.text("type"), precision = o.text("precision"), region = o["region"]?.jsonPrimitive?.content,
                x = o["x"]?.jsonPrimitive?.doubleOrNull, y = o["y"]?.jsonPrimitive?.doubleOrNull, fielding = o["fielding"]?.jsonObject?.let(::fielding))
        }
        val allSnapshots = (listOfNotNull(sourceObservation?.get("fielding")?.jsonObject) + detailRows.mapNotNull { it["fielding"]?.jsonObject }).mapNotNull(::participants)
        val participantSnapshots = allSnapshots.firstOrNull()
        require(allSnapshots.all { it.sortedBy(Participant::position) == participantSnapshots?.sortedBy(Participant::position) }) { "不同守备路线的历史球员资料不一致" }
        val command = op["command"]!!.jsonObject; require(command.size == 1)
        val (kind, body) = command.entries.single(); val fields = body.jsonObject
        val at = date(op["timestamp"]) ?: error("操作时间缺失")
        return when (kind) {
            "pitch" -> Command("pitch", pitch = PitchAction.entries.first { it.title == fields.text("_0") }, at = at)
            "play" -> {
                val defense = fields["_1"]?.jsonObject?.let { f ->
                    Fielding(route = f.array("routePositions").map { it.jsonPrimitive.int }, assists = f.array("assistPositions").map { it.jsonPrimitive.int },
                        putouts = f.array("putoutPositions").map { it.jsonPrimitive.int }, errorPosition = f["errorPosition"]?.jsonPrimitive?.intOrNull,
                        status = if (f.array("routePositions").isEmpty()) "pending" else "recorded")
                }
                val decisions = fields.array("_2").map(::decision).map { move ->
                    val detail = details[move.playerID]
                    if (detail == null) move else move.copy(cause = detail.text("cause"), fielding = detail["fielding"]?.jsonObject?.let(::fielding))
                }
                require(details.keys.all { id -> decisions.any { it.playerID == id } }) { "跑者详情没有对应的跑垒决定" }
                Command("play", outcome = Outcome.entries.first { it.title == fields.text("_0") }, decisions = decisions,
                    observation = observation, fielding = defense, timingRunCounts = fields["_3"]?.jsonPrimitive?.booleanOrNull,
                    participantSnapshots = participantSnapshots, at = at)
            }
            "runner" -> {
                require(observation == null)
                val names = mapOf("偷垒成功" to "stolenBase", "双重盗垒" to "doubleSteal", "延迟盗垒" to "delayedSteal", "守备转传时额外进垒" to "relayAdvance", "守备失误造成额外进垒" to "defensiveErrorAdvance", "跑垒出局" to "caughtStealing", "牵制出局" to "pickoff", "触杀出局" to "tagOut", "封杀出局" to "forceOut", "申诉出局" to "appealOut", "离垒过早" to "leftEarly", "漏踩垒位" to "missedBase", "跑出限制道" to "outOfBasePath", "超越前位跑者" to "passedRunner", "跑者妨碍守备" to "runnerInterference", "跑垒员被击球击中" to "runnerHitByBall", "教练员帮助跑者" to "coachAssistance", "球太偏，跑者进垒" to "wildPitch", "捕手漏球，跑者进垒" to "passedBall", "球漏过去，分不清原因" to "uncertainLooseBall", "投手犯规，跑者进垒" to "balk")
                Command("runner", runnerKind = names[fields.text("_0")] ?: error("跑垒事件不受支持"), decisions = fields.array("_1").map(::decision), timingRunCounts = fields["_2"]?.jsonPrimitive?.booleanOrNull, at = at)
            }
            "hitByPitch", "intentionalWalk", "foulBunt", "endHalf", "skipTB" -> {
                require(observation == null && fields.isEmpty())
                Command(kind, at = at)
            }
            "reopen" -> Command("reopen", flag = fields["_0"]?.jsonPrimitive?.booleanOrNull ?: false, at = at)
            "finish" -> {
                val reasons = mapOf("规定局数完成" to "regulation", "再见分结束" to "walkOff", "时间限制结束" to "timeLimit", "提前结束规则" to "mercyRule", "弃权结束" to "forfeit", "天气或场地原因" to "weather", "比赛中断" to "suspended", "记录员结束记录" to "scorerDecision")
                val reason = reasons[fields.text("_0")] ?: error("结束原因不受支持")
                Command(if (reason == "suspended") "suspend" else "finish", text = reason, at = date(fields["_1"]) ?: at)
            }
            else -> error("暂不支持历史操作 $kind")
        }
    }
    /** Replay only clean checkpoints with supported commands. No persisted-data mutation occurs. */
    fun replay(stored: JsonObject): Game {
        require(stored["sharedTeamID"] == null && stored["sharedGameID"] == null && !stored.bool("isSpectator")) { "暂不支持共享或观赛比赛" }
        val state = stored["state"]!!.jsonObject
        val journal = state["historyJournal"]?.jsonObject ?: return replayLegacy(stored, state)
        fun runJournal(source: JsonObject): Game {
        require(source.int("version") == 1 && !source.bool("legacyPrefix"))
        val checkpoint = GameJson.parseToJsonElement(Base64.getDecoder().decode(source.text("checkpoint")).toString(Charsets.UTF_8)).jsonObject
        require(checkpoint.int("inning") == 1 && checkpoint.bool("isTop") && checkpoint.int("outs") == 0 && checkpoint.int("homeBatterIndex") == 0 && checkpoint.int("awayBatterIndex") == 0)
        for (field in listOf("baseRunners", "batting", "pitching", "fielding")) require(checkpoint.array(field).isEmpty()) { "暂不支持非空检查点" }
        require(checkpoint.array("homeRunsByInning").all { it.jsonPrimitive.int == 0 } && checkpoint.array("awayRunsByInning").all { it.jsonPrimitive.int == 0 })
        val our = stored["ourTeamID"]?.jsonPrimitive?.content?.let(::id) ?: error("缺少本队")
        val homeRaw = checkpoint["homeTeam"]!!.jsonObject; val awayRaw = checkpoint["awayTeam"]!!.jsonObject
        val home = team(homeRaw, id(homeRaw.text("id")) != our); val away = team(awayRaw, id(awayRaw.text("id")) != our)
        val rules = rules(stored["rules"]!!.jsonObject)
        fun positions(team: Team, field: String): Map<Int, String> {
            val names = ids(checkpoint.array(field)).toSet()
            return team.players.filter { it.id in names }.associate { it.position to it.id }.also { require(it.size == rules.fielders) }
        }
        var game = Game(id(stored.text("id")), home, away, rules,
            balls = checkpoint.int("balls"), strikes = checkpoint.int("strikes"),
            homeOrder = ids(checkpoint.array("homeBattingOrderIDs")), awayOrder = ids(checkpoint.array("awayBattingOrderIDs")),
            homePositions = positions(home, "homeFieldingPlayerIDs"), awayPositions = positions(away, "awayFieldingPlayerIDs"),
            homePitcherID = checkpoint["activeHomePitcherID"]?.jsonPrimitive?.content?.let(::id), awayPitcherID = checkpoint["activeAwayPitcherID"]?.jsonPrimitive?.content?.let(::id),
            homeRunsByInning = checkpoint.array("homeRunsByInning").map { it.jsonPrimitive.int }, awayRunsByInning = checkpoint.array("awayRunsByInning").map { it.jsonPrimitive.int },
            ourTeamID = our, createdAt = date(stored["createdAt"]) ?: error("创建时间缺失"),
            playedTimeZoneID = stored["playedTimeZoneID"]?.jsonPrimitive?.content ?: "Asia/Shanghai")
        val checkpointData = MatchEngine.checkpoint(game)
        val records = mutableListOf<RecordedCommand>()
        source.array("operations").forEach { raw ->
            val op = raw.jsonObject
            val errors = op.array("errors").map { element -> element.jsonObject.let { ErrorCredit(id(it.text("playerID")), it.int("count", 1), it["note"]?.jsonPrimitive?.content ?: "漏记守备失误") } }
            val ruling = op["statisticsRuling"]?.jsonObject?.let { r ->
                val values = r.array("earnedRuns"); require(values.size % 2 == 0)
                StatisticsRuling(r.int("runsBattedIn"), values.chunked(2).associate { id(it[0].jsonPrimitive.content) to it[1].jsonPrimitive.int }, ids(r.array("unearnedRunnerIDs")).toSet())
            }
            val sourceContext = op["context"]!!.jsonObject
            val homeOrder = ids(sourceContext.array("homeOrder")); val awayOrder = ids(sourceContext.array("awayOrder"))
            val contextTop = sourceContext.bool("isTop")
            val batterIndex = sourceContext.int("batterIndex")
            val order = if (contextTop) awayOrder else homeOrder
            require(order.isNotEmpty() && batterIndex >= 0)
            val positions = sourceContext.array("positions"); require(positions.size % 2 == 0)
            val positionByID = positions.chunked(2).associate { id(it[0].jsonPrimitive.content) to it[1].jsonPrimitive.int }
            fun positionMap(key: String): Map<Int, String> = ids(sourceContext.array(key)).associateBy { player -> positionByID[player] ?: error("历史守位缺失") }
            val context = HistoryContext(sourceContext.int("inning"), contextTop, batterIndex, homeOrder, awayOrder,
                positionMap("homeFielders"), positionMap("awayFielders"))
            val runnerPairs = sourceContext.array("runners"); require(runnerPairs.size % 2 == 0)
            val runners = MutableList<String?>(3) { null }
            runnerPairs.chunked(2).forEach { pair -> val base = pair[0].jsonPrimitive.int; require(base in 1..3 && runners[base - 1] == null); runners[base - 1] = id(pair[1].jsonPrimitive.content) }
            records += RecordedCommand(id(op.text("id")), command(op), order[batterIndex % order.size], runners,
                acceptContext = op.bool("acceptsNewContext"), context = context,
                inserted = op.bool("inserted"), edited = op.bool("edited"), additionalErrors = errors, statisticsRuling = ruling)
            val preview = History.run(game.copy(checkpoint = checkpointData), records, records.size, strict = false)
            check(preview.canSave) { preview.conflict ?: "历史重放失败" }
            game = preview.game!!
            game.journal = records.toList(); game.cursor = records.size
        }
        if (records.isEmpty()) game.checkpoint = checkpointData
        return game
        }
        var game = runJournal(journal)
        val revisions = state["correctionRevisions"]?.jsonArray.orEmpty().map { raw ->
            val revision = raw.jsonObject
            val previous = runJournal(revision["previousJournal"]!!.jsonObject)
            HistoryRevision(id(revision.text("id")), date(revision["date"]) ?: error("更正时间缺失"), revision.text("reason"),
                previous.checkpoint ?: error("更正检查点缺失"), previous.journal,
                ids(revision.array("appliedOperationIDs")).toSet(), revision.array("summary").map { it.jsonPrimitive.content })
        }
        if (revisions.isNotEmpty()) {
            game.historyRevisions = revisions
            game.correctionRevisions = revisions.map { "${it.at} · ${it.reason}" }
            val applied = revisions.last().appliedOperationIDs
            val prefix = game.journal.takeWhile { it.id in applied }
            require(prefix.map { it.id }.toSet() == applied) { "更正历史与后续操作不一致" }
            game.correctionBoundary = prefix.size
        }
        game.statisticsIncomplete = state.bool("statisticsIncomplete") || game.statisticsIncomplete
        game.revision = stored["revision"]?.jsonPrimitive?.longOrNull ?: game.revision
        validateResult(game, state)
        return game
    }

    private fun validateResult(game: Game, state: JsonObject) {
        require(game.isFinal == state.bool("isFinal")) { "重放比赛状态不一致" }
        require(game.inning == state.int("inning") && game.isTop == state.bool("isTop") && game.balls == state.int("balls") && game.strikes == state.int("strikes") && game.outs == state.int("outs")) { "重放局面与原始资料不一致" }
        require(game.homeRunsByInning == state.array("homeRunsByInning").map { it.jsonPrimitive.int } && game.awayRunsByInning == state.array("awayRunsByInning").map { it.jsonPrimitive.int }) { "重放比分不一致" }
        require(game.homeHits == state.int("homeHits") && game.awayHits == state.int("awayHits") && game.homeErrors == state.int("homeErrors") && game.awayErrors == state.int("awayErrors")) { "重放数据不一致" }
        fun pairs(key: String): Map<String, JsonElement> {
            val values = state.array(key); require(values.size % 2 == 0)
            return values.chunked(2).associate { id(it[0].jsonPrimitive.content) to it[1] }
        }
        val batting = pairs("batting").mapValues { GameJson.decodeFromJsonElement<BattingLine>(it.value) }
        val pitching = pairs("pitching").mapValues { GameJson.decodeFromJsonElement<PitchingLine>(it.value) }
        val fielding = pairs("fielding").mapValues { GameJson.decodeFromJsonElement<FieldingLine>(it.value) }
        require(game.batting == batting && game.pitching == pitching && game.fielding == fielding) { "重放球员统计与原始资料不一致" }
        val baseValues = state.array("baseRunners"); require(baseValues.size % 2 == 0)
        val bases = MutableList<String?>(3) { null }
        baseValues.chunked(2).forEach { pair ->
            val base = pair[0].jsonPrimitive.int; require(base in 1..3 && bases[base - 1] == null)
            bases[base - 1] = id(pair[1].jsonObject.text("id"))
        }
        require(game.bases == bases) { "重放垒况与原始资料不一致" }
        require(game.homeOrder == ids(state.array("homeBattingOrderIDs")) && game.awayOrder == ids(state.array("awayBattingOrderIDs"))) { "重放打序不一致" }
        require(game.homeBatterIndex == state.int("homeBatterIndex") && game.awayBatterIndex == state.int("awayBatterIndex")) { "重放棒次不一致" }
        state["activeHomePitcherID"]?.jsonPrimitive?.content?.let { require(game.homePitcherID == id(it)) { "主队投手不一致" } }
        state["activeAwayPitcherID"]?.jsonPrimitive?.content?.let { require(game.awayPitcherID == id(it)) { "客队投手不一致" } }
        state["runnerPitcherIDs"]?.jsonArray?.let { raw ->
            require(raw.size % 2 == 0)
            val owners = raw.chunked(2).associate { id(it[0].jsonPrimitive.content) to id(it[1].jsonPrimitive.content) }
            require(game.runnerPitcherIDs == owners) { "跑者投手责任不一致" }
        }
        state["unearnedRunnerIDs"]?.jsonArray?.let { require(game.unearnedRunnerIDs == ids(it).toSet()) { "非自责跑者不一致" } }
        state["automaticRunnerIDs"]?.jsonArray?.let { require(game.automaticRunnerIDs == ids(it).toSet()) { "自动跑者不一致" } }
    }

    private fun replayLegacy(stored: JsonObject, state: JsonObject): Game {
        require(state["correctionRevisions"] == null) { "旧记录含不支持的纠错资料" }
        val events = state.array("scoringEvents"); require(events.isNotEmpty()) { "旧比赛没有足够事件，无法可靠重放" }
        val our = stored["ourTeamID"]?.jsonPrimitive?.content?.let(::id) ?: error("缺少本队")
        val homeRaw = state["homeTeam"]!!.jsonObject; val awayRaw = state["awayTeam"]!!.jsonObject
        val home = team(homeRaw, id(homeRaw.text("id")) != our); val away = team(awayRaw, id(awayRaw.text("id")) != our)
        val rules = rules(stored["rules"]!!.jsonObject)
        val homeOrder = ids(state.array("homeBattingOrderIDs")); val awayOrder = ids(state.array("awayBattingOrderIDs"))
        val ownLineup = stored.array("lineup").map { it.jsonObject }.sortedBy { it.int("battingOrder") }.map { id(it.text("playerID")) }
        val expectedOrder = if (stored.bool("isHome")) homeOrder else awayOrder
        require(ownLineup.isEmpty() || ownLineup == expectedOrder) { "旧比赛阵容变化无法可靠还原" }
        fun positions(t: Team, key: String): Map<Int, String> {
            val fielders = ids(state.array(key)).toSet()
            return t.players.filter { it.id in fielders }.associate { it.position to it.id }.also { require(it.size == rules.fielders) }
        }
        val homePositions = positions(home, "homeFieldingPlayerIDs"); val awayPositions = positions(away, "awayFieldingPlayerIDs")
        val innings = if (rules.timed) 1 else rules.innings
        var game = Game(id(stored.text("id")), home, away, rules,
            homeOrder = homeOrder, awayOrder = awayOrder, homePositions = homePositions, awayPositions = awayPositions,
            homePitcherID = homePositions[1], awayPitcherID = awayPositions[1],
            homeRunsByInning = List(innings) { 0 }, awayRunsByInning = List(innings) { 0 },
            ourTeamID = our, createdAt = date(stored["createdAt"]) ?: error("创建时间缺失"),
            playedTimeZoneID = stored["playedTimeZoneID"]?.jsonPrimitive?.content ?: "Asia/Shanghai")
        var previousWasPitch = false
        events.forEachIndexed { index, raw ->
            val event = raw.jsonObject
            require(!event.bool("needsReview")) { "旧事件待复核，不能自动重放" }
            val notation = event["notation"]?.jsonPrimitive?.content ?: ""
            if (notation in listOf("BB", "K", "ꓘ") && previousWasPitch) { previousWasPitch = false; return@forEachIndexed }
            val category = event.text("category")
            if ((category == "clock" && notation == "PLAY" && index == 0) || category == "game" && index == 0) { previousWasPitch = false; return@forEachIndexed }
            val at = date(event["timestamp"]) ?: error("旧事件时间缺失")
            val action = when {
                category == "pitch" && notation in listOf("B", "C", "S", "F") -> Command("pitch", pitch = mapOf("B" to PitchAction.ball, "C" to PitchAction.calledStrike, "S" to PitchAction.swingingStrike, "F" to PitchAction.foul).getValue(notation), at = at)
                category == "battedBall" && event["resolvedOutcome"] != null -> {
                    val outcome = Outcome.entries.first { it.title == event.text("resolvedOutcome") }
                    val decisions = event.array("runnerMovements").map { element ->
                        val move = element.jsonObject
                        val origin = when (move.text("origin")) { "打者" -> 0; "一垒跑者" -> 1; "二垒跑者" -> 2; "三垒跑者" -> 3; else -> error("旧记录起始垒位无法确认") }
                        val destination = when (move.text("destination")) { "停留" -> 0; "出局" -> -1; "得分", "回本垒（不计分）" -> 4; "到一垒" -> 1; "到二垒" -> 2; "到三垒" -> 3; else -> error("旧记录跑者去向无法确认") }
                        Decision(id(move.text("playerID")), origin, destination)
                    }
                    val defense = when (notation) {
                        "6-3" -> Fielding(route = listOf(6, 3), assists = listOf(6), putouts = listOf(3))
                        "4-3" -> Fielding(route = listOf(4, 3), assists = listOf(4), putouts = listOf(3))
                        "5-3" -> Fielding(route = listOf(5, 3), assists = listOf(5), putouts = listOf(3))
                        "F7", "F8", "F9" -> notation.drop(1).toInt().let { Fielding(route = listOf(it), putouts = listOf(it)) }
                        else -> if (notation.startsWith("E")) notation.drop(1).toIntOrNull()?.let { Fielding(errorPosition = it, status = "pending") } else null
                    }
                    Command("play", outcome = outcome, decisions = decisions, fielding = defense, at = at)
                }
                notation == "HBP" -> Command("hitByPitch", at = at)
                notation == "IBB" -> Command("intentionalWalk", at = at)
                notation == "END" && state.bool("isFinal") -> Command("finish", text = "scorerDecision", at = date(state["endedAt"]) ?: at)
                else -> error("旧事件 $notation 缺少可靠操作资料")
            }
            game = MatchEngine.apply(game, action, id(event.text("id")))
            previousWasPitch = category == "pitch"
        }
        validateResult(game, state)
        return game
    }
}
