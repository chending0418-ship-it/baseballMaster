package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Single-session diagnostic. No serviceInfo writes, hidden-policy changes, second
 * UiAutomation connection, shell hierarchy dumps, or application source changes. */
@RunWith(AndroidJUnit4::class)
class Api31AccessibilityCacheDiagnosticTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private val automation get()=inst.uiAutomation
    private val observations=JSONArray()
    private val report=JSONObject()
    private var bridgeCount=0
    private fun <T> db(block:suspend(LocalDatabase,GameRepository)->T):T=runBlocking {
        val database=LocalDatabase.open(context)
        try{block(database,GameRepository(database))}finally{database.close()}
    }
    private fun bridge() {
        if(Build.VERSION.SDK_INT>=34){automation.clearCache();return}
        val event=automation.executeAndWaitForEvent({
            inst.runOnMainSync {
                WindowInspector.getGlobalWindowViews().filter {it.isAttachedToWindow && it.isShown}.forEach { root ->
                    val changed=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    changed.contentChangeTypes=AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                    changed.packageName=context.packageName
                    changed.className=root.javaClass.name
                    changed.setSource(root)
                    root.sendAccessibilityEventUnchecked(changed)
                }
            }
        },{event -> event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE!=0 &&
            event.packageName?.toString()==context.packageName},5000)
        event.recycle();bridgeCount++
    }
    private fun visible(tag:String):UiObject2?=runCatching {
        device.findObject(By.res(tag))?.takeIf {val b=it.visibleBounds;!b.isEmpty && b.height()>16 && b.centerY() in 24..device.displayHeight-24}
    }.getOrNull()
    private fun recordLookup(stage:String,tag:String):UiObject2? {
        val cached=visible(tag)
        val cachedBounds=cached?.visibleBounds?.toShortString()
        bridge()
        val fresh=visible(tag)
        observations.put(JSONObject().put("stage",stage).put("tag",tag)
            .put("beforeBridgeVisible",cached!=null).put("beforeBridgeBounds",cachedBounds)
            .put("afterBridgeVisible",fresh!=null).put("afterBridgeBounds",fresh?.visibleBounds?.toShortString()))
        return fresh
    }
    private fun scroll(down:Boolean) {
        // The fixed workbench has independent panes. Never swipe the hinge or
        // the left field when a recording task owns the right pane.
        val task=device.findObject(By.res("embedded-product-task"))
        val viewport=(task?.findObject(By.scrollable(true))
            ?: device.findObject(By.pkg(context.packageName).scrollable(true)))?.visibleBounds
        val left=viewport?.left ?: 0
        val right=viewport?.right ?: device.displayWidth
        val top=maxOf(viewport?.top ?: 0,device.displayHeight/8)+24
        val bottom=minOf(viewport?.bottom ?: device.displayHeight,device.displayHeight*9/10)-24
        check(bottom-top>80){"No usable task scroll viewport"}
        device.swipe((left+right)/2,if(down)bottom else top,(left+right)/2,if(down)top else bottom,20)
        device.waitForIdle(350)
    }
    private fun find(tag:String):UiObject2 {
        val deadline=SystemClock.uptimeMillis()+25000
        var attempts=0
        var down=true
        while(SystemClock.uptimeMillis()<deadline) {
            bridge()
            visible(tag)?.let {return it}
            if(attempts++%6==5)down=!down
            scroll(down)
        }
        error("Normal subtree events failed to expose visible $tag")
    }
    private fun click(tag:String){find(tag).click();device.waitForIdle(350)}
    private fun input(tag:String,value:String) {
        val node=find(tag);node.click();node.text=value
        inst.runOnMainSync {
            val manager=context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            WindowInspector.getGlobalWindowViews().forEach {manager.hideSoftInputFromWindow(it.windowToken,0)}
        }
        device.waitForIdle(350)
    }
    private fun launch() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)),30000))
        bridge();visible("back-home")?.click();click("tab-0")
    }
    private fun shot(name:String) {
        bridge();device.waitForIdle(350)
        device.takeScreenshot(File(context.getExternalFilesDir(null),"081-cache-diagnostic-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"081-cache-diagnostic-$name.xml"))
    }
    @Test fun normalSubtreeEventsRefreshRealPregameScrollAndProFieldingWithoutServiceReconfiguration() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(2500)
        val serviceBefore=automation.serviceInfo.toString()
        report.put("sdk",Build.VERSION.SDK_INT).put("targetSdk",context.applicationInfo.targetSdkVersion)
        report.put("uiAutomationClearCacheExists",automation.javaClass.methods.any {it.name=="clearCache" && it.parameterCount==0})
        try {
            val type=Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val client=type.getMethod("getInstance").invoke(null)
            type.getMethod("clearCache").invoke(client)
            report.put("hiddenReflection","available-with-current-instrumentation-policy")
        }catch(failure:Throwable) {
            report.put("hiddenReflection","${failure.javaClass.name}: ${failure.message}")
        }
        // The reflection result is evidence only. All real navigation below uses
        // the public normal-event bridge, never the reflective path.
        val roster="陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
        val (own,opponent,game)=db {_,repo ->
            val own=repo.createTeam("海风诊断队",roster)
            val opponent=repo.createTeam("青禾诊断队",roster,true)
            val initial=repo.createGame(own,"青禾诊断对手",roster,GameMode.standard,false,Rules(recordingMethod="situation"))
            Triple(own,opponent,repo.command(initial.id,Command("pitch",pitch=PitchAction.ball),initial.revision))
        }
        try {
            launch();click("new-game")
            click("setup-own-team");input("roster-picker-search",own.name);click("choose-roster-${own.id}")
            click("setup-opponent");input("roster-picker-search",opponent.name);click("choose-roster-${opponent.id}")
            click("start-game")
            find("lineup-row-away-0")
            // Explicitly move this row out of and back into the viewport even
            // when a wide window initially displays both teams side by side.
            recordLookup("pregame-home-initial","lineup-position-home-0")
            repeat(3){scroll(true);bridge()}
            recordLookup("pregame-home-after-scroll-down","lineup-position-home-0")
            repeat(3){scroll(false);bridge()}
            val home=find("lineup-position-home-0")
            assertTrue(home.visibleBounds.height()>16)
            assertNotNull(recordLookup("pregame-home-after-real-scroll","lineup-position-home-0"))
            home.click();device.waitForIdle(350)
            assertNotNull(recordLookup("home-position-menu-open","lineup-position-option-2"))
            click("lineup-position-option-2")
            val position=find("lineup-position-home-0")
            assertNotNull("Fresh home position must show the actual selected catcher",position.findObject(By.text("捕手")))
            shot("home-position-fresh")
            device.pressBack();bridge()
            device.wait(Until.hasObject(By.text("放弃调整并返回")),3000)
            bridge();device.findObject(By.text("放弃调整并返回"))?.click()
            device.waitForIdle(350);device.pressBack();device.waitForIdle(350)
            launch();click("game-${game.id}");click("record-play");click("pro-outcome-single")
            find("contact-field")
            recordLookup("pro-before-landing-tap","fielding-6")
            val field=find("contact-field").visibleBounds
            device.click(field.left+field.width()*3/5,field.top+field.height()*2/5)
            device.waitForIdle(350)
            val defender=recordLookup("pro-after-real-landing-tap","fielding-6") ?: find("fielding-6")
            assertTrue(defender.visibleBounds.height()>16)
            defender.click();device.waitForIdle(350);bridge()
            val route=find("situation-route").text
            val name=game.fieldingTeam.players.first {it.id==game.fieldingPositions[6]}.name
            assertTrue("Fresh defender selection must change the actual route",route.contains(name))
            shot("fielding-six-fresh")
            assertEquals(game,db {d,_->GameRepository.decode(d.dao().game(game.id)!!)})
            assertEquals("No service metadata setter may be used",serviceBefore,automation.serviceInfo.toString())
            report.put("outcome","passed")
        }catch(failure:Throwable) {
            report.put("outcome","failed").put("failure","${failure.javaClass.name}: ${failure.message}")
            runCatching{shot("failure")}
            throw failure
        }finally {
            report.put("bridgeCount",bridgeCount).put("observations",observations)
            File(context.getExternalFilesDir(null),"081-cache-diagnostic-report.json").writeText(report.toString(2))
        }
    }
}
