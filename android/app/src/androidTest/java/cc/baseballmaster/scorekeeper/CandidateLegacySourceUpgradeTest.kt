package cc.baseballmaster.scorekeeper

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.AppleSourceBackup
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.GameJson
import cc.baseballmaster.core.PitchAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run the seed phase with the distributed 0.8.2 APK, then overinstall the candidate and verify. */
@RunWith(AndroidJUnit4::class)
class CandidateLegacySourceUpgradeTest {
    @Test fun importedIosFixtureRemainsUsableAcrossAndroidUpgrade() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("legacySourcePhase")
        assumeTrue("Run seed then verify across an actual APK upgrade", phase in setOf("seed", "verify"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val fixture = inst.context.assets.open("ios-correction-reference.bmbackup").use { it.readBytes() }
        val db = LocalDatabase.open(context, "candidate-legacy-ios-projection.db")
        try {
            val repo = GameRepository(db)
            if (phase == "seed") {
                assertEquals("0.8.2", version)
                assertFalse(repo.importAppleBackup(fixture).alreadyPresent)
                val saved = db.dao().allGames().single()
                assertTrue("0.8.2 stores the earlier canonical projection", "pitcherInningUsageCurrent" !in GameJson.parseToJsonElement(saved.payload).jsonObject)
                assertArrayEquals(fixture, repo.exportAppleBackup(db.dao().allAppleBackups().single().id))
                return@runBlocking
            }
            assertEquals("1.0.0", version)
            val saved = db.dao().allGames().single()
            val source = db.dao().allAppleBackups().single()
            val expected = GameJson.parseToJsonElement(GameJson.encodeToString(AppleSourceBackup.prepare(fixture).games(source.id).single())).jsonObject
            val legacy = GameJson.parseToJsonElement(saved.payload).jsonObject
            val differences = (legacy.keys + expected.keys).filter { legacy[it] != expected[it] }
            val details = differences.joinToString("\n") { key ->
                val keyPairs = when (key) {
                    "checkpoint" -> listOf(legacy[key] to expected[key])
                    "historyRevisions" -> legacy.getValue(key).jsonArray.zip(expected.getValue(key).jsonArray)
                        .map { (old, new) -> old.jsonObject["previousCheckpoint"] to new.jsonObject["previousCheckpoint"] }
                    else -> emptyList()
                }
                val nestedKeys = keyPairs.flatMap { (old, new) ->
                    val oldPayload = (old as? JsonPrimitive)?.content?.let { GameJson.parseToJsonElement(it).jsonObject }
                    val newPayload = (new as? JsonPrimitive)?.content?.let { GameJson.parseToJsonElement(it).jsonObject }
                    (oldPayload.orEmpty().keys + newPayload.orEmpty().keys).filter { oldPayload?.get(it) != newPayload?.get(it) }
                }.distinct()
                "$key: ${nestedKeys.joinToString()}"
            }
            File(context.getExternalFilesDir(null), "candidate-legacy-projection-fields.txt").writeText(details)
            assertArrayEquals(fixture, source.original)
            val changed = JsonObject(legacy + ("createdAt" to JsonPrimitive(123L)))
            db.dao().updateGame(saved.copy(payload = changed.toString()))
            try {
                assertTrue("A non-derived change invalidates the source match", runCatching { repo.importAppleBackup(fixture) }.isFailure)
                assertTrue("A changed source-backed game cannot be backed up", runCatching { repo.exportDeviceBackup(null) }.isFailure)
            } finally { db.dao().updateGame(saved) }
            assertTrue(repo.importAppleBackup(fixture).alreadyPresent)
            val restored = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
            try {
                val restoredRepo = GameRepository(restored)
                restoredRepo.restoreDeviceBackup(repo.exportDeviceBackup(null))
                assertArrayEquals(fixture, restoredRepo.exportAppleBackup(source.id, original = true))
                assertEquals(saved.id, restored.dao().allGames().single().id)
            } finally { restored.close() }
            val next = repo.command(saved.id, Command("pitch", pitch = PitchAction.calledStrike, at = 1_800_000_000_000L), saved.revision)
            assertEquals(saved.revision + 1, next.revision)
            assertArrayEquals(fixture, repo.exportAppleBackup(source.id, original = true))
        } finally { db.close() }
    }
}
