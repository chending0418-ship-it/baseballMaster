package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
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
class Product081PlayerStatisticsLayoutFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)

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
    private fun expectText(tag: String, value: String) { assertEquals(value, node(tag).text) }
    private fun shot(name: String) {
        device.waitForIdle(500)
        val directory = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(directory, "081-player-statistics-layout-$name.png")))
        device.dumpWindowHierarchy(File(directory, "081-player-statistics-layout-$name.xml"))
    }
    private fun orient(landscape: Boolean) {
        if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline &&
            (context.resources.configuration.orientation != expected || (device.displayWidth > device.displayHeight) != landscape)) {
            SystemClock.sleep(100); device.waitForIdle(100)
        }
        assertEquals("Wait for the actual recreated window", expected, context.resources.configuration.orientation)
        assertEquals(landscape, device.displayWidth > device.displayHeight)
        device.waitForIdle(500); UiTreeCache.refresh()
    }

    /** Deliberately does not call a scrolling helper: this proves the opening viewport. */
    private fun assertOpeningRatesVisible() {
        val expected = mapOf("打率 AVG" to "1.000", "上垒率 OBP" to "1.000", "长打率 SLG" to "4.000", "攻击指数 OPS" to "5.000")
        assertNotNull(device.wait(Until.findObject(By.res("stats-player-batting-打率 AVG")), 10_000))
        device.waitForIdle(500); UiTreeCache.refresh()
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val density = context.resources.displayMetrics.density
        expected.forEach { (label, value) ->
            val metric = requireNotNull(device.findObject(By.res("stats-player-batting-$label"))) { "$label must exist before any scrolling" }
            val bounds = metric.visibleBounds
            assertTrue("$label must be a complete readable card on the initial screen: $bounds within $viewport",
                bounds.width() > 40 * density && bounds.height() >= 60 * density - 2 &&
                    bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            val description = metric.contentDescription ?: metric.findObject(By.desc("$label：$value"))?.contentDescription
            assertEquals("The visible rate must be the real two-game aggregate", "$label：$value", description)
        }
        val export = requireNotNull(device.findObject(By.res("export-player-season-pdf")))
        val identity = requireNotNull(device.findObject(By.res("stats-player-identity")))
        assertTrue("PDF export belongs in the toolbar", export.visibleBounds.bottom <= identity.visibleBounds.top)
        assertTrue("PDF export needs an accessible label", export.contentDescription == "导出 PDF" || export.findObject(By.desc("导出 PDF")) != null)
    }

    @Test fun initialRatesAreVisibleAndPersonalFilterDraftCancelsOrAppliesWithoutDatabaseWrites() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val hadTeam = settings.contains("current_team_id")
        val previousTeam = settings.getString("current_team_id", null)
        try {
            orient(false)
            val suffix = UUID.randomUUID().toString().take(5)
            val roster = "陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
            val (team, games) = db { _, repo ->
                val own = repo.createTeam("球员统计海浪$suffix", roster)
                val records = (1..2).map {
                    var game = repo.createGame(own, "飞鹰$suffix", roster, GameMode.standard, false)
                    game = repo.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision)
                    repo.command(game.id, Command("finish", text = "weather"), game.revision)
                }
                own to records
            }
            val before = db { database, _ ->
                Triple(database.dao().allGames().associate { it.id to it.payload }, database.dao().allTeams().associate { it.id to it.payload },
                    database.dao().allOperations().associateBy { it.id })
            }
            fun noWrites(stage: String) = db { database, _ ->
                assertEquals("$stage must not alter saved games", before.first, database.dao().allGames().associate { it.id to it.payload })
                assertEquals("$stage must not alter rosters", before.second, database.dao().allTeams().associate { it.id to it.payload })
                assertEquals("$stage must not create scoring operations", before.third, database.dao().allOperations().associateBy { it.id })
            }
            settings.edit().putString("current_team_id", team.id).commit()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-2")), 30_000))
            click("tab-2"); click("stats-player-${team.players.first().id}")
            assertOpeningRatesVisible()
            shot("opening-first-screen")
            val year = Year.now().value
            expectText("stats-player-game-scope", "$year 年 · 已选 2 / 2 场")
            expectText("stats-player-batting-totals", "打席 2 · 打数 2 · 安打 2 · 打点 2")
            click("export-player-season-pdf")
            assertNotNull(NativeUi.find(device, By.text("保存到文件")))
            assertNotNull(NativeUi.find(device, By.text("系统分享")))
            device.pressBack(); device.waitForIdle(500)

            click("stats-player-open-game-filter")
            StatisticsUi.setChecked(device, "stats-player-filter-game-${games.last().id}", false)
            expectText("stats-player-filter-draft-scope", "已选 1 / 2 场")
            orient(true)
            assertFalse("An unsaved personal filter survives recreation", node("stats-player-filter-game-${games.last().id}").isChecked)
            assertTrue(node("stats-player-filter-game-${games.first().id}").isChecked)
            expectText("stats-player-filter-draft-scope", "已选 1 / 2 场")
            shot("filter-draft-wide")
            click("stats-player-filter-cancel"); orient(false)
            expectText("stats-player-game-scope", "$year 年 · 已选 2 / 2 场")
            expectText("stats-player-batting-totals", "打席 2 · 打数 2 · 安打 2 · 打点 2")
            noWrites("Cancelling an uncommitted filter after rotation")

            click("stats-player-open-game-filter")
            assertTrue("A cancelled choice must not leak into the next filter visit", node("stats-player-filter-game-${games.last().id}").isChecked)
            StatisticsUi.setChecked(device, "stats-player-filter-game-${games.last().id}", false)
            click("stats-player-filter-save")
            expectText("stats-player-game-scope", "$year 年 · 已选 1 / 2 场")
            expectText("stats-player-batting-totals", "打席 1 · 打数 1 · 安打 1 · 打点 1")
            shot("filter-applied-real-totals")
            click("stats-player-close")
            expectText("stats-game-scope", "已选 2 / 2 场")
            expectText("stats-team-record", "2 场比赛 · 2胜0负0平")
            noWrites("Applying a personal selection and returning to the unchanged parent scope")
        } finally {
            device.unfreezeRotation(); device.setOrientationNatural()
            assertTrue(settings.edit().apply { if (hadTeam) putString("current_team_id", previousTeam) else remove("current_team_id") }.commit())
        }
    }
}
