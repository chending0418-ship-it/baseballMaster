package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import kotlinx.coroutines.withContext

@Composable
internal fun ScoringPanels(game: Game, model: ScorekeeperViewModel, requestedPanel: String = "", handled: () -> Unit = {},
    host: (@Composable (controls: @Composable () -> Unit, task: @Composable () -> Unit, inlineTask: Boolean, locked: Boolean) -> Unit)? = null,
    voiceAccess: VoiceAccess = model.voice,
) {
    var panel by rememberSaveable(game.id) { mutableStateOf("") }
    var returnPanel by rememberSaveable(game.id) { mutableStateOf("") }
    var sourceRevision by rememberSaveable(game.id) { mutableLongStateOf(game.revision) }
    // Keep history-heavy match snapshots in the ViewModel across configuration
    // changes. Only the revision enters Android saved state; it also rejects a
    // stale draft if a process restart loads a newer persisted match.
    val source = model.scoringTaskSource?.takeIf { it.id == game.id && it.revision == sourceRevision } ?: game
    val wide = LocalWorkbenchWide.current
    var voiceDraft by remember(game.id) { mutableStateOf<Command?>(null) }
    var voiceDraftTranscript by remember(game.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(voiceAccess, game.id) {
        try { voiceAccess.refresh() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Network trouble leaves manual scoring available. */ }
    }
    var inlineTask by rememberSaveable(game.id) { mutableStateOf(false) }
    fun open(name: String) {
        returnPanel = ""
        model.scoringTaskSource = model.openedGame ?: game
        sourceRevision = (model.openedGame ?: game).revision
        inlineTask = name == "play" && wide && game.rules.recordingMethod == "situation"
        panel = name
    }
    val enabled = !model.busy && !game.isFinal && !game.isScheduled && game.pendingDecision == null
    LaunchedEffect(requestedPanel) {
        if (requestedPanel.isNotEmpty()) { open(requestedPanel); handled() }
    }
    var openingPanel by rememberSaveable(game.id) { mutableStateOf<String?>(null) }
    var confirmEndHalf by rememberSaveable(game.id) { mutableStateOf(false) }
    val controls: @Composable () -> Unit = {
    if (!game.isFinal && !game.isScheduled && game.pendingDecision == null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreControl("击球进入场内", "record-play", Modifier.weight(1f), enabled && panel.isEmpty(), filled = true) {
                if (!game.openingPitchRecorded && !game.practice) openingPanel = "play" else open("play")
            }
            if (voiceAccess.token != null && game.voiceEligible())
                FilledTonalIconButton({
                    if (!game.openingPitchRecorded && !game.practice) openingPanel = "voice" else open("voice")
                }, modifier = Modifier.size(48.dp).testTag("open-voice-scoring"),
                    enabled = enabled && panel.isEmpty()) {
                    Icon(painterResource(R.drawable.ic_voice_mic), "语音输入")
                }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreControl("跑者 / 出局", "record-runner", Modifier.weight(1f), enabled && panel.isEmpty()) { open("runner") }
            ScoreControl("特殊 / 判罚", "record-special", Modifier.weight(1f), enabled && panel.isEmpty(), color = Color(0xFF9F4C0D)) { open("special") }
        }
        if (game.rules.mode == GameMode.slowPitch) {
            ScoreControl("Illegal／非法投球", "record-illegal-pitch", Modifier.fillMaxWidth(), enabled && panel.isEmpty(),
                color = Color(0xFF9F4C0D)) { open("illegal") }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        ScoreControl("撤销", "undo", Modifier.weight(1f), !model.busy && panel.isEmpty() && game.canUndo, compact = true) { model.undo() }
        ScoreControl("恢复", "redo", Modifier.weight(1f), !model.busy && panel.isEmpty() && game.cursor < game.journal.size, compact = true) { model.redo() }
        if (game.isFinal) {
            ScoreControl("返回首页", "return-final-game-home", Modifier.weight(2f), !model.busy, filled = true, compact = true) { model.open(null) }
        } else {
            ScoreControl("修正", "open-state-correction", Modifier.weight(1f), enabled && panel.isEmpty(), color = Color(0xFF9F4C0D), compact = true) { open("correction") }
            ScoreControl("阵容", "open-substitutions", Modifier.weight(1f), enabled && panel.isEmpty(), compact = true) { open("substitution") }
        }
    }
    if (!game.isFinal && game.rules.recordingMethod == "situation" && game.journal.take(game.cursor).any { it.command.kind == "play" })
        TextButton({ open("amend-observation") }, Modifier.fillMaxWidth().heightIn(min = 30.dp).testTag("amend-situation-play"), enabled = enabled && panel.isEmpty()) { Text("补充这球", fontSize = 12.sp) }
    if (!game.isFinal && !game.isScheduled && game.pendingDecision == null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ confirmEndHalf = true }, enabled = enabled && panel.isEmpty(), modifier = Modifier.testTag("end-half")) { Text("结束半局", fontSize = 13.sp) }
            if (game.strikes == 2 && game.rules.mode != GameMode.slowPitch)
                TextButton({ model.command(Command("foulBunt")) }, enabled = enabled && panel.isEmpty(), modifier = Modifier.testTag("foul-bunt-strikeout")) { Text("触击界外出局", fontSize = 12.sp) }
            TextButton({ open("finish") }, enabled = enabled && panel.isEmpty(), modifier = Modifier.testTag("finish-game")) { Text("结束比赛", fontSize = 13.sp) }
        }
    }
    if (confirmEndHalf) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { confirmEndHalf = false }, title = { Text("结束本半局？") },
        text = { Text("请按现场情况确认交换攻守，保留当前比分和记录。") },
        confirmButton = { TextButton({ confirmEndHalf = false; model.command(Command("endHalf")) }, modifier = Modifier.testTag("confirm-end-half")) { Text("确认交换攻守") } },
        dismissButton = { TextButton({ confirmEndHalf = false }) { Text("取消") } })
    openingPanel?.let { next ->
        val voice = next == "voice"
        AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { openingPanel = null }, title = { Text("确认双方先发阵容") },
            text = {
                val teams = "${game.away.name} 与 ${game.home.name}"
                Text(if (voice) "请确认 ${teams}的先发阵容。确认记入首球后开始计时。"
                else "${teams}的先发阵容确认后开始计时。开赛后仍可调整阵容。")
            },
            confirmButton = {
                TextButton({
                    openingPanel = null
                    open(next)
                }, modifier = Modifier.testTag(if (voice) "confirm-opening-voice" else "confirm-opening-play"),
                    enabled = enabled && (!voice || voiceAccess.token != null && game.voiceEligible())) {
                    Text(if (voice) "继续语音记分" else "确认开赛")
                }
            },
            dismissButton = { TextButton({ openingPanel = null; open("lineup") }) { Text("继续设置阵容") } })
    }
    if (game.practice)
        Row {
            Text("练习记分 · 不保存为正式比赛", Modifier.weight(1f), fontSize = 12.sp)
            TextButton(
                { model.practice(game.rules.recordingMethod, game.rules.mode) },
                Modifier.testTag("reset-practice"), enabled = panel.isEmpty() && !model.busy,
            ) {
                Text("重置练习")
            }
        }
    if (game.practice && panel.isEmpty())
        Choices("练习模式", GameMode.entries.associate { it.name to it.title }, game.rules.mode.name) {
            model.practice(game.rules.recordingMethod, GameMode.valueOf(it))
        }
    if(game.practice && model.showPracticeGuidance) Text(
        if(game.entries.isEmpty()) "先尝试记录一个坏球或好球。"
        else if(game.batting.values.all {it.plateAppearances==0}) "接着点击“球打出去了”，按你看到的结果完成记录。"
        else "继续尝试跑者变化、撤销和现场修正。",
        modifier=Modifier.testTag("practice-mode-banner"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (game.requiresTiebreakPlacement || game.canConfirmExtraInning(System.currentTimeMillis()))
        CardBlock {
            Text(
                if (game.requiresTiebreakPlacement) "请安排本半局 TB 自动跑者" else "请确认延长赛方式",
                fontWeight = FontWeight.Bold,
            )
            TextButton({ open("advanced") }, Modifier.testTag("tiebreak-prompt"), enabled = panel.isEmpty() && !model.busy) { Text("处理比赛决定") }
        }
    if (game.isScheduled)
        CardBlock {
            Text("预告比赛", fontWeight = FontWeight.Bold)
            Text(game.scheduledAt?.let { formatMatchDate(it, game.playedTimeZoneID) } ?: "")
            Button({ model.command(Command("activate")) },Modifier.testTag("activate-scheduled"), shape = RoundedCornerShape(15.dp)) { Text("进入比赛") }
        }
    if (game.isFinal)
        CardBlock {
            Text("比赛已结束", fontWeight = FontWeight.Bold)
            Text("${game.away.name} ${game.awayRuns} : ${game.homeRuns} ${game.home.name}")
            TextButton(
                { model.command(Command("reopen")) },
                enabled = !model.busy,
                modifier = Modifier.testTag("reopen-game"),
            ) {
                Text("恢复比赛")
            }
        }
    if (game.pendingDecision != null)
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    when (game.pendingDecision) {
                        "runLimit" -> "已达到半局得分上限"
                        "walkOff" -> "主队取得领先"
                        else -> "本半局已结束"
                    }
                )
            },
            text = {
                Text(
                    if (game.pendingDecision == "runLimit") "结束半局，或继续本半局并停止累计本半局得分。"
                    else "请按比赛现场确认是否结束。"
                )
            },
            confirmButton = {
                TextButton(
                    { model.command(Command("decision", flag = true)) },
                    enabled = !model.busy,
                    modifier = Modifier.testTag("confirm-decision"),
                ) {
                    Text(if (game.pendingDecision == "runLimit") "结束半局" else "结束比赛")
                }
            },
            dismissButton = {
                TextButton(
                    { model.command(Command("decision", flag = false)) },
                    enabled = !model.busy,
                ) {
                    Text("继续比赛")
                }
            },
        )
    }
    val task: @Composable () -> Unit = {
    CompositionLocalProvider(LocalProductTaskEmbedded provides (inlineTask && panel == "play")) {
    if (panel.isNotEmpty()) {
        val snapshot = source.copy(revision = sourceRevision)
        val close = {
            voiceDraft = null
            voiceDraftTranscript = null
            model.scoringTaskSource = model.openedGame ?: game
            sourceRevision = (model.openedGame ?: game).revision
            panel = returnPanel
            returnPanel = ""
        }
        val save: (Command) -> Unit = { model.command(it, snapshot.revision, close) }
        fun voiceMatches() = model.openedGame?.let {
            it.id == snapshot.id && it.revision == snapshot.revision && it.voiceEligible()
        } == true
        fun canSaveVoice() = !model.busy && voiceMatches() && voiceAccess.token != null
        when (panel) {
            "team-broadcast" -> {
                var target by remember(snapshot.id) { mutableStateOf<BroadcastTarget?>(null) }
                var loaded by remember(snapshot.id) { mutableStateOf(false) }
                LaunchedEffect(snapshot.id) {
                    try { target = model.sharing.broadcastTarget(snapshot) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { }
                    finally { loaded = true }
                }
                val selected = target
                if (selected == null) ProductPage("球队固定转播源", onBack = close, backLabel = "返回比赛", modal = true) {
                    Text(if (!loaded) "正在读取球队授权…" else "本场没有可用的球队转播源。请用管理员或记录员身份连接球队后重试。")
                } else BroadcastSourcesScreen(model.sharing, selected.teamID, currentGameID = selected.gameID,
                    select = if (snapshot.isPregame || snapshot.isFinal) null else { selection -> model.selectFixedBroadcast(snapshot, selection) },
                    dismiss = close)
            }
            "rule-notices" -> ProductPage("比赛规则提醒", onBack = close, backLabel = "返回比赛", modal = true) {
                game.ruleNotices(System.currentTimeMillis()).forEach { notice -> CardBlock { Text(notice, fontSize = 14.sp) } }
            }
            "play" -> PlayEditor(snapshot, model.busy, close, onNeedsCorrection = { panel = "correction" }, save = save)
            "voice" -> VoiceScoringScreen(snapshot, voiceAccess, matches = ::voiceMatches, busy = model.busy, dismiss = close,
                confirm = { candidate ->
                if (canSaveVoice()) {
                    if (candidate.kind == "play" && snapshot.rules.recordingMethod == "situation") {
                        voiceDraft = candidate
                        panel = "voice-play"
                    } else save(candidate)
                }
            }, reviewBattedBall = { draft, transcript ->
                if (canSaveVoice()) {
                    voiceDraft = draft
                    voiceDraftTranscript = transcript
                    panel = "voice-play"
                }
            })
            "voice-play" -> {
                val draft = voiceDraft
                if (draft == null) ProductPage("语音记分", onBack = close, backLabel = "返回比赛", modal = true) {
                    Text("语音草稿已结束，请返回记分。")
                } else PlayEditor(snapshot, model.busy, close, initial = draft, voiceConfirmation = true,
                    voiceTranscript = voiceDraftTranscript, save = { candidate ->
                    if (canSaveVoice()) save(candidate)
                })
            }
            "runner" -> ProductRunnerEditor(snapshot, model.busy, close, save = save)
            "amend-observation" -> {
                val index = snapshot.journal.take(snapshot.cursor).indexOfLast { it.command.kind == "play" }
                if (index >= 0) {
                    val record = snapshot.journal[index]
                    val before = remember(snapshot.id, snapshot.revision) { History.stateBefore(snapshot, History.draft(snapshot), index) }
                    var observationJSON by rememberSaveable(snapshot.id, snapshot.revision) { mutableStateOf(GameJson.encodeToString(record.command.observation ?: Observation())) }
                    val observation = remember(observationJSON) { GameJson.decodeFromString<Observation>(observationJSON) }
                    val valid = runCatching { observation.validate(snapshot.rules) }.isSuccess
                    FormPage("补充这球", model.busy, valid, "save-situation-amendment", "保存", close, {
                        model.correct(index, record.command.copy(observation = observation), "补充击球观察", baseRevision = snapshot.revision, done = close)
                    }) {
                        Text("仅补充本球观察，保留原击球结果与跑者去向。", fontSize = 12.sp)
                        ProductObservationEditor(before, observation, record.command.outcome ?: Outcome.other) { change -> observationJSON = GameJson.encodeToString(change(observation)) }
                    }
                }
            }
            "correction" -> ManualSituationEditor(snapshot, model.busy, close, save)
            "special" -> ProductPage("特殊／判罚", model.busy, close, modal = true) {
                ProductActionRow("触身球", "打者获判上一垒", tag = "special-hitByPitch", enabled = enabled) { save(Command("hitByPitch")) }
                if (game.rules.mode != GameMode.coachPitch)
                    ProductActionRow("故意保送", "按现场判给打者上一垒", tag = "special-intentionalWalk", enabled = enabled) { save(Command("intentionalWalk")) }
                if (game.rules.mode == GameMode.standard)
                    ProductActionRow("三振未接住", "核对打者与跑者的最终位置", tag = "special-droppedThirdStrike",
                        enabled = enabled && (game.outs == 2 || game.bases[0] == null)) { panel = "dropped" }
                ProductActionRow("裁判判罚", "妨碍、阻挡与其他判给结果", tag = "special-violation", enabled = enabled) { panel = "violation" }
                ProductActionRow("局面／复核／延长赛", tag = "advanced-tools") { panel = "advanced" }
            }
            "more" ->
                FormPage("比赛操作", model.busy, false, "unused", "", close, {}) {
                    Text(
                        "${game.rules.mode.title} · ${if(game.rules.recordingMethod=="situation")"Pro 版" else "基础版"}",
                        fontWeight = FontWeight.Bold,
                    )
                    listOf(
                            "触身球" to "hitByPitch",
                            "故意保送" to "intentionalWalk",
                            "两好球触击界外" to "foulBunt",
                        )
                        .forEach { (label, kind) ->
                            TextButton(
                                { save(Command(kind)) },
                                enabled =
                                    enabled &&
                                        (kind != "intentionalWalk" ||
                                            game.rules.mode != GameMode.coachPitch) &&
                                        (kind != "foulBunt" ||
                                            game.rules.mode != GameMode.slowPitch &&
                                                game.strikes == 2),
                            ) {
                                Text(label)
                            }
                        }
                    if (game.rules.mode == GameMode.standard)
                        TextButton(
                            { panel = "dropped" },
                            enabled = enabled && (game.outs == 2 || game.bases[0] == null),
                        ) {
                            Text("三振未接住")
                        }
                    if (game.rules.mode == GameMode.slowPitch)
                        TextButton({ panel = "illegal" }, enabled = enabled) { Text("Illegal 宣告") }
                    TextButton({ panel = "substitution" }, enabled = enabled) { Text("换人／换投／守位") }
                    TextButton({ panel = "lineup" }, enabled = !model.busy&&!game.isFinal&&game.pendingDecision==null) { Text("双方阵容与打序") }
                    TextButton({ panel = "violation" }, enabled = enabled) { Text("裁判判罚") }
                    TextButton({ panel = "time" }) { Text("比赛时间／预告") }
                    TextButton({ panel = "rules" }) { Text("本场比赛规则") }
                    TextButton({ panel = "performance" }, Modifier.testTag("batter-performance")) {
                        Text("打击表现／击球图")
                    }
                    TextButton({ panel = "advanced" }, Modifier.testTag("advanced-tools")) {
                        Text("局面／复核／延长赛")
                    }
                    TextButton({ panel = "clock" }) { Text("比赛计时") }
                    if (!game.practice) {
                        TextButton({panel="live"},Modifier.testTag("live-broadcast")) {Text("文字直播")}
                        TextButton({ panel = "poster" }, Modifier.testTag("game-poster")) { Text("比赛宣传海报") }
                        ReportShareButton("导出比赛报告 PDF", "export-game-pdf") { context ->
                            ReportExport.game(context, game, false)
                        }
                        ReportShareButton("导出逐球记录 PDF", "export-play-by-play-pdf") { context ->
                            ReportExport.game(context, game, true)
                        }
                        ReportShareButton("导出逐球纯文字 PDF", "export-play-text-pdf") { context ->
                            ReportExport.game(context, game, true, textOnly = true)
                        }
                        TextButton({ panel = "appearance-exports" }, Modifier.testTag("export-single-appearance")) { Text("导出单打席 PDF") }
                    }
                    TextButton(
                        { panel = "finish" },
                        enabled = !model.busy && !game.isFinal && !game.isScheduled,
                        modifier = Modifier.testTag("finish-game"),
                    ) {
                        Text("结束比赛")
                    }
                    TextButton({ save(Command("endHalf")) }, enabled = enabled) { Text("手动结束本半局") }
                    TextButton(
                        { save(Command("swap")) },
                        enabled = enabled && game.canSwapOpeningSides,
                    ) {
                        Text("首球前互换先攻／后攻")
                    }
                }
            "results" -> GameResultsScreen(game, model, close) {
                model.scoringTaskSource = model.openedGame ?: game
                sourceRevision = (model.openedGame ?: game).revision
                returnPanel = "results"
                panel = it
            }
            "live" -> StandaloneLiveScreen(game,model.live,close)
            "time" -> MatchTimeEditor(snapshot, model.busy, close, save)
            "rules" -> {
                var rules by remember { mutableStateOf<Rules?>(snapshot.rules) }
                FormPage(
                    "比赛规则",
                    model.busy,
                    rules != null && !snapshot.openingPitchRecorded,
                    "save-rules",
                    "保存",
                    close,
                    { save(Command("configure", rules = rules)) },
                ) {
                    RulesForm(snapshot.rules) { rules = it }
                    if (snapshot.openingPitchRecorded) Text("首球后比赛规则已固定。")
                }
            }
            "dropped" ->
                DecisionEditor(
                    "三振未接住",
                    snapshot,
                    MatchEngine.forced(snapshot).map {
                        if (it.origin > 0) it.copy(destination = 0) else it
                    },
                    model.busy,
                    close,
                ) {
                    save(Command("droppedThirdStrike", decisions = it))
                }
            "substitution" -> ParitySubstitutionEditor(snapshot, model.busy, close) { command ->
                model.command(command, snapshot.revision) {
                    model.scoringTaskSource = model.openedGame ?: game
                    sourceRevision = (model.openedGame ?: game).revision
                }
            }
            "lineup" -> ParityLineupEditor(snapshot, model.busy, close, save = { command ->
                model.command(command, snapshot.revision) {
                    model.scoringTaskSource = model.openedGame ?: game
                    sourceRevision = (model.openedGame ?: game).revision
                }
            })
            "violation" -> ViolationEditor(snapshot, model.busy, close, save)
            "illegal" -> IllegalEditor(snapshot, model.busy, close, save)
            "finish" -> FinishGameTask(snapshot, model.busy, close, save)
            "clock" -> ClockEditor(game, model.busy, close) { model.command(it, done = close) }
            "review" -> AdvancedPanel(game, model, close, initialPage = "review")
            "advanced" -> AdvancedPanel(game, model, close)
            "poster" -> GamePosterScreen(game, close)
            "appearance-exports" -> FormPage("选择打席", false, true, "close-appearance-exports", "完成", close, close) {
                AppearanceProjection.build(game).forEach { appearance ->
                    val batter = (game.away.players + game.home.players).firstOrNull { it.id == appearance.batterID }
                    CardBlock {
                        Text("${if (appearance.isTop) "客队" else "主队"}第 ${appearance.sequence} 打席 · ${appearance.inning}局${if (appearance.isTop) "上" else "下"}", fontWeight = FontWeight.Bold)
                        Text(batter?.let { "#${it.number} ${it.name}" } ?: "打者未记录")
                        Text(appearance.events.lastOrNull()?.text ?: "打席进行中", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ReportShareButton("详细 PDF", "export-appearance-detail-${appearance.id}") { context -> ReportExport.game(context, game, true, appearanceID = appearance.id) }
                            ReportShareButton("文字 PDF", "export-appearance-text-${appearance.id}") { context -> ReportExport.game(context, game, true, textOnly = true, appearanceID = appearance.id) }
                        }
                    }
                }
            }
            "performance" -> PerformanceSheet(game, model.games, close)
            "history" -> HistoryEditor(game, model, close)
        }
    }
    }
    }
    if (host != null) host(controls, task, inlineTask && panel == "play", panel.isNotEmpty())
    else { controls(); task() }
}

