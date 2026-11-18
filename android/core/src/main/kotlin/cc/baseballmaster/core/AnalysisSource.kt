package cc.baseballmaster.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class AnalysisScope(val title: String, val situationsTitle: String, val mvpTitle: String) {
    single("比赛分析", "关键局面", "本场 MVP"),
    season("全年比赛分析", "比赛走势", "年度 MVP"),
    filtered("筛选比赛分析", "比赛走势", "所选比赛 MVP"),
}

/** The wire model cannot carry a name, local identifier, timestamp, or free-form scoring note. */
@Serializable
@ConsistentCopyVisibility
data class AnalysisInput internal constructor(
    val scope: AnalysisScope,
    val games: List<AnalysisGame>,
    val players: List<AnalysisPlayer>,
)

@Serializable
@ConsistentCopyVisibility
data class AnalysisPlayer internal constructor(
    val id: String,
    val side: Int,
    val games: Int,
    val batting: List<Int>,
    val pitching: List<Int>? = null,
    val fielding: List<Int>,
)

@Serializable
@ConsistentCopyVisibility
data class AnalysisSituation internal constructor(
    val inning: Int,
    val top: Boolean,
    val kind: String,
    val batter: String? = null,
    val fielder: String? = null,
    val before: List<Int>? = null,
    val after: List<Int>? = null,
    val beforeScore: List<Int>? = null,
    val afterScore: List<Int>? = null,
    val pending: Boolean,
) {
    val importance: Int get() {
        val homeRun = if (kind == "HR") 7 else 0
        val before = beforeScore ?: return homeRun
        val after = afterScore ?: return homeRun
        val leadChanged = (before[0] - before[1]).compareTo(0) != (after[0] - after[1]).compareTo(0)
        val scored = (after.sum() - before.sum()).coerceAtLeast(0)
        return (if (leadChanged) 12 else 0) + scored * 5 + homeRun + (if (kind in setOf("DP", "TP")) 5 else 0)
    }
}

@Serializable
@ConsistentCopyVisibility
data class AnalysisGame internal constructor(
    val mode: GameMode,
    val ownHome: Boolean? = null,
    val away: List<Int>,
    val home: List<Int>,
    val totals: List<Int>,
    val incomplete: Boolean,
    val events: List<AnalysisSituation>,
)

/** The title, aliases, and team labels stay at the display boundary. */
class AnalysisSource private constructor(
    val input: AnalysisInput,
    val title: String,
    val subtitle: String,
    val metrics: List<Pair<String, String>>,
    val names: Map<String, String>,
    val teamNames: List<String>,
) {
    val id: String = UUID.randomUUID().toString()

    fun display(text: String): String = analysisSpacing(aliasPattern.replace(text) { names[it.value] ?: "球员" })

    companion object {
        fun single(game: Game): AnalysisSource {
            require(game.isFinal && !game.practice && !game.isScheduled) { "只能分析已结束的比赛。" }
            val aliases = linkedMapOf<String, String>()
            val labels = linkedMapOf<String, String>()
            val players = buildList {
                listOf(game.away, game.home).forEachIndexed { side, team ->
                    val rows = ReportStatisticsProjection.gameParticipants(game, team.id).associateBy { it.player.id }
                    for (player in team.players) {
                        val row = rows[player.id] ?: continue
                        if (player.id in aliases) continue
                        val alias = "P${size + 1}"
                        aliases[player.id] = alias
                        labels[alias] = (if (side == 0) "客队 " else "主队 ") + player.analysisName()
                        add(analysisPlayer(alias, side, row, game.pitching[player.id]?.takeUnless { game.rules.mode == GameMode.coachPitch }))
                    }
                }
            }
            val date = MatchDates.effectivePlayedAt(game)?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.of(game.playedTimeZoneID))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            return AnalysisSource(
                AnalysisInput(AnalysisScope.single, listOf(analysisGame(game, aliases, 18)), players),
                analysisSpacing("${game.away.shortName} ${game.awayRuns}–${game.homeRuns} ${game.home.shortName}"),
                date?.let { "$it · 已结束" } ?: "已结束",
                listOf("比分（客 / 主）" to "${game.awayRuns} / ${game.homeRuns}",
                    "安打 H（客 / 主）" to "${game.awayHits} / ${game.homeHits}",
                    "失误 E（客 / 主）" to "${game.awayErrors} / ${game.homeErrors}"),
                labels, listOf(game.away.shortName, game.home.shortName),
            )
        }

        fun season(team: Team, year: Int, summary: SeasonStatistics, filtered: Boolean): AnalysisSource {
            require(summary.games.isNotEmpty()) { "当前范围没有已结束的比赛。" }
            require(summary.games.size <= 1000) { "比赛数量超出分析上限，请缩小统计范围。" }
            val aliases = linkedMapOf<String, String>()
            val labels = linkedMapOf<String, String>()
            val participants = summary.players.filter { it.games > 0 }
            require(participants.size <= 200) { "球员数量超出分析上限，请缩小统计范围。" }
            val players = participants.sortedBy { it.player.id }.mapIndexed { index, row ->
                val alias = "P${index + 1}"
                aliases[row.player.id] = alias
                labels[alias] = row.player.analysisName()
                analysisPlayer(alias, 2, row, row.pitching.takeUnless { it == PitchingLine() })
            }
            val games = summary.games.asReversed().map { analysisGame(it, aliases, 3) }
            val scope = if (filtered) AnalysisScope.filtered else AnalysisScope.season
            return AnalysisSource(AnalysisInput(scope, games, players), analysisSpacing(team.name),
                "$year 年 · ${if (filtered) "所选" else "全年"} ${games.size} 场已结束比赛",
                listOf("比赛" to "${games.size}", "胜 / 负 / 平" to "${summary.wins} / ${summary.losses} / ${summary.ties}",
                    "得分" to "${summary.runs}", "失分" to "${summary.runsAllowed}"), labels, emptyList())
        }
    }
}

