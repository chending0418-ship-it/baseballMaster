package cc.baseballmaster.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PlayerStatisticsProjectionTest {
    private val player = Player("p", "球员", "00")
    private val bench = Player("bench", "未上场球员", "0")
    private val old = Player("old", "历史球员", "7")
    private val team = Team("ours", "本队", listOf(player, bench))
    private val rival = Team("rival", "同名对手", listOf(Player("r", "对手球员", "1")), isOpponent = true)

    private fun game(day: Int, mode: GameMode = GameMode.standard, year: Int = 2026) = Game(
        "game-$day-$year-${mode.name}", rival, team.copy(players = listOf(player, bench, old)), Rules(mode),
        ourTeamID = team.id, isFinal = true,
        playedAt = Instant.parse("$year-06-${day.toString().padStart(2, '0')}T12:00:00Z").toEpochMilli(),
        awayOrder = listOf(player.id), awayPositions = mapOf(1 to player.id),
        batting = mutableMapOf(player.id to BattingLine(plateAppearances = day * 2, atBats = day * 2, hits = day, runsBattedIn = day)),
        pitching = mutableMapOf(player.id to PitchingLine(outsRecorded = 3, strikeouts = 2)),
        fielding = mutableMapOf(player.id to FieldingLine(assists = day)),
    )

    @Test fun latestThreeAndTenUseSelectedReverseDateOrderAndAggregateRawCounts() {
        val games = (1..12).map { game(it) }
        val result = PlayerStatisticsProjection.calculate(team, player, games.reversed(), 2026)
        assertEquals(games.reversed().map { it.id }, result.eligibleGames.map { it.id })
        assertEquals(12, result.statistics.games)
        assertEquals(78, result.statistics.batting.hits)
        assertEquals(156, result.statistics.batting.atBats)
        assertEquals(0.5, result.statistics.batting.average, 0.0)
        assertEquals(3, result.recent3.games)
        assertEquals(33, result.recent3.batting.hits)
        assertEquals(10, result.recent10.games)
        assertEquals(75, result.recent10.batting.hits)
        val selected = PlayerStatisticsProjection.calculate(team, player, games, 2026,
            selectedIDs = setOf(games[0].id, games[3].id, games[6].id, games[9].id))
        assertEquals(listOf(games[9].id, games[6].id, games[3].id, games[0].id), selected.selectedGames.map { it.id })
        assertEquals(22, selected.statistics.batting.hits)
        assertEquals(21, selected.recent3.batting.hits)
        assertEquals(4, selected.recent10.games)
    }

    @Test fun inheritedScopeIsAnIndependentBoundaryFromModeYearAndPersonalSelection() {
        val one = game(1)
        val two = game(2)
        val coach = game(3, GameMode.coachPitch)
        val prior = game(4, year = 2025)
        val all = listOf(one, two, coach, prior)
        val inherited = setOf(one.id, coach.id, prior.id)
        val standard = PlayerStatisticsProjection.calculate(team, player, all, 2026, GameMode.standard,
            scopeIDs = inherited, selectedIDs = setOf(one.id, two.id, prior.id))
        assertEquals(listOf(one.id), standard.eligibleGames.map { it.id })
        assertEquals(listOf(one.id), standard.selectedGames.map { it.id })
        val allModes = PlayerStatisticsProjection.calculate(team, player, all, 2026, scopeIDs = inherited)
        assertEquals(listOf(coach.id, one.id), allModes.selectedGames.map { it.id })
        assertEquals(3, allModes.statistics.pitching.outsRecorded) // The coach game has no applicable pitcher line.
        assertFalse(allModes.scope.coachPitchOnly)
        val coachOnly = PlayerStatisticsProjection.calculate(team, player, all, 2026, GameMode.coachPitch, scopeIDs = inherited)
        assertEquals(PitchingLine(), coachOnly.statistics.pitching)
        assertTrue(coachOnly.scope.coachPitchOnly)
        val newYear = PlayerStatisticsProjection.calculate(team, player, all, 2025)
        assertEquals(listOf(prior.id), newYear.selectedGames.map { it.id })
    }

    @Test fun emptySelectionRetainsHistoricalIdentityAndDoesNotCountBenchAsParticipant() {
        val played = game(1).apply { batting[old.id] = BattingLine(atBats = 2, hits = 1) }
        val historical = PlayerStatisticsProjection.calculate(team, old, listOf(played), 2026)
        assertEquals(1, historical.statistics.games)
        assertFalse(historical.statistics.currentRoster)
        assertEquals(old, historical.statistics.player)
        val empty = PlayerStatisticsProjection.calculate(team, old, listOf(played), 2026, selectedIDs = emptySet())
        assertEquals(listOf(played.id), empty.eligibleGames.map { it.id })
        assertTrue(empty.selectedGames.isEmpty())
        assertEquals(0, empty.statistics.games)
        assertEquals(old, empty.statistics.player)
        assertFalse(empty.statistics.currentRoster)
        assertEquals(BattingLine(), empty.statistics.batting)
        assertEquals(0, empty.recent3.games)
        assertEquals(0, empty.recent10.games)
        assertTrue(PlayerStatisticsProjection.calculate(team, bench, listOf(played), 2026).eligibleGames.isEmpty())
    }

    @Test fun modeOnlyParentFilterStillBoundsBroaderPersonalModes() {
        val standard = game(1)
        val coach = game(2, GameMode.coachPitch)
        val all = listOf(standard, coach)
        val initialIDs = SeasonStatisticsCalculator.calculate(team, all, 2026, GameMode.standard).games.map { it.id }.toSet()
        val broadened = PlayerStatisticsProjection.calculate(team, player, all, 2026, scopeIDs = initialIDs)
        assertEquals(listOf(standard.id), broadened.eligibleGames.map { it.id })
        val coachOnly = PlayerStatisticsProjection.calculate(team, player, all, 2026, GameMode.coachPitch, scopeIDs = initialIDs)
        assertTrue(coachOnly.eligibleGames.isEmpty())
        assertEquals(0, coachOnly.statistics.games)
    }

    @Test fun legacyNullParticipationCountsZeroStatStartersButExplicitEmptyDoesNot() {
        val implicit = game(1).copy(awayOrder = null, awayPositions = null, batting = mutableMapOf(),
            pitching = mutableMapOf(), fielding = mutableMapOf())
        val season = SeasonStatisticsCalculator.calculate(team, listOf(implicit), 2026)
        assertEquals(1, season.players.first { it.player.id == bench.id }.games)
        assertEquals(1, season.players.first { it.player.id == old.id }.games)
        assertFalse(season.players.first { it.player.id == old.id }.currentRoster)
        assertEquals(1, PlayerStatisticsProjection.calculate(team, bench, listOf(implicit), 2026).statistics.games)
        val explicit = implicit.copy(awayOrder = emptyList(), awayPositions = emptyMap())
        assertEquals(0, SeasonStatisticsCalculator.calculate(team, listOf(explicit), 2026).players.first { it.player.id == bench.id }.games)
        assertTrue(PlayerStatisticsProjection.calculate(team, bench, listOf(explicit), 2026).eligibleGames.isEmpty())
    }

    @Test fun officialGameFilteringAndSameDateIDOrderMatchSeasonSummary() {
        val one = game(1)
        val sameDate = one.copy(id = "a-first-on-tie")
        val excluded = listOf(
            one.copy(id = "practice", practice = true), one.copy(id = "ongoing", isFinal = false),
            one.copy(id = "scheduled", isScheduled = true), one.copy(id = "observation", ourTeamID = null),
            one.copy(id = "other-team", ourTeamID = "different"),
            one.copy(id = "inconsistent-team", away = rival),
        )
        val result = PlayerStatisticsProjection.calculate(team, player, excluded + one + sameDate, 2026)
        assertEquals(listOf(sameDate.id, one.id), result.selectedGames.map { it.id })
        assertEquals(2, result.statistics.games)
        assertEquals(2, result.statistics.batting.hits)
    }

    @Test fun selectedScopeNoticesExcludeUnselectedIncompleteGamesAndDoNotMutateSavedLines() {
        val one = game(1)
        val pending = game(2).copy(statisticsIncomplete = true, entries = listOf(PitchEntry("pending", text = "待确认", incomplete = true)))
        val result = PlayerStatisticsProjection.calculate(team, player, listOf(one, pending), 2026, selectedIDs = setOf(one.id))
        assertEquals(ReportStatisticsScope(0, 0, false), result.scope)
        val all = PlayerStatisticsProjection.calculate(team, player, listOf(one, pending), 2026)
        assertEquals(ReportStatisticsScope(1, 1, false), all.scope)
        result.statistics.batting.hits = 99
        assertEquals(1, one.batting.getValue(player.id).hits)
        assertEquals(1, result.recent3.batting.hits)
    }
}
