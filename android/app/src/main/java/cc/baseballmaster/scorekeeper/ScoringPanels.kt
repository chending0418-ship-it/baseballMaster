package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

@Composable
internal fun ScoringPanels(game: Game, model: ScorekeeperViewModel) {
    var panel by rememberSaveable(game.id) { mutableStateOf("") }
    var source by rememberSaveable(game.id) { mutableStateOf("") }
    fun open(name: String) {
        source = GameJson.encodeToString(game)
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
            enabled = !model.busy && game.cursor > 0,
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
            TextButton({ model.practice(game.rules.recordingMethod) }) { Text("重置练习") }
        }
    if (game.isScheduled)
        CardBlock {
            Text("预告比赛", fontWeight = FontWeight.Bold)
            Text(game.scheduledAt?.let { formatMatchDate(it, game.playedTimeZoneID) } ?: "")
            Button({ model.command(Command("activate")) }) { Text("进入比赛") }
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
        val snapshot = remember(source) { GameJson.decodeFromString<Game>(source) }
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
                    TextButton({ panel = "lineup" }, enabled = enabled) { Text("双方阵容与打序") }
                    TextButton({ panel = "violation" }, enabled = enabled) { Text("裁判判罚") }
                    TextButton({ panel = "time" }) { Text("比赛时间／预告") }
                    TextButton({ panel = "rules" }) { Text("本场比赛规则") }
                    TextButton({ panel = "clock" }) { Text("比赛计时") }
                    TextButton(
                        { panel = "finish" },
                        enabled = enabled,
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
            "lineup" -> LineupEditor(snapshot, model.busy, close, save)
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
                                { save(Command("finish", text = reason)) },
                                modifier = Modifier.testTag("finish-$reason"),
                            ) {
                                Text(label)
                            }
                        }
                }
            "clock" -> ClockEditor(game, model.busy, close) { model.command(it, done = close) }
            "history" -> HistoryEditor(snapshot, model, close)
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

private val CommandDraftSaver = androidx.compose.runtime.saveable.Saver<Command,String>(
    save={GameJson.encodeToString(it)}, restore={GameJson.decodeFromString<Command>(it)}
)