@Composable
internal fun Choices(
    label: String,
    values: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (values.size <= 4) {
        val columns = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f && values.size > 2) 2 else values.size
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))) {
            values.entries.toList().chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(3.dp)) {
                    row.forEach { (key, title) ->
                        Box(Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(7.dp))
                            .background(if(selected == key) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .toggleable(value = selected == key, role = Role.RadioButton, onValueChange = { onSelect(key) }).testTag("choice-$label-$key"), contentAlignment = Alignment.Center) {
                            Text(title, fontSize = 12.sp, fontWeight = if(selected == key) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = if(selected == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 6.dp))
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEach { (key, title) ->
            FilterChip(selected == key, { onSelect(key) }, modifier = Modifier.testTag("choice-$label-$key"), label = { Text(title, fontSize = 12.sp) })
        }
    }

}

@Composable
private fun DecisionEditor(
    title: String,
    game: Game,
    initial: List<Decision>,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (List<Decision>) -> Unit,
) {
    var json by rememberSaveable { mutableStateOf(GameJson.encodeToString(initial)) }
    FormPage(
        title,
        busy,
        true,
        "save-decisions",
        "记录",
        dismiss,
        { save(GameJson.decodeFromString(json)) },
    ) {
        ProductRunnerDecisions(game, GameJson.decodeFromString(json), false) {
            json = GameJson.encodeToString(it(GameJson.decodeFromString(json)))
        }
    }
}

@Composable
internal fun PlayerChoice(
    label: String,
    players: List<Player>,
    selected: String?,
    change: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                { expanded = true },
                modifier = Modifier.testTag("player-choice-$label"),
                enabled = players.isNotEmpty(),
             shape = RoundedCornerShape(14.dp)) {
                val p = players.firstOrNull { it.id == selected }
                Text(
                    p?.let { "#${it.number} ${it.name}" }
                        ?: if (players.isEmpty()) "没有可用球员" else "选择球员"
                )
            }
            DropdownMenu(
                expanded,
                { expanded = false },
                modifier =
                    Modifier.heightIn(max = 360.dp).semantics { testTagsAsResourceId = true },
            ) {
                players.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("#${p.number} ${p.name}") },
                        modifier = Modifier.testTag("player-option-${p.id}"),
                        onClick = {
                            change(p.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockEditor(game: Game, busy: Boolean, dismiss: () -> Unit, save: (Command) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(game.id) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val seconds =
        if (game.clockDisplayMode == "remaining" && game.rules.timeLimitMinutes != null)
            (game.remainingTime(now) ?: 0) / 1000
        else game.elapsed(now) / 1000
    FormPage("比赛计时", busy, false, "unused", "", dismiss, {}) {
        Text(
            "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )
        if (game.rules.timeLimitMinutes != null)
            Choices(
                "计时显示",
                mapOf("elapsed" to "已用时间", "remaining" to "剩余时间"),
                game.clockDisplayMode,
            ) {
                if (it != game.clockDisplayMode) save(Command("clock", text = "display"))
            }
        Text(if (game.clockRunningSince != null) "计时中" else "计时暂停")
        Text(game.rules.timeLimitMinutes?.let { "比赛时间限制 $it 分钟" } ?: "本场按局数进行")
        Button(
            { save(Command("clock", text = if (game.clockHasStarted) "resume" else "start")) },
            enabled = !game.isFinal && !game.isScheduled && game.clockRunningSince == null && !busy,
         shape = RoundedCornerShape(15.dp)) {
            Text(if (game.clockHasStarted) "继续计时" else "Play Ball")
        }
        OutlinedButton(
            { save(Command("clock", text = "pause")) },
            enabled = game.clockRunningSince != null && !busy,
         shape = RoundedCornerShape(14.dp)) {
            Text("暂停计时")
        }
    }
}

@Composable
private fun IllegalEditor(game: Game, busy: Boolean, dismiss: () -> Unit, save: (Command) -> Unit) {
    var thrown by rememberSaveable { mutableStateOf(true) }
    var penalty by rememberSaveable { mutableStateOf(true) }
    var result by rememberSaveable { mutableStateOf("ball") }
    var editingPlay by rememberSaveable { mutableStateOf(false) }
    if (editingPlay)
        PlayEditor(game, busy, { editingPlay = false }) {
            save(it.copy(kind = "illegal", wasThrown = thrown, penaltyApplied = penalty))
        }
    else
        FormPage(
            "Illegal 宣告",
            busy,
            result != "play" && (thrown || result in listOf("ball","none")),
            "save-illegal",
            "记录",
            dismiss,
            {
                save(
                    Command(
                        "illegal",
                        pitch = PitchAction.entries.firstOrNull { it.name == result },
                        wasThrown = thrown,
                        penaltyApplied = penalty,
                    )
                )
            },
        ) {
            Choices(
                "是否实际投出",
                mapOf("yes" to "实际投出", "no" to "未实际投出"),
                if (thrown) "yes" else "no",
            ) {
                thrown = it == "yes"
                if(!thrown&&result !in listOf("ball","none"))result="ball"
            }
            Choices(
                "罚则",
                mapOf("yes" to "罚则适用", "no" to "未适用，按最终结果"),
                if (penalty) "yes" else "no",
            ) {
                penalty = it == "yes"
            }
            Choices(
                "裁判最终结果",
                mapOf(
                    "ball" to "坏球",
                    "calledStrike" to "好球 · 看",
                    "swingingStrike" to "挥空",
                    "foul" to "界外",
                    "none" to "No Pitch",
                    "play" to "击球结果",
                ).filterKeys{thrown||it in listOf("ball","none")},
                result,
            ) {
                result = it
            }
            if (result == "play") Button({ editingPlay = true }, shape = RoundedCornerShape(15.dp)) { Text("记录击球与跑者") }
        }
}

@Composable
private fun ViolationEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Command) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf("batterInterference") }
    var previous by rememberSaveable { mutableStateOf("notApplicable") }
    var plate by rememberSaveable { mutableStateOf("continueAtBat") }
    var credit by rememberSaveable { mutableStateOf("none") }
    var counts by rememberSaveable { mutableStateOf(false) }
    var final by rememberSaveable { mutableStateOf(true) }
    var rbi by rememberSaveable { mutableStateOf("0") }
    var earned by rememberSaveable { mutableStateOf("0") }
    var errorID by rememberSaveable { mutableStateOf("") }
    val base =
        remember(previous) {
            if (previous == "cancel" && game.cursor > 0)
                MatchEngine.stateBefore(game, game.cursor - 1)
            else game
        }
    var dsJson by
        rememberSaveable(previous, plate) {
            mutableStateOf(
                GameJson.encodeToString(
                    when (plate) {
                        "batterFirst" -> MatchEngine.forced(base)
                        "batterOut" ->
                            base.bases.mapIndexedNotNull { i, p ->
                                p?.let { Decision(it, i + 1, 0) }
                            } + Decision(base.batter.id, 0, -1)
                        else ->
                            base.bases.mapIndexedNotNull { i, p ->
                                p?.let { Decision(it, i + 1, 0) }
                            }
                    }
                )
            )
        }
    val options =
        ScoringOptions.violations.filterKeys {
            game.rules.mode != GameMode.coachPitch ||
                it !in listOf("balk", "quickPitch", "illegalPitch", "foreignSubstance")
        }
    FormPage(
        "裁判判罚",
        busy,
        rbi.toIntOrNull() != null && earned.toIntOrNull() != null,
        "save-violation",
        "记录",
        dismiss,
        {
            save(
                Command(
                    "adjudication",
                    runnerKind = kind,
                    decisions = GameJson.decodeFromString(dsJson),
                    adjudication =
                        Ruling(
                            previousPlay = previous,
                            plateAppearance = plate,
                            countsAsAtBat = counts,
                            isFinal = final,
                            battingCredit = credit,
                            runsBattedIn = rbi.toInt(),
                            earnedRuns = earned.toInt(),
                            errorPlayerID = errorID.takeIf { it.isNotBlank() },
                        ),
                )
            )
        },
    ) {
        Choices("判罚", options, kind) { kind = it }
        OutlinedButton({ save(Command("violation", runnerKind = kind)) }, shape = RoundedCornerShape(14.dp)) { Text("记录默认判罚") }
        HorizontalDivider()
        Text("最终宣判", fontWeight = FontWeight.Bold)
        Choices(
            "前一结果",
            mapOf("notApplicable" to "不适用", "keep" to "保留", "cancel" to "取消前一结果"),
            previous,
        ) {
            previous = it
        }
        Choices(
            "打席",
            mapOf("continueAtBat" to "继续打击", "batterOut" to "打者出局", "batterFirst" to "打者上垒"),
            plate,
        ) {
            plate = it
        }
        ProductRunnerDecisions(base, GameJson.decodeFromString(dsJson), false) {
            dsJson = GameJson.encodeToString(it(GameJson.decodeFromString(dsJson)))
        }
        Choices(
            "打击统计责任",
            mapOf(
                "none" to "不另记结果",
                "single" to "一垒安打",
                "double" to "二垒安打",
                "triple" to "三垒安打",
                "homeRun" to "本垒打",
                "sacrifice" to "牺牲",
                "reachedOnError" to "失误上垒",
            ),
            credit,
        ) {
            credit = it
        }
        Row {
            Checkbox(counts, { counts = it })
            Text("计入打数", Modifier.padding(top = 13.dp))
        }
        if (credit == "reachedOnError")
            PlayerChoice(
                "失误责任人",
                base.fieldingTeam.players.filter { it.id in base.fieldingPositions.values },
                errorID,
            ) {
                errorID = it
            }
        OutlinedTextField(rbi, { rbi = it }, label = { Text("本次打点") }, singleLine = true)
        OutlinedTextField(earned, { earned = it }, label = { Text("本次自责分") }, singleLine = true)
        Row {
            Checkbox(final, { final = it })
            Text("裁判已最终确认", Modifier.padding(top = 13.dp))
        }
    }
}

private fun historyTitle(command: Command) = History.title(command)

@Composable
private fun HistoryEditor(game: Game, model: ScorekeeperViewModel, dismiss: () -> Unit) {
    var screen by rememberSaveable(game.id) { mutableStateOf("list") }
    var selected by rememberSaveable(game.id) { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    LaunchedEffect(game.id) { model.loadHistoryDraft(game) }
    val draft = model.historyDraft?.takeIf { it.gameID == game.id }
    val record = draft?.records?.getOrNull(selected)
    val before =
        remember(game.revision, draft, selected) {
            draft?.let { runCatching { History.stateBefore(game, it, selected) }.getOrNull() }
        }
    val preview by
        produceState<CorrectionPreview?>(null, game.revision, draft) {
            value = null
            value = draft?.let {
                withContext(kotlinx.coroutines.Dispatchers.Default) { History.preview(game, it) }
            }
        }
    fun update(value: CorrectionDraft) {
        model.updateHistoryDraft(value)
    }
    fun changeRecord(value: RecordedCommand) {
        val d = draft ?: return
        update(d.copy(records = d.records.toMutableList().apply { set(selected, value) }))
    }
    fun start(index: Int) {
        val d = draft ?: History.draft(game)
        update(d)
        selected = index
        screen = "detail"
    }
    fun amend(command: Command) {
        val r = record ?: return
        changeRecord(r.copy(command = command.copy(at = r.command.at), edited = true))
        screen = "draft"
    }
    fun insert(command: Command) {
        val d = draft ?: return
        update(History.insert(game, d, selected, command))
        screen = "draft"
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("放弃纠错草稿？") },
            text = { Text("比赛正式记录不会改变。") },
            confirmButton = {
                TextButton({
                    model.discardHistoryDraft {
                        screen = "list"
                        discard = false
                    }
                }) {
                    Text("放弃草稿")
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text("继续核对") } },
        )
    if (screen == "edit" && record != null && before != null) {
        when (record.command.kind) {
            "play" -> PlayEditor(before, model.busy, { screen = "detail" }, record.command, save = ::amend)
            "runner" ->
                ProductRunnerEditor(before, model.busy, { screen = "detail" }, record.command, save = ::amend)
            "pitch" ->
                HistoryPitchEditor(record.command, model.busy, { screen = "detail" }, ::amend)
            "pitcher",
            "batter",
            "pinchRunner",
            "fielder",
            "position" -> ParitySubstitutionEditor(before, model.busy, { screen = "detail" }, ::amend)
            else ->
                FormPage("更正记录", model.busy, false, "unused", "", { screen = "detail" }, {}) {
                    Text("可在草稿中删除此操作，或在前后补记并核对重放结果。")
                }
        }
        return
    }
    if (screen == "responsibility" && record != null && before != null) {
        HistoryResponsibilityEditor(before, record, model.busy, { screen = "detail" }) {
            changeRecord(it)
            screen = "draft"
        }
        return
    }
    if (screen.startsWith("insert-") && draft != null && before != null) {
        when (screen) {
            "insert-pitch" ->
                HistoryPitchEditor(
                    Command("pitch", pitch = PitchAction.ball),
                    model.busy,
                    { screen = "insert" },
                    ::insert,
                )
            "insert-play" -> PlayEditor(before, model.busy, { screen = "insert" }, save = ::insert)
            "insert-runner" ->
                ProductRunnerEditor(before, model.busy, { screen = "insert" }, save = ::insert)
            "insert-situation" ->
                ManualSituationEditor(before, model.busy, { screen = "insert" }, ::insert)
            "insert-sub" -> ParitySubstitutionEditor(before, model.busy, { screen = "insert" }, ::insert)
        }
        return
    }
    if (screen == "insert" && draft != null) {
        FormPage("补记操作", model.busy, false, "unused", "", { screen = "draft" }, {}) {
            Text("补在第 ${selected+1} 条之前；保存前会核对之后每条记录的局次、棒次和跑者。")
            if (before == null) Text("此处之前存在冲突，请先修正前面的记录。")
            else {
                TextButton({ screen = "insert-situation" }, Modifier.testTag("insert-situation")) {
                    Text("补正历史局面")
                }
                TextButton({ screen = "insert-sub" }, Modifier.testTag("insert-substitution")) {
                    Text("换人／换投／守位")
                }
                TextButton({ screen = "insert-pitch" }, Modifier.testTag("insert-pitch")) {
                    Text("漏记投球")
                }
                TextButton({ screen = "insert-play" }, Modifier.testTag("insert-play")) {
                    Text("漏记击球")
                }
                TextButton(
                    { screen = "insert-runner" },
                    enabled = before.bases.any { it != null },
                ) {
                    Text("漏记跑垒")
                }
            }
        }
        return
    }
    if (screen == "detail" && record != null) {
        FormPage(
            "历史纠错",
            model.busy,
            before != null,
            "edit-correction",
            "编辑",
            { screen = "draft" },
            { screen = "edit" },
        ) {
            Text(
                "第 ${selected+1} 条 · ${historyTitle(record.command)}",
                fontWeight = FontWeight.Bold,
            )
            record.context?.let {
                Text(
                    "${it.inning}局${if(it.isTop)"上"else"下"} · 第 ${it.batterIndex+1} 棒",
                    fontSize = 12.sp,
                )
            }
            OutlinedTextField(
                draft.reason,
                { update(draft.copy(reason = it)) },
                label = { Text("纠错原因") },
                modifier = Modifier.fillMaxWidth().testTag("correction-reason"),
            )
            Text("先在草稿中修改，再预览比分和统计变化。", fontSize = 12.sp)
            TextButton({ screen = "insert" }, Modifier.testTag("insert-before")) {
                Text("在此记录之前补记")
            }
            TextButton({
                selected++
                screen = "insert"
            }) {
                Text("在此记录之后补记")
            }
            TextButton({ screen = "responsibility" }, enabled = before != null) {
                Text("补记失误／复核统计责任")
            }
            TextButton(
                {
                    changeRecord(record.copy(acceptContext = true, edited = true))
                    screen = "draft"
                },
                Modifier.testTag("accept-history-context"),
                enabled = before != null,
            ) {
                Text("确认此记录归属重放后的局次与棒次")
            }
            TextButton(
                {
                    update(
                        draft.copy(records = draft.records.filterIndexed { i, _ -> i != selected })
                    )
                    screen = "draft"
                },
                Modifier.testTag("delete-correction"),
            ) {
                Text("从草稿删除此操作")
            }
        }
        return
    }
    if (screen != "list" && draft != null) {
        val result = preview
        FormPage(
            "更正预览",
            model.busy,
            result?.canSave == true && result.changes.isNotEmpty() && draft.reason.isNotBlank(),
            "save-history-correction",
            "保存更正",
            { screen = "list" },
            { model.saveHistoryCorrection(draft) { screen = "list" } },
        ) {
            OutlinedTextField(
                draft.reason,
                { update(draft.copy(reason = it)) },
                label = { Text("更正原因") },
                modifier = Modifier.fillMaxWidth().testTag("correction-reason"),
            )
            Text(
                if (game.practice) "练习草稿仅保留在本次练习"
                else if (model.historyDraftSaved) "草稿已保存在本机" else "正在保存草稿…",
                Modifier.testTag("history-draft-status"),
                fontSize = 12.sp,
            )
            Text("原比赛 ${game.awayRuns}:${game.homeRuns}", fontWeight = FontWeight.Bold)
            if (result == null) Text("正在重放核对…")
            else if (result.conflict != null) {
                Text(
                    result.conflict.orEmpty(),
                    Modifier.testTag("history-conflict"),
                    color = MaterialTheme.colorScheme.error,
                )
                result.conflictID?.let { id ->
                    TextButton({
                        selected = draft.records.indexOfFirst { it.id == id }
                        screen = "detail"
                    }) {
                        Text("核对冲突记录")
                    }
                }
            } else {
                val next = result.game!!
                Text(
                    "更正后 ${next.awayRuns}:${next.homeRuns}",
                    Modifier.testTag("history-preview-score"),
                    fontWeight = FontWeight.Bold,
                )
                if (next.statisticsIncomplete)
                    Text("统计责任待确认，统计可能不完整。", color = MaterialTheme.colorScheme.error)
                result.changes.forEach { Text(it, fontSize = 12.sp) }
            }
            draft.records.forEachIndexed { i, r ->
                CardBlock {
                    Text(
                        "${i+1}. ${historyTitle(r.command)}${if(r.inserted)" · 补记"else if(r.edited)" · 更正"else""}"
                    )
                    TextButton(
                        {
                            selected = i
                            screen = "detail"
                        },
                        Modifier.testTag("draft-record-$i"),
                    ) {
                        Text("核对／编辑")
                    }
                }
            }
            TextButton(
                {
                    selected = draft.records.size
                    screen = "insert"
                },
                Modifier.testTag("history-append"),
            ) {
                Text("在末尾补记")
            }
            TextButton({ discard = true }, Modifier.testTag("discard-history-draft")) {
                Text("放弃纠错草稿")
            }
        }
        return
    }
    FormPage("完整记录", model.busy, false, "unused", "", dismiss, {}) {
        if (model.historyDraftLoadedFor != game.id) Text("正在读取纠错草稿…")
        else if (draft != null)
            TextButton({ screen = "draft" }, Modifier.testTag("resume-history-draft")) {
                Text("继续上次纠错草稿")
            }
        if (game.incompletePrefix || game.statisticsIncomplete) Text("部分历史过程或统计责任不完整，请核对记录。")
        game.entries.asReversed().forEach { entry ->
            CardBlock {
                Text(
                    "${entry.inning}局${if(entry.isTop)"上"else"下"} · ${entry.notation}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(entry.text, fontWeight = FontWeight.SemiBold)
                entry.observation?.let { o ->
                    Text(
                        "${ScoringOptions.ballTypes[o.type]} · ${o.region?.let{ScoringOptions.regions[it]}?:if(o.precision=="point")"已记录落点"else"没有看清"}",
                        fontSize = 12.sp,
                    )
                }
                entry.observation?.fielding?.let { f ->
                    Text(
                        "路线 ${f.route.joinToString(" → "){pos->entry.participants.firstOrNull{it.position==pos}?.let{"$pos ${it.name}"}?:pos.toString()}}",
                        fontSize = 12.sp,
                    )
                }
                val index = game.journal.indexOfFirst { it.id == entry.id }
                val draftIndex =
                    if (draft == null) index else draft.records.indexOfFirst { it.id == entry.id }
                if (index in 0 until game.cursor)
                    TextButton(
                        { start(draftIndex) },
                        enabled =
                            draftIndex >= 0 &&
                                model.historyDraftLoadedFor == game.id &&
                                !model.busy,
                        modifier = Modifier.testTag("correct-record-$index"),
                    ) {
                        Text(if (draftIndex < 0) "草稿已删除此记录" else "纠错／补充本球")
                    }
            }
        }
        if (game.historyRevisions.isNotEmpty()) {
            Text("更正历史", fontWeight = FontWeight.Bold)
            game.historyRevisions.asReversed().forEach { revision ->
                CardBlock {
                    Text(revision.reason, fontWeight = FontWeight.SemiBold)
                    Text(
                        java.time.Instant.ofEpochMilli(revision.at)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            ),
                        fontSize = 12.sp,
                    )
                    revision.summary.forEach { Text(it, fontSize = 12.sp) }
                }
            }
            TextButton(
                {
                    update(History.revertingDraft(game))
                    screen = "draft"
                },
                enabled = draft == null && !model.busy,
                modifier = Modifier.testTag("revert-latest-correction"),
            ) {
                Text("撤回最近一次更正")
            }
        } else game.correctionRevisions.forEach { Text(it, fontSize = 12.sp) }
    }
}

@Composable
private fun HistoryPitchEditor(
    initial: Command,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Command) -> Unit,
) {
    var pitch by rememberSaveable { mutableStateOf(initial.pitch?.name ?: PitchAction.ball.name) }
    FormPage(
        "投球纠错",
        busy,
        true,
        "save-correction",
        "加入草稿",
        dismiss,
        { save(initial.copy(pitch = PitchAction.valueOf(pitch))) },
    ) {
        Choices("投球结果", PitchAction.entries.associate { it.name to it.title }, pitch) { pitch = it }
    }
}

