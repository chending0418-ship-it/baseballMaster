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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduledPlanningStorageTest {
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
    private fun players() = Roster.parse((1..12).joinToString("\n") { "球员$it,$it" })
    @Test fun emptyTeamPlanBacksUpAndUsesRealRostersWhenConfigured(): Unit = runBlocking {
        val db = database(); val restored = database()
        try {
            val repo = GameRepository(db)
            val own = repo.createRoster("未配本队", "本队", "", false); val opponent = repo.createRoster("未配对手", "对手", "", true)
            val planned = repo.createPreparedGame(MatchFactory.schedule(UUID.randomUUID().toString(), opponent, own, Rules(), System.currentTimeMillis()+86_400_000, own.id))
            assertEquals(5, db.dao().game(planned.id)!!.payloadVersion)
            GameRepository(restored).restoreDeviceBackup(repo.exportDeviceBackup(own.id))
            assertEquals(planned, GameRepository.decode(restored.dao().game(planned.id)!!))
            val home = opponent.copy(players = players()); val away = own.copy(players = players())
            repo.updateRoster(home); repo.updateRoster(away)
            val rescheduled = repo.command(planned.id, Command("schedule", dateMillis = planned.scheduledAt!! + 60_000, timeZoneID = "Asia/Shanghai"), planned.revision)
            assertTrue(rescheduled.scheduledSetupPending); assertNull(rescheduled.playedAt)
            assertEquals(5, db.dao().game(planned.id)!!.payloadVersion)
            val prepared = MatchFactory.create(planned.id, home, away, Rules(recordingMethod = "situation"), own.id)
            val configured = repo.configureScheduledGame(prepared, rescheduled.revision)
            val savedName = configured.away.players.first().name
            prepared.away.players.first().name = "外部草稿变化"
            assertEquals(savedName, configured.away.players.first().name)
            assertEquals(savedName, GameRepository.decode(db.dao().game(planned.id)!!).away.players.first().name)
            assertFalse(configured.isScheduled); assertFalse(configured.scheduledSetupPending); assertFalse(configured.openingPitchRecorded)
            assertEquals(rescheduled.scheduledAt, configured.scheduledAt); assertEquals(planned.createdAt, configured.createdAt)
            assertEquals(away.players.map { it.id }, configured.away.players.map { it.id }); assertEquals(4, db.dao().game(planned.id)!!.payloadVersion)
            assertEquals(2, db.dao().allOperations().size)
            val pitched = repo.pitch(configured.id, PitchAction.ball, configured.revision)
            assertTrue(pitched.openingPitchRecorded); assertEquals(1, pitched.balls)
            assertNotNull(pitched.startedAt); assertEquals(pitched.startedAt, MatchDates.effectivePlayedAt(pitched))
        } finally { db.close(); restored.close() }
    }
    @Test fun observationPlanningRejectsStaleRostersAndRollsBackConfiguration(): Unit = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db)
            val away = repo.createRoster("观赛安排客", "客", "", true); val home = repo.createRoster("观赛安排主", "主", "", true)
            val plan = repo.createObservationGame(MatchFactory.schedule(UUID.randomUUID().toString(), home, away, Rules(), System.currentTimeMillis()+86_400_000))
            val a = away.copy(players = players()); val h = home.copy(players = players()); repo.updateRoster(a); repo.updateRoster(h)
            val prepared = MatchFactory.create(plan.id, h, a, Rules())
            try { repo.configureScheduledGame(prepared, -1); fail("stale write") } catch (_: IllegalStateException) { }
            try { GameRepository(db) { error("commit failed") }.configureScheduledGame(prepared, 0); fail("rollback") } catch (_: IllegalStateException) { }
            assertEquals(plan, GameRepository.decode(db.dao().game(plan.id)!!)); assertTrue(db.dao().allOperations().isEmpty())
            repo.updateRoster(a.copy(players = a.players + Player(UUID.randomUUID().toString(), "新增球员", "99")))
            try { repo.configureScheduledGame(prepared, 0); fail("stale roster") } catch (_: IllegalArgumentException) { }
            val refreshed = a.copy(players = GameJson.decodeFromString<Team>(db.dao().team(a.id)!!.payload).players)
            val configured = repo.configureScheduledGame(MatchFactory.create(plan.id, h, refreshed, Rules()), 0)
            assertNull(configured.ourTeamID); assertTrue(configured.isObservation); assertEquals(0, configured.totalPitches)
            val backup = repo.exportDeviceBackup(null); val check = database()
            try { GameRepository(check).restoreDeviceBackup(backup); assertEquals(configured, GameRepository.decode(check.dao().game(plan.id)!!)) } finally { check.close() }
        } finally { db.close() }
    }
    @Test fun pendingFormatIsExplicitAndFutureFormatsRemainReadOnly() {
        val game = MatchFactory.schedule("format", Team("h", "主", emptyList()), Team("a", "客", emptyList(), true), Rules(), 1_900_000_000_000, "h")
        val row = GameEntity(game.id, GameJson.encodeToString(game), 0, 0, 5)
        assertEquals(game, GameRepository.decode(row))
        for (version in listOf(4,6,99)) try { GameRepository.decode(row.copy(payloadVersion = version)); fail("unexpected format") } catch (_: IllegalStateException) { }
    }
}
