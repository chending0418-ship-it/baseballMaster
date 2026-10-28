package cc.baseballmaster.scorekeeper

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamArchiveStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = File(temporary.root, "library")
    private val staging get() = File(temporary.root, "staging")
    private fun store(beforePublish: () -> Unit = {}, limit: Long = 100_000_000) = TeamArchiveStore(directory, staging, beforePublish, limit)
    private fun source() = File(System.getProperty("parity.fixtures"), "team-archive/ios-team-reference.bmteam").readBytes()

    /** A second valid synthetic snapshot of the same authentic game; not a legacy-version fixture. */
    private fun differentSource(): ByteArray {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun line(kind: String, payload: ByteArray): ByteArray = (buildJsonObject {
            put("kind", kind); put("payload", Base64.getEncoder().encodeToString(payload)); put("checksum", hash(payload))
        }.toString() + "\n").toByteArray()
        val rows = source().toString(Charsets.UTF_8).trimEnd().lines()
        val first = Json.parseToJsonElement(rows.first()).jsonObject
        val manifest = Json.parseToJsonElement(Base64.getDecoder().decode(first.getValue("payload").jsonPrimitive.content).toString(Charsets.UTF_8)).jsonObject
        val changed = JsonObject(manifest + ("snapshot" to JsonPrimitive("another-synthetic-snapshot")))
        val body = line("manifest", changed.toString().toByteArray()) + (rows.drop(1).dropLast(1).joinToString("\n") + "\n").toByteArray()
        return body + line("seal", JsonPrimitive(hash(body)).toString().toByteArray())
    }

    @Test fun preservesExactOriginalBytesAndRepeatedImportIsIdempotent() {
        val bytes = source()
        val store = store()
        val staged = store.stage(bytes.inputStream())
        try {
            assertEquals("互导测试球队", staged.summary.teamName)
            val first = store.save(staged)
            assertFalse(first.alreadyPresent)
            val again = store.save(staged)
            assertTrue(again.alreadyPresent)
            assertEquals(first.item, again.item)
            val listing = store.list()
            assertEquals(listOf(first.item), listing.items)
            assertEquals(0, listing.unreadableCount)
            assertEquals(1, directory.listFiles()!!.size)
            val output = ByteArrayOutputStream()
            store.export(first.item.id, output)
            assertArrayEquals(bytes, output.toByteArray())
            val parsed = store.read(first.item.id)
            assertEquals(1, parsed.records.size)
            assertEquals(first.item.summary, parsed.summary)
        } finally { store.discard(staged) }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test fun badOrTruncatedInputNeverAppearsInLibraryAndLeavesNoStage() {
        val store = store()
        val bytes = source()
        assertThrows(Exception::class.java) { store.stage(bytes.dropLast(10).toByteArray().inputStream()) }
        assertThrows(Exception::class.java) { store.stage("not an archive".byteInputStream()) }
        assertTrue(store.list().items.isEmpty())
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test fun sizeLimitAndInterruptedCopyRemoveOnlyTheirOwnTemporaryFile() {
        val store = store(limit = 8)
        assertThrows(IllegalArgumentException::class.java) { store.stage(ByteArray(9).inputStream()) }
        assertTrue(staging.listFiles()!!.isEmpty())
        val input = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("simulated interrupted provider")
        }
        assertThrows(InterruptedIOException::class.java) { store.stage(input) }
        assertTrue(staging.listFiles()!!.isEmpty())
        assertFalse(directory.exists())
    }

    @Test fun writeFailureCannotOverwriteExistingArchiveOrPublishPartialCopy() {
        val original = store()
        val staged = original.stage(source().inputStream())
        val saved = original.save(staged)
        original.discard(staged)
        val originalFile = File(directory, "${saved.item.id}.bmteam")
        val bytes = originalFile.readBytes()
        val failing = store(beforePublish = { error("simulated storage failure") })
        val pending = failing.stage(differentSource().inputStream())
        try { assertThrows(IllegalStateException::class.java) { failing.save(pending) } }
        finally { failing.discard(pending) }
        assertEquals(listOf(originalFile.name), directory.listFiles()!!.map { it.name })
        assertArrayEquals(bytes, originalFile.readBytes())
        assertEquals(1, original.list().items.size)
    }

    @Test fun changedStagingOrExistingFileIsRejectedWithoutReplacement() {
        val store = store()
        val pending = store.stage(source().inputStream())
        pending.file.appendText("changed")
        try { assertThrows(IllegalArgumentException::class.java) { store.save(pending) } }
        finally { store.discard(pending) }
        assertTrue(store.list().items.isEmpty())
        val valid = store.stage(source().inputStream())
        val saved = store.save(valid)
        val existing = File(directory, "${saved.item.id}.bmteam")
        existing.appendText("existing corruption")
        val corrupted = existing.readBytes()
        try { assertThrows(IllegalArgumentException::class.java) { store.save(valid) } }
        finally { store.discard(valid) }
        assertArrayEquals(corrupted, existing.readBytes())
        assertEquals(1, store.list().unreadableCount)
        assertTrue(store.list().items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.read(saved.item.id) }
    }

    @Test fun competingDestinationIsNeverReplacedByPublish() {
        var destination: File? = null
        val conflict = "a conflicting destination must survive".toByteArray()
        val store = store(beforePublish = { destination!!.writeBytes(conflict) })
        val pending = store.stage(source().inputStream())
        destination = File(directory, "${pending.digest}.bmteam")
        try { assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { store.save(pending) } }
        finally { store.discard(pending) }
        assertArrayEquals(conflict, destination!!.readBytes())
        assertEquals(listOf(destination!!.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun fileIDsCannotSelectAnythingOutsideLibrary() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.read("../../credentials") }
        assertThrows(IllegalArgumentException::class.java) { store.export("../other", ByteArrayOutputStream()) }
        assertFalse(directory.exists())
    }

    @Test fun cancellationWhileStagingNeverCreatesAnImportableFile() {
        val store = store()
        assertThrows(InterruptedIOException::class.java) {
            store.stage(source().inputStream()) { throw InterruptedIOException("cancelled") }
        }
        assertTrue(staging.listFiles()!!.isEmpty())
        assertFalse(directory.exists())
    }
}
