package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceBackupUIFlowTest {
    @Test fun saveUsesSystemDocumentPickerAndConfirmsSuccess() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 20_000))
        device.findObject(By.res("tab-3")).click()
        repeat(8) {
            val save = device.findObject(By.res("device-backup-save"))
            if (save != null && save.visibleBounds.centerY() in device.displayHeight / 6..device.displayHeight * 8 / 10) return@repeat
            device.swipe(device.displayWidth / 2, device.displayHeight * 8 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 25)
            android.os.SystemClock.sleep(120)
        }
        val save = device.findObject(By.res("device-backup-save")) ?: error("找不到本机备份入口")
        save.click()
        val picker = device.wait(Until.findObject(By.text("SAVE")), 5_000) ?: device.wait(Until.findObject(By.text("保存")), 5_000) ?: device.wait(Until.findObject(By.text("Save")), 5_000)
        if (picker == null) {
            val dir = context.getExternalFilesDir(null)!!
            device.takeScreenshot(File(dir, "an04-device-backup-picker-failure.png")); device.dumpWindowHierarchy(File(dir, "an04-device-backup-picker-failure.xml"))
        }
        assertNotNull("未打开系统保存界面", picker)
        picker.click()
        assertTrue("未收到保存成功提示", device.wait(Until.hasObject(By.textContains("本机备份已保存")), 20_000))
        device.findObject(By.text("完成"))?.click()
    }
}
