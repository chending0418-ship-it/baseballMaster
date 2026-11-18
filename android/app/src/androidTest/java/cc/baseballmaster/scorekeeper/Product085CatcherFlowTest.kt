package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.GameMode
import cc.baseballmaster.core.Outcome
import cc.baseballmaster.core.PitchAction
import cc.baseballmaster.core.Rules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Product085CatcherFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun basicAndProCreditTheCatcherOnTheThirdOut() {
        for ((method, name) in listOf("traditional" to "basic", "situation" to "pro")) {
            val game = seed(method, pending = false)
            val catcher = game.fieldingPositions.getValue(2)
            val receiver = game.fieldingPositions.getValue(6)
            val runner = requireNotNull(game.bases[0])
            open(game)
            click("record-runner")
            click("runner-event-caughtStealing")
            click("runner-steal-receiver")
            click("runner-steal-receiver-option-$receiver")
            control("runner-steal-target")
            assertTrue(control("save-runner").isEnabled)
            shot("ccs-$name")
            click("save-runner")
            val saved = waitForSave(game)
            assertEquals(1, saved.journal.size - game.journal.size)
            assertEquals(0, saved.outs)
            assertFalse(saved.isTop)
            assertEquals(1, saved.batting[runner]?.caughtStealing)
            assertEquals(1, saved.reportFielding[catcher]?.catcherCaughtStealing)
            assertEquals(1, saved.fielding[catcher]?.assists)
            assertEquals(1, saved.fielding[receiver]?.putouts)
        }
    }

    @Test
    fun halfFilterAndReviewLocateTheOriginalPassedBallForCorrection() {
        val game = seed("traditional", pending = true)
        val (earlier, pb) = game.entries.filter { it.notation == "passedBall" }
        val historicalCatcher = game.homePositions!!.getValue(2)
        val index = game.journal.indexOfFirst { it.id == pb.id }
        val before = payload(game.id)
        open(game)
        click("game-history")
        click("history-half-filter")
        click("history-half-filter-option-1-top")
        control("correct-record-$index")
        assertFalse(device.hasObject(By.res("correct-record-${game.cursor - 1}")))
        shot("pb-history-half")
        device.pressBack()
        click("game-results")
        click("choice-数据-records")
        control("result-review-event-${pb.id}")
        shot("pb-pending-review")
        click("result-review-event-${pb.id}")
        click("runner-passed-ball-catcher")
        click("runner-passed-ball-catcher-option-$historicalCatcher")
        click("save-runner")
        control("correction-reason").text = "确认原捕逸由当时的捕手负责"
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(InputMethodManager::class.java)
            WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        control("history-preview-score")
        assertEquals(before, payload(game.id))
        assertTrue(control("save-history-correction").isEnabled)
        shot("pb-corrected")
        click("save-history-correction")
        val saved = waitForSave(game)
        assertEquals(1, saved.reportFielding[historicalCatcher]?.passedBalls)
        assertFalse(saved.entries.single { it.id == pb.id }.needsReview)
        assertTrue(saved.entries.single { it.id == earlier.id }.needsReview)
        assertEquals(game.situation(), saved.situation())
        assertEquals(game.entries.last().id, saved.entries.last().id)
    }

    private fun seed(method: String, pending: Boolean): Game = db { database ->
        val repository = GameRepository(database)
        val players = listOf("林一", "陈二", "周三", "李四", "王五", "吴六", "刘七", "何八", "赵九", "郑十", "沈十一", "方十二")
        fun roster(prefix: String) = players.mapIndexed { i, name -> "$prefix$name,${i + 1}" }.joinToString("\n")
        val team = repository.createTeam("北岸主队", roster("北岸"))
        var game = repository.createGame(team, "南湾客队", roster("南湾"), GameMode.standard, true,
            Rules(innings = 6, recordingMethod = method))
        suspend fun record(command: Command) {
            game = repository.command(game.id, command, game.revision)
        }
        if (pending) {
            repeat(2) { record(Command("play", outcome = Outcome.single)) }
            repeat(2) { record(Command("runner", runnerKind = "passedBall")) }
            record(Command("endHalf"))
            record(Command("pitch", pitch = PitchAction.ball))
        } else {
            repeat(2) { record(Command("play", outcome = Outcome.groundOut)) }
            record(Command("play", outcome = Outcome.single))
        }
        game
    }

    private fun open(game: Game) {
        Configurator.getInstance().setWaitForIdleTimeout(500)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
        click("game-${game.id}")
        control("record-runner")
    }

    private fun control(tag: String) = NativeUi.find(device, By.res(tag))

    private fun click(tag: String) {
        control(tag).click()
        device.waitForIdle(500)
    }

    private fun <T> db(block: suspend (LocalDatabase) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(database) } finally { database.close() }
    }

    private fun payload(id: String) = db { requireNotNull(it.dao().game(id)).payload }

    private fun waitForSave(before: Game): Game {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val game = db { GameRepository.decode(requireNotNull(it.dao().game(before.id))) }
            if (game.revision > before.revision) return game
            SystemClock.sleep(100)
        }
        error("The catcher event was not saved")
    }

    private fun shot(name: String) {
        val output = context.getExternalFilesDir(null)
        assertTrue(device.takeScreenshot(File(output, "085-$name.png")))
        device.dumpWindowHierarchy(File(output, "085-$name.xml"))
    }
}
