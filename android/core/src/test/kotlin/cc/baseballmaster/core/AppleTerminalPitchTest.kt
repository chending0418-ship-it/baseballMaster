package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleTerminalPitchTest {
    private val time = 1_800_000_000_000L
    private var counter = 0
    private fun id() = "f8000000-0000-0000-0000-${(++counter).toString().padStart(12, '0')}"
    private fun source() = File(System.getProperty("parity.fixtures"), "backup/ios-reference-v7.bmbackup").readBytes()
    private fun state(bytes: ByteArray) = AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
    private fun base() = AppleSourceBackup.prepare(source())
    private fun pitch(plan: AppleSourceBackup, action: PitchAction) = plan.appendPitch(plan.games().single().id, action, id(), time + counter * 1000)
    private fun strikeout(plan: AppleSourceBackup, action: PitchAction): AppleSourceBackup {
        var current = plan
        repeat(2) { current = pitch(current, PitchAction.calledStrike) }
        return pitch(current, action)
    }

    @Test fun fourthBallProducesTwoEventsOneOperationAndAStableNewAppearance() {
        var plan = base(); repeat(3) { plan = pitch(plan, PitchAction.ball) }
        val original = plan.export(); val before = state(original); val beforeGame = plan.games().single()
        val next = pitch(plan, PitchAction.ball); val after = state(next.export()); val game = next.games().single()
        val oldJournal = before.getValue("historyJournal").jsonObject
        val journal = after.getValue("historyJournal").jsonObject
        assertEquals(oldJournal.getValue("operations").jsonArray.size + 1, journal.getValue("operations").jsonArray.size)
        val operation = journal.getValue("operations").jsonArray.last().jsonObject
        assertEquals(2, operation.getValue("eventIDs").jsonArray.size); assertEquals(2, operation.getValue("logIDs").jsonArray.size)
        val events = after.getValue("scoringEvents").jsonArray.takeLast(2).map { it.jsonObject }
        assertEquals(listOf("B", "BB"), events.map { it.getValue("notation").jsonPrimitive.content })
        assertEquals(listOf("pitch", "battedBall"), events.map { it.getValue("category").jsonPrimitive.content })
        assertEquals(4, events[0].getValue("afterSituation").jsonObject.getValue("balls").jsonPrimitive.int)
        assertEquals(events[0].getValue("afterSituation"), events[1].getValue("beforeSituation"))
        assertEquals(0, events[1].getValue("afterSituation").jsonObject.getValue("balls").jsonPrimitive.int)
        assertEquals(before.getValue("currentPlateAppearanceID"), events[0].getValue("plateAppearanceID"))
        assertEquals(before.getValue("currentPlateAppearanceID"), events[1].getValue("plateAppearanceID"))
        assertNotEquals(before.getValue("currentPlateAppearanceID"), after.getValue("currentPlateAppearanceID"))
        assertEquals(after.getValue("currentPlateAppearanceID"), operation.getValue("nextAppearanceID"))
        val oldAppearance = after.getValue("plateAppearances").jsonArray.single { it.jsonObject.getValue("id") == before.getValue("currentPlateAppearanceID") }.jsonObject
        assertTrue(oldAppearance.getValue("completed").jsonPrimitive.boolean)
        assertEquals(before.getValue("plateAppearances").jsonArray.size + 1, after.getValue("plateAppearances").jsonArray.size)
        assertEquals(1, game.batting.getValue(beforeGame.batter.id).walks)
        assertEquals(beforeGame.batter.id, game.bases[0]); assertEquals(beforeGame.bases[0], game.bases[1])
        assertEquals(0, game.appearancePitches); assertEquals(beforeGame.totalPitches + 1, game.totalPitches)
        assertEquals(before.getValue("playLog").jsonArray.toList(), after.getValue("playLog").jsonArray.dropLast(2))
        assertEquals(before.getValue("scoringEvents").jsonArray.toList(), after.getValue("scoringEvents").jsonArray.dropLast(2))
        assertArrayEquals(original, plan.export())
        AppleSourceBackup.validateLineage(source(), next.export())
    }

    @Test fun calledAndSwingingStrikeoutsHaveSeparatePitchAndOutEventsWithoutLosingAudit() {
        for ((action, notation, wording) in listOf(Triple(PitchAction.calledStrike, "ꓘ", "看着"), Triple(PitchAction.swingingStrike, "K", "挥棒"))) {
            var plan = base(); repeat(2) { plan = pitch(plan, PitchAction.calledStrike) }
            val before = plan.games().single(); val next = pitch(plan, action)
            val events = state(next.export()).getValue("scoringEvents").jsonArray.takeLast(2).map { it.jsonObject }
            assertEquals(listOf(if (action == PitchAction.calledStrike) "C" else "S", notation), events.map { it.getValue("notation").jsonPrimitive.content })
            assertEquals("out", events.last().getValue("category").jsonPrimitive.content)
            assertTrue(events.last().getValue("title").jsonPrimitive.content.contains(wording))
            assertEquals(3, events.first().getValue("afterSituation").jsonObject.getValue("strikes").jsonPrimitive.int)
            assertEquals(1, next.games().single().outs); assertEquals(0, next.games().single().strikes)
            assertEquals(before.bases, next.games().single().bases)
            assertEquals(1, next.games().single().batting.getValue(before.batter.id).strikeouts)
            assertEquals(1, next.games().single().pitching.getValue(before.pitcher.id).outsRecorded)
            AppleSourceBackup.validateLineage(source(), next.export())
        }
    }

    @Test fun millisecondTimestampsStayExactForNonterminalAndTerminalPitches() {
        for (offset in listOf(2L, 9L, 15L, 22L, 35L, 48L)) {
            val original = base(); val game = original.games().single()
            val next = original.appendPitch(game.id, PitchAction.calledStrike, id(), time + offset)
            assertEquals(time + offset, next.games().single().journal.last().command.at)
            var before = base(); repeat(3) { before = pitch(before, PitchAction.ball) }
            val at = time + counter * 1000 + offset
            val terminal = before.appendPitch(before.games().single().id, PitchAction.ball, id(), at)
            assertEquals(at, terminal.games().single().journal.last().command.at)
            AppleSourceBackup.validateLineage(source(), terminal.export())
        }
    }

    @Test fun thirdOutRemainsRejectedWithoutChangingAnySourceBytes() {
        var plan = strikeout(strikeout(base(), PitchAction.calledStrike), PitchAction.swingingStrike)
        repeat(2) { plan = pitch(plan, PitchAction.calledStrike) }
        val before = plan.export()
        assertThrows(Exception::class.java) { pitch(plan, PitchAction.calledStrike) }
        assertArrayEquals(before, plan.export())
    }

    @Test fun basesLoadedWalkKeepsTheOriginalAuditAndChargesTheActualEarnedOrUnearnedRunner() {
        for ((file, earned) in listOf("ios-reference-v7.bmbackup" to 1,"ios-correction-reference.bmbackup" to 0)) {
            val original = File(System.getProperty("parity.fixtures"), "backup/$file").readBytes()
            var plan = AppleSourceBackup.prepare(original)
            while (plan.games().single().bases.any { it == null }) {
                while (plan.games().single().balls < 3) plan = pitch(plan,PitchAction.ball)
                plan = pitch(plan,PitchAction.ball)
            }
            while (plan.games().single().balls < 3) plan = pitch(plan,PitchAction.ball)
            val old = plan.games().single(); val before = plan.export(); val beforeState = state(before)
            val scorer = requireNotNull(old.bases[2]); val responsible = old.runnerPitcherIDs.getValue(scorer)
            val next = pitch(plan,PitchAction.ball); val game = next.games().single(); val after = state(next.export())
            assertEquals(old.awayRuns + 1,game.awayRuns); assertEquals(old.homeRuns,game.homeRuns)
            assertEquals((old.batting[scorer]?.runs ?: 0) + 1,game.batting.getValue(scorer).runs)
            assertEquals((old.batting[old.batter.id]?.runsBattedIn ?: 0) + 1,game.batting.getValue(old.batter.id).runsBattedIn)
            assertEquals(old.pitching.getValue(responsible).runs + 1,game.pitching.getValue(responsible).runs)
            assertEquals(old.pitching.getValue(responsible).earnedRuns + earned,game.pitching.getValue(responsible).earnedRuns)
            assertFalse(game.bases.contains(scorer))
            // iOS retains historical responsibility until the half changes.
            assertEquals(responsible,game.runnerPitcherIDs[scorer])
            assertEquals(old.unearnedRunnerIDs.contains(scorer),game.unearnedRunnerIDs.contains(scorer))
            val events = after.getValue("scoringEvents").jsonArray
            assertEquals(listOf(JsonPrimitive(scorer.uppercase())),events.last().jsonObject.getValue("effectiveScorerIDs").jsonArray.toList())
            assertEquals("得分",events.last().jsonObject.getValue("runnerMovements").jsonArray.first().jsonObject.getValue("destination").jsonPrimitive.content)
            assertTrue(events.last().jsonObject.getValue("title").jsonPrimitive.content.endsWith("，挤回 1 分"))
            assertEquals(beforeState.getValue("scoringEvents").jsonArray.toList(),events.dropLast(2))
            assertEquals(beforeState.getValue("playLog").jsonArray.toList(),after.getValue("playLog").jsonArray.dropLast(2))
            assertEquals(beforeState["correctionRevisions"],after["correctionRevisions"])
            assertEquals(beforeState.getValue("historyJournal").jsonObject.getValue("checkpoint"),after.getValue("historyJournal").jsonObject.getValue("checkpoint"))
            assertArrayEquals(before,plan.export())
            val reopened = AppleSourceBackup.validateLineage(original,next.export())
            assertEquals(game.awayRuns,reopened.games().single().awayRuns)
            assertEquals(game.awayRuns,pitch(reopened,PitchAction.ball).games().single().awayRuns)
        }
    }

    @Test fun scoringWalkThatNeedsARunLimitDecisionStillRejectsWithoutChangingTheSource() {
        // Synthetic rule variant, not evidence of an actual legacy iOS export.
        val bytes = source(); val inspection = AppleBackupEnvelope.inspect(bytes)
        val stored = inspection.snapshot.getValue("games").jsonArray.single().jsonObject
        val state = stored.getValue("state").jsonObject
        val rules = JsonObject(stored.getValue("rules").jsonObject + ("halfInningRunLimit" to JsonPrimitive(1)))
        val journal = state.getValue("historyJournal").jsonObject
        val checkpoint = AppleWireJson.parse(java.util.Base64.getDecoder().decode(journal.getValue("checkpoint").jsonPrimitive.content)).jsonObject
        val newCheckpoint = JsonObject(checkpoint + ("rulesSnapshot" to rules)).toString().toByteArray()
        val newJournal = JsonObject(journal + ("checkpoint" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(newCheckpoint))))
        val newState = JsonObject(state + mapOf("rulesSnapshot" to rules,"historyJournal" to newJournal))
        val newStored = JsonObject(stored + mapOf("rules" to rules,"state" to newState))
        val payload = JsonObject(inspection.snapshot + ("games" to JsonArray(listOf(newStored)))).toString().toByteArray()
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {"%02x".format(it)}
        val synthetic = JsonObject(AppleWireJson.parse(bytes).jsonObject + mapOf("checksum" to JsonPrimitive(checksum),"payload" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(payload)))).toString().toByteArray()
        var plan = AppleSourceBackup.prepare(synthetic)
        while(plan.games().single().bases.any {it==null}) {
            while(plan.games().single().balls<3) plan=pitch(plan,PitchAction.ball)
            plan=pitch(plan,PitchAction.ball)
        }
        while(plan.games().single().balls<3) plan=pitch(plan,PitchAction.ball)
        val before=plan.export()
        assertThrows(IllegalArgumentException::class.java) {pitch(plan,PitchAction.ball)}
        assertArrayEquals(before,plan.export())
    }
}
