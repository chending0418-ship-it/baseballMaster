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
class ObservationCreationTest {
    @Test fun observationPersistsWithoutOwningTeamAndNeverEntersTeamStatistics(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db)
            val roster = (1..12).joinToString("\n") { "球员$it,$it" }
            val own = repo.createTeam("本队", roster)
            val away = repo.createTeam("观赛客队", roster, true)
            val home = repo.createTeam("观赛主队", roster, true)
            val prepared = MatchFactory.create(UUID.randomUUID().toString(), home, away, Rules())
            try { repo.createPreparedGame(prepared); fail("observation requires explicit entry") } catch (_: IllegalArgumentException) { }
            val game = repo.createObservationGame(prepared)
            val scored = repo.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision)
            val final = repo.command(game.id, Command("finish", text = "scorerDecision"), scored.revision)
            assertNull(final.ourTeamID); assertEquals(1, final.awayRuns)
            assertTrue(SeasonStatisticsCalculator.calculate(own, listOf(final), java.time.Year.now().value).games.isEmpty())
            assertEquals("暂无战绩", HomeSummary.record(listOf(final), own.id).label)
            GameRepository(restored).restoreDeviceBackup(repo.exportDeviceBackup(own.id))
            assertEquals(final, GameRepository.decode(restored.dao().allGames().single()))
            val bad = prepared.copy(id = UUID.randomUUID().toString(), home = own)
            try { repo.createObservationGame(bad); fail("owned teams are not observation opponents") } catch (_: IllegalArgumentException) { }
            val candidate = prepared.copy(id = UUID.randomUUID().toString())
            try { GameRepository(db) { error("commit failed") }.createObservationGame(candidate); fail("must roll back") } catch (_: IllegalStateException) { }
            assertNull(db.dao().game(candidate.id))
        } finally { db.close(); restored.close() }
    }
    @Test fun pastMatchDateSurvivesFirstPitchCompletionAndAnnualClassification(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db); val roster = (1..12).joinToString("\n") { "球员$it,$it" }
            val own = repo.createTeam("补录本队", roster); val opponent = repo.createTeam("补录对手", roster, true)
            val past = parseMatchDate("2025-06-10 10:00", "Asia/Shanghai")
            val prepared = MatchFactory.create(UUID.randomUUID().toString(), opponent, own, Rules(), own.id).also { it.playedAt = past }
            var game = repo.createPreparedGame(prepared)
            game = repo.pitch(game.id, PitchAction.ball, game.revision)
            assertEquals(past, game.playedAt)
            game = repo.command(game.id, Command("finish", text = "weather"), game.revision)
            assertEquals(1, SeasonStatisticsCalculator.calculate(own, listOf(game), 2025).games.size)
            assertTrue(SeasonStatisticsCalculator.calculate(own, listOf(game), 2026).games.isEmpty())
        } finally { db.close() }
    }
}
