package cc.baseballmaster.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Read-only projection of supported source history (historical class name retained).
 * App imports keep the entire original envelope and never export this projection as iOS data.
 * Mutable continuation uses the separately gated raw-preserving writer.
 */
internal object AppleReplayPrototype {
    private fun JsonObject.optional(key: String) = this[key]?.takeUnless { it == JsonNull }
    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: error("缺少 $key")
    private fun JsonObject.int(key: String, fallback: Int? = null): Int = this[key]?.jsonPrimitive?.intOrNull ?: fallback ?: error("缺少 $key")
    private fun JsonObject.bool(key: String, fallback: Boolean = false) = this[key]?.jsonPrimitive?.booleanOrNull ?: fallback
    private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: error("缺少 $key")
    private fun id(s: String) = appleUUID(s)
    // Decimal wire dates must not round an Android millisecond down when converting
    // from seconds since 2001. Keep sub-millisecond source data opaque; views use floor.
    private fun date(value: JsonElement?) = value?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let {
        BigDecimal(it).add(BigDecimal("978307200")).movePointRight(3).setScale(0, RoundingMode.FLOOR).longValueExact()
    }
    private fun ids(a: JsonArray) = a.map { id(it.jsonPrimitive.content) }
    private fun team(o: JsonObject, opponent: Boolean): Team {
        val players = o.array("players").map { raw ->
            val p = raw.jsonObject
            val nums = p.array("numbers").map { it.jsonPrimitive.int }
            val labels = p.optional("jerseyNumbers")?.jsonArray?.map { it.jsonPrimitive.content } ?: nums.map(Int::toString)
            require(labels.isNotEmpty() && labels.map { it.toIntOrNull() } == nums)
            val cn = p.text("chineseName"); val en = p.text("englishName")
            Player(id(p.text("id")), cn.ifBlank { en }, labels.first(), en, p.int("primaryPosition"), labels, cn).also(Roster::validate)
        }
        return Team(id(o.text("id")), o.text("name"), players, isOpponent = opponent, city = o.text("city"), shortName = o.text("shortName"))
    }
    private fun rules(o: JsonObject): Rules {
        val mode = GameMode.valueOf(o.optional("mode")?.jsonPrimitive?.content ?: "standard")
        val fielders = o.int("fieldersCount")
        require(fielders == if (mode == GameMode.slowPitch) 10 else 9)
        return Rules(mode = mode, initialBalls = o.int("initialBalls", if (mode == GameMode.slowPitch) 1 else 0),
            initialStrikes = o.int("initialStrikes", if (mode == GameMode.slowPitch) 1 else 0),
            coachPitchLimit = o.int("coachPitchLimit", 6), oneExtraFoul = o.optional("twoStrikeFoulPolicy")?.jsonPrimitive?.content != "outImmediately",
            innings = o.int("scheduledInnings"), timed = (o.optional("competitionFormat")?.jsonPrimitive?.content ?: if (mode == GameMode.slowPitch) "timed" else "innings") == "timed",
            timeLimitMinutes = o.optional("timeLimitMinutes")?.jsonPrimitive?.intOrNull, inningsLimit = o.optional("inningsLimit")?.jsonPrimitive?.intOrNull,
            halfRunLimit = o.optional("halfInningRunLimit")?.jsonPrimitive?.intOrNull, pitchLimit = o.optional("pitchLimit")?.jsonPrimitive?.intOrNull,
            pitcherInningsLimit = o.optional("pitcherInningsLimit")?.jsonPrimitive?.intOrNull, recordingMethod = o.optional("recordingMethod")?.jsonPrimitive?.content ?: "traditional",
            designatedHitter = o.bool("usesDesignatedHitter"), twoWayPlayer = o.bool("allowsTwoWayPlayer"),
            timeWarningMinutes = o.optional("timeWarningMinutes")?.jsonPrimitive?.intOrNull, pitchWarningRemaining = o.optional("pitchWarningRemaining")?.jsonPrimitive?.intOrNull)
    }
    private fun decision(raw: JsonElement): Decision {
        val d = raw.jsonObject
        val origin = d["origin"]!!.jsonObject.let { o -> if ("batter" in o) 0 else o["base"]!!.jsonObject.int("_0") }
        val destination = d["destination"]!!.jsonObject.let { o -> when {
            "hold" in o -> 0; "out" in o -> -1; "score" in o -> 4; "base" in o -> o["base"]!!.jsonObject.int("_0"); else -> error("跑垒结果不受支持")
        } }
        return Decision(id(d.text("id")), origin, destination)
    }
    private fun fielding(f: JsonObject) = Fielding(route = f.array("route").map { it.jsonPrimitive.int }, errorPosition = f.optional("errorPosition")?.jsonPrimitive?.intOrNull,
        status = f.optional("status")?.jsonPrimitive?.content ?: error("缺少守备状态"))
    private fun participants(f: JsonObject): List<Participant>? = f.optional("participants")?.jsonArray?.map { raw ->
        val p = raw.jsonObject; Participant(p.int("position"), id(p.text("playerID")), p.text("name"), p.text("number"))
    }
    private val violationIDs=mapOf("投手犯规" to "balk","快速投球" to "quickPitch","违规投球" to "illegalPitch","球体处理违规" to "foreignSubstance","踏出打击区击球" to "batterOutOfBox","打者干扰" to "batterInterference","违规球棒" to "illegalBat","打击次序错误" to "battingOutOfTurn","捕手干扰打击" to "catcherInterference","本垒阻挡或冲撞" to "plateObstruction","阻挡跑垒" to "obstruction","假触杀" to "fakeTag","脱离装备触球" to "detachedEquipment","跑者干扰" to "runnerInterference","跑垒限制道干扰" to "runningLaneInterference","教练员干扰" to "coachInterference","超越前位跑者" to "passingRunner")
    private fun command(op: JsonObject,eventOwners: Map<String,String>): Command {
        val sourceObservation = op.optional("battedBall")?.jsonObject
        val detailRows = sourceObservation?.optional("runnerDetails")?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val details = detailRows.associateBy { id(it.text("playerID")) }
        require(details.size == detailRows.size) { "跑者详情编号重复" }
        val observation = sourceObservation?.let { o ->
            Observation(type = o.text("type"), precision = o.text("precision"), region = o.optional("region")?.jsonPrimitive?.content,
                x = o.optional("x")?.jsonPrimitive?.doubleOrNull, y = o.optional("y")?.jsonPrimitive?.doubleOrNull, fielding = o.optional("fielding")?.jsonObject?.let(::fielding))
        }
        val allSnapshots = (listOfNotNull(sourceObservation?.optional("fielding")?.jsonObject) + detailRows.mapNotNull { it.optional("fielding")?.jsonObject }).mapNotNull(::participants)
        val participantSnapshots = allSnapshots.firstOrNull()
        require(allSnapshots.all { it.sortedBy(Participant::position) == participantSnapshots?.sortedBy(Participant::position) }) { "不同守备路线的历史球员资料不一致" }
        val command = op["command"]!!.jsonObject; require(command.size == 1)
        val (kind, body) = command.entries.single(); val fields = body.jsonObject
        val at = date(op["timestamp"]) ?: error("操作时间缺失")
        return when (kind) {
            "pitch" -> Command("pitch", pitch = PitchAction.entries.first { it.title == fields.text("_0") }, at = at)
            "play" -> {
                val defense = fields.optional("_1")?.jsonObject?.let { f ->
                    Fielding(route = f.array("routePositions").map { it.jsonPrimitive.int }, assists = f.array("assistPositions").map { it.jsonPrimitive.int },
                        putouts = f.array("putoutPositions").map { it.jsonPrimitive.int }, errorPosition = f.optional("errorPosition")?.jsonPrimitive?.intOrNull,
                        status = if (f.array("routePositions").isEmpty()) "pending" else "recorded")
                }
                val decisions = fields.array("_2").map(::decision).map { move ->
                    val detail = details[move.playerID]
                    if (detail == null) move else move.copy(cause = detail.text("cause"), fielding = detail.optional("fielding")?.jsonObject?.let(::fielding))
                }
                require(details.keys.all { id -> decisions.any { it.playerID == id } }) { "跑者详情没有对应的跑垒决定" }
                Command("play", outcome = Outcome.entries.first { it.title == fields.text("_0") }, decisions = decisions,
                    observation = observation, fielding = defense, timingRunCounts = fields.optional("_3")?.jsonPrimitive?.booleanOrNull,
                    participantSnapshots = participantSnapshots, at = at)
            }
            "illegalPitch" -> {
                val play=fields.optional("_2")?.let {outcome ->
                    val body=buildJsonObject {
                        put("_0",outcome);fields.optional("_3")?.let {put("_1",it)}
                        put("_2",fields.optional("_4") ?: JsonArray(emptyList()));fields.optional("_5")?.let {put("_3",it)}
                    }
                    command(JsonObject(op+("command" to buildJsonObject {put("play",body)})),eventOwners)
                }
                (play ?: Command("illegal")).copy(kind="illegal",pitch=fields.optional("_0")?.jsonPrimitive?.content?.let {title ->PitchAction.entries.single {it.title==title}},
                    wasThrown=fields.bool("_1"),penaltyApplied=fields.bool("_6"),at=at)
            }
            "runner" -> {
                require(observation == null)
                val names = mapOf("偷垒成功" to "stolenBase", "双重盗垒" to "doubleSteal", "延迟盗垒" to "delayedSteal", "守备转传时额外进垒" to "relayAdvance", "守备失误造成额外进垒" to "defensiveErrorAdvance", "跑垒出局" to "caughtStealing", "牵制出局" to "pickoff", "触杀出局" to "tagOut", "封杀出局" to "forceOut", "申诉出局" to "appealOut", "离垒过早" to "leftEarly", "漏踩垒位" to "missedBase", "跑出限制道" to "outOfBasePath", "超越前位跑者" to "passedRunner", "跑者妨碍守备" to "runnerInterference", "跑垒员被击球击中" to "runnerHitByBall", "教练员帮助跑者" to "coachAssistance", "球太偏，跑者进垒" to "wildPitch", "捕手漏球，跑者进垒" to "passedBall", "球漏过去，分不清原因" to "uncertainLooseBall", "投手犯规，跑者进垒" to "balk")
                Command("runner", runnerKind = names[fields.text("_0")] ?: error("跑垒事件不受支持"), decisions = fields.array("_1").map(::decision), timingRunCounts = fields.optional("_2")?.jsonPrimitive?.booleanOrNull, at = at)
            }
            "hitByPitch", "intentionalWalk", "foulBunt", "endHalf", "skipTB" -> {
                require(observation == null && fields.isEmpty())
                Command(kind, at = at)
            }
            "pitcher", "batter" -> Command(kind, playerID = id(fields.text("_0")), at = at)
            "pinchRunner" -> Command(kind, position = fields.int("_0"), playerID = id(fields.text("_1")), at = at)
            "fielder" -> Command(kind, playerID = id(fields.text("_0")), replacementID = id(fields.text("_1")), at = at)
            "position" -> Command(kind, playerID = id(fields.text("_0")), position = fields.int("_1"), at = at)
            "reopen" -> Command("reopen", flag = fields.optional("_0")?.jsonPrimitive?.booleanOrNull ?: false, at = at)
            "finish" -> {
                val reasons = mapOf("规定局数完成" to "regulation", "再见分结束" to "walkOff", "时间限制结束" to "timeLimit", "提前结束规则" to "mercyRule", "弃权结束" to "forfeit", "天气或场地原因" to "weather", "比赛中断" to "suspended", "记录员结束记录" to "scorerDecision")
                val reason = reasons[fields.text("_0")] ?: error("结束原因不受支持")
                Command(if (reason == "suspended") "suspend" else "finish", text = reason, at = date(fields["_1"]) ?: at)
            }
            "swapOpeningSides" -> Command("swap",at=at)
            "decision" -> Command("decision",flag=fields.bool("_0"),at=at)
            "extraInning" -> Command("extraInning",flag=fields.bool("_0"),runnerBases=fields.array("_1").map {it.jsonPrimitive.int},at=at)
            "enableTB" -> Command("enableTB",runnerBases=fields.array("_0").map {it.jsonPrimitive.int},at=at)
            "placeTB" -> Command("placeTB",playerID=id(fields.text("_0")),position=fields.optional("_1")?.jsonPrimitive?.int,at=at)
            "note" -> Command("note",text=fields.text("_0"),at=at)
            "clock" -> Command("clock",text=fields.text("_0").let {if(it=="start") "resume" else it},at=date(fields["_1"]) ?: at)
            "droppedThirdStrike" -> Command("droppedThirdStrike",decisions=fields.array("_0").map(::decision),at=at)
            "doubleSwitch" -> Command("doubleSwitch",playerID=id(fields.text("_0")),replacementID=id(fields.text("_1")),position=fields.int("_2"),secondPlayerID=id(fields.text("_3")),secondReplacementID=id(fields.text("_4")),secondPosition=fields.int("_5"),at=at)
            "lineup" -> {
                val value=fields.getValue("_0").jsonObject
                val positions=value.array("players").associate {id(it.jsonObject.text("id")) to it.jsonObject.int("primaryPosition")}
                val fielders=ids(value.array("fieldingIDs"))
                val map=fielders.associateBy {positions.getValue(it)}
                require(map.size==fielders.size) {"阵容历史守位重复"}
                Command("lineup",homeSide=value.bool("isHome"),order=ids(value.array("battingIDs")),positions=map,
                    playerID=value.optional("anchorID")?.jsonPrimitive?.content?.let(::id),designatedHitterID=value.optional("dhID")?.jsonPrimitive?.content?.let(::id),at=at)
            }
            "situation" -> {
                val value=fields.getValue("_0").jsonObject;val bases=MutableList<String?>(3) {null}
                val pairs=value.array("runners");require(pairs.size%2==0)
                pairs.chunked(2).forEach {(b,p) ->val base=b.jsonPrimitive.int;require(base in 1..3 && bases[base-1]==null);bases[base-1]=id(p.jsonObject.text("id"))}
                Command("situation",situation=Situation(value.int("inning"),value.bool("isTop"),value.int("balls"),value.int("strikes"),value.int("outs"),value.int("awayScore"),value.int("homeScore"),bases,value.int("batterIndex"),value.optional("pitcherID")?.jsonPrimitive?.content?.let(::id)),at=at)
            }
            "review" -> {
                val value=fields.getValue("_0").jsonObject
                val owner=eventOwners[id(value.text("id"))] ?: error("复核事件没有原始操作归属，不能猜测编号")
                Command("review",eventID=owner,text=value.text("title"),reviewNote=value.optional("note")?.jsonPrimitive?.content,
                    outcome=value.optional("resolvedOutcome")?.jsonPrimitive?.content?.let {title ->Outcome.entries.single {it.title==title}},
                    playerID=value.optional("secondaryPlayerID")?.jsonPrimitive?.content?.let(::id),at=at)
            }
            "violation" -> {
                Command("violation",runnerKind=violationIDs.getValue(fields.text("_0")),decisions=fields.array("_1").map(::decision),at=at)
            }
            "adjudication" -> {
                val value=fields.getValue("_1").jsonObject
                val statuses=mapOf("活球" to "live","死球" to "dead","延迟死球" to "delayedDead","按裁判宣判" to "umpireDecision")
                val previous=mapOf("不涉及上一比赛结果" to "notApplicable","保留上一比赛结果" to "keep","取消上一比赛结果" to "cancel")
                val appearances=mapOf("继续当前打席" to "continueAtBat","打者出局" to "batterOut","打者上一垒" to "batterFirst")
                val credits=mapOf("不另记打击结果" to "none","一垒安打" to "single","二垒安打" to "double","三垒安打" to "triple","本垒打" to "homeRun","守备失误上垒" to "reachedOnError","野手选择" to "fieldersChoice","牺牲打" to "sacrifice")
                Command("adjudication",runnerKind=violationIDs.getValue(fields.text("_0")),decisions=value.array("runnerDecisions").map(::decision),at=at,
                    adjudication=Ruling(previousPlay=previous.getValue(value.text("previousPlayDisposition")),plateAppearance=appearances.getValue(value.text("plateAppearanceDisposition")),
                        countsAsAtBat=value.bool("countsAsAtBat"),isFinal=value.bool("isFinalRuling"),battingCredit=credits.getValue(value.text("battingCredit")),
                        runsBattedIn=value.int("runsBattedIn"),earnedRuns=value.int("earnedRuns"),errorPlayerID=value.optional("fieldingErrorPlayerID")?.jsonPrimitive?.content?.let(::id),ballStatus=statuses.getValue(value.text("ballStatus"))))
            }
            else -> error("暂不支持历史操作 $kind")
        }
    }
    /** Read supported journals from a validated checkpoint, including saved
     * legacy prefixes. No persisted source mutation or edit permission occurs.
     */
    fun replay(stored: JsonObject): Game {
        require(stored.optional("sharedTeamID") == null && stored.optional("sharedGameID") == null && !stored.bool("isSpectator")) { "暂不支持共享或观赛比赛" }
        val state = stored["state"]!!.jsonObject
        val journal = state.optional("historyJournal")?.jsonObject ?: return replayLegacy(stored, state)
        fun runJournal(source: JsonObject): Game {
        require(source.int("version") == 1)
        val checkpoint = GameJson.parseToJsonElement(Base64.getDecoder().decode(source.text("checkpoint")).toString(Charsets.UTF_8)).jsonObject
        // StoredGame metadata belongs to the file, not to GameState's history
        // checkpoint. In particular, current updatedAt/revision must never
        // leak into every old audit checkpoint when a new action is appended.
        var game=AppleStateProjection.read(stored,checkpoint).view().copy(
            incompletePrefix=source.bool("legacyPrefix"),revision=0,updatedAt=0,
            scheduledAt=null,playedAt=null,startedAt=null,dateRevisions=emptyList(),
            historySourceID=null,isScheduled=false,openingPitchRecorded=false)
        val checkpointData = MatchEngine.checkpoint(game)
        val records = mutableListOf<RecordedCommand>()
        val eventOwners=mutableMapOf<String,String>()
        source.array("operations").forEach {raw ->
            val operation=raw.jsonObject;val owner=id(operation.text("id"))
            operation.array("eventIDs").forEach {rawID ->
                val event=id(rawID.jsonPrimitive.content)
                require(eventOwners.put(event,owner)==null) {"不同操作的原始事件编号重复"}
            }
        }
        source.array("operations").forEach { raw ->
            val op = raw.jsonObject
            val errors = op.array("errors").map { element -> element.jsonObject.let { ErrorCredit(id(it.text("playerID")), it.int("count", 1), it["note"]?.jsonPrimitive?.content ?: "漏记守备失误") } }
            val ruling = op.optional("statisticsRuling")?.jsonObject?.let { r ->
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
            records += RecordedCommand(id(op.text("id")), command(op,eventOwners), order[batterIndex % order.size], runners,
                acceptContext = op.bool("acceptsNewContext"), context = context,
                inserted = op.bool("inserted"), edited = op.bool("edited"), additionalErrors = errors, statisticsRuling = ruling)
        }
        if (records.isEmpty()) game.checkpoint = checkpointData
        else {
            // Parse the complete source journal once, then replay once. Replaying every
            // prefix here multiplies the reducer's own state-copy cost on long games.
            val preview = History.run(game.copy(checkpoint = checkpointData), records, records.size, strict = false)
            check(preview.canSave) { preview.conflict ?: "历史重放失败" }
            game = preview.game!!
            game.journal = records.toList(); game.cursor = records.size
            // Historical files may omit StoredGame.revision. Preserve the old per-operation
            // fallback rather than History.run's single-draft revision increment.
            game.revision = records.size.toLong()
        }
        return game
        }
        var game = runJournal(journal)
        val revisions = state.optional("correctionRevisions")?.jsonArray.orEmpty().map { raw ->
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
        game.revision = stored.optional("revision")?.jsonPrimitive?.longOrNull ?: game.revision
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
        // Original actions may materialize an all-zero dictionary row while
        // the other reducer leaves it absent. These have identical official
        // statistics; the original dictionary is still retained in the file.
        require(game.batting.filterValues {it!=BattingLine()} == batting.filterValues {it!=BattingLine()}) { "重放打击统计与原始资料不一致" }
        require(game.pitching.filterValues {it!=PitchingLine()} == pitching.filterValues {it!=PitchingLine()}) { "重放投球统计与原始资料不一致" }
        require(game.fielding.filterValues {it!=FieldingLine()} == fielding.filterValues {it!=FieldingLine()}) { "重放守备统计与原始资料不一致" }
        val baseValues = state.array("baseRunners"); require(baseValues.size % 2 == 0)
        val bases = MutableList<String?>(3) { null }
        baseValues.chunked(2).forEach { pair ->
            val base = pair[0].jsonPrimitive.int; require(base in 1..3 && bases[base - 1] == null)
            bases[base - 1] = id(pair[1].jsonObject.text("id"))
        }
        require(game.bases == bases) { "重放垒况与原始资料不一致" }
        require(game.homeOrder == ids(state.array("homeBattingOrderIDs")) && game.awayOrder == ids(state.array("awayBattingOrderIDs"))) { "重放打序不一致" }
        require(game.homeBatterIndex == state.int("homeBatterIndex") && game.awayBatterIndex == state.int("awayBatterIndex")) { "重放棒次不一致" }
        state.optional("activeHomePitcherID")?.jsonPrimitive?.content?.let { require(game.homePitcherID == id(it)) { "主队投手不一致" } }
        state.optional("activeAwayPitcherID")?.jsonPrimitive?.content?.let { require(game.awayPitcherID == id(it)) { "客队投手不一致" } }
        for ((side, roster) in listOf("home" to state.getValue("homeTeam").jsonObject, "away" to state.getValue("awayTeam").jsonObject)) {
            val fielders = state.optional("${side}FieldingPlayerIDs")?.jsonArray?.let(::ids)
            if (fielders != null) {
                val byID = roster.array("players").associate { id(it.jsonObject.text("id")) to it.jsonObject.int("primaryPosition") }
                val expected = fielders.associateBy { byID.getValue(it) }
                require(expected.size == fielders.size && expected == (if (side == "home") game.homePositions else game.awayPositions)) { "重放守位不一致" }
            }
            state.optional("${side}ExitedPlayerIDs")?.jsonArray?.let { raw ->
                val teamIDs = roster.array("players").map { id(it.jsonObject.text("id")) }.toSet()
                require(game.exitedPlayers.intersect(teamIDs) == ids(raw).toSet()) { "已退场球员不一致" }
            }
        }
        state.optional("walkResponsiblePitcherID")?.jsonPrimitive?.content?.let { require(game.walkResponsiblePitcherID == id(it)) { "保送责任投手不一致" } }
        state.optional("strikeoutResponsibleBatterID")?.jsonPrimitive?.content?.let { require(game.strikeoutResponsibleBatterID == id(it)) { "三振责任打者不一致" } }
        state.optional("runnerPitcherIDs")?.jsonArray?.let { raw ->
            require(raw.size % 2 == 0)
            val owners = raw.chunked(2).associate { id(it[0].jsonPrimitive.content) to id(it[1].jsonPrimitive.content) }
            require(game.runnerPitcherIDs == owners) { "跑者投手责任不一致" }
        }
        state.optional("unearnedRunnerIDs")?.jsonArray?.let { require(game.unearnedRunnerIDs == ids(it).toSet()) { "非自责跑者不一致" } }
        state.optional("automaticRunnerIDs")?.jsonArray?.let { require(game.automaticRunnerIDs == ids(it).toSet()) { "自动跑者不一致" } }
    }

    private fun replayLegacy(stored: JsonObject, state: JsonObject): Game {
        require(state.optional("correctionRevisions") == null) { "旧记录含不支持的纠错资料" }
        val events = state.array("scoringEvents"); require(events.isNotEmpty()) { "旧比赛没有足够事件，无法可靠重放" }
        val our = stored.optional("ourTeamID")?.jsonPrimitive?.content?.let(::id) ?: error("缺少本队")
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
            playedTimeZoneID = stored.optional("playedTimeZoneID")?.jsonPrimitive?.content ?: "Asia/Shanghai")
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
