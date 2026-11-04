package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic window changes exercise the same activity, task owner and real Room records.
 * These are not a claim of physical hinge, OEM screen continuity or actual split-screen coverage.
 * Fixtures are additive; no existing teams, matches or preferences are deleted.
 */
@RunWith(AndroidJUnit4::class)
class Product081WorkbenchFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private var previousSize: String? = null
    private var previousDensity: String? = null
    private var previousFont = "1.0"
    private var previousRotation = "0"
    private var previousAccelerometer = "0"

    @Before fun prepareWindow() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        previousDensity = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
        previousFont = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        previousRotation = shell("settings get system user_rotation").trim().takeIf { it.toIntOrNull() != null } ?: "0"
        previousAccelerometer = shell("settings get system accelerometer_rotation").trim().takeIf { it in setOf("0", "1") } ?: "0"
        shell("settings put system font_scale 1.0")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
    }

    @After fun restoreWindow() {
        device.setOrientationNatural()
        shell("wm size ${previousSize ?: "reset"}")
        shell("wm density ${previousDensity ?: "reset"}")
        shell("settings put system font_scale $previousFont")
        shell("settings put system user_rotation $previousRotation")
        shell("settings put system accelerometer_rotation $previousAccelerometer")
    }

    private fun shell(command: String) = device.executeShellCommand(command)
    private fun refresh() { UiTreeCache.refresh() }
    private fun <T> db(action: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { action(database, GameRepository(database)) } finally { database.close() }
    }
    private fun stored(id: String): Game = db { database, _ -> GameRepository.decode(database.dao().game(id)!!) }
    private fun await(id: String, predicate: (Game) -> Boolean): Game {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val game = stored(id)
            if (predicate(game)) return game
            SystemClock.sleep(100)
        }
        error("Expected match state was not persisted for $id")
    }
    private fun seed(pro: Boolean = true, innings: Int = 6): Game = db { _, repository ->
        val names = listOf("陈靖森", "朱睿荃", "袁乐扬", "袁允司", "白兰田", "钱芳林", "王星冉", "郭熠硕", "周耀巍", "韩毅晟", "许泽川", "林致远")
        val roster = names.mapIndexed { index, name -> "$name,${index + 1}" }.joinToString("\n")
        val team = repository.createTeam("工作台验收蓝队 ${UUID.randomUUID().toString().take(4)}", roster)
        val game = repository.createGame(team, "海风少年队", roster, GameMode.standard, false,
            Rules(innings = innings, recordingMethod = if (pro) "situation" else "traditional"))
        // A live first-pitch fixture removes the opening confirmation from draft-continuity tests.
        repository.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision)
    }
    private fun find(tag: String): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var direction = Direction.DOWN
        var attempts = 0
        while (SystemClock.uptimeMillis() < deadline) {
            refresh()
            device.findObject(By.res(tag))?.let { node ->
                val bounds = node.visibleBounds
                if (!bounds.isEmpty && bounds.height() > 12 && bounds.centerY() in 24..device.displayHeight - 24) return node
            }
            val task = device.findObject(By.res("embedded-product-task"))
            val scroll = task?.findObject(By.scrollable(true)) ?: device.findObject(By.pkg(context.packageName).scrollable(true))
            if (scroll != null) {
                if (attempts++ % 4 == 3) direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
                runCatching { scroll.scroll(direction, .75f) }
            }
            device.waitForIdle(300)
        }
        error("Missing visible control $tag")
    }
    private fun click(tag: String) { find(tag).click(); device.waitForIdle(500) }
    private fun launch(game: Game) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 20_000))
        device.waitForIdle(500)
        device.findObject(By.res("back-home"))?.click()
        click("tab-0")
        click("game-${game.id}")
        find("back-home")
    }
    private fun window(size: String, density: Int, wide: Boolean, activeGame: Boolean = true, landscape: Boolean = false) {
        // Keep the physical display portrait; exercise rotation through the actual rotation API.
        // Swapping wm's physical width/height with a frozen natural rotation crashes Android 12 SystemUI.
        device.setOrientationNatural()
        shell("wm size $size")
        shell("wm density $density")
        if (landscape) device.setOrientationLeft()
        device.waitForIdle(1000)
        refresh()
        if (activeGame) assertTrue("Layout must follow the current window size",
            device.wait(Until.hasObject(By.res(if (wide) "scorekeeping-wide-layout" else "scorekeeping-compact-layout")), 15_000))
        assertFalse("A supported short or landscape window must never be replaced by a portrait-only message",
            device.hasObject(By.res("portrait-required")) || device.hasObject(By.text("请竖屏使用")))
    }
    private fun screenshot(name: String) {
        device.waitForIdle(500)
        val frame = CountDownLatch(1)
        inst.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback {
            android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
        } }
        assertTrue(frame.await(3, TimeUnit.SECONDS))
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "081-workbench-$name.png")))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "081-workbench-$name.xml"))
    }

    @Test fun wideWorkbenchAndCompactWindowsUseTheSameMatchState() {
        window("1800x2200", 320, wide = true, activeGame = false)
        val game = seed()
        launch(game)
        listOf("workbench-linescore", "workbench-matchup", "workbench-lineups", "workbench-recent-log", "workbench-recent-appearances").forEach { find(it) }
        assertTrue(device.hasObject(By.textContains("海风少年队")))
        screenshot("wide-live")
        val before = stored(game.id)
        window("1080x2340", 420, wide = false)
        find("pitch-ball"); find("record-play")
        assertEquals(before, stored(game.id)); screenshot("cover-live")
        window("1800x2200", 320, wide = true, landscape = true)
        find("workbench-linescore"); assertEquals(before, stored(game.id)); screenshot("wide-landscape")
        window("1280x1280", 320, wide = false)
        find("record-play"); find("finish-game")
        assertEquals(before, stored(game.id)); screenshot("narrow-window")
        window("1800x2200", 320, wide = true)
        find("workbench-lineups"); assertEquals(before, stored(game.id))
    }

    @Test fun embeddedProDraftSurvivesWindowChangesAndCancelDoesNotWrite() {
        window("1800x2200", 320, wide = true, activeGame = false)
        val game = seed()
        launch(game)
        val before = stored(game.id)
        click("record-play")
        find("embedded-product-task")
        click("pro-outcome-single")
        click("choice-球型-ground")
        val field = find("contact-field").visibleBounds
        device.click(field.left + field.width() * 3 / 5, field.top + field.height() * 2 / 5)
        device.waitForIdle(500)
        click("fielding-6")
        val landing = find("landing-selection-state").text
        val route = find("situation-route").text
        val selectedFielder = game.fieldingTeam.players.first { it.id == game.fieldingPositions[6] }.name
        assertTrue("The selected defender must be represented in the draft", route.contains(selectedFielder))
        assertFalse("Landing must be selected before changing windows", landing.contains("未点选"))
        assertFalse(find("back-home").isEnabled)
        assertFalse(find("game-results").isEnabled)
        assertEquals(before, stored(game.id))
        screenshot("pro-inline-draft")
        window("1080x2340", 420, wide = false)
        find("embedded-product-task")
        assertEquals(landing, find("landing-selection-state").text)
        val selectedType = find("choice-球型-ground")
        assertTrue("RadioButton selection must survive window recreation", selectedType.isChecked || selectedType.isSelected)
        assertEquals("The same selected defensive route must survive", route, find("situation-route").text)
        assertEquals(before, stored(game.id)); screenshot("pro-cover-draft")
        window("1800x2200", 320, wide = true, landscape = true)
        assertEquals(landing, find("landing-selection-state").text)
        assertFalse(find("back-home").isEnabled)
        click("cancel-play")
        assertTrue(device.wait(Until.gone(By.res("embedded-product-task")), 10_000))
        assertEquals("Cancel must leave the complete persisted game untouched", before, stored(game.id))
        find("record-play"); screenshot("pro-cancelled")
    }

    @Test fun compactSummaryUsesFullWidthAndOnlyInningsScrollWhileTotalsStayFixed() {
        window("1800x2200", 320, wide = true, activeGame = false)
        val game = seed(innings = 30)
        launch(game)
        val before = stored(game.id)
        val table = find("workbench-linescore").visibleBounds
        val teams = find("workbench-linescore-teams").visibleBounds
        val totals = find("workbench-linescore-rhe").visibleBounds
        val middle = find("workbench-linescore-innings").visibleBounds
        assertTrue("Team names occupy the left edge", kotlin.math.abs(teams.left - table.left) <= 2)
        assertTrue("R/H/E use the right edge rather than leaving a blank half", kotlin.math.abs(totals.right - table.right) <= 2)
        assertTrue("Innings fill the space between names and totals", kotlin.math.abs(middle.left - teams.right) <= 2 && kotlin.math.abs(middle.right - totals.left) <= 2)
        val originalTotals = android.graphics.Rect(totals)
        repeat(8) {
            device.swipe(middle.right - 16, middle.centerY(), middle.left + 16, middle.centerY(), 30)
            device.waitForIdle(300)
        }
        refresh()
        screenshot("long-innings-after-horizontal-scroll")
        val last = device.findObject(By.res("workbench-linescore-inning-30"))
        assertNotNull("The final inning must become visible through a real horizontal gesture", last)
        assertTrue("The final inning must be inside the actual scrolling viewport", last!!.visibleBounds.width() > 12 && middle.contains(last.visibleBounds.centerX(), last.visibleBounds.centerY()))
        assertEquals("Totals must stay pinned while innings move", originalTotals, find("workbench-linescore-rhe").visibleBounds)
        assertEquals(before, stored(game.id))
        screenshot("compact-summary-long-innings")
    }

    @Test fun pitchUndoAndRedoRemainConsistentAcrossWindowChanges() {
        window("1080x2340", 420, wide = false, activeGame = false)
        val game = seed(pro = false)
        launch(game)
        click("pitch-calledStrike")
        val pitched = await(game.id) { it.balls == 1 && it.strikes == 1 && it.totalPitches == 2 }
        window("1800x2200", 320, wide = true)
        assertEquals(pitched, stored(game.id))
        click("undo")
        val undone = await(game.id) { it.strikes == 0 && it.totalPitches == 1 }
        assertEquals(game.bases, undone.bases)
        assertEquals(game.batter.id, undone.batter.id)
        window("1280x1280", 320, wide = false)
        click("redo")
        val restored = await(game.id) { it.strikes == 1 && it.totalPitches == 2 }
        assertEquals(pitched.batting, restored.batting)
        assertEquals(pitched.pitching, restored.pitching)
        assertEquals(pitched.bases, restored.bases)
        assertEquals(pitched.homeRuns, restored.homeRuns)
        assertEquals(pitched.awayRuns, restored.awayRuns)
        assertEquals(pitched.batter.id, restored.batter.id)
        assertEquals(pitched.cursor, restored.cursor)
        screenshot("redo-narrow-window")
    }
}
