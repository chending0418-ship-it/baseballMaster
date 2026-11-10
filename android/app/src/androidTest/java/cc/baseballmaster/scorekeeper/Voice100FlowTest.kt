package cc.baseballmaster.scorekeeper

import android.Manifest
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Voice100FlowTest {
    @get:Rule val activity = ActivityTestRule(MainActivity::class.java)
    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private fun game(pro: Boolean = false, pregame: Boolean = false): Game {
        fun roster(name: String) = Team(UUID.randomUUID().toString(), name,
            (1..9).map { Player(UUID.randomUUID().toString(), "$name$it", "$it") })
        val home = roster("测试主队")
        val game = MatchFactory.create(UUID.randomUUID().toString(), home, roster("测试客队"),
            Rules(recordingMethod = if (pro) "situation" else "traditional"), home.id)
        return if (pregame) game else MatchEngine.apply(game, Command("pitch", pitch = PitchAction.ball))
    }

    @Test fun activatedVoiceIsVisibleBeforeFirstPitchAndAsksForLineups() =
        checkOpeningVoice(practice = false)

    @Test fun practiceVoiceOpensWithoutTheFormalLineupPrompt() =
        checkOpeningVoice(practice = true)

    private fun checkOpeningVoice(practice: Boolean) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val access = VoiceAccess(Memory(VoiceDevice(token = "T".repeat(32))), FakeService())
        inst.runOnMainSync {
            val model = ScorekeeperViewModel(activity.activity.application, SavedStateHandle())
            val original = if (practice) {
                model.practice()
                requireNotNull(model.openedGame)
            } else game(pregame = true).also { assertTrue(it.isPregame) }
            activity.activity.setContent {
                BaseballTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
                        ScoringPanels(original, model, host = { controls, task, _, locked ->
                            if (locked) task() else Column { controls() }
                        }, voiceAccess = access)
                    }
                }
            }
        }
        fun tap(tag: String) = NativeUi.find(device, By.res(tag)).click()
        tap("open-voice-scoring")
        if (!practice) {
            NativeUi.find(device, By.text("确认双方先发阵容"))
            device.pressBack()
            tap("open-voice-scoring")
            tap("confirm-opening-voice")
        }
        NativeUi.find(device, By.res("start-voice-recording"))
        if (practice) NativeUi.find(device, By.text("练习记分 · 不保存为正式比赛"))
    }

    private class Memory(var state: VoiceDevice = VoiceDevice()) : VoiceDeviceStore {
        override fun read() = state
        override fun write(device: VoiceDevice) { state = device }
    }
    private class FakeService : VoiceService {
        val operations = mutableListOf<String>()
        var fail = false
        var active = true
        var observed = ""
        var analysis = VoiceAnalysis(1, "记一个好球", VoiceAnalysis.Ball("unknown", "unknown", "unknown"),
            listOf(VoiceAnalysis.Event(1, "pitch", "好球", pitchResult = "called_strike")), emptyList())
        override suspend fun redeem(code: String, device: String, operation: String): String {
            operations += operation
            if (fail) throw VoiceIssue("模拟丢响应")
            return "T".repeat(32)
        }
        override suspend fun entitlement(token: String) = active
        override suspend fun grant(context: JsonObject, token: String): VoiceGrant = error("Synthetic recorder uses no network")
        override suspend fun end(id: String, token: String) = Unit
        override suspend fun analyze(text: String, context: JsonObject, token: String): VoiceAnalysis {
            observed = text
            return analysis
        }
    }

    @Test fun redemptionRetriesSameOperationAndRevocationClearsSealedState() = runBlocking {
        val store = Memory()
        val service = FakeService()
        val access = VoiceAccess(store, service)
        service.fail = true
        assertTrue(runCatching { access.redeem(" DEMO-1 ") }.isFailure)
        assertNull(access.token)
        assertNotNull(store.state.pendingOperation)
        service.fail = false
        access.redeem("demo-1")
        assertEquals(2, service.operations.size)
        assertEquals(service.operations.first(), service.operations.last())
        assertNull(store.state.pendingOperation)
        assertEquals("T".repeat(32), store.state.token)
        service.active = false
        access.refresh()
        assertNull(access.token); assertNull(store.state.token)
    }

    @Test fun consentCancellationSendsNothingThenSyntheticTranscriptCanBeEditedAndConfirmedOnce() =
        confirmSyntheticPitch("好球", "确认好球", "确认好球", PitchAction.calledStrike)

    @Test fun explicitPitchShowsConfirmationAutomaticallyWithoutCallingAnalysisService() =
        confirmSyntheticPitch("这球是坏球。", null, "", PitchAction.ball)

    @Test fun confirmingPracticeVoiceChangesOnlyTheInMemoryMatch() =
        confirmSyntheticPitch("界外球", null, "", PitchAction.foul, practice = true)

    @Test fun duplicateModelPitchCanBeResolvedWithoutLeavingVoiceReview() =
        confirmSyntheticPitch("裁判判定好球", null, "裁判判定好球", PitchAction.calledStrike, duplicate = true)

    @Test fun ambiguousAndMultiPitchPhrasesStillRequireAnalysis() {
        listOf("好球", "不是坏球", "先坏球再界外", "坏球还是好球", "两个坏球").forEach {
            assertNull(it, explicitVoicePitch(it))
        }
        assertEquals(PitchAction.calledStrike, explicitVoicePitch("没挥棒的好球！")?.pitch)
        assertEquals(PitchAction.swingingStrike, explicitVoicePitch("挥棒落空")?.pitch)
        assertEquals(PitchAction.foul, explicitVoicePitch("界外球")?.pitch)
    }

    @Test fun noisyBattedBallWithSafeRunnersOffersSingleAndRecordsOnce() =
        confirmBattedBall(missingBatter = false)

    @Test fun incompleteBattedBallKeepsKnownRunnerWhenScorerChoosesOutcome() =
        confirmBattedBall(missingBatter = true)

    private fun confirmBattedBall(missingBatter: Boolean) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val original = MatchEngine.apply(game(pregame = true), Command("play", outcome = Outcome.single))
        val runner = requireNotNull(original.bases.first())
        val batter = original.batter.id
        val runnerPhrase = if (missingBatter) "甲已经成功上了3类" else "甲已经成功上了2类"
        val runnerBase = if (missingBatter) "third" else "second"
        val transcript = "气球到中外方向，中外拿球传给二垒。$runnerPhrase。" +
            if (missingBatter) "乙的去向不清楚。" else "乙最终上到1类。"
        val fake = FakeService().apply {
            analysis = VoiceAnalysis(1, "内部编号 $runner", VoiceAnalysis.Ball("unknown", "中外方向", "unknown"),
                listOfNotNull(
                    VoiceAnalysis.Event(1, "field", "中外拿球", toFielder = 8),
                    VoiceAnalysis.Event(2, "throw", "传给二垒", fromFielder = 8, toFielder = 4),
                    VoiceAnalysis.Event(3, "advance", runnerPhrase, runner, "first", runnerBase),
                ), listOf("未明确上垒判定，runnerId 为 $runner"))
        }
        val access = VoiceAccess(Memory(VoiceDevice(token = "T".repeat(32))), fake)
        runBlocking { access.refresh() }
        val saved = AtomicReference(original)
        val writes = AtomicInteger()
        val recorder = object : VoiceRecording {
            override suspend fun run(context: JsonObject, token: String, stop: AtomicBoolean,
                phase: (VoicePhase) -> Unit, transcript: (String) -> Unit): String = transcriptText
            val transcriptText = transcript
        }
        inst.runOnMainSync { activity.activity.setContent {
            BaseballTheme {
                var current by remember { mutableStateOf(original) }
                var draft by remember { mutableStateOf<Pair<Command, String>?>(null) }
                val save: (Command) -> Unit = { command ->
                    current = MatchEngine.apply(current, command)
                    saved.set(current)
                    writes.incrementAndGet()
                }
                val review = draft
                when {
                    current.revision != original.revision ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("已记入比赛") }
                    review != null -> PlayEditor(original, false, { draft = null }, initial = review.first,
                        voiceConfirmation = true, voiceTranscript = review.second, save = save)
                    else -> VoiceScoringScreen(original, access, { true }, false, {}, confirm = save,
                        recording = recorder, reviewBattedBall = { command, source -> draft = command to source })
                }
            }
        } }
        fun tap(tag: String) = NativeUi.find(device, By.res(tag)).click()
        tap("start-voice-recording")
        tap("accept-voice-consent")
        val runnerName = original.battingTeam.players.single { it.id == runner }.name
        NativeUi.find(device, By.text("$runnerName · ${if (missingBatter) "三垒" else "二垒"}"))
        assertFalse(device.hasObject(By.textContains(runner)))
        assertEquals(0, writes.get())
        if (missingBatter) {
            assertFalse(device.hasObject(By.res("confirm-voice-play")))
            tap("review-voice-batted-ball")
            tap("voice-outcome-single")
            assertEquals(0, writes.get())
            tap("save-play")
        } else {
            NativeUi.find(device, By.text("一垒安打"))
            tap("confirm-voice-play")
        }
        assertTrue(device.wait(Until.hasObject(By.text("已记入比赛")), 10_000))
        assertEquals(1, writes.get())
        val after = saved.get()
        assertEquals(if (missingBatter) listOf(batter, null, runner) else listOf(batter, runner, null), after.bases)
        assertEquals(Outcome.single, after.journal.last().command.outcome)
        assertEquals(original.revision + 1, after.revision)
    }

    private fun confirmSyntheticPitch(
        recorded: String, edited: String?, expectedRemoteText: String, expectedAction: PitchAction,
        duplicate: Boolean = false, practice: Boolean = false,
    ) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val fake = FakeService()
        if (duplicate) {
            val event = fake.analysis.events.single()
            fake.analysis = fake.analysis.copy(events = listOf(event, event.copy(sequence = 2)))
        }
        val access = VoiceAccess(Memory(VoiceDevice(token = "T".repeat(32))), fake)
        runBlocking { access.refresh() }
        var practiceModel: ScorekeeperViewModel? = null
        if (practice) inst.runOnMainSync {
            practiceModel = ScorekeeperViewModel(activity.activity.application, SavedStateHandle()).also { it.practice() }
        }
        val original = practiceModel?.openedGame ?: game(pregame = true)
        val saved = AtomicReference(original)
        val writes = AtomicInteger()
        val recordings = AtomicInteger()
        val recorder = object : VoiceRecording {
            override suspend fun run(context: JsonObject, token: String, stop: AtomicBoolean,
                phase: (VoicePhase) -> Unit, transcript: (String) -> Unit): String {
                recordings.incrementAndGet()
                phase(VoicePhase.finishing)
                return recorded
            }
        }
        inst.runOnMainSync { activity.activity.setContent {
            BaseballTheme {
                var current by remember { mutableStateOf(original) }
                if (current.revision != original.revision) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("已确认本球") } else
                    VoiceScoringScreen(original, access, { current.revision == original.revision }, false, {},
                        confirm = { command ->
                            fun accept(next: Game) {
                                current = next
                                saved.set(next)
                                writes.incrementAndGet()
                            }
                            val model = practiceModel
                            if (model == null) accept(MatchEngine.apply(current, command))
                            else model.command(command, original.revision) { accept(requireNotNull(model.openedGame)) }
                        }, recording = recorder)
            }
        } }
        fun tap(tag: String) = NativeUi.find(device, By.res(tag)).click()
        tap("start-voice-recording")
        tap("cancel-voice-consent")
        assertEquals(0, recordings.get())
        assertEquals("", fake.observed)
        tap("start-voice-recording")
        tap("accept-voice-consent")
        if (duplicate) {
            val choice = NativeUi.find(device, By.res("review-voice-${expectedAction.name}"))
            assertFalse(device.hasObject(By.res("confirm-voice-play")))
            choice.click()
        }
        NativeUi.find(device, By.res("confirm-voice-play"))
        inst.runOnMainSync {
            val window = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            val flags = (window.layoutParams as WindowManager.LayoutParams).flags
            assertEquals(0, flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        assertEquals(0, writes.get())
        if (edited != null) {
            val field = NativeUi.find(device, By.res("voice-transcript"))
            field.click()
            field.text = edited
            inst.runOnMainSync {
                val manager = inst.targetContext.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
            }
            assertTrue(device.wait(Until.gone(By.res("confirm-voice-play")), 3_000))
            tap("analyze-voice-play")
        }
        assertEquals(0, writes.get())
        tap("confirm-voice-play")
        assertTrue(device.wait(Until.hasObject(By.text("已确认本球")), 10_000))
        assertEquals(1, recordings.get())
        assertEquals(expectedRemoteText, fake.observed)
        assertEquals(1, writes.get())
        val after = saved.get()
        assertEquals(expectedAction, after.journal.last().command.pitch)
        assertEquals(original.revision + 1, after.revision)
        assertTrue(after.openingPitchRecorded)
        assertEquals(!practice, after.clockHasStarted)
        assertEquals(1, after.totalPitches)
        assertEquals(original.totalPitches, MatchEngine.undo(after).totalPitches)
        if (practice) runBlocking {
            val db = LocalDatabase.open(inst.targetContext)
            try { assertNull(db.dao().game(original.id)) } finally { db.close() }
        }
    }

    @Test fun missingLocationCanBecomeAProDraftButMissingRunnerResolutionCannotCommit() {
        val g = game(pro = true)
        val analysis = VoiceAnalysis(1, "一垒安打", VoiceAnalysis.Ball("ground", "unknown", "single"),
            listOf(VoiceAnalysis.Event(1, "advance", "安打", playerId = g.batter.id, fromBase = "batter", toBase = "first")),
            listOf("未提及落点"))
        val command = requireNotNull(voiceProposal(analysis, g, "安打"))
        assertEquals(Outcome.single, command.outcome)
        assertEquals("unknown", command.observation?.precision)
        assertNull(voiceProposal(analysis, g.copy(bases = listOf(g.battingTeam.players[2].id, null, null)), "安打"))
        assertNull(voiceProposal(analysis, g, "没有证据"))
        val packet = TencentTranscript()
        assertFalse(packet.accept("""{"code":0,"result":{"index":1,"voice_text_str":"球"}}"""))
        assertTrue(packet.accept("""{"code":0,"final":1,"result":{"index":0,"voice_text_str":"好"}}"""))
        assertEquals("好球", packet.text)
    }
}
