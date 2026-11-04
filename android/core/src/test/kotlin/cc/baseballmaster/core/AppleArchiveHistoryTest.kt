package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleArchiveHistoryTest {
    private fun game(name: String): JsonObject = AppleBackupEnvelope.inspect(
        File(System.getProperty("parity.fixtures"), "backup/$name.bmbackup").readBytes()
    ).snapshot.getValue("games").jsonArray.first().jsonObject

    @Test fun realIOSCorrectionAuditAndPreviousJournalRemainUnchanged() {
        val stored = game("ios-correction-reference")
        val before = stored.toString()
        val history = AppleArchiveHistory.read(stored)
        val state = stored.getValue("state").jsonObject
        assertTrue(history.hasJournal)
        assertTrue(history.corrections.isNotEmpty())
        assertEquals(state.getValue("correctionRevisions").jsonArray.toList(), history.corrections)
        assertNotNull(history.corrections.first()["previousJournal"])
        assertEquals(state.getValue("playLog").jsonArray.toList(), history.logs)
        assertEquals(state.getValue("scoringEvents").jsonArray.toList(), history.events)
        assertEquals(state.getValue("plateAppearances").jsonArray.toList(), history.appearances)
        assertEquals(before, stored.toString())
    }

    @Test fun realIOSOperationsAreNotAssumedToBeOneEventEach() {
        val history = AppleArchiveHistory.read(game("ios-reference-v7"))
        assertEquals(2, history.operations.size)
        assertEquals(3, history.events.size)
        assertEquals(4, history.logs.size)
        assertEquals(3, history.operations.sumOf { it.getValue("eventIDs").jsonArray.size })
    }

    @Test fun missingLegacyJournalIsNotSynthesized() {
        val stored = game("ios-reference-v7")
        val legacy = JsonObject(stored + ("state" to JsonObject(stored.getValue("state").jsonObject - "historyJournal")))
        val history = AppleArchiveHistory.read(legacy)
        assertFalse(history.hasJournal)
        assertTrue(history.operations.isEmpty())
        assertTrue(history.logs.isNotEmpty())
    }
}
