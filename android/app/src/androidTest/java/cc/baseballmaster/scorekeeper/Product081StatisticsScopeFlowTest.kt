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
import java.time.Year
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Product081StatisticsScopeFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val settings = context.getSharedPreferences("baseballmaster-settings", 0)

    @org.junit.Rule @JvmField
    val captureFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(error: Throwable, description: org.junit.runner.Description) { shot("failure") }
    }

    private fun <T> db(block: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(database, GameRepository(database)) } finally { database.close() }
    }
    private fun node(tag: String): UiObject2 = NativeUi.find(device, By.res(tag))
    private fun click(tag: String) { node(tag).click(); device.waitForIdle(500); UiTreeCache.refresh() }
    private fun expectText(tag: String, expected: String) {
        val control = node(tag)
        assertTrue("$tag must show $expected", control.text == expected || control.findObject(By.text(expected)) != null)
    }
    private fun expectContains(tag: String, expected: String) {
        val control = node(tag)
        assertTrue("$tag must contain $expected", control.text.orEmpty().contains(expected) || control.findObject(By.textContains(expected)) != null)
    }
    private fun expectSelected(tag: String) {
        val control = node(tag)
        assertTrue("$tag must retain its selected state", control.isChecked || control.isSelected)
    }
    private fun shot(name: String) {
        device.waitForIdle(500)
        val directory = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(directory, "081-statistics-scope-$name.png")))
        device.dumpWindowHierarchy(File(directory, "081-statistics-scope-$name.xml"))
    }
    private fun chooseCurrent(team: Team) {
        if (settings.getString("current_team_id", null) != team.id) {
            click("roster-menu-${team.id}"); click("select-current-team-${team.id}")
        }
        assertEquals("The actual roster action must persist the current team", team.id, settings.getString("current_team_id", null))
    }
    private fun expectScope(team: Team, count: Int, total: Int) {
        expectContains("stats-team-picker", team.name)
        expectText("stats-game-scope", "已选 $count / $total 场")
        expectText("stats-team-record", "$count 场比赛 · ${count}胜0负0平")
    }
    private fun expectPreferences(year: Int) {
        expectContains("stats-season-picker", year.toString())
        expectSelected("choice-比赛模式-standard")
        expectText("stats-sort-direction", "升序")
    }
    private fun retainFirstGame(games: List<Game>) {
        click("stats-open-game-filter")
        games.drop(1).forEach { StatisticsUi.setChecked(device, "stats-team-game-${it.id}", false) }
        assertTrue("The remaining game must stay explicitly checked", node("stats-team-game-${games.first().id}").isChecked)
        click("stats-save-filter")
    }

    @Test fun defaultScopeFollowsChangedCurrentTeamWhileExplicitTeamAndItsFilterStaySelected() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val hadCurrentTeam = settings.contains("current_team_id")
        val previousCurrentTeam = settings.getString("current_team_id", null)
        try {
            val suffix = UUID.randomUUID().toString().take(5)
            val roster = "陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
            data class Fixture(val a: Team, val b: Team, val aGames: List<Game>, val bGames: List<Game>)
            val fixture = db { _, repo ->
                val a = repo.createTeam("筛选海浪$suffix", roster)
                val b = repo.createTeam("筛选飞鹰$suffix", roster)
                suspend fun finished(team: Team, homeRuns: Int): Game {
                    var game = repo.createGame(team, "筛选对手$suffix", roster, GameMode.standard, false)
                    repeat(homeRuns) { game = repo.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision) }
                    return repo.command(game.id, Command("finish", text = "weather"), game.revision)
                }
                Fixture(a, b, List(2) { finished(a, 1) }, List(3) { finished(b, 2) })
            }
            val before = db { database, _ ->
                Triple(database.dao().allGames().associate { it.id to it.payload }, database.dao().allTeams().associate { it.id to it.payload },
                    database.dao().allOperations().associateBy { it.id })
            }
            fun noDatabaseWrites(stage: String) = db { database, _ ->
                assertEquals("$stage must leave every match unchanged", before.first, database.dao().allGames().associate { it.id to it.payload })
                assertEquals("$stage must leave every roster unchanged", before.second, database.dao().allTeams().associate { it.id to it.payload })
                assertEquals("$stage must not create or change any operation", before.third, database.dao().allOperations().associateBy { it.id })
            }
            settings.edit().putString("current_team_id", fixture.a.id).commit()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-2")), 30_000))
            click("tab-2")
            val year = Year.now().value
            click("stats-season-picker"); click("stats-season-picker-option-$year")
            StatisticsUi.setChecked(device, "choice-比赛模式-standard", true)
            click("stats-sort-direction")
            expectScope(fixture.a, 2, 2); expectPreferences(year)
            retainFirstGame(fixture.aGames)
            expectScope(fixture.a, 1, 2)
            shot("a-partial-default")
            noDatabaseWrites("Filtering A's completed matches")

            // Leave the Tab so its SaveableStateHolder really restores the old
            // selection slots after the current team changes in the roster UI.
            click("tab-1"); chooseCurrent(fixture.b); click("tab-2")
            expectScope(fixture.b, 3, 3); expectPreferences(year)
            UiTreeCache.refresh()
            assertFalse("The old A filter dialog must not reappear", device.hasObject(By.res("stats-save-filter")))
            assertFalse("The old A player detail must not reappear", device.hasObject(By.res("stats-player-close")))
            shot("b-default-all-real-games")
            click("stats-player-${fixture.b.players.first().id}")
            expectText("stats-player-game-scope", "$year 年 · 已选 3 / 3 场")
            expectText("stats-player-batting-totals", "打席 3 · 打数 3 · 安打 3 · 打点 3")
            click("stats-player-close")
            expectScope(fixture.b, 3, 3)
            noDatabaseWrites("Following the current B team and reading its real player totals")

            // An explicit statistics-team choice is independent of the current
            // team. Its partial selection must survive another A -> B switch.
            click("stats-team-picker"); click("stats-team-picker-option-${fixture.a.id}")
            expectScope(fixture.a, 2, 2)
            retainFirstGame(fixture.aGames); expectScope(fixture.a, 1, 2)
            click("tab-1"); chooseCurrent(fixture.a); chooseCurrent(fixture.b); click("tab-2")
            expectScope(fixture.a, 1, 2); expectPreferences(year)
            assertEquals(fixture.b.id, settings.getString("current_team_id", null))
            shot("explicit-a-retains-partial-scope")
            click("stats-player-${fixture.a.players.first().id}")
            expectText("stats-player-game-scope", "$year 年 · 已选 1 / 1 场")
            expectText("stats-player-batting-totals", "打席 1 · 打数 1 · 安打 1 · 打点 1")
            click("player-view-game-${fixture.aGames.first().id}"); click("back-home")
            expectScope(fixture.a, 1, 2); expectPreferences(year)
            shot("game-return-keeps-explicit-a-scope")
            noDatabaseWrites("Current-team switches and visiting a historical match")
        } finally {
            assertTrue(settings.edit().apply {
                if (hadCurrentTeam) putString("current_team_id", previousCurrentTeam) else remove("current_team_id")
            }.commit())
        }
    }
}
