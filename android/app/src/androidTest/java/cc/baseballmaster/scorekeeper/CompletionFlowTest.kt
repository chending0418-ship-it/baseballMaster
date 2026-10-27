package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val output
        get() = context.getExternalFilesDir(null)!!

    @org.junit.Rule
    @JvmField
    val capture =
        object : org.junit.rules.TestWatcher() {
            override fun failed(e: Throwable, d: org.junit.runner.Description) {
                device.takeScreenshot(File(output, "completion-failure.png"))
                device.dumpWindowHierarchy(File(output, "completion-failure.xml"))
            }
        }

    private fun launch() {
        context.startActivity(
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 20000))
        device.waitForIdle()
    }

    private fun find(s: BySelector): UiObject2 {
        repeat(120) { i ->
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache()
                device
                    .findObject(s)
                    ?.takeIf { it.visibleBounds.height() > 10 }
                    ?.let {
                        return it
                    }
                device
                    .findObject(By.scrollable(true))
                    ?.scroll(if (i < 3 || i >= 63) Direction.UP else Direction.DOWN, .75f)
            } catch (_: StaleObjectException) {}
            device.waitForIdle()
        }
        return device.wait(Until.findObject(s), 5000) ?: error("Missing $s")
    }

    private fun click(tag: String) {
        find(By.res(tag)).click()
        device.waitForIdle()
    }

    private fun text(value: String) = find(By.text(value))

    private fun input(tag: String, value: String) {
        val f = find(By.res(tag))
        f.click()
        f.text = value
        inst.runOnMainSync {
            val manager =
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                manager.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        device.waitForIdle()
    }

    private fun player(label: String, id: String) {
        click("player-choice-$label")
        click("player-option-$id")
    }

    private fun shot(name: String) {
        device.waitForIdle()
        val latch = java.util.concurrent.CountDownLatch(1)
        inst.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() }
            }
        }
        assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(device.takeScreenshot(File(output, "completion-$name.png")))
    }

    private fun <T> db(block: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val d = LocalDatabase.open(context)
        try {
            block(d, GameRepository(d))
        } finally {
            d.close()
        }
    }

    private fun stored(id: String) = db { d, _ -> GameRepository.decode(d.dao().game(id)!!) }

    private fun await(id: String, p: (Game) -> Boolean): Game {
        repeat(120) {
            val g = stored(id)
            if (p(g)) return g
            Thread.sleep(100)
        }
        error("Saved game state not reached")
    }

    private fun roster() =
        (1..12).joinToString("\n") { "球员$it,Player $it,${if(it==1)"00"else it.toString()}" }

    private fun seed(rules: Rules = Rules(), prepare: (Game) -> Game = { it }): Game = db { d, r ->
        val own = r.createTeam("AN03 完成 ${UUID.randomUUID().toString().take(5)}", roster())
        var g = r.createGame(own, "海风 完成", roster(), rules.mode, false, rules)
        val candidate = prepare(g)
        if (candidate != g)
            d.dao()
                .updateGame(
                    GameEntity(
                        candidate.id,
                        GameJson.encodeToString(candidate),
                        candidate.revision,
                        System.currentTimeMillis(),
                        4,
                    )
                )
        candidate
    }

    @Test
    fun rosterProfileAndThreeModeSetupUseRealScreens() {
        val own = db { _, r ->
            r.createTeam("AN03 名单 ${UUID.randomUUID().toString().take(5)}", roster())
        }
        val opponent = db { _, r ->
            r.createTeam("AN03 对手 ${UUID.randomUUID().toString().take(5)}", roster()).let {
                it.copy(isOpponent = true)
            }
        }
        db { d, _ ->
            d.dao()
                .updateTeam(
                    TeamEntity(opponent.id, opponent.name, GameJson.encodeToString(opponent), true)
                )
        }
        launch()
        click("tab-1")
        click("roster-${own.id}")
        click("edit-player-${own.players[0].id}")
        input("player-chinese", "陈明")
        input("player-english", "Alex Chen")
        input("player-numbers", "0 / 00")
        shot("01-player-profile")
        click("save-player-profile")
        repeat(100) {
            if (
                db { d, _ -> GameJson.decodeFromString<Team>(d.dao().team(own.id)!!.payload) }
                    .players[0]
                    .name != "陈明"
            )
                Thread.sleep(100)
        }
        val changed = db { d, _ -> GameJson.decodeFromString<Team>(d.dao().team(own.id)!!.payload) }
        assertEquals(own.players[0].id, changed.players[0].id)
        assertEquals(listOf("0", "00"), changed.players[0].jerseyNumbers)
        device.pressBack()
        device.waitForIdle()
        click("tab-0")
        for (mode in GameMode.entries) {
            val before = db { d, _ -> d.dao().allGames().map { it.id }.toSet() }
            click("new-game")
            click("setup-own-team")
            click("choose-roster-${own.id}")
            click("setup-opponent")
            click("choose-roster-${opponent.id}")
            click("choice-模式-${mode.name}")
            click("start-game")
            assertNotNull(text("确认双方阵容"))
            shot("02-setup-${mode.name}")
            click("start-confirmed-game")
            var added: Game? = null
            repeat(100) {
                val next = db { d, _ ->
                    d.dao().allGames().firstOrNull { it.id !in before }?.let(GameRepository::decode)
                }
                if (next != null) added = next else Thread.sleep(100)
            }
            val g = added!!
            assertEquals(mode, g.rules.mode)
            assertEquals(opponent.id, g.home.id)
            assertEquals(own.id, g.away.id)
            assertEquals(0, g.totalPitches)
            assertEquals(if (mode == GameMode.slowPitch) 10 else 9, g.awayOrder!!.size)
            assertEquals(if (mode == GameMode.slowPitch) 1 else 0, g.balls)
            shot("03-game-${mode.name}")
            click("back-home")
        }
    }

    @Test
    fun manualReviewTiebreakAndDoubleSwitchStayAtomic() {
        val g =
            seed(Rules(innings = 1)) { initial ->
                var v = MatchEngine.apply(initial, Command("play", outcome = Outcome.triple))
                MatchEngine.apply(v, Command("play", outcome = Outcome.pending))
            }
        launch()
        click("game-${g.id}")
        click("scoring-more")
        click("advanced-tools")
        click("review-events")
        text("复核记录").click()
        click("choice-正式记分结果-single")
        input("review-title", "球员2：一垒安打")
        input("review-note", "已核对现场")
        shot("04-review")
        click("save-review")
        val reviewed = await(g.id) { it.awayHits == 2 }
        assertEquals(1, reviewed.pitching[reviewed.pitcher.id]!!.earnedRuns)
        click("manual-situation")
        input("situation-0", "2")
        input("situation-1", "2")
        input("situation-2", "1")
        input("situation-3", "1")
        input("situation-4", "3")
        input("situation-5", "1")
        shot("05-situation")
        click("save-situation")
        val fixed = await(g.id) { it.inning == 2 }
        assertTrue(fixed.statisticsIncomplete)
        assertEquals(2, fixed.totalPitches)
        assertEquals(2, fixed.batting.values.sumOf { it.plateAppearances })
        click("enable-tb")
        await(g.id) { it.tiebreakStartInning == 2 }
        player("放置于 2 垒", g.away.players[0].id)
        click("place-tb-2")
        val tb = await(g.id) { !it.requiresTiebreakPlacement }
        assertEquals(g.away.players[0].id, tb.bases[1])
        assertEquals(2, tb.totalPitches)
        click("double-switch")
        player("第一名退场", g.home.players[0].id)
        player("第一名替补", g.home.players[9].id)
        click("choice-第一名守位-9")
        player("第二名退场", g.home.players[8].id)
        player("第二名替补", g.home.players[10].id)
        click("choice-第二名守位-1")
        shot("06-double-switch")
        click("save-double-switch")
        val switched = await(g.id) { it.pitcher.id == g.home.players[10].id }
        assertEquals(2, switched.totalPitches)
        assertEquals(2, switched.batting.values.sumOf { it.plateAppearances })
        device.pressBack()
        device.waitForIdle()
        shot("07-tb-field")
    }

    @Test
    fun performanceAndPracticeNeverAlterOfficialData() {
        val g =
            seed() { initial ->
                MatchEngine.apply(
                    initial,
                    Command(
                        "play",
                        outcome = Outcome.single,
                        observation =
                            Observation(
                                "line",
                                "point",
                                x = .33,
                                y = .25,
                                fielding = Fielding(listOf(8)),
                            ),
                    ),
                )
            }
        val before = db { d, _ -> d.dao().game(g.id)!!.payload }
        launch()
        click("game-${g.id}")
        click("scoring-more")
        click("batter-performance")
        player("球员", g.away.players.first().id)
        assertEquals("有去向 1 球 · 缺少去向 0 球", find(By.res("contact-coverage")).text)
        shot("08-performance-game")
        click("choice-统计范围-annual")
        shot("09-performance-annual")
        find(By.textContains("1局上 · 平飞")).click()
        assertNotNull(find(By.res("selected-contact")))
        assertEquals(before, db { d, _ -> d.dao().game(g.id)!!.payload })
        click("close-performance")
        click("back-home")
        val count = db { d, _ -> d.dao().allGames().size }
        click("start-practice")
        click("choice-练习模式-slowPitch")
        click("pitch-ball")
        assertNotNull(find(By.text("已保存 1 球")))
        shot("10-practice")
        click("reset-practice")
        assertNotNull(find(By.text("已保存 0 球")))
        click("back-home")
        assertEquals(count, db { d, _ -> d.dao().allGames().size })
        assertEquals(before, db { d, _ -> d.dao().game(g.id)!!.payload })
    }

    @Test
    fun portraitOnlyDraftSurvivesRotationAndCancelWritesNothing() {
        val g = seed(Rules(recordingMethod = "situation"))
        val before = db { d, _ -> d.dao().game(g.id)!!.payload }
        launch()
        click("game-${g.id}")
        click("record-play")
        click("pro-outcome-single")
        click("choice-球型-line")
        click("choice-落点精度-point")
        var field = find(By.res("contact-field"))
        repeat(8) {
            if (field.visibleBounds.height() < 250 * context.resources.displayMetrics.density) {
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .2f)
                device.waitForIdle()
                field = find(By.res("contact-field"))
            }
        }
        val r = field.visibleBounds
        field.click(android.graphics.Point(r.left + r.width() / 3, r.top + r.height() / 3))
        assertEquals("落点已记录", find(By.res("landing-selection-state")).text)
        click("choice-守备处理-pending")
        shot("11-pro-draft")
        val info =
            context.packageManager.getActivityInfo(
                android.content.ComponentName(context, MainActivity::class.java),
                0,
            )
        assertEquals(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            info.screenOrientation,
        )
        try {
            device.setOrientationLeft()
            device.waitForIdle()
            val bounds = find(By.res("portrait-form")).visibleBounds
            assertTrue("Horizontal app layout appeared", bounds.width() <= bounds.height())
            shot("12-portrait-lock")
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            device.waitForIdle()
        }
        assertEquals("落点已记录", find(By.res("landing-selection-state")).text)
        click("cancel-play")
        assertEquals(before, db { d, _ -> d.dao().game(g.id)!!.payload })
        assertEquals(0, stored(g.id).totalPitches)
    }

    @Test
    fun scheduleDateClockAndFinalNavigationUseRealScreens() {
        val g =
            seed(Rules(timed = true, timeLimitMinutes = 90)) {
                it.copy(isScheduled = true, scheduledAt = 1798699200000)
            }
        launch()
        click("game-${g.id}")
        assertFalse(find(By.res("pitch-ball")).isEnabled)
        shot("13-scheduled")
        click("activate-scheduled")
        await(g.id) { !it.isScheduled }
        click("scoring-more")
        text("比赛时间／预告").click()
        input("match-time", "2026-01-01 00:30")
        input("match-zone", "America/Los_Angeles")
        input("match-time-reason", "补填比赛发生时间")
        shot("14-match-time")
        click("save-match-time")
        val dated = await(g.id) { it.dateRevisions.size == 1 }
        assertEquals(2026, Performances.year(dated))
        assertEquals(0, dated.totalPitches)
        assertEquals("America/Los_Angeles", dated.playedTimeZoneID)
        click("scoring-more")
        text("比赛计时").click()
        text("Play Ball").click()
        await(g.id) { it.clockRunningSince != null }
        click("scoring-more")
        text("比赛计时").click()
        shot("15-clock")
        device.pressBack()
        device.waitForIdle()
        click("scoring-more")
        click("advanced-tools")
        click("game-note")
        input("note-text", "现场备注：阵雨暂停后继续")
        click("save-note")
        await(g.id) { it.entries.last().text == "现场备注：阵雨暂停后继续" }
        device.pressBack()
        device.waitForIdle()
        click("scoring-more")
        click("finish-game")
        click("finish-weather")
        await(g.id) { it.isFinal }
        shot("16-final-navigation")
        click("back-home")
        click("tab-1")
        assertNotNull(text("本机球队管理"))
        click("tab-2")
        assertNotNull(text("本机记录"))
        click("tab-0")
        assertTrue(stored(g.id).isFinal)
    }
}
