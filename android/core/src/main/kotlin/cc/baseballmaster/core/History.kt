package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class HistoryContext(
    val inning: Int,
    val isTop: Boolean,
    val batterIndex: Int,
    val homeOrder: List<String>,
    val awayOrder: List<String>,
    val homePositions: Map<Int, String> = emptyMap(),
    val awayPositions: Map<Int, String> = emptyMap(),
) {
    companion object {
        fun capture(g: Game) =
            HistoryContext(
                g.inning,
                g.isTop,
                g.batterIndex,
                g.homeOrder ?: g.home.players.map { it.id },
                g.awayOrder ?: g.away.players.map { it.id },
                g.homePositions
                    ?: g.home.players
                        .take(g.rules.fielders)
                        .mapIndexed { i, p -> i + 1 to p.id }
                        .toMap(),
                g.awayPositions
                    ?: g.away.players
                        .take(g.rules.fielders)
                        .mapIndexed { i, p -> i + 1 to p.id }
                        .toMap(),
            )
    }
}

@Serializable
data class ErrorCredit(val playerID: String, val count: Int = 1, val note: String = "漏记守备失误")

@Serializable
data class StatisticsRuling(
    val runsBattedIn: Int,
    val earnedRuns: Map<String, Int>,
    val unearnedRunnerIDs: Set<String> = emptySet(),
)

@Serializable
data class CorrectionDraft(
    val gameID: String,
    val baseRevision: Long,
    val checkpoint: String,
    val records: List<RecordedCommand>,
    val reason: String = "",
    val addedHomePlayers: List<Player> = emptyList(),
    val addedAwayPlayers: List<Player> = emptyList(),
)

@Serializable
data class HistoryRevision(
    val id: String,
    val at: Long,
    val reason: String,
    val previousCheckpoint: String,
    val previousRecords: List<RecordedCommand>,
    val appliedOperationIDs: Set<String>,
    val summary: List<String>,
)

data class CorrectionPreview(
    val game: Game?,
    val records: List<RecordedCommand>,
    val changes: List<String>,
    val conflictID: String? = null,
    val conflict: String? = null,
) {
    val canSave
        get() = game != null && conflict == null
}

/** Replays a detached draft. The original game and committed audit history are never mutated. */
object History {
    fun title(command: Command): String =
        when (command.kind) {
            "play" -> command.outcome?.title ?: "击球"
            "pitch" -> command.pitch?.title ?: "投球"
            "runner" -> ScoringOptions.runnerEvents[command.runnerKind] ?: "跑垒"
            "pitcher" -> "换投"
            "batter" -> "代打"
            "pinchRunner" -> "代跑"
            "fielder" -> "守备换人"
            "position" -> "守位互换"
            "lineup" -> "阵容调整"
            "suspend" -> "比赛中断"
            "finish" -> "结束比赛"
            "reopen" -> "恢复比赛"
            "endHalf" -> "结束半局"
            "clock" -> "比赛计时"
            "hitByPitch" -> "触身球"
            "intentionalWalk" -> "故意保送"
            "note" -> "记录备注"
            else -> "比赛操作"
        }

    private fun copy(g: Game) = GameJson.decodeFromString<Game>(GameJson.encodeToString(g))

    fun draft(input: Game): CorrectionDraft {
        val game = MatchEngine.upgradeLegacy(input)
        val checkpoint = game.checkpoint ?: MatchEngine.checkpoint(game)
        val replay =
            run(
                game.copy(checkpoint = checkpoint),
                game.journal.take(game.cursor),
                game.cursor,
                false,
            )
        check(replay.canSave) { replay.conflict ?: "原记录无法完整重放，请先核对资料" }
        return CorrectionDraft(input.id, input.revision, checkpoint, replay.records)
    }

    fun stateBefore(input: Game, draft: CorrectionDraft, index: Int): Game {
        require(index in 0..draft.records.size)
        return run(input.copy(checkpoint = draft.checkpoint), draft.records, index, false).game
            ?: error("无法重放此处之前的记录")
    }

