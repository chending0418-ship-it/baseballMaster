package cc.baseballmaster.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportCatcherStatisticsTest {
    @Test fun selectedMatchesAggregateOnePassedBallAndKeepLegacyAttributionUnknown() {
        fun team(id: String) = Team(id, id, (1..9).map { Player("$id-$it", "球员$it", "$it") })
        val home = team("home")
        val away = team("away")
        val time = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli()
        val start = MatchFactory.create("confirmed", home, away, Rules(), home.id)
        val catcherID = start.fieldingPositions.getValue(2)
        val catcher = home.players.first { it.id == catcherID }
        val receiverID = start.fieldingPositions.getValue(6)
        val before = listOf(Outcome.single, Outcome.single).fold(start) { game, result ->
            MatchEngine.apply(game, Command("play", outcome = result))
        }
        val moves = before.bases.mapIndexedNotNull { index, player ->
            player?.let { Decision(it, index + 1, index + 2) }
        }
        val pb = RunnerDefenseCredit.PassedBall(catcherID)
        val pbCommand = Command("runner", runnerKind = "passedBall",
            decisions = moves.mapIndexed { index, move -> if (index == 0) move.copy(runnerDefense = pb) else move })
        val advanced = MatchEngine.apply(before, pbCommand)
        val runner = requireNotNull(advanced.bases[2])
        val decisions = MatchEngine.suggestedRunner(advanced, "caughtStealing").map { decision ->
            if (decision.playerID == runner) decision.copy(
                destination = -1,
                runnerDefense = RunnerDefenseCredit.CaughtStealing(catcherID, receiverID, 4),
            ) else decision.copy(destination = 0)
        }
        val caught = MatchEngine.apply(advanced, Command("runner", runnerKind = "caughtStealing", decisions = decisions))
        val confirmed = caught.copy(isFinal = true, playedAt = time)
        val legacy = MatchEngine.apply(before, pbCommand.copy(decisions = moves)).let { game ->
            game.copy(id = "legacy", isFinal = true, playedAt = time - 5000)
        }

        val box = ReportStatisticsProjection.gameParticipants(confirmed, home.id)
            .first { it.player.id == catcherID }.fielding
        assertEquals(1, box.catcherCaughtStealing)
        assertEquals(1, box.passedBalls)
        assertEquals(1, box.assists)
        val matches = listOf(confirmed, legacy)
        val season = SeasonStatisticsCalculator.calculate(home, matches, 2026)
        assertEquals(1, season.fielding.catcherCaughtStealing)
        assertEquals(1, season.fielding.passedBalls)
        assertEquals(1, season.pending)
        val scoped = PlayerStatisticsProjection.calculate(home, catcher, matches, 2026, selectedIDs = setOf(legacy.id))
        assertEquals(listOf(legacy.id), scoped.selectedGames.map { it.id })
        assertNull(scoped.statistics.fielding.catcherCaughtStealing)
        assertNull(scoped.statistics.fielding.passedBalls)
        assertEquals(1, scoped.scope.pendingRecords)
        val personal = PlayerStatisticsProjection.calculate(home, catcher, matches, 2026, selectedIDs = setOf(confirmed.id))
        assertEquals(1, personal.statistics.fielding.catcherCaughtStealing)
        assertEquals(1, personal.statistics.fielding.passedBalls)
        assertEquals(0, personal.scope.pendingRecords)
    }
}
