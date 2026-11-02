package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class TeamArchiveUIFlowTest {
    @Test fun readOnlyArchiveSurvivesRepeatedExportCancellationAndBack() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val bytes = inst.context.assets.open("ios-team-reference.bmteam").use { it.readBytes() }
        val store = TeamArchiveRoomStore(context)
        val pending = store.stage(bytes.inputStream())
        val saved = try {
            runBlocking(Dispatchers.IO) {
                val first = store.save(pending)
                assertTrue("重复保存同档案必须幂等", store.save(pending).alreadyPresent)
                first
            }
        } finally { store.discard(pending) }
        try {
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 20_000))
            click(device, "tab-3", scroll = false)
            click(device, "open-local-data")
            click(device, "open-team-archives")
            // The import button may start below the explanatory text on a 360 dp
            // phone. Find it in the reader rather than opening the reader again.
            NativeUi.find(device, By.res("import-team-archive"))
            click(device, "team-archive-${saved.item.id}")
            NativeUi.find(device, By.res("export-team-archive"))
            assertTrue(device.hasObject(By.text("互导测试球队")))
            assertTrue(device.hasObject(By.textContains("只读快照")))
            click(device, "archive-game-history")
            assertTrue(device.wait(Until.hasObject(By.res("archive-history-title")), 10_000))
            val history = cc.baseballmaster.core.AppleArchiveHistory.read(
                cc.baseballmaster.core.AppleTeamArchiveIntegrity.read(bytes.inputStream()).records.first().getValue("payload").jsonObject)
            val counts = listOf(history.logs.size, history.events.size, history.appearances.size, history.operations.size, history.corrections.size)
            val labels = listOf("逐球日志", "记分事件", "打席", "历史操作", "更正审计")
            counts.forEachIndexed { index, count ->
                click(device, "archive-history-section-$index")
                assertTrue(device.wait(Until.hasObject(By.text("${labels[index]} · $count 条")), 10_000))
            }
            device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-archive-corrections.png"))
            device.pressBack()
            NativeUi.find(device, By.res("export-team-archive"))
            // Return to the top before opening the system picker.
            repeat(4) { device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 8 / 10, 20) }
            repeat(2) {
                assertTrue("System save picker did not open", device.performActionAndWait(
                    { click(device, "export-team-archive") }, Until.newWindow(), 10_000))
                assertNotEquals(context.packageName, device.currentPackageName)
                device.pressBack()
                assertTrue("Cancel did not return to the reader", device.wait(Until.hasObject(By.res("export-team-archive")), 10_000))
            }
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.res("export-team-archive")), 10_000))
            NativeUi.find(device, By.res("import-team-archive"))
            click(device, "team-archive-${saved.item.id}")
            NativeUi.find(device, By.res("export-team-archive"))
            device.pressBack()
            device.pressBack()
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 10_000))
            runBlocking(Dispatchers.IO) {
                val exported = ByteArrayOutputStream()
                store.export(saved.item.id, exported)
                assertArrayEquals("Repeated picker cancellation must preserve every original byte", bytes, exported.toByteArray())
                assertEquals(1, store.list().items.count { it.id == saved.item.id })
            }
        } catch (failure: Throwable) {
            val evidence = context.getExternalFilesDir(null)!!
            device.takeScreenshot(File(evidence, "an04-team-archive-failure.png"))
            device.dumpWindowHierarchy(File(evidence, "an04-team-archive-failure.xml"))
            throw failure
        } finally {
            // Keep all device data, including any pre-existing identical synthetic archive.
            store.close()
        }
    }

    private fun click(device: UiDevice, tag: String, scroll: Boolean = true) {
        repeat(3) {
            try {
                NativeUi.find(device, By.res(tag)).click()
                device.waitForIdle(500)
                return
            } catch (_: StaleObjectException) { }
        }
        error("找不到稳定的档案控件：$tag")
    }

}
