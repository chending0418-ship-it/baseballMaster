package cc.baseballmaster.scorekeeper

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All shared roster responses are injected; no shared production service is contacted. */
@RunWith(AndroidJUnit4::class)
class SharedRosterSetupFlowTest {
    @get:Rule val activity = ActivityTestRule(MainActivity::class.java)
    private fun id() = UUID.randomUUID().toString()
    @Test fun homeSetupUsesSharedRosterWithoutALocalTeamAndKeepsStableSource() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        val context = inst.targetContext
        val profile = SharedTeamProfile(id(), "界面共享队", 3, "共享", "上海", (1..10).map {
            SharedRosterPlayer(id(), id(), "共享球员$it", "", listOf(it.toString()))
        })
        val member = RemoteTeam(profile.id, profile.name, profile.revision, TeamRole.member, id())
        val directory = TeamDirectory(listOf(member), id())
        val store = object : TeamDirectoryStorage {
            override val monitor = Any()
            var value = TeamDirectoryState(deviceID = id(), directory = directory)
            override fun load() = value
            override fun save(state: TeamDirectoryState) { value = state }
        }
        val transport = TeamTransport { method, path, _ ->
            check(method == "GET") { "Setup must not write shared data" }
            when (path) {
                "teams" -> GameJson.encodeToJsonElement(directory).jsonObject
                "teams/${profile.id}" -> GameJson.encodeToJsonElement(SharedTeamDetail(profile, member.role, member.memberID)).jsonObject
                else -> error("Unexpected request $path")
            }
        }
        val db = LocalDatabase.open(context)
        val chosen = AtomicReference<Game?>()
        val api = AndroidTeamSharing(GameRepository(db), TeamDirectorySession(TeamDirectoryClient(transport), store, "UI fake"))
        try {
            inst.runOnMainSync {
                activity.activity.setContent {
                    MaterialTheme { NewGameDialog(emptyList(), emptyList(), api, false, {}, { chosen.set(it) }) }
                }
            }
            fun find(selector: BySelector): UiObject2 {
                repeat(30) {
                    device.findObject(selector)?.takeIf { it.visibleBounds.height() > 8 }?.let { return it }
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .7f)
                    device.waitForIdle()
                }
                return device.wait(Until.findObject(selector), 5000) ?: error("Missing $selector")
            }
            find(By.res("setup-refresh-shared")).click()
            find(By.res("setup-shared-${profile.id}")).click()
            assertTrue(device.wait(Until.hasObject(By.res("setup-shared-source")), 10000))
            find(By.res("opponent-name")).text = "界面对手"
            device.pressBack()
            find(By.text("填入示例名单")).click()
            find(By.res("start-game")).click()
            assertTrue(device.wait(Until.hasObject(By.res("start-confirmed-game")), 10000))
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "shared-roster-lineup.png")))
            assertNull(chosen.get())
            find(By.res("start-confirmed-game")).click()
            device.waitForIdle()
            val game = requireNotNull(chosen.get())
            assertEquals(profile.id, game.ourTeamID)
            assertEquals(member.memberID, game.sharedRosterOrigin?.memberID)
            assertEquals("sharedSnapshot", game.away.source)
            assertEquals(profile.players.map { it.sourceID }, game.away.players.map { it.id })
            assertEquals(0, game.totalPitches)
            runBlocking {
                val saved = GameRepository(db).createSharedRosterGame(game)
                assertEquals(game.id, saved.id)
                assertFalse(db.dao().allTeams().any { it.id == profile.id })
            }
        } finally { db.close() }
    }
}
