package cc.baseballmaster.core

import java.util.UUID
import kotlin.math.min

/** Copy-on-write command processor. No persistence or UI side effects. */
object MatchEngine {
    private fun clone(game: Game) = GameJson.decodeFromString<Game>(GameJson.encodeToString(game))

    private fun checkpoint(game: Game) =
        GameJson.encodeToString(
            clone(game).apply {
                journal = emptyList()
                cursor = 0
                checkpoint = null
                correctionRevisions = emptyList()
            }
        )

    fun apply(input: Game, command: Command, id: String = UUID.randomUUID().toString()): Game {
        require(
            id.isNotBlank() &&
                input.journal.none { it.id == id } &&
                input.entries.none { it.id == id }
        ) {
            "重复操作"
        }
        val cmd = if (command.at == 0L) command.copy(at = System.currentTimeMillis()) else command
        val start = input.checkpoint ?: checkpoint(input)
        val base =
            if (cmd.kind == "adjudication" && cmd.adjudication?.previousPlay == "cancel") {
                require(input.cursor > 0) { "没有可以取消的上一结果" }
                replay(input, input.journal, input.cursor - 1)
            } else clone(input)
        val result = Session(base, cmd, id).run()
        result.journal =
            input.journal.take(input.cursor) +
                RecordedCommand(id, cmd, input.batter.id, input.bases)
        result.cursor = result.journal.size
        result.checkpoint = start
        result.revision = input.revision + 1
        result.validate()
        return result
    }

    private fun replay(
        input: Game,
        records: List<RecordedCommand>,
        cursor: Int,
        strict: Boolean = false,
    ): Game {
        var state = GameJson.decodeFromString<Game>(input.checkpoint ?: error("此记录尚无可重放检查点"))
        var previousBefore: Game? = null
        for (record in records.take(cursor)) {
            val before = clone(state)
            if (
                record.command.kind == "adjudication" &&
                    record.command.adjudication?.previousPlay == "cancel"
            ) {
                state = previousBefore ?: error("没有可以取消的上一结果")
            }
            if (
                strict &&
                    !record.acceptContext &&
                    record.command.kind in
                        listOf(
                            "pitch",
                            "play",
                            "hitByPitch",
                            "intentionalWalk",
                            "droppedThirdStrike",
                        ) &&
                    state.batter.id != record.batterID
            )
                error("${record.id} 后续打者发生变化，请核对后再保存纠错")
            state = Session(state, record.command, record.id).run()
            previousBefore = before
        }
        state.checkpoint = input.checkpoint
        state.journal = records
        state.cursor = cursor
        state.revision = input.revision + 1
        state.openingPitchRecorded = state.openingPitchRecorded || input.openingPitchRecorded
        state.correctionRevisions = input.correctionRevisions
        if (cursor == 0 && input.clockHasStarted) {
            state.clockHasStarted = true
            state.clockElapsedMs = input.clockElapsedMs
            state.clockRunningSince = input.clockRunningSince
        }
        state.validate()
        return state
    }

    fun stateBefore(input: Game, index: Int): Game {
        require(index in 0 until input.cursor)
        return replay(input, input.journal, index)
    }

    fun undo(input: Game): Game {
        require(input.cursor > 0) { "没有可撤销的记录" }
        return replay(input, input.journal, input.cursor - 1)
    }

    fun redo(input: Game): Game {
        require(input.cursor < input.journal.size) { "没有可恢复的记录" }
        return replay(input, input.journal, input.cursor + 1)
    }

    fun correct(
        input: Game,
        index: Int,
        replacement: Command?,
        reason: String,
        acceptContext: Boolean = false,
    ): Game {
        require(index in 0 until input.cursor && reason.isNotBlank()) { "请选择记录并填写纠错原因" }
        val records = input.journal.take(input.cursor).toMutableList()
        if (replacement == null) records.removeAt(index)
        else
            records[index] =
                records[index].copy(
                    command = replacement.copy(at = records[index].command.at),
                    acceptContext = acceptContext,
                )
        val result = replay(input, records, records.size, true)
        result.correctionRevisions =
            input.correctionRevisions +
                "${System.currentTimeMillis()} · $reason · ${input.journal[index].id}"
        return result
    }

    fun amendObservation(input: Game, index: Int, observation: Observation?, reason: String): Game {
        require(index in 0 until input.cursor && input.journal[index].command.kind == "play") {
            "只有击球记录可以补充观察"
        }
        return correct(
            input,
            index,
            input.journal[index].command.copy(observation = observation),
            reason,
        )
    }

    fun needsTiming(game: Game, command: Command): Boolean {
        val ds = command.decisions ?: return false
        if (game.outs + ds.count { it.destination == -1 } < 3 || ds.none { it.destination == 4 })
            return false
        return when (command.kind) {
            "play",
            "illegal" ->
                if (command.observation?.fielding != null)
                    !(game.outs == 2 && ds.any { it.origin == 0 && it.destination == -1 })
                else command.outcome == Outcome.runnerTagOut
            "runner" ->
                command.runnerKind !in
                    listOf(
                        "forceOut",
                        "stolenBase",
                        "doubleSteal",
                        "delayedSteal",
                        "relayAdvance",
                        "defensiveErrorAdvance",
                        "wildPitch",
                        "passedBall",
                        "uncertainLooseBall",
                        "balk",
                    )
            else -> false
        }
    }

    fun suggested(game: Game, outcome: Outcome): List<Decision> {
        val decisions =
            (3 downTo 1)
                .mapNotNull { base ->
                    game.bases[base - 1]?.let { id ->
                        Decision(
                            id,
                            base,
                            when (outcome) {
                                Outcome.homeRun,
                                Outcome.triple -> 4
                                Outcome.double -> if (base == 1) 3 else 4
                                Outcome.single,
                                Outcome.error,
                                Outcome.pending,
                                Outcome.sacrificeBunt -> base + 1
                                Outcome.sacrificeFly -> if (base == 3) 4 else 0
                                else -> 0
                            },
                        )
                    }
                }
                .toMutableList()
        decisions +=
            Decision(
                game.batter.id,
                0,
                when (outcome) {
                    Outcome.single,
                    Outcome.error,
                    Outcome.fieldersChoice,
                    Outcome.runnerTagOut,
                    Outcome.pending,
                    Outcome.other -> 1
                    Outcome.double -> 2
                    Outcome.triple -> 3
                    Outcome.homeRun -> 4
                    else -> -1
                },
            )
        val extra =
            when (outcome) {
                Outcome.fieldersChoice,
                Outcome.doublePlay -> 1
                Outcome.triplePlay -> 2
                else -> 0
            }
        decisions.indices
            .filter { decisions[it].origin > 0 }
            .take(extra)
            .forEach { i -> decisions[i] = decisions[i].copy(destination = -1) }
        return decisions
    }

