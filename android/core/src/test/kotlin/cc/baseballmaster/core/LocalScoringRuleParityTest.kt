package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class LocalScoringRuleParityTest {
    @Test
    fun coachPitchLimitCompletesTheAppearanceAfterFoulOrHitByPitch() {
        for (finishingPitch in listOf("pitch", "hitByPitch")) {
            val initial = seed(Rules(GameMode.coachPitch, coachPitchLimit = 3))
            val batter = initial.batter.id
            val first = MatchEngine.apply(initial, Command("hitByPitch"))
            assertEquals(1, first.appearancePitches)
            assertEquals("HBP-CONTINUE", first.entries.last().notation)
            val second = MatchEngine.apply(first, Command("pitch", pitch = PitchAction.ball))
            assertEquals(2, second.appearancePitches)
            assertEquals(0, second.balls)
            val done = MatchEngine.apply(
                second,
                Command(finishingPitch, pitch = PitchAction.foul.takeIf { finishingPitch == "pitch" }),
            )
            assertEquals(3, done.totalPitches)
            assertEquals(0, done.appearancePitches)
            assertEquals(1, done.outs)
            assertEquals(0, done.awayRuns)
            assertNotEquals(batter, done.batter.id)
            assertEquals(BattingLine(plateAppearances = 1, atBats = 1), done.batting.getValue(batter))
            assertTrue(done.pitching.isEmpty())
            assertEquals("PITCH-LIMIT", done.entries.last().notation)
            assertEquals(done.situation(), MatchEngine.redo(MatchEngine.undo(done)).situation())
        }
    }

    @Test
    fun slowPitchFoulPolicyUsesOnlyTheActualPitchesAndSurvivesReload() {
        for (extraFoul in listOf(true, false)) {
            val game = seed(Rules(GameMode.slowPitch, oneExtraFoul = extraFoul))
            val pitcher = game.pitcher.id
            val foul = Command("pitch", pitch = PitchAction.foul)
            assertEquals(1, game.balls)
            assertEquals(1, game.strikes)
            assertEquals(0, game.totalPitches)
            val twoStrikes = MatchEngine.apply(game, foul)
            assertEquals(2, twoStrikes.strikes)
            assertFalse(twoStrikes.extraFoulUsed)
            var after = MatchEngine.apply(twoStrikes, foul)
            assertEquals(if (extraFoul) 0 else 1, after.outs)
            if (extraFoul) {
                assertTrue(after.extraFoulUsed)
                val reloaded = GameJson.decodeFromString<Game>(GameJson.encodeToString(after))
                assertTrue(reloaded.extraFoulUsed)
                assertEquals(90, reloaded.rules.timeLimitMinutes)
                after = MatchEngine.apply(reloaded, foul)
            }
            val pitches = if (extraFoul) 3 else 2
            assertEquals(pitches, after.totalPitches)
            assertEquals(pitches, after.pitching.getValue(pitcher).pitches)
            assertEquals(1, after.pitching.getValue(pitcher).strikeouts)
            assertEquals("K-F", after.entries.last().notation)
            assertEquals(1, after.balls)
            assertEquals(1, after.strikes)
            assertFalse(after.extraFoulUsed)
        }
    }

    @Test
    fun standardLoadedWalkAndBothThirdStrikesCreditOneActualPitchPerAction() {
        val game = seed()
        val pitcher = game.pitcher.id
        val loaded = (1..3).fold(game) { state, _ ->
            MatchEngine.apply(state, Command("play", outcome = Outcome.single))
        }
        val scorer = loaded.bases[2]!!
        val batter = loaded.batter.id
        var after = loaded
        repeat(4) { after = MatchEngine.apply(after, Command("pitch", pitch = PitchAction.ball)) }
        assertEquals(1, after.awayRuns)
        assertEquals(1, after.batting.getValue(scorer).runs)
        assertEquals(1, after.batting.getValue(batter).runsBattedIn)
        assertEquals(1, after.batting.getValue(batter).walks)
        assertEquals(1, after.pitching.getValue(pitcher).runs)
        assertEquals(1, after.pitching.getValue(pitcher).earnedRuns)
        assertEquals(7, after.pitching.getValue(pitcher).pitches)
        for (third in listOf(PitchAction.calledStrike, PitchAction.swingingStrike)) {
            val striker = after.batter.id
            repeat(2) { after = MatchEngine.apply(after, Command("pitch", pitch = PitchAction.calledStrike)) }
            after = MatchEngine.apply(after, Command("pitch", pitch = PitchAction.foul))
            assertEquals(2, after.strikes)
            assertNull(after.batting[striker])
            after = MatchEngine.apply(after, Command("pitch", pitch = third))
            assertEquals(1, after.batting.getValue(striker).strikeouts)
            assertEquals(if (third == PitchAction.calledStrike) "ꓘ" else "K", after.entries.last().notation)
        }
        assertEquals(2, after.outs)
        assertEquals(2, after.pitching.getValue(pitcher).strikeouts)
        assertEquals(2, after.pitching.getValue(pitcher).outsRecorded)
        assertEquals(15, after.pitching.getValue(pitcher).pitches)
    }

    @Test
    fun pitcherLimitUsesMoundConfirmationAndActualPitchesAcrossHalves() {
        val initial = seed(Rules(pitchLimit = 2, pitcherInningsLimit = 1))
        val starter = initial.pitcher.id
        var game = MatchEngine.apply(initial, Command("clock", text = "start"))
        assertEquals(mapOf(starter to setOf(1)), game.pitcherInnings)
        repeat(3) { game = MatchEngine.apply(game, Command("play", outcome = Outcome.groundOut)) }
        assertFalse(game.isTop)
        assertEquals(mapOf(starter to setOf(1)), game.pitcherInnings)
        assertEquals(3, game.pitching.getValue(starter).outsRecorded)
        game = MatchEngine.apply(game, Command("endHalf"))
        game = MatchEngine.apply(game, Command("clock", text = "pause"))
        game = MatchEngine.apply(game, Command("clock", text = "resume"))
        game = MatchEngine.apply(game, Command("note", text = "进入第二局"))
        assertEquals(2, game.inning)
        assertEquals(mapOf(starter to setOf(1)), game.pitcherInnings)
        val legacy = game.copy(
            pitcherInningUsageCurrent = false,
            pitcherInnings = mutableMapOf(starter to setOf(1, 2), game.awayPitcherID!! to setOf(1)),
        )
        assertFalse(legacy.ruleNotices(0).any { "超过 1 局限制" in it })

        val reliever = game.fieldingPositions.getValue(2)
        val changed = MatchEngine.apply(legacy, Command("pitcher", playerID = reliever))
        assertEquals(mapOf(starter to setOf(1), reliever to setOf(2)), changed.pitcherInnings)
        assertEquals(0, changed.pitching[reliever]?.pitches ?: 0)
        assertEquals(game.pitcherInnings, MatchEngine.undo(changed).pitcherInnings)
        assertEquals(changed.pitcherInnings, MatchEngine.redo(MatchEngine.undo(changed)).pitcherInnings)

        val returned = MatchEngine.apply(changed, Command("pitcher", playerID = starter))
        assertEquals(setOf(1, 2), returned.pitcherInnings[starter])
        assertEquals(setOf(2), returned.pitcherInnings[reliever])
        assertTrue(returned.ruleNotices(0).any { "已投 3 球" in it })
        assertTrue(returned.ruleNotices(0).any { "超过 1 局限制" in it })
        val next = MatchEngine.apply(returned, Command("pitch", pitch = PitchAction.ball))
        assertEquals(4, next.pitching.getValue(starter).pitches)
        assertEquals(3, next.pitching.getValue(starter).outsRecorded)
        assertFalse(next.isFinal)
    }

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
