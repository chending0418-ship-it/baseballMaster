package cc.baseballmaster.scorekeeper

import android.content.Intent
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

/** Existing 0.8.0 data regressions, exercised through the actual 0.8.1 product paths. */
@RunWith(AndroidJUnit4::class)
class Ios311ParityFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    @org.junit.Before fun configure() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
    }
    private fun refresh() { UiTreeCache.refresh() }
    private fun click(tag: String) { NativeUi.find(device, By.res(tag)).click(); device.waitForIdle(500) }
    private fun confirmLineups() {
        click("start-confirmed-game")
        assertTrue(device.wait(Until.hasObject(By.res("confirm-create-lineups")), 10_000))
        click("confirm-create-lineups")
    }
    private fun input(tag: String, value: String) {
        val field = NativeUi.find(device, By.res(tag)); field.click(); field.text = value
        inst.runOnMainSync {
            val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        device.waitForIdle(500)
    }
    private fun chooseRoster(tag: String, team: Team) {
        click(tag); input("roster-picker-search", team.name); click("choose-roster-${team.id}")
    }
    private fun screenControl(tag: String): UiObject2 {
        refresh()
        val node = device.wait(Until.findObject(By.res(tag)), 10000) ?: error("Missing $tag")
        val b = node.visibleBounds
        assertTrue("$tag must be visible without scrolling: $b", b.height() > 24 && b.bottom <= device.displayHeight - 16)
        return node
    }
    private fun assertResultsBack() {
        val back = screenControl("close-game-results")
        val label = back.contentDescription ?: back.findObject(By.desc("返回"))?.contentDescription
        assertEquals("The result page returns through its labelled back control", "返回", label)
    }
    private fun <T> db(action: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { action(database, GameRepository(database)) } finally { database.close() }
    }
    private fun stored(id: String) = db { d, _ -> GameRepository.decode(d.dao().game(id)!!) }
    private fun await(id: String, predicate: (Game) -> Boolean) {
        repeat(100) { if (predicate(stored(id))) return; Thread.sleep(100) }
        error("Game state did not persist")
    }
    private fun awaitCreatedGame(matching: (Game) -> Boolean): Game {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val created = db { database, _ -> database.dao().allGames().map(GameRepository::decode).filter(matching) }
            if (created.isNotEmpty()) {
                assertEquals("One accepted setup must create exactly one match", 1, created.size)
                return created.single()
            }
            android.os.SystemClock.sleep(100)
        }
        error("The accepted setup did not persist its one expected match")
    }
    private fun launch() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.hasObject(By.pkg(context.packageName)), 20000)
        device.waitForIdle(500)
        device.findObject(By.res("back-home"))?.click()
        click("tab-0")
        repeat(3) { device.findObject(By.scrollable(true))?.scroll(Direction.UP, 1f) }
        device.waitForIdle(500)
    }
    private fun seed(mode: GameMode = GameMode.standard): Game = db { _, repo ->
        val roster = (1..12).joinToString("\n") { "球员$it,$it" }
        val team = repo.createTeam("0.8 对齐 ${UUID.randomUUID().toString().take(5)}", roster)
        repo.createGame(team, "iOS 对照队", roster, mode, false, Rules(mode))
    }
    private fun shot(name: String) {
        device.waitForIdle(500)
        inst.waitForIdleSync()
        val frame = java.util.concurrent.CountDownLatch(1)
        inst.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback { android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() } } }
        assertTrue(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "081-regression-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "081-regression-$name.xml"))
    }
    @Test fun mainScoringControlsAndSpecialActionsMatchIosHierarchy() {
        val game = seed()
        launch(); click("game-${game.id}")
        listOf("pitch-ball", "pitch-calledStrike", "pitch-swingingStrike", "pitch-foul", "record-play", "record-runner", "record-special", "undo", "redo", "open-state-correction", "open-substitutions", "end-half", "finish-game").forEach { screenControl(it) }
        shot("scoring-main")
        click("pitch-ball")
        screenControl("confirm-opening-pitch").click()
        await(game.id) { it.balls == 1 && it.openingPitchRecorded }
        click("toggle-game-clock"); await(game.id) { it.clockRunningSince == null }
        click("toggle-game-clock"); await(game.id) { it.clockRunningSince != null }
        click("undo"); await(game.id) { it.balls == 0 }
        click("redo"); await(game.id) { it.balls == 1 }
        click("record-special"); shot("special")
        click("special-hitByPitch"); await(game.id) { it.bases[0] != null && it.balls == 0 }
        click("record-runner"); shot("runner"); device.pressBack()
        click("open-state-correction"); shot("correction"); device.pressBack()
        click("open-substitutions"); shot("substitutions"); device.pressBack()
        click("end-half"); NativeUi.find(device, By.text("取消")).click()
        assertTrue(device.wait(Until.gone(By.res("confirm-end-half")), 10000))
        assertTrue(stored(game.id).isTop)
        click("end-half"); click("confirm-end-half"); await(game.id) { !it.isTop }
        click("finish-game"); NativeUi.find(device, By.text("记录员结束记录")).click(); click("confirm-finish-game")
        await(game.id) { it.isFinal }
        screenControl("return-final-game-home").click(); screenControl("tab-1")
        shot("home")
    }
    @Test fun slowPitchAndCoachModesExposeTheirOwnActions() {
        for (mode in listOf(GameMode.coachPitch, GameMode.slowPitch)) {
            val game = seed(mode); launch(); click("game-${game.id}")
            screenControl("record-special")
            if (mode == GameMode.slowPitch) screenControl("record-illegal-pitch")
            shot("scoring-${mode.name}")
            click("record-special")
            if (mode == GameMode.coachPitch) assertFalse(device.hasObject(By.res("special-intentionalWalk")))
            shot("special-${mode.name}"); device.pressBack(); click("back-home")
        }
    }
    @Test fun deleteIsConfirmedAndAffectsOnlySelectedLocalRecord() {
        val game = seed(); val other = seed()
        launch(); click("game-menu-${game.id}")
        NativeUi.find(device, By.text("删除比赛")).click(); device.pressBack()
        assertNotNull(stored(game.id))
        click("game-menu-${game.id}"); NativeUi.find(device, By.text("删除比赛")).click(); click("confirm-delete-game")
        repeat(50) { if (db { d, _ -> d.dao().game(game.id) } == null) return@repeat; Thread.sleep(100) }
        assertNull(db { d, _ -> d.dao().game(game.id) }); assertNotNull(stored(other.id))
    }
    @Test fun mainTabsAndBatchRosterRemainAvailable() {
        launch(); shot("home-tabs")
        click("home-rules-help"); shot("rules"); device.pressBack()
        click("tab-1"); screenControl("add-team"); shot("teams"); click("add-team"); shot("batch-roster"); device.pressBack()
        click("tab-2"); screenControl("stats-team-picker"); shot("statistics")
        click("tab-3"); screenControl("open-app-settings"); shot("profile")
        click("open-app-settings"); shot("settings"); device.pressBack()
        click("open-local-data"); shot("data"); device.pressBack()
        click("open-app-about"); shot("about"); device.pressBack()
    }
    @Test fun gameResultsShowInningsAndPlayerStatisticsInsideApp() {
        val game = seed()
        db { _, repo -> repo.command(game.id, Command("play", outcome = Outcome.homeRun), game.revision) }
        launch(); click("game-${game.id}"); click("game-results")
        screenControl("game-linescore")
        assertTrue(device.hasObject(By.textContains("1 : 0")))
        shot("game-results")
        click("choice-数据-pitching"); shot("game-results-pitching")
        click("choice-数据-fielding"); shot("game-results-fielding")
        click("choice-数据-records"); assertTrue(device.wait(Until.hasObject(By.textContains("中文比赛记录")), 10000)); shot("game-results-records")
        click("result-history-correction")
        assertTrue(device.wait(Until.hasObject(By.text("完整记录")), 10000))
        device.pressBack()
        assertResultsBack()
        shot("game-results-returned-after-history")
        click("edit-game-date")
        screenControl("save-match-time")
        NativeUi.find(device, By.text("取消")).click()
        assertResultsBack()
        device.pressBack(); click("back-home")
    }
    @Test fun largeTextKeepsScoringAndNavigationReachable() {
        val game = seed()
        val previous = device.executeShellCommand("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        try {
            device.executeShellCommand("settings put system font_scale 1.5")
            launch(); click("game-${game.id}")
            click("record-special"); screenControl("special-hitByPitch"); device.pressBack()
            NativeUi.find(device, By.res("finish-game"))
            shot("large-font-scoring")
        } finally { device.executeShellCommand("settings put system font_scale $previous") }
    }

    @Test fun supplementBallObservationKeepsResultAndRunnerState() {
        val game = db { _, repo ->
            val roster = (1..12).joinToString("\n") { "球员$it,$it" }
            val team = repo.createTeam("0.8 Pro补充 ${UUID.randomUUID().toString().take(5)}", roster)
            val initial = repo.createGame(team, "观察对照", roster, GameMode.standard, false, Rules(recordingMethod = "situation"))
            repo.command(initial.id, Command("play", outcome = Outcome.homeRun), initial.revision)
        }
        launch(); click("game-${game.id}"); click("amend-situation-play")
        click("choice-球型-fly"); shot("supplement-observation"); click("save-situation-amendment")
        await(game.id) { it.journal.take(it.cursor).last().command.observation?.type == "fly" }
        val updated = stored(game.id)
        assertEquals(game.awayRuns, updated.awayRuns); assertEquals(game.homeRuns, updated.homeRuns)
        assertEquals(game.bases, updated.bases); assertEquals(game.outs, updated.outs)
        assertEquals(game.batting, updated.batting)
    }

    @Test fun observationSetupManagesOpponentsWithoutLosingDraftAndExcludesTeamRecord() {
        val roster = (1..12).joinToString("\n") { "球员$it,$it" }
        val (away, home) = db { _, repo -> repo.createTeam("观赛客 ${UUID.randomUUID().toString().take(5)}", roster, true) to repo.createTeam("观赛主 ${UUID.randomUUID().toString().take(5)}", roster, true) }
        launch()
        val priorRecord = NativeUi.find(device, By.res("home-team-record")).text
        click("new-game"); input("opponent-name", "草稿必须保留")
        click("setup-manage-opponents"); click("add-team")
        val bulkName = "批量对手 ${UUID.randomUUID().toString().take(5)}"
        input("team-name", bulkName); input("team-roster", roster); click("save-team")
        click("close-setup-opponents")
        assertEquals("草稿必须保留", NativeUi.find(device, By.res("opponent-name")).text)
        assertTrue(db { d, _ -> d.dao().allTeams().any { it.name == bulkName && it.isOpponent } })
        click("choice-记录类型-spectator")
        chooseRoster("setup-spectator-away", away)
        chooseRoster("setup-spectator-home", away)
        assertFalse(screenControl("start-game").isEnabled)
        chooseRoster("setup-spectator-home", home)
        click("start-game"); shot("observation-lineups"); confirmLineups()
        val game = awaitCreatedGame { it.away.id == away.id && it.home.id == home.id }
        assertNull(game.ourTeamID)
        click("pitch-ball"); click("confirm-opening-pitch"); await(game.id) { it.balls == 1 }
        click("finish-game"); click("finish-scorerDecision"); click("confirm-finish-game"); await(game.id) { it.isFinal }
        click("return-final-game-home")
        assertEquals(priorRecord, NativeUi.find(device, By.res("home-team-record")).text)
    }
    @Test fun backfilledMatchKeepsActualDateAndAppearsOnlyInItsYear() {
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = settings.getString("current_team_id", null)
        try {
            val roster = (1..12).joinToString("\n") { "球员$it,$it" }
            val (own, opponent) = db { _, repo -> repo.createTeam("补录本队 ${UUID.randomUUID().toString().take(5)}", roster) to repo.createTeam("补录对手 ${UUID.randomUUID().toString().take(5)}", roster, true) }
            launch(); click("new-game")
            chooseRoster("setup-own-team", own)
            chooseRoster("setup-opponent", opponent)
            click("backfill-game-toggle"); input("setup-match-date", "2025-06-10 10:00")
            click("start-game"); shot("backfilled-lineups"); confirmLineups()
            val game = awaitCreatedGame { it.ourTeamID == own.id }
            click("pitch-ball"); click("confirm-opening-pitch"); await(game.id) { it.balls == 1 }
            assertEquals("2025-06-10 10:00", formatMatchDate(stored(game.id).playedAt!!, stored(game.id).playedTimeZoneID))
            click("finish-game"); click("finish-scorerDecision"); click("confirm-finish-game"); await(game.id) { it.isFinal }
            click("return-final-game-home"); click("tab-2")
            click("stats-season-picker"); click("stats-season-picker-option-2025")
            assertEquals("1 场比赛 · 0胜0负1平", NativeUi.find(device, By.res("stats-team-record")).text)
            shot("backfilled-year-statistics")
        } finally { settings.edit().apply { if(previous == null) remove("current_team_id") else putString("current_team_id", previous) }.commit() }
    }

    @Test fun scheduledMatchWaitsForExplicitEntryAndLineupConfirmation() {
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = settings.getString("current_team_id", null)
        try {
            val roster = (1..12).joinToString("\n") { "球员$it,$it" }
            val (own, opponent) = db { _, repo -> repo.createTeam("预告本队 ${UUID.randomUUID().toString().take(5)}", roster) to repo.createTeam("预告对手 ${UUID.randomUUID().toString().take(5)}", roster, true) }
            launch(); click("new-game")
            chooseRoster("setup-own-team", own); chooseRoster("setup-opponent", opponent)
            click("setup-scheduled-toggle"); click("configure-schedule-now"); click("start-game"); confirmLineups()
            val game = awaitCreatedGame { it.ourTeamID == own.id }
            assertTrue(game.isScheduled); assertFalse(game.openingPitchRecorded)
            assertFalse(device.hasObject(By.res("pitch-ball")))
            click("activate-scheduled"); await(game.id) { !it.isScheduled }
            click("pitch-ball"); screenControl("confirm-opening-pitch").click()
            await(game.id) { it.balls == 1 && it.openingPitchRecorded }
            shot("scheduled-match-entered")
            click("finish-game"); click("finish-scorerDecision"); click("confirm-finish-game"); await(game.id) { it.isFinal }
            click("return-final-game-home")
        } finally { settings.edit().apply { if(previous == null) remove("current_team_id") else putString("current_team_id", previous) }.commit() }
    }

    @Test fun basicOwnedScheduleWaitsForRealRosterConfiguration() = verifyBasicSchedule(false)
    @Test fun basicObservationScheduleWaitsForRealRosterConfiguration() = verifyBasicSchedule(true)
    private fun verifyBasicSchedule(observation: Boolean) {
        val settings = context.getSharedPreferences("baseballmaster-settings", 0)
        val previous = settings.getString("current_team_id", null)
        try {
            val suffix = UUID.randomUUID().toString().take(5)
            val (away, home) = db { _, repo ->
                repo.createRoster("!0.8 预告留空客 $suffix", "客队", "", observation) to repo.createRoster("!0.8 预告留空主 $suffix", "主队", "", true)
            }
            launch(); click("new-game"); click("choice-记分方式-situation")
            if(observation) {
                click("choice-记录类型-spectator"); chooseRoster("setup-spectator-away", away); chooseRoster("setup-spectator-home", home)
            } else { chooseRoster("setup-own-team", away); chooseRoster("setup-opponent", home) }
            click("setup-scheduled-toggle"); click("start-game")
            var plan = awaitCreatedGame { it.away.id == away.id && it.home.id == home.id }
            assertTrue(plan.scheduledSetupPending); assertTrue(plan.isScheduled); assertTrue(plan.away.players.isEmpty())
            assertEquals(5, db { d, _ -> d.dao().game(plan.id)!!.payloadVersion })
            assertFalse(device.hasObject(By.res("pitch-ball"))); assertFalse(device.hasObject(By.res("start-confirmed-game")))
            shot("basic-schedule-${if(observation) "observation" else "owned"}")
            click("pending-edit-time"); assertFalse(device.hasObject(By.res("choice-时间用途-played")))
            input("match-time", "2030-06-01 11:30"); input("match-zone", "Asia/Shanghai")
            if(!observation) {
                val resumed = java.util.concurrent.CountDownLatch(1)
                var previousActivity: android.app.Activity? = null
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                    if(activity !== previousActivity && stage == androidx.test.runner.lifecycle.Stage.RESUMED) resumed.countDown()
                }
                try {
                    inst.runOnMainSync {
                        previousActivity = monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
                        monitor.addLifecycleCallback(callback)
                        previousActivity!!.recreate()
                    }
                    assertTrue("Activity did not recreate", resumed.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals("2030-06-01 11:30", NativeUi.find(device, By.res("match-time")).text)
                } finally { inst.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
            }
            click("save-match-time")
            await(plan.id) { it.scheduledAt == parseMatchDate("2030-06-01 11:30", "Asia/Shanghai") && it.scheduledSetupPending }
            plan = stored(plan.id)
            click("configure-pending-schedule"); click("start-game")
            NativeUi.find(device, By.textContains("双方至少需要"))
            assertEquals(plan, stored(plan.id)); device.pressBack()
            click("pending-manage-rosters"); click("roster-${away.id}"); screenControl("add-player"); device.pressBack()
            val roster = (1..12).joinToString("\n") { "真实名单$it,$it" }
            // Use the same observable repository path as the roster editor. A second
            // Room instance is only a fixture reader, not a cross-process change feed.
            for(team in listOf(away, home)) {
                val saved = java.util.concurrent.CountDownLatch(1)
                val model = java.util.concurrent.atomic.AtomicReference<ScorekeeperViewModel>()
                inst.runOnMainSync {
                    val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
                    val currentModel = androidx.lifecycle.ViewModelProvider(activity as MainActivity)[ScorekeeperViewModel::class.java]
                    model.set(currentModel)
                    currentModel.updateRoster(team.copy(players = Roster.parse(roster))) { saved.countDown() }
                }
                assertTrue("Roster was not saved", saved.await(15, java.util.concurrent.TimeUnit.SECONDS))
                var ready = false
                repeat(100) {
                    inst.runOnMainSync { ready = model.get().rosters.firstOrNull { it.id == team.id }?.players?.size == 12 }
                    if(!ready) Thread.sleep(100)
                }
                assertTrue("Roster changes did not reach setup", ready)
            }
            click("close-scheduled-rosters")
            click("configure-pending-schedule")
            val selectedMethod = screenControl("choice-记分方式-situation")
            assertTrue("The scheduled match must keep its Pro recording method", selectedMethod.isChecked || selectedMethod.isSelected)
            click("start-game"); screenControl("start-confirmed-game")
            assertEquals(plan, stored(plan.id))
            confirmLineups(); await(plan.id) { !it.scheduledSetupPending && !it.isScheduled }
            val configured = stored(plan.id)
            assertEquals(plan.id, configured.id); assertEquals(plan.scheduledAt, configured.scheduledAt)
            assertEquals(12, configured.away.players.size); assertEquals(if(observation) null else away.id, configured.ourTeamID)
            assertFalse(configured.openingPitchRecorded); assertEquals(0, configured.totalPitches)
            assertEquals(4, db { d, _ -> d.dao().game(plan.id)!!.payloadVersion })
            click("pitch-ball"); click("confirm-opening-pitch"); await(plan.id) { it.openingPitchRecorded && it.balls == 1 }
            click("finish-game"); click("finish-scorerDecision"); click("confirm-finish-game"); await(plan.id) { it.isFinal }; click("return-final-game-home")
        } finally { settings.edit().apply { if(previous == null) remove("current_team_id") else putString("current_team_id", previous) }.commit() }
    }

}
