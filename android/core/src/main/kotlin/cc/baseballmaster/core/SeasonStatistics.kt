package cc.baseballmaster.core

import java.time.Year

/** Official local completed games only. Shared-team authority is added separately in AN05. */
data class SeasonPlayerStatistics(
    val player: Player,
    val currentRoster: Boolean,
    var games: Int = 0,
    val batting: BattingLine = BattingLine(),
    val pitching: PitchingLine = PitchingLine(),
    val fielding: FieldingLine = FieldingLine(),
)

data class SeasonStatistics(
    val games: List<Game>,
    val players: List<SeasonPlayerStatistics>,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val runs: Int,
    val runsAllowed: Int,
    val pending: Int,
    val incomplete: Int,
) {
    val batting: BattingLine get() = BattingLine().also { total -> players.forEach { addBatting(total, it.batting) } }
    val pitching: PitchingLine get() = PitchingLine().also { total -> players.forEach { addPitching(total, it.pitching) } }
    val fielding: FieldingLine get() = FieldingLine().also { total -> players.forEach { addFielding(total, it.fielding) } }
}

fun addBatting(a: BattingLine, b: BattingLine) {
    a.plateAppearances += b.plateAppearances; a.atBats += b.atBats; a.runs += b.runs
    a.hits += b.hits; a.doubles += b.doubles; a.triples += b.triples; a.homeRuns += b.homeRuns
    a.runsBattedIn += b.runsBattedIn; a.walks += b.walks; a.hitByPitch += b.hitByPitch
    a.strikeouts += b.strikeouts; a.stolenBases += b.stolenBases; a.caughtStealing += b.caughtStealing
    a.sacrifices += b.sacrifices
}
fun addPitching(a: PitchingLine, b: PitchingLine) {
    a.outsRecorded += b.outsRecorded; a.battersFaced += b.battersFaced; a.hits += b.hits
    a.runs += b.runs; a.earnedRuns += b.earnedRuns; a.walks += b.walks; a.hitByPitch += b.hitByPitch
    a.strikeouts += b.strikeouts; a.wildPitches += b.wildPitches; a.pitches += b.pitches; a.strikes += b.strikes
}
fun addFielding(a: FieldingLine, b: FieldingLine) {
    a.putouts += b.putouts; a.assists += b.assists; a.errors += b.errors; a.doublePlays += b.doublePlays
}

object SeasonStatisticsCalculator {
    fun years(games: List<Game>): List<Int> =
        (games.mapNotNull { runCatching { Performances.year(it) }.getOrNull() } + Year.now().value)
            .distinct().sortedDescending()

    fun calculate(team: Team, all: List<Game>, year: Int, mode: GameMode? = null, selectedIDs: Set<String>? = null): SeasonStatistics {
        val games = all.filter { g ->
            g.isFinal && !g.practice && !g.isScheduled && g.ourTeamID == team.id &&
                runCatching { Performances.year(g) }.getOrNull() == year &&
                (mode == null || mode == g.rules.mode) && (selectedIDs == null || g.id in selectedIDs)
        }.sortedWith(compareByDescending<Game> { it.playedAt ?: it.startedAt ?: it.scheduledAt ?: it.createdAt }.thenBy { it.id })
        val players = team.players.associate { it.id to SeasonPlayerStatistics(it, currentRoster = true) }.toMutableMap()
        var wins = 0; var losses = 0; var ties = 0; var runs = 0; var runsAllowed = 0; var pending = 0; var incomplete = 0
        for (game in games) {
            val our = when (team.id) { game.home.id -> game.home; game.away.id -> game.away; else -> continue }
            val home = our.id == game.home.id
            val ours = if (home) game.homeRuns else game.awayRuns
            val theirs = if (home) game.awayRuns else game.homeRuns
            runs += ours; runsAllowed += theirs
            when { ours > theirs -> wins++; ours < theirs -> losses++; else -> ties++ }
            pending += game.entries.count { it.incomplete }
            if (game.statisticsIncomplete || game.incompletePrefix) incomplete++
            val order = (if (home) game.homeOrder else game.awayOrder).orEmpty()
            val positions = (if (home) game.homePositions else game.awayPositions).orEmpty().values
            val participants = (order + positions + game.exitedPlayers + game.batting.keys + game.pitching.keys + game.fielding.keys).toSet()
            for (p in our.players.filter { it.id in participants }) {
                val row = players.getOrPut(p.id) { SeasonPlayerStatistics(p, currentRoster = false) }
                row.games++
                game.batting[p.id]?.let { addBatting(row.batting, it) }
                if (game.rules.mode != GameMode.coachPitch) game.pitching[p.id]?.let { addPitching(row.pitching, it) }
                game.fielding[p.id]?.let { addFielding(row.fielding, it) }
            }
        }
        return SeasonStatistics(games, players.values.toList(), wins, losses, ties, runs, runsAllowed, pending, incomplete)
    }
}
