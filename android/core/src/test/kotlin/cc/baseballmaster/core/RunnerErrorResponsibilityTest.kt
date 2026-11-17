package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RunnerErrorResponsibilityTest {
    private fun seed(): Game {
        fun team(name: String, opponent: Boolean) = Team(
            UUID.randomUUID().toString(), name,
            (1..12).map { Player(UUID.randomUUID().toString(), "球员$it", "$it", position = (it - 1) % 9 + 1) },
            isOpponent = opponent,
        )
        val home = team("主队", true)
        val away = team("客队", false)
        return MatchFactory.create(UUID.randomUUID().toString(), home, away, Rules(), away.id)
    }

    private fun twoRunners(seed: Game): Game {
        val single = Command("play", outcome = Outcome.single)
        return MatchEngine.apply(MatchEngine.apply(seed, single), single)
    }

    private fun runnerError(game: Game, responsibility: RunnerErrorResponsibility?) = Command(
        "runner", runnerKind = "defensiveErrorAdvance",
        decisions = listOf(Decision(game.bases[0]!!, 1, 2), Decision(game.bases[1]!!, 2, 4)),
        runnerErrorResponsibility = responsibility,
    )

    @Test
    fun pendingAndConfirmedCreditsMatchIOSForBothDefendingSides() {
        for (top in listOf(true, false)) {
            val opening = seed()
            val before = twoRunners(if (top) opening else MatchEngine.apply(opening, Command("endHalf")))
            val pitcher = before.pitcher.id
            val outgoing = before.fieldingPositions.getValue(5)
            val incoming = before.fieldingTeam.players.last().id
            val afterSubstitution = MatchEngine.apply(before, Command("fielder", playerID = outgoing, replacementID = incoming))
            val invalid = runnerError(afterSubstitution, RunnerErrorResponsibility(outgoing))
            val original = GameJson.encodeToString(afterSubstitution)
            assertThrows(IllegalArgumentException::class.java) { MatchEngine.apply(afterSubstitution, invalid) }
            assertEquals(original, GameJson.encodeToString(afterSubstitution))

            val pending = MatchEngine.apply(afterSubstitution, runnerError(afterSubstitution, RunnerErrorResponsibility()))
            fun teamErrors(game: Game) = if (top) game.homeErrors else game.awayErrors
            assertEquals(1, teamErrors(pending))
            assertEquals(0, if (top) pending.awayErrors else pending.homeErrors)
            assertEquals(1, pending.pitching.getValue(pitcher).runs)
            assertEquals(0, pending.pitching.getValue(pitcher).earnedRuns)
            assertNull(pending.fielding[incoming])
            assertTrue(pending.entries.last().incomplete)

            val undone = MatchEngine.undo(pending)
            assertEquals(0, teamErrors(undone))
            val redone = MatchEngine.redo(undone)
            assertEquals(1, teamErrors(redone))
            assertEquals(pending.situation(), redone.situation())

            val draft = History.draft(redone)
            val event = draft.records.last()
            val attributed = event.copy(command = event.command.copy(
                runnerErrorResponsibility = RunnerErrorResponsibility(incoming),
            ), edited = true)
            val correction = draft.copy(records = draft.records.dropLast(1) + attributed, reason = "确认失误责任人")
            val duplicate = correction.copy(records = draft.records.dropLast(1) + attributed.copy(
                additionalErrors = listOf(ErrorCredit(incoming)),
            ))
            assertEquals(event.id, History.preview(redone, duplicate).conflictID)
            val saved = History.commit(redone, correction)
            val reopened = GameJson.decodeFromString<Game>(GameJson.encodeToString(saved))
            assertEquals(1, teamErrors(reopened))
            assertEquals(pending.situation(), reopened.situation())
            assertEquals(1, reopened.fielding.getValue(incoming).errors)
            assertNull(reopened.fielding[outgoing])
            assertFalse(reopened.entries.last().incomplete)
            assertTrue(reopened.entries.last().text.contains("三垒"))
            assertEquals(0, reopened.pitching.getValue(pitcher).earnedRuns)
        }
    }

    @Test
    fun legacyRunnerErrorKeepsOriginalTotalsWhenReloadedAndReplayed() {
        val before = twoRunners(seed())
        val command = runnerError(before, null)
        val bytes = GameJson.encodeToString(command)
        assertFalse(bytes.contains("runnerErrorResponsibility"))
        val recorded = MatchEngine.apply(before, GameJson.decodeFromString<Command>(bytes))
        val replayed = MatchEngine.redo(MatchEngine.undo(recorded))
        assertEquals(0, replayed.homeErrors)
        assertEquals(1, replayed.pitching.getValue(before.pitcher.id).earnedRuns)
        assertTrue(replayed.entries.last().incomplete)
    }

    @Test
    fun iosWireRoundTripKeepsPendingAndConfirmedRunnerErrors() {
        val before = twoRunners(seed())
        for (responsibility in listOf(RunnerErrorResponsibility(), RunnerErrorResponsibility(before.fieldingPositions.getValue(5)))) {
            val recorded = MatchEngine.apply(before, runnerError(before, responsibility))
            val encoded = AppleExportPrototype.encode(recorded)
            val game = AppleBackupEnvelope.inspect(encoded).snapshot.getValue("games").jsonArray.single().jsonObject
            val replayed = AppleReplayPrototype.replay(game)
            assertEquals(recorded.situation(), replayed.situation())
            assertEquals(1, replayed.homeErrors)
            assertEquals(recorded.fielding, replayed.fielding)
            assertEquals(responsibility, replayed.journal.last().command.runnerErrorResponsibility)
        }
    }
}
