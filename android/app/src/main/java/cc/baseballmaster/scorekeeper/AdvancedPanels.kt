package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

@Composable
internal fun AdvancedPanel(game: Game, model: ScorekeeperViewModel, close: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("menu") }
    val save: (Command) -> Unit = { model.command(it, game.revision) { page = "menu" } }
    when (page) {
        "note" -> {
            var text by rememberSaveable{mutableStateOf("")}
            FormPage("比赛备注",model.busy,text.isNotBlank(),"save-note","记录",{page="menu"},{save(Command("note",text=text.trim()))}){OutlinedTextField(text,{text=it},label={Text("备注内容")},modifier=Modifier.fillMaxWidth().testTag("note-text"))}
        }

        "situation" -> ManualSituationEditor(game, model.busy, { page = "menu" }, save)
        "double" -> DoubleSwitchEditor(game, model.busy, { page = "menu" }, save)
        "review" -> ReviewEditor(game, model.busy, { page = "menu" }, save)
        else ->
            FormPage("局面与比赛决定", model.busy, false, "unused", "", close, {}) {
                if (!game.isFinal) {
                    TextButton({ page = "situation" }, Modifier.testTag("manual-situation")) {
                        Text("人工局面修正")
                    }
                    TextButton(
                        { page = "double" },
                        Modifier.testTag("double-switch"),
                        enabled = game.pendingDecision == null,
                    ) {
                        Text("双重换人")
                    }
                    val at = System.currentTimeMillis()
                    var bases by rememberSaveable { mutableStateOf("2") }
                    Choices(
                        "TB 放人垒位",
                        mapOf("2" to "二垒", "1,2" to "一、二垒", "1,2,3" to "满垒"),
                        bases,
                    ) {
                        bases = it
                    }
                    if (game.canConfirmExtraInning(at)) {
                        Text("已进入延长局，请确认继续方式。", fontWeight = FontWeight.Bold)
                        TextButton(
                            { save(Command("extraInning", flag = false)) },
                            Modifier.testTag("confirm-extra"),
                        ) {
                            Text("普通延长赛")
                        }
                        TextButton(
                            {
                                save(
                                    Command(
                                        "extraInning",
                                        flag = true,
                                        runnerBases = bases.split(',').map { it.toInt() },
                                    )
                                )
                            },
                            Modifier.testTag("confirm-extra-tb"),
                        ) {
                            Text("延长赛并启用 TB")
                        }
                    } else if (game.canEnableTiebreak(at))
                        TextButton(
                            {
                                save(
                                    Command(
                                        "enableTB",
                                        runnerBases = bases.split(',').map { it.toInt() },
                                    )
                                )
                            },
                            Modifier.testTag("enable-tb"),
                        ) {
                            Text("从本局启用 TB")
                        }
                    if (game.requiresTiebreakPlacement) {
                        Text("本半局 TB 自动跑者", fontWeight = FontWeight.Bold)
                        game.tiebreakRunnerBases
                            .filter { "${game.halfKey}-base$it" !in game.tiebreakPlacementKeys }
                            .forEach { base ->
                                var pid by
                                    rememberSaveable(game.halfKey, base) { mutableStateOf("") }
                                PlayerChoice("放置于 $base 垒", game.eligibleTiebreakRunners, pid) {
                                    pid = it
                                }
                                TextButton(
                                    { save(Command("placeTB", playerID = pid, position = base)) },
                                    Modifier.testTag("place-tb-$base"),
                                    enabled = pid.isNotBlank(),
                                ) {
                                    Text("确认 $base 垒自动跑者")
                                }
                            }
                        TextButton({ save(Command("skipTB")) }, Modifier.testTag("skip-tb")) {
                            Text("本半局不放人，标记待确认")
                        }
                    }
                }
                TextButton({page="note"},Modifier.testTag("game-note")){Text("比赛备注")}
            TextButton({ page = "review" }, Modifier.testTag("review-events")) {
                    Text("待确认记录复核 · ${game.entries.count{it.incomplete}}")
                }
                Text("补正局面不推算缺失过程，原有统计会保留并提示不完整。", fontSize = 12.sp)
            }
    }
}

