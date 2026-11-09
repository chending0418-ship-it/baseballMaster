package cc.baseballmaster.scorekeeper

import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.baseballmaster.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PitcherLimits100Test {
    private fun game(rules: Rules = Rules(pitchLimit = 3, pitcherInningsLimit = 1)): Game {
        fun team(key: String) = Team(key, key, (1..12).map { Player("$key-$it", "测试球员$it", "$it") })
        return Game("limits", team("主队"), team("客队"), rules)
    }

    private fun Game.apply(command: Command) = MatchEngine.apply(this, command)
    private fun Game.ball() = apply(Command("pitch", pitch = PitchAction.ball))
    private fun Game.nextHalf() = apply(Command("endHalf"))
    private fun Game.notices() = ruleNotices(System.currentTimeMillis())

    @Test fun customInputAcceptsFullwidthBoundsAndBlankAndRejectsInvalidText() {
        fun value(text: String) = RuleDraft(pitchLimit = text).build(GameMode.standard).pitchLimit
        assertEquals(3, value("3"))
        assertEquals(3, value("　３　"))
        assertEquals(1, value("1"))
        assertEquals(1_000_000, value("１００００００"))
        assertNull(value(""))
        for (text in listOf("0", "1000001", "3.5", "3球", "-3", "1 2", "1000000000"))
            assertThrows("Expected invalid: $text", IllegalStateException::class.java) { value(text) }
    }

    @Test fun thirdOutCountsForOutgoingPitcherAndCarriedPitcherDoesNotCountUntilConfirmed() {
        val initial = game()
        val a = initial.pitcher.id
        var g = initial.apply(Command("clock", text = "start"))
        assertEquals(setOf(1), g.pitcherInnings[a])
        repeat(9) { g = g.apply(Command("pitch", pitch = PitchAction.calledStrike)) }
        assertFalse(g.isTop)
        assertEquals(3, g.pitching.getValue(a).outsRecorded)
        assertNull(g.pitcherInnings[g.pitcher.id])
        g = g.nextHalf()
        assertEquals(2, g.inning)
        assertEquals(setOf(1), g.pitcherInnings[a])
        val b = g.fieldingPositions.getValue(2)
        g = g.apply(Command("pitcher", playerID = b))
        assertEquals(setOf(1), g.pitcherInnings[a])
        assertEquals(setOf(2), g.pitcherInnings[b])
        assertEquals(0, g.pitching[b]?.outsRecorded ?: 0)
        assertNull(MatchEngine.undo(g).pitcherInnings[b])
        assertEquals(g.pitcherInnings, MatchEngine.redo(MatchEngine.undo(g)).pitcherInnings)
        assertEquals(setOf(2), g.ball().ball().pitcherInnings[b])
    }

    @Test fun nonPitchRunnerEventDoesNotCountButPassedBallDoes() {
        var g = game().ball().nextHalf().nextHalf()
        val a = g.pitcher.id
        g = g.apply(Command("intentionalWalk"))
        val runner = requireNotNull(g.bases[0])
        g = g.apply(Command("runner", runnerKind = "stolenBase", decisions = listOf(Decision(runner, 1, 2))))
        assertEquals(setOf(1), g.pitcherInnings[a])
        g = g.apply(Command("runner", runnerKind = "passedBall", decisions = listOf(Decision(runner, 2, 3))))
        assertEquals(setOf(1, 2), g.pitcherInnings[a])
        assertEquals(setOf(1), MatchEngine.undo(g).pitcherInnings[a])
    }

    @Test fun bothLimitsAreAdvisoryAndOldUsageIsRebuiltWithoutInventingInnings() {
        var g = game().ball().ball().ball()
        val a = g.pitcher.id
        assertTrue(g.notices().any { "已达到 3 球限制" in it })
        assertTrue(g.notices().any { "已达到 1 局登板额度" in it })
        g = g.nextHalf().nextHalf()
        val stale = g.copy(pitcherInningUsageCurrent = false, pitcherInnings = mutableMapOf(a to setOf(1, 2)))
        val old = GameJson.decodeFromString<Game>(GameJson.encodeToString(stale))
        old.restorePitcherInningUsage()
        assertEquals(setOf(1), old.pitcherInnings[a])
        assertFalse(old.pitcherInningHistoryIncomplete)
        val incomplete = old.copy(pitcherInningUsageCurrent = false, pitcherInnings = mutableMapOf(a to setOf(1, 2)),
            entries = emptyList(), journal = emptyList(), cursor = 0)
        incomplete.restorePitcherInningUsage()
        assertTrue(incomplete.notices().any { "登板记录缺失" in it })
        assertNull(incomplete.pitcherInnings[a])

        g = old.ball()
        assertTrue(g.notices().any { "已投 4 球" in it })
        assertTrue(g.notices().any { "已确认登板 2 局" in it })
        val next = g.ball()
        assertEquals(5, next.totalPitches)
        assertFalse(next.isFinal)
        assertEquals(0, next.pitching.getValue(a).outsRecorded)
    }

    @Test fun coachPitchStillEndsTheAtBatAtItsSeparateLimit() {
        val g = game(Rules(GameMode.coachPitch, coachPitchLimit = 2, pitchLimit = 1, pitcherInningsLimit = 1))
            .ball().ball()
        assertEquals(1, g.outs)
        assertEquals(0, g.pitching.size)
        assertTrue(g.pitcherInnings.isEmpty())
        assertTrue(g.notices().none { "限制" in it || "登板" in it })
    }
}
