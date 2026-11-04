package cc.baseballmaster.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleTeamArchiveIntegrityTest {
    private fun source() = File(System.getProperty("parity.fixtures"), "team-archive/ios-team-reference.bmteam").readBytes()
    @Test fun acceptsActualIOSTeamArchiveAndChecksSeal() {
        val data = source()
        val result = AppleTeamArchiveIntegrity.inspect(data.inputStream())
        assertEquals(2, result.version)
        assertEquals("互导测试球队", result.teamName)
        assertEquals(1, result.gameCount)
        assertEquals(1, result.activeGameCount)
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.inspect(data.dropLast(20).toByteArray().inputStream()) }
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.read(data.dropLast(20).toByteArray().inputStream()) }
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.inspect((data + data).inputStream()) }
        val changed = data.copyOf(); changed[changed.size / 4] = (changed[changed.size / 4].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.inspect(changed.inputStream()) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun row(kind: String, value: JsonElement): ByteArray {
        val payload = value.toString().toByteArray()
        return buildJsonObject {
            put("kind", kind); put("payload", Base64.getEncoder().encodeToString(payload)); put("checksum", hash(payload))
        }.toString().toByteArray() + byteArrayOf(10)
    }
    private fun values(): List<Pair<String, JsonElement>> = source().toString(Charsets.UTF_8).trimEnd().lines().map { line ->
        val outer = Json.parseToJsonElement(line).jsonObject
        outer.getValue("kind").jsonPrimitive.content to Json.parseToJsonElement(Base64.getDecoder().decode(outer.getValue("payload").jsonPrimitive.content).toString(Charsets.UTF_8))
    }
    /** Changes are intentionally rehashed/resealed: SHA-256 alone is not semantic validation. */
    private fun reseal(transform: (String, JsonElement) -> JsonElement): ByteArray {
        val bytes = values().filter { it.first != "seal" }.fold(byteArrayOf()) { result, (kind, value) -> result + row(kind, transform(kind, value)) }
        return bytes + row("seal", JsonPrimitive(hash(bytes)))
    }
    @Test fun readRetainsOriginalIdentitiesProfileAndAllWireFieldsWithoutConversion() {
        val expected = values()
        val archive = AppleTeamArchiveIntegrity.read(source().inputStream())
        assertEquals(expected.first().second, archive.manifest)
        assertEquals(expected.filter { it.first == "game" }.map { it.second }, archive.records)
        assertEquals(AppleTeamArchiveIntegrity.inspect(source().inputStream()), archive.summary)
    }
    @Test fun validChecksumsDoNotPermitMalformedStoredGamesOrProfiles() {
        val badScore = reseal { kind, value ->
            if (kind != "game") value else {
                val record = value.jsonObject; val stored = record.getValue("payload").jsonObject
                val state = stored.getValue("state").jsonObject
                JsonObject(record + ("payload" to JsonObject(stored + ("state" to JsonObject(state + ("outs" to JsonPrimitive(9)))))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { AppleTeamArchiveIntegrity.read(badScore.inputStream()) }
        val badProfile = reseal { kind, value ->
            if (kind != "manifest") value else {
                val manifest = value.jsonObject; val profile = manifest.getValue("profile").jsonObject
                val players = profile.getValue("players").jsonArray.toMutableList()
                players[0] = JsonObject(players[0].jsonObject + ("numbers" to JsonArray(listOf(JsonPrimitive("01")))))
                JsonObject(manifest + ("profile" to JsonObject(profile + ("players" to JsonArray(players)))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { AppleTeamArchiveIntegrity.inspect(badProfile.inputStream()) }
    }
    @Test fun deletedAndPurgedRecordsKeepTheirTombstonesAndDoNotCountAsActive() {
        val deleted = reseal { kind, value ->
            if (kind == "manifest") {
                val manifest = value.jsonObject
                JsonObject(manifest + ("games" to JsonArray(manifest.getValue("games").jsonArray.map { JsonObject(it.jsonObject + ("deleted" to JsonPrimitive(true))) })))
            } else JsonObject(value.jsonObject + ("deleted" to JsonPrimitive(true)))
        }
        val read = AppleTeamArchiveIntegrity.read(deleted.inputStream())
        assertEquals(0, read.summary.activeGameCount)
        assertNotNull(read.records.single()["payload"])
        val purged = reseal { kind, value ->
            if (kind == "manifest") {
                val manifest = value.jsonObject
                JsonObject(manifest + ("games" to JsonArray(manifest.getValue("games").jsonArray.map { JsonObject(it.jsonObject + mapOf("deleted" to JsonPrimitive(true), "purged" to JsonPrimitive(true))) })))
            } else JsonObject(value.jsonObject - "payload" - "projection" + mapOf("deleted" to JsonPrimitive(true), "purged" to JsonPrimitive(true)))
        }
        val tombstone = AppleTeamArchiveIntegrity.read(purged.inputStream())
        assertEquals(1, tombstone.summary.gameCount); assertEquals(0, tombstone.summary.activeGameCount)
        assertTrue(tombstone.records.single().getValue("purged").jsonPrimitive.boolean)
        assertNull(tombstone.records.single()["payload"])
    }

    @Test fun purgedRecordsCannotBecomeActiveEvenWithCorrectChecksums() {
        val invalid = reseal { kind, value ->
            if (kind == "manifest") {
                val manifest = value.jsonObject
                JsonObject(manifest + ("games" to JsonArray(manifest.getValue("games").jsonArray.map { JsonObject(it.jsonObject + ("purged" to JsonPrimitive(true))) })))
            } else JsonObject(value.jsonObject - "payload" - "projection" + ("purged" to JsonPrimitive(true)))
        }
        assertThrows(IllegalArgumentException::class.java) { AppleTeamArchiveIntegrity.read(invalid.inputStream()) }
    }
}
