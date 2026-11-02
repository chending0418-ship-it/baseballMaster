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
            fun openStatistics() {
                repeat(3) {
                    useVisibleNode(device, By.res("tab-2"), scroll = false) { it.click() }
                    if (device.wait(Until.hasObject(By.res("stats-open-game-filter")), 8_000)) return
                }
                error("统计页未打开")
            }
            openStatistics()
            assertTrue(device.hasObject(By.textContains(name)))
            fun text(selector: BySelector) = useVisibleNode(device, selector) { it.text }
            fun click(selector: BySelector, scroll: Boolean = true) =
                useVisibleNode(device, selector, scroll) { it.click() }
            fun assertTeamRecord() {
                assertEquals("1 场比赛 · 1胜0负0平", text(By.res("stats-team-record")))
            }
            assertTeamRecord()
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-statistics-summary.png"))
            fun chooseCategory(tag: String) {
                StatisticsUi.setChecked(device,tag,true)
            }
            chooseCategory("choice-统计分类-PITCHING")
            useVisibleNode(device, By.res("stats-summary-PITCHING")) { Unit }
            chooseCategory("choice-统计分类-FIELDING")
            useVisibleNode(device, By.res("stats-summary-FIELDING")) { Unit }
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-statistics-fielding.png"))
            chooseCategory("choice-统计分类-BATTING")
            fun assertPlayerTotals(selected: Boolean) {
                val count = if (selected) 1 else 0
                val scope = "年 · 已选 $count / 1 场"
                assertTrue(text(By.textContains(scope)).contains(scope))
                // Wait for the recalculated aggregate card, not the unchanged per-game detail below it.
                val totals = "打席 $count · 打数 $count · 安打 $count · 打点 $count"
                assertEquals(totals, text(By.textStartsWith(totals)).substringBefore('\n'))
            }
            fun openPlayer() {
                click(By.res("stats-player-${team.players.first().id}"))
                assertTrue(device.wait(Until.hasObject(By.res("stats-player-close")), 10_000))
                assertPlayerTotals(selected = true)
            }
            fun selectPlayerGame(selected: Boolean) {
                StatisticsUi.setChecked(device,"stats-player-game-$gameID",selected)
                assertPlayerTotals(selected)
            }
            openPlayer()
            selectPlayerGame(false)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-statistics-player-filter.png"))
            selectPlayerGame(true)
            selectPlayerGame(false)
            // Back discards this detail-page selection without changing the team's scope.
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.res("stats-player-close")), 5_000))
            assertTeamRecord()
            openPlayer()
            selectPlayerGame(false)
            click(By.res("stats-player-close"), scroll = false)
            assertTrue(device.wait(Until.gone(By.res("stats-player-close")), 5_000))
            assertTeamRecord()
            // Reopening after Done starts from all eligible games, just like reopening after Back.
            openPlayer()
            click(By.text("取消"), scroll = false)
            assertTrue(device.wait(Until.gone(By.res("stats-player-close")), 5_000))
            assertTeamRecord()
        } finally {
            settings.edit().apply { if (previous == null) remove("current_team_id") else putString("current_team_id", previous) }.commit()
        }
    }

    private fun <T : Any> useVisibleNode(
        device: UiDevice,
        selector: BySelector,
        scroll: Boolean = true,
        use: (UiObject2) -> T,
    ): T = StatisticsUi.use(device,selector,scroll,use)
}
