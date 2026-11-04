package cc.baseballmaster.scorekeeper

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
class TeamArchiveRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun database() = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
    private fun archive(): TeamArchiveEntity {
        val bytes = File(System.getProperty("parity.fixtures"), "team-archive/ios-team-reference.bmteam").readBytes()
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return TeamArchiveEntity(id, bytes)
    }

    @Test fun archiveOriginalBytesAndMatchesRestoreTogetherAndFailedCommitLeavesNeither() = runBlocking {
        val source = database(); val restored = database(); val failed = database()
        try {
            val repo = GameRepository(source)
            val team = repo.createTeam("整批恢复", (1..10).joinToString("\n") { "球员$it,$it" })
            repo.createGame(team, "对手", (1..10).joinToString("\n") { "对手$it,$it" }, cc.baseballmaster.core.GameMode.standard, false)
            val original = archive()
            repo.importTeamArchives(listOf(original))
            repo.importTeamArchives(listOf(original))
            assertEquals(1, source.dao().allTeamArchives().size)
            val backup = repo.exportDeviceBackup(team.id)
            assertEquals(3, Json.parseToJsonElement(backup.toString(Charsets.UTF_8)).jsonObject.getValue("version").jsonPrimitive.int)
            val restoredRepo = GameRepository(restored)
            restoredRepo.restoreDeviceBackup(backup)
            assertArrayEquals(original.original, restored.dao().allTeamArchives().single().original)
            assertEquals(source.dao().allGames(), restored.dao().allGames())
            try { GameRepository(failed) { error("disk full before commit") }.restoreDeviceBackup(backup); fail("must roll back") }
            catch (_: IllegalStateException) {}
            assertTrue(failed.dao().allTeamArchives().isEmpty())
            assertTrue(failed.dao().allGames().isEmpty())
            assertTrue(failed.dao().allTeams().isEmpty())
            try { restoredRepo.restoreDeviceBackup(backup); fail("must reject existing archives/data") }
            catch (_: IllegalArgumentException) {}
            assertArrayEquals(original.original, restored.dao().allTeamArchives().single().original)
        } finally { source.close(); restored.close(); failed.close() }
    }

    @Test fun rehashedEnvelopeCannotSmuggleDamagedArchiveOrDuplicateIDs(): Unit = runBlocking {
        val original = archive()
        val snapshot = DeviceSnapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), teamArchives = listOf(DeviceTeamArchive(original.id, Base64.getEncoder().encodeToString(original.original))))
        val bytes = AndroidDeviceBackup.encode(snapshot)
        assertArrayEquals(original.original, Base64.getDecoder().decode(AndroidDeviceBackup.decode(bytes).teamArchives.single().original))
        assertThrows(IllegalArgumentException::class.java) { AndroidDeviceBackup.encode(snapshot.copy(teamArchives = snapshot.teamArchives + snapshot.teamArchives)) }
        val changed = original.original.clone(); changed[changed.size / 2] = (changed[changed.size / 2].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { AndroidDeviceBackup.encode(snapshot.copy(teamArchives = listOf(DeviceTeamArchive(original.id, Base64.getEncoder().encodeToString(changed))))) }
        assertThrows(IllegalArgumentException::class.java) { AndroidDeviceBackup.encode(snapshot.copy(teamArchives = listOf(DeviceTeamArchive("../escape", snapshot.teamArchives.single().original)))) }
    }

    @Test fun legacyMigrationKeepsOriginalAndRefusesPartialImportOnCorruptionOrFailedCommit() = runBlocking {
        val directory = File(context.filesDir, "team-archives")
        val staging = File(context.cacheDir, "team-archive-staging")
        val row = archive()
        check(directory.isDirectory || directory.mkdirs())
        // Historical app versions stored plain files. Seed that authentic layout directly;
        // the current UI saves to Room and does not use the old publication path.
        val originalFile = File(directory, "${row.id}.bmteam")
        originalFile.writeBytes(row.original)
        val db = database()
        try {
            try { GameRepository(db) { error("simulated commit failure") }.migrateLegacyTeamArchives(context); fail("must roll back") }
            catch (_: IllegalStateException) {}
            assertTrue(db.dao().allTeamArchives().isEmpty())
            assertArrayEquals(row.original, originalFile.readBytes())
            val bad = File(directory, "${"a".repeat(64)}.bmteam")
            bad.writeText("corrupt archive")
            try {
                try { GameRepository(db).migrateLegacyTeamArchives(context); fail("must not silently omit invalid file") }
                catch (_: IllegalArgumentException) {}
                assertTrue(db.dao().allTeamArchives().isEmpty())
                assertEquals("corrupt archive", bad.readText())
            } finally { bad.delete() }
            GameRepository(db).migrateLegacyTeamArchives(context)
            GameRepository(db).migrateLegacyTeamArchives(context)
            assertEquals(1, db.dao().allTeamArchives().size)
            assertArrayEquals(row.original, db.dao().allTeamArchives().single().original)
            assertArrayEquals(row.original, originalFile.readBytes())
        } finally { db.close(); originalFile.delete() }
    }

    @Test fun schemaFourUpgradeKeepsExistingTeamsAndCreatesEmptyArchiveTable() = runBlocking {
        val name = "archive-upgrade-${UUID.randomUUID()}.db"
        var db = LocalDatabase.open(context, name)
        try {
            val team = GameRepository(db).createTeam("升级保留", "球员,00")
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                raw.execSQL("DROP TABLE team_archives")
                raw.execSQL("PRAGMA user_version = 4")
            }
            db = LocalDatabase.open(context, name)
            assertEquals(team.id, db.dao().allTeams().single().id)
            assertTrue(db.dao().allTeamArchives().isEmpty())
            GameRepository(db).importTeamArchives(listOf(archive()))
            assertEquals(1, db.dao().allTeamArchives().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
