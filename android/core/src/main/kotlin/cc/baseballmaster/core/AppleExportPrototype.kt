package cc.baseballmaster.core

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Research-only subset for byte-level iOS decoder tests; never used by the app's export UI. */
internal object AppleExportPrototype {
    private val json = GameJson
    private fun uid(id: String) = appleUUID(id).uppercase()
    private fun generated(id: String) = UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString().uppercase()
    private fun date(ms: Long) = ms / 1000.0 - 978307200.0
    private fun JsonObjectBuilder.dateField(name: String, value: Long?) { if (value != null) put(name, date(value)) }
    private fun JsonObjectBuilder.idField(name: String, value: String?) { if (value != null) put(name, uid(value)) }
    private fun idArray(values: Iterable<String>) = buildJsonArray { values.forEach { add(uid(it)) } }
    private fun player(p: Player) = buildJsonObject {
        Roster.validate(p)
        put("id", uid(p.id)); put("chineseName", p.chineseName ?: p.name); put("englishName", p.englishName)
        put("numbers", buildJsonArray { p.jerseyNumbers.forEach { add(it.toInt()) } })
        put("jerseyNumbers", buildJsonArray { p.jerseyNumbers.forEach { add(it) } })
        put("primaryPosition", p.position.also { require(it in 1..10) })
    }
    private fun team(t: Team, positions: Map<Int, String>? = null) = buildJsonObject {
        require(t.source == "local" && !t.archived && t.players.none { it.archived })
        put("id", uid(t.id)); put("name", t.name); put("shortName", t.shortName); put("city", t.city)
        put("players", buildJsonArray { t.players.forEach { p -> add(player(p.copy(position = positions?.entries?.firstOrNull { it.value == p.id }?.key ?: p.position))) } })
    }
    private fun rules(r: Rules) = buildJsonObject {
        put("scheduledInnings", r.innings); put("fieldersCount", r.fielders); put("mode", r.mode.name)
        put("initialBalls", r.initialBalls); put("initialStrikes", r.initialStrikes); put("coachPitchLimit", r.coachPitchLimit)
        put("twoStrikeFoulPolicy", if (r.oneExtraFoul) "oneExtraFoul" else "outImmediately")
        put("competitionFormat", if (r.timed) "timed" else "innings")
        r.timeLimitMinutes?.let { put("timeLimitMinutes", it) }; r.inningsLimit?.let { put("inningsLimit", it) }
        r.timeWarningMinutes?.let { put("timeWarningMinutes", it) }; r.pitchLimit?.let { put("pitchLimit", it) }
        r.pitchWarningRemaining?.let { put("pitchWarningRemaining", it) }; r.pitcherInningsLimit?.let { put("pitcherInningsLimit", it) }
        r.halfRunLimit?.let { put("halfInningRunLimit", it) }; put("usesDesignatedHitter", r.designatedHitter)
        put("allowsTwoWayPlayer", r.twoWayPlayer); put("recordingMethod", r.recordingMethod)
    }
    private fun pairUUIDMap(map: Map<String, String>) = buildJsonArray {
        map.toSortedMap().forEach { (key, value) -> add(uid(key)); add(uid(value)) }
    }
    private fun positions(g: Game, home: Boolean) = (if (home) g.homePositions else g.awayPositions) ?: error("缺少守备阵容")
    private fun snapshot(g: Game) = buildJsonObject {
        put("inning", g.inning); put("isTop", g.isTop); put("balls", g.balls); put("strikes", g.strikes); put("outs", g.outs)
        put("homeRunsByInning", json.encodeToJsonElement(g.homeRunsByInning)); put("awayRunsByInning", json.encodeToJsonElement(g.awayRunsByInning))
        put("homeBatterIndex", g.homeBatterIndex); put("awayBatterIndex", g.awayBatterIndex)
        put("baseRunners", buildJsonArray { g.bases.forEachIndexed { i, id -> if (id != null) add(buildJsonObject { put("base", i + 1); put("playerID", uid(id)) }) } })
        idField("activeHomePitcherID", g.homePitcherID); idField("activeAwayPitcherID", g.awayPitcherID)
        put("homeBattingOrderIDs", idArray(g.homeOrder ?: error("缺少主队打序"))); put("awayBattingOrderIDs", idArray(g.awayOrder ?: error("缺少客队打序")))
        put("runnerPitcherIDs", pairUUIDMap(g.runnerPitcherIDs)); put("unearnedRunnerIDs", idArray(g.unearnedRunnerIDs))
        put("extraFoulUsed", g.extraFoulUsed)
    }
    private fun state(g: Game, logs: JsonArray = JsonArray(emptyList()), events: JsonArray = JsonArray(emptyList()), appearances: JsonArray? = null, currentID: String? = null, history: JsonObject? = null): JsonObject = buildJsonObject {
        put("homeTeam", team(g.home, g.homePositions)); put("awayTeam", team(g.away, g.awayPositions)); put("scheduledInnings", g.rules.innings); put("rulesSnapshot", rules(g.rules))
        put("inning", g.inning); put("isTop", g.isTop); put("balls", g.balls); put("strikes", g.strikes); put("outs", g.outs)
        put("homeRunsByInning", json.encodeToJsonElement(g.homeRunsByInning)); put("awayRunsByInning", json.encodeToJsonElement(g.awayRunsByInning))
        put("homeHits", g.homeHits); put("awayHits", g.awayHits); put("homeErrors", g.homeErrors); put("awayErrors", g.awayErrors)
        put("homeBatterIndex", g.homeBatterIndex); put("awayBatterIndex", g.awayBatterIndex)
        val allPlayers = (g.home.players + g.away.players).associateBy { it.id }
        put("baseRunners", buildJsonArray { g.bases.forEachIndexed { i, id -> if (id != null) { add(i + 1); add(player(allPlayers[id] ?: error("跑者缺失"))) } } })
        put("batting", buildJsonArray { g.batting.toSortedMap().forEach { (id, line) -> add(uid(id)); add(json.encodeToJsonElement(line)) } })
        put("pitching", buildJsonArray { g.pitching.toSortedMap().forEach { (id, line) -> add(uid(id)); add(json.encodeToJsonElement(line)) } })
        put("fielding", buildJsonArray { g.fielding.toSortedMap().forEach { (id, line) -> add(uid(id)); add(json.encodeToJsonElement(line)) } })
        put("playLog", logs); put("plateAppearancePitchCount", g.appearancePitches); put("isFinal", g.isFinal); idField("activeHomePitcherID", g.homePitcherID); idField("activeAwayPitcherID", g.awayPitcherID)
        put("homeBattingOrderIDs", idArray(g.homeOrder ?: error("缺少主队打序"))); put("awayBattingOrderIDs", idArray(g.awayOrder ?: error("缺少客队打序")))
        put("homeFieldingPlayerIDs", idArray(positions(g, true).toSortedMap().values)); put("awayFieldingPlayerIDs", idArray(positions(g, false).toSortedMap().values))
        idField("homeDesignatedHitterID", g.homeDesignatedHitterID); idField("awayDesignatedHitterID", g.awayDesignatedHitterID)
        put("homeExitedPlayerIDs", idArray(g.exitedPlayers.filter { id -> g.home.players.any { it.id == id } })); put("awayExitedPlayerIDs", idArray(g.exitedPlayers.filter { id -> g.away.players.any { it.id == id } }))
        put("scoringEvents", events); put("runnerPitcherIDs", pairUUIDMap(g.runnerPitcherIDs)); put("unearnedRunnerIDs", idArray(g.unearnedRunnerIDs)); put("automaticRunnerIDs", idArray(g.automaticRunnerIDs))
        put("clockElapsedSeconds", g.clockElapsedMs / 1000.0); dateField("clockRunningSince", g.clockRunningSince); put("clockDisplayMode", g.clockDisplayMode)
        dateField("endedAt", g.endedAt); if (g.isFinal) put("canResumePrecisely", true); g.endReason?.let { put("endReason", endReasons[it] ?: error("结束原因不受支持")) }; put("extraFoulUsed", g.extraFoulUsed); put("pitchingOutsAccountingVersion", 2); idField("walkResponsiblePitcherID", g.walkResponsiblePitcherID); idField("strikeoutResponsibleBatterID", g.strikeoutResponsibleBatterID)
        if (g.statisticsIncomplete) put("statisticsIncomplete", true)
        appearances?.let { put("plateAppearances", it) }; currentID?.let { put("currentPlateAppearanceID", it) }; history?.let { put("historyJournal", it) }
    }
    private fun movement(d: Decision) = buildJsonObject {
        put("playerID", uid(d.playerID))
        put("origin", when (d.origin) { 0 -> "打者"; 1 -> "一垒跑者"; 2 -> "二垒跑者"; 3 -> "三垒跑者"; else -> error("起始垒位无效") })
        put("destination", when (d.destination) { -1 -> "出局"; 0 -> "停留"; 1 -> "到一垒"; 2 -> "到二垒"; 3 -> "到三垒"; 4 -> "得分"; else -> error("目标垒位无效") })
    }
    private fun context(g: Game) = buildJsonObject {
        put("inning", g.inning); put("isTop", g.isTop); put("batterIndex", g.batterIndex)
        put("homeOrder", idArray(g.homeOrder ?: error("缺少主队打序"))); put("awayOrder", idArray(g.awayOrder ?: error("缺少客队打序")))
        put("homeFielders", idArray(positions(g, true).toSortedMap().values)); put("awayFielders", idArray(positions(g, false).toSortedMap().values))
        put("positions", buildJsonArray { (g.home.players + g.away.players).sortedBy { it.id }.forEach { p -> add(uid(p.id)); add((if (g.home.players.any { it.id == p.id }) g.homePositions else g.awayPositions)?.entries?.firstOrNull { it.value == p.id }?.key ?: p.position) } })
        put("runners", buildJsonArray { g.bases.forEachIndexed { i, id -> if (id != null) { add(i + 1); add(uid(id)) } } })
    }
    private fun runnerDecision(d: Decision, players: Map<String, Player>) = buildJsonObject {
        put("id", uid(d.playerID)); put("player", player(players[d.playerID] ?: error("跑者不存在")))
        put("origin", if (d.origin == 0) buildJsonObject { put("batter", buildJsonObject {}) } else buildJsonObject { put("base", buildJsonObject { put("_0", d.origin) }) })
        put("destination", when (d.destination) {
            -1 -> buildJsonObject { put("out", buildJsonObject {}) }; 0 -> buildJsonObject { put("hold", buildJsonObject {}) }
            4 -> buildJsonObject { put("score", buildJsonObject {}) }; in 1..3 -> buildJsonObject { put("base", buildJsonObject { put("_0", d.destination) }) }; else -> error("目标垒位无效")
        })
    }
    private val runnerNames = mapOf("stolenBase" to "偷垒成功", "doubleSteal" to "双重盗垒", "delayedSteal" to "延迟盗垒", "relayAdvance" to "守备转传时额外进垒", "defensiveErrorAdvance" to "守备失误造成额外进垒", "caughtStealing" to "跑垒出局", "pickoff" to "牵制出局", "tagOut" to "触杀出局", "forceOut" to "封杀出局", "appealOut" to "申诉出局", "leftEarly" to "离垒过早", "missedBase" to "漏踩垒位", "outOfBasePath" to "跑出限制道", "passedRunner" to "超越前位跑者", "runnerInterference" to "跑者妨碍守备", "runnerHitByBall" to "跑垒员被击球击中", "coachAssistance" to "教练员帮助跑者", "wildPitch" to "球太偏，跑者进垒", "passedBall" to "捕手漏球，跑者进垒", "uncertainLooseBall" to "球漏过去，分不清原因", "balk" to "投手犯规，跑者进垒")
    private val endReasons = mapOf("regulation" to "规定局数完成", "walkOff" to "再见分结束", "timeLimit" to "时间限制结束", "mercyRule" to "提前结束规则", "forfeit" to "弃权结束", "weather" to "天气或场地原因", "suspended" to "比赛中断", "scorerDecision" to "记录员结束记录")
    private fun appleFielding(fielding: Fielding, participants: List<Participant>) = buildJsonObject {
        put("status", fielding.status); put("route", json.encodeToJsonElement(fielding.route)); fielding.errorPosition?.let { put("errorPosition", it) }
        put("participants", buildJsonArray { participants.forEach { p -> add(buildJsonObject { put("position", p.position); put("playerID", uid(p.playerID)); put("name", p.name); put("number", p.number) }) } })
    }
    private fun observation(e: PitchEntry): JsonObject? = e.observation?.let { o -> buildJsonObject {
        put("type", o.type); put("precision", o.precision); o.region?.let { put("region", it) }; o.x?.let { put("x", it) }; o.y?.let { put("y", it) }
        o.fielding?.let { put("fielding", appleFielding(it, e.participants)) }
        val details = e.decisions.filter { it.cause != "hit" || it.fielding != null || it.errorPosition != null || it.route.isNotEmpty() }
        if (details.isNotEmpty()) put("runnerDetails", buildJsonArray { details.forEach { d -> add(buildJsonObject {
            put("playerID", uid(d.playerID)); put("cause", d.cause)
            val fielding = d.fielding ?: if (d.route.isNotEmpty() || d.errorPosition != null) Fielding(route = d.route, errorPosition = d.errorPosition, status = if (d.route.isNotEmpty()) "recorded" else "pending") else null
            fielding?.let { put("fielding", appleFielding(it, e.participants)) }
        }) } })
    } }
    private fun defensivePlay(e: PitchEntry, c: Command): JsonObject? {
        val f = c.fielding ?: c.observation?.fielding ?: return null
        val outs = e.decisions.count { it.destination == -1 }
        val caught = c.outcome in listOf(Outcome.flyOut, Outcome.lineOut, Outcome.foulFlyOut, Outcome.sacrificeFly, Outcome.infieldFly)
        val putouts = if (f.putouts.isNotEmpty()) f.putouts else if (f.status == "recorded" && outs > 0) if (caught) f.route.take(1) else f.route.takeLast(outs) else emptyList()
        val assists = if (f.assists.isNotEmpty()) f.assists else if (f.status == "recorded" && outs > 0 && !caught) f.route.dropLast(1) else emptyList()
        return buildJsonObject {
            put("id", "ANDROID"); put("title", "本球守备"); put("notation", f.route.joinToString("-").ifEmpty { f.errorPosition?.let { "E$it" } ?: "待补" })
            put("routePositions", json.encodeToJsonElement(f.route)); put("assistPositions", json.encodeToJsonElement(assists)); put("putoutPositions", json.encodeToJsonElement(putouts)); f.errorPosition?.let { put("errorPosition", it) }
        }
    }
    private fun historyCommand(record: RecordedCommand, event: PitchEntry, before: Game): JsonObject {
        val c = record.command
        require(c.observation != null || event.decisions.all { it.cause == "hit" && it.fielding == null && it.route.isEmpty() && it.errorPosition == null }) { "跑者守备详情缺少击球观察" }
        val people = (before.home.players + before.away.players).associateBy { it.id }
        return when (c.kind) {
            "pitch" -> buildJsonObject { put("pitch", buildJsonObject { put("_0", c.pitch?.title ?: error("缺少投球")) }) }
            "play" -> buildJsonObject { put("play", buildJsonObject { put("_0", c.outcome?.title ?: error("缺少结果")); defensivePlay(event, c)?.let { put("_1", it) }; put("_2", buildJsonArray { event.decisions.forEach { add(runnerDecision(it, people)) } }); c.timingRunCounts?.let { put("_3", it) } }) }
            "runner" -> buildJsonObject { put("runner", buildJsonObject { put("_0", runnerNames[c.runnerKind] ?: error("跑垒事件不受支持")); put("_1", buildJsonArray { event.decisions.forEach { add(runnerDecision(it, people)) } }); c.timingRunCounts?.let { put("_2", it) } }) }
            "hitByPitch", "intentionalWalk", "foulBunt", "endHalf", "skipTB" -> buildJsonObject { put(c.kind, buildJsonObject {}) }
            "pitcher", "batter" -> buildJsonObject { put(c.kind, buildJsonObject { put("_0", uid(c.playerID ?: error("缺少换人球员"))) }) }
            "pinchRunner" -> buildJsonObject { put(c.kind, buildJsonObject { put("_0", c.position ?: error("缺少代跑垒位")); put("_1", uid(c.playerID ?: error("缺少代跑球员"))) }) }
            "fielder" -> buildJsonObject { put(c.kind, buildJsonObject { put("_0", uid(c.playerID ?: error("缺少原守备球员"))); put("_1", uid(c.replacementID ?: error("缺少新守备球员"))) }) }
            "position" -> buildJsonObject { put(c.kind, buildJsonObject { put("_0", uid(c.playerID ?: error("缺少守备球员"))); put("_1", c.position ?: error("缺少守位")) }) }
            "reopen" -> buildJsonObject { put("reopen", buildJsonObject { put("_0", c.flag) }) }
            "finish", "suspend" -> buildJsonObject { put("finish", buildJsonObject { put("_0", endReasons[c.text] ?: error("结束原因不受支持")); put("_1", date(c.at)) }) }
            else -> error("研究编码暂不支持操作 ${c.kind}")
        }
    }
    fun encode(g: Game): ByteArray {
        require(!g.practice && !g.isScheduled && !g.incompletePrefix && g.dateRevisions.isEmpty()) { "研究编码不支持预告、日期更正、练习或不完整历史前缀" }
        require(g.pendingDecision == null && !g.halfEndedAwaitingDecision && g.historySourceID == null && (!g.isFinal || g.endReason in endReasons)) { "研究编码不支持待确认或历史关联" }
        require(g.cursor == g.journal.size && g.entries.size == g.cursor && g.ourTeamID in listOf(g.home.id, g.away.id) && g.createdAt > 0)
        require(g.journal.map { it.id }.distinct().size == g.journal.size && g.journal.all { it.command.at > 0 })
        require(g.historyRevisions.size == g.correctionRevisions.size) { "更正审计不完整" }
        val cp = GameJson.decodeFromString<Game>(g.checkpoint ?: error("缺少原始检查点"))
        require(cp.entries.isEmpty() && cp.journal.isEmpty())
        val states = (0..g.cursor).map { index -> History.run(g, g.journal, index, strict = false).also { require(it.canSave) }.game!! }
        val replayed = states.last()
        require(replayed.inning == g.inning && replayed.isTop == g.isTop && replayed.balls == g.balls && replayed.strikes == g.strikes && replayed.outs == g.outs && replayed.bases == g.bases && replayed.homeRunsByInning == g.homeRunsByInning && replayed.awayRunsByInning == g.awayRunsByInning && replayed.batting == g.batting && replayed.pitching == g.pitching && replayed.fielding == g.fielding && replayed.runnerPitcherIDs == g.runnerPitcherIDs) { "历史重放与当前比赛不一致" }
        val groups = AppearanceProjection.build(g)
        val groupID = groups.associate { it.id to generated("${g.id}/appearance/${it.id}") }
        val eventGroup = groups.flatMap { appearance -> appearance.events.map { it.id to groupID.getValue(appearance.id) } }.toMap()
        val appearances = buildJsonArray { groups.forEach { a -> add(buildJsonObject {
            put("id", groupID.getValue(a.id)); put("inning", a.inning); put("isTop", a.isTop); put("batterIDs", idArray(a.batterIDs)); put("completed", a.complete); put("interrupted", a.interrupted)
        }) } }
        val logs = buildJsonArray { g.entries.forEach { e -> add(buildJsonObject {
            put("id", generated("${g.id}/log/${e.id}")); put("inning", e.inning); put("isTop", e.isTop); put("text", e.text); put("timestamp", date(e.at)); put("isIncomplete", e.incomplete)
        }) } }
        val events = buildJsonArray { g.entries.forEachIndexed { i, e ->
            require(e.id == g.journal[i].id && e.kind in listOf("pitch", "play", "runner", "hitByPitch", "intentionalWalk", "foulBunt", "endHalf", "skipTB", "finish", "suspend", "reopen", "pitcher", "batter", "pinchRunner", "fielder", "position"))
            add(buildJsonObject {
                put("id", uid(e.id)); put("logEntryID", generated("${g.id}/log/${e.id}")); put("inning", e.inning); put("isTop", e.isTop); put("timestamp", date(e.at))
                put("category", when (e.kind) { "pitch" -> "pitch"; "play" -> "battedBall"; "runner" -> "runner"; "hitByPitch", "intentionalWalk", "foulBunt" -> "pitch"; "pitcher", "batter", "pinchRunner", "fielder", "position" -> "substitution"; else -> "game" }); put("title", e.text); put("notation", e.notation)
                idField("primaryPlayerID", if (e.kind == "pitch") e.pitcherID else e.batterID); idField("secondaryPlayerID", if (e.kind == "pitch") e.batterID else e.pitcherID)
                idField("plateAppearanceBatterID", e.batterID); put("plateAppearanceID", eventGroup.getValue(e.id))
                put("runnerMovements", buildJsonArray { e.decisions.forEach { add(movement(it)) } }); put("needsReview", e.incomplete); e.reviewNote?.let { put("reviewNote", it) }; dateField("reviewedAt", e.reviewedAt)
                e.outcome?.let { put("resolvedOutcome", it.title) }; observation(e)?.let { put("battedBall", it) }; put("beforeSituation", snapshot(states[i])); put("afterSituation", snapshot(states[i + 1]))
            })
        } }
        val operations = buildJsonArray { g.journal.forEachIndexed { i, op -> add(buildJsonObject {
            put("id", uid(op.id)); put("command", historyCommand(op, g.entries[i], states[i])); put("context", context(states[i])); put("timestamp", date(op.command.at)); put("title", g.entries[i].text)
            put("eventIDs", idArray(listOf(op.id))); put("logIDs", buildJsonArray { add(generated("${g.id}/log/${op.id}")) })
            val groupIndex = groups.indexOfFirst { a -> a.events.any { it.id == op.id } }
            val appearance = groups[groupIndex]
            val next = if ((appearance.complete || appearance.interrupted) && appearance.events.last().id == op.id) groups.getOrNull(groupIndex + 1) else appearance
            next?.let { put("nextAppearanceID", groupID.getValue(it.id)) }
            put("inserted", op.inserted); put("edited", op.edited); put("acceptsNewContext", op.acceptContext)
            put("errors", buildJsonArray { op.additionalErrors.forEachIndexed { n, credit -> add(buildJsonObject {
                put("id", generated("${g.id}/error/${op.id}/$n")); put("playerID", uid(credit.playerID)); put("count", credit.count); put("note", credit.note)
            }) } })
            op.statisticsRuling?.let { ruling -> put("statisticsRuling", buildJsonObject {
                put("runsBattedIn", ruling.runsBattedIn)
                put("earnedRuns", buildJsonArray { ruling.earnedRuns.toSortedMap().forEach { (id, runs) -> add(uid(id)); add(runs) } })
                put("unearnedRunnerIDs", idArray(ruling.unearnedRunnerIDs))
            }) }
            dateField("correctedAt", g.entries[i].reviewedAt)
            observation(g.entries[i])?.let { put("battedBall", it) }
        }) } }
        val initialAppearance = groups.first()
        val initialID = groupID.getValue(initialAppearance.id)
        val checkpointAppearances = buildJsonArray { add(buildJsonObject {
            put("id", initialID); put("inning", cp.inning); put("isTop", cp.isTop); put("batterIDs", idArray(listOf(cp.batter.id))); put("completed", false); put("interrupted", false)
        }) }
        val history = buildJsonObject { put("version", 1); put("checkpoint", Base64.getEncoder().encodeToString(json.encodeToString(JsonObject.serializer(), state(cp, appearances = checkpointAppearances, currentID = initialID)).toByteArray())); put("operations", operations); put("legacyPrefix", false) }
        val revisions = buildJsonArray { g.historyRevisions.forEach { revision ->
            val priorInput = g.copy(checkpoint = revision.previousCheckpoint, historyRevisions = emptyList(), correctionRevisions = emptyList(), correctionBoundary = 0)
            val preview = History.run(priorInput, revision.previousRecords, revision.previousRecords.size, strict = true)
            require(preview.canSave) { preview.conflict ?: "更正前历史不能重放" }
            val previous = requireNotNull(preview.game)
            val previousBackup = AppleBackupEnvelope.inspect(encode(previous))
            val previousJournal = previousBackup.snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject.getValue("historyJournal")
            add(buildJsonObject {
                put("id", uid(revision.id)); put("date", date(revision.at)); put("reason", revision.reason); put("previousJournal", previousJournal)
                put("appliedOperationIDs", idArray(revision.appliedOperationIDs)); put("summary", json.encodeToJsonElement(revision.summary))
            })
        } }
        val home = g.home.id == g.ourTeamID
        fun lineup(team: Team, ownHome: Boolean) = buildJsonArray {
            val order = if (ownHome) cp.homeOrder else cp.awayOrder
            val fielders = positions(cp, ownHome)
            (order ?: error("打序缺失")).forEachIndexed { index, id -> add(buildJsonObject { put("playerID", uid(id)); put("battingOrder", index + 1); fielders.entries.firstOrNull { it.value == id }?.let { put("position", it.key) } }) }
        }
        val game = buildJsonObject {
            put("id", uid(g.id)); put("createdAt", date(g.createdAt)); put("updatedAt", date(g.updatedAt.takeIf { it > 0 } ?: g.createdAt))
            dateField("scheduledAt", g.scheduledAt); dateField("startedAt", g.startedAt); dateField("playedAt", g.playedAt); put("playedTimeZoneID", g.playedTimeZoneID)
            put("seasonID", (Performances.year(g) ?: error("缺少年度")).toString()); idField("ourTeamID", g.ourTeamID); put("opponentTeamID", uid(if (home) g.away.id else g.home.id))
            put("isHome", home); put("isSpectator", false); put("rules", rules(g.rules)); put("lineup", lineup(if (home) cp.home else cp.away, home)); put("secondaryLineup", lineup(if (home) cp.away else cp.home, !home))
            put("status", if (g.isScheduled) 2 else if (g.isFinal) 1 else 0); put("revision", g.revision); put("openingPitchRecorded", g.openingPitchRecorded)
            put("state", JsonObject(state(g, logs, events, appearances, groups.lastOrNull()?.let { groupID.getValue(it.id) }, history) +
                if (revisions.isEmpty()) emptyMap() else mapOf("correctionRevisions" to revisions)))
        }
        val roster = AppleBackupEnvelope.inspect(AppleRosterBackup.encode(listOf(g.home, g.away), emptyList(), g.ourTeamID, g.createdAt)).snapshot
        val payload = json.encodeToString(JsonObject.serializer(), JsonObject(roster + ("games" to JsonArray(listOf(game))))).toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val result = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("format", "BaseballMaster Backup"); put("version", 7); put("createdAt", date(g.createdAt)); put("payload", Base64.getEncoder().encodeToString(payload)); put("checksum", hash) }).toByteArray()
        AppleBackupEnvelope.inspect(result)
        return result
    }
}
