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
        (games.mapNotNull { MatchDates.year(it) } + Year.now().value)
            .distinct().sortedDescending()

    fun calculate(team: Team, all: List<Game>, year: Int, mode: GameMode? = null, selectedIDs: Set<String>? = null): SeasonStatistics {
        val games = all.filter { g ->
            g.isFinal && !g.practice && !g.isScheduled && g.ourTeamID == team.id &&
                (g.home.id == team.id || g.away.id == team.id) && MatchDates.year(g) == year &&
                (mode == null || mode == g.rules.mode) && (selectedIDs == null || g.id in selectedIDs)
        }.sortedWith(compareByDescending<Game> { MatchDates.effectivePlayedAt(it) }.thenBy { it.id })
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
            for (participant in ReportStatisticsProjection.gameParticipants(game, team.id)) {
                val p = participant.player
                val row = players.getOrPut(p.id) { SeasonPlayerStatistics(p, currentRoster = false) }
                row.games++
                addBatting(row.batting, participant.batting)
                addPitching(row.pitching, participant.pitching)
                addFielding(row.fielding, participant.fielding)
            }
        }
        return SeasonStatistics(games, players.values.toList(), wins, losses, ties, runs, runsAllowed, pending, incomplete)
    }
}
