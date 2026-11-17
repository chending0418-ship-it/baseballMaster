package cc.baseballmaster.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CatcherRunnerParityTest {
    @Test
    fun thirdOutCreditsTheCatcherAndReceiverFromThatHalf() {
        for (recordingMethod in listOf("traditional", "situation")) {
            val withRunner = plays(seed(recordingMethod), Outcome.groundOut, Outcome.groundOut, Outcome.single)
            val formerCatcher = withRunner.fieldingPositions.getValue(2)
            val catcher = withRunner.fieldingTeam.players.last().id
            val before = MatchEngine.apply(withRunner, Command("fielder", playerID = formerCatcher, replacementID = catcher))
            val receiver = before.fieldingPositions.getValue(6)
            val runner = requireNotNull(before.bases[0])
            val stale = caught(runner, -1, RunnerDefenseCredit.CaughtStealing(formerCatcher, receiver, 2))
            val original = GameJson.encodeToString(before)
            assertThrows(IllegalArgumentException::class.java) { MatchEngine.apply(before, stale) }
            assertEquals(original, GameJson.encodeToString(before))

            val confirmed = caught(runner, -1, RunnerDefenseCredit.CaughtStealing(catcher, receiver, 2))
            val after = MatchEngine.apply(before, confirmed)
            assertFalse(after.isTop)
            assertEquals(0, after.outs)
            assertEquals(1, after.batting.getValue(runner).caughtStealing)
            assertEquals(1, after.reportFielding.getValue(catcher).catcherCaughtStealing)
            assertEquals(1, after.fielding.getValue(catcher).assists)
            assertEquals(1, after.fielding.getValue(receiver).putouts)
            assertNull(after.reportFielding[formerCatcher]?.catcherCaughtStealing)
            assertTrue(after.entries.last().text.contains("2–6"))
            assertFalse(after.entries.last().needsReview)
            val undone = MatchEngine.undo(after)
            assertNull(undone.reportFielding[catcher]?.catcherCaughtStealing)
            val reopened = GameJson.decodeFromString<Game>(GameJson.encodeToString(MatchEngine.redo(undone)))
            assertEquals(after.reportFielding, reopened.reportFielding)
            assertEquals(after.situation(), reopened.situation())

            val noAssist = MatchEngine.apply(before, caught(runner, -1, RunnerDefenseCredit.NoCatcherAssist))
            assertFalse(noAssist.entries.last().needsReview)
            assertEquals(1, noAssist.batting.getValue(runner).caughtStealing)
            assertNull(noAssist.reportFielding[catcher]?.catcherCaughtStealing)
        }
    }

    @Test
    fun safeDropCountsCatcherAssistAndReceiverErrorAndLaterRunIsUnearned() {
        val before = plays(seed(), Outcome.single)
        val runner = requireNotNull(before.bases[0])
        val pitcher = before.pitcher.id
        val catcher = before.fieldingPositions.getValue(2)
        val receiver = before.fieldingPositions.getValue(6)
        val safe = MatchEngine.apply(before, caught(runner, 2, RunnerDefenseCredit.CaughtStealing(catcher, receiver, 2)))
        assertEquals(listOf(null, runner, null), safe.bases)
        assertEquals(0, safe.outs)
        assertEquals(1, safe.batting.getValue(runner).caughtStealing)
        assertEquals(1, safe.reportFielding.getValue(catcher).catcherCaughtStealing)
        assertEquals(1, safe.fielding.getValue(catcher).assists)
        assertEquals(0, safe.fielding.getValue(receiver).putouts)
        assertEquals(1, safe.fielding.getValue(receiver).errors)
        assertEquals(1, safe.homeErrors)
        assertTrue(runner in safe.unearnedRunnerIDs)
        val scored = MatchEngine.apply(safe, Command(
            "runner", runnerKind = "relayAdvance", decisions = listOf(Decision(runner, 2, 4)),
        ))
        assertEquals(1, scored.awayRuns)
        assertEquals(1, scored.pitching.getValue(pitcher).runs)
        assertEquals(0, scored.pitching.getValue(pitcher).earnedRuns)
    }

    @Test
    fun oldUnassignedPassedBallCanBeCorrectedOnceAndKeepsLaterWildPitch() {
        val before = plays(seed(), Outcome.single, Outcome.single)
        val pitcher = before.pitcher.id
        val catcher = before.fieldingPositions.getValue(2)
        val legacy = Command("runner", runnerKind = "passedBall", decisions = before.bases.mapIndexedNotNull { index, id ->
            id?.let { Decision(it, index + 1, index + 2) }
        })
        val source = GameJson.encodeToString(legacy)
        assertFalse(source.contains("runnerDefense"))
        val pending = MatchEngine.apply(before, GameJson.decodeFromString<Command>(source))
        assertTrue(pending.entries.last().needsReview)
        assertTrue(pending.entries.last().copy(incomplete = false).needsReview)
        assertNull(pending.reportFielding[catcher]?.passedBalls)
        val later = MatchEngine.apply(pending, Command("runner", runnerKind = "wildPitch"))
        val draft = History.draft(later)
        val index = draft.records.indexOfFirst { it.id == pending.entries.last().id }
        val target = draft.records[index]
        val corrected = target.copy(command = target.command.copy(
            decisions = requireNotNull(target.command.decisions).mapIndexed { moveIndex, decision ->
                if (moveIndex == 0) decision.copy(runnerDefense = RunnerDefenseCredit.PassedBall(catcher)) else decision
            },
        ), edited = true)
        val change = draft.copy(
            reason = "捕逸责任归当时的捕手",
            records = draft.records.toMutableList().apply { this[index] = corrected },
        )
        val preview = History.preview(later, change)
        assertTrue(preview.conflict.orEmpty(), preview.canSave)
        assertTrue(preview.changes.any { it.contains("PB") || it.contains("捕逸") })
        val saved = History.commit(later, change)
        assertEquals(later.situation(), saved.situation())
        assertEquals(1, saved.reportFielding.getValue(catcher).passedBalls)
        assertEquals(1, saved.pitching.getValue(pitcher).wildPitches)
        assertFalse(saved.entries.single { it.id == target.id }.needsReview)
        assertEquals(1, saved.journal[index].command.decisions.orEmpty().count { it.runnerDefense != null })
    }

    @Test
    fun runBeforeTagCanCountButNeverCountsOnThirdOutForce() {
        val beforeSingle = plays(seed(), Outcome.groundOut, Outcome.groundOut, Outcome.triple)
        val originalThird = requireNotNull(beforeSingle.bases[2])
        val before = MatchEngine.apply(beforeSingle, Command("play", outcome = Outcome.single,
            decisions = listOf(Decision(originalThird, 3, 0), Decision(beforeSingle.batter.id, 0, 1))))
        val first = requireNotNull(before.bases[0])
        val third = requireNotNull(before.bases[2])
        val catcher = before.fieldingPositions.getValue(2)
        val receiver = before.fieldingPositions.getValue(6)
        val tag = caught(first, -1, RunnerDefenseCredit.CaughtStealing(catcher, receiver, 2))
        val timePlay = tag.copy(decisions = requireNotNull(tag.decisions) + Decision(third, 3, 4))
        assertTrue(MatchEngine.needsTiming(before, timePlay))
        assertThrows(IllegalArgumentException::class.java) { MatchEngine.apply(before, timePlay) }
        assertEquals(1, MatchEngine.apply(before, timePlay.copy(timingRunCounts = true)).awayRuns)
        assertEquals(0, MatchEngine.apply(before, timePlay.copy(timingRunCounts = false)).awayRuns)
        val force = timePlay.copy(runnerKind = "forceOut", decisions = listOf(Decision(first, 1, -1), Decision(third, 3, 4)))
        assertFalse(MatchEngine.needsTiming(before, force))
        assertEquals(0, MatchEngine.apply(before, force.copy(timingRunCounts = true)).awayRuns)
    }

    @Test
    fun iosWireRestoresCatcherCreditsAndUsesItsOfficialEventNotations() {
        val before = plays(seed(), Outcome.single)
        val runner = requireNotNull(before.bases[0])
        val catcher = before.fieldingPositions.getValue(2)
        val receiver = before.fieldingPositions.getValue(6)
        val pb = Command("runner", runnerKind = "passedBall",
            decisions = listOf(Decision(runner, 1, 2, runnerDefense = RunnerDefenseCredit.PassedBall(catcher))))
        val advanced = MatchEngine.apply(before, pb)
        val cs = Command("runner", runnerKind = "caughtStealing",
            decisions = listOf(Decision(runner, 2, -1, runnerDefense = RunnerDefenseCredit.CaughtStealing(catcher, receiver, 3))))
        val recorded = MatchEngine.apply(advanced, cs)
        val encoded = AppleExportPrototype.encode(recorded)
        val stored = AppleBackupEnvelope.inspect(encoded).snapshot.getValue("games").jsonArray.single().jsonObject
        val events = stored.getValue("state").jsonObject.getValue("scoringEvents").jsonArray.takeLast(2)
        assertEquals(listOf("PB", "CS"), events.map { it.jsonObject.getValue("notation").jsonPrimitive.content })
        val restored = AppleReplayPrototype.replay(stored)
        assertEquals(recorded.situation(), restored.situation())
        assertEquals(recorded.reportFielding, restored.reportFielding)
    }

    private fun seed(recordingMethod: String = "traditional"): Game {
        fun team(name: String, opponent: Boolean) = Team(
            UUID.randomUUID().toString(), name,
            (1..12).map { Player(UUID.randomUUID().toString(), "球员$it", "$it", position = (it - 1) % 9 + 1) },
            isOpponent = opponent,
        )
        val home = team("主队", true)
        val away = team("客队", false)
        return MatchFactory.create(UUID.randomUUID().toString(), home, away, Rules(recordingMethod = recordingMethod), away.id)
    }

    private fun plays(game: Game, vararg outcomes: Outcome) =
        outcomes.fold(game) { before, outcome -> MatchEngine.apply(before, Command("play", outcome = outcome)) }

    private fun caught(runner: String, destination: Int, credit: RunnerDefenseCredit?) = Command(
        "runner", runnerKind = "caughtStealing", decisions = listOf(Decision(runner, 1, destination, runnerDefense = credit)),
    )
}