    fun insert(input: Game, draft: CorrectionDraft, index: Int, command: Command): CorrectionDraft {
        require(index in 0..draft.records.size)
        val before = stateBefore(input, draft, index)
        val at =
            command.at.takeIf { it > 0 }
                ?: draft.records.getOrNull(index)?.command?.at
                ?: (draft.records.lastOrNull()?.command?.at?.plus(1) ?: System.currentTimeMillis())
        val record =
            RecordedCommand(
                UUID.randomUUID().toString(),
                command.copy(at = at),
                before.batter.id,
                before.bases,
                context = HistoryContext.capture(before),
                inserted = true,
            )
        return draft.copy(records = draft.records.toMutableList().apply { add(index, record) })
    }

    fun preview(input: Game, draft: CorrectionDraft): CorrectionPreview {
        if (input.id != draft.gameID || input.revision != draft.baseRevision)
            return CorrectionPreview(null, draft.records, emptyList(), conflict = "比赛已更新，请重新核对纠错草稿")
        return try {
            val initial = GameJson.decodeFromString<Game>(draft.checkpoint)
            val known = (initial.home.players + initial.away.players).map { it.id }.toSet()
            val additions = draft.addedHomePlayers + draft.addedAwayPlayers
            require(
                additions.map { it.id }.distinct().size == additions.size &&
                    additions.none { it.id in known }
            ) {
                "补记球员身份重复"
            }
            initial.home =
                initial.home.copy(players = initial.home.players + draft.addedHomePlayers)
            initial.away =
                initial.away.copy(players = initial.away.players + draft.addedAwayPlayers)
            val checkpoint = MatchEngine.checkpoint(initial)
            val result =
                run(input.copy(checkpoint = checkpoint), draft.records, draft.records.size, true)
            val game = result.game ?: return result
            require(!input.isFinal || game.isFinal) { "原比赛已经结束，更正后的结束依据不成立，请核对结束记录" }
            game.clockElapsedMs = input.clockElapsedMs
            game.clockRunningSince = input.clockRunningSince
            game.clockHasStarted = input.clockHasStarted
            game.playedAt = input.playedAt
            game.playedTimeZoneID = input.playedTimeZoneID
            game.dateRevisions = input.dateRevisions
            if (input.isFinal) game.endedAt = input.endedAt
            val changes = mutableListOf<String>()
            val ids = draft.records.map { it.id }.toSet()
            val oldIDs = input.journal.take(input.cursor).map { it.id }.toSet()
            input.journal
                .take(input.cursor)
                .filter { it.id !in ids }
                .forEach { changes += "删除记录：${title(it.command)}" }
            if (
                input.journal.take(input.cursor).map { it.id }.filter { it in ids } !=
                    draft.records.map { it.id }.filter { it in oldIDs }
            )
                changes += "已调整记录顺序，后续局面将重新核对"
            if (additions.isNotEmpty()) changes += "补全本场球员：${additions.joinToString{it.name}}"
            for (r in draft.records) {
                val old = input.journal.firstOrNull { it.id == r.id }
                if (old == null) changes += "补记：${title(r.command)}"
                else if (
                    old.command != r.command ||
                        old.additionalErrors != r.additionalErrors ||
                        old.statisticsRuling != r.statisticsRuling ||
                        r.acceptContext
                )
                    changes += "更正：${title(r.command)}"
            }
            if (input.awayRuns != game.awayRuns || input.homeRuns != game.homeRuns)
                changes +=
                    "比分 ${input.awayRuns}:${input.homeRuns} → ${game.awayRuns}:${game.homeRuns}"
            for (player in game.home.players + game.away.players) {
                val oldBat = input.batting[player.id] ?: BattingLine()
                val newBat = game.batting[player.id] ?: BattingLine()
                val oldPitch = input.pitching[player.id] ?: PitchingLine()
                val newPitch = game.pitching[player.id] ?: PitchingLine()
                val oldField = input.fielding[player.id] ?: FieldingLine()
                val newField = game.fielding[player.id] ?: FieldingLine()
                val label = "${player.name} #${player.number}"
                if (oldBat != newBat)
                    changes +=
                        "$label：打击 PA ${oldBat.plateAppearances}→${newBat.plateAppearances}，H ${oldBat.hits}→${newBat.hits}，R ${oldBat.runs}→${newBat.runs}，RBI ${oldBat.runsBattedIn}→${newBat.runsBattedIn}"
                if (oldPitch != newPitch)
                    changes +=
                        "$label：投球 ${oldPitch.pitches}→${newPitch.pitches}，失分 ${oldPitch.runs}→${newPitch.runs}，自责分 ${oldPitch.earnedRuns}→${newPitch.earnedRuns}"
                if (oldField != newField)
                    changes +=
                        "$label：守备 PO ${oldField.putouts}→${newField.putouts}，A ${oldField.assists}→${newField.assists}，E ${oldField.errors}→${newField.errors}"
            }
            if (input.situation() != game.situation())
                changes +=
                    "最终局面：${game.inning}局${if(game.isTop)"上"else"下"}，B ${game.balls} / S ${game.strikes} / O ${game.outs}"
            CorrectionPreview(game, result.records, changes)
        } catch (error: IllegalArgumentException) {
            CorrectionPreview(null, draft.records, emptyList(), conflict = error.message)
        } catch (error: IllegalStateException) {
            CorrectionPreview(null, draft.records, emptyList(), conflict = error.message)
        }
    }

