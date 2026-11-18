package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.GameJson
import cc.baseballmaster.core.Team
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Product085RosterBatchFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    @Test fun anExistingRosterRejectsIdenticalRowsAndRequiresNamesakeConfirmation() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(2000)
        val oldName = "北岸名单${UUID.randomUUID().toString().take(4)}"
        val team = db { GameRepository(it).createTeam(oldName, "王一,Lee,00\n陈二,23") }
        val before = readTeam(team.id)
        launch()
        click("roster-${team.id}")
        click("add-players-batch")
        input("王一,Lee,00\n沈三,36")
        click("roster-batch-preview")
        node("roster-batch-issue-1")
        assertEquals(before, readTeam(team.id))

        input("王一,Will,00\n沈三,36")
        click("roster-batch-preview")
        node("roster-batch-warning-1")
        assertFalse(node("roster-batch-submit").isEnabled)
        assertEquals(before, readTeam(team.id))
        shot("085-roster-batch-warning")
        click("roster-batch-distinct")
        assertTrue(node("roster-batch-submit").isEnabled)
        click("roster-batch-submit")

        val deadline = SystemClock.uptimeMillis() + 10_000
        var after = readTeam(team.id)
        while (after == before && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(120)
            after = readTeam(team.id)
        }
        assertEquals(before.players, after.players.take(before.players.size))
        assertEquals(listOf("王一", "沈三"), after.players.drop(before.players.size).map { it.name })
        assertEquals(listOf("00", "36"), after.players.drop(before.players.size).map { it.number })
        assertEquals(4, after.players.map { it.id }.toSet().size)
        node("roster-player-${after.players.last().id}")
        shot("085-roster-batch-appended")
    }

    private fun launch() {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-1")), 15_000))
        click("tab-1")
    }

    private fun input(value: String) {
        node("roster-batch-input").text = value
        inst.runOnMainSync {
            val manager = context.getSystemService(InputMethodManager::class.java)
            WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        device.waitForIdle(500)
    }

    private fun node(tag: String) = NativeUi.find(device, By.res(tag))

    private fun click(tag: String) {
        node(tag).click()
        device.waitForIdle(500)
    }

    private fun shot(name: String) {
        val dir = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
        device.dumpWindowHierarchy(File(dir, "$name.xml"))
    }

    private fun readTeam(id: String): Team = db {
        GameJson.decodeFromString(requireNotNull(it.dao().team(id)).payload)
    }

    private fun <T> db(block: suspend (LocalDatabase) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(database) } finally { database.close() }
    }
}
