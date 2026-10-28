package cc.baseballmaster.scorekeeper

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import cc.baseballmaster.core.*
import java.io.File
import kotlinx.coroutines.*
import java.util.Base64
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** JVM Android/SQLite integration only: no Activity, emulator, screen or UI automation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AppleImportTransactionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun source() = File(System.getProperty("parity.fixtures"), "backup/ios-correction-reference.bmbackup").readBytes()
    private fun artifact(name: String, bytes: ByteArray) {
        val output = File(requireNotNull(System.getProperty("apple.source.output"))); output.mkdirs(); File(output, name).writeBytes(bytes)
    }
    private fun database() = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()

    @Test fun importIsPersistentIdempotentAndKeepsOriginalAudit() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val bytes = source()
            val imported = repo.importAppleBackup(bytes)
            assertFalse(imported.alreadyPresent)
            assertEquals(1, db.dao().allGames().size)
            assertEquals(1, db.dao().allAppleBackups().size)
            assertEquals(1, db.dao().allAppleGameSources().size)
            val game = GameRepository.decode(db.dao().allGames().single())
            assertEquals(1, game.historyRevisions.size); assertTrue(game.statisticsIncomplete)
            assertArrayEquals(bytes, repo.exportAppleBackup(imported.id))
            val again = repo.importAppleBackup(bytes)
            assertTrue(again.alreadyPresent); assertEquals(imported.id, again.id)
            assertEquals(1, db.dao().allGames().size)
            assertArrayEquals(bytes, db.dao().appleBackup(imported.id)!!.original)
        } finally { db.close() }
    }

    @Test fun pitchAndSourceCommitTogetherAndRetryDoesNotAppendTwice() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val bytes = source(); val receipt = repo.importAppleBackup(bytes)
            val game = GameRepository.decode(db.dao().allGames().single())
            val id = "f7000000-0000-0000-0000-000000000001"
            val command = Command("pitch", pitch = PitchAction.calledStrike, at = 1_800_000_000_000)
            val next = repo.command(game.id, command, game.revision, id)
            assertEquals(game.revision + 1, next.revision); assertEquals(1, next.strikes)
            val edited = repo.exportAppleBackup(receipt.id)
            assertFalse(bytes.contentEquals(edited))
            assertArrayEquals(bytes, db.dao().appleBackup(receipt.id)!!.original)
            val retry = repo.command(game.id, command, game.revision, id)
            assertEquals(next.revision, retry.revision); assertArrayEquals(edited, repo.exportAppleBackup(receipt.id))
            assertEquals(1, db.dao().allOperations().size)
            assertTrue(repo.importAppleBackup(bytes).alreadyPresent)
            assertArrayEquals(edited, repo.exportAppleBackup(receipt.id))
            val beforeUnsupported = db.dao().game(game.id)!!
            try { repo.undo(game.id, next.revision); fail("Imported history cannot be silently rewritten") } catch (_: IllegalArgumentException) { }
            assertEquals(beforeUnsupported, db.dao().game(game.id))
            assertArrayEquals(edited, repo.exportAppleBackup(receipt.id))
        } finally { db.close() }
    }

    @Test fun diskFullDuringImportRollsBackAllTablesAndProtectsExistingTeam() = runBlocking {
        val db = database()
        try {
            val plain = GameRepository(db)
            val existing = plain.createTeam("原有球队", "球员一,1")
            val before = db.dao().allTeams()
            val failing = GameRepository(db) { throw SQLiteFullException("injected full") }
            try { failing.importAppleBackup(source()); fail("Expected failure") } catch (_: SQLiteFullException) { }
            assertEquals(before, db.dao().allTeams()); assertEquals(existing.id, db.dao().allTeams().single().id)
            assertTrue(db.dao().allGames().isEmpty()); assertTrue(db.dao().allAppleBackups().isEmpty())
            assertTrue(db.dao().allAppleGameSources().isEmpty()); assertTrue(db.dao().allOperations().isEmpty())
        } finally { db.close() }
    }

    @Test fun diskFullDuringPitchRollsBackSourceProjectionAndLedger() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val before = db.dao().allGames().single(); val raw = repo.exportAppleBackup(receipt.id)
            val failing = GameRepository(db) { throw SQLiteFullException("injected full") }
            try {
                failing.command(before.id, Command("pitch", pitch = PitchAction.calledStrike, at = 1_800_000_000_000), before.revision)
                fail("Expected failure")
            } catch (_: SQLiteFullException) { }
            assertEquals(before, db.dao().game(before.id)); assertArrayEquals(raw, repo.exportAppleBackup(receipt.id))
            assertTrue(db.dao().allOperations().isEmpty())
        } finally { db.close() }
    }

    @Test fun conflictingTeamAndUnsupportedFileCannotPartiallyImport() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db)
            repo.importRosters(AppleSourceBackup.prepare(source()).teams().take(1).map { it.copy(name = "同编号但不同名单资料") })
            val before = db.dao().allTeams()
            try { repo.importAppleBackup(source()); fail("Conflict must abort the entire batch") } catch (_: IllegalArgumentException) { }
            assertEquals(before, db.dao().allTeams()); assertTrue(db.dao().allGames().isEmpty())
            val invalid = multipleGames(true)
            try { repo.importAppleBackup(invalid); fail("Unsupported history command must reject the batch") } catch (_: IllegalArgumentException) { }
            assertEquals(before, db.dao().allTeams()); assertTrue(db.dao().allAppleBackups().isEmpty())
        } finally { db.close() }
    }

    @Test fun deviceBackupV2RestoresOriginalCurrentMappingAndIdempotencyLedger() = runBlocking {
        val db = database(); val restored = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val first = GameRepository.decode(db.dao().allGames().single())
            val op = "f7000000-0000-0000-0000-000000000002"
            val command = Command("pitch", pitch = PitchAction.calledStrike, at = 1_800_000_000_000)
            repo.command(first.id, command, first.revision, op)
            val bytes = repo.exportDeviceBackup(first.ourTeamID)
            assertEquals(2, AppleWireJson.parse(bytes).jsonObject.getValue("version").jsonPrimitive.int)
            val other = GameRepository(restored)
            assertEquals(first.ourTeamID, other.restoreDeviceBackup(bytes))
            assertArrayEquals(source(), restored.dao().appleBackup(receipt.id)!!.original)
            assertArrayEquals(repo.exportAppleBackup(receipt.id), other.exportAppleBackup(receipt.id))
            assertEquals(db.dao().allGames(), restored.dao().allGames())
            val retry = other.command(first.id, command, first.revision, op)
            assertEquals(first.revision + 1, retry.revision)
            assertEquals(1, restored.dao().allOperations().size)
            artifact("source-original.bmbackup", source())
            artifact("source-after-pitch.bmbackup", repo.exportAppleBackup(receipt.id))
            artifact("source-after-room-restore.bmbackup", other.exportAppleBackup(receipt.id))
            try { other.restoreDeviceBackup(bytes); fail("Nonempty target must be protected") } catch (_: IllegalArgumentException) { }
        } finally { db.close(); restored.close() }
    }

    private fun multipleGames(unsupportedSecond: Boolean = false): ByteArray {
        val bytes = File(System.getProperty("parity.fixtures"), "backup/ios-reference-v7.bmbackup").readBytes()
        val outer = AppleWireJson.parse(bytes).jsonObject
        val snapshot = AppleBackupEnvelope.inspect(bytes).snapshot
        val first = snapshot.getValue("games").jsonArray.single().jsonObject
        val state = first.getValue("state").jsonObject
        val identities = (listOf(first.getValue("id")) + listOf("playLog", "scoringEvents", "plateAppearances").flatMap { state.getValue(it).jsonArray.map { raw -> raw.jsonObject.getValue("id") } } +
            state.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.map { it.jsonObject.getValue("id") }).map { it.jsonPrimitive.content.lowercase() }.toSet()
        fun remap(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, item) ->
                if (key == "checkpoint") JsonPrimitive(Base64.getEncoder().encodeToString(remap(AppleWireJson.parse(Base64.getDecoder().decode(item.jsonPrimitive.content))).toString().toByteArray())) else remap(item)
            })
            is JsonArray -> JsonArray(value.map(::remap))
            is JsonPrimitive -> if (value.isString && value.content.lowercase() in identities) JsonPrimitive(UUID.nameUUIDFromBytes((value.content.lowercase()+"/second").toByteArray()).toString().uppercase()) else value
        }
        var second = remap(first).jsonObject
        if (unsupportedSecond) {
            val oldState = second.getValue("state").jsonObject
            val history = oldState.getValue("historyJournal").jsonObject
            val ops = history.getValue("operations").jsonArray.toMutableList()
            ops[0] = JsonObject(ops[0].jsonObject + ("command" to buildJsonObject { put("futureAction", buildJsonObject {}) }))
            second = JsonObject(second + ("state" to JsonObject(oldState + ("historyJournal" to JsonObject(history + ("operations" to JsonArray(ops)))))))
        }
        val payload = JsonObject(snapshot + ("games" to JsonArray(listOf(first, second)))).toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        return JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
    }

    @Test fun multipleGamesShareOneSourceAndEachPitchPreservesTheOtherGame() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val bytes = multipleGames(); val receipt = repo.importAppleBackup(bytes)
            assertEquals(2, receipt.gameCount); assertEquals(1, db.dao().allAppleBackups().size)
            val games = db.dao().allGames().map(GameRepository::decode)
            games.forEachIndexed { index, game -> repo.command(game.id, Command("pitch", pitch = PitchAction.calledStrike, at = 1_800_000_000_000 + index * 1000L), game.revision) }
            val exported = AppleSourceBackup.validateLineage(bytes, repo.exportAppleBackup(receipt.id))
            assertEquals(listOf(1,1), exported.games().map { it.strikes })
            assertArrayEquals(bytes, repo.exportAppleBackup(receipt.id, original = true))
            assertEquals(2, db.dao().allAppleGameSources().size)
            artifact("source-multiple-original.bmbackup", bytes)
            artifact("source-multiple-after-pitch.bmbackup", repo.exportAppleBackup(receipt.id))
        } finally { db.close() }
    }

    @Test fun oneUnsupportedGameRejectsTheWholeBatchBeforeAnyInsert() = runBlocking {
        val db = database()
        try {
            try { GameRepository(db).importAppleBackup(multipleGames(true)); fail("No partial batch") } catch (_: IllegalArgumentException) { }
            assertTrue(db.dao().allTeams().isEmpty()); assertTrue(db.dao().allGames().isEmpty())
            assertTrue(db.dao().allAppleBackups().isEmpty()); assertTrue(db.dao().allAppleGameSources().isEmpty())
        } finally { db.close() }
    }

    @Test fun fullDuringRestoreRollsBackSourceMappingsAndAllBusinessRows() = runBlocking {
        val db = database(); val target = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val bytes = repo.exportDeviceBackup(AppleSourceBackup.prepare(source()).currentTeamID)
            try { GameRepository(target) { throw SQLiteFullException("injected restore full") }.restoreDeviceBackup(bytes); fail("Expected rollback") } catch (_: SQLiteFullException) { }
            assertTrue(target.dao().allTeams().isEmpty()); assertTrue(target.dao().allGames().isEmpty())
            assertTrue(target.dao().allAppleBackups().isEmpty()); assertTrue(target.dao().allAppleGameSources().isEmpty())
            assertArrayEquals(source(), repo.exportAppleBackup(receipt.id))
        } finally { db.close(); target.close() }
    }

    @Test fun sourceAndPitchSurviveDatabaseCloseAndReopen() = runBlocking {
        val name = "apple-restart.db"; context.deleteDatabase(name)
        var db = LocalDatabase.open(context, name)
        val receipt = GameRepository(db).importAppleBackup(source())
        val first = GameRepository.decode(db.dao().allGames().single())
        GameRepository(db).command(first.id, Command("pitch", pitch = PitchAction.calledStrike, at = 1_800_000_000_000), first.revision)
        val current = GameRepository(db).exportAppleBackup(receipt.id)
        db.close(); db = LocalDatabase.open(context, name)
        try {
            val repo = GameRepository(db)
            assertEquals(first.revision + 1, db.dao().game(first.id)!!.revision)
            assertArrayEquals(current, repo.exportAppleBackup(receipt.id)); assertArrayEquals(source(), repo.exportAppleBackup(receipt.id, true))
            assertTrue(repo.importAppleBackup(source()).alreadyPresent)
            assertArrayEquals(current, repo.exportAppleBackup(receipt.id))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun concurrentWritersAtSameRevisionCommitExactlyOneSourceAppend() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val game = GameRepository.decode(db.dao().allGames().single())
            val results = listOf(PitchAction.ball, PitchAction.calledStrike).mapIndexed { n, action -> async(Dispatchers.IO) {
                runCatching { repo.command(game.id, Command("pitch", pitch = action, at = 1_800_000_000_000 + n * 1000L), game.revision) }
            } }.awaitAll()
            assertEquals(1, results.count { it.isSuccess }); assertEquals(1, results.count { it.isFailure })
            assertEquals(1, db.dao().allOperations().size)
            assertEquals(game.revision + 1, db.dao().game(game.id)!!.revision)
            assertEquals(game.revision + 1, AppleSourceBackup.validateLineage(source(), repo.exportAppleBackup(receipt.id)).games().single().revision)
        } finally { db.close() }
    }

    @Test fun damagedCurrentSourceStopsWritesButOriginalCanStillBeRecovered() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val old = db.dao().allGames().single()
            val damaged = db.dao().appleBackup(receipt.id)!!.copy(current = byteArrayOf(0,1,2))
            db.dao().updateAppleBackup(damaged)
            try { repo.command(old.id, Command("pitch", pitch = PitchAction.ball), old.revision); fail("Must fail closed") } catch (_: Exception) { }
            assertEquals(old, db.dao().game(old.id)); assertTrue(db.dao().allOperations().isEmpty())
            assertArrayEquals(source(), repo.exportAppleBackup(receipt.id, original = true))
        } finally { db.close() }
    }

    @Test fun importedRosterCannotDivergeFromItsSource() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val team = AppleSourceBackup.prepare(source()).teams().first()
            val before = db.dao().allTeams()
            try { repo.updateRoster(team.copy(name = "未转换的新名单")); fail("Must not diverge") } catch (_: IllegalArgumentException) { }
            assertEquals(before, db.dao().allTeams()); assertArrayEquals(source(), repo.exportAppleBackup(receipt.id))
        } finally { db.close() }
    }

    @Test fun removingSourceMappingCannotConvertAnImportedGameIntoAnEditableNativeGame() = runBlocking {
        val db = database()
        try {
            val repo = GameRepository(db); val receipt = repo.importAppleBackup(source())
            val old = db.dao().allGames().single()
            assertEquals(receipt.id, GameRepository.decode(old).appleSourceID)
            db.openHelper.writableDatabase.execSQL("DELETE FROM apple_game_sources")
            try { repo.command(old.id, Command("pitch", pitch = PitchAction.ball), old.revision); fail("Missing mapping must not bypass protection") } catch (_: IllegalArgumentException) { }
            assertEquals(old, db.dao().game(old.id)); assertTrue(db.dao().allOperations().isEmpty())
            try { repo.importAppleBackup(source()); fail("Broken mapping is not a successful idempotent import") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(source(), repo.exportAppleBackup(receipt.id, true))
        } finally { db.close() }
    }

    @Test fun rehashedBackupWithDroppedSourcesRejectsBeforeRestoringAnyData() = runBlocking {
        val db = database(); val target = database()
        try {
            val repo = GameRepository(db); repo.importAppleBackup(source())
            val bytes = repo.exportDeviceBackup(AppleSourceBackup.prepare(source()).currentTeamID)
            val outer = AppleWireJson.parse(bytes).jsonObject
            val snapshot = AppleWireJson.parse(Base64.getDecoder().decode(outer.getValue("payload").jsonPrimitive.content)).jsonObject
            val dropped = JsonObject(snapshot - "appleBackups" - "appleSources").toString().toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(dropped).joinToString("") { "%02x".format(it) }
            val forged = JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(dropped)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
            try { GameRepository(target).restoreDeviceBackup(forged); fail("Checksum does not authorize dropping lineage") } catch (_: IllegalArgumentException) { }
            assertTrue(target.dao().allGames().isEmpty()); assertTrue(target.dao().allTeams().isEmpty())
            assertTrue(target.dao().allAppleBackups().isEmpty()); assertTrue(target.dao().allAppleGameSources().isEmpty())
        } finally { db.close(); target.close() }
    }

    @Test fun legacyAndFinishedHistoryPersistAsSourceProtectedReadOnlyGames() = runBlocking {
        for (name in listOf("ios-original-v1.bmbackup", "ios-original-v2.bmbackup", "ios-original-v6.bmbackup", "android-complex-prototype-v7.bmbackup")) {
            val db = database()
            try {
                val bytes = File(System.getProperty("parity.fixtures"), "backup/$name").readBytes()
                val repo = GameRepository(db); val receipt = repo.importAppleBackup(bytes)
                val game = GameRepository.decode(db.dao().allGames().single())
                assertEquals(receipt.id, game.appleSourceID)
                assertArrayEquals(bytes, repo.exportAppleBackup(receipt.id))
                try { repo.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision); fail("Legacy/final source is read-only") } catch (_: IllegalArgumentException) { }
                assertArrayEquals(bytes, repo.exportAppleBackup(receipt.id)); assertTrue(db.dao().allOperations().isEmpty())
            } finally { db.close() }
        }
    }

    @Test fun newGamesFromAnotherBackupReuseExactlyEqualRostersWithoutOverwritingPriorSource() = runBlocking {
        val db = database()
        try {
            val firstBytes = File(System.getProperty("parity.fixtures"), "backup/ios-reference-v7.bmbackup").readBytes()
            val multi = multipleGames(); val outer = AppleWireJson.parse(multi).jsonObject
            val snapshot = AppleBackupEnvelope.inspect(multi).snapshot
            val payload = JsonObject(snapshot + ("games" to JsonArray(listOf(snapshot.getValue("games").jsonArray.last())))).toString().toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            val secondBytes = JsonObject(outer + mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)), "checksum" to JsonPrimitive(hash))).toString().toByteArray()
            val repo = GameRepository(db); val first = repo.importAppleBackup(firstBytes)
            val teams = db.dao().allTeams(); val firstGame = db.dao().allGames().single()
            val second = repo.importAppleBackup(secondBytes)
            assertEquals(teams, db.dao().allTeams()); assertEquals(firstGame, db.dao().game(firstGame.id))
            assertEquals(2, db.dao().allGames().size); assertEquals(2, db.dao().allAppleBackups().size)
            assertArrayEquals(firstBytes, repo.exportAppleBackup(first.id)); assertArrayEquals(secondBytes, repo.exportAppleBackup(second.id))
            val backup = repo.exportDeviceBackup(AppleSourceBackup.prepare(firstBytes).currentTeamID)
            val restored = database()
            try {
                val other = GameRepository(restored); other.restoreDeviceBackup(backup)
                assertEquals(2, restored.dao().allGames().size); assertEquals(teams, restored.dao().allTeams())
                assertArrayEquals(firstBytes, other.exportAppleBackup(first.id)); assertArrayEquals(secondBytes, other.exportAppleBackup(second.id))
            } finally { restored.close() }
        } finally { db.close() }
    }

    @Test fun originalSourceOperationIDsStayReservedForNativeGamesAndAfterRestore() = runBlocking {
        val db = database(); val restored = database()
        try {
            val repo = GameRepository(db); repo.importAppleBackup(source())
            val originalID = AppleSourceBackup.prepare(source()).games().single().journal.first().id
            val team = repo.createTeam("新增本机队", (1..9).joinToString("\n") { "新球员$it,$it" })
            val game = repo.createGame(team, "新增本机对手", (1..9).joinToString("\n") { "对手$it,$it" }, GameMode.standard, false)
            try { repo.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision, originalID.uppercase()); fail("Source IDs cannot be reused in another game") } catch (_: IllegalArgumentException) { }
            assertEquals(game.revision, db.dao().game(game.id)!!.revision)
            val bytes = repo.exportDeviceBackup(team.id); val other = GameRepository(restored); other.restoreDeviceBackup(bytes)
            assertNotNull(restored.dao().appleOriginalOperation(originalID))
            try { other.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision, originalID); fail("Reserved IDs survive restore") } catch (_: IllegalArgumentException) { }
            assertTrue(restored.dao().allOperations().isEmpty())
        } finally { db.close(); restored.close() }
    }

    @Test fun migrationThreeToFourKeepsOriginalNativePayloadAndAddsOnlyEmptySourceTables() = runBlocking {
        val name = "migration-apple-source.db"
        context.deleteDatabase(name)
        var db = LocalDatabase.open(context, name)
        val repo = GameRepository(db)
        val team = repo.createTeam("迁移原有球队", (1..9).joinToString("\n") { "球员$it,$it" })
        repo.createGame(team, "原有对手", (1..9).joinToString("\n") { "对手$it,$it" }, GameMode.standard, false)
        val before = db.dao().allGames(); val teams = db.dao().allTeams()
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("DROP TABLE apple_original_operations"); sql.execSQL("DROP TABLE apple_game_sources"); sql.execSQL("DROP TABLE apple_backups")
            val schema = File(requireNotNull(System.getProperty("parity.fixtures"))).parentFile!!.parentFile!!.resolve("android/app/schemas/cc.baseballmaster.scorekeeper.LocalDatabase/3.json")
            val hash = GameJson.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject.getValue("identityHash").jsonPrimitive.content
            sql.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(hash))
            sql.version = 3
        }
        db = LocalDatabase.open(context, name)
        try {
            assertEquals(before, db.dao().allGames()); assertEquals(teams, db.dao().allTeams())
            assertTrue(db.dao().allAppleBackups().isEmpty()); assertTrue(db.dao().allAppleGameSources().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
