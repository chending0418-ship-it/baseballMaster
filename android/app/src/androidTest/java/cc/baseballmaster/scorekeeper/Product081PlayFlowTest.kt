package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.graphics.Point
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

/** Real product journeys: immediate field input, a single submit boundary, and draft continuity. */
@RunWith(AndroidJUnit4::class)
class Product081PlayFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before fun configure() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        device.setOrientationNatural()
    }
    @After fun restoreWindow() { device.unfreezeRotation(); device.setOrientationNatural() }

    private fun <T> db(block: suspend (GameRepository, LocalDatabase) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(GameRepository(database), database) } finally { database.close() }
    }
    private fun seed(pro: Boolean, runner: Boolean = false): Game = db { repository, _ ->
        val roster = (1..12).joinToString("\n") { "球员$it,$it" }
        val team = repository.createTeam("081 击球 ${UUID.randomUUID().toString().take(5)}", roster)
        val game = repository.createGame(team, "081 对手", roster, GameMode.standard, false,
            Rules(recordingMethod = if (pro) "situation" else "traditional"))
        if (runner) repository.command(game.id, Command("play", outcome = Outcome.triple), game.revision) else game
    }
    private fun stored(id: String): Game = db { _, database -> GameRepository.decode(database.dao().game(id)!!) }
    private fun payload(id: String) = db { _, database -> database.dao().game(id)!!.payload }
    private fun click(tag: String) { NativeUi.find(device, By.res(tag)).click(); device.waitForIdle(500) }
    private fun shot(name: String) {
        device.waitForIdle(500)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "081-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "081-$name.xml"))
    }
    private fun open(game: Game) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
        click("tab-0"); click("game-${game.id}"); click("record-play")
        if (!game.openingPitchRecorded) click("confirm-opening-play")
    }
    private fun tapField() {
        val field = device.wait(Until.findObject(By.res("contact-field")), 10_000)
            ?: error("The field must be visible before any precision choice")
        val bounds = field.visibleBounds
        assertTrue("The complete field must fit the initial viewport", bounds.height() >= 250 * context.resources.displayMetrics.density)
        assertTrue(bounds.bottom < device.displayHeight)
        field.click(Point(bounds.left + bounds.width() / 3, bounds.top + bounds.height() / 4))
        assertTrue("A point tap should immediately offer the defenders",
            device.wait(Until.hasObject(By.res("fielding-6")), 10_000))
    }
    private fun await(id: String, predicate: (Game) -> Boolean): Game {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            stored(id).let { if (predicate(it)) return it }
            SystemClock.sleep(100)
        }
        return stored(id).also { assertTrue("Expected record was not persisted", predicate(it)) }
    }
    /** No swipe/search helper is allowed before a stage's initial-viewport assertion. */
    private fun visibleAtCurrentScroll(selector: BySelector, minimumHeightDp: Int): UiObject2 {
        assertNotNull(device.wait(Until.findObject(selector), 10_000))
        device.waitForIdle(500); UiTreeCache.refresh()
        val control = requireNotNull(device.findObject(selector))
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val bounds = control.visibleBounds
        assertTrue("$selector must be fully visible without scrolling: $bounds within $viewport",
            bounds.height() >= minimumHeightDp * context.resources.displayMetrics.density - 2 &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        return control
    }

    @Test fun proFieldIsImmediatelyAvailableAndCancelLeavesTheMatchUntouched() {
        val game = seed(pro = true)
        val before = payload(game.id)
        open(game)
        assertFalse(device.hasObject(By.res("choice-记分方式-base")))
        click("pro-outcome-single")
        assertNotNull(device.wait(Until.findObject(By.res("contact-field")), 10_000))
        assertFalse("Precision is secondary to field input", device.hasObject(By.text("落点精度")))
        shot("pro-contact-initial")
        click("choice-球型-line"); tapField(); click("fielding-6"); click("fielding-3")
        assertTrue(NativeUi.find(device, By.res("situation-route")).text.contains("球员6"))
        shot("pro-contact-route")
        click("cancel-play")
        assertEquals("A cancelled ball must not persist a pitch, clock or revision", before, payload(game.id))
    }

    @Test fun basicArrivalShowsTheChosenResultAndRecordsWithoutASecondMethodPicker() {
        val game = seed(pro = false)
        open(game)
        assertFalse(device.hasObject(By.res("choice-记分方式-pro")))
        click("arrival-1")
        assertNotNull(NativeUi.find(device, By.text("已记录：到一垒")))
        assertNotNull(NativeUi.find(device, By.text("守备没有明显失误")))
        shot("basic-selected-arrival")
        click("cause-single")
        val saved = await(game.id) { it.totalPitches == game.totalPitches + 1 }
        assertEquals(Outcome.single, saved.journal.last().command.outcome)
        assertEquals("traditional", saved.journal.last().command.recordingMethod)
        assertNotNull(saved.bases[0])
        assertFalse("A simple no-runner hit has already been submitted", device.hasObject(By.res("save-play")))
    }

    @Test fun proRunnerReviewRetainsTheSameDraftAcrossWindowChangesAndSavesOnce() {
        val game = seed(pro = true, runner = true)
        val runner = game.bases[2]!!
        open(game); click("pro-outcome-double"); click("choice-球型-line"); tapField(); click("fielding-8")
        click("play-next")
        click("choice-最终位置-3-0")
        shot("pro-runner-review-portrait")
        device.setOrientationLeft(); device.waitForIdle(500)
        assertNotNull(device.wait(Until.findObject(By.res("save-play")), 15_000))
        shot("pro-runner-review-wide")
        assertEquals("Window changes must not create a record", game.revision, stored(game.id).revision)
        device.setOrientationNatural(); device.waitForIdle(500)
        click("save-play")
        val saved = await(game.id) { it.totalPitches == game.totalPitches + 1 }
        val command = saved.journal.last().command
        assertEquals(Outcome.double, command.outcome)
        assertEquals("line", command.observation?.type)
        assertEquals("point", command.observation?.precision)
        assertEquals(listOf(8), command.observation?.fielding?.route)
        assertEquals(0, command.decisions!!.first { it.playerID == runner }.destination)
        assertEquals(runner, saved.bases[2])
        assertNotNull(saved.bases[1])
        assertEquals(game.journal.size + 1, saved.journal.size)
    }

    @Test fun scrollingAContactFormBackToTheTopNeverReturnsToTheOutcomeCatalog() {
        val game = seed(pro = true)
        val before = payload(game.id)
        open(game); click("pro-outcome-single"); click("choice-球型-line")
        tapField(); click("fielding-6"); click("fielding-3")
        val route = NativeUi.find(device, By.res("situation-route")).text
        // Return from the lower part of the real form, then overscroll at the top.
        // This used to drag the sheet and unexpectedly invoke the previous step.
        val viewport = NativeUi.find(device, By.res("product-page-scroll")).visibleBounds
        repeat(5) {
            device.swipe(viewport.centerX(), viewport.top + viewport.height() / 3,
                viewport.centerX(), viewport.bottom - viewport.height() / 4, 30)
            device.waitForIdle(300)
        }
        UiTreeCache.refresh()
        shot("pro-contact-after-raw-top-gestures")
        assertNotNull("Content scrolling must keep the contact step and its defender field", device.findObject(By.res("fielding-6")))
        val selected = NativeUi.find(device, By.res("choice-球型-line"))
        assertTrue(selected.isChecked || selected.isSelected)
        assertEquals(route, NativeUi.find(device, By.res("situation-route")).text)
        assertEquals(before, payload(game.id))
        shot("pro-contact-scroll-retains-step-and-route")
        click("cancel-play")
        assertEquals("Scrolling and cancel do not submit any ball", before, payload(game.id))
    }

    @Test fun contactToReviewStartsAtTheTopAndReturningRetainsTheSameUnsavedBall() {
        val game = seed(pro = true, runner = true)
        val before = payload(game.id)
        open(game); click("pro-outcome-groundOut"); click("choice-球型-ground")
        tapField(); click("fielding-6"); click("fielding-3")
        val route = NativeUi.find(device, By.res("situation-route")).text
        assertEquals("球员6 → 球员3", route)
        click("contact-other-actions")
        NativeUi.find(device, By.res("situation-clear-route"))
        UiTreeCache.refresh()
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val topChoice = device.findObject(By.res("choice-球型-ground"))?.visibleBounds
        assertTrue("Exercise a contact form genuinely scrolled away from the top",
            topChoice == null || topChoice.height() <= 10 || !viewport.contains(topChoice.centerX(), topChoice.centerY()))
        shot("pro-contact-bottom-before-review")
        click("play-next")
        shot("pro-review-initial-viewport-after-contact-scroll")
        // Direct native lookups prove this is the first review viewport; they do
        // not scroll the clipped text back into view to make the check pass.
        val pending = visibleAtCurrentScroll(By.text("尚未提交"), 12)
        val summary = visibleAtCurrentScroll(By.text(Outcome.groundOut.title + " · " + ScoringOptions.ballTypes["ground"].orEmpty()), 18)
        assertTrue(pending.visibleBounds.bottom <= summary.visibleBounds.top)
        assertEquals("Moving to review must not commit a pitch or clock change", before, payload(game.id))
        device.pressBack(); device.waitForIdle(500); UiTreeCache.refresh()
        val type = NativeUi.find(device, By.res("choice-球型-ground"))
        assertTrue(type.isChecked || type.isSelected)
        assertEquals("已点选落点 · 可重新点选", NativeUi.find(device, By.res("landing-selection-state")).text)
        assertEquals(route, NativeUi.find(device, By.res("situation-route")).text)
        assertEquals(before, payload(game.id))
        shot("pro-review-back-retains-point-type-and-route")
        click("cancel-play")
        assertEquals("Review/back/cancel must leave the whole persisted match unchanged", before, payload(game.id))
    }

    @Test fun catalogSystemBackReturnsExactlyOneStageThenEntryBackCancelsWithoutWriting() {
        val game = seed(pro = true)
        val before = payload(game.id)
        open(game); click("all-batted-outcomes")
        visibleAtCurrentScroll(By.res("outcome-catalog-search"), 40)
        shot("pro-catalog-before-native-back")
        device.pressBack(); device.waitForIdle(500); UiTreeCache.refresh()
        visibleAtCurrentScroll(By.res("pro-outcome-groundOut"), 40)
        assertFalse("One Back leaves the catalog without dismissing the entry", device.hasObject(By.res("outcome-catalog-search")))
        assertEquals(before, payload(game.id))
        shot("pro-catalog-native-back-still-in-entry")
        device.pressBack(); device.waitForIdle(500); UiTreeCache.refresh()
        assertTrue(device.wait(Until.gone(By.res("pro-outcome-groundOut")), 10_000))
        assertTrue("The second Back returns to the live match", NativeUi.find(device, By.res("record-play")).isEnabled)
        assertEquals("Two native Back presses must never submit or mutate the match", before, payload(game.id))
        shot("pro-entry-native-back-cancelled")
    }

}
