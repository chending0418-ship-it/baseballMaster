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

/** iOS 3.1.1 result-catalog parity, through real Compose screens and Room. */
@RunWith(AndroidJUnit4::class)
class ProCatalogFlowTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val device get()=UiDevice.getInstance(inst)
    @org.junit.Rule @JvmField val evidence=object:org.junit.rules.TestWatcher() {
        override fun failed(e:Throwable,d:org.junit.runner.Description) {
            device.takeScreenshot(File(context.getExternalFilesDir(null),"081-regression-pro-${d.methodName}-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"081-regression-pro-${d.methodName}-failure.xml"))
        }
    }
    private fun <T> db(block:suspend (GameRepository,LocalDatabase)->T):T=runBlocking {
        val database=LocalDatabase.open(context)
        try {block(GameRepository(database),database)} finally {database.close()}
    }
    @org.junit.Before fun configure() { Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000) }
    private fun seed(mode:GameMode=GameMode.standard,runner:Boolean=false):Game=db {repo,_->
        val roster=(1..12).joinToString("\n") {"球员$it,$it"}
        val team=repo.createTeam("Pro311 ${UUID.randomUUID().toString().take(5)}",roster)
        val game=repo.createGame(team,"合成对手",roster,mode,false,Rules(mode=mode,recordingMethod="situation"))
        if(runner) repo.command(game.id,Command("play",outcome=Outcome.triple),game.revision) else game
    }
    private fun payload(id:String)=db {_,database->database.dao().game(id)!!.payload}
    private fun stored(id:String)=GameJson.decodeFromString<Game>(payload(id))
    private fun click(tag:String) {NativeUi.find(device,By.res(tag)).click();device.waitForIdle(500)}
    private fun choose(tag:String) {
        val choice = NativeUi.find(device, By.res(tag))
        if (!choice.isChecked && !choice.isSelected) choice.click()
        device.waitForIdle(500)
        val selected = NativeUi.find(device, By.res(tag))
        assertTrue("The product choice must be selected", selected.isChecked || selected.isSelected)
    }
    private fun finishContact(requiresReview:Boolean) {
        val action = if (requiresReview) "play-next" else "save-play"
        StatisticsUi.use(device, By.res(action), scroll = false) {
            assertTrue("Contact must be valid before submitting or reviewing", it.isEnabled)
            it.click()
        }
        if (requiresReview) {
            val review = NativeUi.find(device, By.res("save-play"))
            assertTrue("The visible review must be valid before its single save", review.isEnabled)
            click("save-play")
        }
    }
    private fun text(value:String)=NativeUi.find(device,By.text(value))
    private fun open(game:Game) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")),30_000))
        click("game-${game.id}");click("record-play")
        if(!game.openingPitchRecorded) click("confirm-opening-play")
    }
    private fun process(type:String) {
        click("situation-by-process");click("choice-球型-$type")
        assertTrue(device.wait(Until.hasObject(By.res("process-selected-type")),10_000))
        assertTrue(device.findObject(By.res("process-selected-type")).text.startsWith(ScoringOptions.ballTypes.getValue(type)))
        click("process-all-outcomes")
        assertTrue(device.wait(Until.hasObject(By.res("outcome-catalog-search")),10_000))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"081-regression-pro-full-catalog.png"))
    }
    private fun await(id:String,predicate:(Game)->Boolean):Game {
        val end=android.os.SystemClock.uptimeMillis()+10_000
        while(android.os.SystemClock.uptimeMillis()<end) {
            val g=stored(id);if(predicate(g)) return g
            android.os.SystemClock.sleep(100)
        }
        return stored(id).also {assertTrue("Saved match did not reach expected state",predicate(it))}
    }
    @Test fun processCatalogBackKeepsBallTypeAndCancelPreservesTheMatch() {
        val game=seed();val before=payload(game.id);open(game);process("ground")
        NativeUi.find(device, By.desc("返回")).click()
        assertTrue(device.wait(Until.hasObject(By.res("process-selected-type")),10_000))
        assertTrue(device.findObject(By.res("process-selected-type")).text.startsWith("地滚"))
        click("process-all-outcomes");click("pro-outcome-homeRun")
        NativeUi.find(device,By.res("choice-球型-ground")).let {assertTrue(it.isChecked || it.isSelected)}
        click("cancel-play")
        assertTrue(device.wait(Until.gone(By.res("cancel-play")),10_000))
        assertEquals(before,payload(game.id))
    }
    @Test fun processOtherResultsCanRecordHomeRunExactlyOnceAndUndoRedo() {
        val game=seed();open(game);process("line");click("pro-outcome-homeRun")
        assertNotNull(device.wait(Until.findObject(By.res("contact-field")), 10_000))
        click("contact-other-actions");choose("choice-区域-left");click("situation-no-fielding")
        finishContact(requiresReview = false)
        val result=await(game.id) {it.awayRuns==1}
        assertEquals(1,result.totalPitches);assertEquals(1,result.cursor)
        assertEquals("line",result.entries.single().observation?.type)
        assertEquals("noFielding",result.entries.single().observation?.fielding?.status)
        assertEquals(1,result.batting.getValue(game.batter.id).homeRuns)
        click("undo");await(game.id) {it.awayRuns==0 && it.cursor==0}
        click("redo");await(game.id) {it.awayRuns==1 && it.cursor==1}
        device.takeScreenshot(File(context.getExternalFilesDir(null),"081-regression-pro-catalog-home-run.png"))
    }
    @Test fun sacrificeFlySelectsMatchingTypeAndPreservesAtBatRunAndOutAccounting() {
        val game=seed(runner=true);open(game);process("ground")
        NativeUi.find(device,By.res("outcome-catalog-search")).text="牺牲"
        click("pro-outcome-sacrificeFly")
        NativeUi.find(device,By.res("choice-球型-fly")).let {assertTrue(it.isChecked || it.isSelected)}
        choose("choice-球型-ground");click("contact-other-actions");choose("choice-区域-center");click("choice-守备处理-pending")
        text("牺牲高飞请核对高飞、平飞或内野小飞球型。")
        assertFalse(device.findObject(By.res("play-next")).isEnabled)
        choose("choice-球型-fly");finishContact(requiresReview = true)
        val result=await(game.id) {it.awayRuns==1 && it.outs==1}
        val batter=result.batting.getValue(game.batter.id)
        assertEquals(0,batter.atBats);assertEquals(1,batter.sacrifices);assertEquals(1,batter.runsBattedIn)
        assertEquals(1,result.pitching.getValue(game.pitcher.id).outsRecorded)
        assertEquals(2,result.totalPitches)
    }
    @Test fun slowPitchCatalogExplainsWhySacrificeBuntIsUnavailable() {
        val game=seed(GameMode.slowPitch);val before=payload(game.id);open(game)
        click("all-batted-outcomes")
        NativeUi.find(device,By.res("outcome-catalog-search")).text="牺牲"
        val bunt=NativeUi.find(device,By.res("pro-outcome-sacrificeBunt"))
        assertFalse(bunt.isEnabled)
        text("慢垒不允许触击。")
        click("cancel-play");assertEquals(before,payload(game.id))
    }
}
