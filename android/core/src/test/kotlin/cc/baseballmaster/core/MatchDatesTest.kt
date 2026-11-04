package cc.baseballmaster.core

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class MatchDatesTest {
    private val ours = Team("ours", "本队", listOf(Player("p", "球员", "00")))
    private val opponent = Team("opponent", "对手", listOf(Player("r", "对手球员", "1")), isOpponent = true)
    private fun game() = Game("date", opponent, ours, Rules(), ourTeamID = ours.id, isFinal = true)
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun effectiveDateUsesPlayedThenStartedThenScheduledThenKnownCreatedDate() {
        val game = game().copy(playedAt = 4L, startedAt = 3L, scheduledAt = 2L, createdAt = 1L)
        assertEquals(4L, MatchDates.effectivePlayedAt(game))
        assertEquals(3L, MatchDates.effectivePlayedAt(game.copy(playedAt = null)))
        assertEquals(2L, MatchDates.effectivePlayedAt(game.copy(playedAt = null, startedAt = null)))
        assertEquals(1L, MatchDates.effectivePlayedAt(game.copy(playedAt = null, startedAt = null, scheduledAt = null)))
        assertNull(MatchDates.effectivePlayedAt(game()))
        assertNull(MatchDates.effectivePlayedAt(game().copy(createdAt = -1L)))
        // An explicitly saved epoch timestamp is distinct from the unknown-createdAt sentinel.
        assertEquals(0L, MatchDates.effectivePlayedAt(game().copy(playedAt = 0L)))
    }

    @Test fun savedTimezoneControlsYearAndLabelIndependentlyOfViewingDeviceTimezone() {
        val boundary = game().copy(playedAt = time("2025-12-31T16:30:00Z"), playedTimeZoneID = "Asia/Shanghai")
        val western = boundary.copy(playedTimeZoneID = "America/Los_Angeles")
        val original = TimeZone.getDefault()
        try {
            for (deviceZone in listOf("Pacific/Honolulu", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                assertEquals(2026, MatchDates.year(boundary))
                assertEquals(2025, MatchDates.year(western))
                assertEquals("2026-01-01 00:30 · Asia/Shanghai", MatchDates.label(boundary))
                assertEquals("2025-12-31 08:30 · America/Los_Angeles", MatchDates.label(western))
                assertEquals(MatchDates.year(boundary), Performances.year(boundary))
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun unknownTimezoneOrDateDoesNotInventAStatisticYear() {
        val invalid = game().copy(playedAt = time("2026-01-01T00:00:00Z"), playedTimeZoneID = "not/a-zone")
        assertNull(MatchDates.localPlayedAt(invalid))
        assertNull(MatchDates.year(invalid))
        assertEquals("比赛时区未识别 · not/a-zone", MatchDates.label(invalid))
        assertNull(MatchDates.year(game()))
        assertEquals("比赛时间未记录", MatchDates.label(game()))
        assertTrue(SeasonStatisticsCalculator.calculate(ours, listOf(invalid, game()), 2026).games.isEmpty())
    }

    @Test fun scheduledFallbackAndDateCorrectionMoveTheSameMatchBetweenCorrectYears() {
        val legacy = game().copy(scheduledAt = time("2025-06-01T12:00:00Z"), createdAt = time("2026-01-01T12:00:00Z"))
        assertEquals(2025, MatchDates.year(legacy))
        assertEquals(2025, Performances.year(legacy))
        assertEquals(listOf(legacy.id), SeasonStatisticsCalculator.calculate(ours, listOf(legacy), 2025).games.map { it.id })
        assertTrue(SeasonStatisticsCalculator.calculate(ours, listOf(legacy), 2026).games.isEmpty())
        val corrected = legacy.copy(playedAt = time("2026-06-01T12:00:00Z"))
        assertEquals(listOf(corrected.id), SeasonStatisticsCalculator.calculate(ours, listOf(corrected), 2026).games.map { it.id })
        assertTrue(SeasonStatisticsCalculator.calculate(ours, listOf(corrected), 2025).games.isEmpty())
    }
}
