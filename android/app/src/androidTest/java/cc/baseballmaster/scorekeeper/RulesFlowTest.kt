package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RulesFlowTest {
    @Test fun offlineRulesMatchIOSAndOpenSearchAndPageNavigation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val index = JSONObject(context.resources.openRawResource(R.raw.baseball_rules_index).bufferedReader().use { it.readText() })
        val hash = MessageDigest.getInstance("SHA-256").digest(context.resources.openRawResource(R.raw.baseball_rules_2022).use { it.readBytes() }).joinToString("") { "%02x".format(it) }
        assertEquals(index.getString("pdfSHA256"), hash)
        assertEquals(229, index.getJSONArray("pages").length())
        assertEquals(29, JSONArray(context.resources.openRawResource(R.raw.baseball_rule_glossary).bufferedReader().use { it.readText() }).length())
        val device = UiDevice.getInstance(inst)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-3")), 20_000))
        device.findObject(By.res("tab-3")).click()
        assertTrue(device.wait(Until.hasObject(By.res("open-rules")), 10_000))
        device.findObject(By.res("open-rules")).click()
        assertTrue(device.wait(Until.hasObject(By.res("rule-category-基础")), 10_000))
        assertTrue(device.hasObject(By.text("界内球")))
        device.findObject(By.res("rule-category-进攻")).click()
        assertTrue(device.wait(Until.hasObject(By.text("安打")), 5_000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-rules-glossary.png"))
        device.findObject(By.res("open-rule-pdf")).click()
        assertTrue(device.wait(Until.hasObject(By.res("rule-pdf-reader")), 20_000))
        assertEquals("229 页", device.findObject(By.res("rule-pdf-page-count")).text)
        device.findObject(By.res("rule-pdf-next-page")).click()
        assertTrue(device.wait(Until.hasObject(By.text("2 / 229")), 10_000))
        assertTrue(device.wait(Until.hasObject(By.res("rule-pdf-reader")), 20_000))
        device.findObject(By.res("rule-pdf-search-field")).text = "Balk"
        device.findObject(By.res("rule-pdf-search-button")).click()
        assertTrue(device.wait(Until.hasObject(By.res("rule-pdf-search-status")), 10_000))
        assertTrue(device.findObject(By.res("rule-pdf-search-status")).text.contains(" / "))
        assertTrue(device.wait(Until.hasObject(By.res("rule-pdf-reader")), 20_000))
        device.findObject(By.res("rule-pdf-zoom-in")).click()
        assertTrue(device.wait(Until.hasObject(By.text("150%")), 5_000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "an04-rules-pdf.png"))
    }
}
