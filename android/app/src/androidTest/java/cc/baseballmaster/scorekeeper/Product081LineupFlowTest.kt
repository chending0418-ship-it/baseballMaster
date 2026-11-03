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

/** Product tasks and identity preservation, independent of the frozen 0.8.0 tests. */
@RunWith(AndroidJUnit4::class)
class Product081LineupFlowTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private val roster="陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
    @Before fun configure() {Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)}
    private fun <T> db(block:suspend(LocalDatabase,GameRepository)->T):T=runBlocking {
        val database=LocalDatabase.open(context)
        try {block(database,GameRepository(database))}finally{database.close()}
    }
    private fun stored(id:String)=db { d,_->GameRepository.decode(requireNotNull(d.dao().game(id))) }
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
        device.findObject(By.res("back-home"))?.click()
        click("tab-0")
    }
    private fun await(id:String,predicate:(Game)->Boolean) {
        repeat(100) {if(predicate(stored(id)))return;Thread.sleep(100)}
        error("Expected lineup state was not committed")
    }
    private fun shot(name:String) {
        device.waitForIdle(500);inst.waitForIdleSync()
        val frame=CountDownLatch(1)
        inst.runOnMainSync {android.view.Choreographer.getInstance().postFrameCallback {android.view.Choreographer.getInstance().postFrameCallback {frame.countDown()}}}
        assertTrue(frame.await(3,TimeUnit.SECONDS))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"081-lineup-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"081-lineup-$name.xml"))
    }
    private fun chooseRoster(tag:String,team:Team) {
        click(tag);input("roster-picker-search",team.name);click("choose-roster-${team.id}")
    }
    private fun teams():Pair<Team,Team> = db {_,repo ->
        repo.createTeam("海风少年队",roster) to repo.createTeam("青禾棒球队",roster,true)
    }
    private fun liveGame():Game=db {_,repo ->
        val own=repo.createTeam("海风少年队",roster)
        var game=repo.createGame(own,"青禾棒球队",roster,GameMode.standard,false)
        game=repo.command(game.id,Command("play",outcome=Outcome.homeRun),game.revision)
        repo.command(game.id,Command("pitch",pitch=PitchAction.ball),game.revision)
    }
    private fun openLineup(game:Game,home:Boolean=false) {
        launch();click("game-${game.id}");click("open-substitutions");click("edit-lineup-${if(home)"home" else "away"}")
    }

    @Test fun pregameBothTeamsRemainDirectlyEditableAndFirstBatterReplacementStartsSafely() {
        val (own,opponent)=teams();launch();click("new-game")
        chooseRoster("setup-own-team",own);chooseRoster("setup-opponent",opponent)
        click("start-game")
        NativeUi.find(device,By.res("lineup-row-away-0"))
        shot("pregame-first-screen")
        NativeUi.find(device,By.res("lineup-row-home-0"))
        shot("pregame-both-teams")
        // Return to the away first row after inspecting the home team's roster.
        click("lineup-replace-${own.players.first().id}")
        click("lineup-replace-with-${own.players[9].id}")
        click("lineup-position-away-0");click("lineup-position-option-2")
        click("lineup-down-1");click("lineup-up-2")
        // Edit the other side too; scrolling/selection never resets the away draft.
        click("lineup-position-home-0");click("lineup-position-option-2")
        shot("pregame-position-and-first-batter")
        click("start-confirmed-game")
        assertTrue(device.wait(Until.hasObject(By.res("confirm-create-lineups")),10000))
        click("confirm-create-lineups")
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        var created:Game?=null
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            created=db {d,_->d.dao().allGames().map(GameRepository::decode).singleOrNull {it.ourTeamID==own.id && it.home.id==opponent.id}}
            if(created!=null)break
            Thread.sleep(100)
        }
        val result=requireNotNull(created)
        assertEquals(own.players[9].id,result.awayOrder!!.first())
        assertEquals(own.players[9].id,result.awayPositions!![2])
        assertEquals(own.players[1].id,result.awayPositions!![1])
        assertEquals(opponent.players[0].id,result.homePositions!![2])
        assertEquals(0,result.totalPitches);assertTrue(result.isPregame)
        assertTrue("Pregame setup must not leave orphaned scoring events",result.entries.isEmpty())
        assertTrue(result.journal.isEmpty());assertNull(result.checkpoint)
        assertFalse(result.incompletePrefix);assertFalse(result.statisticsIncomplete)
        assertTrue(result.ruleNotices(System.currentTimeMillis()).isEmpty())
        click("pitch-ball");click("confirm-opening-pitch")
        await(result.id){it.totalPitches==1 && it.openingPitchRecorded}
        val first=stored(result.id)
        assertEquals(1,first.entries.size);assertEquals("pitch",first.entries.single().kind)
        assertEquals(1,first.journal.size)
        assertFalse(first.incompletePrefix);assertFalse(first.statisticsIncomplete)
        assertTrue(first.ruleNotices(System.currentTimeMillis()).isEmpty())
        assertEquals(result.awayOrder,first.awayOrder);assertEquals(result.homeOrder,first.homeOrder)
        assertEquals(result.awayOrder!!.first(),first.batter.id)
        val baseline=GameJson.decodeFromString<Game>(requireNotNull(first.checkpoint))
        assertTrue(baseline.entries.isEmpty());assertFalse(baseline.incompletePrefix)
        click("undo");await(result.id){it.totalPitches==0}
        val undone=stored(result.id)
        assertTrue(undone.entries.isEmpty());assertFalse(undone.incompletePrefix);assertFalse(undone.statisticsIncomplete)
        assertEquals(result.awayOrder,undone.awayOrder);assertEquals(result.homeOrder,undone.homeOrder)
        assertEquals(result.awayPositions,undone.awayPositions);assertEquals(result.homePositions,undone.homePositions)
        click("redo");await(result.id){it.totalPitches==1}
        val redone=stored(result.id)
        assertEquals(first.entries,redone.entries);assertEquals(first.batting,redone.batting);assertEquals(first.pitching,redone.pitching)
        assertEquals(first.balls,redone.balls);assertEquals(first.batter.id,redone.batter.id)
        assertFalse(redone.incompletePrefix);assertFalse(redone.statisticsIncomplete)
        assertTrue(redone.ruleNotices(System.currentTimeMillis()).isEmpty())
        assertFalse("A normal new match must have no rule warning badge",device.hasObject(By.res("game-rule-warning")))
        shot("pregame-first-pitch-undo-redo-complete")
    }

    @Test fun liveTaskMenuShowsBothSidesAndSavingOrderKeepsBatterCountAndNextPlayer() {
        val game=liveGame();launch();click("game-${game.id}");click("open-substitutions")
        NativeUi.find(device,By.res("edit-lineup-away"));NativeUi.find(device,By.res("edit-lineup-home"))
        NativeUi.find(device,By.res("substitution-pitcher"));NativeUi.find(device,By.res("substitution-batter"))
        shot("live-task-menu")
        click("edit-lineup-away");NativeUi.find(device,By.text("当前打者"));shot("live-away-roles")
        device.pressBack();click("edit-lineup-home")
        NativeUi.find(device,By.text("下一位打者"));shot("live-home-roles")
        click("lineup-down-1");click("save-lineup")
        await(game.id){it.revision>game.revision}
        NativeUi.find(device,By.res("lineup-saved"))
        val result=stored(game.id)
        assertEquals(game.batter.id,result.batter.id);assertEquals(game.balls,result.balls);assertEquals(game.strikes,result.strikes)
        assertEquals(game.totalPitches,result.totalPitches)
        assertEquals(game.homeOrder!!.first(),result.homeOrder!![result.homeBatterIndex])
        shot("live-saved-continue-editing")
    }

    @Test fun unsavedExitOffersContinueDiscardAndSaveThenReturnOnlyAfterCommit() {
        val game=liveGame();openLineup(game)
        click("lineup-down-1");device.pressBack()
        NativeUi.find(device,By.text("保存并返回"));NativeUi.find(device,By.text("放弃调整并返回"));NativeUi.find(device,By.text("继续调整"))
        shot("unsaved-three-actions");text("继续调整")
        assertTrue(NativeUi.find(device,By.res("save-lineup")).isEnabled)
        NativeUi.find(device,By.res("lineup-player-${game.batter.id}"))
        // Continue must return to a working editor, not leave the task menu
        // behind the discarded dialog. Make a further draft-only position swap.
        click("lineup-position-away-0");click("lineup-position-option-3")
        assertTrue(NativeUi.find(device,By.res("save-lineup")).isEnabled)
        shot("unsaved-continued-editing")
        assertEquals(game.revision,stored(game.id).revision)
        device.pressBack();text("放弃调整并返回")
        NativeUi.find(device,By.res("edit-lineup-away"));assertEquals(game.awayOrder,stored(game.id).awayOrder)
        click("edit-lineup-away");click("lineup-down-1");device.pressBack();text("保存并返回")
        await(game.id){it.revision>game.revision}
        NativeUi.find(device,By.res("edit-lineup-away"));shot("saved-and-returned")
        assertEquals(game.batter.id,stored(game.id).batter.id)
    }

    @Test fun profileCorrectionAndCancellationKeepIdentityExistingStatsAndOriginalEventText() {
        val game=liveGame();val player=game.away.players.first()
        assertEquals(1,game.batting[player.id]!!.hits)
        openLineup(game);click("lineup-player-${player.id}");text("更正姓名／号码")
        input("lineup-chinese-name","陈靖森更正");input("lineup-english-name","Jingsen Chen");input("lineup-numbers","00, 0")
        shot("profile-correction");text("取消")
        assertEquals(game.away.players.first().name,stored(game.id).away.players.first().name)
        click("lineup-player-${player.id}");text("更正姓名／号码")
        input("lineup-chinese-name","陈靖森更正");input("lineup-english-name","Jingsen Chen");input("lineup-numbers","00, 0");text("完成")
        assertEquals(game.revision,stored(game.id).revision)
        click("save-lineup");await(game.id){it.away.players.first().name=="陈靖森更正"}
        val result=stored(game.id)
        assertEquals(player.id,result.away.players.first().id);assertEquals(listOf("00","0"),result.away.players.first().jerseyNumbers)
        assertEquals(game.batting,result.batting);assertEquals(game.pitching,result.pitching)
        assertEquals(game.bases,result.bases);assertEquals(game.balls,result.balls);assertEquals(game.batter.id,result.batter.id)
        assertEquals(game.entries.first(),result.entries.first())
        assertEquals("陈靖森更正",db {d,_->GameJson.decodeFromString<Team>(d.dao().team(game.away.id)!!.payload).players.first().name})
        try { NativeUi.find(device,By.res("lineup-saved")) }
        catch(failure:Throwable) { shot("profile-feedback-missing");throw failure }
        shot("profile-saved-statistics-retained")
    }
    @Test fun longPressDragMovesWholePlayerWhileKeepingLiveBatterPositionAndCount() {
        val game=liveGame()
        val current=game.batter.id
        val target=game.awayOrder!![2]
        openLineup(game)
        val start=NativeUi.find(device,By.res("lineup-drag-$current")).visibleBounds
        val end=NativeUi.find(device,By.res("lineup-drag-$target")).visibleBounds
        // UiDevice.drag includes the initial long press; swipe would only scroll.
        assertTrue(device.drag(start.centerX(),start.centerY(),end.centerX(),end.bottom-4,40))
        device.waitForIdle(1000)
        shot("long-press-drag-draft")
        click("save-lineup")
        await(game.id){it.revision>game.revision}
        val result=stored(game.id)
        val expectedOrder=game.awayOrder!!.toMutableList().apply { add(2,removeAt(indexOf(current))) }
        assertEquals(expectedOrder,result.awayOrder)
        assertEquals(current,result.awayOrder!![2])
        assertEquals(game.batter.id,result.batter.id)
        assertEquals(game.awayPositions,result.awayPositions)
        assertEquals(game.balls,result.balls);assertEquals(game.strikes,result.strikes)
        assertEquals(game.totalPitches,result.totalPitches)
        assertEquals(game.batting,result.batting);assertEquals(game.pitching,result.pitching)
        NativeUi.find(device,By.res("lineup-saved"))
        shot("long-press-drag-saved")
    }

    @Test fun replacementMustBeSavedBeforeMovingItsBattingSlotAndPreservesExitAfterSeparateSort() {
        try {
            val game=liveGame()
            val outgoing=game.awayOrder!![2]
            val incoming=game.away.players[9].id
            assertNotEquals(game.batter.id,outgoing);assertFalse(outgoing in game.bases)
            val before=db {d,_->Triple(d.dao().allGames().associate {it.id to it.payload},
                d.dao().allTeams().associate {it.id to it.payload},d.dao().allOperations().associateBy {it.id})}
            openLineup(game)
            click("lineup-replace-$outgoing");click("lineup-replace-with-$incoming")
            click("lineup-down-3")
            NativeUi.find(device,By.text("请先保存换人，再调整棒次；每位替补须接替原棒次"))
            assertFalse("The combined replacement/reorder must not be submitted",NativeUi.find(device,By.res("save-lineup")).isEnabled)
            shot("replacement-sort-blocked-with-reason")
            db {d,_->
                assertEquals(before.first,d.dao().allGames().associate {it.id to it.payload})
                assertEquals(before.second,d.dao().allTeams().associate {it.id to it.payload})
                assertEquals(before.third,d.dao().allOperations().associateBy {it.id})
            }
            click("lineup-up-4")
            assertTrue("Returning the substitute to the original slot enables the valid save",NativeUi.find(device,By.res("save-lineup")).isEnabled)
            click("save-lineup");await(game.id){it.revision==game.revision+1}
            val replaced=stored(game.id)
            val expectedReplacement=game.awayOrder!!.map {if(it==outgoing)incoming else it}
            assertEquals(expectedReplacement,replaced.awayOrder)
            assertEquals(mapOf(outgoing to incoming),replaced.journal.last().command.lineupReplacements)
            assertTrue(outgoing in replaced.exitedPlayers)
            assertEquals(game.awayPositions!!.mapValues {if(it.value==outgoing)incoming else it.value},replaced.awayPositions)
            assertEquals(game.batter.id,replaced.batter.id);assertEquals(game.balls,replaced.balls)
            NativeUi.find(device,By.res("lineup-saved"));shot("replacement-saved-before-sort")
            click("lineup-down-3");click("save-lineup")
            await(game.id){it.revision==replaced.revision+1}
            val sorted=stored(game.id)
            val expectedSorted=expectedReplacement.toMutableList().apply {java.util.Collections.swap(this,2,3)}
            assertEquals(expectedSorted,sorted.awayOrder)
            assertTrue(outgoing in sorted.exitedPlayers)
            assertEquals(replaced.awayPositions,sorted.awayPositions)
            assertTrue(sorted.journal.last().command.lineupReplacements.isNullOrEmpty())
            assertEquals(game.batter.id,sorted.batter.id);assertEquals(game.balls,sorted.balls);assertEquals(game.strikes,sorted.strikes)
            assertEquals(game.totalPitches,sorted.totalPitches)
            assertEquals(game.batting,sorted.batting);assertEquals(game.pitching,sorted.pitching);assertEquals(game.fielding,sorted.fielding)
            assertEquals(game.entries,sorted.entries.take(game.entries.size))
            NativeUi.find(device,By.res("lineup-saved"));shot("replacement-then-independent-sort-retains-exit")
        } catch(failure:Throwable) {shot("replacement-sort-boundary-failure");throw failure}
    }

}
