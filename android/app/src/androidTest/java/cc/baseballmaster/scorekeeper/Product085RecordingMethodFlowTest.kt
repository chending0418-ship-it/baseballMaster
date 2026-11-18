package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.GameMode
import cc.baseballmaster.core.PitchAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Product085RecordingMethodFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun node(selector: BySelector) = NativeUi.find(device, selector)
    private fun click(tag: String) { node(By.res(tag)).click(); device.waitForIdle(500) }

    private fun game(id: String): Game = runBlocking {
        val database = LocalDatabase.open(context)
        try {
            GameRepository.decode(requireNotNull(database.dao().game(id)))
        } finally {
            database.close()
        }
    }

    private fun waitMode(id: String, mode: String): Game {
        val end = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < end) {
            val saved = game(id)
            if (saved.rules.recordingMethod == mode) return saved
            SystemClock.sleep(100)
        }
        error("Expected saved recording mode $mode")
    }

    @Test fun defaultAppliesOnlyToNewGamesAndMidAtBatSwitchSurvivesReopening() {
        Configurator.getInstance().setWaitForIdleTimeout(500)
        val prefs = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = prefs.getString("defaultRecordingMethod", null)
        try {
            val initial = runBlocking {
                val database = LocalDatabase.open(context)
                try {
                    val repository = GameRepository(database)
                    val home = repository.createTeam("记录方式对照队", SampleRoster)
                    val game = repository.createGame(home, "对照客队", SampleRoster, GameMode.standard, true)
                    repository.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision)
                } finally {
                    database.close()
                }
            }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 20_000))
            click("tab-3")
            click("open-app-settings")
            click("choice-默认记录方式-situation")
            assertEquals("situation", prefs.getString("defaultRecordingMethod", null))
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "085-default-recording-method.png")))
            device.pressBack()
            click("tab-0")
            click("new-game")
            val proDefault = node(By.res("choice-记分方式-situation"))
            assertTrue(proDefault.isChecked || proDefault.isSelected)
            device.pressBack()
            assertEquals("traditional", game(initial.id).rules.recordingMethod)

            click("game-${initial.id}")
            click("scoring-more")
            node(By.text("记录方式：基础版")).click()
            node(By.text("切换至Pro 版"))
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "085-switch-recording-method.png")))
            click("confirm-recording-method")
            val pro = waitMode(initial.id, "situation")
            assertEquals(initial.journal, pro.journal)
            assertEquals(initial.situation(), pro.situation())
            click("back-home")
            click("game-${initial.id}")
            click("scoring-more")
            node(By.text("记录方式：Pro 版")).click()
            click("confirm-recording-method")
            val basic = waitMode(initial.id, "traditional")
            assertEquals(initial.journal, basic.journal)
            assertEquals(initial.situation(), basic.situation())
        } finally {
            prefs.edit().putString("defaultRecordingMethod", previous).commit()
        }
    }
}
