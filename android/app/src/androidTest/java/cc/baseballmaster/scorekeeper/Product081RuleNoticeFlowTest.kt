package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Like iOS, a real configured limit appears beside the pitcher, without an extra control row. */
@RunWith(AndroidJUnit4::class)
class Product081RuleNoticeFlowTest {
    @Test fun realLimitUsesPitcherBadgeAndOpeningItNeverWritesAGameCommand() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val database = LocalDatabase.open(context)
        val game = runBlocking {
            val repository = GameRepository(database)
            val roster = "陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9"
            val own = repository.createTeam("海风少年队", roster)
            val initial = repository.createGame(own, "青禾棒球队", roster, GameMode.standard, false,
                Rules(pitchLimit = 1, pitchWarningRemaining = 1))
            repository.pitch(initial.id, PitchAction.ball, initial.revision)
        }
        try {
            fun click(tag: String) { NativeUi.find(device, By.res(tag)).click(); device.waitForIdle(500) }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
            click("game-${game.id}")
            val badge = NativeUi.find(device, By.res("game-rule-warning"))
            assertTrue(badge.contentDescription.orEmpty().contains("已达到 1 球限制") ||
                badge.findObject(By.descContains("已达到 1 球限制")) != null)
            assertFalse("The Android-only warning summary row must not return", device.hasObject(By.res("rule-notices")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "081-rule-pitcher-badge.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "081-rule-pitcher-badge.xml"))
            click("game-rule-warning")
            NativeUi.find(device, By.text("比赛规则提醒"))
            NativeUi.find(device, By.textContains("已达到 1 球限制"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "081-rule-limit-details.png"))
            device.pressBack(); device.waitForIdle(500)
            NativeUi.find(device, By.res("record-play"))
            val stored = runBlocking { GameRepository.decode(database.dao().game(game.id)!!) }
            assertEquals(game, stored)
            assertFalse(stored.isFinal); assertEquals(1, stored.totalPitches)
            click("pitch-ball")
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            var advanced = stored
            while (advanced.totalPitches < 2 && android.os.SystemClock.uptimeMillis() < deadline) {
                advanced = runBlocking { GameRepository.decode(database.dao().game(game.id)!!) }
                if (advanced.totalPitches < 2) android.os.SystemClock.sleep(100)
            }
            assertEquals(2, advanced.totalPitches)
            assertFalse(advanced.isFinal)
        } finally { database.close() }
    }
}