@Composable
internal fun ManualSituationEditor(
    game: Game,
    busy: Boolean,
    close: () -> Unit,
    save: (Command) -> Unit,
) {
    var inning by rememberSaveable { mutableStateOf(game.inning.toString()) }
    var top by rememberSaveable { mutableStateOf(game.isTop) }
    var balls by rememberSaveable { mutableStateOf(game.balls.toString()) }
    var strikes by rememberSaveable { mutableStateOf(game.strikes.toString()) }
    var outs by rememberSaveable { mutableStateOf(game.outs.toString()) }
    var away by rememberSaveable { mutableStateOf(game.awayRuns.toString()) }
    var home by rememberSaveable { mutableStateOf(game.homeRuns.toString()) }
    var batter by rememberSaveable { mutableIntStateOf(game.batterIndex) }
    var runnersJSON by rememberSaveable { mutableStateOf(GameJson.encodeToString(game.bases)) }
    val runners = GameJson.decodeFromString<List<String?>>(runnersJSON)
    val batting = if (top) game.away else game.home
    val order = (if (top) game.awayOrder else game.homeOrder) ?: batting.players.map { it.id }
    val defense = if (top) game.home else game.away
    val positions =
        (if (top) game.homePositions else game.awayPositions)
            ?: defense.players
                .take(game.rules.fielders)
                .mapIndexed { i, p -> i + 1 to p.id }
                .toMap()
    var pitcher by rememberSaveable { mutableStateOf(game.pitcher.id) }
    val state = runCatching {
        Situation(
            inning.toInt(),
            top,
            balls.toInt(),
            strikes.toInt(),
            outs.toInt(),
            away.toInt(),
            home.toInt(),
            runners,
            batter,
            pitcher,
        )
    }.getOrNull()
    FormPage(
        "人工局面修正",
        busy,
        state != null,
        "save-situation",
        "保存",
        close,
        { save(Command("situation", situation = state)) },
    ) {
        Text("仅补正当前局面，缺失打席、安打和投球不会自动补算。", fontSize = 12.sp)
        Choices("攻守", mapOf("top" to "上半局", "bottom" to "下半局"), if (top) "top" else "bottom") {
            top = it == "top"
            batter = 0
            runnersJSON = GameJson.encodeToString(listOf<String?>(null, null, null))
            pitcher =
                ((if (top) game.homePositions else game.awayPositions)?.get(1)
                    ?: if (top) game.home.players.first().id else game.away.players.first().id)
        }
        listOf(
                "局数" to inning,
                "坏球" to balls,
                "好球" to strikes,
                "出局" to outs,
                "客队得分" to away,
                "主队得分" to home,
            )
            .forEachIndexed { i, (label, value) ->
                OutlinedTextField(
                    value,
                    { v ->
                        when (i) {
                            0 -> inning = v
                            1 -> balls = v
                            2 -> strikes = v
                            3 -> outs = v
                            4 -> away = v
                            5 -> home = v
                        }
                    },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("situation-$i"),
                )
            }
        Choices(
            "下一打者",
            order
                .mapIndexed { i, id ->
                    i.toString() to "${i+1} 棒 ${batting.players.first{it.id==id}.name}"
                }
                .toMap(),
            batter.toString(),
        ) {
            batter = it.toInt()
        }
        PlayerChoice("当前投手", defense.players.filter { it.id in positions.values }, pitcher) {
            pitcher = it
        }
        (0..2).forEach { base ->
            Choices(
                "${base+1} 垒",
                mapOf("" to "无人") +
                    order
                        .filter { it != order[batter] }
                        .associateWith { id ->
                            batting.players.first { it.id == id }.let { "#${it.number} ${it.name}" }
                        },
                runners[base].orEmpty(),
            ) { id ->
                runnersJSON =
                    GameJson.encodeToString(
                        runners.toMutableList().apply { set(base, id.takeIf { it.isNotBlank() }) }
                    )
            }
        }
    }
}

