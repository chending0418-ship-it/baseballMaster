package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class LocalScoringRuleParityTest {
    @Test
    fun basicAndProTimePlayKeepTheScoreAndPitcherLinesConsistentThroughReplay() {
        for (pro in listOf(false, true)) {
            var game = seed(Rules(recordingMethod = if (pro) "situation" else "traditional"))
            repeat(2) { game = MatchEngine.apply(game, Command("play", outcome = Outcome.groundOut)) }
            game = MatchEngine.apply(game, Command("play", outcome = Outcome.triple))
            val third = requireNotNull(game.bases[2])
            val single = Command("play", outcome = Outcome.single, decisions = listOf(
                Decision(third, 3, 0), Decision(game.batter.id, 0, 1),
            ))
            val before = MatchEngine.apply(game, single)
            val first = requireNotNull(before.bases[0])
            val pitcher = before.pitcher.id
            val batter = before.batter.id
            val observation = if (pro) Observation(type = "line", precision = "region", region = "infieldMiddle",
                fielding = Fielding(listOf(6, 4))) else null
            val timing = Command(
                "play", outcome = Outcome.runnerTagOut, observation = observation,
                decisions = listOf(Decision(third, 3, 4), Decision(first, 1, -1), Decision(batter, 0, 1)),
            )
            assertTrue(MatchEngine.needsTiming(before, timing))
            for (counts in listOf(true, false)) {
                val expected = if (counts) 1 else 0
                val result = MatchEngine.apply(before, timing.copy(timingRunCounts = counts))
                val replay = MatchEngine.redo(MatchEngine.undo(result))
                assertEquals(expected, replay.awayRuns)
                assertEquals(expected, replay.batting.getValue(batter).runsBattedIn)
                assertEquals(expected, replay.pitching.getValue(pitcher).runs)
                assertEquals(expected, replay.pitching.getValue(pitcher).earnedRuns)
                assertFalse(replay.isTop)
            }
            val batterNeverReachedFirst = timing.copy(outcome = Outcome.groundOut,
                decisions = listOf(Decision(third, 3, 4), Decision(first, 1, 0), Decision(batter, 0, -1)))
            assertFalse(MatchEngine.needsTiming(before, batterNeverReachedFirst))
            val forced = MatchEngine.apply(before, batterNeverReachedFirst)
            assertEquals(0, forced.awayRuns)
            assertEquals(0, forced.pitching.getValue(pitcher).runs)
        }
    }

    @Test
    fun routineBasicSingleDoesNotReadAStaleTimingDecision() {
        val before = MatchEngine.apply(seed(), Command("play", outcome = Outcome.triple))
        val after = MatchEngine.apply(before, Command("play", outcome = Outcome.single, timingRunCounts = false))
        assertEquals(1, after.awayRuns)
        assertEquals(1, after.pitching.getValue(before.pitcher.id).runs)
        assertEquals(1, after.batting.getValue(before.batter.id).runsBattedIn)
    }

    @Test
    fun delayedDeadFieldersChoiceCancelsTheOriginalSingleWithoutDuplicateStats() {
        val before = seed()
        val batter = before.batter.id
        val hit = MatchEngine.apply(before, Command("play", outcome = Outcome.single))
        val decision = Command(
            "adjudication", runnerKind = "obstruction",
            decisions = listOf(Decision(batter, 0, 1)),
            adjudication = Ruling(ballStatus = "delayedDead", previousPlay = "cancel",
                plateAppearance = "batterFirst", countsAsAtBat = true, isFinal = false, battingCredit = "fieldersChoice"),
        )
        val after = MatchEngine.apply(hit, decision)
        assertEquals(1, after.batting.getValue(batter).plateAppearances)
        assertEquals(1, after.batting.getValue(batter).atBats)
        assertEquals(0, after.batting.getValue(batter).hits)
        assertEquals(0, after.awayHits)
        assertEquals(0, after.totalPitches)
        assertEquals(batter, after.bases[0])
        val entry = after.entries.last()
        assertTrue(entry.text.contains("延迟死球"))
        assertTrue(entry.text.contains("野手选择"))
        assertEquals(Outcome.fieldersChoice, entry.outcome)
        assertTrue(entry.needsReview)
        assertEquals(1, MatchEngine.undo(after).awayHits)
        assertEquals(after.situation(), MatchEngine.redo(MatchEngine.undo(after)).situation())
    }

    @Test
    fun playingAtTheTimeLimitNeedsASeparateConfirmationAndReopenStaysPaused() {
        val initial = seed(Rules(timed = true, timeLimitMinutes = 1))
        val before = MatchEngine.apply(initial, Command("pitch", pitch = PitchAction.ball, at = 1_000))
        assertFalse(before.hasReachedTimeLimit(60_999))
        assertTrue(before.hasReachedTimeLimit(61_000))
        assertTrue(before.ruleNotices(61_000).any { it.contains("已到 1 分钟") })
        assertFalse(before.isFinal)
        val continued = MatchEngine.apply(before, Command("pitch", pitch = PitchAction.calledStrike, at = 61_000))
        assertEquals(1, continued.balls)
        assertEquals(1, continued.strikes)
        val finished = MatchEngine.apply(continued, Command("finish", text = "timeLimit", at = 62_000))
        assertEquals("timeLimit", finished.endReason)
        assertFalse(finished.requiresLegacyResumeConfirmation)
        val reopened = MatchEngine.apply(finished, Command("reopen", at = 63_000))
        assertFalse(reopened.isFinal)
        assertNull(reopened.clockRunningSince)
        assertEquals(continued.situation(), reopened.situation())

        val legacy = finished.copy(endedAt = null, endReason = null, checkpoint = null, journal = emptyList(), cursor = 0)
        assertTrue(legacy.requiresLegacyResumeConfirmation)
        assertThrows(IllegalArgumentException::class.java) { MatchEngine.apply(legacy, Command("reopen")) }
        assertFalse(MatchEngine.apply(legacy, Command("reopen", flag = true)).isFinal)
    }

    private fun seed(rules: Rules = Rules()): Game {
        fun team(name: String, opponent: Boolean) =
            Team(name, name, List(12) { index -> Player("$name-$index", "$name$index", "${index + 1}", position = index % 9 + 1) },
                isOpponent = opponent)
        return MatchFactory.create("match", team("Home", true), team("Away", false), rules, "Away")
    }
}
