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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual product menus save explicit special-role changes into the same local match. */
@RunWith(AndroidJUnit4::class)
class Product081SpecialLineupFlowTest {
    @org.junit.Rule @JvmField val captureFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val ui = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val prefix = "081-special-${description.methodName}-failure"
            ui.takeScreenshot(File(target.getExternalFilesDir(null), "$prefix.png"))
            ui.dumpWindowHierarchy(File(target.getExternalFilesDir(null), "$prefix.xml"))
        }
    }

    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private val roster="陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
    @Before fun configure() {Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)}
    private fun <T> db(block:suspend(LocalDatabase,GameRepository)->T):T=runBlocking {
        val database=LocalDatabase.open(context)
        try {block(database,GameRepository(database))}finally{database.close()}
    }
    private fun stored(id:String)=db {d,_->GameRepository.decode(requireNotNull(d.dao().game(id)))}
    private fun click(tag:String) {NativeUi.find(device,By.res(tag)).click();device.waitForIdle(500)}
    private fun text(value:String) {NativeUi.find(device,By.text(value)).click();device.waitForIdle(500)}
    private fun input(tag:String,value:String) {
        val field=NativeUi.find(device,By.res(tag));field.click();field.text=value
        inst.runOnMainSync {
            val manager=context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {manager.hideSoftInputFromWindow(it.windowToken,0)}
        }
        device.waitForIdle(500)
    }
    private fun launch() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.hasObject(By.pkg(context.packageName)),20000);device.waitForIdle(500)
        device.findObject(By.res("back-home"))?.click();click("tab-0")
    }
    private fun openLineup(game:Game,home:Boolean) {
        launch();click("game-${game.id}");click("open-substitutions");click("edit-lineup-${if(home)"home" else "away"}")
    }
    private fun await(id:String,predicate:(Game)->Boolean) {
        repeat(100){if(predicate(stored(id)))return;Thread.sleep(100)}
        error("Special lineup command was not committed")
    }
    private fun shot(name:String) {
        device.waitForIdle(500);inst.waitForIdleSync()
        val frame=CountDownLatch(1)
        inst.runOnMainSync {android.view.Choreographer.getInstance().postFrameCallback {android.view.Choreographer.getInstance().postFrameCallback {frame.countDown()}}}
        assertTrue(frame.await(3,TimeUnit.SECONDS))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"081-lineup-special-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"081-lineup-special-$name.xml"))
    }
    private fun liveGame(rules:Rules):Game=db {_,repo ->
        val own=repo.createTeam("海风少年队",roster)
        var g=repo.createGame(own,"青禾棒球队",roster,rules.mode,true,rules)
        if(rules.mode==GameMode.slowPitch) {
            g=repo.command(g.id,Command("lineup",homeSide=true,order=g.home.players.map {it.id},positions=g.homePositions),g.revision)
        }
        g=repo.command(g.id,Command("play",outcome=Outcome.single),g.revision)
        repeat(3-rules.initialBalls) {g=repo.command(g.id,Command("pitch",pitch=PitchAction.ball),g.revision)}
        g
    }
    /** Compare the complete committed object, not just selected visible controls. */
    private fun assertExactCommitted(before:Game,after:Game):Command {
        assertEquals(before.revision+1,after.revision)
        val record=after.journal[after.cursor-1]
        assertEquals("lineup",record.command.kind)
        val expected=MatchEngine.apply(before,record.command,record.id)
        assertEquals(expected,after)
        assertEquals(after,stored(after.id))
        return record.command
    }

    @Test fun twoWayPitchingOnlyMenuKeepsOriginalDhOrderAndInheritedPitcherResponsibility() {
        val game=liveGame(Rules(designatedHitter=true,twoWayPlayer=true))
        val original=game.pitcher.id;val incoming=game.home.players[9].id
        assertEquals(original,game.homeDesignatedHitterID)
        val runner=game.bases.first {it!=null}!!
        openLineup(game,true);click("lineup-player-$original")
        click("lineup-pitching-only-$original");shot("pitching-only-choice")
        click("lineup-replace-with-$incoming");click("save-lineup")
        NativeUi.find(device,By.text("确认调整投手？"));text("确认保存")
        await(game.id){it.homePitcherID==incoming}
        val result=stored(game.id);val command=assertExactCommitted(game,result)
        assertEquals(true,command.homeSide);assertEquals(game.homeOrder,command.order)
        assertEquals(original,command.designatedHitterID);assertTrue(command.lineupReplacements.isNullOrEmpty())
        assertEquals(game.homeOrder,result.homeOrder);assertEquals(original,result.homeDesignatedHitterID)
        assertEquals(incoming,result.homePositions!![1]);assertFalse(original in result.homePositions!!.values)
        assertFalse(incoming in result.homeOrder!!);assertTrue(original in result.homeOrder!!)
        assertFalse(original in result.exitedPlayers)
        assertEquals(game.runnerPitcherIDs[runner],result.runnerPitcherIDs[runner])
        assertEquals(original,result.walkResponsiblePitcherID)
        assertEquals(game.bases,result.bases);assertEquals(game.batter.id,result.batter.id)
        assertEquals(game.balls,result.balls);assertEquals(game.strikes,result.strikes)
        assertEquals(game.batting,result.batting);assertEquals(game.pitching,result.pitching);assertEquals(game.fielding,result.fielding)
        assertEquals(game.totalPitches,result.totalPitches)
        NativeUi.find(device,By.res("lineup-saved"));shot("pitching-only-saved-dh-retained")
    }

    @Test fun slowPitchExtraHitterEntersPitchingThroughMenuWithoutChangingOrderCountOrExitIdentity() {
        val game=liveGame(Rules(GameMode.slowPitch))
        val original=game.pitcher.id;val incoming=game.home.players[10].id
        assertTrue(incoming in game.homeOrder!!);assertFalse(incoming in game.homePositions!!.values)
        openLineup(game,true);click("lineup-player-$incoming")
        click("lineup-enter-fielding-$incoming");shot("extra-hitter-fielding-choice")
        click("lineup-fielding-replace-$original");click("save-lineup")
        NativeUi.find(device,By.text("确认调整投手？"));text("确认保存")
        await(game.id){it.homePitcherID==incoming}
        val result=stored(game.id);val command=assertExactCommitted(game,result)
        assertEquals(game.homeOrder,command.order);assertTrue(command.lineupReplacements.isNullOrEmpty())
        assertEquals(game.homeOrder,result.homeOrder)
        assertEquals(12,result.homeOrder!!.size);assertEquals(10,result.homePositions!!.size)
        assertEquals(10,result.homePositions!!.values.distinct().size)
        assertEquals(incoming,result.homePositions!![1]);assertFalse(original in result.homePositions!!.values)
        assertTrue(original in result.homeOrder!!);assertFalse(original in result.exitedPlayers)
        assertEquals(game.bases,result.bases);assertEquals(game.batter.id,result.batter.id)
        assertEquals(game.balls,result.balls);assertEquals(game.strikes,result.strikes)
        assertEquals(game.runnerPitcherIDs,result.runnerPitcherIDs)
        assertEquals(original,result.walkResponsiblePitcherID)
        assertEquals(game.batting,result.batting);assertEquals(game.pitching,result.pitching);assertEquals(game.fielding,result.fielding)
        assertEquals(game.totalPitches,result.totalPitches)
        NativeUi.find(device,By.res("lineup-saved"));shot("extra-hitter-pitcher-saved")
    }

    @Test fun clearingBothNamesUsesNumberFallbackAndRetainsSamePlayerStatisticsAndRunnerIdentity() {
        val game=liveGame(Rules());val player=game.away.players.first()
        assertEquals(1,game.batting[player.id]!!.hits)
        openLineup(game,false);click("lineup-player-${player.id}");text("更正姓名／号码")
        input("lineup-chinese-name","");input("lineup-english-name","")
        assertTrue(NativeUi.find(device,By.text("完成")).isEnabled)
        shot("number-only-profile");text("完成");click("save-lineup")
        val fallback="${player.number}号球员"
        await(game.id){it.away.players.first().name==fallback}
        val result=stored(game.id);val command=assertExactCommitted(game,result)
        val update=requireNotNull(command.playerUpdates).single()
        assertEquals(player.id,update.id);assertEquals(fallback,update.name)
        assertEquals("",update.chineseName);assertEquals("",update.englishName)
        assertEquals(player.jerseyNumbers,update.jerseyNumbers)
        val saved=result.away.players.first()
        assertEquals(player.id,saved.id);assertEquals(fallback,saved.name)
        assertEquals("",saved.chineseName);assertEquals("",saved.englishName)
        assertEquals(game.awayOrder,result.awayOrder);assertEquals(game.awayPositions,result.awayPositions)
        assertEquals(game.batter.id,result.batter.id);assertEquals(game.bases,result.bases)
        assertEquals(game.balls,result.balls);assertEquals(game.strikes,result.strikes)
        assertEquals(game.batting,result.batting);assertEquals(game.pitching,result.pitching);assertEquals(game.fielding,result.fielding)
        assertEquals(game.entries.first(),result.entries.first());assertEquals(game.totalPitches,result.totalPitches)
        val local=db {d,_->GameJson.decodeFromString<Team>(d.dao().team(game.away.id)!!.payload).players.first()}
        assertEquals(player.id,local.id);assertEquals(fallback,local.name)
        assertEquals("",local.chineseName);assertEquals("",local.englishName)
        NativeUi.find(device,By.res("lineup-saved"));shot("number-only-saved-statistics-retained")
    }
}
