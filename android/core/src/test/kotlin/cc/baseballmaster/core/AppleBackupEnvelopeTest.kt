package cc.baseballmaster.core

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AppleBackupEnvelopeTest {
    private fun envelope(payload: String, version: Int = 7, checksum: String? = null): ByteArray {
        val b = payload.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        return """{"format":"BaseballMaster Backup","version":$version,"createdAt":0,"payload":"${Base64.getEncoder().encodeToString(b)}","checksum":"${checksum ?: hash}"}""".toByteArray()
    }
    private val snapshot = """{"teams":[{"id":"00000000-0000-0000-0000-000000000001","name":"春雷","players":[{"id":"00000000-0000-0000-0000-000000000002","numbers":[0],"jerseyNumbers":["00"]}]}],"currentTeamID":"00000000-0000-0000-0000-000000000001","seasons":[],"playerGameRecords":[],"games":[]}"""
    @Test fun verifiesOriginalBytesAndSupportsKnownVersions() {
        for (v in 1..7) {
            val result = AppleBackupEnvelope.inspect(envelope(snapshot, v))
            assertEquals(v, result.version); assertEquals(1, result.teamCount); assertEquals(0, result.gameCount)
            assertArrayEquals(snapshot.toByteArray(), result.payloadBytes)
        }
    }
    @Test fun actualIOSBackupVerifiesByteForByte() {
        val root = java.io.File(System.getProperty("parity.fixtures"))
        val result = AppleBackupEnvelope.inspect(java.io.File(root, "backup/ios-reference-v7.bmbackup").readBytes())
        assertEquals(7, result.version); assertEquals(1, result.teamCount)
        assertEquals(2, result.opponentCount); assertEquals(1, result.gameCount)
    }
    @Test fun archivedSnapshotMayShareIdentityWithLiveRoster() {
        val archived = """[{"id":"00000000-0000-0000-0000-000000000001","name":"春雷","players":[{"id":"00000000-0000-0000-0000-000000000002","numbers":[0]}]}]"""
        val payload = snapshot.replace("\"games\":[]}", "\"games\":[],\"archivedTeams\":$archived}")
        assertEquals(1, AppleBackupEnvelope.inspect(envelope(payload)).teamCount)
    }
    @Test fun corruptionAndUnknownVersionsAreRejectedBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) { AppleBackupEnvelope.inspect(envelope(snapshot, checksum = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { AppleBackupEnvelope.inspect(envelope(snapshot, version = 8)) }
        assertThrows(IllegalArgumentException::class.java) { AppleBackupEnvelope.inspect(envelope(snapshot.replace("\"00\"", "\"01\""))) }
        assertThrows(IllegalArgumentException::class.java) { AppleBackupEnvelope.inspect(envelope(snapshot.replace("00000000-0000-0000-0000-000000000001", "0-0-0-0-1"))) }
    }
}
