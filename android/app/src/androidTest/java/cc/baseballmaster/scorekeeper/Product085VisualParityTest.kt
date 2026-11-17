package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.MatchFactory
import cc.baseballmaster.core.Outcome
import cc.baseballmaster.core.PitchAction
import cc.baseballmaster.core.Rules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** Runs against a disposable simulator install with the same teams and match events as the iOS review. */
@RunWith(AndroidJUnit4::class)
class Product085VisualParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = context.getSharedPreferences("baseballmaster-settings", 0)
    private val fixture by lazy {
        JSONObject(File(context.filesDir, "085-visual-fixture.json").readText())
    }
    private val output by lazy {
        File(context.getExternalFilesDir(null), "085-page-parity").apply { mkdirs() }
    }

    @Before fun prepare() {
        Configurator.getInstance().setWaitForIdleTimeout(350).setWaitForSelectorTimeout(1600)
    }

    @Test fun seedDisposably() = runBlocking {
        val db = LocalDatabase.open(context)
        try {
            check(db.dao().allGames().isEmpty() && db.dao().allTeams().isEmpty()) {
                "Run this fixture only on an empty simulator install"
            }
            val repo = GameRepository(db)
            val names = listOf("林一", "陈二", "周三", "李四", "王五", "吴六", "刘七", "何八", "赵九", "郑十", "沈十一", "方十二")
            val own = repo.createTeam("北岸", names.mapIndexed { index, name -> "北岸$name,${index + 1}" }.joinToString("\n"))
            val opponent = repo.createTeam("南湾", names.mapIndexed { index, name -> "南湾$name,${index + 1}" }.joinToString("\n"), true)
            val played = Instant.parse("2026-11-16T06:00:00Z").toEpochMilli()
            val scheduled = Instant.parse("2026-12-01T06:00:00Z").toEpochMilli()
            fun id() = UUID.randomUUID().toString()
            suspend fun command(game: Game, step: Command) = repo.command(game.id, step, game.revision)
            val rules = Rules(innings = 6, recordingMethod = "traditional")
            var final = repo.createPreparedGame(MatchFactory.create(id(), opponent, own, rules, own.id).apply { playedAt = played })
            for (hit in listOf(Outcome.single, Outcome.double, Outcome.triple, Outcome.homeRun)) {
                final = command(final, Command("play", outcome = hit))
            }
            repeat(4) { final = command(final, Command("pitch", pitch = PitchAction.ball)) }
            final = command(final, Command("hitByPitch"))
            final = command(final, Command("finish", text = "scorerDecision"))
            assertTrue(final.isFinal)
            assertEquals(4, final.awayRuns)
            assertEquals(10, final.batting.values.sumOf { it.totalBases })

            val plan = repo.createPreparedGame(MatchFactory.schedule(id(), own, opponent, rules, scheduled, own.id))
            val live = listOf("traditional", "situation").mapIndexed { index, mode ->
                var game = repo.createPreparedGame(MatchFactory.create(id(), own, opponent, rules.copy(recordingMethod = mode), own.id)
                    .apply { playedAt = played + index * 60_000 })
                game = command(game, Command("play", outcome = Outcome.double))
                game = command(game, Command("pitch", pitch = PitchAction.ball))
                assertEquals(opponent.players.first().id, game.bases[1])
                game
            }
            File(context.filesDir, "085-visual-fixture.json").writeText(JSONObject()
                .put("own", own.id).put("first", own.players.first().id).put("shortstop", own.players[5].id).put("scheduled", plan.id)
                .put("opponentFirst", opponent.players.first().id).put("final", final.id).put("basic", live[0].id).put("pro", live[1].id).toString())
            assertTrue(settings.edit().putString("current_team_id", own.id).commit())
        } finally {
            db.close()
        }
    }

    private fun launch(tab: Int = 0, dark: Boolean = false) {
        assertTrue(settings.edit().putString("appAppearance", if (dark) "dark" else "light").commit())
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-$tab")), 15_000))
        click("tab-$tab")
    }

    private fun node(selector: BySelector) = NativeUi.find(device, selector)

    private fun click(tag: String) = tap(By.res(tag))

    private fun tap(selector: BySelector) {
        node(selector).click()
        device.waitForIdle(400)
        UiTreeCache.refresh()
    }

    private fun match(key: String) = click("game-${fixture.getString(key)}")

    private fun shot(name: String) {
        device.waitForIdle(500)
        instrumentation.waitForIdleSync()
        UiTreeCache.refresh()
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
        val xml = File(output, "$name.xml")
        device.dumpWindowHierarchy(xml)
        assertTrue(xml.length() > 100)
    }

    @Test fun tabs() {
        val pages = listOf("games", "teams", "statistics", "profile")
        for (dark in listOf(false, true)) {
            launch(dark = dark)
            for ((index, page) in pages.withIndex()) {
                click("tab-$index")
                shot("tab-$page" + if (dark) "-dark" else "")
            }
        }
    }

    @Test fun setupAndSchedule() {
        launch(); click("new-game"); node(By.res("setup-own-team")); shot("setup")
        click("setup-scheduled-toggle")
        click("setup-match-date-date"); shot("scheduled-date"); device.pressBack()
        launch(); match("scheduled"); node(By.res("pending-schedule-date")); shot("scheduled-details")
        click("configure-pending-schedule"); shot("scheduled-setup")
        click("start-game"); node(By.res("lineup-row-away-0")); shot("pregame")
    }

    @Test fun roster() {
        launch(1); click("roster-${fixture.getString("own")}")
        node(By.res("roster-player-${fixture.getString("first")}")); shot("team-roster")
        click("add-players-batch"); shot("bulk-roster")
        node(By.res("roster-batch-input")).text = "Lee,,00\nKim,,27"
        click("roster-batch-preview")
        assertTrue(node(By.res("roster-batch-preview-count")).text.contains("2 人"))
        shot("bulk-roster-preview")
    }

    @Test fun statistics() {
        launch(2)
        assertEquals("1 场比赛 · 1胜0负0平", node(By.res("stats-team-record")).text)
        click("stats-open-game-filter"); shot("statistics-filter")
        device.pressBack()
        click("export-team-season-pdf"); node(By.res("report-preview-page")); shot("team-pdf")
        click("report-preview-close")

        launch(1); click("roster-${fixture.getString("own")}")
        click("roster-player-${fixture.getString("first")}")
        node(By.res("stats-player-game-scope")); shot("player-statistics")
        click("stats-player-open-game-filter"); shot("player-filter")
    }

    @Test fun profile() {
        for ((control, name) in listOf("open-app-settings" to "settings", "open-local-data" to "local-data",
            "open-app-about" to "about", "open-rules" to "rules", "open-practice-inning" to "practice-live")) {
            launch(3); click(control); shot(name)
        }
    }

    @Test fun results() {
        launch(); match("final")
        assertNotNull("A completed game opens results directly", device.wait(Until.findObject(By.res("result-final-score")), 5000))
        shot("results-overview")
        click("result-history-correction"); shot("history"); device.pressBack()
        node(By.res("game-result-batting-${fixture.getString("first")}"))
        shot("results-batting")
        for (kind in listOf("pitching", "fielding", "records")) {
            click("choice-数据-$kind")
            if (kind == "pitching") {
                click("choice-球队-home")
                node(By.res("game-result-pitching-${fixture.getString("opponentFirst")}"))
            }
            if (kind == "fielding") {
                click("choice-球队-away")
                node(By.res("game-result-fielding-${fixture.getString("first")}"))
            }
            shot("results-$kind")
        }
        click("result-export-game-pdf"); node(By.res("report-preview-page")); shot("game-pdf")
    }

    @Test fun catcherScreens() {
        launch(); match("pro"); click("record-runner"); click("runner-event-passedBall")
        node(By.res("runner-passed-ball-catcher")); shot("catcher-passed-ball")

        launch(); match("pro")
        repeat(3) { click("pitch-ball") }
        click("record-runner"); click("runner-event-caughtStealing"); click("runner-card-1")
        click("runner-steal-receiver")
        click("runner-steal-receiver-option-${fixture.getString("shortstop")}")
        node(By.res("runner-steal-target")); shot("catcher-caught-stealing")
    }

    @Test fun scoring() {
        for (mode in listOf("basic", "pro")) {
            launch(); match(mode)
            assertFirstScoringScreen()
            shot("live-$mode")
            if (mode == "basic") {
                click("record-play"); node(By.res("arrival-1")); shot("basic-entry")
            } else {
                device.findObject(By.res("open-compact-field"))?.let { field ->
                    field.click()
                    assertTrue(device.wait(Until.hasObject(By.res("compact-field-dialog")), 5000))
                    device.pressBack()
                    assertFirstScoringScreen()
                }
                click("end-half")
                assertTrue(device.wait(Until.hasObject(By.res("confirm-end-half")), 5000))
                device.pressBack()
                click("finish-game")
                click("finish-scorerDecision")
                assertTrue(device.wait(Until.hasObject(By.res("confirm-finish-game")), 5000))
                tap(By.text("取消"))
                device.pressBack()
                assertFirstScoringScreen()
                click("record-runner"); shot("runner-menu")
            }
        }
    }

    private fun assertFirstScoringScreen() {
        assertTrue(device.wait(Until.hasObject(By.res("finish-game")), 8000))
        UiTreeCache.refresh()
        val window = requireNotNull(device.findObject(By.res("portrait-app"))).visibleBounds
        val density = context.resources.displayMetrics.density
        val controls = mapOf(
            "pitch-ball" to 42, "pitch-calledStrike" to 42, "pitch-swingingStrike" to 42, "pitch-foul" to 42,
            "record-play" to 44, "record-runner" to 44, "record-special" to 44,
            "undo" to 38, "redo" to 38, "open-state-correction" to 38, "open-substitutions" to 38,
            "end-half" to 48, "finish-game" to 48,
        )
        for ((tag, minimumHeight) in controls) {
            val button = requireNotNull(device.findObject(By.res(tag))) { "$tag must be visible without scrolling" }
            assertTrue("$tag must fit in the first screen: ${button.visibleBounds}",
                (tag == "redo" || button.isEnabled) && window.contains(button.visibleBounds) &&
                    button.visibleBounds.height() >= minimumHeight * density - 2)
        }
        val half = requireNotNull(device.findObject(By.res("end-half"))).visibleBounds
        val finish = requireNotNull(device.findObject(By.res("finish-game"))).visibleBounds
        assertEquals("The two end actions must share one row", half.centerY(), finish.centerY())
        assertTrue(half.right <= finish.left)
    }
}
