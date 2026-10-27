package cc.baseballmaster.scorekeeper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val roster = (1..10).joinToString("\n") { "球员$it,${if (it == 1) "00" else it}" }
    @Test fun pitchReopenIdempotencyConflictAndRollback() = runBlocking {
        val name = "storage-test-${UUID.randomUUID()}.db"
        var db = LocalDatabase.open(context, name)
        try {
            val repository = GameRepository(db)
            val team = repository.createTeam("客队", roster)
            assertEquals("00", team.players.first().number)
            val game = repository.createGame(team, "主队", roster, GameMode.standard, false)
            val recorded = repository.pitch(game.id, PitchAction.ball, 0, "operation-1")
            assertEquals(1, recorded.balls)
            assertEquals(recorded, repository.pitch(game.id, PitchAction.ball, 0, "operation-1"))
            assertEquals(1, db.dao().operationCount(game.id))
            try { repository.pitch(game.id, PitchAction.foul, 0, "operation-1"); fail("changed retry accepted") } catch (_: IllegalArgumentException) {}
            try { repository.pitch(game.id, PitchAction.foul, 0, "stale"); fail("stale revision accepted") } catch (_: IllegalStateException) {}
            val failing = GameRepository(db) { error("injected transaction failure") }
            try { failing.pitch(game.id, PitchAction.calledStrike, 1, "will-rollback"); fail("fault swallowed") } catch (_: IllegalStateException) {}
            assertNull(db.dao().operation("will-rollback"))
            assertEquals(1, db.dao().operationCount(game.id))
            db.close(); db = LocalDatabase.open(context, name)
            val restored = GameJson.decodeFromString<Game>(db.dao().game(game.id)!!.payload)
            assertEquals(recorded, restored)
            assertEquals(1L, restored.revision)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun correctionDraftAuditAndOperationCommitTogetherWithRollbackAndRetry() = runBlocking {
        val name="history-storage-${UUID.randomUUID()}.db"
        var db=LocalDatabase.open(context,name)
        try {
            var repo=GameRepository(db)
            val team=repo.createTeam("纠错合成球队",roster)
            val fresh=repo.createGame(team,"合成对手",roster,GameMode.standard,false)
            val original=repo.command(fresh.id,Command("play",outcome=Outcome.single),fresh.revision,"single")
            val initial=History.draft(original)
            val draft=initial.copy(reason="原球为二垒安打",records=initial.records.map{it.copy(command=it.command.copy(outcome=Outcome.double),edited=true)})
            repo.saveCorrectionDraft(draft)
            db.close();db=LocalDatabase.open(context,name);repo=GameRepository(db)
            assertEquals(draft,repo.loadCorrectionDraft(original.id))
            val oldRow=db.dao().game(original.id)!!
            try {GameRepository(db){error("injected")}.saveCorrection(draft,"correction");fail("Failed correction committed")}catch(_:IllegalStateException){}
            assertEquals(oldRow,db.dao().game(original.id));assertEquals(draft,repo.loadCorrectionDraft(original.id));assertNull(db.dao().operation("correction"))
            val corrected=repo.saveCorrection(draft,"correction")
            assertEquals(1,corrected.historyRevisions.size);assertNotNull(corrected.bases[1]);assertNull(corrected.bases[0])
            assertNull(repo.loadCorrectionDraft(original.id));assertEquals(corrected,repo.saveCorrection(draft,"correction"))
            assertEquals(2,db.dao().operationCount(original.id))
            val stale=draft.copy(reason="旧草稿保留")
            repo.saveCorrectionDraft(stale)
            val before=db.dao().game(original.id)!!
            try {repo.saveCorrection(stale,"stale-correction");fail("Stale draft overwrote scoring")}catch(_:IllegalStateException){}
            assertEquals(before,db.dao().game(original.id));assertEquals(stale,repo.loadCorrectionDraft(original.id));assertNull(db.dao().operation("stale-correction"))
            assertEquals(3,before.payloadVersion)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun futureDatabaseVersionIsRejectedWithoutDeletingData() = runBlocking {
        val name = "future-test-${UUID.randomUUID()}.db"
        val db = LocalDatabase.open(context, name)
        val team = GameRepository(db).createTeam("保留球队", roster)
        db.close()
        context.openOrCreateDatabase(name, 0, null).use { it.version = 99 }
        val future = LocalDatabase.open(context, name)
        try {
            try { future.dao().game("missing"); fail("future database was accepted") } catch (_: IllegalStateException) {}
        } finally { future.close() }
        context.openOrCreateDatabase(name, 0, null).use { raw ->
            assertEquals(99, raw.version)
            raw.rawQuery("SELECT name FROM teams WHERE id = ?", arrayOf(team.id)).use { row -> assertTrue(row.moveToFirst()); assertEquals("保留球队", row.getString(0)) }
        }
        context.deleteDatabase(name)
        Unit
    }
    @Test fun credentialIsEncryptedAndStableAcrossVaultInstances() {
        val first = CredentialVault(context).secret()
        assertEquals(32, first.size)
        assertArrayEquals(first, CredentialVault(context).secret())
        val saved = context.getSharedPreferences("device_credentials", 0).getString("sealed", null)!!
        assertFalse(saved.contains(android.util.Base64.encodeToString(first, android.util.Base64.NO_WRAP)))
    }
    @Test fun corruptCredentialIsPreservedAndNeverSilentlyReplaced() {
        val preferences = context.getSharedPreferences("device_credentials", 0)
        CredentialVault(context).secret()
        val original = preferences.getString("sealed", null)!!
        val corrupt = android.util.Base64.encodeToString(ByteArray(40), android.util.Base64.NO_WRAP)
        preferences.edit().putString("sealed", corrupt).commit()
        try {
            try { CredentialVault(context).secret(); fail("corrupt credential accepted") } catch (_: Exception) {}
            assertEquals(corrupt, preferences.getString("sealed", null))
        } finally { preferences.edit().putString("sealed", original).commit() }
    }

}
