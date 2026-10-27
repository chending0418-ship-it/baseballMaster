package cc.baseballmaster.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryTest {
    @Test
    fun selectedTeamRecordUsesActualSideCalendarYearAndCompletedGamesOnly() {
        val players = (1..10).map { Player("p$it", "球员$it", "$it") }
        val own = Team("own", "春雷", players)
        val other =
            Team("other", "海风", players.map { it.copy(id = "op-${it.id}") }, isOpponent = true)
        val current = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        val prior = Instant.parse("2025-06-01T00:00:00Z").toEpochMilli()
        val home = MatchFactory.create("home", own, other, Rules(), own.id)
        val away = MatchFactory.create("away", other, own, Rules(), own.id)
        val win = home.copy(isFinal = true, homeRuns = 3, awayRuns = 1, playedAt = current)
        val loss = away.copy(isFinal = true, homeRuns = 3, awayRuns = 1, playedAt = current)
        val tie =
            away.copy(id = "tie", isFinal = true, homeRuns = 2, awayRuns = 2, playedAt = current)
        val wrongYear = win.copy(id = "old", playedAt = prior)
        val ongoing = win.copy(id = "ongoing", isFinal = false)
        val practice = win.copy(id = "practice", practice = true)
        val otherTeam = win.copy(id = "foreign", ourTeamID = other.id)
        val games = listOf(win, loss, tie, wrongYear, ongoing, practice, otherTeam)

        assertEquals(HomeRecord(1, 1, 1), HomeSummary.record(games, own.id, 2026))
        assertEquals("1胜 1负 1平", HomeSummary.record(games, own.id, 2026).label)
        assertEquals("1胜 0负", HomeSummary.record(games, own.id, 2025).label)
        assertEquals("暂无战绩", HomeSummary.record(games, null, 2026).label)
        assertEquals("暂无战绩", HomeSummary.record(games, "missing", 2026).label)
        assertEquals(
            "1胜 0负",
            HomeSummary.record(listOf(win.copy(ourTeamID = null)), own.id, 2026).label,
        )
        val ambiguous = win.copy(ourTeamID = null, away = other.copy(isOpponent = false))
        assertEquals("暂无战绩", HomeSummary.record(listOf(ambiguous), own.id, 2026).label)
    }
}
