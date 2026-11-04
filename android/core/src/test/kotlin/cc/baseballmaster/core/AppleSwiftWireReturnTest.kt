package cc.baseballmaster.core

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Run only with :core:swiftWireTest after swift-wire-check.py generated actual Swift outputs. */
class AppleSwiftWireReturnTest {
    @Test fun swiftDecodedAndReencodedBackupsKeepHistoryIdentitiesAndReplayResults() {
        val configured = System.getProperty("swift.wire.directory").orEmpty()
        require(configured.isNotBlank()) { "Provide -PswiftWireDirectory pointing to actual Swift results" }
        val directory = File(configured)
        val results = AppleWireJson.parse(File(directory, "results.json").readBytes()).jsonArray
        require(results.isNotEmpty()) { "No Swift results" }
        results.forEachIndexed { index, raw ->
            val receipt = raw.jsonObject
            assertTrue(receipt.getValue("input").jsonPrimitive.content, receipt.getValue("passed").jsonPrimitive.boolean)
            val input = File(receipt.getValue("input").jsonPrimitive.content)
            val output = File(directory, "${index.toString().padStart(3, '0')}-${input.nameWithoutExtension}.bmbackup")
            val originalBytes = input.readBytes()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(originalBytes).joinToString("") { "%02x".format(it) }
            assertEquals("Source changed after Swift check: ${input.name}", receipt.getValue("sourceSha256").jsonPrimitive.content, hash)
            val before = AppleBackupEnvelope.inspect(originalBytes)
            val consumedPayload = File(directory, "${index.toString().padStart(3, '0')}-${input.nameWithoutExtension}.snapshot.json").readBytes()
            val envelope = AppleWireJson.parse(originalBytes).jsonObject
            assertArrayEquals("Swift must consume the current source payload: ${input.name}",
                Base64.getDecoder().decode(envelope.getValue("payload").jsonPrimitive.content), consumedPayload)
            val after = AppleBackupEnvelope.inspect(output.readBytes())
            assertEquals(input.name, before.version, after.version)
            assertEquals(input.name, before.teamCount, after.teamCount)
            assertEquals(input.name, before.gameCount, after.gameCount)
            val oldGames = before.snapshot.getValue("games").jsonArray
            val newGames = after.snapshot.getValue("games").jsonArray
            oldGames.zip(newGames).forEach { (a, b) ->
                val old = a.jsonObject; val new = b.jsonObject
                assertEquals(old.getValue("id"), new.getValue("id"))
                val oldState = old.getValue("state").jsonObject; val newState = new.getValue("state").jsonObject
                fun identities(state: JsonObject, field: String) = state[field]?.jsonArray.orEmpty().map { it.jsonObject.getValue("id") }
                for (key in listOf("playLog", "scoringEvents", "plateAppearances", "correctionRevisions"))
                    assertEquals("${input.name} $key identities", identities(oldState, key), identities(newState, key))
                val oldJournal = oldState["historyJournal"]?.jsonObject
                if (oldJournal != null) {
                    val newJournal = newState.getValue("historyJournal").jsonObject
                    for (key in listOf("id", "eventIDs", "logIDs", "nextAppearanceID"))
                        assertEquals("${input.name} operation $key", oldJournal.getValue("operations").jsonArray.map { it.jsonObject[key] }, newJournal.getValue("operations").jsonArray.map { it.jsonObject[key] })
                    // Swift treats checkpoint Data as opaque; it must remain exactly the same bytes.
                    assertArrayEquals(Base64.getDecoder().decode(oldJournal.getValue("checkpoint").jsonPrimitive.content), Base64.getDecoder().decode(newJournal.getValue("checkpoint").jsonPrimitive.content))
                }
                assertEquals("${input.name} Kotlin replay", GameJson.encodeToJsonElement(AppleReplayPrototype.replay(old)), GameJson.encodeToJsonElement(AppleReplayPrototype.replay(new)))
            }
        }
    }
}
