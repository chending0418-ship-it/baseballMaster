package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
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
    games: List<Game> = emptyList(),
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
    var confirmSampleRoster by rememberSaveable { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(initialSchedule?.rules?.mode?.name ?: GameMode.standard.name) }
    val mode = GameMode.valueOf(modeName)
    var ownHome by rememberSaveable { mutableStateOf(initialSchedule != null && initialSchedule.home.id == initialSchedule.ourTeamID) }
    val baselineRules = initialSchedule?.rules?.takeIf { it.mode == mode } ?: Rules(mode)
    // Keep every field, including invalid text, above the conditional first-step
    // page. Returning from lineup review or hiding rules for a basic schedule
    // must not reconstruct the user's inputs from default/last-valid Rules.
    var ruleDraftJSON by rememberSaveable(mode) {
        mutableStateOf(GameJson.encodeToString(RuleDraft.from(baselineRules)))
    }
    val ruleDraft = remember(ruleDraftJSON) { GameJson.decodeFromString<RuleDraft>(ruleDraftJSON) }
    var ruleAdvanced by rememberSaveable(mode) { mutableStateOf(
        baselineRules.halfRunLimit != null || baselineRules.pitchLimit != null ||
            baselineRules.pitchWarningRemaining != null || baselineRules.pitcherInningsLimit != null || baselineRules.designatedHitter
    ) }
    val rules = remember(ruleDraft, mode) { runCatching { ruleDraft.build(mode) }.getOrNull() }
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
        listOf(own?.id, originJSON, sharingChoiceJSON, opponentID, opponentName, roster, modeName, ownHome, ruleDraftJSON, scheduled, date, recordType, spectatorAwayID, spectatorHomeID, recordingMethod, backfill, backfillDateText, zoneID, configureScheduleNow)
            .joinToString("|")
    var preparedJSON by rememberSaveable(inputKey) { mutableStateOf("") }
    var issue by remember { mutableStateOf<String?>(null) }
    val prepared =
        remember(preparedJSON) {
            preparedJSON.takeIf { it.isNotBlank() }?.let { GameJson.decodeFromString<Game>(it) }
        }
    if (prepared != null) {
        PregameLineupConfirmation(prepared, games, busy, { preparedJSON = "" }) { save(it) }
        return
    }
    if (confirmSampleRoster) AlertDialog(
        onDismissRequest = { confirmSampleRoster = false },
        title = { Text("替换为示例名单？") },
        text = { Text("当前填写的对方球员名单将被替换为 10 位示例球员。") },
        confirmButton = { TextButton(onClick = { roster = SampleRoster; confirmSampleRoster = false },
            modifier = Modifier.testTag("confirm-sample-opponent-roster")) { Text("替换名单") } },
        dismissButton = { TextButton(onClick = { confirmSampleRoster = false }) { Text("取消") } },
    )
    ProductPage(
        title = if(initialSchedule == null) "新比赛" else "预告赛前设置",
        busy = busy || sourceBusy,
        actionEnabled = (if(recordType == "spectator") spectatorAway != null && spectatorHome != null && spectatorAway.id != spectatorHome.id
         else own != null && (previous != null || opponentName.isNotBlank() && (basicSchedule || roster.isNotBlank()))) &&
            (basicSchedule || rules != null) && (!scheduled || scheduleDate != null && scheduleDate > System.currentTimeMillis()) && (!backfill || playedDate != null && playedDate <= System.currentTimeMillis()),
        actionTag = "start-game",
        actionLabel = if(basicSchedule) "保存比赛安排" else "下一步 · 确认双方阵容",
        onBack = dismiss,
        modal = false,
        onAction = {
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("记录方式", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                SetupSegmented("记分方式", linkedMapOf("traditional" to "基础版", "situation" to "Pro 版"), recordingMethod) { recordingMethod = it }
                Text(if (recordingMethod == "situation") "球型、落点和守备在同一个球场记录，再统一核对跑者。" else "先选打者最终位置，再判断原因。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupSectionTitle("参赛球队", if (basicSchedule) "安排时只需基本信息" else "第 1 步，共 2 步")
                if (initialSchedule == null) SetupSegmented("记录类型", linkedMapOf("team" to "本队比赛", "spectator" to "观赛记录"), recordType) { recordType = it }
                CardBlock {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (recordType == "spectator") {
                            if (initialSchedule != null) {
                                SetupValueRow("客队 · 先攻", spectatorAway?.name ?: initialSchedule.away.name, SetupTeamsIcon)
                            } else RosterChoice(opponents, spectatorAwayID, tag = "setup-spectator-away", title = "客队 · 先攻") { spectatorAwayID = it }
                            SetupRowDivider()
                            if (initialSchedule != null) {
                                SetupValueRow("主队 · 后攻", spectatorHome?.name ?: initialSchedule.home.name, Icons.Default.Home)
                            } else RosterChoice(opponents, spectatorHomeID, tag = "setup-spectator-home", title = "主队 · 后攻", icon = Icons.Default.Home) { spectatorHomeID = it }
                        } else {
                            if (initialSchedule != null) {
                                SetupValueRow("本队", own?.let { setupTeamText(it, if (shared == null) "本机球队" else "共享球队") }.orEmpty(), SetupTeamsIcon)
                            } else {
                                RosterChoice(teams, if (shared == null) ownID else "", title = "本队",
                                    display = if (sourceBusy) "读取中…" else own?.let { setupTeamText(it, if (shared == null) "本机球队" else "共享球队") },
                                    additionalChoices = { closeChoices ->
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(if (sourceBusy) "读取中…" else "选择共享名单") },
                                            enabled = !busy && !sourceBusy,
                                            modifier = Modifier.testTag("setup-refresh-shared"),
                                            onClick = {
                                                sourceBusy = true; issue = null
                                                scope.launch {
                                                    try { sharedTeams = sharing.refresh().teams }
                                                    catch (cancel: CancellationException) { throw cancel }
                                                    catch (_: Exception) { issue = "共享名单暂不可用，可继续使用本机球队。" }
                                                    finally { sourceBusy = false }
                                                }
                                            },
                                        )
                                        sharedTeams.forEach { membership ->
                                            DropdownMenuItem(
                                                text = { Text("${membership.name} · ${membership.role.title()}") },
                                                enabled = !busy && !sourceBusy,
                                                modifier = Modifier.testTag("setup-shared-${membership.id}"),
                                                onClick = {
                                                    closeChoices()
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
                                                            if (!sharingChoiceExplicit) sharingChoiceJSON = if (current.role != TeamRole.member) GameJson.encodeToString(PostgameSharingChoice(current.id, current.name)) else ""
                                                        } catch (cancel: CancellationException) { throw cancel }
                                                        catch (failure: Exception) { issue = if (failure is TeamServiceFault || failure is IllegalArgumentException || failure is IllegalStateException) failure.message else "共享名单未读取，请重试。" }
                                                        finally { sourceBusy = false }
                                                    }
                                                },
                                            )
                                        }
                                    },
                                ) { ownID = it; sharedJSON = ""; originJSON = "" }
                            }
                            if (shared != null) {
                                Text("共享名单 · ${shared.name}", Modifier.padding(start = 38.dp, bottom = 10.dp).testTag("setup-shared-source"),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (initialSchedule != null && shared != null) {
                                TextButton(enabled = !busy && !sourceBusy, modifier = Modifier.testTag("setup-refresh-shared"), onClick = {
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
                                                val current = sharing.state().directory?.teams?.single { it.id == membership.id } ?: error("当前无权读取该球队")
                                                sharedJSON = GameJson.encodeToString(LocalMatchSource.sharedSnapshot(profile))
                                                originJSON = GameJson.encodeToString(SharedRosterOrigin.from(profile, current))
                                                if (!sharingChoiceExplicit) sharingChoiceJSON = if (current.role != TeamRole.member) GameJson.encodeToString(PostgameSharingChoice(current.id, current.name)) else ""
                                            } catch (cancel: CancellationException) { throw cancel }
                                            catch (failure: Exception) { issue = if (failure is TeamServiceFault || failure is IllegalArgumentException || failure is IllegalStateException) failure.message else "共享名单未读取，请重试。" }
                                            finally { sourceBusy = false }
                                        }
                                    }) { Text("${membership.name} · ${membership.role.title()}") }
                                }
                            }
                            SetupRowDivider()
                            if (initialSchedule != null) SetupValueRow("对手", previous?.let { setupTeamText(it) }.orEmpty(), SetupShieldIcon)
                            else RosterChoice(rosters.filter { it.isOpponent && !it.archived }, opponentID, true, title = "对手", icon = SetupShieldIcon) { opponentID = it }
                            if (previous == null) {
                                OutlinedTextField(opponentName, { opponentName = it }, label = { Text("对手名称") },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("opponent-name"), singleLine = true,
                                    shape = RoundedCornerShape(12.dp))
                            }
                        }
                        SetupRowDivider()
                        SetupValueRow("管理对手球队和名单", "", SetupTeamsIcon, tag = "setup-manage-opponents",
                            titleColor = MaterialTheme.colorScheme.primary, showIndicator = false, onClick = manageOpponents)
                    }
                    if (recordType == "spectator" && opponents.size < 2) Text("观赛记录至少需要两支对手球队及名单。", fontSize = 12.sp)
                    if (recordType == "spectator" && spectatorAway != null && spectatorAwayID == spectatorHomeID)
                        Text("客队和主队不能相同", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            if (recordType == "team") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SetupSectionTitle("主客关系")
                    CardBlock {
                        SetupChoiceMenu("本队身份", if (ownHome) "home" else "away",
                            linkedMapOf("away" to "客队 · 先攻", "home" to "主队 · 后攻"), "setup-own-side", "choice-主客", Icons.Default.Home) {
                            ownHome = it == "home"
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupSectionTitle("比赛时间")
                CardBlock {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (initialSchedule == null) {
                            SetupToggleRow("安排未来比赛", scheduled, "setup-scheduled-toggle") { scheduled = it; if (it) backfill = false }
                            SetupRowDivider(inset = false)
                            if (!scheduled) SetupToggleRow("补填过去的比赛", backfill, "backfill-game-toggle") { backfill = it }
                        } else {
                            SetupValueRow("开赛时间", initialSchedule.scheduledAt?.let { formatMatchDate(it, initialSchedule.playedTimeZoneID) } ?: "尚未设置")
                        }
                        if (scheduled || backfill) {
                            OutlinedTextField(if (scheduled) date else backfillDateText, { if (scheduled) date = it else backfillDateText = it },
                                label = { Text(if (scheduled) "预告时间 yyyy-MM-dd HH:mm" else "比赛实际时间 yyyy-MM-dd HH:mm") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("setup-match-date"), singleLine = true, shape = RoundedCornerShape(12.dp))
                            if (backfill) Text("选择实际比赛日期与时间，计时仍从开始记录后独立计算。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (scheduled) {
                    CardBlock {
                        Text("可以只保存双方球队和时间；名单、规则、棒次与守位也可以现在设置，或留到开赛前补充。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        SetupToggleRow("现在完成赛前设置", configureScheduleNow, "configure-schedule-now") { configureScheduleNow = it }
                        if (basicSchedule) Text("先保存双方队伍和时间，开赛前再补充名单与比赛设置。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupSectionTitle("比赛规则")
                CardBlock {
                    SetupChoiceMenu("比赛模式", modeName, GameMode.entries.associate { it.name to it.title },
                        "setup-game-mode", "choice-模式", NavigationSymbols.diamond) { modeName = it }
                    if (!basicSchedule) {
                        HorizontalDivider()
                        RuleDraftForm(mode, ruleDraft, ruleAdvanced, showRecordingMethod = false,
                            onAdvancedChange = { ruleAdvanced = it },
                            onDraftChange = { ruleDraftJSON = GameJson.encodeToString(it) })
                    }
                }
            }
            if (recordType == "team" && previous == null && !basicSchedule) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SetupSectionTitle("对方球员名单")
                    CardBlock {
                        Text("每行：姓名,背号（可加英文名）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(roster, { roster = it }, minLines = 5, maxLines = 10,
                            modifier = Modifier.fillMaxWidth().testTag("opponent-roster"), shape = RoundedCornerShape(12.dp))
                        TextButton(onClick = {
                            if (roster.isBlank()) roster = SampleRoster else confirmSampleRoster = true
                        }, enabled = !busy && !sourceBusy, modifier = Modifier.testTag("fill-sample-opponent-roster")) {
                            Text("填入示例名单")
                        }
                    }
                }
            }
            if (recordType == "team") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SetupSectionTitle("赛后共享")
                    CardBlock {
                        Text(when { sharingChoice == null -> "尚未选择"; sharingChoice.teamID == null -> "仅本机"; else -> sharingChoice.teamName.orEmpty() },
                            Modifier.testTag("setup-postgame-choice"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth()) {
                            TextButton({ sharingChoiceExplicit = true; sharingChoiceJSON = GameJson.encodeToString(PostgameSharingChoice()) },
                                Modifier.weight(1f).testTag("setup-postgame-local")) { Text("仅本机保留") }
                            TextButton({ sharingChoiceExplicit = true; sharingChoiceJSON = "" },
                                Modifier.weight(1f).testTag("setup-postgame-undecided")) { Text("赛后再选") }
                        }
                        sharedTeams.filter { it.role != TeamRole.member }.forEach { target ->
                            TextButton({ sharingChoiceExplicit = true; sharingChoiceJSON = GameJson.encodeToString(PostgameSharingChoice(target.id, target.name)) },
                                Modifier.testTag("setup-postgame-team-${target.id}")) { Text(target.name) }
                        }
                    }
                }
            }
            issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RosterChoice(
    teams: List<Team>,
    selected: String,
    allowNew: Boolean = false,
    tag: String? = null,
    title: String = "本队",
    icon: ImageVector = SetupTeamsIcon,
    display: String? = null,
    additionalChoices: @Composable (closeChoices: () -> Unit) -> Unit = {},
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxWidth()) {
        val team = teams.firstOrNull { it.id == selected }
        SetupValueRow(title, display ?: team?.let { setupTeamText(it, if (allowNew || it.isOpponent) null else "本机球队") }
            ?: if (allowNew) "新建对手" else "选择球队", icon,
            tag = tag ?: if (allowNew) "setup-opponent" else "setup-own-team",
            onClick = { query = ""; open = true })
        DropdownMenu(open, { open = false },
            Modifier.heightIn(max = 420.dp).semantics { testTagsAsResourceId = true }) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索队名") },
                modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = 8.dp).testTag("roster-picker-search"))
            if (allowNew) DropdownMenuItem(text = { Text("新建对手") }, onClick = { change(""); open = false })
            val matching = teams.filter { it.name.contains(query.trim(), ignoreCase = true) || it.shortName.contains(query.trim(), ignoreCase = true) }
            if (matching.isEmpty()) Text("没有匹配的球队", Modifier.padding(16.dp), fontSize = 13.sp)
            matching.forEach { team ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(team.name)
                            Text("${team.players.count { !it.archived }} 名球员" + team.city.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { change(team.id); open = false },
                    modifier = Modifier.testTag("choose-roster-${team.id}"),
                )
            }
            additionalChoices { open = false }
        }
    }
}

@Composable
private fun SetupSectionTitle(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Black)
        subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun SetupSegmented(label: String, values: Map<String, String>, selected: String, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .075f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        values.forEach { (key, title) ->
            Box(Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(21.dp))
                .background(if (selected == key) MaterialTheme.colorScheme.surface else Color.Transparent)
                .toggleable(value = selected == key, role = Role.RadioButton, onValueChange = { if (it) choose(key) })
                .testTag("choice-$label-$key"), contentAlignment = Alignment.Center) {
                Text(title, Modifier.padding(horizontal = 8.dp, vertical = 7.dp), fontSize = 14.sp,
                    fontWeight = if (selected == key) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SetupValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    tag: String = "",
    titleColor: Color = Color.Unspecified,
    showIndicator: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val interaction = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(interaction).testTag(tag).heightIn(min = 58.dp).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Icon(it, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor)
        if (value.isNotBlank()) Text(value, Modifier.widthIn(max = 195.dp), fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
        if (showIndicator && onClick != null) {
            Column(Modifier.size(width = 15.dp, height = 18.dp), verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(width = 15.dp, height = 9.dp), tint = MaterialTheme.colorScheme.primary)
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(width = 15.dp, height = 9.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SetupRowDivider(inset: Boolean = true) {
    HorizontalDivider(Modifier.padding(start = if (inset) 36.dp else 0.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .8f))
}

@Composable
private fun SetupToggleRow(title: String, checked: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Switch(checked, change, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun SetupChoiceMenu(title: String, selected: String, values: Map<String, String>, tag: String,
    optionPrefix: String, icon: ImageVector? = null, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        SetupValueRow(title, values[selected].orEmpty(), icon, tag = tag, onClick = { expanded = true })
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            values.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, modifier = Modifier.testTag("$optionPrefix-$key").semantics { this.selected = key == selected },
                    onClick = { choose(key); expanded = false })
            }
        }
    }
}

private fun setupTeamText(team: Team, source: String? = null): String =
    team.shortName.ifBlank { team.name } + "\n" + listOfNotNull(source, "${team.players.count { !it.archived }} 人").joinToString(" · ")

private val SetupShieldIcon = ImageVector.Builder("对手", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f); lineTo(21f, 5f); lineTo(21f, 13f)
        curveTo(21f, 17f, 17f, 21f, 12f, 23f); curveTo(7f, 21f, 3f, 17f, 3f, 13f)
        lineTo(3f, 5f); close()
    }
}.build()

private val SetupTeamsIcon = ImageVector.Builder("球队名单", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(15f, 6f); curveTo(15f, 10f, 9f, 10f, 9f, 6f); curveTo(9f, 2f, 15f, 2f, 15f, 6f)
        moveTo(7f, 7f); curveTo(7f, 10.5f, 2f, 10.5f, 2f, 7f); curveTo(2f, 3.5f, 7f, 3.5f, 7f, 7f)
        moveTo(22f, 7f); curveTo(22f, 10.5f, 17f, 10.5f, 17f, 7f); curveTo(17f, 3.5f, 22f, 3.5f, 22f, 7f)
        moveTo(6f, 21f); lineTo(6f, 17f); curveTo(6f, 10.5f, 18f, 10.5f, 18f, 17f); lineTo(18f, 21f); close()
        moveTo(0f, 20f); lineTo(0f, 16f); curveTo(0f, 12f, 4f, 11f, 7f, 13f); curveTo(5f, 15f, 5f, 18f, 5f, 20f); close()
        moveTo(24f, 20f); lineTo(24f, 16f); curveTo(24f, 12f, 20f, 11f, 17f, 13f); curveTo(19f, 15f, 19f, 18f, 19f, 20f); close()
    }
}.build()

@Composable
internal fun PendingScheduleScreen(game: Game, model: ScorekeeperViewModel, quickAdd: (Boolean) -> Unit) {
    var configuring by rememberSaveable(game.id) { mutableStateOf(false) }
    var managing by rememberSaveable(game.id) { mutableStateOf(false) }
    var editingTime by rememberSaveable(game.id) { mutableStateOf(false) }
    var dateRevision by rememberSaveable(game.id) { mutableLongStateOf(game.revision) }
    if (configuring) NewGameDialog(model.teams, model.rosters, model.sharing, model.busy,
        dismiss = { configuring = false }, manageOpponents = { managing = true }, initialSchedule = game, games = model.games,
        save = { prepared -> model.configureScheduledGame(prepared, game.revision) { configuring = false } })
    else ProductPage(title="比赛安排",busy=model.busy,onBack={model.open(null)},actionLabel="赛前设置",actionTag="configure-pending-schedule",onAction={configuring=true},modal=true) {
        CardBlock {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("客队 · 先攻",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(game.away.name,fontSize=19.sp,fontWeight=FontWeight.Bold)
                }
                Text("VS",Modifier.padding(top=20.dp),fontSize=15.sp,color=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("主队 · 后攻",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(game.home.name,fontSize=19.sp,fontWeight=FontWeight.Bold)
                }
            }
            HorizontalDivider()
            Text(game.scheduledAt?.let { formatMatchDate(it, game.playedTimeZoneID) }.orEmpty(), Modifier.testTag("pending-schedule-date"),fontWeight=FontWeight.SemiBold)
            Text(if(game.isObservation) "观赛记录 · 不计本队战绩" else "本队比赛", fontSize = 13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("双方队伍和时间已保存，开赛前可继续配置名单、规则和阵容。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ProductActionRow("管理球队和名单","核对参赛球队的实际球员",tag="pending-manage-rosters",enabled=!model.busy) { managing=true }
        ProductActionRow("修改预告时间",tag="pending-edit-time",enabled=!model.busy) {dateRevision=game.revision;editingTime=true}
    }
    if(managing) FormPage("管理球队和名单", model.busy, true, "close-scheduled-rosters", "完成", { managing = false }, { managing = false }) {
        RosterScreen(model, initialFilter = if(game.isObservation) "opponent" else "own", quickAdd = quickAdd)
    }
    if(editingTime) MatchTimeEditor(game, model.busy, { editingTime = false }) { model.command(it, dateRevision) { editingTime = false } }
}