@Composable
private fun HistoryResponsibilityEditor(
    before: Game,
    record: RecordedCommand,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (RecordedCommand) -> Unit,
) {
    var creditJSON by rememberSaveable {
        mutableStateOf(GameJson.encodeToString(record.additionalErrors))
    }
    val credits = remember(creditJSON) { GameJson.decodeFromString<List<ErrorCredit>>(creditJSON) }
    var reviewed by rememberSaveable { mutableStateOf(record.statisticsRuling != null) }
    var rbi by rememberSaveable {
        mutableStateOf(record.statisticsRuling?.runsBattedIn?.toString().orEmpty())
    }
    var earnedJSON by rememberSaveable {
        mutableStateOf(
            GameJson.encodeToString(
                record.statisticsRuling?.earnedRuns?.mapValues { it.value.toString() }
                    ?: emptyMap<String, String>()
            )
        )
    }
    val earned = remember(earnedJSON) { GameJson.decodeFromString<Map<String, String>>(earnedJSON) }
    val valid =
        (!reviewed ||
            (rbi.toIntOrNull()?.let { it >= 0 } == true &&
                earned.values.all { it.toIntOrNull()?.let { n -> n >= 0 } == true }))
    FormPage(
        "失误与统计责任",
        busy,
        valid,
        "save-history-responsibility",
        "加入草稿",
        dismiss,
        {
            save(
                record.copy(
                    edited = true,
                    additionalErrors = credits,
                    statisticsRuling =
                        if (reviewed)
                            StatisticsRuling(
                                rbi.toInt(),
                                earned.mapValues { it.value.toInt() },
                                record.statisticsRuling?.unearnedRunnerIDs ?: emptySet(),
                            )
                        else null,
                )
            )
        },
    ) {
        Text("补记失误不会增加打席或投球；责任未确认时标记统计可能不完整。", fontSize = 12.sp)
        before.fieldingTeam.players
            .filter { it.id in before.fieldingPositions.values }
            .forEach { player ->
                val count = credits.firstOrNull { it.playerID == player.id }?.count ?: 0
                Choices(
                    "${player.name} #${player.number}",
                    (0..9).associate { it.toString() to if (it == 0) "无" else "$it 次" },
                    count.toString(),
                ) { value ->
                    val n = value.toInt()
                    creditJSON =
                        GameJson.encodeToString(
                            credits.filter { it.playerID != player.id } +
                                if (n > 0) listOf(ErrorCredit(player.id, n)) else emptyList()
                        )
                }
            }
        Row {
            Checkbox(reviewed, { reviewed = it })
            Text("人工确认本事件打点与投手责任", Modifier.padding(top = 13.dp))
        }
        if (reviewed) {
            OutlinedTextField(rbi, { rbi = it }, label = { Text("本事件打点") }, singleLine = true)
            before.fieldingTeam.players
                .filter { it.id in before.pitching || it.id == before.pitcher.id }
                .forEach { p ->
                    OutlinedTextField(
                        earned[p.id].orEmpty(),
                        {
                            earnedJSON =
                                GameJson.encodeToString(
                                    earned.toMutableMap().apply {
                                        if (it.isBlank()) remove(p.id) else put(p.id, it)
                                    }
                                )
                        },
                        label = { Text("${p.name} 本事件自责分（留空沿用）") },
                        singleLine = true,
                    )
                }
            Text("数值会按本事件实际有效得分和各投手失分校验。", fontSize = 12.sp)
        }
    }
}

