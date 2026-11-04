package cc.baseballmaster.scorekeeper

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceBackupTest {
    @Test fun preservesLedgerAndDraftAndRollsBackEveryFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun db() = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val source = db(); val target = db(); val failed = db()
        try {
            val repo = GameRepository(source)
            val roster = (1..10).joinToString("\n") { "备份球员$it,$it" }
            val team = repo.createTeam("备份球队", roster)
            var game = repo.createGame(team, "备份对手", roster, GameMode.standard, false)
            game = repo.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision)
            val draft = CorrectionDraft(game.id, game.revision, game.checkpoint!!, game.journal, "测试保留草稿")
            repo.saveCorrectionDraft(draft)
            val bytes = repo.exportDeviceBackup(team.id)
            assertEquals(team.id, GameRepository(target).restoreDeviceBackup(bytes))
            assertEquals(source.dao().allTeams().toSet(), target.dao().allTeams().toSet())
            assertEquals(source.dao().allGames().toSet(), target.dao().allGames().toSet())
            assertEquals(source.dao().allOperations().toSet(), target.dao().allOperations().toSet())
            assertEquals(source.dao().allCorrectionDrafts().toSet(), target.dao().allCorrectionDrafts().toSet())
            try { GameRepository(target).restoreDeviceBackup(bytes); fail("must not overwrite") } catch (_: IllegalArgumentException) {}
            assertEquals(source.dao().allGames().toSet(), target.dao().allGames().toSet())
            try { GameRepository(failed) { error("simulated failure") }.restoreDeviceBackup(bytes); fail("must roll back") } catch (_: IllegalStateException) {}
            assertTrue(failed.dao().allTeams().isEmpty()); assertTrue(failed.dao().allGames().isEmpty())
            val damaged = bytes.clone(); damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 1).toByte()
            try { GameRepository(failed).restoreDeviceBackup(damaged); fail("must reject damage") } catch (_: Exception) {}
            assertTrue(failed.dao().allTeams().isEmpty()); assertTrue(failed.dao().allGames().isEmpty())
        } finally { source.close(); target.close(); failed.close() }
    }
}
