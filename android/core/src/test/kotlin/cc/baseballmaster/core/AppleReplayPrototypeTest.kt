package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleReplayPrototypeTest {
    @Test fun iosJournalCatalogReplaysSupportedCommandsAcrossThreeModes() {
        val fixtures = GameJson.parseToJsonElement(File(System.getProperty("parity.fixtures"), "backup/ios-journal-catalog.json").readText()).jsonArray
        fixtures.forEach { entry ->
            val fixture = entry.jsonObject
            val replayed = AppleReplayPrototype.replay(fixture.getValue("storedGame").jsonObject)
            assertEquals(fixture.getValue("scenario").jsonPrimitive.content, fixture.getValue("steps").jsonArray.size, replayed.journal.size)
            val expected = fixture.getValue("storedGame").jsonObject.getValue("state").jsonObject.getValue("plateAppearances").jsonArray
            val actual = AppearanceProjection.build(replayed)
            assertEquals(fixture.getValue("scenario").jsonPrimitive.content + " appearances", expected.size, actual.size)
            expected.zip(actual).forEach { (raw, record) ->
                val pa = raw.jsonObject
                assertEquals(pa.getValue("inning").jsonPrimitive.int, record.inning)
                assertEquals(pa.getValue("isTop").jsonPrimitive.boolean, record.isTop)
                assertEquals(pa.getValue("completed").jsonPrimitive.boolean, record.complete)
                assertEquals(pa.getValue("interrupted").jsonPrimitive.boolean, record.interrupted)
                assertEquals(pa.getValue("batterIDs").jsonArray.last().jsonPrimitive.content.lowercase(), record.batterID)
            }

            if (fixture.getValue("scenario").jsonPrimitive.content == "runner-defense-detail") {
                val entry = replayed.entries.last()
                val detail = entry.decisions.first { it.cause == "tag" }
                assertEquals(listOf(8, 4), detail.fielding?.route)
                assertEquals("球员8", entry.participants.first { it.position == 8 }.name)
                val restored = MatchEngine.redo(MatchEngine.undo(replayed)).entries.last()
                assertEquals(detail, restored.decisions.first { it.cause == "tag" })
                assertEquals(entry.participants, restored.participants)
            }
            if (fixture.getValue("scenario").jsonPrimitive.content == "observation-defense") {
                val original = replayed.entries.last().participants
                assertEquals(9, original.size); assertEquals("球员6", original.first { it.position == 6 }.name)
                val undone = MatchEngine.undo(replayed)
                assertEquals(original, MatchEngine.redo(undone).entries.last().participants)
            }
        }
    }
    @Test fun genuineHistoricalBackupsReplayAgainstOriginalStats() {
        for (version in listOf(1, 2, 3, 4, 6)) {
            val bytes = File(System.getProperty("parity.fixtures"), "backup/ios-original-v$version.bmbackup").readBytes()
            val backup = AppleBackupEnvelope.inspect(bytes)
            assertEquals(version, backup.version)
            val replayed = try { AppleReplayPrototype.replay(backup.snapshot.getValue("games").jsonArray.single().jsonObject) }
                catch (failure: Exception) { throw AssertionError("iOS backup v$version", failure) }
            assertEquals("version $version", 1, replayed.awayHits)
            assertEquals("version $version", 2, replayed.journal.size)
            assertEquals("version $version", 1, replayed.batting.values.sumOf { it.hits })
        }
    }
    @Test fun androidGameEditedOnIOSReplaysBackOnAndroid() {
        val bytes = File(System.getProperty("parity.fixtures"), "backup/android-ios-game-roundtrip.bmbackup").readBytes()
        val backup = AppleBackupEnvelope.inspect(bytes)
        val replayed = AppleReplayPrototype.replay(backup.snapshot.getValue("games").jsonArray.single().jsonObject)
        assertEquals(3, replayed.journal.size)
        assertEquals(1, replayed.awayHits)
        assertEquals(1, replayed.batting.values.sumOf { it.hits })
        assertEquals(0, replayed.balls)
        assertEquals(1, replayed.strikes)
        assertEquals(1, replayed.bases.filterNotNull().size)
    }
    @Test fun androidComplexGameReopenedAndFinishedOnIOSReplaysBack() {
        val bytes = File(System.getProperty("parity.fixtures"), "backup/android-ios-complex-roundtrip.bmbackup").readBytes()
        val backup = AppleBackupEnvelope.inspect(bytes)
        val replayed = AppleReplayPrototype.replay(backup.snapshot.getValue("games").jsonArray.single().jsonObject)
        assertTrue(replayed.isFinal)
        assertEquals(8, replayed.journal.size)
        assertEquals(1, replayed.awayHits)
        assertEquals(1, replayed.batting.values.sumOf { it.stolenBases })
        val fielding = replayed.entries.mapNotNull { it.observation?.fielding }.single()
        assertEquals(listOf(6, 3), fielding.route)
        assertEquals("球员6", replayed.entries.first { it.observation != null }.participants.first { it.position == 6 }.name)
    }
    @Test fun iosCorrectionKeepsEarlierJournalAndCanBeReverted() {
        val bytes = File(System.getProperty("parity.fixtures"), "backup/ios-correction-reference.bmbackup").readBytes()
        val stored = AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
        val corrected = AppleReplayPrototype.replay(stored)
        assertEquals(1, corrected.historyRevisions.size)
        assertEquals("更正误记安打", corrected.historyRevisions.single().reason)
        assertTrue(corrected.journal.first().edited)
        assertEquals(1, corrected.homeErrors)
        assertEquals(0, corrected.awayHits)
        val draft = History.revertingDraft(corrected)
        val preview = History.preview(corrected, draft)
        assertTrue(preview.conflict ?: "correction revert", preview.canSave)
        assertEquals(1, preview.game!!.awayHits)
        assertEquals(0, preview.game!!.homeErrors)
        assertEquals(1, preview.game!!.balls)
    }
    @Test fun authenticIOSSimpleJournalReplaysAndUnsupportedHistoryIsRejected() {
        val bytes = File(System.getProperty("parity.fixtures"), "backup/ios-reference-v7.bmbackup").readBytes()
        val game = AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
        val replayed = AppleReplayPrototype.replay(game)
        assertEquals(1, replayed.awayHits)
        assertEquals(2, replayed.journal.size)
        assertEquals(1, replayed.bases.filterNotNull().size)
        assertEquals(1, replayed.batting.values.sumOf { it.hits })
        val state = game.getValue("state").jsonObject
        val journal = state.getValue("historyJournal").jsonObject
        val badOp = buildJsonObject { put("id", journal.getValue("operations").jsonArray[0].jsonObject.getValue("id")); put("timestamp", JsonPrimitive(1)); put("errors", JsonArray(emptyList())); put("command", buildJsonObject { put("unknown", buildJsonObject {}) }) }
        val badJournal = JsonObject(journal + ("operations" to JsonArray(listOf(badOp))))
        val badState = JsonObject(state + ("historyJournal" to badJournal))
        assertThrows(Exception::class.java) { AppleReplayPrototype.replay(JsonObject(game + ("state" to badState))) }
    }
}
