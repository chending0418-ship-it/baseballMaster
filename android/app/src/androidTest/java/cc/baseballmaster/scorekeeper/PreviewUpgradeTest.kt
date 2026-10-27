package cc.baseballmaster.scorekeeper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the original AN02 emulator receipt; all edits run against a separate database copy. */
@RunWith(AndroidJUnit4::class)
class PreviewUpgradeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun receipt() =
        JSONObject(File(context.getExternalFilesDir(null), "storage-receipt.json").readText())

    @Test
    fun originalPreviewPayloadAndCredentialRemainUnchanged() = runBlocking {
        val rows = receipt().getJSONArray("games")
        assertTrue(rows.length() > 0)
        val db = LocalDatabase.open(context)
        try {
            for (i in 0 until rows.length()) {
                val original = rows.getJSONObject(i)
                val entity = db.dao().game(original.getString("id"))!!
                assertEquals(original.getLong("revision"), entity.revision)
                assertEquals(original.getString("sha256"), sha(entity.payload.toByteArray()))
                GameRepository.decode(entity)
            }
        } finally {
            db.close()
        }
        val originalFingerprint =
            File(context.filesDir, "test-credential-fingerprint.txt").readText()
        assertEquals(originalFingerprint, sha(CredentialVault(context).secret()))
    }

    @Test
    fun legacyCopyCanContinueWithAtomicStatisticsAndUndo() = runBlocking {
        val sourceDB = LocalDatabase.open(context)
        val source =
            try {
                sourceDB
                    .dao()
                    .game(receipt().getJSONArray("games").getJSONObject(0).getString("id"))!!
            } finally {
                sourceDB.close()
            }
        assertEquals(1, source.payloadVersion)
        val legacy = GameRepository.decode(source)
        assertEquals(1, legacy.totalPitches)
        val dbName = "legacy-upgrade-${UUID.randomUUID()}.db"
        val db = LocalDatabase.open(context, dbName)
        try {
            db.dao().insertGame(source)
            val originalEvent = legacy.entries.single()
            db.dao()
                .insertOperation(
                    OperationEntity(
                        originalEvent.id,
                        legacy.id,
                        originalEvent.action!!.name,
                        0,
                        source.updatedAt,
                    )
                )
            val repo = GameRepository(db)
            assertEquals(legacy, repo.pitch(legacy.id, originalEvent.action!!, 0, originalEvent.id))
            assertEquals(source.payload, db.dao().game(legacy.id)!!.payload)
            val continued =
                repo.command(
                    legacy.id,
                    Command("play", outcome = Outcome.single),
                    legacy.revision,
                    "continued",
                )
            assertEquals(4, db.dao().game(legacy.id)!!.payloadVersion)
            assertEquals(2, continued.totalPitches)
            assertEquals(1, continued.awayHits)
            assertEquals(legacy.away.players.first().id, continued.bases[0])
            assertEquals(originalEvent.id, continued.entries.first().id)
            val undo = repo.undo(legacy.id, continued.revision, "undo-upgrade")
            assertEquals(1, undo.totalPitches)
            assertEquals(1, undo.balls)
            assertEquals(0, undo.awayHits)
            val redo = repo.redo(legacy.id, undo.revision, "redo-upgrade")
            assertEquals(continued.batting, redo.batting)
            assertEquals(continued.pitching, redo.pitching)
            assertEquals(continued.bases, redo.bases)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun previewFourCopyUpgradesWithoutRewritingItsOriginalHistory() = runBlocking {
        val source = LocalDatabase.open(context)
        val old =
            try {
                source.dao().allGames().firstOrNull { it.payloadVersion == 3 }
            } finally {
                source.close()
            }
        // The coverage run uses the owned preview.4 emulator state, never a user database.
        assertNotNull("Missing real preview.4 row", old)
        val name = "v4-copy-${UUID.randomUUID()}.db"
        val db = LocalDatabase.open(context, name)
        try {
            db.dao().insertGame(old!!)
            val before = GameRepository.decode(old)
            val repo = GameRepository(db)
            val next =
                repo.command(
                    before.id,
                    Command("note", text = "升级副本校验"),
                    before.revision,
                    "v4-copy",
                )
            assertEquals(4, db.dao().game(before.id)!!.payloadVersion)
            assertEquals(before.situation(), next.situation())
            assertEquals(before.historyRevisions, next.historyRevisions)
            assertEquals(before.totalPitches, next.totalPitches)
            assertEquals(before.situation(), repo.undo(before.id, next.revision).situation())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
