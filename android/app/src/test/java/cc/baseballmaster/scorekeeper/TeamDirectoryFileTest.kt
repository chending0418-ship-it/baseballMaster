package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AtomicFile/AES-GCM under Android JVM; no Activity/UI, no real Keystore claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class TeamDirectoryFileTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun state(): TeamDirectoryState {
        val epoch = UUID.randomUUID().toString()
        return TeamDirectoryState(directory = TeamDirectory(emptyList(), epoch), pending = PendingDirectoryRequest(
            DirectoryRequestKind.join, buildJsonObject { put("operationID", UUID.randomUUID().toString()); put("token", TeamSecrets.random()); put("nickname", "成员称呼") }, epoch))
    }
    private fun folder() = Files.createTempDirectory("android-team-directory-").toFile()

    @Test fun encryptedPendingRequestSurvivesReopenWithoutWritingAnyPlaintextSecret() {
        val dir = folder()
        try {
            val value = state(); val store = TeamDirectoryFile(dir, { key.copyOf() })
            assertEquals(TeamDirectoryState(), store.load()); store.save(value)
            assertEquals(value, TeamDirectoryFile(dir, { key.copyOf() }).load())
            val bytes = File(dir,"directory-v1.sealed").readBytes()
            for (text in listOf("成员称呼", "operationID", value.pending!!.body.getValue("token").jsonPrimitive.content))
                assertFalse(String(bytes, Charsets.UTF_8).contains(text))
            store.save(value); assertFalse(bytes.contentEquals(File(dir,"directory-v1.sealed").readBytes()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedReplacementRetainsTheOriginalPendingRequestAndExactCiphertext() {
        val dir = folder()
        try {
            val store = TeamDirectoryFile(dir, { key.copyOf() }); val original = state(); store.save(original)
            val sealed = File(dir,"directory-v1.sealed").readBytes()
            val failing = TeamDirectoryFile(dir, { key.copyOf() }) { throw IOException("injected full") }
            assertThrows(IOException::class.java) { failing.save(original.copy(pending = null)) }
            assertArrayEquals(sealed, File(dir,"directory-v1.sealed").readBytes()); assertEquals(original, store.load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptionAndLostKeyFailClosedAndNeverReplaceTheOriginalFile() {
        val dir = folder()
        try {
            val store = TeamDirectoryFile(dir, { key.copyOf() }); store.save(state())
            val file = File(dir,"directory-v1.sealed"); val before = file.readBytes()
            assertThrows(Exception::class.java) { TeamDirectoryFile(dir, { ByteArray(32) { 9 } }).load() }
            assertArrayEquals(before, file.readBytes())
            val corrupt = before.copyOf(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte(); file.writeBytes(corrupt)
            assertThrows(Exception::class.java) { store.load() }; assertArrayEquals(corrupt, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
