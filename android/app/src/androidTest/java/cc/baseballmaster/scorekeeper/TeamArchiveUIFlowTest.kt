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

@RunWith(AndroidJUnit4::class)
class TeamArchiveUIFlowTest {
    @Test fun readOnlyArchiveSurvivesRepeatedExportCancellationAndBack() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val bytes = inst.context.assets.open("ios-team-reference.bmteam").use { it.readBytes() }
        val directory = File(context.filesDir, "team-archives")
        val store = TeamArchiveStore(directory, File(context.cacheDir, "team-archive-staging"))
        val pending = store.stage(bytes.inputStream())
        val saved = try { store.save(pending) } finally { store.discard(pending) }
        val original = File(directory, "${saved.item.id}.bmteam")
        try {
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 20_000))
            click(device, "tab-3", scroll = false)
            click(device, "open-team-archives")
            assertTrue(device.wait(Until.hasObject(By.res("import-team-archive")), 10_000))
            click(device, "team-archive-${saved.item.id}")
            assertTrue(device.wait(Until.hasObject(By.res("export-team-archive")), 10_000))
            assertTrue(device.hasObject(By.text("互导测试球队")))
            assertTrue(device.hasObject(By.textContains("只读快照")))
            repeat(2) {
                assertTrue("System save picker did not open", device.performActionAndWait(
                    { click(device, "export-team-archive") }, Until.newWindow(), 10_000))
                assertNotEquals(context.packageName, device.currentPackageName)
                device.pressBack()
                assertTrue("Cancel did not return to the reader", device.wait(Until.hasObject(By.res("export-team-archive")), 10_000))
            }
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.res("export-team-archive")), 10_000))
            assertTrue(device.hasObject(By.res("import-team-archive")))
            click(device, "team-archive-${saved.item.id}")
            assertTrue(device.wait(Until.hasObject(By.res("export-team-archive")), 10_000))
            device.pressBack()
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 10_000))
            assertArrayEquals(bytes, original.readBytes())
            assertEquals(1, store.list().items.count { it.id == saved.item.id })
        } catch (failure: Throwable) {
            val evidence = context.getExternalFilesDir(null)!!
            device.takeScreenshot(File(evidence, "an04-team-archive-failure.png"))
            device.dumpWindowHierarchy(File(evidence, "an04-team-archive-failure.xml"))
            throw failure
        } finally {
            // Keep any pre-existing copy; only remove the synthetic file this test newly installed.
            if (!saved.alreadyPresent) original.delete()
        }
    }

    private fun click(device: UiDevice, tag: String, scroll: Boolean = true) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val node = device.findObject(By.res(tag))
                if (node != null) {
                    val bounds = node.visibleBounds
                    if (!bounds.isEmpty && (!scroll || bounds.centerY() in device.displayHeight / 8..device.displayHeight * 9 / 10)) {
                        node.click()
                        return
                    }
                }
            } catch (_: StaleObjectException) { continue }
            if (scroll) device.swipe(device.displayWidth / 2, device.displayHeight * 8 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 25)
            device.waitForIdle(500)
        }
        error("找不到可操作的档案控件：$tag")
    }

}
