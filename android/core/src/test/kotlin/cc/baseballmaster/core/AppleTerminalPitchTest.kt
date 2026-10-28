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

    @Test fun thirdOutAndScoringWalkRemainRejectedWithoutChangingAnySourceBytes() {
        var plan = strikeout(strikeout(base(), PitchAction.calledStrike), PitchAction.swingingStrike)
        repeat(2) { plan = pitch(plan, PitchAction.calledStrike) }
        val before = plan.export()
        assertThrows(Exception::class.java) { pitch(plan, PitchAction.calledStrike) }
        assertArrayEquals(before, plan.export())
        var walks = base()
        repeat(2) { repeat(3) { walks = pitch(walks, PitchAction.ball) }; walks = pitch(walks, PitchAction.ball) }
        repeat(3) { walks = pitch(walks, PitchAction.ball) }
        val loaded = walks.export()
        assertThrows(Exception::class.java) { pitch(walks, PitchAction.ball) }
        assertArrayEquals(loaded, walks.export())
    }
}
