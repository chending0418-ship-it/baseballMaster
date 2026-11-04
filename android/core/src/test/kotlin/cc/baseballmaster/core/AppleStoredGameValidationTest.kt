package cc.baseballmaster.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleStoredGameValidationTest {
    private val root get() = File(System.getProperty("parity.fixtures"))
    private fun backup(name: String): JsonObject {
        val envelope = Json.parseToJsonElement(File(root, "backup/$name").readText()).jsonObject
        return Json.parseToJsonElement(Base64.getDecoder().decode(envelope.getValue("payload").jsonPrimitive.content).toString(Charsets.UTF_8)).jsonObject
    }
    private fun original() = backup("ios-reference-v7.bmbackup").getValue("games").jsonArray.single().jsonObject
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun changedState(change: (JsonObject) -> JsonObject) = original().let { it.with("state", change(it.getValue("state").jsonObject)) }
    private fun changedJournal(change: (JsonObject) -> JsonObject) = changedState { state -> state.with("historyJournal", change(state.getValue("historyJournal").jsonObject)) }
    private fun rejects(game: JsonObject) { assertThrows(Exception::class.java) { AppleStoredGameValidation.validate(game) } }

    @Test fun validatesGenuineHistoricalSamplesWithoutInventingVersionFive() {
        val names = listOf(1, 2, 3, 4, 6).map { "ios-original-v$it.bmbackup" } + listOf("ios-reference-v7.bmbackup", "ios-correction-reference.bmbackup", "android-ios-game-roundtrip.bmbackup", "android-ios-complex-roundtrip.bmbackup")
        names.forEach { name -> backup(name).getValue("games").jsonArray.forEach { raw ->
            try { AppleStoredGameValidation.validate(raw.jsonObject) } catch (failure: Exception) { throw AssertionError(name, failure) }
        } }
    }

    @Test fun validatesIOSCatalogAcrossThreeModesAndPreservesSourceJSON() {
        val catalog = Json.parseToJsonElement(File(root, "backup/ios-journal-catalog.json").readText()).jsonArray
        catalog.forEach { raw ->
            val row = raw.jsonObject; val stored = row.getValue("storedGame").jsonObject; val before = stored.toString()
            try { AppleStoredGameValidation.validate(stored) } catch (failure: Exception) { throw AssertionError(row.getValue("scenario").jsonPrimitive.content, failure) }
            assertEquals(before, stored.toString())
        }
    }

    @Test fun validatesStoredPayloadInsideGenuineSharedTeamArchiveWithoutGrantingAuthority() {
        val rows = File(root, "team-archive/ios-team-reference.bmteam").readLines().map { Json.parseToJsonElement(it).jsonObject }
        val game = rows.single { it.getValue("kind").jsonPrimitive.content == "game" }
        val record = Json.parseToJsonElement(Base64.getDecoder().decode(game.getValue("payload").jsonPrimitive.content).toString(Charsets.UTF_8)).jsonObject
        val stored = record.getValue("payload").jsonObject
        val before = stored.toString()
        AppleStoredGameValidation.validate(stored)
        assertEquals(before, stored.toString())
    }

    @Test fun aCorrectChecksumDoesNotMakeInvalidGameContentSafe() {
        val source = backup("ios-reference-v7.bmbackup")
        val changed = changedState { state -> state.with("outs", JsonPrimitive(4)) }
        val payload = source.with("games", JsonArray(listOf(changed))).toString().toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val envelope = buildJsonObject {
            put("format", "BaseballMaster Backup"); put("version", 7); put("createdAt", 0)
            put("payload", Base64.getEncoder().encodeToString(payload)); put("checksum", checksum)
        }.toString().toByteArray()
        // Envelope checksums authenticate the bytes only; semantic validation must still fail.
        assertThrows(Exception::class.java) {
            val inspection = AppleBackupEnvelope.inspect(envelope)
            AppleStoredGameValidation.validate(inspection.snapshot.getValue("games").jsonArray.single().jsonObject)
        }
    }

    @Test fun rejectsWrongPrimitiveTypesUnknownEnumsAndDates() {
        rejects(changedState { it.with("outs", JsonPrimitive("1")) })
        rejects(changedState { it.with("isTop", JsonPrimitive("true")) })
        rejects(original().with("playedAt", JsonPrimitive("0")))
        rejects(original().with("playedTimeZoneID", JsonPrimitive("Mars/Olympus")))
        rejects(original().with("status", JsonPrimitive(3)))
        rejects(original().let { it.with("rules", it.getValue("rules").jsonObject.with("mode", JsonPrimitive("futureMode"))) })
        rejects(changedState { it.with("clockDisplayMode", JsonPrimitive("futureClock")) })
    }

    @Test fun rejectsDuplicateStatsKeysIncludingCaseVariantsAndNegativeCounts() {
        rejects(changedState { state ->
            val values = state.getValue("batting").jsonArray
            state.with("batting", JsonArray(values + listOf(JsonPrimitive(values[0].jsonPrimitive.content.lowercase()), values[1])))
        })
        rejects(changedState { state ->
            val values = state.getValue("batting").jsonArray.toMutableList()
            values[1] = values[1].jsonObject.with("hits", JsonPrimitive(-1)); state.with("batting", JsonArray(values))
        })
        rejects(changedState { state -> state.with("pitching", JsonArray(state.getValue("pitching").jsonArray.dropLast(1))) })
    }

    @Test fun rejectsDuplicateOrDanglingAppearanceAndEventIdentities() {
        rejects(changedState { state -> state.with("scoringEvents", JsonArray(state.getValue("scoringEvents").jsonArray.let { it + it.first() })) })
        rejects(changedState { state -> state.with("plateAppearances", JsonArray(state.getValue("plateAppearances").jsonArray.let { it + it.first() })) })
        rejects(changedState { it.with("currentPlateAppearanceID", JsonPrimitive("eeeeeeee-0000-0000-0000-000000000001")) })
        rejects(changedState { state ->
            val events = state.getValue("scoringEvents").jsonArray.toMutableList()
            events[0] = events[0].jsonObject.with("plateAppearanceID", JsonPrimitive("eeeeeeee-0000-0000-0000-000000000001"))
            state.with("scoringEvents", JsonArray(events))
        })
    }

    @Test fun rejectsInvalidLineupsBasesAndScores() {
        rejects(changedState { state -> state.with("homeBattingOrderIDs", JsonArray(state.getValue("homeBattingOrderIDs").jsonArray.let { it + it.first() })) })
        rejects(changedState { state ->
            val away = state.getValue("awayTeam").jsonObject
            val homePlayer = state.getValue("homeTeam").jsonObject.getValue("players").jsonArray.first()
            state.with("awayTeam", away.with("players", JsonArray(away.getValue("players").jsonArray + homePlayer)))
        })
        rejects(changedState { state -> state.with("homeRunsByInning", JsonArray(emptyList())) })
        rejects(changedState { state -> state.with("baseRunners", JsonArray(state.getValue("baseRunners").jsonArray.let { listOf(JsonPrimitive(4), it[1]) })) })
        rejects(changedState { state ->
            val bases = state.getValue("baseRunners").jsonArray
            state.with("baseRunners", JsonArray(bases + listOf(JsonPrimitive(2), bases[1])))
        })
    }

    @Test fun rejectsFutureJournalDuplicateOperationsAndRecursiveOrWrongTeamCheckpoints() {
        rejects(changedJournal { it.with("version", JsonPrimitive(2)) })
        rejects(changedJournal { journal -> journal.with("operations", JsonArray(journal.getValue("operations").jsonArray.let { it + it.first() })) })
        rejects(changedJournal { journal ->
            val operations = journal.getValue("operations").jsonArray.toMutableList()
            operations[0] = operations[0].jsonObject.with("command", buildJsonObject { put("futureCommand", buildJsonObject {}) })
            journal.with("operations", JsonArray(operations))
        })
        fun alteredCheckpoint(transform: (JsonObject) -> JsonObject) = changedJournal { journal ->
            val checkpoint = Json.parseToJsonElement(Base64.getDecoder().decode(journal.getValue("checkpoint").jsonPrimitive.content).toString(Charsets.UTF_8)).jsonObject
            journal.with("checkpoint", JsonPrimitive(Base64.getEncoder().encodeToString(transform(checkpoint).toString().toByteArray())))
        }
        rejects(alteredCheckpoint { it.with("correctionRevisions", JsonArray(emptyList())) })
        rejects(alteredCheckpoint { it.with("homeTeam", it.getValue("homeTeam").jsonObject.with("id", JsonPrimitive("eeeeeeee-0000-0000-0000-000000000001"))) })
    }

    @Test fun rejectsInvalidObservationGeometryRouteAndSlowPitchRules() {
        val catalog = Json.parseToJsonElement(File(root, "backup/ios-journal-catalog.json").readText()).jsonArray
        fun scenario(name: String) = catalog.single { it.jsonObject.getValue("scenario").jsonPrimitive.content == name }.jsonObject.getValue("storedGame").jsonObject
        val observationGame = scenario("observation-defense")
        val state = observationGame.getValue("state").jsonObject
        val events = state.getValue("scoringEvents").jsonArray.toMutableList()
        val index = events.indexOfFirst { "battedBall" in it.jsonObject }
        val event = events[index].jsonObject; val observation = event.getValue("battedBall").jsonObject
        events[index] = event.with("battedBall", observation.with("x", JsonPrimitive(1.5)))
        rejects(observationGame.with("state", state.with("scoringEvents", JsonArray(events))))
        events[index] = event.with("battedBall", observation.with("fielding", observation.getValue("fielding").jsonObject.with("route", JsonArray(List(51) { JsonPrimitive(6) }))))
        rejects(observationGame.with("state", state.with("scoringEvents", JsonArray(events))))
        val slow = scenario("slow-pitch")
        rejects(slow.with("rules", slow.getValue("rules").jsonObject.with("fieldersCount", JsonPrimitive(9))))
        rejects(slow.with("rules", JsonObject(slow.getValue("rules").jsonObject - "twoStrikeFoulPolicy")))
    }
}
