package cc.baseballmaster.core

data class PlayerStatisticsSelection(
    val eligibleGames: List<Game>,
    val selectedGames: List<Game>,
    val statistics: SeasonPlayerStatistics,
    val recent3: SeasonPlayerStatistics,
    val recent10: SeasonPlayerStatistics,
    val scope: ReportStatisticsScope,
)

/** Personal statistics use only selected completed games where this player participated. */
object PlayerStatisticsProjection {
    fun calculate(
        team: Team,
        player: Player,
        all: List<Game>,
        year: Int,
        mode: GameMode? = null,
        scopeIDs: Set<String>? = null,
        selectedIDs: Set<String>? = null,
    ): PlayerStatisticsSelection {
        val current = team.players.firstOrNull { it.id == player.id }
        val eligible = SeasonStatisticsCalculator.calculate(team, all, year, mode, scopeIDs).games
            .mapNotNull { game ->
                ReportStatisticsProjection.gameParticipants(game, team.id)
                    .firstOrNull { it.player.id == player.id }?.let { game to it }
            }
        val selected = eligible.filter { selectedIDs == null || it.first.id in selectedIDs }
        fun aggregate(records: List<Pair<Game, SeasonPlayerStatistics>>): SeasonPlayerStatistics {
            val row = SeasonPlayerStatistics(current ?: player, currentRoster = current != null, games = records.size)
            records.forEach { (_, source) ->
                addBatting(row.batting, source.batting)
                addPitching(row.pitching, source.pitching)
                addFielding(row.fielding, source.fielding)
            }
            return row
        }
        val selectedGames = selected.map { it.first }
        return PlayerStatisticsSelection(
            eligibleGames = eligible.map { it.first },
            selectedGames = selectedGames,
            statistics = aggregate(selected),
            recent3 = aggregate(selected.take(3)),
            recent10 = aggregate(selected.take(10)),
            scope = ReportStatisticsProjection.scope(selectedGames),
        )
    }
}
