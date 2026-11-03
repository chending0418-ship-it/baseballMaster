package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import cc.baseballmaster.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host drives a genuine foldable AVD through adb emu posture/fold/unfold.
 * No wm size/density or device_state overrides are used by this test.
 * Protocol in external files: phase=ready -> host-step=half-opened -> phase=half-opened-ok
 * -> host-step=folded -> phase=folded-ok -> host-step=unfolded -> phase=complete.
 */
@RunWith(AndroidJUnit4::class)
class Product081RealFoldFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val directory get() = context.getExternalFilesDir(null)!!
    private val phase get() = File(directory, "081-real-fold-phase.txt")
    private val hostStep get() = File(directory, "081-real-fold-host-step.txt")
    private val receipts = JSONArray()
    private var deadline = 0L

    @Before fun configure() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        phase.delete(); hostStep.delete()
        deadline = SystemClock.uptimeMillis() + 360_000
    }
    private fun refresh() { UiTreeCache.refresh() }
    private fun find(tag: String): UiObject2 {
        val until = minOf(deadline, SystemClock.uptimeMillis() + 20_000)
        var attempts = 0
        var direction = Direction.DOWN
        while (SystemClock.uptimeMillis() < until) {
            refresh()
            device.findObject(By.res(tag))?.let { node ->
                val b = node.visibleBounds
                if (!b.isEmpty && b.height() > 12 && b.centerY() in 24..device.displayHeight - 24) return node
            }
            val scroll = device.findObject(By.res("embedded-product-task"))?.findObject(By.scrollable(true))
                ?: device.findObject(By.pkg(context.packageName).scrollable(true))
            if (attempts++ % 4 == 3) direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
            if (scroll != null) runCatching { scroll.scroll(direction, .7f) }
            device.waitForIdle(300)
        }
        error("Missing visible control $tag")
    }
    private fun click(tag: String) { find(tag).click(); device.waitForIdle(500) }
    private fun <T> db(block: suspend (GameRepository, LocalDatabase) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(GameRepository(database), database) } finally { database.close() }
    }
    private fun payload(id: String) = db { _, database -> database.dao().game(id)!!.payload }
    private fun awaitHost(step: String) {
        phase.writeText(if (step == "half-opened") "ready" else if (step == "folded") "half-opened-ok" else "folded-ok")
        while (SystemClock.uptimeMillis() < deadline && hostStep.takeIf { it.exists() }?.readText()?.trim() != step) SystemClock.sleep(250)
        assertEquals("Host must perform the genuine emulator posture transition", step, hostStep.takeIf { it.exists() }?.readText()?.trim())
        device.waitForIdle(750)
    }
    private fun receipt(stage: String, matchID: String) {
        val info = runBlocking {
            val activity = withContext(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            }
            activity?.let { withTimeoutOrNull(5000) { WindowInfoTracker.getOrCreate(context).windowLayoutInfo(it).first() } }
        }
        val features = JSONArray()
        info?.displayFeatures?.forEach { feature ->
            val rect = feature.bounds
            val value = JSONObject().put("type", feature.javaClass.simpleName)
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            if (feature is FoldingFeature) value.put("state", feature.state.toString()).put("orientation", feature.orientation.toString())
                .put("isSeparating", feature.isSeparating).put("occlusion", feature.occlusionType.toString())
            features.put(value)
        }
        val record = JSONObject().put("stage", stage).put("matchID", matchID)
            .put("displayWidth", device.displayWidth).put("displayHeight", device.displayHeight)
            .put("deviceState", device.executeShellCommand("cmd device_state state").trim())
            .put("windowInfoSampled", info != null).put("features", features)
        receipts.put(record)
        File(directory, "081-real-fold-receipt.json").writeText(JSONObject().put("transitions", receipts).toString(2))
        assertTrue(device.takeScreenshot(File(directory, "081-real-fold-$stage.png")))
        device.dumpWindowHierarchy(File(directory, "081-real-fold-$stage.xml"))
    }

    @Test fun liveProDraftSurvivesHingeCoverAndInnerDisplayTransitions() {
        val game = db { repository, _ ->
            val names = listOf("陈靖森", "朱睿荃", "袁乐扬", "袁允司", "白兰田", "钱芳林", "王星冉", "郭熠硕", "周耀巍", "韩毅晟", "许泽川", "林致远")
            val roster = names.mapIndexed { index, name -> "$name,${index + 1}" }.joinToString("\n")
            val team = repository.createTeam("折叠验收蓝队 ${UUID.randomUUID().toString().take(4)}", roster)
            val initial = repository.createGame(team, "海风少年队", roster, GameMode.standard, false, Rules(recordingMethod = "situation"))
            repository.command(initial.id, Command("pitch", pitch = PitchAction.ball), initial.revision)
        }
        val before = payload(game.id)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
        click("game-${game.id}")
        assertTrue("Start this protocol with the genuine inner display unfolded", device.wait(Until.hasObject(By.res("scorekeeping-wide-layout")), 15_000))
        click("record-play"); find("embedded-product-task")
        click("pro-outcome-single"); click("choice-球型-line")
        val field = find("contact-field").visibleBounds
        device.click(field.left + field.width() / 3, field.top + field.height() / 3)
        device.waitForIdle(500); click("fielding-6"); click("fielding-3")
        val landing = find("landing-selection-state").text
        val route = find("situation-route").text
        assertTrue(route.contains("陈") || route.contains("6") || route.contains("钱芳林"))
        assertEquals(before, payload(game.id)); receipt("unfolded-draft", game.id)
        awaitHost("half-opened")
        find("embedded-product-task")
        assertEquals(landing, find("landing-selection-state").text)
        assertEquals(route, find("situation-route").text)
        assertEquals(before, payload(game.id)); receipt("half-opened-draft", game.id)
        awaitHost("folded")
        assertTrue(device.wait(Until.hasObject(By.res("scorekeeping-compact-layout")), 15_000))
        find("embedded-product-task")
        assertEquals(landing, find("landing-selection-state").text)
        val selectedType = find("choice-球型-line")
        assertTrue("Selected RadioButton must survive the cover-screen transition", selectedType.isChecked || selectedType.isSelected)
        assertEquals(route, find("situation-route").text)
        assertEquals(before, payload(game.id)); receipt("folded-draft", game.id)
        awaitHost("unfolded")
        assertTrue(device.wait(Until.hasObject(By.res("scorekeeping-wide-layout")), 15_000))
        assertEquals(landing, find("landing-selection-state").text)
        assertEquals(route, find("situation-route").text)
        assertFalse(find("back-home").isEnabled)
        assertFalse(find("game-results").isEnabled)
        assertEquals(before, payload(game.id)); receipt("reopened-draft", game.id)
        click("cancel-play")
        assertTrue(device.wait(Until.gone(By.res("embedded-product-task")), 10_000))
        assertEquals("Cancelling the folded/unfolded draft must not persist any event or revision", before, payload(game.id))
        receipt("cancelled", game.id)
        phase.writeText("complete")
    }
}
