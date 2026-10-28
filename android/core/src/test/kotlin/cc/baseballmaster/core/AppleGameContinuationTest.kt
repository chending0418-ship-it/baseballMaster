package cc.baseballmaster.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleGameContinuationTest {
    private val time = 1_800_000_000_000L
    private val op1 = "e1000000-0000-0000-0000-000000000001"
    private val op2 = "e1000000-0000-0000-0000-000000000002"
    private val op3 = "e1000000-0000-0000-0000-000000000003"
    private fun fixture(name: String = "ios-reference-v7.bmbackup") = File(System.getProperty("parity.fixtures"), "backup/$name").readBytes()
    private fun inspect(bytes: ByteArray) = AppleBackupEnvelope.inspect(bytes)
    private fun stored(bytes: ByteArray) = inspect(bytes).snapshot.getValue("games").jsonArray.first().jsonObject
    private fun session(bytes: ByteArray = fixture()) = AppleGameContinuation.create(bytes, stored(bytes).getValue("id").jsonPrimitive.content)
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun state(value: JsonObject) = value.getValue("state").jsonObject
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rewrap(outer: JsonObject, payload: JsonObject): ByteArray {
        val bytes = payload.toString().toByteArray()
        return outer.with("payload", JsonPrimitive(Base64.getEncoder().encodeToString(bytes))).with("checksum", JsonPrimitive(sha(bytes))).toString().toByteArray()
    }
    private fun syntheticMutation(change: (JsonObject) -> JsonObject): ByteArray {
        val bytes = fixture(); val snapshot = inspect(bytes).snapshot; val game = snapshot.getValue("games").jsonArray.first().jsonObject
        return rewrap(AppleWireJson.parse(bytes).jsonObject, snapshot.with("games", JsonArray(listOf(change(game)))))
    }

    @Test fun unchangedExportIsExactlyTheOriginalEnvelopeAndOwnsItsBytes() {
        val original = fixture(); val expected = original.copyOf(); val continuation = session(original)
        original.fill(0)
        assertArrayEquals(expected, continuation.exportBackup())
        val output = continuation.exportBackup(); output.fill(0)
        assertArrayEquals(expected, continuation.exportBackup())
    }

    @Test fun appendingRealPitchPreservesMultipleEventsPerOperationAndRawSourceHistory() {
        val bytes = fixture(); val before = stored(bytes); val oldState = state(before)
        val after = stored(session(bytes).appendNonterminalPitch(PitchAction.calledStrike, op1, time).exportBackup()); val newState = state(after)
        val oldJournal = oldState.getValue("historyJournal").jsonObject; val newJournal = newState.getValue("historyJournal").jsonObject
        assertEquals(2, oldJournal.getValue("operations").jsonArray.first().jsonObject.getValue("eventIDs").jsonArray.size)
        for (key in listOf("playLog", "scoringEvents")) assertEquals(oldState.getValue(key).jsonArray.toList(), newState.getValue(key).jsonArray.dropLast(1))
        for (key in listOf("plateAppearances", "currentPlateAppearanceID", "homeTeam", "awayTeam", "clockRunningSince")) assertEquals(oldState[key], newState[key])
        assertEquals(oldJournal.getValue("checkpoint"), newJournal.getValue("checkpoint"))
        assertEquals(oldJournal.getValue("operations").jsonArray.toList(), newJournal.getValue("operations").jsonArray.dropLast(1))
        // JsonPrimitive preserves the original fractional-second token, not a rounded millisecond.
        assertEquals(oldState.getValue("scoringEvents").jsonArray.first().jsonObject.getValue("timestamp").jsonPrimitive.content,
            newState.getValue("scoringEvents").jsonArray.first().jsonObject.getValue("timestamp").jsonPrimitive.content)
        assertEquals(1, newState.getValue("strikes").jsonPrimitive.int)
        assertEquals(1, newState.getValue("plateAppearancePitchCount").jsonPrimitive.int)
        assertEquals(before.getValue("revision").jsonPrimitive.long + 1, after.getValue("revision").jsonPrimitive.long)
        val replayed = AppleReplayPrototype.replay(after)
        assertEquals(1, replayed.strikes); assertEquals(1, replayed.awayHits)
    }

    @Test fun newOperationEventAndLogHaveSeparateStableNoncollidingIdentities() {
        val before = stored(fixture()); val after = stored(session().appendNonterminalPitch(PitchAction.ball, op1, time).exportBackup())
        val oldState = state(before); val newState = state(after)
        val event = newState.getValue("scoringEvents").jsonArray.last().jsonObject
        val log = newState.getValue("playLog").jsonArray.last().jsonObject
        val operation = newState.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.last().jsonObject
        val ids = listOf(operation, event, log).map { it.getValue("id").jsonPrimitive.content.lowercase() }
        assertEquals(3, ids.distinct().size)
        assertEquals(op1, ids.first())
        val existing = listOf("scoringEvents", "playLog", "plateAppearances").flatMap { oldState.getValue(it).jsonArray.map { row -> row.jsonObject.getValue("id").jsonPrimitive.content.lowercase() } }
        assertTrue(ids.none { it in existing })
        assertEquals(event.getValue("logEntryID"), log.getValue("id"))
        assertEquals(event.getValue("id"), operation.getValue("eventIDs").jsonArray.single())
        assertEquals(log.getValue("id"), operation.getValue("logIDs").jsonArray.single())
        assertEquals(oldState.getValue("currentPlateAppearanceID"), event.getValue("plateAppearanceID"))
    }

    @Test fun allOtherGamesRostersSeasonsAndSnapshotFieldsSurviveExport() {
        val bytes = fixture(); val original = inspect(bytes).snapshot
        val second = original.getValue("games").jsonArray.first().jsonObject.with("id", JsonPrimitive("eeeeeeee-0000-0000-0000-000000000001"))
        val modified = original.with("games", JsonArray(original.getValue("games").jsonArray + second))
            .with("futureArchiveMetadata", buildJsonObject { put("keepExactly", "synthetic preservation probe") })
        val source = rewrap(AppleWireJson.parse(bytes).jsonObject, modified)
        val exported = inspect(session(source).appendNonterminalPitch(PitchAction.ball, op1, time).exportBackup()).snapshot
        for ((key, value) in modified) if (key != "games") assertEquals(key, value, exported[key])
        assertEquals(second, exported.getValue("games").jsonArray[1])
        assertEquals(original.getValue("games").jsonArray.first().jsonObject.getValue("id"), exported.getValue("games").jsonArray.first().jsonObject.getValue("id"))
    }

    @Test fun serializeRestoreKeepsSourceIdentityAndSupportsFurtherContinuation() {
        val one = session().appendNonterminalPitch(PitchAction.calledStrike, op1, time)
        val restored = AppleGameContinuation.restore(one.serialize())
        assertArrayEquals(one.exportBackup(), restored.exportBackup())
        val after = restored.appendNonterminalPitch(PitchAction.ball, op2, time + 1000)
        val expected = one.appendNonterminalPitch(PitchAction.ball, op2, time + 1000)
        assertArrayEquals(expected.exportBackup(), after.exportBackup())
        assertArrayEquals(expected.exportBackup(), AppleGameContinuation.restore(after.serialize()).exportBackup())
        assertEquals(1, after.preview().balls); assertEquals(1, after.preview().strikes)
    }

    @Test fun checksumCorruptionAndRehashedDuplicateOperationsCannotRestore() {
        val continuation = session().appendNonterminalPitch(PitchAction.ball, op1, time)
        val bytes = continuation.serialize(); val outer = AppleWireJson.parse(bytes).jsonObject
        assertThrows(Exception::class.java) { AppleGameContinuation.restore(outer.with("checksum", JsonPrimitive("0".repeat(64))).toString().toByteArray()) }
        val payload = AppleWireJson.parse(Base64.getDecoder().decode(outer.getValue("payload").jsonPrimitive.content)).jsonObject
        val records = payload.getValue("appended").jsonArray
        assertThrows(Exception::class.java) { AppleGameContinuation.restore(rewrap(outer, payload.with("appended", JsonArray(records + records.single())))) }
        assertThrows(Exception::class.java) { AppleGameContinuation.restore(rewrap(outer, payload.with("sourceChecksum", JsonPrimitive("0".repeat(64))))) }
        assertArrayEquals(continuation.exportBackup(), AppleGameContinuation.restore(bytes).exportBackup())
    }

    @Test fun duplicateOperationAndTerminalPitchAreRejectedWithoutChangingSession() {
        val base = session(); val oldOperation = state(stored(fixture())).getValue("historyJournal").jsonObject.getValue("operations").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        assertThrows(Exception::class.java) { base.appendNonterminalPitch(PitchAction.ball, oldOperation.lowercase(), time) }
        val first = base.appendNonterminalPitch(PitchAction.calledStrike, op1, time)
        val snapshot = first.exportBackup()
        assertThrows(Exception::class.java) { first.appendNonterminalPitch(PitchAction.ball, op1.uppercase(), time + 1) }
        assertArrayEquals(snapshot, first.exportBackup())
        val second = first.appendNonterminalPitch(PitchAction.swingingStrike, op2, time + 1000)
        val beforeFailure = second.exportBackup()
        assertThrows(Exception::class.java) { second.appendNonterminalPitch(PitchAction.calledStrike, op3, time + 2000) }
        assertArrayEquals(beforeFailure, second.exportBackup())
        val foul = second.appendNonterminalPitch(PitchAction.foul, op3, time + 2000)
        assertEquals(2, foul.preview().strikes); assertEquals(second.preview().awayBatterIndex, foul.preview().awayBatterIndex)
    }

    @Test fun mutatingDetachedPreviewCannotSmuggleUnsupportedEditsIntoExport() {
        val continuation = session().appendNonterminalPitch(PitchAction.ball, op1, time)
        val expected = continuation.exportBackup(); val preview = continuation.preview()
        preview.homeRuns = 999; preview.home.name = "mutated"; preview.journal = emptyList(); preview.historyRevisions = emptyList()
        assertArrayEquals(expected, continuation.exportBackup())
        assertEquals(0, continuation.preview().homeRuns)
    }

    @Test fun unknownCommandPayloadsSharedGamesAndCorrectionsStayBlocked() {
        val unknown = syntheticMutation { game ->
            val state = state(game); val journal = state.getValue("historyJournal").jsonObject; val operations = journal.getValue("operations").jsonArray.toMutableList()
            val first = operations[0].jsonObject; val body = first.getValue("command").jsonObject.getValue("pitch").jsonObject.with("futurePenalty", JsonPrimitive(true))
            operations[0] = first.with("command", buildJsonObject { put("pitch", body) })
            game.with("state", state.with("historyJournal", journal.with("operations", JsonArray(operations))))
        }
        assertThrows(Exception::class.java) { session(unknown) }
        assertThrows(Exception::class.java) { session(syntheticMutation { it.with("sharedTeamID", JsonPrimitive("eeeeeeee-0000-0000-0000-000000000001")) }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { it.with("futureGameRule", JsonPrimitive(true)) }) }
        assertThrows(Exception::class.java) { session(fixture("ios-correction-reference.bmbackup")) }
        assertThrows(Exception::class.java) { session(fixture("ios-original-v6.bmbackup")) }
    }

    @Test fun wrongHalfCurrentAppearanceIsRejectedBeforeAnEditableSessionExists() {
        for (key in listOf("inning", "isTop")) {
            val bytes = syntheticMutation { game ->
                val oldState = state(game); val current = oldState.getValue("currentPlateAppearanceID")
                val appearances = oldState.getValue("plateAppearances").jsonArray.map { raw ->
                    val appearance = raw.jsonObject
                    if (appearance.getValue("id") != current) appearance else appearance.with(key,
                        if (key == "inning") JsonPrimitive(appearance.getValue(key).jsonPrimitive.int + 1)
                        else JsonPrimitive(!appearance.getValue(key).jsonPrimitive.boolean))
                }
                game.with("state", oldState.with("plateAppearances", JsonArray(appearances)))
            }
            assertThrows(key, Exception::class.java) { session(bytes) }
        }
    }

    @Test fun pausedOrUnstartedClockIsNotImplicitlyResumed() {
        val absent = syntheticMutation { it.with("state", JsonObject(state(it) - "clockRunningSince")) }
        val explicitNull = syntheticMutation { it.with("state", state(it).with("clockRunningSince", JsonNull)) }
        assertThrows(Exception::class.java) { session(absent) }
        assertThrows(Exception::class.java) { session(explicitNull) }
        val futureClock = syntheticMutation { it.with("state", state(it).with("clockRunningSince", Json.parseToJsonElement("900000000.000000001"))) }
        val continuation = session(futureClock)
        assertThrows(Exception::class.java) { continuation.appendNonterminalPitch(PitchAction.ball, op1, 1_878_307_200_000L) }
        assertArrayEquals(futureClock, continuation.exportBackup())
    }

    @Test fun malformedAssociatedPlayerAndFalseHistoryContextCannotBeEdited() {
        fun mutateOperation(index: Int, transform: (JsonObject) -> JsonObject) = syntheticMutation { game ->
            val oldState = state(game); val journal = oldState.getValue("historyJournal").jsonObject
            val operations = journal.getValue("operations").jsonArray.toMutableList(); operations[index] = transform(operations[index].jsonObject)
            game.with("state", oldState.with("historyJournal", journal.with("operations", JsonArray(operations))))
        }
        assertThrows(Exception::class.java) { session(mutateOperation(0) { operation -> operation.with("context", operation.getValue("context").jsonObject.with("inning", JsonPrimitive(2))) }) }
        assertThrows(Exception::class.java) { session(mutateOperation(0) { operation -> operation.with("context", operation.getValue("context").jsonObject.with("batterIndex", JsonPrimitive(1))) }) }
        assertThrows(Exception::class.java) { session(mutateOperation(1) { operation ->
            val play = operation.getValue("command").jsonObject.getValue("play").jsonObject
            val decisions = play.getValue("_2").jsonArray.toMutableList(); val decision = decisions[0].jsonObject
            decisions[0] = decision.with("player", decision.getValue("player").jsonObject.with("numbers", JsonArray(listOf(JsonPrimitive("12")))))
            operation.with("command", buildJsonObject { put("play", play.with("_2", JsonArray(decisions))) })
        }) }
    }

    @Test fun conflictingRulesRolesAndLineupSnapshotsAreRejected() {
        assertThrows(Exception::class.java) { session(syntheticMutation { game ->
            game.with("rules", game.getValue("rules").jsonObject.with("scheduledInnings", JsonPrimitive(7)))
        }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { game ->
            game.with("state", state(game).with("rulesSnapshot", game.getValue("rules").jsonObject.with("pitchLimit", JsonPrimitive(80))))
        }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { game -> game.with("isHome", JsonPrimitive(!game.getValue("isHome").jsonPrimitive.boolean)) }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { game -> game.with("opponentTeamID", game.getValue("ourTeamID")) }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { game ->
            val oldState = state(game)
            game.with("state", oldState.with("homeTeam", oldState.getValue("homeTeam").jsonObject.with("name", JsonPrimitive("unrecorded roster edit"))))
        }) }
    }

    @Test fun quotedFlagsAndVersionNumbersAreNotAcceptedAsSwiftValues() {
        for (key in listOf("statisticsIncomplete", "halfEndedAwaitingDecision", "recordsLineupSnapshots", "extraFoulUsed", "pitchingOutsReviewRequired", "canResumePrecisely")) {
            val bytes = syntheticMutation { it.with("state", state(it).with(key, JsonPrimitive("false"))) }
            assertThrows("quoted $key", Exception::class.java) { session(bytes) }
        }
        assertThrows(Exception::class.java) { session(syntheticMutation { it.with("openingPitchRecorded", JsonPrimitive("true")) }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { game -> game.with("rules", game.getValue("rules").jsonObject.with("rulesVersion", JsonPrimitive("1"))) }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { it.with("state", state(it).with("pitchingOutsAccountingVersion", JsonPrimitive("2"))) }) }
        assertThrows(Exception::class.java) { session(syntheticMutation { game ->
            val oldState = state(game); val map = oldState.getValue("runnerPitcherIDs").jsonArray
            game.with("state", oldState.with("runnerPitcherIDs", JsonArray(map + map)))
        }) }
    }

    @Test fun subMillisecondOriginalTimeCannotBeRoundedBackwards() {
        val bytes = syntheticMutation { it.with("updatedAt", Json.parseToJsonElement("900000000.000000001")) }
        val continuation = session(bytes)
        val exactEarlierMillis = 1_878_307_200_000L
        assertThrows(Exception::class.java) { continuation.appendNonterminalPitch(PitchAction.ball, op1, exactEarlierMillis) }
        val accepted = continuation.appendNonterminalPitch(PitchAction.ball, op1, exactEarlierMillis + 1)
        assertEquals(1, accepted.preview().balls)
        assertArrayEquals(bytes, continuation.exportBackup())
    }

    @Test fun operationIDsCannotCollideWithAnotherGamesOriginalHistory() {
        val bytes = fixture(); val snapshot = inspect(bytes).snapshot
        val first = snapshot.getValue("games").jsonArray.first().jsonObject
        val oldState = state(first); val journal = oldState.getValue("historyJournal").jsonObject
        val operations = journal.getValue("operations").jsonArray.toMutableList()
        operations[0] = operations[0].jsonObject.with("id", JsonPrimitive(op1.uppercase()))
        val second = first.with("id", JsonPrimitive("eeeeeeee-0000-0000-0000-000000000001"))
            .with("state", oldState.with("historyJournal", journal.with("operations", JsonArray(operations))))
        val source = rewrap(AppleWireJson.parse(bytes).jsonObject, snapshot.with("games", JsonArray(listOf(first, second))))
        val continuation = session(source)
        assertThrows(Exception::class.java) { continuation.appendNonterminalPitch(PitchAction.ball, op1.lowercase(), time) }
        assertArrayEquals(source, continuation.exportBackup())
    }

    @Test fun nonterminalPitchMatchesExistingActualIOSRoundtripStatistics() {
        // These are existing before/after fixtures; no new fixture is claimed to have run on iOS.
        val before = fixture("android-game-prototype-v7.bmbackup")
        val expected = stored(fixture("android-ios-game-roundtrip.bmbackup"))
        val actual = stored(session(before).appendNonterminalPitch(PitchAction.calledStrike, op1, time).exportBackup())
        val expectedState = state(expected); val actualState = state(actual)
        for (key in listOf("balls", "strikes", "outs", "plateAppearancePitchCount", "batting", "pitching", "fielding", "baseRunners", "plateAppearances")) assertEquals(key, expectedState[key], actualState[key])
        val expectedEvent = expectedState.getValue("scoringEvents").jsonArray.last().jsonObject
        val actualEvent = actualState.getValue("scoringEvents").jsonArray.last().jsonObject
        for (key in listOf("category", "notation", "title", "inning", "isTop", "primaryPlayerID", "secondaryPlayerID", "plateAppearanceBatterID", "plateAppearanceID", "runnerMovements", "needsReview")) assertEquals(key, expectedEvent[key], actualEvent[key])
    }
}
