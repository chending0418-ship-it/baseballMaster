package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerStatisticsFlowTest {
    @org.junit.Rule @JvmField
    val captureFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            val inst = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(inst)
            val directory = inst.targetContext.getExternalFilesDir(null)!!
            device.takeScreenshot(File(directory, "an04-personal-statistics-failure.png"))
            device.dumpWindowHierarchy(File(directory, "an04-personal-statistics-failure.xml"))
        }
    }

    @Test fun personalYearModeMetricsAndSelectionDoNotMutateInheritedTeamScope() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val preferences = context.getSharedPreferences("baseballmaster-settings", 0)
        val previousTeam = preferences.getString("current_team_id", null)
        data class Fixture(val team: Team, val standard: Game, val coach: Game)
        val fixture = runBlocking {
            val database = LocalDatabase.open(context)
            try {
                val repository = GameRepository(database)
                val roster = (1..9).joinToString("\n") { "球员$it,$it" }
                val team = repository.createTeam("个人统计${UUID.randomUUID().toString().take(5)}", roster)
                suspend fun finished(mode: GameMode, date: String): Game {
                    var game = repository.createGame(team, "同名对手", roster, mode, false)
                    game = repository.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision)
                    game = repository.command(game.id, Command("finish", text = "weather"), game.revision)
                    return repository.command(game.id, Command("date", dateMillis = Instant.parse(date).toEpochMilli(),
                        timeZoneID = "Asia/Shanghai", text = "合成统计验证"), game.revision)
                }
                val standard = finished(GameMode.standard, "2026-01-01T16:30:00Z")
                val coach = finished(GameMode.coachPitch, "2026-01-02T16:30:00Z")
                finished(GameMode.standard, "2025-01-01T16:30:00Z")
                Fixture(team, standard, coach)
            } finally { database.close() }
        }
        fun click(tag: String, scroll: Boolean = true) = node(device, By.res(tag), scroll) { it.click() }
        fun expectText(tag: String, expected: String) {
            node(device, By.res(tag).text(expected)) { assertEquals(expected, it.text) }
        }
        fun expectDescription(tag: String, expected: String) {
            node(device, By.res(tag).desc(expected)) { assertEquals(expected, it.contentDescription) }
        }
        fun choose(tag: String) {
            node(device, By.res(tag)) { if (!it.isChecked) it.click() }
            assertTrue(device.wait(Until.hasObject(By.res(tag).checked(true)), 10_000))
        }
        fun year(tag: String, selected: Int) {
            click(tag)
            node(device, By.text("$selected 年"), scroll = false) { it.click() }
        }
        fun scope(expected: String) = expectText("stats-player-game-scope", expected)
        fun totals(count: Int) = expectText("stats-player-batting-totals", "打席 $count · 打数 $count · 安打 $count · 打点 $count")
        try {
            preferences.edit().putString("current_team_id", fixture.team.id).commit()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("App did not reach navigation", device.wait(Until.hasObject(By.res("tab-2")), 60_000))
            click("tab-2", scroll = false)
            year("stats-season-picker", 2026)
            expectText("stats-team-record", "2 场比赛 · 2胜0负0平")
            // Mode-only parent filtering also passes an ID boundary, without opening the game picker.
            choose("choice-比赛模式-standard")
            expectText("stats-team-record", "1 场比赛 · 1胜0负0平")
            click("stats-player-${fixture.team.players.first().id}")
            scope("2026 年 · 已选 1 / 1 场")
            choose("choice-球员比赛模式-coachPitch")
            scope("2026 年 · 已选 0 / 0 场")
            choose("choice-球员比赛模式-all")
            scope("2026 年 · 已选 1 / 1 场")
            click("stats-player-close", scroll = false)
            assertTrue(device.wait(Until.gone(By.res("stats-player-close")), 10_000))
            expectText("stats-team-record", "1 场比赛 · 1胜0负0平")
            choose("choice-比赛模式-all")
            expectText("stats-team-record", "2 场比赛 · 2胜0负0平")
            click("stats-open-game-filter")
            val teamChoice = "stats-team-game-${fixture.coach.id}"
            node(device, By.res(teamChoice)) { if (it.isChecked) it.click() }
            assertTrue(device.wait(Until.hasObject(By.res(teamChoice).checked(false)), 10_000))
            expectText("stats-team-game-date-${fixture.standard.id}", "2026-01-02 00:30 · Asia/Shanghai")
            click("stats-save-filter", scroll = false)
            expectText("stats-team-record", "1 场比赛 · 1胜0负0平")

            click("stats-player-${fixture.team.players.first().id}")
            scope("2026 年 · 已选 1 / 1 场")
            totals(1)
            expectDescription("stats-player-batting-上垒率 OBP", "上垒率 OBP：1.000")
            expectDescription("stats-player-batting-垒打数 TB", "垒打数 TB：4")
            expectText("stats-player-recent3-totals", "1 场 · 1 安打 · 1 打数 · 1 打点")
            expectText("stats-player-recent10-totals", "1 场 · 1 安打 · 1 打数 · 1 打点")
            expectText("stats-player-game-date-${fixture.standard.id}", "2026-01-02 00:30 · Asia/Shanghai")

            // Changing mode keeps the inherited one-game boundary, even though the team has a coach game.
            choose("choice-球员比赛模式-coachPitch")
            scope("2026 年 · 已选 0 / 0 场")
            choose("choice-球员统计分类-PITCHING")
            expectText("stats-player-pitching-not-applicable", "教练投手模式：投手数据不适用")
            choose("choice-球员比赛模式-all")
            choose("choice-球员统计分类-BATTING")
            scope("2026 年 · 已选 1 / 1 场")

            // A different personal year releases that inherited boundary, exactly as on iOS.
            year("stats-player-season-picker", 2025)
            scope("2025 年 · 已选 1 / 1 场")
            totals(1)
            year("stats-player-season-picker", 2026)
            scope("2026 年 · 已选 2 / 2 场")
            totals(2)
            click("stats-player-clear")
            scope("2026 年 · 已选 0 / 2 场")
            totals(0)
            click("stats-player-select-all")
            totals(2)
            click("stats-player-close", scroll = false)
            assertTrue(device.wait(Until.gone(By.res("stats-player-close")), 10_000))
            expectText("stats-team-record", "1 场比赛 · 1胜0负0平")
            click("stats-player-${fixture.team.players.first().id}")
            scope("2026 年 · 已选 1 / 1 场")
            totals(1)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-personal-statistics.png"))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.res("stats-player-close")), 10_000))
            expectText("stats-team-record", "1 场比赛 · 1胜0负0平")
        } finally {
            preferences.edit().apply { if (previousTeam == null) remove("current_team_id") else putString("current_team_id", previousTeam) }.commit()
        }
    }

    /** Fresh lookup and use are one bounded action; a replacement node is never retained. */
    private fun <T : Any> node(device: UiDevice, selector: BySelector, scroll: Boolean = true, use: (UiObject2) -> T): T {
        var swipes = 0
        var lastStale: StaleObjectException? = null
        val value = device.wait(Condition<UiDevice, T?> { current ->
            try {
                val target = current.findObject(selector)
                val bounds = target?.visibleBounds
                val viewport = if (scroll) current.findObject(By.scrollable(true))?.visibleBounds else null
                if (target != null && bounds != null && bounds.height() > 10 &&
                    (!scroll || viewport == null || viewport.contains(bounds.centerX(), bounds.centerY()))) {
                    use(target)
                } else {
                    if (scroll && swipes < 32) {
                        val down = if (bounds != null && bounds.height() > 10) bounds.centerY() > current.displayHeight / 2 else swipes % 16 < 8
                        val top = viewport?.top ?: current.displayHeight / 6
                        val bottom = viewport?.bottom ?: current.displayHeight * 8 / 10
                        val inset = (bottom - top) / 5
                        val x = viewport?.centerX() ?: current.displayWidth / 2
                        current.swipe(x, if (down) bottom - inset else top + inset, x, if (down) top + inset else bottom - inset, 25)
                        swipes++
                    }
                    null
                }
            } catch (failure: StaleObjectException) { lastStale = failure; null }
        }, 60_000)
        return value ?: throw AssertionError("Missing or unstable $selector", lastStale)
    }
}
