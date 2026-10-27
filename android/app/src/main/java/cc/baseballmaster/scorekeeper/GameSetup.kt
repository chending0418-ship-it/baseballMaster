package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
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
import java.util.UUID

@Composable
internal fun NewGameDialog(
    teams: List<Team>,
    rosters: List<Team>,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Game) -> Unit,
) {
    var ownID by rememberSaveable { mutableStateOf(teams.first().id) }
    val own = teams.firstOrNull { it.id == ownID } ?: teams.first()
    var opponentID by rememberSaveable { mutableStateOf("") }
    var opponentName by rememberSaveable { mutableStateOf("") }
    var roster by rememberSaveable { mutableStateOf("") }
    var modeName by rememberSaveable { mutableStateOf(GameMode.standard.name) }
    val mode = GameMode.valueOf(modeName)
    var ownHome by rememberSaveable { mutableStateOf(false) }
    var rules by remember(mode) { mutableStateOf<Rules?>(Rules(mode)) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable {
        mutableStateOf(formatMatchDate(System.currentTimeMillis(), "Asia/Shanghai"))
    }
    val scheduleDate =
        if (scheduled) runCatching { parseMatchDate(date, "Asia/Shanghai") }.getOrNull() else null
    val previous = rosters.firstOrNull { it.id == opponentID && !it.archived && it.isOpponent }
    val inputKey =
        listOf(own.id, opponentID, opponentName, roster, modeName, ownHome, rules, scheduled, date)
            .joinToString("|")
    var preparedJSON by rememberSaveable(inputKey) { mutableStateOf("") }
    var issue by remember { mutableStateOf<String?>(null) }
    var side by rememberSaveable { mutableStateOf("") }
    val prepared =
        remember(preparedJSON) {
            preparedJSON.takeIf { it.isNotBlank() }?.let { GameJson.decodeFromString<Game>(it) }
        }
    if (prepared != null) {
        if (side.isNotBlank())
            LineupEditor(prepared, busy, { side = "" }, initialHome = side == "home") { c ->
                val g = MatchEngine.apply(prepared, c)
                g.revision = 0
                g.checkpoint = null
                g.journal = emptyList()
                g.cursor = 0
                preparedJSON = GameJson.encodeToString(g)
                side = ""
            }
        else
            FormPage(
                "确认双方阵容",
                busy,
                true,
                "start-confirmed-game",
                if (prepared.isScheduled) "安排" else "开始",
                { preparedJSON = "" },
                { save(prepared) },
            ) {
                Text(
                    "${prepared.away.name} 对 ${prepared.home.name}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${prepared.rules.mode.title} · ${if(prepared.rules.recordingMethod=="situation")"Pro 版"else"基础版"} · ${prepared.rules.innings} 局",
                    fontSize = 13.sp,
                )
                listOf(false, true).forEach { home ->
                    val t = if (home) prepared.home else prepared.away
                    val order = if (home) prepared.homeOrder else prepared.awayOrder
                    val positions = if (home) prepared.homePositions else prepared.awayPositions
                    CardBlock {
                        Text(
                            if (home) "主队 · ${t.name}" else "客队 · ${t.name}",
                            fontWeight = FontWeight.Bold,
                        )
                        order.orEmpty().forEachIndexed { i, id ->
                            val p = t.players.first { it.id == id }
                            val pos = positions?.entries?.firstOrNull { it.value == id }?.key
                            Text(
                                "${i+1}. #${p.number} ${p.name} · ${pos?.let{ScoringOptions.positions[it-1]}?:"DH／额外打者"}",
                                fontSize = 13.sp,
                            )
                        }
                        TextButton(
                            { side = if (home) "home" else "away" },
                            Modifier.testTag(if (home) "setup-home-lineup" else "setup-away-lineup"),
                        ) {
                            Text("调整打序与守位")
                        }
                    }
                }
                Text("请核对主客、先发球员和守位。取消本页不会创建比赛。", fontSize = 12.sp)
            }
        return
    }
    FormPage(
        "新建比赛",
        busy,
        (previous != null || opponentName.isNotBlank() && roster.isNotBlank()) &&
            rules != null &&
            (!scheduled || scheduleDate != null),
        "start-game",
        "确认阵容",
        dismiss,
        {
            runCatching {
                val selectedRules = rules ?: error("请检查比赛规则")
                val opponent =
                    previous?.copy(players = previous.players.filter { !it.archived })
                        ?: Team(
                            UUID.randomUUID().toString(),
                            opponentName.trim(),
                            Roster.parse(roster),
                            true,
                        )
                val activeOwn = own.copy(players = own.players.filter { !it.archived })
                MatchFactory.create(
                    UUID.randomUUID().toString(),
                    if (ownHome) activeOwn else opponent,
                    if (ownHome) opponent else activeOwn,
                    selectedRules,
                    own.id,
                    scheduleDate,
                )
            }
                .onSuccess {
                    preparedJSON = GameJson.encodeToString(it)
                    issue = null
                }
                .onFailure { issue = it.message }
        },
    ) {
        CardBlock {
            Text("我的球队", fontWeight = FontWeight.Bold)
            RosterChoice(teams, ownID) { ownID = it }
            Text("对手", fontWeight = FontWeight.Bold)
            RosterChoice(rosters.filter { it.isOpponent && !it.archived }, opponentID, true) {
                opponentID = it
            }
            if (previous == null)
                OutlinedTextField(
                    opponentName,
                    { opponentName = it },
                    label = { Text("对手名称") },
                    modifier = Modifier.fillMaxWidth().testTag("opponent-name"),
                )
        }
        CardBlock {
            Text("比赛模式", fontWeight = FontWeight.Bold)
            Choices("模式", GameMode.entries.associate { it.name to it.title }, modeName) {
                modeName = it
            }
            Choices(
                "主客",
                mapOf("away" to "我方先攻", "home" to "我方后攻"),
                if (ownHome) "home" else "away",
            ) {
                ownHome = it == "home"
            }
        }
        CardBlock {
            Text("赛程与规则", fontWeight = FontWeight.Bold)
            Row {
                Checkbox(scheduled, { scheduled = it })
                Text("安排预告比赛", Modifier.padding(top = 13.dp))
            }
            if (scheduled)
                OutlinedTextField(date, { date = it }, label = { Text("预告时间 yyyy-MM-dd HH:mm") })
            RulesForm(Rules(mode)) { rules = it }
        }
        if (previous == null)
            CardBlock {
                Text("对方球员名单", fontWeight = FontWeight.Bold)
                Text("每行：中文名,英文名,背号；英文名可省略，多个背号用 / 分隔。", fontSize = 12.sp)
                OutlinedTextField(
                    roster,
                    { roster = it },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().testTag("opponent-roster"),
                )
                TextButton({ roster = (1..12).joinToString("\n") { "球员$it,$it" } }) {
                    Text("填入示例名单")
                }
            }
        if (issue != null) Text(issue.orEmpty(), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RosterChoice(
    teams: List<Team>,
    selected: String,
    allowNew: Boolean = false,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            { open = true },
            Modifier.testTag(if (allowNew) "setup-opponent" else "setup-own-team"),
        ) {
            Text(teams.firstOrNull { it.id == selected }?.name ?: if (allowNew) "新建对手" else "选择球队")
        }
        DropdownMenu(
            open,
            { open = false },
            Modifier.heightIn(max = 360.dp).semantics { testTagsAsResourceId = true },
        ) {
            if (allowNew)
                DropdownMenuItem(
                    text = { Text("新建对手") },
                    onClick = {
                        change("")
                        open = false
                    },
                )
            teams.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name) },
                    onClick = {
                        change(t.id)
                        open = false
                    },
                    modifier = Modifier.testTag("choose-roster-${t.id}"),
                )
            }
        }
    }
}
