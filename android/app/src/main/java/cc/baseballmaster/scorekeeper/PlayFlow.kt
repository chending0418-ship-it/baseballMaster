package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

/** iOS basic arrival → cause → runners; Pro result/process → contact → review. */
@Composable
internal fun PlayEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    initial: Command? = null,
    save: (Command) -> Unit,
) {
    var json by
        rememberSaveable(game.id, game.revision) {
            mutableStateOf(
                GameJson.encodeToString(
                    initial
                        ?: Command(
                            "play",
                            outcome = Outcome.single,
                            decisions = MatchEngine.suggested(game, Outcome.single),
                        )
                )
            )
        }
    val cmd = remember(json) { GameJson.decodeFromString<Command>(json) }
    val initialPro =
        if (initial != null) initial.observation != null || initial.recordingMethod == "situation"
        else game.rules.recordingMethod == "situation"
    var pro by rememberSaveable { mutableStateOf(initialPro) }
    var stage by rememberSaveable {
        mutableStateOf(if (initial != null) if (initialPro) "contact" else "review" else "entry")
    }
    var arrival by rememberSaveable {
        mutableIntStateOf(initial?.decisions?.firstOrNull { it.origin == 0 }?.destination ?: 1)
    }
    var typeChosen by rememberSaveable { mutableStateOf(initial?.observation != null) }
    var issue by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(f: (Command) -> Command) {
        json = GameJson.encodeToString(f(GameJson.decodeFromString(json)))
        issue = null
    }
    fun choose(outcome: Outcome) {
        update {
            it.copy(
                outcome = outcome,
                decisions =
                    MatchEngine.suggested(game, outcome).map { d ->
                        if (!pro && d.origin == 0) d.copy(destination = arrival) else d
                    },
                timingRunCounts = null,
            )
        }
        stage = if (pro) "contact" else "review"
    }
    val observation = cmd.observation ?: Observation()
    val fieldIssue =
        when {
            !typeChosen -> "请选择球型"
            observation.precision == "unknown" -> "请点选落点，或选择大致区域"
            observation.precision == "point" && (observation.x == null || observation.y == null) ->
                "请点选球场落点"
            observation.precision == "region" && observation.region == null -> "请选择大致区域"
            observation.fielding == null || observation.fielding?.status.isNullOrBlank() ->
                "请点第一位处理球的人，或标记守备待补"
            else -> runCatching { observation.validate(game.rules) }.exceptionOrNull()?.message
        }
    fun finalCommand() =
        cmd.copy(
            recordingMethod = if (pro) "situation" else "traditional",
            observation = if (pro) observation else null,
            timingRunCounts = if (MatchEngine.needsTiming(game, cmd)) cmd.timingRunCounts else null,
        )
    val reviewIssue =
        if (stage == "review")
            runCatching { MatchEngine.apply(game, finalCommand()) }.exceptionOrNull()?.message
        else null
    val close: () -> Unit = {
        when (stage) {
            "entry" -> dismiss()
            "cause",
            "catalog",
            "process" -> stage = "entry"
            "contact" -> stage = "entry"
            else -> stage = if (pro) "contact" else "cause"
        }
    }
    FormPage(
        when (stage) {
            "entry" -> if (pro) "记录击球" else "打者最后怎么样了？"
            "cause" -> "为什么到这里？"
            "contact" -> "落点与守备"
            "review" -> "核对本球"
            else -> "全部击球"
        },
        busy,
        if (stage == "contact") fieldIssue == null
        else if (stage == "review") reviewIssue == null else false,
        if (stage == "review") "save-play" else "play-next",
        if (stage == "review") "记录" else if (stage == "contact") "核对跑者" else "",
        close,
        {
            if (stage == "contact") {
                if (fieldIssue == null) stage = "review" else issue = fieldIssue
            } else if (stage == "review") {
                if (reviewIssue == null) save(finalCommand()) else issue = reviewIssue
            }
        },
    ) {
        if (stage == "entry") {
            Choices("记分方式", mapOf("base" to "基础版", "pro" to "Pro 版"), if (pro) "pro" else "base") {
                pro = it == "pro"
                update { c ->
                    c.copy(observation = if (pro) c.observation ?: Observation() else null)
                }
            }
            CardBlock {
                Text(
                    "${game.inning}局${if(game.isTop)"上"else"下"} · ${game.outs} 出局",
                    fontSize = 12.sp,
                )
                Text(
                    "#${game.batter.number} ${game.batter.name}",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!pro) {
                Text("先点你看到的结果，不需要先判断安打、失误或专业符号。", fontSize = 13.sp)
                listOf(-1 to "打者出局", 1 to "到一垒", 2 to "到二垒", 3 to "到三垒", 4 to "打者得分")
                    .chunked(2)
                    .forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (dest, label) ->
                                OutlinedButton(
                                    {
                                        arrival = dest
                                        stage = "cause"
                                    },
                                    Modifier.weight(1f)
                                        .heightIn(min = 62.dp)
                                        .testTag("arrival-$dest"),
                                ) {
                                    Text(label, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
            } else {
                Text("这次击球最后是什么结果？", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                listOf(
                        Outcome.single,
                        Outcome.double,
                        Outcome.flyOut,
                        Outcome.groundOut,
                        Outcome.error,
                        Outcome.doublePlay,
                    )
                    .chunked(2)
                    .forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { o ->
                                OutlinedButton(
                                    { choose(o) },
                                    Modifier.weight(1f)
                                        .heightIn(min = 58.dp)
                                        .testTag("pro-outcome-${o.name}"),
                                ) {
                                    Text(o.title)
                                }
                            }
                        }
                    }
                TextButton({ stage = "catalog" }, Modifier.testTag("all-batted-outcomes")) {
                    Text("全部击球")
                }
                TextButton({ stage = "process" }, Modifier.testTag("situation-by-process")) {
                    Text("从看到的过程开始")
                }
                TextButton({ choose(Outcome.other) }) { Text("结果待确认") }
                Text("点落点，再点守备员；有跑者时统一核对。", fontSize = 12.sp)
            }
        } else if (stage == "cause") {
            val choices =
                if (arrival == -1)
                    buildList {
                        add(Outcome.flyOut to "球被接住")
                        add(Outcome.lineOut to "平飞球接杀")
                        add(Outcome.foulFlyOut to "界外飞球接杀")
                        add(Outcome.groundOut to "打者未到一垒前出局")
                        if (game.bases[0] != null && game.bases[1] != null && game.outs < 2)
                            add(Outcome.infieldFly to "内野高飞必死")
                        if (game.bases.any { it != null } && game.outs < 2) {
                            add(Outcome.doublePlay to "一次出了两人")
                            if (game.rules.mode != GameMode.slowPitch)
                                add(Outcome.sacrificeBunt to "触击推进跑者")
                        }
                        if (game.bases.count { it != null } >= 2 && game.outs == 0)
                            add(Outcome.triplePlay to "一次出了三人")
                        if (game.bases[2] != null && game.outs < 2)
                            add(Outcome.sacrificeFly to "接杀后跑者得分")
                        add(Outcome.pendingOut to "我不确定")
                    }
                else if (arrival == 4)
                    listOf(
                        Outcome.homeRun to "本垒打",
                        Outcome.error to "守备连续失误",
                        Outcome.pending to "我不确定",
                    )
                else
                    listOf(
                        (when (arrival) {
                            2 -> Outcome.double
                            3 -> Outcome.triple
                            else -> Outcome.single
                        }) to "正常打上垒",
                        Outcome.error to "守备没处理好",
                        Outcome.fieldersChoice to "杀了其他跑者",
                        Outcome.runnerTagOut to "触杀其他跑者",
                        Outcome.pending to "我不确定",
                    )
            choices.forEach { (outcome, label) ->
                OutlinedButton(
                    { choose(outcome) },
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cause-${outcome.name}"),
                ) {
                    Column {
                        Text(label, fontWeight = FontWeight.Bold)
                        Text(outcome.title, fontSize = 12.sp)
                    }
                }
            }
        } else if (stage == "catalog") {
            Outcome.entries
                .filter { game.rules.mode != GameMode.slowPitch || it != Outcome.sacrificeBunt }
                .forEach { o ->
                    TextButton({ choose(o) }, Modifier.testTag("pro-outcome-${o.name}")) {
                        Text(o.title)
                    }
                }
        } else if (stage == "process") {
            Text("先选看到的球型，再选择最后结果。", fontSize = 13.sp)
            Choices(
                "球型",
                ScoringOptions.ballTypes.filterKeys {
                    game.rules.mode != GameMode.slowPitch || it != "bunt"
                },
                observation.type,
            ) { type ->
                typeChosen = true
                update { it.copy(observation = observation.copy(type = type)) }
            }
            Outcome.entries
                .filter { game.rules.mode != GameMode.slowPitch || it != Outcome.sacrificeBunt }
                .forEach { o -> TextButton({ choose(o) }) { Text(o.title) } }
        } else if (stage == "contact") {
            Text(cmd.outcome!!.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            ObservationEditor(
                game,
                observation,
                cmd.outcome!!,
                onTypeChosen = { typeChosen = true },
            ) { f ->
                update { it.copy(observation = f(it.observation ?: Observation())) }
            }
            if (fieldIssue != null)
                Text(fieldIssue, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "${cmd.outcome!!.title} · 核对所有跑者最终位置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            if (pro) {
                Text(
                    "${ScoringOptions.ballTypes[observation.type]} · ${if(observation.precision=="point")"已点选落点"else ScoringOptions.regions[observation.region].orEmpty()}",
                    fontSize = 13.sp,
                )
                TextButton({ stage = "contact" }) { Text("修改落点与守备") }
            } else if (
                cmd.outcome == Outcome.error ||
                    cmd.outcome!!.name.endsWith("Out") ||
                    cmd.outcome in listOf(Outcome.doublePlay, Outcome.triplePlay)
            ) {
                Text("守备路线（不知道可标记待补）", fontWeight = FontWeight.Bold)
                FieldingEditor(
                    game,
                    cmd.fielding ?: Fielding(status = "pending"),
                    cmd.outcome == Outcome.homeRun,
                ) { f ->
                    update { it.copy(fielding = f(it.fielding ?: Fielding(status = "pending"))) }
                }
            }
            RunnerDecisions(game, cmd.decisions.orEmpty(), pro) { f ->
                update { it.copy(decisions = f(it.decisions.orEmpty()), timingRunCounts = null) }
            }
            if (MatchEngine.needsTiming(game, cmd))
                TimingChoice(cmd.timingRunCounts) { value ->
                    update { it.copy(timingRunCounts = value) }
                }
            if (reviewIssue != null)
                Text(reviewIssue, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Text("取消不会增加投球、打席或统计。", fontSize = 12.sp)
        }
        if (stage != "entry") TextButton(dismiss, Modifier.testTag("cancel-play")) { Text("取消本球") }
        if (issue != null) Text(issue.orEmpty(), color = MaterialTheme.colorScheme.error)
    }
}
