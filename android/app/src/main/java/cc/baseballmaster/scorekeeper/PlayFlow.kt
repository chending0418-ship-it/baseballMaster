package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

/** The recording method belongs to the match; each ball has one local draft and one submit action. */
@Composable
internal fun PlayEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    initial: Command? = null,
    onNeedsCorrection: (() -> Unit)? = null,
    voiceConfirmation: Boolean = false,
    voiceTranscript: String? = null,
    save: (Command) -> Unit,
) {
    val pro = if (initial != null) initial.observation != null || initial.recordingMethod == "situation"
        else game.rules.recordingMethod == "situation"
    val partialVoice = voiceConfirmation && initial != null && initial.outcome == null
    var json by rememberSaveable(game.id, game.revision) {
        mutableStateOf(GameJson.encodeToString(initial ?: Command("play", outcome = Outcome.single,
            decisions = MatchEngine.suggested(game, Outcome.single))))
    }
    val cmd = remember(json) { GameJson.decodeFromString<Command>(json) }
    var stage by rememberSaveable(game.id, game.revision) {
        mutableStateOf(when {
            partialVoice -> "voiceOutcome"
            voiceConfirmation && initial?.observation?.precision == "unknown" -> "contact"
            initial == null -> "entry"
            else -> "review"
        })
    }
    var arrival by rememberSaveable(game.id, game.revision) {
        mutableIntStateOf(initial?.decisions?.firstOrNull { it.origin == 0 }?.destination ?: 1)
    }
    var typeChosen by rememberSaveable(game.id, game.revision) {
        mutableStateOf(initial?.observation?.let { !partialVoice || it.type != "unknown" } == true)
    }
    var issue by rememberSaveable(game.id, game.revision) { mutableStateOf<String?>(null) }
    var catalogReturnStage by rememberSaveable(game.id, game.revision) { mutableStateOf("entry") }
    var contactReturnStage by rememberSaveable(game.id, game.revision) { mutableStateOf("entry") }
    var contactFromReview by rememberSaveable(game.id, game.revision) { mutableStateOf(false) }
    var catalogSearch by rememberSaveable(game.id, game.revision) { mutableStateOf("") }

    fun update(transform: (Command) -> Command) {
        json = GameJson.encodeToString(transform(GameJson.decodeFromString(json)))
        issue = null
    }
    fun finalCommand(value: Command = cmd) = value.copy(
        recordingMethod = if (pro) "situation" else "traditional",
        observation = if (pro) value.observation ?: Observation() else null,
        timingRunCounts = if (MatchEngine.needsTiming(game, value)) value.timingRunCounts else null,
    )
    fun submit(value: Command = cmd) {
        val command = finalCommand(value)
        val contactError = if (pro) productObservationIssue(game, command.observation ?: Observation(), command.outcome ?: Outcome.other, typeChosen) else null
        val error = contactError ?: productCommandIssue(game, command)
        if (error == null) save(command) else { issue = error; stage = if (contactError != null) "contact" else "review" }
    }
    fun choose(outcome: Outcome) {
        productOutcomeUnavailable(game, outcome)?.let { issue = it; return }
        contactReturnStage = stage
        contactFromReview = false
        val selectedType = when (outcome) {
            Outcome.sacrificeBunt -> "bunt"
            Outcome.sacrificeFly -> "fly"
            else -> cmd.observation?.type?.takeIf { typeChosen } ?: when (outcome) {
                Outcome.groundOut -> "ground"
                Outcome.flyOut -> "fly"
                Outcome.lineOut -> "line"
                else -> null
            }
        }
        if (pro) typeChosen = selectedType != null
        val chosen = cmd.copy(
            outcome = outcome,
            observation = if (pro) (initial?.observation?.takeIf { partialVoice } ?: Observation())
                .copy(type = selectedType ?: "unknown") else null,
            fielding = if (!pro && productNeedsDefense(outcome)) Fielding(status = "") else null,
            decisions = MatchEngine.suggested(game, outcome).sortedBy { it.origin }.map { decision ->
                when {
                    partialVoice && decision.origin != 0 -> initial?.decisions.orEmpty()
                        .firstOrNull { it.origin == decision.origin && it.playerID == decision.playerID } ?: decision
                    !partialVoice && !pro && decision.origin == 0 -> decision.copy(destination = arrival)
                    else -> decision
                }
            },
            timingRunCounts = null,
        )
        update { chosen }
        stage = when {
            pro -> "contact"
            productNeedsDefense(outcome) -> "defense"
            partialVoice || productRequiresBasicReview(game, outcome) -> "review"
            else -> "cause"
        }
        if (stage == "cause") submit(chosen)
    }

    val observation = cmd.observation ?: Observation()
    val fieldIssue = productObservationIssue(game, observation, cmd.outcome ?: Outcome.other, typeChosen)
    val defenseIssue = cmd.fielding?.let { fielding ->
        when {
            fielding.status.isBlank() -> "请点选守备员，或明确标记待补。"
            cmd.outcome == Outcome.error && fielding.status != "pending" && fielding.errorPosition == null -> "请在球场点选失误责任人。"
            else -> runCatching { fielding.validate(game.rules) }.exceptionOrNull()?.message
        }
    }
    val command = finalCommand()
    val preview = if (stage == "review") runCatching { MatchEngine.apply(game, command) }.getOrNull() else null
    val reviewIssue = if (stage == "review") (if (pro) fieldIssue else null) ?: productCommandIssue(game, command) else null
    val requiresProReview = initial != null || game.bases.any { it != null } || cmd.outcome in
        listOf(Outcome.error, Outcome.fieldersChoice, Outcome.runnerTagOut, Outcome.infieldFly,
            Outcome.pending, Outcome.pendingOut, Outcome.other)
    val title = when (stage) {
        "entry" -> "记录击球"
        "voiceOutcome" -> "补选击球结果"
        "cause" -> "发生了什么？"
        "defense" -> "${cmd.outcome?.title.orEmpty()} · 守备"
        "contact" -> productOutcomeTitle(cmd.outcome ?: Outcome.other)
        "review" -> "核对本球"
        "process" -> "是什么球？"
        "processResult" -> "${ScoringOptions.ballTypes[observation.type].orEmpty()} · 结果"
        else -> "全部击球"
    }
    val close: () -> Unit = {
        issue = null
        when (stage) {
            "entry" -> dismiss()
            "voiceOutcome" -> dismiss()
            "contact" -> if (voiceConfirmation && !partialVoice && !contactFromReview) dismiss() else stage = if (contactFromReview) "review" else contactReturnStage
            "cause", "process" -> stage = "entry"
            "catalog" -> stage = catalogReturnStage
            "processResult" -> stage = "process"
            "defense" -> stage = if (partialVoice) contactReturnStage else "cause"
            else -> stage = if (pro) "contact" else if (productNeedsDefense(cmd.outcome ?: Outcome.other)) "defense" else if (partialVoice) contactReturnStage else "cause"
        }
    }
    ProductPage(
        title = title,
        busy = busy,
        onBack = close,
        backLabel = if (stage == "entry") "取消" else "返回",
        actionLabel = when (stage) {
            "contact" -> if (contactFromReview) "完成修改" else if (requiresProReview) "下一步 · 核对跑者" else "完成本球"
            "defense" -> if (partialVoice || productRequiresBasicReview(game, cmd.outcome ?: Outcome.other)) "下一步 · 核对跑者" else "确认本球"
            "review" -> if (initial == null || voiceConfirmation) "确认本球" else "保存本球修改"
            else -> ""
        },
        actionEnabled = when (stage) { "contact" -> fieldIssue == null; "defense" -> defenseIssue == null; "review" -> reviewIssue == null; else -> false },
        actionTag = when (stage) { "contact" -> if (contactFromReview || requiresProReview) "play-next" else "save-play"; "defense" -> "play-next"; else -> "save-play" },
        onAction = {
            when (stage) {
                "contact" -> if (fieldIssue != null) issue = fieldIssue else if (contactFromReview || requiresProReview) stage = "review" else submit()
                "defense" -> if (defenseIssue != null) issue = defenseIssue else if (partialVoice || productRequiresBasicReview(game, cmd.outcome ?: Outcome.other)) stage = "review" else submit()
                "review" -> submit()
            }
        },
        modal = true,
        scrollKey = stage,
    ) {
        when (stage) {
            "voiceOutcome" -> {
                ProductPlayContext(game)
                voiceTranscript?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("选择结果后可核对并修改跑者去向。")
                ProductOutcomeGrid(game, listOf(Outcome.single, Outcome.error, Outcome.fieldersChoice, Outcome.pending),
                    "voice-outcome", ::choose)
                ProductAuxiliaryAction("其他击球结果", "voice-all-outcomes") {
                    catalogReturnStage = "voiceOutcome"
                    stage = "catalog"
                }
            }
            "entry" -> {
                ProductPlayContext(game)
                if (pro) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("球型、落点、守备", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        TextButton({ catalogReturnStage = "entry"; stage = "catalog" }, Modifier.testTag("all-batted-outcomes")) { Text("全部击球", fontSize = 12.sp) }
                    }
                    ProductOutcomeGrid(game, listOf(Outcome.single, Outcome.double, Outcome.flyOut, Outcome.groundOut,
                        Outcome.error, Outcome.doublePlay), "pro-outcome", ::choose)
                    ProductAuxiliaryAction("按击球过程记录", "situation-by-process") { stage = "process" }
                    ProductAuxiliaryAction("结果待确认", "situation-pending-outcome") { choose(Outcome.other) }
                    Text("点落点，再点守备员；有跑者时统一核对。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("打者最后怎么样了？", fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("先点你看到的结果，不需要判断安打、失误或专业符号。", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProductArrivalGrid { destination -> arrival = destination; issue = null; stage = "cause" }
                    if (onNeedsCorrection != null) {
                        ProductAuxiliaryAction("刚才没看清，直接修正现场", "batted-ball-unclear", onNeedsCorrection)
                    } else {
                        ProductAuxiliaryAction("刚才没看清，先记待确认", "batted-ball-unclear") { choose(Outcome.other) }
                    }
                }
            }
            "cause" -> {
                ProductArrivalSummary(arrival)
                productCauses(game, arrival).forEach { cause ->
                    ProductActionRow(cause.title, cause.detail, tag = "cause-${cause.outcome.name}", enabled = !busy) { choose(cause.outcome) }
                }
            }
            "catalog" -> {
                ProductPlayContext(game)
                OutlinedTextField(catalogSearch, { catalogSearch = it },
                    Modifier.fillMaxWidth().testTag("outcome-catalog-search"),
                    placeholder = { Text("搜索：牺牲、野手选择、待确认…", fontSize = 13.sp) }, singleLine = true,
                    shape = RoundedCornerShape(12.dp))
                val groups = listOf(
                    "安打" to listOf(Outcome.single, Outcome.double, Outcome.triple, Outcome.homeRun),
                    "接杀与出局" to listOf(Outcome.groundOut, Outcome.flyOut, Outcome.lineOut, Outcome.foulFlyOut, Outcome.infieldFly),
                    "战术与多人出局" to listOf(Outcome.sacrificeBunt, Outcome.sacrificeFly, Outcome.doublePlay, Outcome.triplePlay),
                    "其他结果" to listOf(Outcome.error, Outcome.fieldersChoice, Outcome.runnerTagOut),
                    "待确认" to listOf(Outcome.pendingOut, Outcome.pending, Outcome.other),
                )
                var anyMatches = false
                groups.forEach { (label, outcomes) ->
                    val matches = outcomes.filter { catalogSearch.isBlank() || productOutcomeTitle(it).contains(catalogSearch.trim()) || it.title.contains(catalogSearch.trim()) }
                    if (matches.isNotEmpty()) {
                        anyMatches = true
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        ProductOutcomeGrid(game, matches, "pro-outcome", ::choose)
                    }
                }
                if (!anyMatches) Text("没有匹配结果，请试试失误或接杀。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "process" -> {
                ProductPlayContext(game)
                ProductBallTypeGrid(game, observation.type.takeIf { typeChosen }) { type ->
                    typeChosen = true
                    update { it.copy(observation = observation.copy(type = type)) }
                    stage = "processResult"
                }
            }
            "processResult" -> {
                ProductPlayContext(game)
                Text("${ScoringOptions.ballTypes[observation.type].orEmpty()} · 选择最后结果", Modifier.testTag("process-selected-type"),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val outcomes = when (observation.type) {
                    "ground", "hardGround" -> listOf(Outcome.single, Outcome.groundOut, Outcome.error, Outcome.fieldersChoice, Outcome.doublePlay)
                    "bunt" -> listOf(Outcome.sacrificeBunt, Outcome.single, Outcome.groundOut, Outcome.error, Outcome.fieldersChoice)
                    "line" -> listOf(Outcome.single, Outcome.lineOut, Outcome.double, Outcome.error)
                    else -> listOf(Outcome.single, Outcome.flyOut, Outcome.double, Outcome.sacrificeFly, Outcome.foulFlyOut, Outcome.error)
                }
                ProductOutcomeGrid(game, outcomes, "process-outcome", ::choose)
                ProductAuxiliaryAction("其他结果／全部击球", "process-all-outcomes") { catalogReturnStage = "processResult"; stage = "catalog" }
                ProductAuxiliaryAction("结果待确认", "process-pending-outcome") { choose(Outcome.other) }
            }
            "contact" -> {
                ProductObservationEditor(game, observation, cmd.outcome ?: Outcome.other,
                    onTypeChosen = { typeChosen = true }, typeSelected = typeChosen) { transform ->
                    update { current -> current.copy(observation = transform(current.observation ?: Observation())) }
                }
                fieldIssue?.takeUnless { it == productLocationSummary(observation) }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("contact-validation-message")) }
            }
            "defense" -> {
                Text("按现场传接顺序点选守备员；没看清时可以明确标记待补。", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProductFieldingEditor(game, cmd.fielding ?: Fielding(status = ""),
                    defaultErrorMode = cmd.outcome == Outcome.error) { transform ->
                    update { current -> current.copy(fielding = transform(current.fielding ?: Fielding(status = ""))) }
                }
                defenseIssue?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            "review" -> {
                Text(if (initial == null || voiceConfirmation) "尚未提交" else "修改刚才这球 · 保存后替换", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = ProductAmber)
                Text(productOutcomeTitle(cmd.outcome ?: Outcome.other) + if (pro) " · ${ScoringOptions.ballTypes[observation.type].orEmpty()}" else "",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (pro) {
                    observation.fielding?.let { Text(productFieldingSummary(game, it), fontSize = 12.sp) }
                    observation.fielding?.errorPosition?.let { position ->
                        Text("失误责任：${productFielderName(game, position)}", fontSize = 12.sp, color = ProductRed)
                    }
                    TextButton({ contactFromReview = true; stage = "contact" }, Modifier.testTag("edit-situation-field"),
                        contentPadding = PaddingValues(0.dp)) { Text("${productLocationSummary(observation)} · 修改球场记录", fontSize = 12.sp) }
                } else {
                    cmd.fielding?.let { fielding ->
                        Text(productFieldingSummary(game, fielding), fontSize = 12.sp)
                        TextButton({ stage = "defense" }, contentPadding = PaddingValues(0.dp)) { Text("修改守备路线", fontSize = 12.sp) }
                    }
                }
                ProductRunnerDecisions(game, cmd.decisions.orEmpty(), pro,
                    sharedFielding = observation.fielding ?: cmd.fielding) { transform ->
                    update { current -> current.copy(decisions = transform(current.decisions.orEmpty()), timingRunCounts = null) }
                }
                Text("进垒是建议值，按现场调整；点击姓名展开去向。", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (MatchEngine.needsTiming(game, cmd)) ProductTimingChoice(cmd.timingRunCounts) { timing -> update { it.copy(timingRunCounts = timing) } }
                if (preview != null) ProductPlayPreview(game, command, preview)
                reviewIssue?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.testTag("review-validation-message")) }
            }
        }
        if (stage != "entry") {
            TextButton(dismiss, Modifier.fillMaxWidth().testTag("cancel-play"), enabled = !busy) {
                Text("取消本球", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        issue?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("play-issue")) }
    }
}

@Composable
private fun ProductPlayContext(game: Game) {
    Text("第 ${game.inning} 局${if (game.isTop) "上" else "下"} · ${game.outs} 出局 · 打者 #${game.batter.number} ${game.batter.name}",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("play-context"))
}

@Composable
private fun ProductArrivalGrid(choose: (Int) -> Unit) {
    val values = listOf(-1, 1, 2, 3, 4)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { destination ->
                    val color = if (destination == -1) ProductRed else MaterialTheme.colorScheme.primary
                    Surface(onClick = { choose(destination) }, modifier = Modifier.weight(1f).heightIn(min = 92.dp).testTag("arrival-$destination"),
                        color = color.copy(alpha = .10f), contentColor = color, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            ProductArrivalIcon(destination, color)
                            Text(productArrivalTitle(destination), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProductArrivalIcon(destination: Int, color: Color) {
    when (destination) {
        -1 -> Surface(color = color, contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(29.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, null, Modifier.size(22.dp)) }
        }
        4 -> Icon(Icons.Default.Home, null, Modifier.size(29.dp), tint = color)
        else -> Surface(color = color, contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(29.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(destination.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ProductArrivalSummary(destination: Int) {
    val color = if (destination == -1) ProductRed else MaterialTheme.colorScheme.primary
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductArrivalIcon(destination, color)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("已记录：${productArrivalTitle(destination)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("再选最接近现场的一项", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProductOutcomeGrid(game: Game, values: List<Outcome>, prefix: String, choose: (Outcome) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { outcome ->
                    val reason = productOutcomeUnavailable(game, outcome)
                    val color = if (outcome.isHit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton({ choose(outcome) }, Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("$prefix-${outcome.name}"),
                            enabled = reason == null, shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = color),
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 12.dp)) {
                            Text(productOutcomeTitle(outcome), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                        reason?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("outcome-unavailable-${outcome.name}")) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One folded card per participant keeps the whole play readable before editing a particular runner. */
@Composable
internal fun ProductRunnerDecisions(
    game: Game,
    decisions: List<Decision>,
    pro: Boolean,
    sharedFielding: Fielding? = null,
    change: ((List<Decision>) -> List<Decision>) -> Unit,
) {
    val identity = decisions.map { it.playerID }.sorted().joinToString("|")
    var expanded by rememberSaveable(game.id, game.revision, identity) {
        mutableStateOf(if (decisions.size > 3) null else decisions.firstOrNull { it.origin != 0 }?.playerID ?: decisions.firstOrNull()?.playerID)
    }
    var routeEditor by rememberSaveable(game.id, game.revision, identity) { mutableStateOf<String?>(null) }
    decisions.sortedBy { it.origin }.forEach { decision ->
        val player = game.battingTeam.players.firstOrNull { it.id == decision.playerID }
        val name = player?.let { "#${it.number} ${it.name}" } ?: "球员"
        val open = expanded == decision.playerID
        fun modify(transform: (Decision) -> Decision) {
            change { current -> current.map { if (it.playerID == decision.playerID) transform(it) else it } }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(onClick = { expanded = if (open) null else decision.playerID }, color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("runner-card-${decision.origin}")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (decision.origin == 0) "打者" else "${decision.origin} 垒跑者", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(productDestinationTitle(decision.destination), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (decision.destination == -1) ProductRed else MaterialTheme.colorScheme.primary)
                        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                if (open) {
                    val options = linkedMapOf<String, String>()
                    if (decision.origin > 0) options["0"] = "留垒"
                    ((decision.origin + 1).coerceAtLeast(1)..3).forEach { options[it.toString()] = "${it} 垒" }
                    options["4"] = "得分"
                    options["-1"] = "出局"
                    ProductOptionGrid("最终位置-${decision.origin}", options, decision.destination.toString()) { destination ->
                        modify { current ->
                            val next = destination.toInt()
                            current.copy(destination = next,
                                cause = when {
                                    !pro -> current.cause
                                    next == 0 -> "hit"
                                    next == -1 && current.cause == "hit" -> "tag"
                                    next != -1 && current.cause in listOf("tag", "appeal") -> "hit"
                                    else -> current.cause
                                },
                                fielding = if (pro && next == 0) null else if (pro && next == -1 && current.cause == "hit") current.fielding ?: Fielding(status = "") else current.fielding)
                        }
                    }
                    if (pro && decision.destination != 0) {
                        ProductRunnerCauseMenu(decision) { cause ->
                            modify { current -> current.copy(cause = cause,
                                fielding = if (cause in listOf("relay", "error", "tag", "appeal")) current.fielding ?: Fielding(status = "") else null) }
                        }
                        if (decision.cause in listOf("relay", "error", "tag", "appeal")) {
                            TextButton({ routeEditor = if (routeEditor == decision.playerID) null else decision.playerID },
                                modifier = Modifier.testTag("runner-extra-route-${decision.origin}"), contentPadding = PaddingValues(0.dp)) {
                                Text("补充跑垒处理" + if (decision.fielding?.route?.isNotEmpty() == true) " · 已记录" else "", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            if (routeEditor == decision.playerID) {
                                if (sharedFielding != null && sharedFielding.route.isNotEmpty()) {
                                    ProductAuxiliaryAction("沿用本球路线：${sharedFielding.route.joinToString("-")}", "runner-reuse-route-${decision.origin}") {
                                        modify { it.copy(fielding = sharedFielding) }
                                        routeEditor = null
                                    }
                                }
                                ProductFieldingEditor(game, decision.fielding ?: Fielding(status = ""),
                                    defaultErrorMode = decision.cause == "error") { transform ->
                                    modify { it.copy(fielding = transform(it.fielding ?: Fielding(status = ""))) }
                                }
                                Text("本次跑垒处理独立保存，不改变原本的安打结果。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton({ routeEditor = null }, Modifier.testTag("runner-finish-route-${decision.origin}")) { Text("完成路线", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRunnerCauseMenu(decision: Decision, choose: (String) -> Unit) {
    var expanded by rememberSaveable(decision.playerID) { mutableStateOf(false) }
    val causes = linkedMapOf("hit" to "随本次击球", "relay" to "趁传进垒", "error" to "失误进垒",
        "award" to "裁判判给", "tag" to "进垒被触杀", "appeal" to "申诉出局", "pending" to "待确认")
        .filterKeys { if (decision.destination == -1) it in listOf("hit", "tag", "appeal", "pending") else it !in listOf("tag", "appeal") }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag("runner-cause-${decision.origin}"),
            shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text("变化原因", Modifier.weight(1f), textAlign = TextAlign.Start, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(causes[decision.cause] ?: "待确认", fontSize = 12.sp)
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            causes.forEach { (cause, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; choose(cause) },
                    modifier = Modifier.testTag("runner-cause-${decision.origin}-$cause"))
            }
        }
    }
}

@Composable
internal fun ProductTimingChoice(value: Boolean?, change: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("第三出局，核对得分", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("本球同时有第三出局和回本垒。封杀或打者到一垒前出局不计分；其他情况按现场先后确认。", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProductOptionGrid("得分先后", linkedMapOf("yes" to "得分在先 · 计分", "no" to "第三出局在先 · 不计分"),
            when (value) { true -> "yes"; false -> "no"; null -> null }, columns = 1) { change(it == "yes") }
    }
}

@Composable
private fun ProductPlayPreview(before: Game, command: Command, after: Game) {
    val decisions = command.decisions.orEmpty()
    val outs = minOf(3, before.outs + decisions.count { it.destination == -1 })
    val baseText = if (outs >= 3 || after.bases.none { it != null }) "垒上无人"
        else after.bases.mapIndexedNotNull { index, id -> if (id == null) null else "${index + 1} 垒" }.joinToString("、") + "有人"
    Text("记录后：${after.awayRuns} : ${after.homeRuns} · $outs 出局 · $baseText", fontSize = 12.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("play-save-preview"))
}

/** The runner menu starts with the event that happened, rather than a wall of event chips. */
@Composable
internal fun ProductRunnerEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    initial: Command? = null,
    save: (Command) -> Unit,
) {
    var json by rememberSaveable(game.id, game.revision) { mutableStateOf(initial?.let { GameJson.encodeToString(it) }.orEmpty()) }
    val command = remember(json) { json.takeIf { it.isNotEmpty() }?.let { GameJson.decodeFromString<Command>(it) } }
    var menu by rememberSaveable(game.id, game.revision) { mutableStateOf(initial == null) }
    val options = ScoringOptions.runnerEvents.filterKeys { ScoringOptions.runnerAllowed(game.rules.mode, it) }
    fun update(transform: (Command) -> Command) { json = GameJson.encodeToString(transform(GameJson.decodeFromString(json))) }
    val final = command?.let { it.copy(timingRunCounts = if (MatchEngine.needsTiming(game, it)) it.timingRunCounts else null) }
    val issue = if (!menu && final != null) productCommandIssue(game, final) else null
    val preview = if (!menu && final != null) runCatching { MatchEngine.apply(game, final) }.getOrNull() else null
    ProductPage(title = if (menu) "跑者／出局" else "跑者现在在哪？", busy = busy,
        onBack = { if (menu || initial != null) dismiss() else menu = true }, backLabel = if (menu) "取消" else "返回",
        actionLabel = if (menu) "" else if (initial == null) "确认跑者变化" else "保存跑者修改",
        actionEnabled = final != null && issue == null, actionTag = "save-runner",
        onAction = { if (final != null && issue == null) save(final) }, modal = true) {
        if (menu) {
            Surface(color = ProductAmber.copy(alpha = .10f), shape = RoundedCornerShape(12.dp)) {
                Text("这里只处理跑者变化；坏球或好球仍用主页面按钮记录。", Modifier.fillMaxWidth().padding(12.dp),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (game.bases.none { it != null }) {
                Text("垒上没有跑者", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("有跑者时才能记录跑垒事件。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val groups = listOf("进垒" to listOf("stolenBase", "doubleSteal", "delayedSteal", "relayAdvance", "defensiveErrorAdvance"),
                    "投捕失误与判给" to listOf("wildPitch", "passedBall", "uncertainLooseBall", "balk"),
                    "出局" to options.keys.filter { it !in listOf("stolenBase", "doubleSteal", "delayedSteal", "relayAdvance", "defensiveErrorAdvance", "wildPitch", "passedBall", "uncertainLooseBall", "balk") })
                // Use the sheet's usable width: some inner screens are narrower
                // than the full scoring workbench's 700dp breakpoint.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 520.dp) 3 else 1
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        groups.forEach { (title, kinds) ->
                            val shown = kinds.filter { it in options }
                            if (shown.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                shown.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { kind ->
                                            val choose = {
                                                json = GameJson.encodeToString(Command("runner", runnerKind = kind,
                                                    decisions = MatchEngine.suggestedRunner(game, kind)))
                                                menu = false
                                            }
                                            Box(Modifier.weight(1f)) {
                                                if (columns == 1) ProductActionRow(options.getValue(kind),
                                                    tag = "runner-event-$kind", enabled = !busy, onClick = choose)
                                                else Surface(onClick = choose, enabled = !busy,
                                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("runner-event-$kind"),
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                                                        Text(options.getValue(kind), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                                            textAlign = TextAlign.Center)
                                                    }
                                                }
                                            }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (command != null) {
            Text(options[command.runnerKind].orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("系统给出了常见去向；按现场实际位置点一下即可修改。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProductRunnerDecisions(game, command.decisions.orEmpty(), false) { transform ->
                update { it.copy(decisions = transform(it.decisions.orEmpty()), timingRunCounts = null) }
            }
            if (MatchEngine.needsTiming(game, command)) ProductTimingChoice(command.timingRunCounts) { timing -> update { it.copy(timingRunCounts = timing) } }
            if (preview != null && final != null) ProductPlayPreview(game, final, preview)
            issue?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            TextButton(dismiss, Modifier.fillMaxWidth().testTag("cancel-runner"), enabled = !busy) { Text("取消本次跑垒", fontSize = 12.sp) }
        }
    }
}

private data class ProductCause(val title: String, val detail: String, val outcome: Outcome)

private fun productCauses(game: Game, arrival: Int): List<ProductCause> = when (arrival) {
    -1 -> buildList {
        add(ProductCause("球被接住", "飞球或平飞球被接杀", Outcome.flyOut))
        add(ProductCause("平飞球接杀", "强劲平飞球被直接接住", Outcome.lineOut))
        add(ProductCause("界外飞球接杀", "界外区飞球被接住", Outcome.foulFlyOut))
        add(ProductCause("打者未到一垒前出局", "传一垒、封杀或触杀打者跑者", Outcome.groundOut))
        if (game.bases[0] != null && game.bases[1] != null && game.outs < 2)
            add(ProductCause("内野高飞必死", "打者立即出局，跑者仍按现场结果确认", Outcome.infieldFly))
        if (game.bases.any { it != null } && game.outs < 2) {
            add(ProductCause("一次出了两人", "这一球形成双杀", Outcome.doublePlay))
            if (game.rules.mode != GameMode.slowPitch) add(ProductCause("触击推进跑者", "打者出局，跑者推进", Outcome.sacrificeBunt))
        }
        if (game.bases.count { it != null } >= 2 && game.outs == 0) add(ProductCause("一次出了三人", "这一球形成三杀", Outcome.triplePlay))
        if (game.bases[2] != null && game.outs < 2) add(ProductCause("接杀后跑者得分", "高飞球接杀，跑者回本垒", Outcome.sacrificeFly))
        add(ProductCause("我不确定", "先记出局，赛后再确认", Outcome.pendingOut))
    }
    4 -> listOf(ProductCause("本垒打", "打出围墙或场内跑完全场", Outcome.homeRun),
        ProductCause("守备连续失误", "因为守备处理问题跑回本垒", Outcome.error),
        ProductCause("我不确定", "先记录得分，赛后再确认", Outcome.pending))
    else -> listOf(ProductCause("正常打上垒", "守备没有明显失误", when (arrival) { 2 -> Outcome.double; 3 -> Outcome.triple; else -> Outcome.single }),
        ProductCause("守备没处理好", "漏接、漏传或传球失误", Outcome.error),
        ProductCause("杀了其他跑者", "守备选择让打者上垒", Outcome.fieldersChoice),
        ProductCause("触杀其他跑者", "打者安全，其他跑者被触杀", Outcome.runnerTagOut),
        ProductCause("我不确定", "先保存垒况，赛后再确认", Outcome.pending))
}

internal fun productOutcomeUnavailable(game: Game, outcome: Outcome): String? = when (outcome) {
    Outcome.doublePlay -> if (game.outs >= 2) "已有两出局，不能记录双杀。" else if (game.bases.none { it != null }) "双杀需要垒上有跑者。" else null
    Outcome.triplePlay -> if (game.outs != 0) "三杀需要零出局。" else if (game.bases.count { it != null } < 2) "三杀需要至少两名跑者。" else null
    Outcome.sacrificeBunt -> if (game.rules.mode == GameMode.slowPitch) "慢垒不允许触击。" else if (game.outs >= 2) "牺牲触击需要少于两出局。" else if (game.bases.none { it != null }) "牺牲触击需要垒上有跑者。" else null
    Outcome.sacrificeFly -> if (game.outs >= 2) "牺牲高飞需要少于两出局。" else if (game.bases[2] == null) "牺牲高飞需要三垒有跑者。" else null
    Outcome.infieldFly -> if (game.outs >= 2) "内野高飞必死需要少于两出局。" else if (game.bases[0] == null || game.bases[1] == null) "需一、二垒均有跑者。" else null
    else -> null
}

private fun productOutcomeTitle(outcome: Outcome): String = when (outcome) {
    Outcome.flyOut -> "高飞接杀"
    Outcome.error -> "守备失误"
    Outcome.other -> "结果待确认"
    else -> outcome.title
}

private fun productArrivalTitle(destination: Int) = when (destination) {
    -1 -> "打者出局"; 1 -> "到一垒"; 2 -> "到二垒"; 3 -> "到三垒"; else -> "打者得分"
}
private fun productDestinationTitle(destination: Int) = when (destination) {
    -1 -> "出局"; 0 -> "留垒"; 4 -> "得分"; else -> "$destination 垒"
}
private fun productNeedsDefense(outcome: Outcome) = outcome in listOf(Outcome.error, Outcome.flyOut, Outcome.lineOut,
    Outcome.foulFlyOut, Outcome.groundOut, Outcome.infieldFly, Outcome.doublePlay, Outcome.triplePlay, Outcome.fieldersChoice, Outcome.runnerTagOut)
private fun productRequiresBasicReview(game: Game, outcome: Outcome) = game.bases.any { it != null } || outcome in
    listOf(Outcome.fieldersChoice, Outcome.runnerTagOut, Outcome.doublePlay, Outcome.triplePlay, Outcome.infieldFly, Outcome.sacrificeBunt, Outcome.sacrificeFly)

private fun productObservationIssue(game: Game, observation: Observation, outcome: Outcome, typeChosen: Boolean): String? {
    val fielding = observation.fielding
    return when {
        !typeChosen -> "请选择球型"
        outcome == Outcome.sacrificeBunt && observation.type != "bunt" -> "牺牲触击请将球型选为触击。"
        outcome == Outcome.sacrificeFly && observation.type !in setOf("fly", "line", "popup") -> "牺牲高飞请核对高飞、平飞或内野小飞球型。"
        observation.precision == "unknown" -> "请在球场点选落点，或展开区域选择"
        observation.precision == "point" && (observation.x == null || observation.y == null) -> "请在球场点选落点"
        observation.precision == "region" && observation.region == null -> "请选择大致区域"
        fielding == null || fielding.status.isBlank() -> "请点第一位处理球的守备员，或明确标记待补"
        fielding.status == "noFielding" && outcome != Outcome.homeRun -> "只有越过围墙的本垒打可选择无人处理"
        outcome == Outcome.error && fielding.status != "pending" && fielding.errorPosition == null -> "请在同一球场点选失误责任人"
        else -> runCatching { observation.validate(game.rules) }.exceptionOrNull()?.message
    }
}

private fun productCommandIssue(game: Game, command: Command): String? {
    val failure = runCatching { MatchEngine.apply(game, command) }.exceptionOrNull() ?: return null
    val message = failure.message
    return if (message.isNullOrBlank() || message == "Failed requirement.") "请核对本球的跑者去向和守备记录。" else message
}
