package cc.baseballmaster.scorekeeper

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A real Activity rotation captures the framework's completed SavedState Bundle.
 * The match is over 1 MiB, while both lineup and Pro drafts must remain small. */
@RunWith(AndroidJUnit4::class)
class Product081LargeDraftRotationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val app get()=context.applicationContext as Application
    private val device=UiDevice.getInstance(inst)
    private val savedStates=Collections.synchronizedMap(mutableMapOf<Activity,Int>())
    private val receipts=mutableListOf<String>()
    private val callback=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity:Activity,state:Bundle?) {}
        override fun onActivityStarted(activity:Activity) {}
        override fun onActivityResumed(activity:Activity) {}
        override fun onActivityPaused(activity:Activity) {}
        override fun onActivityStopped(activity:Activity) {}
        override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
        override fun onActivityDestroyed(activity:Activity) {}
        override fun onActivityPostSaveInstanceState(activity:Activity,state:Bundle) {
            if(activity is MainActivity) {
                val parcel=Parcel.obtain()
                try {parcel.writeBundle(state);savedStates[activity]=parcel.dataSize()}finally{parcel.recycle()}
            }
        }
    }
    @Before fun configure() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        device.setOrientationNatural();device.waitForIdle(500)
        app.registerActivityLifecycleCallbacks(callback)
    }
    @After fun restore() {
        app.unregisterActivityLifecycleCallbacks(callback)
        device.setOrientationNatural();device.unfreezeRotation()
    }
    private fun <T> db(block:suspend(LocalDatabase,GameRepository)->T):T=runBlocking {
        val database=LocalDatabase.open(context)
        try{block(database,GameRepository(database))}finally{database.close()}
    }
    private fun payload(id:String)=db {d,_->requireNotNull(d.dao().game(id)).payload}
    private fun operationCount(id:String)=db {d,_->d.dao().operationCount(id)}
    private fun click(tag:String){NativeUi.find(device,By.res(tag)).click();device.waitForIdle(500)}
    private fun text(value:String){NativeUi.find(device,By.text(value)).click();device.waitForIdle(500)}
    private fun resumed():MainActivity? {
        var result:MainActivity?=null
        inst.runOnMainSync {result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()}
        return result
    }
    private fun rotate(left:Boolean,label:String) {
        val previous=requireNotNull(resumed())
        if(left)device.setOrientationLeft()else device.setOrientationNatural()
        val deadline=SystemClock.uptimeMillis()+30000
        var next:MainActivity?=null
        while(SystemClock.uptimeMillis()<deadline) {
            next=resumed()
            if(next!=null && next!==previous && savedStates.containsKey(previous))break
            SystemClock.sleep(100)
        }
        assertNotNull("Activity must resume after actual rotation",next)
        assertNotSame("Rotation must recreate the Activity",previous,next)
        val bytes=requireNotNull(savedStates[previous]){"Framework must report the completed SavedState Bundle"}
        assertTrue("A lineup/Pro draft must not copy the >1MiB match into SavedState ($bytes bytes)",bytes<256*1024)
        receipts.add("$label savedStateBytes=$bytes activityRecreated=true")
        device.waitForIdle(750)
    }
    private fun shot(name:String) {
        device.waitForIdle(500);inst.waitForIdleSync()
        val frame=CountDownLatch(1)
        inst.runOnMainSync {android.view.Choreographer.getInstance().postFrameCallback {android.view.Choreographer.getInstance().postFrameCallback {frame.countDown()}}}
        assertTrue(frame.await(3,TimeUnit.SECONDS))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"081-large-draft-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"081-large-draft-$name.xml"))
    }
    @Test fun actualRotationsKeepLineupAndProDraftsWithLargeHistoryOutsideSavedStateAndNeverSubmit() {
        val roster="陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
        val game=db {_,repo ->
            val own=repo.createTeam("海风少年队",roster)
            var g=repo.createGame(own,"青禾棒球队",roster,GameMode.standard,false,Rules(recordingMethod="situation"))
            g=repo.command(g.id,Command("pitch",pitch=PitchAction.ball),g.revision)
            g=repo.command(g.id,Command("note",text="ARCHIVE-CONTEXT|"+"atbat context ".repeat(45000)),g.revision)
            // Keep the deliberately large archival note outside the recent-log UI.
            repeat(6){index->g=repo.command(g.id,Command("note",text="赛场观察 ${index+1}"),g.revision)}
            g
        }
        val before=payload(game.id);val count=operationCount(game.id)
        assertTrue("Fixture must actually exceed 1 MiB",before.toByteArray().size>1024*1024)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)),30000))
        device.findObject(By.res("back-home"))?.click();click("tab-0");click("game-${game.id}")
        click("open-substitutions");click("edit-lineup-away");click("lineup-down-1")
        assertTrue(NativeUi.find(device,By.res("save-lineup")).isEnabled)
        rotate(true,"lineup-landscape")
        val moved=NativeUi.find(device,By.res("lineup-row-away-1"))
        assertNotNull("The same draft player must remain in the moved batting slot",moved.findObject(By.res("lineup-player-${game.awayOrder!!.first()}")))
        assertTrue(NativeUi.find(device,By.res("save-lineup")).isEnabled)
        assertEquals(before,payload(game.id));assertEquals(count,operationCount(game.id))
        rotate(false,"lineup-portrait")
        assertNotNull(NativeUi.find(device,By.res("lineup-row-away-1")).findObject(By.res("lineup-player-${game.awayOrder!!.first()}")))
        shot("lineup-restored")
        device.pressBack();text("放弃调整并返回");device.pressBack()
        click("record-play");click("pro-outcome-single");click("choice-球型-ground")
        val field=NativeUi.find(device,By.res("contact-field")).visibleBounds
        device.click(field.left+field.width()*3/5,field.top+field.height()*2/5)
        device.waitForIdle(500);click("fielding-6")
        val landing=NativeUi.find(device,By.res("landing-selection-state")).text
        val route=NativeUi.find(device,By.res("situation-route")).text
        assertFalse(landing.contains("未点选"))
        assertTrue(route.contains(game.fieldingTeam.players.first {it.id==game.fieldingPositions[6]}.name))
        rotate(true,"pro-landscape")
        assertEquals(landing,NativeUi.find(device,By.res("landing-selection-state")).text)
        assertEquals(route,NativeUi.find(device,By.res("situation-route")).text)
        assertEquals(before,payload(game.id));assertEquals(count,operationCount(game.id))
        rotate(false,"pro-portrait")
        assertEquals(landing,NativeUi.find(device,By.res("landing-selection-state")).text)
        assertEquals(route,NativeUi.find(device,By.res("situation-route")).text)
        shot("pro-restored");click("cancel-play")
        assertEquals(before,payload(game.id));assertEquals(count,operationCount(game.id))
        File(context.getExternalFilesDir(null),"081-large-draft-saved-state-receipt.txt").writeText("matchBytes=${before.toByteArray().size}\n"+receipts.joinToString("\n"))
    }
}
