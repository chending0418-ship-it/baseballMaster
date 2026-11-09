package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSystemFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @org.junit.Rule @JvmField val capture = object : org.junit.rules.TestWatcher() {
        override fun failed(error: Throwable, description: org.junit.runner.Description) {
            val directory = context.getExternalFilesDir(null)!!
            device.takeScreenshot(File(directory, "profile-system-failure.png"))
            device.dumpWindowHierarchy(File(directory, "profile-system-failure.xml"))
        }
    }

    private fun launch() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 20_000))
        NativeUi.find(device, By.res("tab-3")).click()
    }

    private fun click(tag: String) { NativeUi.find(device, By.res(tag)).click(); device.waitForIdle() }

    @Test fun settingsSurviveActivityRecreationAndAboutAndDataReturnSafely() {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        assertEquals("棒球比赛记录大师", label)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val appearance = settings.getString("appAppearance", "system")
        val guidance = settings.getBoolean("showPracticeGuidance", true)
        val examples = settings.getBoolean("showRuleExamples", true)
        try {
            launch()
            click("open-app-settings")
            assertEquals(label, NativeUi.find(device, By.res("product-brand")).contentDescription)
            click("choice-App 外观-dark")
            val guidanceBefore = NativeUi.find(device, By.res("show-practice-guidance")).isChecked
            click("show-practice-guidance")
            val examplesBefore = NativeUi.find(device, By.res("show-rule-examples")).isChecked
            click("show-rule-examples")
            assertEquals("dark", settings.getString("appAppearance", null))
            assertEquals(!guidanceBefore, settings.getBoolean("showPracticeGuidance", guidanceBefore))
            assertEquals(!examplesBefore, settings.getBoolean("showRuleExamples", examplesBefore))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "081-regression-settings-dark.png"))
            launch()
            click("open-app-settings")
            val selectedAppearance = NativeUi.find(device, By.res("choice-App 外观-dark"))
            assertTrue("The saved appearance remains selected after reopening the activity",
                selectedAppearance.isChecked || selectedAppearance.isSelected)
            assertEquals(!guidanceBefore, NativeUi.find(device, By.res("show-practice-guidance")).isChecked)
            assertEquals(!examplesBefore, NativeUi.find(device, By.res("show-rule-examples")).isChecked)
            click("choice-App 外观-light")
            device.pressBack()
            click("open-app-about")
            assertEquals(label, NativeUi.find(device, By.res("product-brand")).contentDescription)
            assertNotNull(NativeUi.find(device, By.text("Android ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")))
            assertNotNull(NativeUi.find(device, By.res("about-privacy-policy")))
            assertNotNull(NativeUi.find(device, By.res("about-support")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "081-regression-about.png"))
            device.pressBack()
            click("open-local-data")
            click("manage-roster-from-profile")
            assertNotNull(NativeUi.find(device, By.text("本机球队管理")))
            click("tab-0")
            assertNotNull(NativeUi.find(device, By.res("new-game")))
        } finally {
            settings.edit().putString("appAppearance", appearance)
                .putBoolean("showPracticeGuidance", guidance).putBoolean("showRuleExamples", examples).commit()
        }
    }
}