    fun forced(game: Game): List<Decision> =
        (3 downTo 1).mapNotNull { base ->
            game.bases[base - 1]?.let { id ->
                Decision(
                    id,
                    base,
                    if ((1 until base).all { game.bases[it - 1] != null }) base + 1 else 0,
                )
            }
        } + Decision(game.batter.id, 0, 1)

    fun suggestedRunner(game: Game, kind: String): List<Decision> {
        val ds =
            (1..3).mapNotNull { b -> game.bases[b - 1]?.let { Decision(it, b, 0) } }.toMutableList()
        if (kind in listOf("wildPitch", "passedBall", "uncertainLooseBall", "balk", "doubleSteal"))
            return ds.map { it.copy(destination = it.origin + 1) }
        if (kind in listOf("stolenBase", "delayedSteal", "relayAdvance", "defensiveErrorAdvance")) {
            if (ds.isNotEmpty())
                ds[ds.lastIndex] = ds.last().copy(destination = ds.last().origin + 1)
        } else if (ds.isNotEmpty()) ds[0] = ds[0].copy(destination = -1)
        return ds
    }

    /**
     * AN02 snapshots contain pitches only. Rebuild only if their canonical result matches exactly.
     */
    fun upgradeLegacy(input: Game): Game {
        if (input.checkpoint != null || input.entries.isEmpty()) return input
        return runCatching {
            var fresh = Game(input.id, input.home, input.away, input.rules)
            for (event in input.entries) {
                require(event.action != null)
                fresh =
                    apply(
                        fresh,
                        Command(
                            "pitch",
                            pitch = event.action,
                            at = event.at.takeIf { it > 0 } ?: 1,
                            timeUnknown=event.at<=0,
                        ),
                        event.id,
                    )
            }
            require(
                fresh.situation() == input.situation() && fresh.totalPitches == input.totalPitches
            )
            fresh.revision = input.revision
            fresh
        }
            .getOrElse { clone(input).apply { incompletePrefix = true } }
    }
}

private class Session(val g: Game, val c: Command, val id: String) {
    private val before = g.situation()
    private val oldBatter = g.batter
    private val oldPitcher = g.pitcher
    private var notation = ""
    private var title = c.text
    private var outcome: Outcome? = null
    private var decisions: List<Decision> = emptyList()
    private var incomplete = false
    private val participants =
        g.fieldingPositions.map { (pos, pid) ->
            val p = g.fieldingTeam.players.first { it.id == pid }
            Participant(pos, p.id, p.name, p.number)
        }

    private fun bat(pid: String) = g.batting.getOrPut(pid) { BattingLine() }

    private fun pit(pid: String) =
        if (g.rules.mode == GameMode.coachPitch) PitchingLine()
        else g.pitching.getOrPut(pid) { PitchingLine() }

    private fun field(pid: String) = g.fielding.getOrPut(pid) { FieldingLine() }

    private fun start() {
        if(g.startedAt == null && !g.practice && !c.timeUnknown) g.startedAt = c.at
        g.isScheduled = false
        if (!g.clockHasStarted && !g.practice && !c.timeUnknown) {
            g.clockHasStarted = true
            g.clockRunningSince = c.at
        }
        if (g.rules.mode != GameMode.coachPitch)
            g.pitcherInnings[g.pitcher.id] =
                (g.pitcherInnings[g.pitcher.id] ?: emptySet()) + g.inning
    }

