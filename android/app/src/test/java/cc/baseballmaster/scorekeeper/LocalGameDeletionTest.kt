package cc.baseballmaster.scorekeeper

import androidx.room.Room
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalGameDeletionTest {
    @Test fun revisionProtectionAndFailedCommitPreserveGameAndOperations(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db)
            val roster = (1..10).joinToString("\n") { "球员$it,$it" }
            val team = repo.createTeam("删除隔离", roster)
            val original = repo.createGame(team, "对手", roster, GameMode.standard, false)
            val other = repo.createGame(team, "另一场", roster, GameMode.standard, false)
            val updated = repo.pitch(original.id, PitchAction.ball, original.revision)
            val operations = db.dao().allOperations()
            try { repo.deleteGame(updated.id, original.revision); fail("stale deletion must fail") } catch (_: IllegalStateException) { }
            assertNotNull(db.dao().game(updated.id))
            try { GameRepository(db) { error("commit failed") }.deleteGame(updated.id, updated.revision); fail("must roll back") } catch (_: IllegalStateException) { }
            assertNotNull(db.dao().game(updated.id)); assertEquals(operations, db.dao().allOperations())
            repo.deleteGame(updated.id, updated.revision)
            assertNull(db.dao().game(updated.id)); assertNotNull(db.dao().game(other.id))
            assertFalse(db.dao().allOperations().any { it.gameID == updated.id })
            assertNotNull(db.dao().team(team.id))
            val backup = AndroidDeviceBackup.decode(repo.exportDeviceBackup(team.id))
            assertEquals(listOf(other.id), backup.games.map { it.id })
        } finally { db.close() }
    }
    @Test fun importedArchiveIsProtectedAndStillRestorable(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db)
            val bytes = java.io.File(System.getProperty("parity.fixtures"), "backup/ios-correction-reference.bmbackup").readBytes()
            repo.importAppleBackup(bytes)
            val game = GameRepository.decode(db.dao().allGames().single())
            try { repo.deleteGame(game.id, game.revision); fail("cannot delete one row of a source archive") } catch (_: IllegalArgumentException) { }
            GameRepository(restored).restoreDeviceBackup(repo.exportDeviceBackup(game.ourTeamID))
            assertEquals(game, GameRepository.decode(restored.dao().allGames().single()))
        } finally { db.close(); restored.close() }
    }

}
