package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.GameMode
import cc.baseballmaster.core.PitchAction
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Product085SubstitutionFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun removingOneRecordedDoubleSwitchPairPreservesTheOtherPairAndPitch() {
        Configurator.getInstance().setWaitForIdleTimeout(500)
        val original = seed()
        val before = payload(original.id)
        val switch = original.journal[1].command
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 20_000))
        click("game-${original.id}")
        click("game-history")
        click("correct-record-1")
        control("correction-reason").text = "第一组换人误录"
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                manager.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        click("edit-correction")
        assertTrue(control("history-keep-switch-1").isChecked)
        click("history-keep-switch-1")
        assertFalse(control("history-keep-switch-1").isChecked)
        assertTrue(control("history-keep-switch-2").isChecked)
        control("history-switch-incoming-2")
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "085-substitution-edit.png")))
        click("save-history-substitution")
        control("history-preview-score")
        assertEquals(before, payload(original.id))
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "085-substitution-preview.png")))
        click("save-history-correction")

        val saved = waitForCorrection(original.id)
        assertEquals(switch.playerID, saved.homePositions?.get(5))
        assertEquals(switch.secondReplacementID, saved.homePositions?.get(7))
        assertEquals(switch.playerID, saved.homeOrder?.get(4))
        assertEquals(switch.secondReplacementID, saved.homeOrder?.get(6))
        assertEquals(original.journal.last().id, saved.journal.last().id)
        assertEquals(original.pitching, saved.pitching)
        assertEquals(1, saved.balls)
        assertEquals(1, saved.strikes)
    }

    private fun seed() = db { database ->
        val repository = GameRepository(database)
        val roster = (1..12).joinToString("\n") { "球员$it,$it" }
        val home = repository.createTeam("换人对照主队", roster)
        var game = repository.createGame(home, "换人对照客队", roster, GameMode.standard, true)
        game = repository.command(game.id, Command("pitch", pitch = PitchAction.calledStrike), game.revision)
        val switch = Command("doubleSwitch", playerID = game.fieldingPositions.getValue(5),
            replacementID = game.home.players[9].id, position = 5,
            secondPlayerID = game.fieldingPositions.getValue(7),
            secondReplacementID = game.home.players[10].id, secondPosition = 7)
        game = repository.command(game.id, switch, game.revision)
        repository.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision)
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

    private fun waitForCorrection(id: String): Game {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val game = db { GameRepository.decode(requireNotNull(it.dao().game(id))) }
            if (game.historyRevisions.isNotEmpty()) return game
            SystemClock.sleep(100)
        }
        error("The substitution correction was not saved")
    }
}