    fun run(): Game {
        c.recordingMethod?.let { require(it in listOf("traditional","situation"));g.rules=g.rules.copy(recordingMethod=it) }
        require(
            c.kind in listOf("finish", "reopen", "decision", "clock", "date", "note") ||
                (!g.isFinal && g.pendingDecision == null)
        ) {
            "请先处理终场或半局确认"
        }
        when (c.kind) {
            "activate" -> { g.isScheduled=false; title="进入比赛，等待 Play Ball"; notation="START" }
            "configure" -> {
                require(!g.openingPitchRecorded) { "首球后比赛规则已固定" }
                val rules=c.rules ?: error("请核对规则")
                require(g.home.players.size >= rules.fielders && g.away.players.size >= rules.fielders)
                require(g.fieldingPositions.size == rules.fielders) { "请先确认对应人数的守备阵容" }
                g.rules=rules;g.balls=rules.initialBalls;g.strikes=rules.initialStrikes;title="赛前规则设置";notation="RULES"
            }
            "method" -> {
                require(c.text in listOf("traditional","situation"));g.rules=g.rules.copy(recordingMethod=c.text);title=if(c.text=="situation")"切换 Pro 版"else"切换基础版";notation="METHOD"
            }
            "pitch" -> {
                start()
                pitch(c.pitch ?: error("缺少投球结果"), true)
            }
            "play" -> {
                start()
                play(c.outcome ?: error("缺少击球结果"))
            }
            "hitByPitch" -> {
                start()
                if (g.rules.mode == GameMode.coachPitch) {
                    g.totalPitches++
                    g.appearancePitches++
                    coachLimit()
                    notation = "HBP-CONTINUE"
                    title = "触身球，继续打击"
                } else {
                    pit(g.pitcher.id).pitches++
                    g.totalPitches++
                    award("HBP", true)
                }
            }
            "intentionalWalk" -> {
                require(g.rules.mode != GameMode.coachPitch) { "教练投手不适用故意保送" }
                start()
                award("IBB")
            }
            "foulBunt" -> {
                require(g.rules.mode != GameMode.slowPitch && g.strikes == 2) { "仅两好球后可记录触击界外三振" }
                start()
                g.totalPitches++
                g.appearancePitches++
                pit(g.pitcher.id).apply {
                    pitches++
                    strikes++
                }
                strikeout("K-BUNT")
            }
            "droppedThirdStrike" -> {
                require(g.rules.mode == GameMode.standard && (g.outs == 2 || g.bases[0] == null)) {
                    "当前局面不适用三振未接住上垒"
                }
                start()
                val ds = c.decisions ?: error("请核对跑者")
                validate(ds, true)
                g.totalPitches++
                g.appearancePitches++
                bat(g.strikeoutResponsibleBatterID ?: g.batter.id).apply {
                    plateAppearances++
                    atBats++
                    strikeouts++
                }
                pit(g.pitcher.id).apply {
                    pitches++
                    strikes++
                    strikeouts++
                    battersFaced++
                }
                val res = resolve(ds)
                credit(res, true)
                decisions = ds
                notation = "K WP/PB"
                title = "三振未接住"
                incomplete = true
                advance()
                registerOuts(res.second)
            }
            "runner" -> {
                start()
                runner(c.runnerKind ?: error("请选择跑垒事件"))
            }
            "illegal" -> {
                require(g.rules.mode == GameMode.slowPitch) { "Illegal 仅用于成人慢垒" }
                start()
                if (c.outcome != null) play(c.outcome)
                else if (c.pitch != null) pitch(c.pitch, c.wasThrown)
                else {
                    if (c.wasThrown) {
                        g.totalPitches++
                        g.appearancePitches++
                        pit(g.pitcher.id).pitches++
                    }
                    notation = "ILLEGAL-NP"
                    title = "No Pitch"
                }
                title = "Illegal · ${if(c.wasThrown)"实际投出" else "未实际投出"} · $title"
            }
            "adjudication" -> {
                start()
                adjudication(c.adjudication ?: error("请核对最终判罚"))
            }
            "violation" -> {
                start()
                violation(c.runnerKind ?: error("请选择判罚"))
            }
            "pitcher" -> changePitcher(c.playerID ?: error("请选择投手"))
            "batter" -> replaceBatter(c.playerID ?: error("请选择代打"))
            "pinchRunner" -> pinchRunner(c.position ?: error("请选择垒位"), c.playerID ?: error("请选择代跑"))
            "fielder" ->
                replaceFielder(c.playerID ?: error("请选择原球员"), c.replacementID ?: error("请选择新球员"))
            "position" -> position(c.playerID ?: error("请选择球员"), c.position ?: error("请选择守位"))
            "lineup" -> lineup()
            "situation" -> situation(c.situation ?: error("请核对局面"))
            "swap" -> {
                require(g.canSwapOpeningSides) { "仅能在比赛首球前互换先攻／后攻" }
                val h = g.home
                g.home = g.away
                g.away = h
                val ho = g.homeOrder
                g.homeOrder = g.awayOrder
                g.awayOrder = ho
                val hp = g.homePositions
                g.homePositions = g.awayPositions
                g.awayPositions = hp
                val dh=g.homeDesignatedHitterID;g.homeDesignatedHitterID=g.awayDesignatedHitterID;g.awayDesignatedHitterID=dh
                val index=g.homeBatterIndex;g.homeBatterIndex=g.awayBatterIndex;g.awayBatterIndex=index
                val pi = g.homePitcherID
                g.homePitcherID = g.awayPitcherID
                g.awayPitcherID = pi
                title = "互换先攻／后攻"
                notation = "SWAP"
            }
            "endHalf" -> {
                endHalf()
                title = "结束半局"
                notation = "HALF-END"
            }
            "decision" -> decision(c.flag)
            "finish" -> {
                require(!g.isFinal) { "比赛已经结束" }
                finish(c.text.ifBlank { "scorerDecision" })
                notation = "END"
                title = "比赛结束"
            }
            "reopen" -> {
                require(g.isFinal)
                g.isFinal = false
                g.endedAt = null
                g.endReason = null
                g.clockRunningSince = null
                g.pendingDecision = null
                g.dismissedWalkOffHalf = g.halfKey
                if (g.halfEndedAwaitingDecision) switchHalf()
                notation = "REOPEN"
                title = "恢复比赛，计时暂停"
            }
            "clock" -> {
                require(!g.isFinal) { "终场后请先恢复比赛" }
                when (c.text) {
                    "start",
                    "resume" -> {
                        require(g.clockRunningSince == null) { "比赛正在计时" }
                        g.clockHasStarted = true
                        g.clockRunningSince = c.at
                    }
                    "pause" -> {
                        g.clockElapsedMs = g.elapsed(c.at)
                        g.clockRunningSince = null
                    }
                    else -> error("未知计时操作")
                }
                notation = "CLOCK"
                title = if(c.text=="pause")"暂停计时"else"开始／继续计时"
            }
            "tiebreak" -> {
                require(c.decisions != null)
                validate(c.decisions, false, allowPlacement = true)
                g.bases = listOf(null, null, null)
                g.automaticRunnerIDs = emptySet()
                for (d in c.decisions) {
                    require(d.destination in 1..3)
                    g.bases = g.bases.toMutableList().apply { set(d.destination - 1, d.playerID) }
                    g.automaticRunnerIDs += d.playerID
                    g.unearnedRunnerIDs += d.playerID
                    g.runnerPitcherIDs[d.playerID] = g.pitcher.id
                }
                title = "TB 自动跑者安排"
                notation = "TB"
            }
            "date" -> {
                val date=c.dateMillis ?: error("请填写比赛时间")
                require(date > 0 && date <= System.currentTimeMillis() && c.text.isNotBlank()) { "请填写已发生的比赛时间和更正原因" }
                java.time.ZoneId.of(c.timeZoneID)
                g.dateRevisions += "${g.playedAt} → $date ${c.timeZoneID} ${c.text}"
                g.playedAt=date;g.playedTimeZoneID=c.timeZoneID;title="比赛时间更正 · ${c.text}";notation="DATE"
            }
            "schedule" -> {
                require(!g.openingPitchRecorded);val date=c.dateMillis ?: error("请填写预告时间");require(date>0)
                java.time.ZoneId.of(c.timeZoneID);g.scheduledAt=date;g.playedTimeZoneID=c.timeZoneID;g.isScheduled=true;title="安排比赛";notation="SCHEDULE"
            }
            "note" -> {
                require(c.text.isNotBlank())
                notation = "NOTE"
            }
            else -> error("不支持的记录操作，原资料未修改")
        }
        if (
            c.kind in listOf("pitch", "play", "hitByPitch", "foulBunt", "droppedThirdStrike") ||
                (c.kind == "illegal" && c.wasThrown) ||
                c.kind == "runner" &&
                    c.runnerKind in listOf("wildPitch", "passedBall", "uncertainLooseBall")
        )
            g.openingPitchRecorded = true
        if (
            !g.isFinal &&
                g.pendingDecision == null &&
                g.rules.halfRunLimit != null &&
                g.remainingRuns == 0 &&
                g.dismissedRunLimitHalf != g.halfKey
        )
            g.pendingDecision = "runLimit"
        g.entries =
            g.entries +
                PitchEntry(
                    id,
                    c.pitch,
                    title.ifBlank { c.kind },
                    c.kind,
                    notation,
                    oldBatter.id,
                    if (g.rules.mode == GameMode.coachPitch) null else oldPitcher.id,
                    outcome,
                    decisions,
                    c.observation,
                    participants,
                    before.inning,
                    before.isTop,
                    c.at,
                    before,
                    g.situation(),
                    incomplete,
                )
        g.validate()
        return g
    }

    private fun pitch(action: PitchAction, thrown: Boolean) {
        if (thrown) {
            g.totalPitches++
            g.appearancePitches++
            pit(g.pitcher.id).apply {
                pitches++
                if (action != PitchAction.ball) strikes++
            }
        }
        title = "${g.batter.name} · ${action.title}"
        notation =
            when (action) {
                PitchAction.ball -> "B"
                PitchAction.calledStrike -> "C"
                PitchAction.swingingStrike -> "S"
                PitchAction.foul -> "F"
            }
        when (action) {
            PitchAction.ball -> {
                if (g.rules.mode != GameMode.coachPitch) g.balls++
                if (g.balls >= 4) award("BB")
            }
            PitchAction.calledStrike,
            PitchAction.swingingStrike -> {
                g.strikes++
                if (g.strikes >= 3)
                    strikeout(if (action == PitchAction.swingingStrike) "K" else "ꓘ")
            }
            PitchAction.foul -> {
                if (g.strikes < 2) g.strikes++
                else if (g.rules.mode == GameMode.slowPitch) {
                    if (!g.rules.oneExtraFoul || g.extraFoulUsed) strikeout("K-F")
                    else g.extraFoulUsed = true
                }
            }
        }
        if (notation in listOf("B", "C", "S", "F")) coachLimit()
    }

