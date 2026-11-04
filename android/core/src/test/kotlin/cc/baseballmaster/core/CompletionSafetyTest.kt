package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class CompletionSafetyTest {
    private fun seed(
        mode: GameMode = GameMode.standard,
        dh: Boolean = false,
        two: Boolean = false,
    ): Game {
        fun t(id: String) = Team(id, id, (1..13).map { Player("$id-$it", "同名球员", it.toString()) })
        return MatchFactory.create(
            "completion",
            t("home"),
            t("away"),
            Rules(mode, designatedHitter = dh, twoWayPlayer = two),
        )
    }

    @Test
    fun rosterKeepsDistinctPeopleAndOriginalZeroLabels() {
        val players = Roster.parse("陈明,Alex Chen,0 / 00\n陈明,Chris Chen,7")
        assertNotEquals(players[0].id, players[1].id)
        assertEquals(listOf("0", "00"), players[0].jerseyNumbers)
        assertEquals("Alex Chen", players[0].englishName)
        for (row in listOf("陈明,01", "陈明,00 / 00", "陈明,100", "陈明,", " ,")) try {
            Roster.parse(row)
            fail("Invalid roster accepted: $row")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun expandedSlowPitchOrderIsFrozenAfterPlayBallAndHasNoFakeStartingPitches() {
        var g = seed(GameMode.slowPitch)
        g =
            MatchEngine.apply(
                g,
                Command(
                    "lineup",
                    homeSide = false,
                    order = g.away.players.take(12).map { it.id },
                    positions = g.awayPositions,
                ),
            )
        assertEquals(12, g.awayOrder!!.size)
        assertEquals(0, g.totalPitches)
        g = MatchEngine.apply(g, Command("clock", text = "start", at = 1000))
        val before = GameJson.encodeToString(g)
        try {
            MatchEngine.apply(
                g,
                Command(
                    "lineup",
                    homeSide = false,
                    order = g.away.players.take(13).map { it.id },
                    positions = g.awayPositions,
                ),
            )
            fail("Order grew after Play Ball")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, GameJson.encodeToString(g))
        g = MatchEngine.apply(g, Command("play", outcome = Outcome.homeRun, at = 2000))
        assertEquals(1, g.balls)
        assertEquals(1, g.strikes)
        assertEquals(1, g.totalPitches)
    }

    @Test
    fun clockControlsDoNotBecomeScoringUndoAndFirstPitchKeepsClockStart() {
        var g = seed()
        g = MatchEngine.apply(g, Command("clock", text = "start", at = 1000))
        assertFalse(g.canUndo)
        g = MatchEngine.apply(g, Command("pitch", pitch = PitchAction.ball, at = 2000))
        g = MatchEngine.apply(g, Command("clock", text = "pause", at = 5000))
        val undone = MatchEngine.undo(g)
        assertEquals(0, undone.balls)
        assertEquals(1000L, undone.clockRunningSince)
        val redone = MatchEngine.redo(undone)
        assertEquals(1, redone.balls)
        assertNull(redone.clockRunningSince)
        assertEquals(4000L, redone.clockElapsedMs)
    }

    @Test
    fun positionCannotSilentlyPutBenchPlayerOnFieldAndInvalidDoubleSwitchIsAtomic() {
        val g = seed()
        val original = GameJson.encodeToString(g)
        for (c in
            listOf(
                Command("position", playerID = "home-12", position = 4),
                Command(
                    "doubleSwitch",
                    playerID = "home-1",
                    replacementID = "home-10",
                    position = 1,
                    secondPlayerID = "home-2",
                    secondReplacementID = "home-10",
                    secondPosition = 2,
                ),
            )) try {
            MatchEngine.apply(g, c)
            fail("Invalid assignment accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(original, GameJson.encodeToString(g))
        val two = seed(dh = true, two = true)
        val changed = MatchEngine.apply(two, Command("position", playerID = "home-1", position = 9))
        assertNull(changed.homeDesignatedHitterID)
        assertEquals("home-9", changed.pitcher.id)
    }

    @Test
    fun manualSituationCannotGuessPitcherFromBenchOrDuplicateRunner() {
        val g = seed()
        val state = g.situation().copy(bases = listOf("away-2", "away-2", null))
        try {
            MatchEngine.apply(g, Command("situation", situation = state))
            fail("Duplicate runner accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            MatchEngine.apply(
                g,
                Command("situation", situation = g.situation().copy(pitcherID = "home-12")),
            )
            fail("Bench pitcher invented")
        } catch (_: IllegalArgumentException) {}
        assertTrue(g.batting.isEmpty())
        assertEquals(0, g.totalPitches)
    }

    @Test fun unthrownIllegalNeverInventsClockOrPitchAndInvalidTerminalResultIsAtomic(){
        val g=seed(GameMode.slowPitch)
        val noPitch=MatchEngine.apply(g,Command("illegal",wasThrown=false,at=1000))
        assertEquals(0,noPitch.totalPitches);assertFalse(noPitch.clockHasStarted);assertNull(noPitch.playedAt)
        val penalty=MatchEngine.apply(g,Command("illegal",pitch=PitchAction.ball,wasThrown=false,at=1000))
        assertEquals(2,penalty.balls);assertEquals(0,penalty.totalPitches);assertFalse(penalty.clockHasStarted)
        for(c in listOf(Command("illegal",outcome=Outcome.single,wasThrown=false),Command("illegal",pitch=PitchAction.calledStrike,wasThrown=false)))try{MatchEngine.apply(g,c);fail("Unthrown ball generated an actual result")}catch(_:IllegalArgumentException){}
    }

    @Test
    fun everyModeHasReversibleWholeInningAndPracticeStaysTransient() {
        for (mode in GameMode.entries) {
            var g = seed(mode)
            repeat(3) { g = MatchEngine.apply(g, Command("play", outcome = Outcome.groundOut)) }
            assertFalse(g.isTop)
            assertEquals(0, g.outs)
            val old = MatchEngine.undo(g)
            assertTrue(old.isTop)
            assertEquals(2, old.outs)
            assertEquals(g.pitching, MatchEngine.redo(old).pitching)
            val practice = g.copy(practice = true)
            assertEquals(
                1,
                Performances.calculate(practice, listOf(g), practice.home.players.first().id, true)
                    .gameCount,
            )
        }
    }
}
