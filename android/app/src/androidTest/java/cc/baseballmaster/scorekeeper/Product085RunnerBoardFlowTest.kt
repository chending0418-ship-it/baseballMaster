package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.GameMode
import cc.baseballmaster.core.Outcome
import cc.baseballmaster.core.Rules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mirrors the iOS 3.1.3 Pro scenario with a runner on second, a score and a tag out. */
@RunWith(AndroidJUnit4::class)
class Product085RunnerBoardFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun proRunnerBoardBlocksBaseConflictsAndRecordsThePreviewOnce() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        device.setOrientationNatural()
        try {
            val start = seed()
            val runner = requireNotNull(start.bases[1])
            val batter = start.batter.id
            val before = payload(start.id)
            open(start)
            shot("scoring-second")

            click("record-play")
            click("pro-outcome-single")
            click("choice-球型-line")
            val field = control("contact-field")
            val bounds = field.visibleBounds
            field.click(Point(bounds.centerX(), bounds.top + bounds.height() / 4))
            click("fielding-8")
            click("fielding-4")
            click("play-next")
            control("runner-base-diamond")
            assertTrue("The confirmation must fit the initial review viewport",
                device.hasObject(By.res("save-play")))
            shot("pro-runner-review")

            click("choice-最终位置-0-2")
            click("runner-card-2")
            click("choice-最终位置-2-0")
            control("runner-base-conflict")
            assertFalse(control("save-play").isEnabled)
            assertEquals(before, payload(start.id))
            shot("pro-runner-conflict")

            click("choice-最终位置-2-4")
            click("runner-card-0")
            click("choice-最终位置-0--1")
            click("runner-extra-route-0")
            click("runner-reuse-route-0")
            click("runner-board-preview")
            control("runner-preview-outs")
            val preview = requireNotNull(device.findObject(By.res("play-save-preview")))
            assertTrue(preview.text.contains("1 : 0 · 1 出局 · 垒上无人"))
            assertEquals(before, payload(start.id))
            assertTrue(control("save-play").isEnabled)
            shot("pro-runner-preview")

            click("save-play")
            val after = waitForRecord(start)
            val command = after.journal.last().command
            val decisions = requireNotNull(command.decisions).associateBy { it.playerID }
            assertEquals(Outcome.single, command.outcome)
            assertEquals(listOf(8, 4), command.observation?.fielding?.route)
            assertEquals(listOf(8, 4), decisions[batter]?.fielding?.route)
            assertEquals(-1, decisions[batter]?.destination)
            assertEquals(4, decisions[runner]?.destination)
            assertEquals(start.journal.size + 1, after.journal.size)
            assertEquals(start.totalPitches + 1, after.totalPitches)
            assertEquals(start.awayHits + 1, after.awayHits)
            assertEquals(start.awayRuns + 1, after.awayRuns)
            assertEquals(start.outs + 1, after.outs)
            assertTrue(after.bases.all { it == null })
        } finally {
            device.unfreezeRotation()
        }
    }

    private fun seed(): Game = db { database ->
        val repository = GameRepository(database)
        val team = repository.createTeam("海浪少年队 · 对照样本", SampleRoster)
        val game = repository.createGame(
            team, "海风少年队", SampleRoster, GameMode.standard, false,
            Rules(recordingMethod = "situation"),
        )
        repository.command(game.id, Command("play", outcome = Outcome.double), game.revision)
    }

    private fun open(game: Game) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
        click("tab-0")
        click("game-${game.id}")
        control("record-play")
    }

    private fun control(tag: String): UiObject2 = NativeUi.find(device, By.res(tag))

    private fun click(tag: String) {
        control(tag).click()
        device.waitForIdle(500)
    }

    private fun <T> db(block: suspend (LocalDatabase) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun payload(id: String) = db { requireNotNull(it.dao().game(id)).payload }

    private fun waitForRecord(before: Game): Game {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val after = db { GameRepository.decode(requireNotNull(it.dao().game(before.id))) }
            if (after.revision > before.revision) return after
            SystemClock.sleep(100)
        }
        error("The confirmed play was not saved")
    }

    private fun shot(name: String) {
        val output = context.getExternalFilesDir(null)
        assertTrue(device.takeScreenshot(File(output, "085-$name.png")))
        device.dumpWindowHierarchy(File(output, "085-$name.xml"))
    }
}