    private fun coachLimit() {
        if (g.rules.mode == GameMode.coachPitch && g.appearancePitches >= g.rules.coachPitchLimit) {
            bat(g.batter.id).apply {
                plateAppearances++
                atBats++
            }
            notation = "PITCH-LIMIT"
            title = "规定投球数用尽，打者出局（不记三振）"
            advance()
            registerOuts(1)
        }
    }

    private fun strikeout(n: String) {
        bat(g.strikeoutResponsibleBatterID ?: g.batter.id).apply {
            plateAppearances++
            atBats++
            strikeouts++
        }
        pit(g.pitcher.id).apply {
            battersFaced++
            strikeouts++
        }
        notation = n
        title = "${g.batter.name} · 三振出局"
        advance()
        registerOuts(1)
    }

    private fun award(n: String, hbp: Boolean = false) {
        val owner = if (hbp) g.pitcher.id else g.walkResponsiblePitcherID ?: g.pitcher.id
        bat(g.batter.id).apply {
            plateAppearances++
            if (hbp) hitByPitch++ else walks++
        }
        pit(owner).apply {
            battersFaced++
            if (hbp) hitByPitch++ else walks++
        }
        val ds = MatchEngine.forced(g)
        val res = resolve(ds, owner)
        bat(g.batter.id).runsBattedIn += res.first.size
        credit(res, true)
        decisions = ds
        notation = n
        title = "${g.batter.name} · ${if(hbp)"触身球" else "保送"}"
        walkOff()
        advance()
    }

    private fun advance() {
        g.walkResponsiblePitcherID = null
        g.strikeoutResponsibleBatterID = null
        if (g.isTop) g.awayBatterIndex = (g.awayBatterIndex + 1) % g.battingOrder.size
        else g.homeBatterIndex = (g.homeBatterIndex + 1) % g.battingOrder.size
        g.balls = g.rules.initialBalls
        g.strikes = g.rules.initialStrikes
        g.appearancePitches = 0
        g.extraFoulUsed = false
    }

    private fun validate(
        ds: List<Decision>,
        includesBatter: Boolean,
        allowPlacement: Boolean = false,
    ) {
        require(ds.map { it.playerID }.distinct().size == ds.size) { "同一球员不能出现两次" }
        if (!allowPlacement) {
            require(
                ds.filter { it.origin > 0 }.map { it.playerID }.toSet() ==
                    g.bases.filterNotNull().toSet()
            ) {
                "跑者名单与局面不一致"
            }
            for (d in ds.filter { it.origin > 0 }) require(
                d.origin in 1..3 &&
                    g.bases[d.origin - 1] == d.playerID &&
                    (d.destination in listOf(-1, 0, 4) || d.destination in (d.origin + 1)..3)
            ) {
                "跑者起点或去向无效"
            }
            val batters = ds.filter { it.origin == 0 }
            require(
                if (includesBatter)
                    batters.size == 1 &&
                        batters[0].playerID == g.batter.id &&
                        batters[0].destination in listOf(-1, 1, 2, 3, 4)
                else batters.isEmpty()
            ) {
                "打者结果与当前棒次不一致"
            }
        } else require(ds.all { d -> g.battingTeam.players.any { it.id == d.playerID } })
        val bases = ds.mapNotNull {
            if (it.destination in 1..3) it.destination
            else if (it.destination == 0 && it.origin > 0) it.origin else null
        }
        require(bases.distinct().size == bases.size) { "同一垒位不能站两名跑者" }
        require(g.outs + ds.count { it.destination == -1 } <= 3) { "超过三个出局" }
    }

    private fun resolve(
        ds: List<Decision>,
        batterOwner: String = g.pitcher.id,
        unearnedBatter: Boolean = false,
    ): Pair<List<String>, Int> {
        for (pid in g.bases.filterNotNull()) g.runnerPitcherIDs.putIfAbsent(pid, g.pitcher.id)
        val newBases = MutableList<String?>(3) { null }
        val scored = mutableListOf<String>()
        var out = 0
        for (d in ds) {
            if (d.origin == 0) {
                g.runnerPitcherIDs[d.playerID] = batterOwner
                g.unearnedRunnerIDs -= d.playerID
                if (unearnedBatter) g.unearnedRunnerIDs += d.playerID
            }
            when (d.destination) {
                -1 -> {
                    out++
                    g.automaticRunnerIDs -= d.playerID
                }
                4 -> {
                    scored += d.playerID
                    g.automaticRunnerIDs -= d.playerID
                }
                0 -> if (d.origin > 0) newBases[d.origin - 1] = d.playerID
                else -> newBases[d.destination - 1] = d.playerID
            }
        }
        g.bases = newBases
        return scored.take(g.remainingRuns) to out
    }

    private fun credit(res: Pair<List<String>, Int>, earned: Boolean) {
        val scorers = res.first
        if (g.isTop) {
            g.awayRuns += scorers.size
            g.awayRunsByInning = addInning(g.awayRunsByInning, scorers.size)
        } else {
            g.homeRuns += scorers.size
            g.homeRunsByInning = addInning(g.homeRunsByInning, scorers.size)
        }
        for (pid in scorers) {
            bat(pid).runs++
            pit(g.runnerPitcherIDs[pid] ?: oldPitcher.id).apply {
                runs++
                if (earned && pid !in g.unearnedRunnerIDs && pid !in g.automaticRunnerIDs)
                    earnedRuns++
            }
        }
    }

    private fun addInning(list: List<Int>, runs: Int): List<Int> {
        val l = list.toMutableList()
        while (l.size < g.inning) l += 0
        l[g.inning - 1] += runs
        return l
    }