/** Same hierarchy, colors and corner treatment as iOS ScoreActionButtonStyle. */
@Composable
internal fun ScoreControl(label: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    color: Color = Accent, filled: Boolean = false, compact: Boolean = false, action: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val sizing = modifier.heightIn(min = if (compact) 38.dp else 44.dp).testTag(tag)
    val padding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    if (filled) Button(action, sizing, enabled = enabled, shape = shape, contentPadding = padding,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    } else OutlinedButton(action, sizing, enabled = enabled, shape = shape, contentPadding = padding,
        border = BorderStroke(1.dp, color.copy(alpha = if(enabled) .25f else .12f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, containerColor = MaterialTheme.colorScheme.surface)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}


@Composable
private fun FinishGameTask(game: Game, busy: Boolean, close: () -> Unit, save: (Command) -> Unit) {
    var selected by rememberSaveable(game.id) { mutableStateOf<String?>(null) }
    val reasons = linkedMapOf("regulation" to "规定局数完成", "timeLimit" to "时间限制结束",
        "mercyRule" to "提前结束规则", "forfeit" to "弃权结束", "weather" to "天气或场地原因",
        "suspended" to "比赛中断", "scorerDecision" to "记录员结束记录")
    ProductPage("结束或中断比赛", busy, close, backLabel = "取消", modal = true) {
        CardBlock {
            Text("时间、球数和投手局数限制只负责提醒，请按现场裁判或赛事规定选择结束原因。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        reasons.forEach { (reason, label) ->
            ProductActionRow(label,
                if (reason == "suspended") "保存当前局面，比赛仍保留在正在进行中" else "冻结计时并保存为已结束比赛",
                Icons.Outlined.CheckCircle, "finish-$reason", enabled = !busy) { selected = reason }
        }
    }
    selected?.let { reason ->
        AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { if (!busy) selected = null },
            title = { Text(if (reason == "suspended") "确认中断比赛？" else "确认结束比赛？") },
            text = { Text("${game.away.shortName} ${game.awayRuns} : ${game.homeRuns} ${game.home.shortName}\n${reasons[reason]}") },
            confirmButton = { TextButton({ save(Command(if (reason == "suspended") "suspend" else "finish", text = reason)) },
                enabled = !busy, modifier = Modifier.testTag("confirm-finish-game")) { Text("确认保存") } },
            dismissButton = { TextButton({ selected = null }, enabled = !busy) { Text("取消") } })
    }
}