private val aliasPattern = Regex("P[1-9][0-9]{0,2}(?![0-9])")
private val spacingPattern = Regex("(?<=\\p{IsHan})(?=[\\p{IsLatin}0-9]|#[0-9])|(?<=[\\p{IsLatin}0-9])(?=\\p{IsHan})")
private fun analysisSpacing(text: String) = text.replace(spacingPattern, " ")

private fun Player.analysisName() = "${jerseyNumbers.ifEmpty { listOf(number) }.joinToString(" / ") { "#$it" }} $name"

private fun analysisPlayer(id: String, side: Int, row: SeasonPlayerStatistics, pitching: PitchingLine?) = AnalysisPlayer(
    id, side, row.games,
    row.batting.run { listOf(plateAppearances, atBats, hits, totalBases, runs, runsBattedIn, walks, hitByPitch, strikeouts, stolenBases, caughtStealing) },
    pitching?.run { listOf(outsRecorded, battersFaced, hits, runs, earnedRuns, walks, strikeouts, pitches, strikes) },
    row.fielding.run { listOf(putouts, assists, errors, doublePlays) },
)

private fun analysisGame(game: Game, aliases: Map<String, String>, limit: Int): AnalysisGame {
    val commands = game.journal.associateBy { it.id }
    val events = game.entries.mapIndexedNotNull { index, event ->
        event.analysisSituation(aliases, commands[event.id]?.command)?.let { IndexedValue(index, it) }
    }.sortedWith(compareByDescending<IndexedValue<AnalysisSituation>> { it.value.importance }.thenBy { it.index })
        .take(limit).sortedBy { it.index }.map { it.value }
    return AnalysisGame(game.rules.mode, game.ourTeamID?.let { it == game.home.id },
        game.awayRunsByInning.take(game.inning.coerceAtLeast(1)), game.homeRunsByInning.take(game.inning.coerceAtLeast(1)),
        listOf(game.awayRuns, game.awayHits, game.awayErrors, game.homeRuns, game.homeHits, game.homeErrors),
        game.entries.isEmpty() || game.statisticsIncomplete || game.incompletePrefix || game.entries.any { it.needsReview }, events)
}

private val analysisNotations = setOf("1B", "2B", "3B", "HR", "GO", "FO", "LO", "FF", "IF", "E", "FC", "TAG", "SH", "SF", "DP", "TP",
    "BB", "IBB", "HBP", "K", "K-BUNT", "SB", "CS", "WP", "PB", "E-ADV", "BALK", "HALF-END", "END")

private val terminalPitchNotations = setOf("BB", "K", "ꓘ", "K-F", "PITCH-LIMIT")

private fun PitchEntry.analysisSituation(aliases: Map<String, String>, command: Command?): AnalysisSituation? {
    if (kind == "pitch" && notation !in terminalPitchNotations ||
        kind in setOf("clock", "lineup", "schedule", "date", "note", "comment") || notation == "PREGAME-LINEUP") return null
    val category = when (kind) {
        "play", "battedBall" -> "battedBall"
        "pitch" -> if (notation == "BB") "battedBall" else "out"
        "runner" -> "runner"
        "foulBunt", "droppedThirdStrike", "hitByPitch", "out" -> "out"
        "sub", "substitute", "substitution", "pitcher", "batter", "pinchRunner", "fielder", "position", "defense", "doubleSwitch" -> "substitution"
        "illegal", "adjudication", "ruling", "violation" -> "violation"
        "tiebreak", "tiebreakRunner", "extraInning", "enableTB", "placeTB" -> "tiebreak"
        "review", "correction" -> "correction"
        else -> "game"
    }
    val type = outcome?.notation?.takeIf { it in analysisNotations } ?: notation.takeIf { it in analysisNotations } ?: when (notation) {
        "stolenBase", "delayedSteal" -> "SB"
        "caughtStealing" -> "CS"
        "wildPitch" -> "WP"
        "passedBall" -> "PB"
        "defensiveErrorAdvance" -> "E-ADV"
        "balk" -> "BALK"
        else -> category
    }
    val fielder = command?.runnerErrorResponsibility?.fielderID?.takeIf { type == "E-ADV" }
    val batter = batterID?.takeIf { category in setOf("battedBall", "runner", "out", "violation") }
    // iOS records the terminal pitch first and then the resulting walk/strikeout.
    val beforeResult = before?.let { situation ->
        when {
            kind == "pitch" && notation == "BB" -> situation.copy(balls = 4)
            kind == "pitch" && notation in setOf("K", "ꓘ") -> situation.copy(strikes = 3)
            else -> situation
        }
    }
    return AnalysisSituation(inning, isTop, type, batter?.let(aliases::get), fielder?.let(aliases::get),
        beforeResult?.analysisState(), after?.analysisState(), before?.analysisScore(), after?.analysisScore(), needsReview)
}

private fun Situation.analysisState() = listOf(inning, if (isTop) 1 else 0, outs,
    bases.foldIndexed(0) { index, mask, runner -> if (runner == null) mask else mask or (1 shl index) }, balls, strikes)

private fun Situation.analysisScore() = listOf(awayScore, homeScore)