    private fun play(o: Outcome) {
        c.observation?.validate(g.rules)
        val ds = c.decisions ?: MatchEngine.suggested(g, o)
        validate(ds, true)
        require(!(g.rules.mode == GameMode.slowPitch && o == Outcome.sacrificeBunt)) { "成人慢垒不适用触击" }
        when (o) {
            Outcome.infieldFly ->
                require(g.outs < 2 && g.bases[0] != null && g.bases[1] != null) {
                    "内野高飞必死需一二垒有人且不足两出局"
                }
            Outcome.doublePlay -> require(g.outs <= 1 && g.bases.any { it != null }) { "当前局面无法双杀" }
            Outcome.triplePlay ->
                require(g.outs == 0 && g.bases.count { it != null } >= 2) { "当前局面无法三杀" }
            Outcome.sacrificeFly -> require(g.outs < 2 && g.bases[2] != null) { "牺牲高飞需三垒有人且不足两出局" }
            Outcome.sacrificeBunt ->
                require(g.outs < 2 && g.bases.any { it != null }) { "当前局面无法牺牲触击" }
            else -> Unit
        }
        val obs = c.observation
        if (obs?.fielding != null) {
            require(
                obs.precision != "unknown" &&
                    obs.fielding.status in listOf("recorded", "pending", "noFielding")
            )
            require(obs.fielding.status != "noFielding" || o == Outcome.homeRun)
            require(
                o != Outcome.error ||
                    obs.fielding.status == "pending" ||
                    obs.fielding.errorPosition != null
            ) {
                "请确认失误责任"
            }
        }
        if (obs?.fielding != null) {
            val count = ds.count { it.destination == -1 }
            require(o != Outcome.doublePlay || count == 2) { "双杀必须确认两个实际出局" }
            require(o != Outcome.triplePlay || count == 3) { "三杀必须确认三个实际出局" }
            require(
                o !in
                    listOf(
                        Outcome.groundOut,
                        Outcome.flyOut,
                        Outcome.lineOut,
                        Outcome.foulFlyOut,
                        Outcome.infieldFly,
                        Outcome.sacrificeBunt,
                        Outcome.sacrificeFly,
                        Outcome.pendingOut,
                    ) || ds.any { it.origin == 0 && it.destination == -1 }
            ) {
                "该结果要求打者出局"
            }
            for (d in ds) {
                require(d.cause in ScoringOptions.causes)
                if (d.cause in listOf("relay", "error", "tag", "appeal") && d.destination != 0) {
                    val f =
                        d.fielding
                            ?: if (d.route.isNotEmpty())
                                Fielding(d.route, errorPosition = d.errorPosition)
                            else error("请补充跑垒处理路线或标记待补")
                    f.validate(g.rules)
                    require(f.status != "noFielding")
                    require(
                        d.cause != "error" || f.status == "pending" || f.errorPosition != null
                    ) {
                        "请选择跑垒失误责任人"
                    }
                }
            }
        }
        val thirds = g.outs + ds.count { it.destination == -1 } >= 3
        val timing =
            thirds &&
                ds.any { it.destination == 4 } &&
                (if (obs?.fielding != null)
                    !(g.outs == 2 && ds.any { it.origin == 0 && it.destination == -1 })
                else o == Outcome.runnerTagOut)
        require(!timing || c.timingRunCounts != null) { "请确认得分和第三出局的先后" }
        val batter = g.batter.id
        val pitcher = g.pitcher.id
        g.totalPitches++
        g.appearancePitches++
        val b = bat(batter)
        b.plateAppearances++
        pit(pitcher).apply {
            battersFaced++
            pitches++
            strikes++
        }
        when {
            o.isHit -> {
                b.atBats++
                b.hits++
                pit(pitcher).hits++
                if (o == Outcome.double) b.doubles++
                if (o == Outcome.triple) b.triples++
                if (o == Outcome.homeRun) b.homeRuns++
                if (g.isTop) g.awayHits++ else g.homeHits++
            }
            o in listOf(Outcome.sacrificeBunt, Outcome.sacrificeFly) -> b.sacrifices++
            o != Outcome.other -> b.atBats++
        }
        for (d in ds) if (d.cause == "error" && d.destination != 0)
            g.unearnedRunnerIDs += d.playerID
        var res = resolve(ds, unearnedBatter = o == Outcome.error)
        if (o == Outcome.fieldersChoice && ds.any { it.origin == 0 && it.destination != -1 })
            ds.firstOrNull { it.origin > 0 && it.destination == -1 }
                ?.let { d ->
                    g.runnerPitcherIDs[d.playerID]?.let { g.runnerPitcherIDs[batter] = it }
                    if (d.playerID in g.unearnedRunnerIDs) g.unearnedRunnerIDs += batter
                }
        if (
            thirds &&
                ((g.outs == 2 && ds.any { it.origin == 0 && it.destination == -1 }) ||
                    (obs?.fielding == null &&
                        o in
                            listOf(
                                Outcome.groundOut,
                                Outcome.fieldersChoice,
                                Outcome.doublePlay,
                                Outcome.triplePlay,
                                Outcome.pendingOut,
                            ))) || c.timingRunCounts == false
        )
            res = emptyList<String>() to res.second
        if (o !in listOf(Outcome.error, Outcome.pendingOut, Outcome.pending, Outcome.other))
            b.runsBattedIn +=
                res.first.count { pid ->
                    ds.first { it.playerID == pid }.cause !in
                        listOf("relay", "error", "award", "pending")
                }
        credit(res, o !in listOf(Outcome.error, Outcome.pendingOut, Outcome.pending))
        val defense =
            obs?.fielding?.let { main ->
                val extra = ds.filter { it.destination == -1 && it.fielding?.status == "recorded" }
                val mainOuts = (res.second - extra.size).coerceAtLeast(0)
                val catch =
                    o in
                        listOf(
                            Outcome.flyOut,
                            Outcome.lineOut,
                            Outcome.foulFlyOut,
                            Outcome.sacrificeFly,
                            Outcome.infieldFly,
                        )
                val po =
                    if (main.status == "recorded" && mainOuts > 0) {
                        if (catch) main.route.take(1) else main.route.takeLast(mainOuts)
                    } else emptyList()
                val assists =
                    if (main.status == "recorded" && mainOuts > 0 && !catch) main.route.dropLast(1)
                    else emptyList()
                main.copy(
                    putouts = po + extra.map { it.fielding!!.route.last() },
                    assists =
                        (assists + extra.flatMap { it.fielding!!.route.dropLast(1) }).distinct(),
                )
            } ?: c.fielding
        defensive(defense, o, res.second)
        if (o == Outcome.error && obs?.fielding != null && defense?.errorPosition == null) {
            if (g.isTop) g.homeErrors++ else g.awayErrors++
        }
        ds.mapNotNull {
                if (it.cause == "error" && it.destination != 0)
                    (it.fielding?.errorPosition ?: it.errorPosition)
                else null
            }
            .toSet()
            .filter { it != defense?.errorPosition }
            .forEach {
                defensive(Fielding(errorPosition = it, status = "pending"), Outcome.error, 0)
            }
        decisions = ds
        outcome = o
        notation = o.notation
        title = "${oldBatter.name} · ${o.title}"
        incomplete = o in listOf(Outcome.other, Outcome.pendingOut, Outcome.pending)
        walkOff()
        advance()
        registerOuts(res.second)
    }

