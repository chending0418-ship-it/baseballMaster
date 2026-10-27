package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentTeamFlowTest {
    @org.junit.Rule
    @JvmField
    val captureFailure =
        object : org.junit.rules.TestWatcher() {
            override fun failed(e: Throwable, description: org.junit.runner.Description) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val device = UiDevice.getInstance(instrumentation)
                val output = instrumentation.targetContext.getExternalFilesDir(null)!!
                device.takeScreenshot(File(output, "current-team-failure.png"))
                device.dumpWindowHierarchy(File(output, "current-team-failure.xml"))
            }
        }

    @Test
    fun selectCurrentTeamAndRecord() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = settings.getString("current_team_id", null)
        val roster = (1..10).joinToString("\n") { "球员$it,$it" }
        val pair = runBlocking {
            val db = LocalDatabase.open(context)
            try {
                val repo = GameRepository(db)
                val suffix = UUID.randomUUID().toString().take(5)
                val first = repo.createTeam("!当前队甲$suffix", roster)
                val second = repo.createTeam("!当前队乙$suffix", roster)
                var game = repo.createGame(second, "对手$suffix", roster, GameMode.standard, false)
                game =
                    repo.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision)
                repo.command(game.id, Command("finish", text = "weather"), game.revision)
                first to second
            } finally {
                db.close()
            }
        }
        fun launch() {
            context.startActivity(
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            assertTrue(device.wait(Until.hasObject(By.res("home-team-record")), 20000))
            device.waitForIdle()
        }
        fun find(selector: BySelector): UiObject2 {
            var direction = Direction.DOWN
            for (i in 0 until 120) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache()
                    device
                        .findObject(selector)
                        ?.takeIf { it.visibleBounds.height() > 8 }
                        ?.let {
                            return it
                        }
                    val scroll = device.findObject(By.scrollable(true)) ?: break
                    val canContinue = scroll.scroll(direction, .75f)
                    if (!canContinue && direction == Direction.DOWN) direction = Direction.UP
                    else if (!canContinue && direction == Direction.UP) break
                } catch (_: StaleObjectException) {}
                device.waitForIdle()
            }
            return device.wait(Until.findObject(selector), 5000) ?: error("Missing $selector")
        }
        fun step(value: String) {
            File(context.getExternalFilesDir(null), "current-team-test-step.txt").writeText(value)
        }

        File(context.filesDir, "current-team-test-previous.txt").writeText(previous.orEmpty())
        File(context.filesDir, "current-team-test-selected.txt")
            .writeText(pair.second.id + "\n" + pair.second.name)
        try {
            step("launch")
            launch()
            step("open-rosters-first")
            find(By.res("tab-1")).click()
            assertTrue("球队页未打开", device.wait(Until.hasObject(By.res("add-roster")), 10000))
            device.waitForIdle()
            step("select-first")
            find(By.res("select-current-team-${pair.first.id}")).click()
            device.waitForIdle()
            step("home-first")
            find(By.res("tab-0")).click()
            assertTrue("比赛页未打开", device.wait(Until.hasObject(By.res("home-team-name")), 10000))
            device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.text(pair.first.name)), 10000))
            assertEquals("暂无战绩", find(By.res("home-team-record")).text)
            find(By.res("tab-1")).click()
            assertTrue("球队页未打开", device.wait(Until.hasObject(By.res("add-roster")), 10000))
            device.waitForIdle()
            step("select-second")
            find(By.res("select-current-team-${pair.second.id}")).click()
            device.waitForIdle()
            step("home-second")
            find(By.res("tab-0")).click()
            assertTrue("比赛页未打开", device.wait(Until.hasObject(By.res("home-team-name")), 10000))
            device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.text(pair.second.name)), 10000))
            assertEquals("1胜 0负", find(By.res("home-team-record")).text)
            assertTrue(
                device.takeScreenshot(
                    File(context.getExternalFilesDir(null), "current-team-record.png")
                )
            )
            assertEquals(pair.second.id, settings.getString("current_team_id", null))
            step("selected")
        } catch (failure: Throwable) {
            settings
                .edit()
                .apply {
                    if (previous == null) remove("current_team_id")
                    else putString("current_team_id", previous)
                }
                .commit()
            throw failure
        }
    }

    @Test
    fun selectedTeamSurvivesExternalProcessKill() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = File(context.filesDir, "current-team-test-previous.txt").readText()
        val (id, name) = File(context.filesDir, "current-team-test-selected.txt").readLines()
        try {
            context.startActivity(
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            assertTrue(device.wait(Until.hasObject(By.res("home-team-record")), 20000))
            assertTrue(device.wait(Until.hasObject(By.text(name)), 10000))
            assertEquals("1胜 0负", device.findObject(By.res("home-team-record")).text)
            assertEquals(id, settings.getString("current_team_id", null))
        } finally {
            settings
                .edit()
                .apply {
                    if (previous.isEmpty()) remove("current_team_id")
                    else putString("current_team_id", previous)
                }
                .commit()
        }
    }
}
