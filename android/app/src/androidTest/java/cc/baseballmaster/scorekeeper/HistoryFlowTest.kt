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
class HistoryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val idFile
        get() = File(context.filesDir, "history-flow-id.txt")

    private val originalHash
        get() = File(context.filesDir, "history-flow-original.txt")

    private val draftHash
        get() = File(context.filesDir, "history-flow-draft.txt")

    private val output
        get() = context.getExternalFilesDir(null)!!

    @org.junit.Rule
    @JvmField
    val failures =
        object : org.junit.rules.TestWatcher() {
            override fun failed(e: Throwable, d: org.junit.runner.Description) {
                device.takeScreenshot(File(output, "history-failure.png"))
                device.dumpWindowHierarchy(File(output, "history-failure.xml"))
            }
        }

    private fun sha(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun launch() {
        context.startActivity(
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 20000))
    }

    private fun find(selector: BySelector): UiObject2 {
        repeat(22) { i ->
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                device.findObject(selector)?.takeIf { it.visibleBounds.height() > 8 }?.let { return it }
                device.findObject(By.scrollable(true))?.scroll(if (i < 11) Direction.DOWN else Direction.UP, .5f)
            }catch(_:StaleObjectException){
                // A modal may replace its scroll node between lookup and scroll. Re-query it;
                // scoring actions themselves are never retried by this read/scroll helper.
            }
            device.waitForIdle()
        }
        return device.wait(Until.findObject(selector), 5000) ?: error("Missing $selector")
    }

    private fun click(tag: String) {
        find(By.res(tag)).click()
        device.waitForIdle()
    }

    private fun text(value: String) = find(By.text(value))

    private fun hideKeyboard() {
        instrumentation.runOnMainSync {
            val manager =
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                manager.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        device.waitForIdle()
    }

    private fun snapshot(): Pair<Game, CorrectionDraft?> = runBlocking {
        val db = LocalDatabase.open(context)
        try {
            GameRepository.decode(db.dao().game(idFile.readText())!!) to
                GameRepository(db).loadCorrectionDraft(idFile.readText())
        } finally {
            db.close()
        }
    }

    private fun rawGame(): String = runBlocking {
        val db = LocalDatabase.open(context)
        try {
            db.dao().game(idFile.readText())!!.payload
        } finally {
            db.close()
        }
    }

    private fun waitFor(
        predicate: (Game, CorrectionDraft?) -> Boolean
    ): Pair<Game, CorrectionDraft?> {
        repeat(150) {
            val value = snapshot()
            if (predicate(value.first, value.second)) return value
            Thread.sleep(100)
        }
        error("Saved state not reached")
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        val frame = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
        }
        assertTrue(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(device.takeScreenshot(File(output, "history-$name.png")))
    }

    @Test
    fun prepareCorrectionDraftWithoutChangingOfficialGame() {
        val id = runBlocking {
            val db = LocalDatabase.open(context)
            try {
                val repo = GameRepository(db)
                val roster = (1..12).joinToString("\n") { "球员$it,$it" }
                val team = repo.createTeam("历史 春雷", roster)
                var g = repo.createGame(team, "历史 海风", roster, GameMode.standard, false)
                g = repo.command(g.id, Command("play", outcome = Outcome.single), g.revision)
                g = repo.command(g.id, Command("pitch", pitch = PitchAction.ball), g.revision)
                repo.command(g.id, Command("play", outcome = Outcome.homeRun), g.revision).id
            } finally {
                db.close()
            }
        }
        idFile.writeText(id)
        originalHash.writeText(sha(rawGame()))
        launch()
        click("game-$id")
        click("game-history")
        click("correct-record-1")
        find(By.res("correction-reason")).text = "补记换投"
        hideKeyboard()
        click("insert-before")
        click("insert-substitution")
        text("选择球员").click()
        text("#12 球员12").click()
        click("save-substitution")
        waitFor { _, d -> d?.records?.size == 4 && d.reason == "补记换投" }
        assertEquals(originalHash.readText(), sha(rawGame()))
        assertEquals("更正后 2:0", find(By.res("history-preview-score")).text)
        assertEquals("草稿已保存在本机", find(By.res("history-draft-status")).text)
        draftHash.writeText(sha(GameJson.encodeToString(snapshot().second!!)))
        screenshot("01-draft-preview")
    }

    @Test
    fun recoverDraftSaveWithdrawAndSuspend() {
        assertEquals(originalHash.readText(), sha(rawGame()))
        assertEquals(draftHash.readText(), sha(GameJson.encodeToString(snapshot().second!!)))
        launch()
        click("game-${idFile.readText()}")
        click("game-history")
        click("resume-history-draft")
        assertEquals("更正后 2:0", find(By.res("history-preview-score")).text)
        screenshot("02-restored-draft")
        click("save-history-correction")
        val corrected = waitFor { g, d -> g.historyRevisions.size == 1 && d == null }.first
        val first = corrected.home.players.first().id
        val incoming = corrected.home.players[11].id
        assertEquals(1, corrected.pitching[first]!!.runs)
        assertEquals(1, corrected.pitching[incoming]!!.runs)
        device.pressBack()
        device.waitForIdle()
        click("pitch-calledStrike")
        waitFor { g, _ -> g.strikes == 1 }
        click("game-history")
        click("revert-latest-correction")
        screenshot("03-withdraw-preview")
        click("save-history-correction")
        val restored = waitFor { g, d -> g.historyRevisions.size == 2 && d == null }.first
        assertEquals(1, restored.strikes)
        assertEquals(4, restored.totalPitches)
        assertEquals(2, restored.pitching[first]!!.runs)
        assertEquals(4, restored.pitching[first]!!.pitches)
        assertEquals(first, restored.pitcher.id)
        device.pressBack()
        device.waitForIdle()
        assertFalse(find(By.res("undo")).isEnabled)
        click("scoring-more")
        click("finish-game")
        click("finish-suspended")
        val suspended = waitFor { g, _ -> g.entries.lastOrNull()?.notation == "SUSP" }.first
        assertFalse(suspended.isFinal)
        assertNull(suspended.clockRunningSince)
        assertEquals(1, suspended.strikes)
        screenshot("04-suspended")
        click("pitch-ball")
        val continued = waitFor { g, _ -> g.balls == 1 }.first
        assertFalse(continued.isFinal)
        assertEquals(1, continued.strikes)
        assertNull(continued.clockRunningSince)
    }
}