@Composable
internal fun DoubleSwitchEditor(
    game: Game,
    busy: Boolean,
    close: () -> Unit,
    save: (Command) -> Unit,
) {
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    var x by rememberSaveable { mutableStateOf("") }
    var y by rememberSaveable { mutableStateOf("") }
    var p by rememberSaveable { mutableStateOf("1") }
    var q by rememberSaveable { mutableStateOf("9") }
    val active = game.fieldingTeam.players.filter { it.id in game.fieldingPositions.values }
    val order = (if (game.isTop) game.homeOrder else game.awayOrder).orEmpty()
    val bench =
        game.fieldingTeam.players.filter {
            it.id !in game.fieldingPositions.values && it.id !in order
        }
    FormPage(
        "双重换人",
        busy,
        listOf(a, b, x, y).all { it.isNotBlank() },
        "save-double-switch",
        "保存",
        close,
        {
            save(
                Command(
                    "doubleSwitch",
                    playerID = a,
                    replacementID = x,
                    position = p.toInt(),
                    secondPlayerID = b,
                    secondReplacementID = y,
                    secondPosition = q.toInt(),
                )
            )
        },
    ) {
        Text("两名替补分别接管原棒次；守位可交叉调整。启用 DH 时请先处理投手／DH 关系。", fontSize = 12.sp)
        PlayerChoice("第一名退场", active, a) { a = it }
        PlayerChoice("第一名替补", bench, x) { x = it }
        Choices(
            "第一名守位",
            ScoringOptions.positions
                .take(game.rules.fielders)
                .mapIndexed { i, label -> (i + 1).toString() to label }
                .toMap(),
            p,
        ) {
            p = it
        }
        PlayerChoice("第二名退场", active, b) { b = it }
        PlayerChoice("第二名替补", bench, y) { y = it }
        Choices(
            "第二名守位",
            ScoringOptions.positions
                .take(game.rules.fielders)
                .mapIndexed { i, label -> (i + 1).toString() to label }
                .toMap(),
            q,
        ) {
            q = it
        }
    }
}

@Composable
private fun ReviewEditor(game: Game, busy: Boolean, close: () -> Unit, save: (Command) -> Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    val entry = game.entries.firstOrNull { it.id == selected && it.incomplete }
    if (entry == null)
        FormPage("待确认记录", busy, false, "unused", "", close, {}) {
            game.entries
                .filter { it.incomplete }
                .forEach { e ->
                    CardBlock {
                        Text("${e.inning}局${if(e.isTop)"上"else"下"} · ${e.text}")
                        TextButton({ selected = e.id }) { Text("复核记录") }
                    }
                }
            if (game.entries.none { it.incomplete }) Text("没有待确认记录")
        }
    else
        key(entry.id) {
            var title by rememberSaveable { mutableStateOf(entry.text) }
            var note by rememberSaveable { mutableStateOf("") }
            var outcome by rememberSaveable { mutableStateOf("") }
            var pid by rememberSaveable { mutableStateOf("") }
            val needs = entry.outcome in listOf(Outcome.pending, Outcome.pendingOut, Outcome.other)
            FormPage(
                "复核记录",
                busy,
                title.isNotBlank() && note.isNotBlank() && (!needs || outcome.isNotBlank()),
                "save-review",
                "确认",
                { selected = "" },
                {
                    save(
                        Command(
                            "review",
                            eventID = entry.id,
                            text = title,
                            reviewNote = note,
                            outcome = outcome.takeIf { it.isNotBlank() }?.let(Outcome::valueOf),
                            playerID = pid.takeIf { it.isNotBlank() },
                        )
                    )
                },
            ) {
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("确认后的中文记录") },
                    modifier = Modifier.fillMaxWidth().testTag("review-title"),
                )
                if (needs)
                    Choices(
                        "正式记分结果",
                        Outcome.entries
                            .filter {
                                it !in listOf(Outcome.pending, Outcome.pendingOut, Outcome.other)
                            }
                            .associate { it.name to it.title },
                        outcome,
                    ) {
                        outcome = it
                    }
                if (outcome == Outcome.error.name)
                    PlayerChoice(
                        "当时失误责任人",
                        (if (entry.isTop) game.home else game.away).players.filter { p ->
                            entry.participants.any { it.playerID == p.id }
                        },
                        pid,
                    ) {
                        pid = it
                    }
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text("复核说明") },
                    modifier = Modifier.fillMaxWidth().testTag("review-note"),
                )
                Text("正式结果必须与原打者去向一致；改变出局或垒况时，先用历史纠错重放。", fontSize = 12.sp)
            }
        }
}
