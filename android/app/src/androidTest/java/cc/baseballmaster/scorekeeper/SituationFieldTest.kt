package cc.baseballmaster.scorekeeper

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SituationFieldTest {
    @Test fun actualFieldDeliversNormalizedPointAndDrawsMarker() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        fun team(id:String)=Team(id,id,(1..12).map{Player("$id-$it","球员$it",it.toString())})
        val game=MatchFactory.create("field",team("home"),team("away"),Rules())
        val observation=mutableStateOf(Observation(type="line",precision="point"))
        val received=AtomicReference<Pair<Double,Double>>()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->activity.setContent{MaterialTheme{Box(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.background(Color(0xFFF5F2EA)),contentAlignment=Alignment.Center){Box(Modifier.padding(18.dp)){SituationField(game,observation.value,onPoint={x,y->received.set(x to y);observation.value=observation.value.copy(x=x,y=y)})}}}}}
            val field=device.wait(Until.findObject(By.res("contact-field")),10_000)?:error("No actual field")
            val rect=field.visibleBounds
            field.click(android.graphics.Point(rect.left+rect.width()/3,rect.top+rect.height()/4))
            repeat(30){if(received.get()==null)android.os.SystemClock.sleep(100)}
            File(context.getExternalFilesDir(null),"field-point-callback.txt").writeText(received.get().toString())
            assertNotNull("Actual field never delivered a point",received.get())
            assertEquals(1.0/3.0,received.get().first,.02);assertEquals(.25,received.get().second,.02)
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null),"field-isolated.png")))
        }
    }

    @Test fun editorRetainsTheActualPointBeforeSave() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        fun team(id:String)=Team(id,id,(1..12).map{Player("$id-$it","球员$it",it.toString())})
        val game=MatchFactory.create("editor",team("home"),team("away"),Rules(recordingMethod="situation"))
        val saved=AtomicReference<Command>()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->activity.setContent{BaseballTheme{PlayEditor(game,false,{}){saved.set(it)}}}}
            val point=device.wait(Until.findObject(By.res("choice-落点精度-point")),5_000)?:run {
                repeat(3){device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.4f)}
                device.findObject(By.res("choice-落点精度-point"))!!
            }
            point.click();device.waitForIdle()
            repeat(4){
                val f=device.findObject(By.res("contact-field"))
                if(f==null||f.visibleBounds.height()<260*context.resources.displayMetrics.density*.95)device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.2f)
            }
            android.os.SystemClock.sleep(600)
            if(android.os.Build.VERSION.SDK_INT>=34)instrumentation.uiAutomation.clearCache()
            val field=device.findObject(By.res("contact-field"))!!;val rect=field.visibleBounds
            field.click(android.graphics.Point(rect.left+rect.width()/3,rect.top+rect.height()/4))
            android.os.SystemClock.sleep(500)
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"field-editor-before-save.xml"))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"field-editor-before-save.png"))
            device.findObject(By.res("save-play"))!!.click()
            repeat(30){if(saved.get()==null)android.os.SystemClock.sleep(100)}
            val command=saved.get()!!
            File(context.getExternalFilesDir(null),"field-editor-saved.json").writeText(GameJson.encodeToString(command))
            assertNotNull("Editor lost point x",command.observation!!.x)
            assertNotNull("Editor lost point y",command.observation!!.y)
        }
    }
}
