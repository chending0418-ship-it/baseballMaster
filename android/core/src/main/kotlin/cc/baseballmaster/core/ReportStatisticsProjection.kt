package cc.baseballmaster.core

/** The exact selected games, not the entire season, determine report notices. */
data class ReportStatisticsScope(
    val incompleteGames: Int,
    val pendingRecords: Int,
    val coachPitchOnly: Boolean,
)

/** Platform-independent report inputs, following the iOS box-score participant policy. */
object ReportStatisticsProjection {
    fun scope(games: List<Game>) = ReportStatisticsScope(
        incompleteGames = games.count { it.statisticsIncomplete || it.incompletePrefix },
        pendingRecords = games.sumOf { game -> game.entries.count { it.incomplete } },
        coachPitchOnly = games.isNotEmpty() && games.all { it.rules.mode == GameMode.coachPitch },
    )

    fun eventDescription(event: PitchEntry, textOnly: Boolean = false): String {
        val review = event.reviewNote?.takeIf { it.isNotEmpty() }
        return event.text + (if (event.incomplete) "（待确认）" else "") +
            (review?.let { if (textOnly) "（复核：$it）" else "\n复核：$it" } ?: "")
    }

    /** Use recorded situations only: the current match pitcher is not historical evidence. */
    fun textAppearance(game: Game, appearance: ProjectedAppearance): List<String> {
        fun player(id: String?): String {
            if (id == null) return "未记录"
            return (game.home.players + game.away.players).firstOrNull { it.id == id }
                ?.let { "#${it.number} ${it.name}" } ?: "历史球员"
        }
        fun belongsToAppearance(state: Situation) = state.inning == appearance.inning && state.isTop == appearance.isTop
        val coachPitch = game.rules.mode == GameMode.coachPitch
        var pitcherID = appearance.events.firstOrNull()?.before?.pitcherID
        val descriptions = mutableListOf(if (coachPitch) "投手不适用（教练投手）" else "投手：${player(pitcherID)}")
        for (event in appearance.events) {
            val before = event.before
            val after = event.after
            if (!coachPitch && before != null && belongsToAppearance(before) && before.pitcherID != pitcherID) {
                descriptions += "投手：${player(pitcherID)} → ${player(before.pitcherID)}"
                pitcherID = before.pitcherID
            }
            var detail = eventDescription(event, textOnly = true)
            if (!coachPitch && before != null && after != null && belongsToAppearance(before) &&
                belongsToAppearance(after) && before.pitcherID != after.pitcherID) {
                detail += "（投手：${player(before.pitcherID)} → ${player(after.pitcherID)}）"
                pitcherID = after.pitcherID
            }
            descriptions += detail
        }
        return descriptions
    }

    fun gameParticipants(game: Game, teamID: String): List<SeasonPlayerStatistics> {
        val home = when (teamID) {
            game.home.id -> true
            game.away.id -> false
            else -> throw IllegalArgumentException("球队不属于这场比赛")
        }
        val team = if (home) game.home else game.away
        // Null participation fields are legacy defaults, not an explicitly empty lineup.
        val order = (if (home) game.homeOrder else game.awayOrder) ?: team.players.map { it.id }
        val defense = (if (home) game.homePositions else game.awayPositions)?.values
            ?: team.players.take(game.rules.fielders).map { it.id }
        val recorded = (game.batting.keys + game.pitching.keys + game.fielding.keys +
            game.exitedPlayers + defense).toSet()
        val ids = (order + team.players.filter { it.id in recorded }.map { it.id }).distinct()
        val roster = team.players.associateBy { it.id }
        return ids.mapNotNull { id ->
            roster[id]?.let { player ->
                SeasonPlayerStatistics(
                    player, currentRoster = true, games = 1,
                    batting = game.batting[id]?.copy() ?: BattingLine(),
                    pitching = if (game.rules.mode == GameMode.coachPitch) PitchingLine()
                        else game.pitching[id]?.copy() ?: PitchingLine(),
                    fielding = game.fielding[id]?.copy() ?: FieldingLine(),
                )
            }
        }
    }
}
