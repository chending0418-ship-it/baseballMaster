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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun NewGameDialog(
    teams: List<Team>,
    rosters: List<Team>,
    sharing: AndroidTeamSharing,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Game) -> Unit,
) {
    var ownID by rememberSaveable { mutableStateOf(teams.firstOrNull()?.id.orEmpty()) }
    var sharedJSON by rememberSaveable { mutableStateOf("") }
    var originJSON by rememberSaveable { mutableStateOf("") }
    var sharingChoiceJSON by rememberSaveable { mutableStateOf("") }
    var sharingChoiceExplicit by rememberSaveable { mutableStateOf(false) }
    val sharingChoice=remember(sharingChoiceJSON) {sharingChoiceJSON.takeIf {it.isNotEmpty()}?.let {GameJson.decodeFromString<PostgameSharingChoice>(it)}}
    val shared = remember(sharedJSON) { sharedJSON.takeIf { it.isNotBlank() }?.let { GameJson.decodeFromString<Team>(it) } }
    val origin = remember(originJSON) { originJSON.takeIf { it.isNotBlank() }?.let { GameJson.decodeFromString<SharedRosterOrigin>(it) } }
    val own = shared ?: teams.firstOrNull { it.id == ownID } ?: teams.firstOrNull()
    val scope = rememberCoroutineScope()
    var sourceBusy by remember { mutableStateOf(false) }
    var sharedTeams by remember { mutableStateOf<List<RemoteTeam>>(emptyList()) }
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
        listOf(own?.id, originJSON, sharingChoiceJSON, opponentID, opponentName, roster, modeName, ownHome, rules, scheduled, date)
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
                Text(if (prepared.sharedRosterOrigin != null) "名单来源：共享球队。比赛先完整保存在本机，名单快照不自动回写共享球队。" else "名单来源：本机球队。", fontSize = 12.sp)
                Text(when {sharingChoice==null -> "赛后共享目标：尚未选择";sharingChoice.teamID==null -> "赛后共享目标：仅本机";else -> "赛后共享目标：${sharingChoice.teamName}（结束后再次确认）"},Modifier.testTag("setup-postgame-summary"),fontSize=12.sp)
                Text("请核对主客、先发球员和守位。取消本页不会创建比赛。", fontSize = 12.sp)
            }
        return
    }
    FormPage(
        "新建比赛",
        busy || sourceBusy,
        own != null && (previous != null || opponentName.isNotBlank() && roster.isNotBlank()) &&
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
                val chosenOwn = requireNotNull(own) { "请先选择球队" }
                val activeOwn = chosenOwn.copy(players = chosenOwn.players.filter { !it.archived })
                MatchFactory.create(
                    UUID.randomUUID().toString(),
                    if (ownHome) activeOwn else opponent,
                    if (ownHome) opponent else activeOwn,
                    selectedRules,
                    chosenOwn.id,
                    scheduleDate,
                ).also { it.sharedRosterOrigin = origin; it.postgameSharingChoice=sharingChoice; it.validate() }
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
            if (teams.isNotEmpty()) RosterChoice(teams, if (shared == null) ownID else "") {
                ownID = it; sharedJSON = ""; originJSON = ""
            }
            if (shared != null) Text("共享名单 · ${shared.name}", Modifier.testTag("setup-shared-source"))
            TextButton(enabled = !busy && !sourceBusy, modifier = Modifier.testTag("setup-refresh-shared"), onClick = {
                sourceBusy = true; issue = null
                scope.launch {
                    try { sharedTeams = sharing.refresh().teams }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { issue = "共享名单暂不可用，可继续使用本机球队。" }
                    finally { sourceBusy = false }
                }
            }) { Text(if (sourceBusy) "读取中…" else "从共享球队选择名单") }
            sharedTeams.forEach { membership ->
                TextButton(enabled = !busy && !sourceBusy, modifier = Modifier.testTag("setup-shared-${membership.id}"), onClick = {
                    sourceBusy = true; issue = null
                    scope.launch {
                        try {
                            val profile = sharing.roster(membership.id, true)
                            val current = sharing.state().directory?.teams?.single { it.id == membership.id }
                                ?: error("当前无权读取该球队")
                            val snapshot = LocalMatchSource.sharedSnapshot(profile)
                            val provenance = SharedRosterOrigin.from(profile, current)
                            sharedJSON = GameJson.encodeToString(snapshot)
                            originJSON = GameJson.encodeToString(provenance)
                            if(!sharingChoiceExplicit) sharingChoiceJSON=if(current.role!=TeamRole.member) GameJson.encodeToString(PostgameSharingChoice(current.id,current.name)) else ""
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { issue = if (failure is TeamServiceFault || failure is IllegalArgumentException || failure is IllegalStateException) failure.message else "共享名单未读取，请重试。" }
                        finally { sourceBusy = false }
                    }
                }) { Text("${membership.name} · ${membership.role.title()}") }
            }
            Text(if (shared != null) "以当前共享名单保存本机比赛；全程本机记分，结束前不上传球队档案。" else "本机记分与直播，比赛结束后可再选择共享目标。", fontSize = 12.sp)
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
            Text("赛后共享目标",fontWeight=FontWeight.Bold)
            Text("比赛先完整保存在本机。这里只预设目标，结束保存后仍需单独确认上传。",fontSize=12.sp)
            Text(when {sharingChoice==null -> "尚未选择";sharingChoice.teamID==null -> "仅本机";else -> sharingChoice.teamName.orEmpty()},Modifier.testTag("setup-postgame-choice"))
            TextButton({sharingChoiceExplicit=true;sharingChoiceJSON=GameJson.encodeToString(PostgameSharingChoice())},Modifier.testTag("setup-postgame-local")) {Text("仅本机保留")}
            TextButton({sharingChoiceExplicit=true;sharingChoiceJSON=""},Modifier.testTag("setup-postgame-undecided")) {Text("结束后再选")}
            sharedTeams.filter {it.role!=TeamRole.member}.forEach {target ->
                TextButton({sharingChoiceExplicit=true;sharingChoiceJSON=GameJson.encodeToString(PostgameSharingChoice(target.id,target.name))},Modifier.testTag("setup-postgame-team-${target.id}")) {Text(target.name)}
            }
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