    private fun defensive(d: Fielding?, o: Outcome, outs: Int) {
        if (d == null) return
        val catchOut =
            o in
                listOf(
                    Outcome.flyOut,
                    Outcome.lineOut,
                    Outcome.foulFlyOut,
                    Outcome.sacrificeFly,
                    Outcome.infieldFly,
                )
        val assists =
            if (c.observation?.fielding != null) d.assists
            else if (d.assists.isNotEmpty()) d.assists
            else if (d.status == "recorded" && outs > 0 && !catchOut) d.route.dropLast(1).distinct()
            else emptyList()
        val putouts =
            if (c.observation?.fielding != null) d.putouts
            else if (d.putouts.isNotEmpty()) d.putouts
            else if (d.status == "recorded" && outs > 0 && d.route.isNotEmpty()) {
                if (catchOut) listOf(d.route.first()) else d.route.takeLast(outs)
            } else emptyList()
        assists.forEach { p -> g.fieldingPositions[p]?.let { field(it).assists++ } }
        putouts.forEach { p -> g.fieldingPositions[p]?.let { field(it).putouts++ } }
        if (o == Outcome.doublePlay)
            d.route.toSet().forEach { p -> g.fieldingPositions[p]?.let { field(it).doublePlays++ } }
        d.errorPosition?.let { p ->
            g.fieldingPositions[p]?.let {
                field(it).errors++
                if (g.isTop) g.homeErrors++ else g.awayErrors++
            }
        }
    }

    private fun runner(kind: String) {
        require(ScoringOptions.runnerAllowed(g.rules.mode, kind)) { "此模式不适用该跑垒事件" }
        val stolen = listOf("stolenBase", "doubleSteal", "delayedSteal")
        val advancing =
            stolen +
                listOf(
                    "relayAdvance",
                    "defensiveErrorAdvance",
                    "wildPitch",
                    "passedBall",
                    "uncertainLooseBall",
                    "balk",
                )
        require(
            !(g.rules.mode == GameMode.slowPitch && kind in stolen + "leftEarly") &&
                !(g.rules.mode == GameMode.coachPitch &&
                    kind in
                        stolen +
                            listOf(
                                "caughtStealing",
                                "leftEarly",
                                "wildPitch",
                                "passedBall",
                                "uncertainLooseBall",
                                "balk",
                            ))
        ) {
            "此模式不适用该跑垒事件"
        }
        val ds = c.decisions ?: MatchEngine.suggestedRunner(g, kind)
        require(ds.isNotEmpty()) { "没有跑者变化" }
        validate(ds, false)
        var res = resolve(ds)
        val thirds = g.outs + res.second >= 3 && res.first.isNotEmpty()
        require(
            !(thirds && kind !in advancing && kind != "forceOut" && c.timingRunCounts == null)
        ) {
            "请确认得分与第三出局的先后"
        }
        if ((kind == "forceOut" && g.outs + res.second >= 3) || c.timingRunCounts == false)
            res = emptyList<String>() to res.second
        if (kind in stolen)
            ds.filter { it.destination != 0 && it.destination != -1 }
                .forEach { bat(it.playerID).stolenBases++ }
        if (kind == "caughtStealing")
            ds.filter { it.destination == -1 }.forEach { bat(it.playerID).caughtStealing++ }
        if (kind == "wildPitch") pit(g.pitcher.id).wildPitches++
        credit(res, kind !in listOf("passedBall", "uncertainLooseBall"))
        decisions = ds
        notation = kind
        title = ScoringOptions.runnerEvents.getValue(kind)
        incomplete = kind in listOf("uncertainLooseBall", "defensiveErrorAdvance")
        walkOff()
        registerOuts(res.second)
    }

    private fun adjudication(r: Ruling) {
        val kind = c.runnerKind ?: error("请选择判罚")
        require(
            kind in ScoringOptions.violations &&
                !(g.rules.mode == GameMode.coachPitch &&
                    kind in listOf("balk", "quickPitch", "illegalPitch", "foreignSubstance"))
        ) {
            "此模式不适用该判罚"
        }
        require(
            r.previousPlay in listOf("notApplicable", "keep", "cancel") &&
                r.plateAppearance in listOf("continueAtBat", "batterOut", "batterFirst")
        ) {
            "判罚选项无效"
        }
        require(
            r.battingCredit in
                listOf(
                    "none",
                    "single",
                    "double",
                    "triple",
                    "homeRun",
                    "sacrifice",
                    "reachedOnError",
                )
        )
        val ds = c.decisions ?: error("请核对判罚后的跑者")
        validate(ds, r.plateAppearance != "continueAtBat")
        val scored = ds.count { it.destination == 4 }
        val automatic = ds.count { it.destination == 4 && it.playerID in g.automaticRunnerIDs }
        require(r.runsBattedIn in 0..scored && r.earnedRuns in 0..(scored - automatic)) {
            "打点或自责分超过本次有效得分"
        }
        require(r.battingCredit == "none" || r.plateAppearance != "continueAtBat") {
            "打席继续时不能记入最终打击结果"
        }
        val hit = r.battingCredit in listOf("single", "double", "triple", "homeRun")
        val batterDecision = ds.firstOrNull { it.origin == 0 }
        require(!hit || batterDecision?.destination != -1) { "打者被判出局时不能记为安打" }
        require(r.battingCredit != "homeRun" || batterDecision?.destination == 4) { "本垒打要求打者得分" }
        if (r.battingCredit == "reachedOnError")
            require(r.errorPlayerID in g.fieldingPositions.values) { "请选择场上失误责任人" }
        val batter = g.batter.id
        var res = resolve(ds)
        if (r.plateAppearance == "batterOut" && g.outs + res.second >= 3)
            res = emptyList<String>() to res.second
        if (r.plateAppearance != "continueAtBat") {
            bat(batter).apply {
                plateAppearances++
                if (r.countsAsAtBat) atBats++
            }
            pit(g.pitcher.id).battersFaced++
        }
        if (hit) {
            bat(batter).apply {
                hits++
                when (r.battingCredit) {
                    "double" -> doubles++
                    "triple" -> triples++
                    "homeRun" -> homeRuns++
                }
            }
            pit(g.pitcher.id).hits++
            if (g.isTop) g.awayHits++ else g.homeHits++
        } else if (r.battingCredit == "sacrifice") bat(batter).sacrifices++
        else if (r.battingCredit == "reachedOnError") {
            field(r.errorPlayerID!!).errors++
            if (g.isTop) g.homeErrors++ else g.awayErrors++
        }
        bat(batter).runsBattedIn += min(r.runsBattedIn, res.first.size)
        credit(res, false)
        pit(g.pitcher.id).earnedRuns +=
            min(r.earnedRuns, (res.first.size - automatic).coerceAtLeast(0))
        decisions = ds
        notation = "RULING"
        title = "裁判最终宣判 · ${ScoringOptions.violations.getValue(kind)}"
        incomplete = !r.isFinal
        if (r.plateAppearance != "continueAtBat") advance()
        walkOff()
        registerOuts(res.second)
    }

