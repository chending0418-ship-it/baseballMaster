package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.shape.RoundedCornerShape
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
    manageOpponents: () -> Unit = {},
    initialSchedule: Game? = null,
    save: (Game) -> Unit,
) {
    var recordType by rememberSaveable { mutableStateOf(if(initialSchedule?.isObservation == true) "spectator" else "team") }
    var recordingMethod by rememberSaveable { mutableStateOf(initialSchedule?.rules?.recordingMethod ?: "traditional") }
    val opponents = rosters.filter { it.isOpponent && !it.archived && it.source == "local" }
    var spectatorAwayID by rememberSaveable { mutableStateOf(initialSchedule?.away?.id ?: opponents.firstOrNull()?.id.orEmpty()) }
    var spectatorHomeID by rememberSaveable { mutableStateOf(initialSchedule?.home?.id ?: opponents.getOrNull(1)?.id.orEmpty()) }
    val spectatorAway = opponents.firstOrNull { it.id == spectatorAwayID }
    val spectatorHome = opponents.firstOrNull { it.id == spectatorHomeID }
    val zoneID = rememberSaveable { initialSchedule?.playedTimeZoneID ?: java.util.TimeZone.getDefault().id }
    var backfill by rememberSaveable { mutableStateOf(false) }
    var ownID by rememberSaveable { mutableStateOf(initialSchedule?.ourTeamID ?: teams.firstOrNull()?.id.orEmpty()) }
    var sharedJSON by rememberSaveable { mutableStateOf(initialSchedule?.takeIf { it.sharedRosterOrigin != null }?.let { g -> GameJson.encodeToString(if(g.home.id == g.ourTeamID) g.home else g.away) }.orEmpty()) }
    var originJSON by rememberSaveable { mutableStateOf(initialSchedule?.sharedRosterOrigin?.let { GameJson.encodeToString(it) }.orEmpty()) }
    var sharingChoiceJSON by rememberSaveable { mutableStateOf(initialSchedule?.postgameSharingChoice?.let { GameJson.encodeToString(it) }.orEmpty()) }
    var sharingChoiceExplicit by rememberSaveable { mutableStateOf(false) }
    val sharingChoice=remember(sharingChoiceJSON) {sharingChoiceJSON.takeIf {it.isNotEmpty()}?.let {GameJson.decodeFromString<PostgameSharingChoice>(it)}}
    val shared = remember(sharedJSON) { sharedJSON.takeIf { it.isNotBlank() }?.let { GameJson.decodeFromString<Team>(it) } }
    val origin = remember(originJSON) { originJSON.takeIf { it.isNotBlank() }?.let { GameJson.decodeFromString<SharedRosterOrigin>(it) } }
    val own = shared ?: teams.firstOrNull { it.id == ownID } ?: if(initialSchedule == null) teams.firstOrNull() else null
    val scope = rememberCoroutineScope()
    var sourceBusy by remember { mutableStateOf(false) }
    var sharedTeams by remember { mutableStateOf<List<RemoteTeam>>(emptyList()) }
    var opponentID by rememberSaveable { mutableStateOf(initialSchedule?.let { if(it.home.id == it.ourTeamID) it.away.id else it.home.id }.orEmpty()) }
    var opponentName by rememberSaveable { mutableStateOf("") }
    var roster by rememberSaveable { mutableStateOf("") }
    var modeName by rememberSaveable { mutableStateOf(initialSchedule?.rules?.mode?.name ?: GameMode.standard.name) }
    val mode = GameMode.valueOf(modeName)
    var ownHome by rememberSaveable { mutableStateOf(initialSchedule != null && initialSchedule.home.id == initialSchedule.ourTeamID) }
    val baselineRules = initialSchedule?.rules?.takeIf { it.mode == mode } ?: Rules(mode)
    var rules by remember(mode) { mutableStateOf<Rules?>(baselineRules) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var configureScheduleNow by rememberSaveable { mutableStateOf(false) }
    val basicSchedule = scheduled && !configureScheduleNow
    var date by rememberSaveable {
        mutableStateOf(formatMatchDate(System.currentTimeMillis() + 86_400_000, zoneID))
    }
    var backfillDateText by rememberSaveable { mutableStateOf(formatMatchDate(System.currentTimeMillis(), zoneID)) }
    val scheduleDate =
        if (scheduled) runCatching { parseMatchDate(date, zoneID) }.getOrNull() else null
    val playedDate = if (backfill && !scheduled) runCatching { parseMatchDate(backfillDateText, zoneID) }.getOrNull() else null
    val previous = rosters.firstOrNull { it.id == opponentID && !it.archived && it.isOpponent }
    val inputKey =
        listOf(own?.id, originJSON, sharingChoiceJSON, opponentID, opponentName, roster, modeName, ownHome, rules, scheduled, date, recordType, spectatorAwayID, spectatorHomeID, recordingMethod, backfill, backfillDateText, zoneID, configureScheduleNow)
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
                if(recordType == "spectator") Text("观赛记录 · 不计入本队统计", fontSize = 12.sp)
                else Text(when {sharingChoice==null -> "赛后共享：尚未选择";sharingChoice.teamID==null -> "赛后共享：仅本机";else -> "赛后共享：${sharingChoice.teamName}（赛后确认）"},Modifier.testTag("setup-postgame-summary"),fontSize=12.sp)
            }
        return
    }
    FormPage(
        if(initialSchedule == null) "新建比赛" else "预告赛前设置",
        busy || sourceBusy,
        (if(recordType == "spectator") spectatorAway != null && spectatorHome != null && spectatorAway.id != spectatorHome.id
         else own != null && (previous != null || opponentName.isNotBlank() && (basicSchedule || roster.isNotBlank()))) &&
            (basicSchedule || rules != null) && (!scheduled || scheduleDate != null && scheduleDate > System.currentTimeMillis()) && (!backfill || playedDate != null && playedDate <= System.currentTimeMillis()),
        "start-game",
        if(basicSchedule) "保存安排" else "确认阵容",
        dismiss,
        {
            runCatching {
                val selectedRules = (rules ?: if(basicSchedule) Rules(mode) else error("请检查比赛规则")).copy(recordingMethod = recordingMethod)
                val gameID = initialSchedule?.id ?: UUID.randomUUID().toString()
                fun build(home: Team, away: Team, ownTeamID: String?): Game =
                    if(basicSchedule) MatchFactory.schedule(gameID, home, away, selectedRules, requireNotNull(scheduleDate), ownTeamID)
                    else MatchFactory.create(gameID, home, away, selectedRules, ownTeamID, scheduleDate)
                val game = if(recordType == "spectator") {
                    val away = requireNotNull(spectatorAway) { "请选择观赛客队" }
                    val home = requireNotNull(spectatorHome) { "请选择观赛主队" }
                    require(away.id != home.id) { "观赛客队和主队不能相同" }
                    build(home.copy(players = home.players.filter { !it.archived }), away.copy(players = away.players.filter { !it.archived }), null)
                } else {
                    val opponent = previous?.copy(players = previous.players.filter { !it.archived })
                        ?: Team(UUID.randomUUID().toString(), opponentName.trim(), if(basicSchedule && roster.isBlank()) emptyList() else Roster.parse(roster), true)
                    val chosenOwn = requireNotNull(own) { "请先选择球队" }
                    val activeOwn = chosenOwn.copy(players = chosenOwn.players.filter { !it.archived })
                    build(if(ownHome) activeOwn else opponent, if(ownHome) opponent else activeOwn, chosenOwn.id)
                }
                game.also {
                    it.sharedRosterOrigin = if(recordType == "team") origin else null
                    it.postgameSharingChoice = if(recordType == "team") sharingChoice else null
                    if(initialSchedule != null) {
                        it.scheduledAt = initialSchedule.scheduledAt
                        it.createdAt = initialSchedule.createdAt
                    }
                    it.playedTimeZoneID = zoneID
                    it.playedAt = playedDate
                    it.validate()
                }
            }
                .onSuccess {
                    if(basicSchedule) save(it) else preparedJSON = GameJson.encodeToString(it)
                    issue = null
                }
                .onFailure { issue = it.message }
        },
    ) {
        Choices("记分方式", mapOf("traditional" to "基础版", "situation" to "Pro 版"), recordingMethod) { recordingMethod = it }
        Text("参赛球队", fontWeight = FontWeight.Bold)
        if(initialSchedule == null) Choices("记录类型", mapOf("team" to "本队比赛", "spectator" to "观赛记录"), recordType) { recordType = it }
        if(recordType == "spectator") CardBlock {
            Text("客队 · 先攻", fontWeight = FontWeight.Bold)
            if(initialSchedule != null) Text(spectatorAway?.name ?: initialSchedule.away.name) else RosterChoice(opponents, spectatorAwayID, tag = "setup-spectator-away") { spectatorAwayID = it }
            Text("主队 · 后攻", fontWeight = FontWeight.Bold)
            if(initialSchedule != null) Text(spectatorHome?.name ?: initialSchedule.home.name) else RosterChoice(opponents, spectatorHomeID, tag = "setup-spectator-home") { spectatorHomeID = it }
            if(opponents.size < 2) Text("观赛记录至少需要两支对手球队及名单。", fontSize = 12.sp)
            if(spectatorAway != null && spectatorAwayID == spectatorHomeID) Text("客队和主队不能相同", color = MaterialTheme.colorScheme.error)
        } else {
            CardBlock {
                Text("我的球队", fontWeight = FontWeight.Bold)
                if(initialSchedule != null) Text(own?.name.orEmpty())
                else if (teams.isNotEmpty()) RosterChoice(teams, if (shared == null) ownID else "") {
                    ownID = it; sharedJSON = ""; originJSON = ""
                }
                if (shared != null) Text("共享名单 · ${shared.name}", Modifier.testTag("setup-shared-source"))
                if(initialSchedule == null || shared != null) TextButton(enabled = !busy && !sourceBusy, modifier = Modifier.testTag("setup-refresh-shared"), onClick = {
                    sourceBusy = true; issue = null
                    scope.launch {
                        try { sharedTeams = sharing.refresh().teams }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { issue = "共享名单暂不可用，可继续使用本机球队。" }
                        finally { sourceBusy = false }
                    }
                }) { Text(if (sourceBusy) "读取中…" else "选择共享名单") }
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
                Text("对手", fontWeight = FontWeight.Bold)
                if(initialSchedule != null) Text(previous?.name.orEmpty()) else RosterChoice(rosters.filter { it.isOpponent && !it.archived }, opponentID, true) {
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
        }
        TextButton(manageOpponents, Modifier.testTag("setup-manage-opponents")) { Text("管理对手球队和名单") }
        if(recordType == "team") {
            CardBlock {
                Text("赛后共享",fontWeight=FontWeight.Bold)
                Text(when {sharingChoice==null -> "尚未选择";sharingChoice.teamID==null -> "仅本机";else -> sharingChoice.teamName.orEmpty()},Modifier.testTag("setup-postgame-choice"))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({sharingChoiceExplicit=true;sharingChoiceJSON=GameJson.encodeToString(PostgameSharingChoice())},Modifier.weight(1f).testTag("setup-postgame-local")) {Text("仅本机保留")}
                    TextButton({sharingChoiceExplicit=true;sharingChoiceJSON=""},Modifier.weight(1f).testTag("setup-postgame-undecided")) {Text("赛后再选")}
                }
                sharedTeams.filter {it.role!=TeamRole.member}.forEach {target ->
                    TextButton({sharingChoiceExplicit=true;sharingChoiceJSON=GameJson.encodeToString(PostgameSharingChoice(target.id,target.name))},Modifier.testTag("setup-postgame-team-${target.id}")) {Text(target.name)}
                }
            }
        }
        CardBlock {
            Text("比赛模式", fontWeight = FontWeight.Bold)
            Choices("模式", GameMode.entries.associate { it.name to it.title }, modeName) {
                modeName = it
            }
            if(recordType == "team") Choices(
                "主客",
                mapOf("away" to "我方先攻", "home" to "我方后攻"),
                if (ownHome) "home" else "away",
            ) {
                ownHome = it == "home"
            }
        }
        CardBlock {
            Text("赛程与规则", fontWeight = FontWeight.Bold)
            if(initialSchedule == null) Row {
                Checkbox(scheduled, { scheduled = it; if(it) backfill = false }, Modifier.testTag("setup-scheduled-toggle"))
                Text("安排预告比赛", Modifier.padding(top = 13.dp))
            }
            if (!scheduled && initialSchedule == null) Row {
                Checkbox(backfill, { backfill = it }, Modifier.testTag("backfill-game-toggle"))
                Text("补填过去的比赛", Modifier.padding(top = 13.dp))
            }
            if (scheduled || backfill)
                OutlinedTextField(if(scheduled) date else backfillDateText, { if(scheduled) date = it else backfillDateText = it }, label = { Text(if(scheduled) "预告时间 yyyy-MM-dd HH:mm" else "比赛实际时间 yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth().testTag("setup-match-date"))
            if(scheduled) {
                Row {
                    Checkbox(configureScheduleNow, { configureScheduleNow = it }, Modifier.testTag("configure-schedule-now"))
                    Text("现在配置名单、规则和阵容", Modifier.padding(top = 13.dp))
                }
                if(basicSchedule) Text("先保存双方队伍和时间，开赛前再补充名单与比赛设置。", fontSize = 12.sp)
            }
            if(!basicSchedule) RulesForm(baselineRules, showRecordingMethod = false) { rules = it }
        }
        if (recordType == "team" && previous == null && !basicSchedule)
            CardBlock {
                Text("对方球员名单", fontWeight = FontWeight.Bold)
                Text("每行：姓名,背号（可加英文名）", fontSize = 12.sp)
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
    tag: String? = null,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Box {
        OutlinedButton(
            { query = ""; open = true },
            Modifier.testTag(tag ?: if (allowNew) "setup-opponent" else "setup-own-team"),
         shape = RoundedCornerShape(14.dp)) {
            Text(teams.firstOrNull { it.id == selected }?.name ?: if (allowNew) "新建对手" else "选择球队")
        }
        DropdownMenu(
            open,
            { open = false },
            Modifier.heightIn(max = 360.dp).semantics { testTagsAsResourceId = true },
        ) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索队名") }, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = 8.dp).testTag("roster-picker-search"))
            if (allowNew)
                DropdownMenuItem(
                    text = { Text("新建对手") },
                    onClick = {
                        change("")
                        open = false
                    },
                )
            val matching = teams.filter { it.name.contains(query.trim(), ignoreCase = true) || it.shortName.contains(query.trim(), ignoreCase = true) }
            if (matching.isEmpty()) Text("没有匹配的球队", Modifier.padding(16.dp))
            matching.forEach { t ->
                DropdownMenuItem(
                    text = { Column { Text(t.name); Text("${t.players.count { !it.archived }} 名球员" + t.city.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
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

@Composable
internal fun PendingScheduleScreen(game: Game, model: ScorekeeperViewModel, quickAdd: (Boolean) -> Unit) {
    var configuring by rememberSaveable(game.id) { mutableStateOf(false) }
    var managing by rememberSaveable(game.id) { mutableStateOf(false) }
    var editingTime by rememberSaveable(game.id) { mutableStateOf(false) }
    var dateRevision by rememberSaveable(game.id) { mutableLongStateOf(game.revision) }
    if(editingTime) MatchTimeEditor(game, model.busy, { editingTime = false }) { model.command(it, dateRevision) { editingTime = false } }
    if (configuring) NewGameDialog(model.teams, model.rosters, model.sharing, model.busy,
        dismiss = { configuring = false }, manageOpponents = { managing = true }, initialSchedule = game,
        save = { prepared -> model.configureScheduledGame(prepared, game.revision) { configuring = false } })
    else FormPage("比赛安排", model.busy, false, "unused", "", { model.open(null) }, {}) {
        CardBlock {
            Text("${game.away.name} 对 ${game.home.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(game.scheduledAt?.let { formatMatchDate(it, game.playedTimeZoneID) }.orEmpty(), Modifier.testTag("pending-schedule-date"))
            Text(if(game.isObservation) "观赛记录 · 不计本队战绩" else "本队比赛", fontSize = 13.sp)
        }
        Text("已保存双方队伍和时间，名单、规则与阵容可在开赛前配置。")
        Button({ configuring = true }, Modifier.fillMaxWidth().testTag("configure-pending-schedule"), enabled = !model.busy, shape = RoundedCornerShape(15.dp)) { Text("赛前设置") }
        TextButton({ managing = true }, Modifier.testTag("pending-manage-rosters")) { Text("管理球队和名单") }
        TextButton({ dateRevision = game.revision; editingTime = true }, Modifier.testTag("pending-edit-time")) { Text("修改预告时间") }
    }
    if(managing) FormPage("管理球队和名单", model.busy, true, "close-scheduled-rosters", "完成", { managing = false }, { managing = false }) {
        RosterScreen(model, initialFilter = if(game.isObservation) "opponent" else "own", quickAdd = quickAdd)
    }
}
