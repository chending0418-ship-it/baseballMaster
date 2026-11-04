package cc.baseballmaster.scorekeeper

import androidx.room.Room
import cc.baseballmaster.core.*
import java.util.UUID
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
class SharedRosterLocalGameTest {
    private fun id() = UUID.randomUUID().toString()
    private fun profile() = SharedTeamProfile(id(), "共享名单", 5, "共享", "上海", (0..9).map {
        SharedRosterPlayer(id(), id(), "球员$it", "", listOf(if (it == 0) "00" else it.toString()))
    })
    private fun prepared(profile: SharedTeamProfile): Game {
        val own = LocalMatchSource.sharedSnapshot(profile)
        val other = Team(id(), "对手", (1..10).map { Player(id(), "对手$it", it.toString()) }, true)
        return MatchFactory.create(id(), own, other, Rules(), own.id).also {
            it.sharedRosterOrigin = SharedRosterOrigin.from(profile, RemoteTeam(profile.id, profile.name, profile.revision, TeamRole.member, id()))
        }
    }
    @Test fun snapshotPersistsOfflineScoringUndoFinishAndBackupWithoutImportingRosterOrAuthority() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db)
            val initial = prepared(profile())
            var game = repo.createSharedRosterGame(initial)
            assertEquals(initial.sharedRosterOrigin, game.sharedRosterOrigin)
            assertFalse(db.dao().allTeams().any { it.id == game.ourTeamID })
            assertEquals("00", game.home.players.first().number)
            game = repo.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision)
            game = repo.undo(game.id, game.revision)
            game = repo.redo(game.id, game.revision)
            game = repo.command(game.id, Command("finish", text = "scorerDecision"), game.revision)
            assertTrue(game.isFinal)
            assertEquals(initial.id, game.id)
            assertEquals(initial.sharedRosterOrigin, game.sharedRosterOrigin)
            val restoredRepo = GameRepository(restored)
            restoredRepo.restoreDeviceBackup(repo.exportDeviceBackup(null))
            assertEquals(game, GameRepository.decode(restored.dao().allGames().single()))
            assertFalse(restored.dao().allTeams().any { it.id == game.ourTeamID })
        } finally { db.close(); restored.close() }
    }
    @Test fun wrongEntryAndConflictingLocalIdentityAbortWithoutCreatingAnyPartialData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db)
            val game = prepared(profile())
            try { repo.createPreparedGame(game); fail("Requires separate snapshot entry") } catch (_: IllegalArgumentException) { }
            assertTrue(db.dao().allGames().isEmpty()); assertTrue(db.dao().allTeams().isEmpty())
            repo.importRosters(listOf(game.home.copy(source = "local")))
            val prior = db.dao().allTeams()
            try { repo.createSharedRosterGame(game); fail("Conflicting identity must not overwrite a local team") } catch (_: IllegalArgumentException) { }
            assertTrue(db.dao().allGames().isEmpty()); assertEquals(prior, db.dao().allTeams())
        } finally { db.close() }
    }
    @Test fun failureBeforeCommitRollsBackBothOpponentAndGame() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db) { error("synthetic commit failure") }
            try { repo.createSharedRosterGame(prepared(profile())); fail("Expected rollback") } catch (_: IllegalStateException) { }
            assertTrue(db.dao().allGames().isEmpty()); assertTrue(db.dao().allTeams().isEmpty())
        } finally { db.close() }
    }
}
