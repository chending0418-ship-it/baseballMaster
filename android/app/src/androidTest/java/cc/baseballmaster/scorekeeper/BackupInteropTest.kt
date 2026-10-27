package cc.baseballmaster.scorekeeper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupInteropTest {
    @Test fun rosterImportIsAtomicAndConflictsNeverOverwrite() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bytes = inst.context.assets.open("ios-reference-v7.bmbackup").use { it.readBytes() }
        val rosters = AppleRosterBackup.decode(AppleBackupEnvelope.inspect(bytes))
        val db = Room.inMemoryDatabaseBuilder(inst.targetContext, LocalDatabase::class.java).build()
        try {
            GameRepository(db).importRosters(rosters)
            assertEquals(3, db.dao().allTeams().size)
            val original = db.dao().allTeams().map { it.id to it.payload }.toMap()
            try { GameRepository(db).importRosters(rosters); fail("Duplicate IDs must be rejected") }
            catch (_: IllegalArgumentException) {}
            assertEquals(original, db.dao().allTeams().map { it.id to it.payload }.toMap())
        } finally { db.close() }
        val failed = Room.inMemoryDatabaseBuilder(inst.targetContext, LocalDatabase::class.java).build()
        try {
            try { GameRepository(failed) { error("simulated write failure") }.importRosters(rosters); fail("Failure must roll back") }
            catch (_: IllegalStateException) {}
            assertTrue(failed.dao().allTeams().isEmpty())
        } finally { failed.close() }
    }
    @Test fun authenticIOSBackupVerifiesAndAndroidRosterMatchesIOSReadFixture() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val ios = assets.open("ios-reference-v7.bmbackup").use { it.readBytes() }
        val result = AppleBackupEnvelope.inspect(ios)
        assertEquals(7, result.version); assertEquals(1, result.gameCount)
        assertEquals(1, result.teamCount); assertEquals(2, result.opponentCount)
        val player = Player("a1000000-0000-0000-0000-000000000001", "林安", "00", englishName = "An Lin", position = 6, jerseyNumbers = listOf("00", "7"), chineseName = "林安")
        val team = Team("a1000000-0000-0000-0000-000000000000", "合成春雷", listOf(player), city = "合成城市", shortName = "春雷")
        val android = AppleRosterBackup.encode(listOf(team), emptyList(), team.id, 1_790_000_000_000)
        assertArrayEquals(assets.open("android-roster-reference-v7.bmbackup").use { it.readBytes() }, android)
        assertEquals(0, AppleBackupEnvelope.inspect(android).gameCount)
        val corrupted = ios.clone(); corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { AppleBackupEnvelope.inspect(corrupted) }
    }
}
