package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoringFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    @org.junit.Rule
    @JvmField
    val failureCapture =
        object : org.junit.rules.TestWatcher() {
            override fun failed(error: Throwable, description: org.junit.runner.Description) {
                device.takeScreenshot(File(context.getExternalFilesDir(null), "an03-failure.png"))
                device.dumpWindowHierarchy(
                    File(context.getExternalFilesDir(null), "an03-failure.xml")
                )
            }
        }
    private val identityFile
        get() = File(context.filesDir, "an03-game.txt")

    private val hashFile
        get() = File(context.filesDir, "an03-payload-sha256.txt")

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 20_000))
    }

    private fun settle() {
        device.waitForIdle()
        val frame = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
        }
        check(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    }

    private fun stable(selector: BySelector): UiObject2 {
        var previous: android.graphics.Rect? = null
        var same = 0
        repeat(80) {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            val node = device.findObject(selector)
            val bounds = node?.visibleBounds
            if (bounds != null && bounds == previous) same++ else same = 0
            if (node != null && same >= 5) return node
            previous = bounds
            android.os.SystemClock.sleep(100)
        }
        error("Control kept moving: $selector")
    }

    private fun visible(selector: BySelector): UiObject2 {
        repeat(14) { index ->
            val node = device.findObject(selector)
            if (node != null && node.visibleBounds.height() > 10) return node
            val scroll = device.findObject(By.scrollable(true))
            if (scroll != null) scroll.scroll(if (index < 7) Direction.DOWN else Direction.UP, 0.6f)
            else
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * (if (index < 7) 8 else 3) / 10,
                    device.displayWidth / 2,
                    device.displayHeight * (if (index < 7) 3 else 8) / 10,
                    20,
                )
            if (selector.toString().contains("守备处理"))
                device.dumpWindowHierarchy(
                    File(context.getExternalFilesDir(null), "an03-scroll-$index.xml")
                )
            device.waitForIdle()
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        }
        return device.wait(Until.findObject(selector), 5_000) ?: error("Missing control $selector")
    }

    private fun tag(key: String): UiObject2 {
        var node = visible(By.res(key))
        if (key == "contact-field")
            repeat(6) {
                val bounds = node.visibleBounds
                if (
                    bounds.height() >=
                        (260 * context.resources.displayMetrics.density * .95).toInt()
                ) {
                    settle()
                    return stable(By.res(key))
                }
                device
                    .findObject(By.scrollable(true))
                    ?.scroll(
                        if (bounds.top < device.displayHeight / 3) Direction.UP else Direction.DOWN,
                        .2f,
                    )
                settle()
                node = visible(By.res(key))
            }
        return node
    }

    private fun text(value: String) = visible(By.text(value))

    private fun click(key: String) {
        tag(key).click()
        device.waitForIdle()
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        val drawn = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { drawn.countDown() }
            }
        }
        assertTrue(drawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "an03-$name.png")))
    }

    private fun stored(): Pair<Game, String> = runBlocking {
        val db = LocalDatabase.open(context)
        try {
            val row = db.dao().game(identityFile.readText())!!
            GameRepository.decode(row) to row.payload
        } finally {
            db.close()
        }
    }

    private fun awaitGame(predicate: (Game) -> Boolean): Game {
        repeat(100) {
            val game = stored().first
            if (predicate(game)) return game
            Thread.sleep(100)
        }
        error("Stored match did not reach expected state")
    }

    private fun waitSaved(key: String) {
        assertTrue(device.wait(Until.gone(By.res(key)), 15_000))
        device.waitForIdle()
    }

    private fun sha(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    @Test
    fun recordHitRunnerProUndoCorrectionAndFinish() {
        val id = runBlocking {
            val db = LocalDatabase.open(context)
            try {
                val repo = GameRepository(db)
                val roster = (1..12).joinToString("\n") { "球员$it,$it" }
                val team = repo.createTeam("AN03 春雷", roster)
                repo.createGame(team, "AN03 海风", roster, GameMode.standard, false).id
            } finally {
                db.close()
            }
        }
        identityFile.writeText(id)
        launch()
        click("game-$id")
        click("record-play")
        screenshot("01-play-base")
        click("save-play")
        waitSaved("save-play")
        var game = stored().first
        assertEquals(1, game.awayHits)
        assertNotNull(game.bases[0])
        val first = game.bases[0]!!
        click("record-runner")
        click("save-runner")
        waitSaved("save-runner")
        game = stored().first
        assertEquals(first, game.bases[1])
        assertEquals(1, game.batting[first]!!.stolenBases)
        click("record-play")
        text("本垒打").click()
        click("save-play")
        waitSaved("save-play")
        assertEquals(2, stored().first.awayRuns)
        click("undo")
        assertEquals(0, awaitGame { it.awayRuns == 0 }.awayRuns)
        click("redo")
        assertEquals(2, awaitGame { it.awayRuns == 2 }.awayRuns)
        click("record-play")
        text("Pro 版").click()
        text("平飞").click()
        text("精确落点").click()
        tag("contact-field")
        settle()
        val rect = stable(By.res("contact-field")).visibleBounds
        File(context.getExternalFilesDir(null), "an03-tap-target.txt")
            .writeText("$rect / density=${context.resources.displayMetrics.density}")
        device
            .findObject(By.res("contact-field"))
            .click(
                android.graphics.Point(rect.left + rect.width() / 3, rect.top + rect.height() / 4)
            )
        settle()
        assertTrue(
            "Landing selection feedback was not updated after tap",
            device.wait(Until.hasObject(By.res("landing-selection-state").text("落点已记录")), 5_000),
        )
        screenshot("02a-landing-field")
        tag("choice-球场操作-route").click()
        tag("choice-守备处理-recorded").click()
        assertTrue(
            "Recorded fielding was not selected",
            device.wait(Until.hasObject(By.res("choice-守备处理-recorded").checked(true)), 5_000),
        )
        tag("fielding-8").click()
        tag("fielding-4").click()
        tag("fielding-3").click()
        settle()
        assertTrue(
            "Route did not include all selected participants",
            device.wait(Until.hasObject(By.text("防守路线：8 → 4 → 3")), 5_000),
        )
        screenshot("02-play-pro")
        click("save-play")
        waitSaved("save-play")
        game = stored().first
        assertEquals(3, game.awayHits)
        assertEquals("point", game.entries.last().observation!!.precision)
        assertEquals(listOf(8, 4, 3), game.entries.last().observation!!.fielding!!.route)
        assertEquals(1.0 / 3.0, game.entries.last().observation!!.x!!, .02)
        assertEquals(.25, game.entries.last().observation!!.y!!, .02)
        val stats = GameJson.encodeToString(game.batting)
        click("game-history")
        text("纠错／补充本球").click()
        val reason = tag("correction-reason")
        reason.click()
        reason.text = "补充球型观察"
        instrumentation.runOnMainSync {
            val manager =
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                manager.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        click("edit-correction")
        text("Pro 版").click()
        text("高飞").click()
        click("save-play")
        waitSaved("save-play")
        game = stored().first
        assertEquals(stats, GameJson.encodeToString(game.batting))
        assertEquals("fly", game.entries.last().observation!!.type)
        assertEquals(1, game.correctionRevisions.size)
        device.pressBack()
        device.waitForIdle()
        click("scoring-more")
        click("finish-game")
        click("finish-scorerDecision")
        assertTrue(awaitGame { it.isFinal }.isFinal)
        screenshot("03-final")
        click("reopen-game")
        val reopened = awaitGame { !it.isFinal }
        assertFalse(reopened.isFinal)
        assertNull(reopened.clockRunningSince)
        click("scoring-more")
        click("finish-game")
        click("finish-weather")
        assertTrue(awaitGame { it.isFinal }.isFinal)
        hashFile.writeText(sha(stored().second))
    }

    @Test
    fun completeRecordSurvivesExternalKill() {
        val before = stored()
        assertTrue(before.first.isFinal)
        assertEquals(hashFile.readText(), sha(before.second))
        launch()
        click("game-${identityFile.readText()}")
        assertFalse(tag("pitch-ball").isEnabled)
        click("game-history")
        assertNotNull(text("完整记录"))
        screenshot("04-restored-history")
        assertEquals(hashFile.readText(), sha(stored().second))
    }
}
