package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import kotlinx.coroutines.withContext

@Composable
internal fun ScoringPanels(game: Game, model: ScorekeeperViewModel) {
    var panel by rememberSaveable(game.id) { mutableStateOf("") }
    var sourceRevision by rememberSaveable(game.id) { mutableLongStateOf(game.revision) }
    var source by remember(game.id) { mutableStateOf(game) }
    fun open(name: String) {
        source = game
        sourceRevision = game.revision
        panel = name
    }
    val enabled = !model.busy && !game.isFinal && !game.isScheduled && game.pendingDecision == null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            { open("play") },
            Modifier.weight(1f).testTag("record-play"),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("击球／上垒")
        }
        OutlinedButton(
            { open("runner") },
            Modifier.weight(1f).testTag("record-runner"),
            enabled = enabled && game.bases.any { it != null },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("跑者")
        }
        OutlinedButton(
            { open("more") },
            Modifier.weight(.75f).testTag("scoring-more"),
            enabled = !model.busy,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("更多")
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            model::undo,
            enabled = !model.busy && game.canUndo,
            modifier = Modifier.testTag("undo"),
        ) {
            Text("撤销")
        }
        TextButton(
            model::redo,
            enabled = !model.busy && game.cursor < game.journal.size,
            modifier = Modifier.testTag("redo"),
        ) {
            Text("重做")
        }
        TextButton({ open("history") }, modifier = Modifier.testTag("game-history")) {
            Text("完整记录 · ${game.entries.size}")
        }
    }
    if (game.practice)
        Row {
            Text("练习记分 · 不保存为正式比赛", Modifier.weight(1f), fontSize = 12.sp)
            TextButton(
                { model.practice(game.rules.recordingMethod, game.rules.mode) },
                Modifier.testTag("reset-practice"),
            ) {
                Text("重置练习")
            }
        }
    if (game.practice)
        Choices("练习模式", GameMode.entries.associate { it.name to it.title }, game.rules.mode.name) {
            model.practice(game.rules.recordingMethod, GameMode.valueOf(it))
        }
    if (game.requiresTiebreakPlacement || game.canConfirmExtraInning(System.currentTimeMillis()))
        CardBlock {
            Text(
                if (game.requiresTiebreakPlacement) "请安排本半局 TB 自动跑者" else "请确认延长赛方式",
                fontWeight = FontWeight.Bold,
            )
            TextButton({ open("advanced") }, Modifier.testTag("tiebreak-prompt")) { Text("处理比赛决定") }
        }
    if (game.isScheduled)
        CardBlock {
            Text("预告比赛", fontWeight = FontWeight.Bold)
            Text(game.scheduledAt?.let { formatMatchDate(it, game.playedTimeZoneID) } ?: "")
            Button({ model.command(Command("activate")) },Modifier.testTag("activate-scheduled")) { Text("进入比赛") }
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
    if (panel.isNotEmpty()) {
        val snapshot = source.copy(revision = sourceRevision)
        val close = { panel = "" }
        val save: (Command) -> Unit = { model.command(it, snapshot.revision, close) }
        when (panel) {
            "play" -> PlayEditor(snapshot, model.busy, close, save = save)
            "runner" -> RunnerEditor(snapshot, model.busy, close, save = save)
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
            "substitution" -> SubstitutionEditor(snapshot, model.busy, close, save)
            "lineup" -> LineupEditor(snapshot, model.busy, close, save = save)
            "violation" -> ViolationEditor(snapshot, model.busy, close, save)
            "illegal" -> IllegalEditor(snapshot, model.busy, close, save)
            "finish" ->
                FormPage("结束比赛", model.busy, false, "unused", "", close, {}) {
                    Text("结束后保留完整记录，可从比赛页恢复。")
                    listOf(
                            "scorerDecision" to "记录员结束记录",
                            "regulation" to "规定局数完成",
                            "timeLimit" to "时间限制结束",
                            "mercyRule" to "提前结束规则",
                            "forfeit" to "弃权结束",
                            "weather" to "天气或场地原因",
                            "suspended" to "比赛中断",
                        )
                        .forEach { (reason, label) ->
                            TextButton(
                                {
                                    save(
                                        Command(
                                            if (reason == "suspended") "suspend" else "finish",
                                            text = reason,
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("finish-$reason"),
                            ) {
                                Text(label)
                            }
                        }
                }
            "clock" -> ClockEditor(game, model.busy, close) { model.command(it, done = close) }
            "advanced" -> AdvancedPanel(game, model, close)
            "performance" -> PerformanceSheet(game, model.games, close)
            "history" -> HistoryEditor(game, model, close)
        }
    }
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        values.forEach { (key, title) ->
            FilterChip(
                selected == key,
                { onSelect(key) },
                modifier = Modifier.testTag("choice-$label-$key"),
                label = { Text(title, fontSize = 12.sp) },
            )
        }
    }
}

private val CommandDraftSaver =
    androidx.compose.runtime.saveable.Saver<Command, String>(
        save = { GameJson.encodeToString(it) },
        restore = { GameJson.decodeFromString<Command>(it) },
    )

@Composable
private fun RunnerEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    initial: Command? = null,
    save: (Command) -> Unit,
) {
    val options =
        ScoringOptions.runnerEvents.filterKeys { ScoringOptions.runnerAllowed(game.rules.mode, it) }
    var cmd by
        rememberSaveable(game.id, game.revision, stateSaver = CommandDraftSaver) {
            mutableStateOf(
                initial
                    ?: Command(
                        "runner",
                        runnerKind = options.keys.first(),
                        decisions = MatchEngine.suggestedRunner(game, options.keys.first()),
                    )
            )
        }
    fun update(f: (Command) -> Command) {
        cmd = f(cmd)
    }
    FormPage(
        "跑者记录",
        busy,
        true,
        "save-runner",
        "记录",
        dismiss,
        {
            val latest = cmd
            save(
                latest.copy(
                    timingRunCounts =
                        if (MatchEngine.needsTiming(game, latest)) latest.timingRunCounts else null
                )
            )
        },
    ) {
        Choices("跑垒事件", options, cmd.runnerKind!!) { kind ->
            update {
                it.copy(
                    runnerKind = kind,
                    decisions = MatchEngine.suggestedRunner(game, kind),
                    timingRunCounts = null,
                )
            }
        }
        RunnerDecisions(game, cmd.decisions.orEmpty(), false) { f ->
            update { it.copy(decisions = f(it.decisions.orEmpty()), timingRunCounts = null) }
        }
        if (MatchEngine.needsTiming(game, cmd))
            TimingChoice(cmd.timingRunCounts) { value ->
                update { it.copy(timingRunCounts = value) }
            }
    }
}

@Composable
internal fun RunnerDecisions(
    game: Game,
    decisions: List<Decision>,
    pro: Boolean,
    change: ((List<Decision>) -> List<Decision>) -> Unit,
) {
    decisions.forEach { d ->
        fun modify(f: (Decision) -> Decision) {
            change { current -> current.map { if (it.playerID == d.playerID) f(it) else it } }
        }
        val p = game.battingTeam.players.first { it.id == d.playerID }
        CardBlock {
            Text(
                "${if(d.origin==0)"打者"else"${d.origin} 垒跑者"} · #${p.number} ${p.name}",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            val options = linkedMapOf<String, String>()
            if (d.origin > 0) options["0"] = "停留"
            ((d.origin + 1).coerceAtLeast(1)..3).forEach { options[it.toString()] = "${it} 垒" }
            options["4"] = "得分"
            options["-1"] = "出局"
            Choices("最终位置", options, d.destination.toString()) { key ->
                modify { d->val dest=key.toInt();d.copy(destination=dest,cause=if(!pro)d.cause else if(dest==0)"hit"else if(dest == -1&&d.cause=="hit")"tag"else if(dest != -1&&d.cause in listOf("tag","appeal"))"hit"else d.cause,fielding=if(pro&&dest==0)null else d.fielding) }
            }
            if (pro) {
                Choices("进垒／出局原因", ScoringOptions.causes, d.cause) { cause ->
                    modify { it.copy(cause = cause) }
                }
                if (d.cause in listOf("relay", "error", "tag", "appeal") && d.destination != 0)
                    FieldingEditor(game, d.fielding ?: Fielding(status = "pending"), false) { f ->
                        modify {
                            it.copy(fielding = f(it.fielding ?: Fielding(status = "pending")))
                        }
                    }
            }
        }
    }
}

@Composable
internal fun TimingChoice(value: Boolean?, change: (Boolean) -> Unit) {
    Choices(
        "若第三出局同时发生得分，按现场先后确认",
        mapOf("yes" to "得分先发生", "no" to "出局先发生"),
        when (value) {
            true -> "yes"
            false -> "no"
            null -> ""
        },
    ) {
        change(it == "yes")
    }
}

@Composable
internal fun ObservationEditor(
    game: Game,
    observation: Observation,
    outcome: Outcome,
    onTypeChosen: () -> Unit = {},
    change: ((Observation) -> Observation) -> Unit,
) {
    var fieldMode by rememberSaveable { mutableStateOf("landing") }
    CardBlock {
        Choices(
            "球型",
            ScoringOptions.ballTypes.filterKeys {
                it != "bunt" || game.rules.mode != GameMode.slowPitch
            },
            observation.type,
        ) { type ->
            onTypeChosen()
            change { it.copy(type = type) }
        }
        Choices(
            "落点精度",
            mapOf("unknown" to "没有看清", "region" to "大致区域", "point" to "精确落点"),
            observation.precision,
        ) { precision ->
            fieldMode = "landing"
            change {
                it.copy(
                    precision = precision,
                    region = null,
                    x = null,
                    y = null,
                    fielding =
                        if (precision == "unknown") null else it.fielding ?: Fielding(status = ""),
                )
            }
        }
        if (observation.precision == "region")
            Choices("区域", ScoringOptions.regions, observation.region.orEmpty()) { region ->
                change { it.copy(region = region) }
            }
        if (observation.precision != "unknown") {
            Choices(
                "球场操作",
                mapOf("landing" to "落点", "route" to "传球路线", "error" to "失误责任"),
                fieldMode,
            ) {
                fieldMode = it
            }
            SituationField(
                game,
                observation,
                observation.fielding ?: Fielding(status = ""),
                mode = SituationFieldMode.valueOf(fieldMode),
                onPoint = { x, y ->
                    change { it.copy(precision = "point", region = null, x = x, y = y) }
                },
                onFielder = { pos ->
                    change { o ->
                        val f = o.fielding ?: Fielding(status = "")
                        o.copy(
                            fielding =
                                if (fieldMode == "error") f.copy(errorPosition = pos)
                                else
                                    f.copy(
                                        status = "recorded",
                                        route =
                                            if (f.route.lastOrNull() == pos) f.route
                                            else (f.route + pos).take(50),
                                    )
                        )
                    }
                },
            )
            if (observation.precision == "point")
                Text(
                    if (observation.x != null && observation.y != null) "落点已记录" else "请点选球场落点",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("landing-selection-state"),
                )
            FieldingEditor(
                game,
                observation.fielding ?: Fielding(status = ""),
                outcome == Outcome.homeRun,
                showField = false,
            ) { f ->
                change { it.copy(fielding = f(it.fielding ?: Fielding(status = ""))) }
            }
        }
    }
}

@Composable
internal fun FieldingEditor(
    game: Game,
    fielding: Fielding,
    allowNone: Boolean,
    showField: Boolean = true,
    change: ((Fielding) -> Fielding) -> Unit,
) {
    Choices(
        "守备处理",
        if (allowNone) mapOf("recorded" to "已记录", "pending" to "守备待补", "noFielding" to "越墙本垒打，无人处理")
        else mapOf("recorded" to "已记录", "pending" to "守备待补"),
        fielding.status,
    ) { status ->
        change {
            it.copy(
                status = status,
                route = if (status == "noFielding") emptyList() else it.route,
                errorPosition = if (status == "noFielding") null else it.errorPosition,
            )
        }
    }
    if (fielding.status != "noFielding") {
        Text("防守路线：${fielding.route.joinToString(" → ").ifBlank{"待记录"}}", fontSize = 13.sp)
        var errorMode by rememberSaveable { mutableStateOf(false) }
        if (showField)
            Choices(
                "球场操作",
                mapOf("route" to "传球路线", "error" to "失误责任"),
                if (errorMode) "error" else "route",
            ) {
                errorMode = it == "error"
            }
        if (showField)
            SituationField(
                game,
                fielding = fielding,
                mode = if (errorMode) SituationFieldMode.error else SituationFieldMode.route,
                onFielder = { pos ->
                    change {
                        if (errorMode) it.copy(errorPosition = pos)
                        else
                            it.copy(
                                status = "recorded",
                                route =
                                    if (it.route.lastOrNull() == pos) it.route
                                    else (it.route + pos).take(50),
                            )
                    }
                },
            )
        TextButton({ change { it.copy(route = it.route.dropLast(1)) } }) { Text("撤回一次传球") }
        Choices(
            "失误责任",
            mapOf("" to "无／待确认") +
                game.fieldingPositions
                    .toSortedMap()
                    .map { (pos, pid) ->
                        pos.toString() to "$pos ${game.fieldingTeam.players.first{it.id==pid}.name}"
                    }
                    .toMap(),
            fielding.errorPosition?.toString().orEmpty(),
        ) { position ->
            change { it.copy(errorPosition = position.toIntOrNull()) }
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
        RunnerDecisions(game, GameJson.decodeFromString(json), false) {
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
            ) {
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
private fun SubstitutionEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Command) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("pitcher") }
    var pid by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var pos by rememberSaveable { mutableIntStateOf(1) }
    val fielders = game.fieldingTeam.players.filter { it.id in game.fieldingPositions.values }
    val battingPositions = if (game.isTop) game.awayPositions else game.homePositions
    val bench =
        game.battingTeam.players.filter {
            it.id !in game.battingOrder &&
                it.id !in (battingPositions ?: emptyMap()).values &&
                it.id !in game.bases
        }
    val defenseOrder =
        (if (game.isTop) game.homeOrder else game.awayOrder)
            ?: game.fieldingTeam.players.map { it.id }
    val fieldingBench =
        game.fieldingTeam.players.filter {
            it.id !in defenseOrder && it.id !in game.fieldingPositions.values
        }
    val eligible =
        when (type) {
            "pitcher" -> fielders.filter { it.id != game.pitcher.id } + fieldingBench
            "batter",
            "pinchRunner" -> bench
            else -> fielders
        }
    FormPage(
        "换人／换投／守位",
        busy,
        pid.isNotBlank() && (type != "fielder" || replacement.isNotBlank()),
        "save-substitution",
        "保存",
        dismiss,
        {
            save(
                Command(
                    type,
                    playerID = pid,
                    replacementID = replacement.takeIf { it.isNotBlank() },
                    position = pos,
                )
            )
        },
    ) {
        Choices(
            "操作",
            mapOf(
                "pitcher" to "换投",
                "batter" to "代打",
                "pinchRunner" to "代跑",
                "fielder" to "守备换人",
                "position" to "守位互换",
            ),
            type,
        ) {
            type = it
            pid = ""
            replacement = ""
        }
        if (type == "pinchRunner")
            Choices(
                "替换哪一垒跑者",
                game.bases
                    .mapIndexedNotNull { i, id -> id?.let { (i + 1).toString() to "${i+1} 垒" } }
                    .toMap(),
                pos.toString(),
            ) {
                pos = it.toInt()
            }
        PlayerChoice(if (type == "fielder") "原球员" else "球员", eligible, pid) { pid = it }
        if (type == "fielder") PlayerChoice("替补球员", fieldingBench, replacement) { replacement = it }
        if (type == "position")
            Choices(
                "改守位置",
                ScoringOptions.positions
                    .take(game.rules.fielders)
                    .mapIndexed { i, label -> (i + 1).toString() to label }
                    .toMap(),
                pos.toString(),
            ) {
                pos = it.toInt()
            }
        Text(
            "保留已记录的投手责任及原球员统计；代打、代跑继承原棒次。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LineupEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    initialHome: Boolean = true,
    save: (Command) -> Unit,
) {
    var home by rememberSaveable { mutableStateOf(initialHome) }
    val team = if (home) game.home else game.away
    var orderJson by
        rememberSaveable(home) {
            mutableStateOf(
                GameJson.encodeToString(
                    (if (home) game.homeOrder else game.awayOrder) ?: team.players.map { it.id }
                )
            )
        }
    var positionsJson by
        rememberSaveable(home) {
            mutableStateOf(
                GameJson.encodeToString(
                    (if (home) game.homePositions else game.awayPositions)
                        ?: team.players
                            .take(game.rules.fielders)
                            .mapIndexed { i, p -> i + 1 to p.id }
                            .toMap()
                )
            )
        }
    var dh by
        rememberSaveable(home) {
            mutableStateOf(
                (if (home) game.homeDesignatedHitterID else game.awayDesignatedHitterID).orEmpty()
            )
        }
    val order = remember(orderJson) { GameJson.decodeFromString<List<String>>(orderJson) }
    val positions =
        remember(positionsJson) { GameJson.decodeFromString<Map<Int, String>>(positionsJson) }
    FormPage(
        "双方阵容与打序",
        busy,
        true,
        "save-lineup",
        "保存",
        dismiss,
        {
            save(
                Command(
                    "lineup",
                    homeSide = home,
                    order = order,
                    positions = positions,
                    designatedHitterID = dh.takeIf { it.isNotBlank() },
                )
            )
        },
    ) {
        Choices(
            "球队",
            mapOf("away" to game.away.name, "home" to game.home.name),
            if (home) "home" else "away",
        ) {
            home = it == "home"
        }
        Text("打序", fontWeight = FontWeight.Bold)
        order.forEachIndexed { i, id ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val p = team.players.first { it.id == id }
                Text(
                    "${i+1}. #${p.number} ${p.name}",
                    Modifier.weight(1f).padding(top = 12.dp),
                    fontSize = 13.sp,
                )
                TextButton(
                    {
                        val list = order.toMutableList()
                        list.removeAt(i)
                        list.add(i - 1, id)
                        orderJson = GameJson.encodeToString(list)
                    },
                    enabled = i > 0,
                ) {
                    Text("↑")
                }
                TextButton(
                    {
                        val list = order.toMutableList()
                        list.removeAt(i)
                        list.add(i + 1, id)
                        orderJson = GameJson.encodeToString(list)
                    },
                    enabled = i < order.lastIndex,
                ) {
                    Text("↓")
                }
                if (game.rules.mode == GameMode.slowPitch && game.isPregame)
                    TextButton(
                        { orderJson = GameJson.encodeToString(order - id) },
                        enabled = order.size > 10 && id !in positions.values,
                    ) {
                        Text("移出")
                    }
            }
        }
        if (game.rules.mode == GameMode.slowPitch && game.isPregame)
            PlayerChoice("增加打者", team.players.filter { it.id !in order }, null) {
                orderJson = GameJson.encodeToString(order + it)
            }
        if (game.isPregame) {
            Text("赛前先发选择", fontWeight = FontWeight.Bold)
            order.forEachIndexed { index, id ->
                PlayerChoice("第 ${index+1} 棒", team.players, id) { incoming ->
                    val ids = order.toMutableList()
                    val other = ids.indexOf(incoming)
                    ids[index] = incoming
                    if (other >= 0) ids[other] = id
                    val map = positions.mapValues { (_, pid) ->
                        if (pid == id) incoming else if (other >= 0 && pid == incoming) id else pid
                    }
                    orderJson = GameJson.encodeToString(ids)
                    positionsJson = GameJson.encodeToString(map)
                    if (dh == id) dh = incoming
                }
            }
        }
        HorizontalDivider()
        if (game.rules.dhEnabled) {
            PlayerChoice(
                "DH",
                team.players.filter {
                    it.id in order &&
                        (it.id !in positions.values ||
                            game.rules.twoWayEnabled && it.id == positions[1])
                },
                dh,
            ) {
                dh = it
            }
            TextButton({ dh = "" }) { Text("取消 DH 身份") }
        }
        Text("守备位置", fontWeight = FontWeight.Bold)
        positions.toSortedMap().forEach { (pos, id) ->
            PlayerChoice("$pos ${ScoringOptions.positions[pos-1]}", team.players, id) { new ->
                val map = positions.toMutableMap()
                val oldPos = map.entries.firstOrNull { it.value == new }?.key
                if (oldPos != null) map[oldPos] = id
                map[pos] = new
                positionsJson = GameJson.encodeToString(map)
            }
        }
        Text(
            "当前打者的身份保持，历史路线保留记录时的姓名。换入替补请使用换人入口。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        ) {
            Text(if (game.clockHasStarted) "继续计时" else "Play Ball")
        }
        OutlinedButton(
            { save(Command("clock", text = "pause")) },
            enabled = game.clockRunningSince != null && !busy,
        ) {
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
            if (result == "play") Button({ editingPlay = true }) { Text("记录击球与跑者") }
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
        OutlinedButton({ save(Command("violation", runnerKind = kind)) }) { Text("记录默认判罚") }
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
        RunnerDecisions(base, GameJson.decodeFromString(dsJson), false) {
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
            "play" -> PlayEditor(before, model.busy, { screen = "detail" }, record.command, ::amend)
            "runner" ->
                RunnerEditor(before, model.busy, { screen = "detail" }, record.command, ::amend)
            "pitch" ->
                HistoryPitchEditor(record.command, model.busy, { screen = "detail" }, ::amend)
            "pitcher",
            "batter",
            "pinchRunner",
            "fielder",
            "position" -> SubstitutionEditor(before, model.busy, { screen = "detail" }, ::amend)
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
                RunnerEditor(before, model.busy, { screen = "insert" }, save = ::insert)
            "insert-situation" ->
                ManualSituationEditor(before, model.busy, { screen = "insert" }, ::insert)
            "insert-sub" -> SubstitutionEditor(before, model.busy, { screen = "insert" }, ::insert)
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
