package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SeasonStatisticsTest {
    @Test fun completedGamesRespectTeamYearModeAndSelectionAndKeepHistoricalPlayers() {
        val current = Player("one", "小明", "00")
        val old = Player("old", "旧球员", "7")
        val team = Team("ours", "春雷", listOf(current))
        val snapshot = team.copy(players = listOf(current, old))
        val rival = Team("rival", "海风", listOf(Player("r", "对手", "8")), isOpponent = true)
        fun game(id: String, mode: GameMode, year: Int, final: Boolean = true) = Game(
            id, rival, snapshot, Rules(mode), isFinal = final, ourTeamID = team.id,
            createdAt = Instant.parse("$year-06-01T12:00:00Z").toEpochMilli(),
            awayOrder = listOf(current.id, old.id),
            batting = mutableMapOf(current.id to BattingLine(atBats = 2, hits = 1), old.id to BattingLine(atBats = 1, hits = 1)),
            pitching = mutableMapOf(current.id to PitchingLine(outsRecorded = 3, strikeouts = 2)),
            fielding = mutableMapOf(old.id to FieldingLine(putouts = 1)),
            awayRuns = 3, homeRuns = 1,
        )
        val normal = game("normal", GameMode.standard, 2026)
        val coach = game("coach", GameMode.coachPitch, 2026)
        val otherYear = game("old-year", GameMode.standard, 2025)
        val ongoing = game("ongoing", GameMode.standard, 2026, false)
        val otherTeam = game("other-team", GameMode.standard, 2026).copy(ourTeamID = "another")
        val result = SeasonStatisticsCalculator.calculate(team, listOf(normal, coach, otherYear, ongoing, otherTeam), 2026)
        assertEquals(2, result.games.size); assertEquals(2, result.wins); assertEquals(6, result.runs)
        assertEquals(4, result.batting.hits); assertEquals(3, result.pitching.outsRecorded) // coach pitchers excluded
        assertEquals(2, result.players.first { it.player.id == old.id }.games)
        assertFalse(result.players.first { it.player.id == old.id }.currentRoster)
        assertEquals(1, SeasonStatisticsCalculator.calculate(team, listOf(normal, coach), 2026, GameMode.standard).games.size)
        assertEquals(listOf("coach"), SeasonStatisticsCalculator.calculate(team, listOf(normal, coach), 2026, selectedIDs = setOf("coach")).games.map { it.id })
        assertTrue(SeasonStatisticsCalculator.calculate(team, listOf(normal), 2026, selectedIDs = emptySet()).games.isEmpty())
    }
}
