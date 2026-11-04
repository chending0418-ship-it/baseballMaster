package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Additive synthetic fixtures; captures the actual UI and leaves match drafts uncommitted. */
@RunWith(AndroidJUnit4::class)
class Product082FeedbackCaptureTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private fun node(tag: String) = NativeUi.find(device, By.res(tag))
    private fun click(tag: String) { Log.i("Capture082", "click $tag"); node(tag).click(); device.waitForIdle(500); UiTreeCache.refresh() }
    private fun shot(name: String) {
        device.waitForIdle(700)
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "082-$name.png")))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "082-$name.xml"))
    }
    private fun launch() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
        click("tab-0")
        repeat(12) {
            device.swipe(device.displayWidth / 2, device.displayHeight / 4,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 10)
        }
        device.waitForIdle(500)
    }
    @Test fun captureSampleRosterAndExpandedRunnerMenu() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val original = settings.all.filterKeys { it in setOf("current_team_id", "appAppearance") }
        val database = LocalDatabase.open(context)
        try {
            val repo = GameRepository(database)
            val (team, game) = runBlocking {
                val own = repo.createTeam("海浪少年队 · 082评审 ${UUID.randomUUID().toString().take(4)}", SampleRoster)
                var live = repo.createGame(own, "海风少年队", SampleRoster, GameMode.standard, false, Rules())
                live = repo.command(live.id, Command("play", outcome = Outcome.triple,
                    decisions = MatchEngine.suggested(live, Outcome.triple)), live.revision)
                own to live
            }
            assertTrue(settings.edit().putString("current_team_id", team.id).putString("appAppearance", "light").commit())
            launch(); click("new-game")
            node("opponent-name").text = "海风少年队"
            inst.runOnMainSync {
                val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
            }
            click("fill-sample-opponent-roster")
            assertTrue(node("opponent-roster").text.contains("示例球员1,00"))
            assertTrue(node("opponent-roster").text.contains("示例球员10,10"))
            shot("sample-roster")
            click("fill-sample-opponent-roster")
            NativeUi.find(device, By.text("替换名单"))
            NativeUi.find(device, By.text("取消")).click(); device.waitForIdle(500)
            assertTrue(node("opponent-roster").text.contains("示例球员1,00"))
            device.pressBack(); device.waitForIdle(500)
            launch(); click("game-${game.id}"); click("record-runner")
            assertTrue(device.wait(Until.hasObject(By.res("runner-event-stolenBase")), 10_000))
            UiTreeCache.refresh()
            val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
            val options = ScoringOptions.runnerEvents.filterKeys { ScoringOptions.runnerAllowed(game.rules.mode, it) }
            val boxes = options.keys.map { kind ->
                val box = requireNotNull(device.findObject(By.res("runner-event-$kind"))) { "Option missing without scrolling: $kind" }.visibleBounds
                assertTrue("Entire option must fit the initial viewport: $kind $box in $viewport",
                    box.height() >= 48 * context.resources.displayMetrics.density - 2 && viewport.contains(box))
                box
            }
            assertEquals("Expanded menu uses three distinct columns", 3, boxes.map { it.left }.distinct().size)
            shot("runner-menu-expanded")
            device.pressBack(); device.waitForIdle(300)
            runBlocking {
                assertEquals("Menu navigation must not commit a runner event", GameJson.encodeToString(game),
                    database.dao().game(game.id)!!.payload)
            }
        } finally {
            database.close()
            val editor = settings.edit().remove("current_team_id").remove("appAppearance")
            original.forEach { (key, value) -> editor.putString(key, value as? String) }
            assertTrue(editor.commit())
        }
    }
}
