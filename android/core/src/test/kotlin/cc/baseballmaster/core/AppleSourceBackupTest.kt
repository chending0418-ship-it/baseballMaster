package cc.baseballmaster.core

import java.io.File
import java.util.Base64
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleSourceBackupTest {
    private fun source(name: String = "ios-correction-reference.bmbackup") = File(System.getProperty("parity.fixtures"), "backup/$name").readBytes()
    @Test fun preparesCompleteSourceAndPreservesItWithoutEdits() {
        val bytes = source(); val expected = bytes.copyOf()
        val plan = AppleSourceBackup.prepare(bytes)
        bytes.fill(0)
        assertArrayEquals(expected, plan.export())
        assertEquals(1, plan.games().size)
        val raw = AppleBackupEnvelope.inspect(expected).snapshot.getValue("games").jsonArray.single().jsonObject
        val projected = plan.games().single()
        assertEquals(raw["playedAt"]?.jsonPrimitive?.double?.let { ((it + 978307200) * 1000).toLong() }, projected.playedAt)
        assertTrue(projected.statisticsIncomplete)
        assertEquals(1, projected.historyRevisions.size)
        val detached = plan.games().single(); detached.home.name = "changed"; detached.historyRevisions = emptyList()
        assertNotEquals("changed", plan.games().single().home.name)
        assertEquals(1, plan.games().single().historyRevisions.size)
        assertArrayEquals(expected, plan.export())
    }
    @Test fun supportedPitchChangesRawStateWithoutReplacingPriorHistoryOrMetadata() {
        val bytes = source(); val before = AppleSourceBackup.prepare(bytes)
        val game = before.games().single()
        val next = before.appendPitch(game.id, PitchAction.calledStrike, "f6000000-0000-0000-0000-000000000001", 1_800_000_000_000)
        assertEquals(game.revision + 1, next.games().single().revision)
        assertEquals(game.playedAt, next.games().single().playedAt)
        assertEquals(game.startedAt, next.games().single().startedAt)
        assertEquals(1, next.games().single().strikes)
        assertArrayEquals(bytes, before.export())
        val oldState = AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
        val newState = AppleBackupEnvelope.inspect(next.export()).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
        assertEquals(oldState.getValue("correctionRevisions"), newState.getValue("correctionRevisions"))
        assertEquals(oldState.getValue("playLog").jsonArray.toList(), newState.getValue("playLog").jsonArray.dropLast(1))
    }
    @Test fun restoredSourceMustReplayTheExactAppendAndKeepOriginalHistory() {
        val original = source(); val plan = AppleSourceBackup.prepare(original)
        val next = plan.appendPitch(plan.games().single().id, PitchAction.calledStrike, "f6000000-0000-0000-0000-000000000002", 1_800_000_000_000)
        val verified = AppleSourceBackup.validateLineage(original, next.export())
        assertEquals(plan.digest, verified.games(plan.digest).single().appleSourceID)
        fun mutate(change: (JsonObject) -> JsonObject): ByteArray {
            val outer = AppleWireJson.parse(next.export()).jsonObject
            val snapshot = AppleBackupEnvelope.inspect(next.export()).snapshot
            val games = snapshot.getValue("games").jsonArray
            val value = JsonObject(snapshot + ("games" to JsonArray(listOf(change(games.single().jsonObject)))))
            val bytes = value.toString().toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
        }
        val changedLog = mutate { game ->
            val state = game.getValue("state").jsonObject; val logs = state.getValue("playLog").jsonArray.toMutableList()
            logs[0] = JsonObject(logs[0].jsonObject + ("text" to JsonPrimitive("改写原始历史")))
            JsonObject(game + ("state" to JsonObject(state + ("playLog" to JsonArray(logs)))))
        }
        assertThrows(Exception::class.java) { AppleSourceBackup.validateLineage(original, changedLog) }
        val changedDate = mutate { JsonObject(it + ("playedAt" to JsonPrimitive(900_000_000))) }
        assertThrows(Exception::class.java) { AppleSourceBackup.validateLineage(original, changedDate) }
        val changedUpdate = mutate { JsonObject(it + ("updatedAt" to JsonPrimitive(900_000_000))) }
        assertThrows(Exception::class.java) { AppleSourceBackup.validateLineage(original, changedUpdate) }
        assertThrows(Exception::class.java) { AppleSourceBackup.validateLineage(original, original + byteArrayOf(10)) }
        assertArrayEquals(original, plan.export())
    }

    @Test fun importedProjectionCannotBeRewrittenByNativeExporter() {
        val game = AppleSourceBackup.prepare(source()).games().single()
        assertNotNull(game.appleSourceID)
        assertThrows(Exception::class.java) { AppleExportPrototype.encode(game) }
    }

    @Test fun allExistingGameReferencesHaveAReadOnlySourceProjectionAndExactOriginalExport() {
        val files = File(System.getProperty("parity.fixtures"), "backup").listFiles()!!.filter { it.extension == "bmbackup" }
        var count = 0
        for (file in files) {
            val bytes = file.readBytes(); val inspection = AppleBackupEnvelope.inspect(bytes)
            if (inspection.gameCount == 0) continue
            val plan = AppleSourceBackup.prepare(bytes)
            assertEquals(file.name, inspection.gameCount, plan.games().size)
            assertArrayEquals(file.name, bytes, plan.export())
            assertEquals(inspection.snapshot.getValue("games").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content.lowercase() }, plan.games().map { it.id })
            count++
        }
        assertEquals(12, count)
    }

    @Test fun explicitOptionalNullsRemainInOriginalSourceAndDoNotBecomeLiteralNullIdentifiers() {
        val bytes = source("ios-reference-v7.bmbackup")
        val outer = AppleWireJson.parse(bytes).jsonObject; val snapshot = AppleBackupEnvelope.inspect(bytes).snapshot
        val stored = snapshot.getValue("games").jsonArray.single().jsonObject
        val state = stored.getValue("state").jsonObject
        val journal = state.getValue("historyJournal").jsonObject
        val operations = journal.getValue("operations").jsonArray.map { JsonObject(it.jsonObject + mapOf("battedBall" to JsonNull, "statisticsRuling" to JsonNull)) }
        val changedState = JsonObject(state + mapOf("activeHomePitcherID" to JsonNull, "pitchingOutsReviewRequired" to JsonPrimitive(true), "historyJournal" to JsonObject(journal + ("operations" to JsonArray(operations)))))
        val changed = JsonObject(stored + mapOf("sharedTeamID" to JsonNull, "sharedGameID" to JsonNull, "playedTimeZoneID" to JsonNull, "playedAt" to JsonPrimitive(java.time.Instant.parse("2026-12-31T23:30:00Z").epochSecond - 978307200L), "state" to changedState))
        val payload = JsonObject(snapshot + ("games" to JsonArray(listOf(changed)))).toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val input = JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
        val plan = AppleSourceBackup.prepare(input)
        assertArrayEquals(input, plan.export()); assertEquals("UTC", plan.games().single().playedTimeZoneID)
        assertEquals(2026, MatchDates.year(plan.games().single())); assertTrue(plan.games().single().statisticsIncomplete)
        assertEquals(1, plan.games().single().awayHits)
    }

    @Test fun longerJournalWithoutStoredRevisionKeepsOperationCountAndReplayResult() {
        val bytes = source("ios-reference-v7.bmbackup")
        val stored = AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
        val id = stored.getValue("id").jsonPrimitive.content
        var session = AppleGameContinuation.create(bytes, id)
        repeat(12) { n -> session = session.appendNonterminalPitch(PitchAction.foul, "f6000000-0000-0000-0000-${(100+n).toString().padStart(12, '0')}", 1_800_000_000_000 + n * 1000L) }
        val current = session.exportBackup(); val outer = AppleWireJson.parse(current).jsonObject
        val snapshot = AppleBackupEnvelope.inspect(current).snapshot
        val game = snapshot.getValue("games").jsonArray.single().jsonObject
        val payload = JsonObject(snapshot + ("games" to JsonArray(listOf(JsonObject(game - "revision"))))).toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val noRevision = JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
        val replayed = AppleSourceBackup.prepare(noRevision).games().single()
        assertEquals(14L, replayed.revision); assertEquals(14, replayed.journal.size)
        val expected = AppleSourceBackup.prepare(current).games().single().copy(appleSourceID = replayed.appleSourceID, revision = replayed.revision)
        assertEquals(GameJson.encodeToJsonElement(expected), GameJson.encodeToJsonElement(replayed))
    }

    @Test fun legacyAndFinalGamesAreImportedReadOnlyWithoutConvertingTheirSource() {
        for (name in listOf("ios-original-v1.bmbackup", "ios-original-v2.bmbackup", "ios-original-v6.bmbackup", "android-complex-prototype-v7.bmbackup")) {
            val bytes = source(name); val imported = AppleSourceBackup.prepare(bytes)
            assertArrayEquals(bytes, imported.export()); assertEquals(1, imported.games().size)
            assertThrows(Exception::class.java) { imported.appendPitch(imported.games().single().id, PitchAction.ball, "f6000000-0000-0000-0000-000000000003", 1_800_000_000_000) }
        }
    }
}
