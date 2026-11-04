package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Product081SetupDraftFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    @org.junit.Rule @JvmField
    val captureFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(error: Throwable, description: org.junit.runner.Description) {
            shot("failure")
        }
    }

    private fun <T> db(block: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(database, GameRepository(database)) } finally { database.close() }
    }
    private fun node(tag: String): UiObject2 = NativeUi.find(device, By.res(tag))
    private fun click(tag: String) { node(tag).click(); device.waitForIdle(500); UiTreeCache.refresh() }
    private fun input(tag: String, value: String) {
        node(tag).apply { click(); text = value }
        inst.runOnMainSync {
            val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        device.waitForIdle(500); UiTreeCache.refresh()
    }
    private fun chooseRoster(tag: String, team: Team) {
        click(tag); input("roster-picker-search", team.name); click("choose-roster-${team.id}")
    }
    private fun expectTeam(tag: String, team: Team) {
        val row = node(tag)
        val visibleText = row.text.orEmpty() + row.findObjects(By.textContains(team.name)).joinToString { it.text.orEmpty() }
        assertTrue("The setup row must retain the chosen team ${team.name}", visibleText.contains(team.name))
    }
    private fun shot(name: String) {
        device.waitForIdle(500)
        val directory = requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(directory, "081-setup-draft-$name.png")))
        device.dumpWindowHierarchy(File(directory, "081-setup-draft-$name.xml"))
    }

    @Test fun fullRuleDraftSurvivesHiddenInvalidFieldsAndReturningFromLineupBeforeOneCommit() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val hadCurrentTeam = settings.contains("current_team_id")
        val previousCurrentTeam = settings.getString("current_team_id", null)
        try {
            val suffix = UUID.randomUUID().toString().take(5)
            val roster = "陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
            val (own, opponent) = db { _, repo ->
                repo.createTeam("规则草稿海浪$suffix", roster) to repo.createTeam("规则草稿飞鹰$suffix", roster, true)
            }
            val before = db { database, _ ->
                Triple(database.dao().allGames().associate { it.id to it.payload },
                    database.dao().allTeams().associate { it.id to it.payload }, database.dao().allOperations().associateBy { it.id })
            }
            fun noWrites(stage: String) = db { database, _ ->
                assertEquals("$stage must not create or alter a match", before.first, database.dao().allGames().associate { it.id to it.payload })
                assertEquals("$stage must not modify either roster", before.second, database.dao().allTeams().associate { it.id to it.payload })
                assertEquals("$stage must not create any scoring operation", before.third, database.dao().allOperations().associateBy { it.id })
            }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 30_000))
            click("tab-0"); click("new-game")
            chooseRoster("setup-own-team", own); chooseRoster("setup-opponent", opponent)
            input("rule-innings", "7"); click("rule-advanced-toggle")
            input("rule-run-limit", "0")
            val invalidMessage = "半局得分上限 请填写 1–99"
            assertEquals(invalidMessage, node("rule-validation-error").text)
            assertFalse("An invalid draft must disable the next step", node("start-game").isEnabled)
            noWrites("Invalid rule editing")

            // Basic scheduling removes the rule form from composition. Restoring the
            // full setup must retain the invalid text and its error, rather than the
            // last valid Rules/defaults. Do not press the schedule save action.
            click("setup-scheduled-toggle")
            assertTrue(device.wait(Until.gone(By.res("rule-run-limit")), 5_000))
            noWrites("Temporarily hiding the invalid rule form")
            click("setup-scheduled-toggle")
            assertEquals("0", node("rule-run-limit").text)
            assertEquals("7", node("rule-innings").text)
            assertEquals(invalidMessage, node("rule-validation-error").text)
            assertFalse(node("start-game").isEnabled)
            shot("invalid-rule-restored")

            input("rule-run-limit", "5"); input("rule-pitch-limit", "120")
            assertTrue("Correcting the same draft enables review", node("start-game").isEnabled)
            click("start-game"); node("lineup-row-away-0")
            noWrites("First lineup review")
            shot("first-review")
            device.pressBack(); device.waitForIdle(500); UiTreeCache.refresh()
            node("setup-own-team")
            expectTeam("setup-own-team", own); expectTeam("setup-opponent", opponent)
            assertEquals("7", node("rule-innings").text)
            assertEquals("5", node("rule-run-limit").text)
            assertEquals("120", node("rule-pitch-limit").text)
            val advancedToggle = node("rule-advanced-toggle")
            assertEquals("收起更多规则", advancedToggle.text ?: advancedToggle.findObject(By.text("收起更多规则"))?.text)
            noWrites("Returning to the original setup task")
            shot("review-back-retained")

            click("start-game"); node("lineup-row-away-0")
            noWrites("Second lineup review")
            click("start-confirmed-game"); node("confirm-create-lineups")
            noWrites("Final confirmation before acceptance")
            click("confirm-create-lineups")
            val deadline = SystemClock.uptimeMillis() + 15_000
            var saved: Game? = null
            while (SystemClock.uptimeMillis() < deadline) {
                saved = db { database, _ -> database.dao().allGames().map(GameRepository::decode)
                    .singleOrNull { it.ourTeamID == own.id && it.home.id == opponent.id } }
                if (saved != null) break
                SystemClock.sleep(100)
            }
            val game = requireNotNull(saved) { "The accepted setup must create exactly one match" }
            assertEquals(own.id, game.away.id); assertEquals(opponent.id, game.home.id)
            assertEquals(7, game.rules.innings); assertEquals(5, game.rules.halfRunLimit); assertEquals(120, game.rules.pitchLimit)
            assertEquals(0, game.totalPitches); assertTrue(game.isPregame)
            assertTrue(game.entries.isEmpty()); assertTrue(game.journal.isEmpty()); assertNull(game.checkpoint)
            assertFalse(game.incompletePrefix); assertFalse(game.statisticsIncomplete)
            db { database, _ ->
                assertEquals("Review/back/next must result in one new match only", before.first.size + 1, database.dao().allGames().size)
                before.first.forEach { (id, original) -> assertEquals(original, requireNotNull(database.dao().game(id)).payload) }
                assertEquals(before.second, database.dao().allTeams().associate { it.id to it.payload })
                assertEquals("Setup is not a scoring operation", before.third, database.dao().allOperations().associateBy { it.id })
            }
            click("pitch-ball"); click("confirm-opening-pitch")
            var first = game
            val pitchDeadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < pitchDeadline) {
                first = db { database, _ -> GameRepository.decode(requireNotNull(database.dao().game(game.id))) }
                if (first.totalPitches == 1) break
                SystemClock.sleep(100)
            }
            assertEquals(1, first.totalPitches); assertEquals(1, first.entries.size); assertEquals("pitch", first.entries.single().kind)
            assertEquals(1, first.journal.size); assertTrue(first.openingPitchRecorded)
            assertEquals(5, first.rules.halfRunLimit); assertEquals(7, first.rules.innings); assertEquals(120, first.rules.pitchLimit)
            assertFalse(first.incompletePrefix); assertFalse(first.statisticsIncomplete)
            assertTrue(first.ruleNotices(System.currentTimeMillis()).isEmpty())
            UiTreeCache.refresh()
            assertFalse("A clean first pitch must not show a false history/rule warning", device.hasObject(By.res("game-rule-warning")))
            shot("created-and-first-pitch")
        } finally {
            assertTrue(settings.edit().apply {
                if (hadCurrentTeam) putString("current_team_id", previousCurrentTeam) else remove("current_team_id")
            }.commit())
        }
    }
}
