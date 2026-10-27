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
class StatisticsFlowTest {
    @org.junit.Rule @JvmField
    val captureFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            val inst = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(inst)
            val dir = inst.targetContext.getExternalFilesDir(null)!!
            device.takeScreenshot(File(dir, "an04-statistics-failure.png"))
            device.dumpWindowHierarchy(File(dir, "an04-statistics-failure.xml"))
        }
    }
    @Test fun earnedRunAverageUsesSameSevenInningBasisAsIOS() {
        assertEquals(7.0, era(PitchingLine(outsRecorded = 3, earnedRuns = 1))!!, 0.00001)
        assertNull(era(PitchingLine()))
    }
    @Test fun completedLocalGameAppearsInTeamYearAndModeFilters() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = settings.getString("current_team_id", null)
        val name = "统计测试${UUID.randomUUID().toString().take(5)}"
        val (team, gameID) = runBlocking {
            val db = LocalDatabase.open(context)
            try {
                val repo = GameRepository(db); val roster = (1..10).joinToString("\n") { "球员$it,$it" }
                val t = repo.createTeam(name, roster)
                var game = repo.createGame(t, "海风统计", roster, GameMode.standard, false)
                game = repo.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision)
                repo.command(game.id, Command("finish", text = "weather"), game.revision)
                t to game.id
            } finally { db.close() }
        }
        try {
            settings.edit().putString("current_team_id", team.id).commit()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-2")), 20_000))
            device.waitForIdle()
            var opened = false
            for (attempt in 0 until 3) {
                device.findObject(By.res("tab-2")).click()
                if (device.wait(Until.hasObject(By.res("stats-open-game-filter")), 8_000)) { opened = true; break }
            }
            assertTrue("统计页未打开", opened)
            assertTrue(device.hasObject(By.textContains(name)))
            fun find(selector: BySelector): UiObject2 {
                for (i in 0 until 32) {
                    device.findObject(selector)?.takeIf {
                        val bounds = it.visibleBounds
                        bounds.height() > 10 && bounds.centerY() in device.displayHeight / 6..device.displayHeight * 72 / 100
                    }?.let { return it }
                    val down = i % 16 < 8
                    device.swipe(device.displayWidth / 2, device.displayHeight * (if (down) 8 else 3) / 10, device.displayWidth / 2, device.displayHeight * (if (down) 3 else 8) / 10, 25)
                    android.os.SystemClock.sleep(120)
                }
                error("Missing $selector")
            }
            assertEquals("1 场比赛 · 1胜0负0平", find(By.res("stats-team-record")).text)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-statistics-summary.png"))
            fun chooseCategory(tag: String) {
                repeat(3) {
                    find(By.res(tag))
                    device.waitForIdle(2_000)
                    find(By.res(tag)).click()
                    if (device.wait(Until.hasObject(By.res(tag).checked(true)), 3_000)) return
                }
                error("Category did not select: $tag")
            }
            chooseCategory("choice-统计分类-PITCHING")
            assertNotNull(find(By.res("stats-summary-PITCHING")))
            chooseCategory("choice-统计分类-FIELDING")
            assertNotNull(find(By.res("stats-summary-FIELDING")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-statistics-fielding.png"))
            chooseCategory("choice-统计分类-BATTING")
            find(By.res("stats-player-${team.players.first().id}")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("已选 1 / 1 场")), 10_000))
            find(By.res("stats-player-game-$gameID")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("已选 0 / 1 场")), 5_000))
            assertTrue(device.hasObject(By.textContains("打数 0")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-statistics-player-filter.png"))
        } finally {
            settings.edit().apply { if (previous == null) remove("current_team_id") else putString("current_team_id", previous) }.commit()
        }
    }
}
