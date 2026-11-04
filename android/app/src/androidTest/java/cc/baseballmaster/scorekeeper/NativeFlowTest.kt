package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Accessibility-driven real input; avoids Espresso's removed API-37 InputManager reflection. */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class NativeFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val roster =
        listOf("陈一,00", "李明,2", "王宇,3", "张晨,4", "林峰,5", "周阳,6", "吴凡,7", "刘洋,8", "赵宁,9", "何川,10")
            .joinToString("\n")

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName)), 20_000)
    }

    private fun node(tag: String): UiObject2 {
        return device.wait(Until.findObject(By.res(tag)), 15_000) ?: error("Missing control: $tag")
    }

    private fun enter(tag: String, text: String) {
        val field = node(tag)
        field.click()
        field.text = text
        device.waitForIdle()
        instrumentation.runOnMainSync {
            val manager =
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { root ->
                manager.hideSoftInputFromWindow(root.windowToken, 0)
            }
        }
        device.waitForIdle()
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")))
    }

    private fun fingerprint() =
        MessageDigest.getInstance("SHA-256").digest(CredentialVault(context).secret()).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    @Test
    fun createTeamStartGameAndRecordFirstPitch() {
        launch()
        node("create-team")
        screenshot("01-home-empty")
        node("create-team").click()
        enter("team-name", "春雷棒球队")
        enter("team-roster", roster)
        screenshot("02-create-team")
        node("save-team").click()
        assertTrue(device.wait(Until.gone(By.res("save-team")), 15_000))
        assertNotNull(device.wait(Until.findObject(By.text("春雷棒球队")), 15_000))
        screenshot("03-home-team")
        node("new-game").click()
        enter("opponent-name", "海风棒球队")
        screenshot("04-new-game-top")
        repeat(4) {
            if (device.findObject(By.res("opponent-roster")) == null)
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 7 / 10,
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 10,
                    22,
                )
        }
        enter("opponent-roster", roster.replace("陈一", "陆一"))
        node("start-game").click()
        node("pitch-ball").click()
        assertTrue(device.wait(Until.hasObject(By.text("已保存 1 球")), 15_000))
        assertEquals("1", node("count-坏球").text)
        screenshot("05-first-pitch")
        File(context.filesDir, "test-credential-fingerprint.txt").writeText(fingerprint())
        node("back-home").click()
        node("continue-game")
        screenshot("06-home-resumable")
    }

    @Test
    fun restoredGameIsAvailableAfterExternalProcessKillOrUpgrade() {
        launch()
        node("continue-game").click()
        assertEquals("1", node("count-坏球").text)
        assertNotNull(device.findObject(By.text("已保存 1 球")))
        assertEquals(
            File(context.filesDir, "test-credential-fingerprint.txt").readText(),
            fingerprint(),
        )
        runBlocking {
            val db = LocalDatabase.open(context)
            try {
                val rows = db.dao().games().first()
                val games = rows.map { row ->
                    JSONObject()
                        .put("id", row.id)
                        .put(
                            "sha256",
                            MessageDigest.getInstance("SHA-256")
                                .digest(row.payload.toByteArray())
                                .joinToString("") { "%02x".format(it) },
                        )
                        .put("revision", row.revision)
                }
                val receipt =
                    JSONObject()
                        .put("schema", db.openHelper.readableDatabase.version)
                        .put("games", JSONArray(games))
                File(context.getExternalFilesDir(null), "storage-receipt.json")
                    .writeText(receipt.toString(2))
            } finally {
                db.close()
            }
        }
        screenshot("07-restored-pitch")
        node("back-home").click()
        node("tab-1").click()
        assertNotNull(device.wait(Until.findObject(By.text("陈一")), 10_000))
        screenshot("08-team-roster")
        node("tab-0").click()
    }
}