    private fun violation(kind: String) {
        require(kind in ScoringOptions.violations) { "未知判罚，原记录未修改" }
        require(
            !(g.rules.mode == GameMode.coachPitch &&
                kind in listOf("balk", "quickPitch", "illegalPitch", "foreignSubstance"))
        ) {
            "教练投手不适用投手犯规"
        }
        if (g.rules.mode == GameMode.slowPitch && kind == "illegalPitch") {
            pitch(PitchAction.ball, true)
            return
        }
        when (kind) {
            "balk",
            "quickPitch",
            "illegalPitch" -> {
                if (g.bases.all { it == null }) {
                    g.balls++
                    g.totalPitches++
                    pit(g.pitcher.id).pitches++
                    if (g.balls >= 4) award("BB")
                } else {
                    val ds = c.decisions ?: MatchEngine.suggestedRunner(g, "balk")
                    validate(ds, false)
                    val res = resolve(ds)
                    credit(res, false)
                    decisions = ds
                    registerOuts(res.second)
                }
            }
            "batterOutOfBox",
            "batterInterference",
            "illegalBat",
            "runningLaneInterference" -> {
                bat(g.batter.id).apply {
                    plateAppearances++
                    atBats++
                }
                pit(g.pitcher.id).battersFaced++
                advance()
                registerOuts(1)
            }
            "catcherInterference" -> {
                val ds = c.decisions ?: MatchEngine.forced(g)
                validate(ds, true)
                bat(g.batter.id).plateAppearances++
                pit(g.pitcher.id).battersFaced++
                val res = resolve(ds)
                credit(res, false)
                decisions = ds
                advance()
                registerOuts(res.second)
            }
            "foreignSubstance",
            "battingOutOfTurn" -> incomplete = true
            else -> {
                val ds = c.decisions ?: error("请核对裁判最终判罚和跑者位置")
                validate(ds, false)
                val res = resolve(ds)
                credit(res, false)
                decisions = ds
                registerOuts(res.second)
            }
        }
        notation = "VIOLATION"
        title = "裁判判罚 · ${ScoringOptions.violations.getValue(kind)}"
        walkOff()
    }

    private fun changePitcher(pid: String) {
        require(g.fieldingTeam.players.any { it.id == pid }) {
            "投手不在可用名单中"
        }
        val old = g.pitcher.id
        g.bases.filterNotNull().forEach { g.runnerPitcherIDs.putIfAbsent(it, old) }
        g.walkResponsiblePitcherID =
            if (g.balls >= 3 || (g.balls == 2 && g.strikes < 2)) g.walkResponsiblePitcherID ?: old
            else null
        val fromBench = pid !in g.fieldingPositions.values
        val dh=if(g.isTop)g.homeDesignatedHitterID else g.awayDesignatedHitterID
        val keepsDH=g.rules.twoWayEnabled && dh==old && fromBench
        position(pid, 1)
        if (fromBench && !keepsDH) {
            val order =
                ((if (g.isTop) g.homeOrder else g.awayOrder)
                        ?: g.fieldingTeam.players.map { it.id })
                    .map { if (it == old) pid else it }
            if (g.isTop) g.homeOrder = order else g.awayOrder = order
            g.exitedPlayers += old
        }
        if(!fromBench&&g.rules.twoWayEnabled&&dh==old){if(g.isTop)g.homeDesignatedHitterID=null else g.awayDesignatedHitterID=null}
        if (g.isTop) g.homePitcherID = pid else g.awayPitcherID = pid
        notation = "PITCHER"
        title = "换投 ${g.pitcher.name}"
    }

    private fun replaceBatter(pid: String) {
        require(g.battingTeam.players.any { it.id == pid } && pid !in g.battingOrder) {
            "代打不在可用名单中或已在打序"
        }
        val old = g.batter.id
        if (g.strikes == 2) g.strikeoutResponsibleBatterID = g.strikeoutResponsibleBatterID ?: old
        val order = g.battingOrder.toMutableList()
        order[g.batterIndex] = pid
        if (g.isTop) g.awayOrder = order else g.homeOrder = order
        val positions =
            if (g.isTop) g.awayPositions ?: defaultPositions(g.away)
            else g.homePositions ?: defaultPositions(g.home)
        val changed = positions.mapValues { if (it.value == old) pid else it.value }
        setTeamPositions(!g.isTop,changed)
        replaceDH(old,pid,!g.isTop)
        g.exitedPlayers += old
        notation = "BATTER"
        title = "代打 ${g.batter.name}"
    }

    private fun pinchRunner(base: Int, pid: String) {
        require(base in 1..3 && g.bases[base - 1] != null)
        require(g.battingTeam.players.any { it.id == pid } && pid !in g.battingOrder) {
            "代跑不在可用名单中"
        }
        val old = g.bases[base - 1]!!
        val order = g.battingOrder.toMutableList()
        val index = order.indexOf(old)
        require(index >= 0)
        order[index] = pid
        if (g.isTop) g.awayOrder = order else g.homeOrder = order
        val positions =
            (if (g.isTop) g.awayPositions ?: defaultPositions(g.away)
                else g.homePositions ?: defaultPositions(g.home))
                .mapValues { if (it.value == old) pid else it.value }
        setTeamPositions(!g.isTop,positions)
        g.bases = g.bases.toMutableList().apply { set(base - 1, pid) }
        g.runnerPitcherIDs[pid] = g.runnerPitcherIDs[old] ?: g.pitcher.id
        if (old in g.unearnedRunnerIDs) g.unearnedRunnerIDs += pid
        if (old in g.automaticRunnerIDs) {
            g.automaticRunnerIDs -= old
            g.automaticRunnerIDs += pid
        }
        replaceDH(old,pid,!g.isTop)
        g.exitedPlayers += old
        notation = "PINCH-RUNNER"
        title = "代跑"
    }

    private fun setTeamPositions(home:Boolean,positions:Map<Int,String>) {
        if(home){g.homePositions=positions;g.homePitcherID=positions[1]}else{g.awayPositions=positions;g.awayPitcherID=positions[1]}
    }

    private fun replaceDH(old:String,new:String,home:Boolean) {
        if(home && g.homeDesignatedHitterID==old) g.homeDesignatedHitterID=new
        if(!home && g.awayDesignatedHitterID==old) g.awayDesignatedHitterID=new
    }

    private fun defaultPositions(t: Team) =
        t.players.take(g.rules.fielders).mapIndexed { i, p -> i + 1 to p.id }.toMap()

    private fun position(pid: String, pos: Int) {
        require(pos in 1..g.rules.fielders && g.fieldingTeam.players.any { it.id == pid })
        val positions = g.fieldingPositions.toMutableMap()
        val old = positions.entries.firstOrNull { it.value == pid }?.key
        val occupant = positions[pos]
        if (old != null && occupant != null) positions[old] = occupant
        else if (old != null) positions.remove(old)
        positions[pos] = pid
        if (g.isTop) {
            g.homePositions = positions
            g.homePitcherID = positions[1]
        } else {
            g.awayPositions = positions
            g.awayPitcherID = positions[1]
        }
        notation = "POSITION"
        title = "守位调整"
    }

