package cc.baseballmaster.scorekeeper

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeviceBackupJsonSafetyTest {
    private val snapshot = DeviceSnapshot(null, emptyList(), emptyList(), emptyList(), emptyList())
    private fun replacePayload(payload: ByteArray): ByteArray {
        val outer = Json.parseToJsonElement(AndroidDeviceBackup.encode(snapshot).toString(Charsets.UTF_8)).jsonObject
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        return JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
    }
    @Test fun roundTripKeepsSnapshot() {
        assertEquals(snapshot, AndroidDeviceBackup.decode(AndroidDeviceBackup.encode(snapshot)))
    }
    @Test fun duplicateEnvelopeVersionIsRejected() {
        val bytes = AndroidDeviceBackup.encode(snapshot).toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":2,\"version\":1").toByteArray()
        assertThrows(Exception::class.java) { AndroidDeviceBackup.decode(bytes) }
    }
    @Test fun rehashedDuplicatePayloadFieldsAreRejected() {
        val payload = """{"currentTeamID":null,"teams":[],"games":[],"operations":[],"drafts":[],"games":[]}""".toByteArray()
        assertThrows(Exception::class.java) { AndroidDeviceBackup.decode(replacePayload(payload)) }
    }
    @Test fun rehashedMalformedUtf8IsRejected() {
        val payload = """{"currentTeamID":null,"teams":[],"games":[],"operations":[],"drafts":[],"bad":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { AndroidDeviceBackup.decode(replacePayload(payload)) }
    }
}
