package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Roster navigation uses the same real statistics as the statistics tab. */
@RunWith(AndroidJUnit4::class)
class Product081RosterFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    @org.junit.After fun restoreOrientation() { device.setOrientationNatural() }
    @Before fun configure() { Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000) }
    private fun <T> db(action: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { action(database, GameRepository(database)) } finally { database.close() }
    }
    private fun click(tag: String) { NativeUi.find(device, By.res(tag)).click(); device.waitForIdle(500) }
    private fun seed(): Pair<Team, Game> = db { _, repository ->
        val roster = "陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9"
        val team = repository.createTeam("!0.8.1 球员详情 ${UUID.randomUUID().toString().take(4)}", roster)
        var game = repository.createGame(team, "青禾棒球队", roster, GameMode.standard, false)
        game = repository.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision)
        game = repository.command(game.id, Command("finish", text = "weather"), game.revision)
        team to game
    }
    private fun storedTeam(id: String) = db { d, _ -> GameJson.decodeFromString<Team>(d.dao().team(id)!!.payload) }
    private fun storedGame(id: String) = db { d, _ -> GameRepository.decode(d.dao().game(id)!!) }
    private fun open(team: Team) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-1")), 30_000))
        click("tab-1"); click("roster-${team.id}")
    }
    private fun shot(name: String) {
        device.waitForIdle(500)
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "081-roster-$name.png")))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "081-roster-$name.xml"))
    }
    @Test fun playerRowOpensExistingStatisticsWithoutMutatingMatchOrRoster() {
        val (team, game) = seed(); val player = team.players.first()
        open(team); shot("compact-player-list")
        click("roster-player-${player.id}")
        assertTrue(device.wait(Until.hasObject(By.res("stats-player-close")), 10_000))
        assertEquals("${java.time.Year.now().value} 年 · 已选 1 / 1 场", NativeUi.find(device, By.res("stats-player-game-scope")).text)
        shot("player-profile-statistics")
        assertEquals("打席 1 · 打数 1 · 安打 1 · 打点 1", NativeUi.find(device, By.res("stats-player-batting-totals")).text)
        device.setOrientationLeft(); device.waitForIdle(1000)
        assertEquals("${java.time.Year.now().value} 年 · 已选 1 / 1 场", NativeUi.find(device, By.res("stats-player-game-scope")).text)
        assertEquals("打席 1 · 打数 1 · 安打 1 · 打点 1", NativeUi.find(device, By.res("stats-player-batting-totals")).text)
        device.setOrientationNatural(); device.waitForIdle(1000)
        click("player-view-game-${game.id}")
        NativeUi.find(device, By.res("back-home")); click("back-home")
        NativeUi.find(device, By.res("roster-player-${player.id}"))
        shot("returned-to-same-roster")
        assertEquals(team, storedTeam(team.id)); assertEquals(game, storedGame(game.id))
    }
    @Test fun playerRemovalIsConfirmedAndKeepsIdentityAndHistoricalMatch() {
        val (team, game) = seed(); val player = team.players.first()
        open(team); click("roster-player-menu-${player.id}"); click("archive-player-${player.id}")
        NativeUi.find(device, By.text("取消")).click(); device.waitForIdle(500)
        assertEquals(team, storedTeam(team.id)); assertEquals(game, storedGame(game.id))
        click("roster-player-menu-${player.id}"); click("archive-player-${player.id}")
        shot("remove-player-confirmation"); click("confirm-archive-player")
        repeat(100) { if (!storedTeam(team.id).players.first().archived) Thread.sleep(100) }
        val after = storedTeam(team.id)
        assertEquals(team.players.size, after.players.size)
        assertEquals(player.copy(archived = true), after.players.first())
        assertEquals(team.players.drop(1), after.players.drop(1))
        assertEquals(game, storedGame(game.id))
    }
}