    private fun replaceFielder(old: String, new: String) {
        require(new !in g.fieldingPositions.values && g.fieldingTeam.players.any { it.id == new })
        val pos =
            g.fieldingPositions.entries.firstOrNull { it.value == old }?.key ?: error("原球员不在守备阵容")
        position(new, pos)
        val teamOrder =
            (if (g.isTop) g.homeOrder else g.awayOrder) ?: g.fieldingTeam.players.map { it.id }
        val order = teamOrder.map { if (it == old) new else it }
        if (g.isTop) g.homeOrder = order else g.awayOrder = order
        replaceDH(old,new,g.isTop)
        g.exitedPlayers += old
        notation = "SUB"
        title = "守备换人"
    }

    private fun lineup() {
        val home = c.homeSide ?: error("请指定球队")
        val team = if (home) g.home else g.away
        val order = c.order ?: error("请核对打序")
        val positions = c.positions ?: error("请核对守位")
        require(
            order.distinct() == order &&
                order.size >= g.rules.fielders &&
                order.all { id -> team.players.any { it.id == id } }
        )
        require(
            positions.keys == (1..g.rules.fielders).toSet() &&
                positions.values.distinct().size == g.rules.fielders &&
                positions.values.all { id -> team.players.any { it.id == id } }
        )
        val old =
            if (home) g.homeOrder ?: g.home.players.map { it.id }
            else g.awayOrder ?: g.away.players.map { it.id }
        val current = old[(if (home) g.homeBatterIndex else g.awayBatterIndex) % old.size]
        require(if(g.rules.mode==GameMode.slowPitch&&!g.openingPitchRecorded)order.size>=10 else order.size==old.size) { "本场打序人数已固定" }
        val anchor=c.playerID?:current
        require(anchor in order) { "请保留当前打者或明确接替者" }
        if(home!=g.isTop)require(g.bases.filterNotNull().all{it in order}&&anchor !in g.bases) { "打者或跑者身份发生冲突" }
        val dh=c.designatedHitterID
        if(dh!=null)require(g.rules.dhEnabled&&dh in order&&(dh !in positions.values||g.rules.twoWayEnabled&&dh==positions[1])) { "DH 与守备身份不一致" }
        if(home)g.homeDesignatedHitterID=dh else g.awayDesignatedHitterID=dh
        val newIndex = if(!g.openingPitchRecorded)0 else order.indexOf(anchor)
        if (home) {
            g.homeOrder = order
            g.homePositions = positions
            g.homePitcherID = positions[1]
            g.homeBatterIndex = newIndex
        } else {
            g.awayOrder = order
            g.awayPositions = positions
            g.awayPitcherID = positions[1]
            g.awayBatterIndex = newIndex
        }
        notation = "LINEUP"
        title = "阵容调整"
    }

    private fun situation(s: Situation) {
        require(
            s.inning >= 1 &&
                s.balls in 0..3 &&
                s.strikes in 0..2 &&
                s.outs in 0..2 &&
                s.awayScore >= 0 &&
                s.homeScore >= 0
        )
        g.inning = s.inning
        g.isTop = s.isTop
        g.balls = s.balls
        g.strikes = s.strikes
        g.outs = s.outs
        g.bases = s.bases
        require(s.batterIndex in g.battingOrder.indices)
        if (g.isTop) g.awayBatterIndex = s.batterIndex else g.homeBatterIndex = s.batterIndex
        fun score(old: List<Int>, total: Int): List<Int> {
            val l = old.toMutableList()
            while (l.size < g.inning) l += 0
            var diff = total - l.sum()
            if (diff >= 0) l[g.inning - 1] += diff
            else
                for (i in l.indices.reversed()) {
                    val remove = min(l[i], -diff)
                    l[i] -= remove
                    diff += remove
                    if (diff == 0) break
                }
            return l
        }
        g.awayRunsByInning = score(g.awayRunsByInning, s.awayScore)
        g.homeRunsByInning = score(g.homeRunsByInning, s.homeScore)
        g.awayRuns = s.awayScore
        g.homeRuns = s.homeScore
        s.pitcherID?.let { changePitcher(it) }
        g.incompletePrefix = true
        notation = "CORRECTION"
        title = "局面修正，过程待确认"
    }

    private fun registerOuts(n: Int) {
        val actual = min(n, 3 - g.outs).coerceAtLeast(0)
        pit(g.pitcher.id).outsRecorded += actual
        g.outs += actual
        if (g.outs >= 3) endHalf()
    }

    private fun endHalf() {
        g.appearancePitches = 0
        val regulation = g.rules.regulationInnings
        val decision =
            regulation != null && g.inning >= regulation && (!g.isTop || g.homeRuns > g.awayRuns)
        if (decision) {
            g.pendingDecision = "regulationHalf"
            g.halfEndedAwaitingDecision = true
        } else switchHalf()
    }

    private fun switchHalf() {
        g.outs = 0
        g.balls = g.rules.initialBalls
        g.strikes = g.rules.initialStrikes
        g.extraFoulUsed = false
        g.bases = listOf(null, null, null)
        g.automaticRunnerIDs = emptySet()
        g.runnerPitcherIDs.clear()
        g.unearnedRunnerIDs = emptySet()
        g.walkResponsiblePitcherID = null
        g.strikeoutResponsibleBatterID = null
        g.pendingDecision = null
        g.halfEndedAwaitingDecision = false
        g.isTop = !g.isTop
        if (g.isTop) g.inning++
        while (g.homeRunsByInning.size < g.inning) {
            g.homeRunsByInning += 0
            g.awayRunsByInning += 0
        }
        g.appearancePitches = 0
    }

    private fun walkOff() {
        if (
            !g.isTop &&
                g.rules.regulationInnings?.let { g.inning >= it } == true &&
                g.homeRuns > g.awayRuns &&
                g.dismissedWalkOffHalf != g.halfKey
        )
            g.pendingDecision = "walkOff"
    }

    private fun decision(finish: Boolean) {
        val pending = g.pendingDecision ?: error("没有待确认事项")
        g.pendingDecision = null
        when (pending) {
            "runLimit" -> {
                g.dismissedRunLimitHalf = g.halfKey
                if (finish) endHalf()
            }
            "regulationHalf" -> if (finish) finish("regulation") else switchHalf()
            "walkOff" -> {
                g.dismissedWalkOffHalf = g.halfKey
                if (finish) finish("walkOff")
            }
        }
        notation = "DECISION"
        title = if (finish) "确认结束" else "确认继续"
    }

    private fun finish(reason: String) {
        require(
            reason in
                listOf(
                    "regulation",
                    "walkOff",
                    "timeLimit",
                    "mercyRule",
                    "forfeit",
                    "weather",
                    "suspended",
                    "scorerDecision",
                )
        )
        g.clockElapsedMs = g.elapsed(c.at)
        g.clockRunningSince = null
        g.isFinal = true
        g.endedAt = c.at
        g.endReason = reason
        g.pendingDecision = null
    }
}