@Composable
internal fun PlayEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    initial: Command? = null,
    save: (Command) -> Unit,
) {
    var cmd by rememberSaveable(game.id,game.revision,stateSaver=CommandDraftSaver) {
        mutableStateOf(initial?:Command("play",outcome=Outcome.single,decisions=MatchEngine.suggested(game,Outcome.single)))
    }
    fun update(f:(Command)->Command){cmd=f(cmd)}
    var pro by rememberSaveable {
        mutableStateOf(initial?.observation != null || game.rules.recordingMethod == "situation")
    }
    FormPage(
        "击球记录",
        busy,
        true,
        "save-play",
        "记录",
        dismiss,
        {
            val latest = cmd
            save(
                latest.copy(
                    recordingMethod = if (pro) "situation" else "traditional",
                    observation = if (pro) latest.observation ?: Observation() else null,
                    timingRunCounts =
                        if (MatchEngine.needsTiming(game, latest)) latest.timingRunCounts else null,
                )
            )
        },
    ) {
        Choices("记分方式", mapOf("base" to "基础版", "pro" to "Pro 版"), if (pro) "pro" else "base") { key
            ->
            pro = key == "pro"
            update { it.copy(observation = if (pro) it.observation ?: Observation() else null) }
        }
        Choices(
            "打者结果",
            Outcome.entries
                .filter { game.rules.mode != GameMode.slowPitch || it != Outcome.sacrificeBunt }
                .associate { it.name to it.title },
            cmd.outcome!!.name,
        ) { key ->
            val outcome = Outcome.valueOf(key)
            update {
                it.copy(
                    outcome = outcome,
                    decisions = MatchEngine.suggested(game, outcome),
                    timingRunCounts = null,
                )
            }
        }
        if (pro)
            ObservationEditor(game, cmd.observation ?: Observation(), cmd.outcome!!) { f ->
                update { it.copy(observation = f(it.observation ?: Observation())) }
            }
        RunnerDecisions(game, cmd.decisions.orEmpty(), pro) { f ->
            update { it.copy(decisions = f(it.decisions.orEmpty()), timingRunCounts = null) }
        }
        if (MatchEngine.needsTiming(game, cmd))
            TimingChoice(cmd.timingRunCounts) { value ->
                update { it.copy(timingRunCounts = value) }
            }
        Text(
            "核对本球所有跑者后保存。取消不会增加投球或统计。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
    var cmd by rememberSaveable(game.id,game.revision,stateSaver=CommandDraftSaver) {
        mutableStateOf(initial?:Command("runner",runnerKind=options.keys.first(),decisions=MatchEngine.suggestedRunner(game,options.keys.first())))
    }
    fun update(f:(Command)->Command){cmd=f(cmd)}
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
private fun RunnerDecisions(
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
                modify { it.copy(destination = key.toInt()) }
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
private fun TimingChoice(value: Boolean?, change: (Boolean) -> Unit) {
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
private fun ObservationEditor(
    game: Game,
    observation: Observation,
    outcome: Outcome,
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
            if(observation.precision=="point")Text(if(observation.x!=null&&observation.y!=null)"落点已记录"else"请点选球场落点",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("landing-selection-state"))
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
private fun FieldingEditor(
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
private fun PlayerChoice(
    label: String,
    players: List<Player>,
    selected: String?,
    change: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ expanded = true }, enabled = players.isNotEmpty()) {
                val p = players.firstOrNull { it.id == selected }
                Text(
                    p?.let { "#${it.number} ${it.name}" }
                        ?: if (players.isEmpty()) "没有可用球员" else "选择球员"
                )
            }
            DropdownMenu(
                expanded,
                { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                players.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("#${p.number} ${p.name}") },
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
private fun LineupEditor(game: Game, busy: Boolean, dismiss: () -> Unit, save: (Command) -> Unit) {
    var home by rememberSaveable { mutableStateOf(true) }
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
                if (game.rules.mode == GameMode.slowPitch && !game.openingPitchRecorded)
                    TextButton(
                        { orderJson = GameJson.encodeToString(order - id) },
                        enabled = order.size > 10 && id !in positions.values,
                    ) {
                        Text("移出")
                    }
            }
        }
        if (game.rules.mode == GameMode.slowPitch && !game.openingPitchRecorded)
            PlayerChoice("增加打者", team.players.filter { it.id !in order }, null) {
                orderJson = GameJson.encodeToString(order + it)
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
    val seconds = game.elapsed(now) / 1000
    FormPage("比赛计时", busy, false, "unused", "", dismiss, {}) {
        Text(
            "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(if (game.clockRunningSince != null) "计时中" else "计时暂停")
        Text(game.rules.timeLimitMinutes?.let { "比赛时间限制 $it 分钟" } ?: "本场按局数进行")
        Button(
            { save(Command("clock", text = if (game.clockHasStarted) "resume" else "start")) },
            enabled = !game.isFinal && game.clockRunningSince == null && !busy,
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
            result != "play",
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
                ),
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

@Composable
private fun HistoryEditor(game: Game, model: ScorekeeperViewModel, dismiss: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var reason by rememberSaveable { mutableStateOf("") }
    var edit by rememberSaveable { mutableStateOf(false) }
    val record = game.journal.getOrNull(selected)
    if (record != null && edit) {
        val base = remember(selected) { MatchEngine.stateBefore(game, selected) }
        val save: (Command) -> Unit = {
            model.correct(selected, it, reason, baseRevision = game.revision) {
                selected = -1
                edit = false
            }
        }
        when (record.command.kind) {
            "play" -> PlayEditor(base, model.busy, { edit = false }, record.command, save)
            "runner" -> RunnerEditor(base, model.busy, { edit = false }, record.command, save)
            "pitch" -> {
                var pitch by rememberSaveable { mutableStateOf(record.command.pitch!!.name) }
                FormPage(
                    "投球纠错",
                    model.busy,
                    true,
                    "save-correction",
                    "保存",
                    { edit = false },
                    { save(record.command.copy(pitch = PitchAction.valueOf(pitch))) },
                ) {
                    Choices("投球结果", PitchAction.entries.associate { it.name to it.title }, pitch) {
                        pitch = it
                    }
                    Text(reason)
                }
            }
            else ->
                FormPage("更正说明", model.busy, false, "unused", "", { edit = false }, {}) {
                    Text("此记录可撤销后重新录入，或填写原因删除并重放后续记录。")
                }
        }
    } else if (record != null)
        FormPage(
            "历史纠错",
            model.busy,
            reason.isNotBlank(),
            "edit-correction",
            "编辑",
            { selected = -1 },
            { edit = true },
        ) {
            Text(
                game.entries.firstOrNull { it.id == record.id }?.text ?: "比赛操作",
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                reason,
                { reason = it },
                label = { Text("纠错原因") },
                modifier = Modifier.fillMaxWidth().testTag("correction-reason"),
            )
            Text("保存前重算后续记录。若打者或跑者发生冲突，会保留原比赛并提示核对。", fontSize = 12.sp)
            TextButton(
                {
                    model.correct(selected, null, reason, baseRevision = game.revision) {
                        selected = -1
                    }
                },
                enabled = reason.isNotBlank(),
                modifier = Modifier.testTag("delete-correction"),
            ) {
                Text("删除此操作并重放")
            }
        }
    else
        FormPage("完整记录", model.busy, false, "unused", "", dismiss, {}) {
            if (game.incompletePrefix) Text("部分历史过程不完整，请核对记录。")
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
                    if (index in 0 until game.cursor)
                        TextButton({
                            selected = index
                            reason = ""
                        }) {
                            Text("纠错／补充本球")
                        }
                }
            }
            if (game.correctionRevisions.isNotEmpty()) {
                Text("纠错记录", fontWeight = FontWeight.Bold)
                game.correctionRevisions.forEach { Text(it, fontSize = 12.sp) }
            }
        }
}