    fun commit(input: Game, draft: CorrectionDraft): Game {
        require(draft.reason.isNotBlank()) { "请填写纠错原因" }
        val preview = preview(input, draft)
        check(preview.canSave) { preview.conflict ?: "纠错未完成" }
        require(preview.changes.isNotEmpty()) { "尚未修改记录" }
        val game = preview.game!!
        val revision =
            HistoryRevision(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                draft.reason.trim(),
                input.checkpoint ?: MatchEngine.checkpoint(input),
                input.journal.take(input.cursor),
                preview.records.map { it.id }.toSet(),
                preview.changes,
            )
        game.journal = preview.records
        game.cursor = preview.records.size
        game.correctionBoundary = game.cursor
        game.historyRevisions = input.historyRevisions + revision
        game.correctionRevisions = input.correctionRevisions + "${revision.at} · ${revision.reason}"
        game.revision = input.revision + 1
        game.validate()
        return game
    }

    fun revertingDraft(input: Game): CorrectionDraft {
        val last = input.historyRevisions.lastOrNull() ?: error("没有可撤回的更正")
        val later = input.journal.take(input.cursor).filter { it.id !in last.appliedOperationIDs }
        return CorrectionDraft(
            input.id,
            input.revision,
            last.previousCheckpoint,
            last.previousRecords + later,
            "撤回更正：${last.reason}",
        )
    }

