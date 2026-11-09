package cc.baseballmaster.scorekeeper

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkRoster100FlowTest {
    @get:Rule val activity = ActivityTestRule(MainActivity::class.java)

    @Test fun opponentRosterPreviewDoesNotWriteThenOneBatchKeepsOriginalIdentity() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val context = inst.targetContext
        val name = "bulk100-${UUID.randomUUID()}.db"
        val db = LocalDatabase.open(context, name)
        val repo = GameRepository(db)
        try {
            val team = runBlocking { repo.createTeam("合成对手名单", "原有球员,13", isOpponent = true) }
            fun stored() = runBlocking { GameJson.decodeFromString<Team>(db.dao().team(team.id)!!.payload) }
            inst.runOnMainSync { activity.activity.setContent {
                BaseballTheme {
                    val scope = rememberCoroutineScope()
                    var done by remember { mutableStateOf(false) }
                    if (done) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("已写入测试名单") } else BulkRosterScreen(team.name, team.players.map { it.name },
                        team, false, true, dismiss = {}, save = { text, _ ->
                            scope.launch { repo.appendRoster(team, text); done = true }
                        })
                }
            } }
            fun setInput(text: String) {
                NativeUi.find(device, By.res("bulk-roster-input")).apply { click(); this.text = text }
                inst.runOnMainSync {
                    val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
                }
            }
            fun confirm() = NativeUi.find(device, By.res("bulk-roster-confirm")).click()
            setInput("合成甲,Alpha,0/00\n\n原有球员,7")
            confirm()
            NativeUi.find(device, By.textContains("第 3 行"))
            assertEquals(team, stored())
            setInput("合成甲,Alpha,0/00\n\n合成乙,7")
            confirm()
            NativeUi.find(device, By.textContains("第 3 行 · 合成乙"))
            assertEquals(team, stored())
            confirm()
            assertTrue(device.wait(Until.hasObject(By.text("已写入测试名单")), 10_000))
            val actual = stored()
            assertEquals(team.players.single(), actual.players.first())
            assertEquals(3, actual.players.size)
            assertEquals(listOf("0", "00"), actual.players[1].jerseyNumbers)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.appendRoster(team, "旧预览,8") } }
            assertEquals(actual, stored())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
