package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class ScheduledPlanningTest {
    private fun plan() = MatchFactory.schedule("plan", Team("home", "主队", emptyList()), Team("away", "客队", emptyList(), true), Rules(), 1_900_000_000_000, "home")
    @Test fun planningNeedsNoInventedPlayersAndRoundTrips() {
        val game = plan()
        assertTrue(game.scheduledSetupPending); assertNull(game.homeOrder); assertNull(game.homePitcherID)
        val copy = GameJson.decodeFromString<Game>(GameJson.encodeToString(game)); copy.validate()
        assertEquals(game, copy); assertTrue(copy.home.players.isEmpty()); assertEquals(0, copy.totalPitches)
    }
    @Test fun pendingPlansRejectEveryPlayingCommandAndHiddenGameState() {
        val game = plan()
        listOf(Command("activate"), Command("pitch", pitch = PitchAction.ball), Command("clock", text = "start"), Command("finish", text = "weather")).forEach {
            try { MatchEngine.apply(game, it); fail("pending plan must not enter scoring") } catch (_: IllegalArgumentException) { }
        }
        listOf(game.copy(totalPitches = 1), game.copy(isScheduled = false), game.copy(clockHasStarted = true), game.copy(playedAt = 10L)).forEach {
            try { it.validate(); fail("non-planning state must not hide inside a plan") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun defaultFlagDoesNotChangeExistingSerializedGames() {
        val game = Game("legacy", Team("h", "主", listOf(Player("p1", "甲", "1"))), Team("a", "客", listOf(Player("p2", "乙", "2")), true), Rules())
        game.validate()
        assertFalse(GameJson.encodeToString(game).contains("scheduledSetupPending"))
    }
}