    internal fun run(
        input: Game,
        records: List<RecordedCommand>,
        cursor: Int,
        strict: Boolean,
    ): CorrectionPreview {
        var state = GameJson.decodeFromString<Game>(input.checkpoint ?: error("缺少可重放的检查点"))
        val aliases = mutableMapOf<String, String>()
        val normalized = mutableListOf<RecordedCommand>()
        var previousBefore: Game? = null
        fun replacement(old: String, new: String) {
            aliases.keys.toList().filter { aliases[it] == old }.forEach { aliases[it] = new }
            aliases[old] = new
            aliases[new] = new
        }
        for ((index, record) in records.take(cursor).withIndex()) {
            try {
                val context = record.context
                if (strict && !record.inserted && !record.acceptContext) {
                    if (context != null) {
                        check(context.inning == state.inning && context.isTop == state.isTop) {
                            "原记录的局次已变化，请核对归属"
                        }
                        if (record.command.kind in batterCommands)
                            check(context.batterIndex == state.batterIndex) { "棒次发生变化，请核对实际打者" }
                    } else if (record.command.kind in batterCommands)
                        check(record.batterID == state.batter.id) { "打者发生变化，请核对归属" }
                }
                val beforeContext = HistoryContext.capture(state)
                val beforeBatter = state.batter.id
                val beforeBases = state.bases
                val beforeTop = state.isTop
                val beforeDefense = state.fieldingPositions.values.toSet()
                val beforeFieldTeam = state.fieldingTeam.players.map { it.id }.toSet()
                val beforeBattingTeam = state.battingTeam.players.map { it.id }.toSet()
                val beforeTotalRuns = state.homeRuns + state.awayRuns
                val beforeRBI = state.batting[state.batter.id]?.runsBattedIn ?: 0
                val beforePitch = state.pitching.mapValues { it.value.copy() }
                val oldPitcher = state.pitcher.id
                val original = record.command
                val beforeCancel =
                    if (
                        records.getOrNull(index + 1)?.command?.adjudication?.previousPlay ==
                            "cancel"
                    )
                        copy(state)
                    else null
                if (
                    original.kind == "adjudication" &&
                        original.adjudication?.previousPlay == "cancel"
                )
                    state = previousBefore ?: error("没有可取消的上一结果")
                fun participant(id: String): String {
                    val mapped = aliases[id] ?: id
                    if (
                        mapped in (state.homeOrder ?: state.home.players.map { it.id }) ||
                            mapped in (state.awayOrder ?: state.away.players.map { it.id }) ||
                            mapped in state.fieldingPositions.values
                    )
                        return mapped
                    val c = context ?: return mapped
                    val h = c.homeOrder.indexOf(id)
                    if (h >= 0)
                        return (state.homeOrder ?: state.home.players.map { it.id }).getOrElse(h) {
                            mapped
                        }
                    val a = c.awayOrder.indexOf(id)
                    return if (a >= 0)
                        (state.awayOrder ?: state.away.players.map { it.id }).getOrElse(a) {
                            mapped
                        }
                    else mapped
                }
                val decisions =
                    original.decisions?.map { d ->
                        if (d.origin == 0) d.copy(playerID = state.batter.id)
                        else {
                            check(
                                state.bases.getOrNull(d.origin - 1) ==
                                    (aliases[d.playerID] ?: d.playerID)
                            ) {
                                "跑者与补记后的垒况不同，请修改跑垒决定"
                            }
                            d.copy(playerID = state.bases[d.origin - 1]!!)
                        }
                    }
                var command = original.copy(decisions = decisions)
                if (original.kind in listOf("fielder", "position"))
                    command = command.copy(playerID = original.playerID?.let(::participant))
                if (original.kind == "lineup" && context != null) {
                    val home = original.homeSide ?: error("请核对阵容所属球队")
                    val sourceOrder = if (home) context.homeOrder else context.awayOrder
                    val actualOrder =
                        (if (home) state.homeOrder else state.awayOrder) ?: error("缺少当时打序")
                    val originalPositions =
                        if (home) context.homePositions else context.awayPositions
                    val actualPositions =
                        (if (home) state.homePositions else state.awayPositions)
                            ?: originalPositions
                    fun slot(id: String): String =
                        sourceOrder
                            .indexOf(id)
                            .takeIf { it >= 0 }
                            ?.let { actualOrder.getOrNull(it) } ?: id
                    val mappedPositions =
                        original.positions?.map { (position, id) ->
                            val actualID = slot(id)
                            val oldPosition =
                                originalPositions.entries.firstOrNull { it.value == id }?.key
                            val target =
                                if (position == oldPosition)
                                    actualPositions.entries
                                        .firstOrNull { it.value == actualID }
                                        ?.key ?: position
                                else position
                            target to actualID
                        }
                    require(
                        mappedPositions == null ||
                            mappedPositions.map { it.first }.distinct().size == mappedPositions.size
                    ) {
                        "补记影响此处守位，请重新核对阵容"
                    }
                    command =
                        command.copy(
                            order = original.order?.map(::slot),
                            positions = mappedPositions?.toMap(),
                            playerID = original.playerID?.let(::slot),
                            designatedHitterID = original.designatedHitterID?.let(::slot),
                        )
                }
                val outgoing =
                    when (original.kind) {
                        "batter" -> state.batter.id
                        "pinchRunner" -> state.bases.getOrNull((original.position ?: 1) - 1)
                        "fielder" -> command.playerID
                        "pitcher" -> oldPitcher
                        else -> null
                    }
                state = Session(state, command, record.id).run()
                val incoming =
                    if (original.kind == "fielder") original.replacementID else original.playerID
                if (
                    outgoing != null &&
                        incoming != null &&
                        (original.kind != "pitcher" || outgoing !in state.fieldingPositions.values)
                )
                    replacement(outgoing, incoming)
                for (credit in record.additionalErrors) {
                    require(credit.count in 1..9 && credit.playerID in beforeDefense) {
                        "失误责任人必须在本事件时实际参与守备"
                    }
                    state.fielding.getOrPut(credit.playerID) { FieldingLine() }.errors +=
                        credit.count
                    if (beforeTop) state.homeErrors += credit.count
                    else state.awayErrors += credit.count
                }
                record.statisticsRuling?.let { r ->
                    require(
                        original.outcome !in
                            listOf(Outcome.pending, Outcome.pendingOut, Outcome.other)
                    ) {
                        "请先确认实际击球结果，再复核统计责任"
                    }
                    val runs =
                        ((state.awayRuns + state.homeRuns) - beforeTotalRuns).coerceAtLeast(0)
                    require(r.runsBattedIn in 0..runs) { "打点不能超过本事件有效得分" }
                    state.batting.getOrPut(beforeBatter) { BattingLine() }.runsBattedIn =
                        beforeRBI + r.runsBattedIn
                    for ((pid, earned) in r.earnedRuns) {
                        val old = beforePitch[pid] ?: PitchingLine()
                        val line = state.pitching.getOrPut(pid) { PitchingLine() }
                        require(
                            pid in beforeFieldTeam &&
                                earned in 0..(line.runs - old.runs).coerceAtLeast(0)
                        ) {
                            "自责分超过该投手本事件失分"
                        }
                        line.earnedRuns = old.earnedRuns + earned
                    }
                    require(r.unearnedRunnerIDs.all { it in beforeBattingTeam }) {
                        "非自责跑者必须属于当时进攻方"
                    }
                    state.unearnedRunnerIDs += r.unearnedRunnerIDs
                }
                if (
                    (record.edited || record.inserted) &&
                        (original.outcome == Outcome.error ||
                            record.additionalErrors.isNotEmpty()) &&
                        record.statisticsRuling == null
                ) {
                    state.statisticsIncomplete = true
                    state.entries =
                        state.entries.dropLast(1) + state.entries.last().copy(incomplete = true)
                }
                normalized +=
                    record.copy(
                        context = beforeContext,
                        batterID = beforeBatter,
                        bases = beforeBases,
                        acceptContext = false,
                    )
                previousBefore = beforeCancel
            } catch (failure: IllegalArgumentException) {
                return CorrectionPreview(null, records, emptyList(), record.id, failure.message)
            } catch (failure: IllegalStateException) {
                return CorrectionPreview(null, records, emptyList(), record.id, failure.message)
            }
        }
        state.checkpoint = input.checkpoint
        state.journal = normalized + records.drop(cursor)
        state.cursor = cursor
        state.openingPitchRecorded = state.openingPitchRecorded || input.openingPitchRecorded
        state.historyRevisions = input.historyRevisions
        state.correctionRevisions = input.correctionRevisions
        state.correctionBoundary = input.correctionBoundary
        state.revision = input.revision + 1
        state.validate()
        return CorrectionPreview(state, state.journal, emptyList())
    }

    private val batterCommands =
        setOf(
            "pitch",
            "play",
            "illegal",
            "hitByPitch",
            "intentionalWalk",
            "foulBunt",
            "droppedThirdStrike",
            "batter",
        )
}
