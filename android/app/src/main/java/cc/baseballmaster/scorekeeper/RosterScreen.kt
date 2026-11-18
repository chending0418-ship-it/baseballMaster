package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import java.util.UUID

@Composable
internal fun RosterScreen(
    model: ScorekeeperViewModel,
    initialFilter: String = "own",
    showTabHeader: Boolean = false,
    initialTeamID: String = "",
    quickAdd: (Boolean) -> Unit,
) {
    var showSharing by rememberSaveable { mutableStateOf(false) }
    var teamID by rememberSaveable(initialTeamID) { mutableStateOf(initialTeamID) }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var editingTeam by rememberSaveable { mutableStateOf(false) }
    var editingPlayer by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingPlayer by rememberSaveable { mutableStateOf<String?>(null) }
    var removingPlayer by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addingBatch by rememberSaveable(teamID) { mutableStateOf(false) }
    val team = model.rosters.firstOrNull { it.id == teamID }
    if (showSharing) TeamSharingScreen(model.sharing, model.teams, broadcast = model.fixedBroadcast,
        statistics = { id, close ->
            key(id) {
                ProductPage("球队年度统计", onBack = close, backTag = "shared-stats-back") {
                    StatisticsScreen(model, scopedSharedTeamID = id)
                }
            }
        }) { showSharing = false }
    val addTeam: @Composable () -> Unit = {
        if (filter != "archived") RosterCreateMenu(filter == "opponent", { adding = true }, { quickAdd(filter == "opponent") })
    }
    if (showTabHeader) ProductTabHeader("球队", trailing = addTeam)
    if (filter == "own") ProductActionRow("球队共享", "创建共享球队，或通过邀请加入", NavigationSymbols.roster, "open-team-sharing") { showSharing = true }
    val visibleTeams = model.rosters.filter {
        if (filter == "archived") it.archived
        else !it.archived && it.isOpponent == (filter == "opponent")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (filter == "opponent") "对手球队管理" else if (filter == "archived") "已移除球队" else "本机球队管理",
            Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("${visibleTeams.size} 支球队", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!showTabHeader) addTeam()
    }
    StatisticsSegmented("名单", mapOf("own" to "我的球队", "opponent" to "对手", "archived" to "已移除"), filter) {
        filter = it
    }
    if (visibleTeams.isEmpty()) CardBlock {
        Text(if (filter == "opponent") "还没有对手球队" else if (filter == "archived") "没有已移除球队" else "还没有球队，历史比赛和统计仍可查看。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    visibleTeams.forEach { t -> key(t.id) {
        var menu by remember(t.id) { mutableStateOf(false) }
        CardBlock {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f).clickable { teamID = t.id }.testTag("roster-${t.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TeamMark(t.shortName.ifBlank { t.name }, 40)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(t.name, Modifier.align(Alignment.CenterVertically), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (t.id == model.currentTeam?.id && !t.isOpponent && !t.archived) {
                                Surface(Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "当前球队" },
                                    shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("当前", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        Text(listOf(t.city.takeIf { it.isNotBlank() }, "${t.players.count { !it.archived }} 名球员").filterNotNull().joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton({ menu = true }, Modifier.size(48.dp).testTag("roster-menu-${t.id}")) {
                        Icon(Icons.Outlined.MoreVert, "${t.name}的球队操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(menu, { menu = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                        if (!t.isOpponent && !t.archived && t.source == "local" && t.id != model.currentTeam?.id)
                            DropdownMenuItem(text = { Text("设为当前球队") }, modifier = Modifier.testTag("select-current-team-${t.id}"),
                                onClick = { menu = false; model.selectCurrentTeam(t.id) })
                        DropdownMenuItem(text = { Text("查看球员名单") }, onClick = { menu = false; teamID = t.id })
                        DropdownMenuItem(text = { Text("编辑球队资料") }, onClick = { menu = false; teamID = t.id; editingTeam = true })
                    }
                }
            }
        }
    } }
    if (adding || editingTeam && team != null) {
        val original = if (adding) null else team
        key(original?.id) {
            TeamProfileEditor(
                original,
                filter == "opponent",
                model.busy,
                {
                    adding = false
                    editingTeam = false
                },
            ) { name, short, city ->
                if (original == null)
                    model.addRoster(name, short, city, filter == "opponent") { adding = false }
                else
                    model.updateRoster(original.copy(name = name, shortName = short, city = city)) {
                        editingTeam = false
                    }
            }
        }
    } else if (team != null) {
        FormPage(team.shortName.ifBlank { team.name }, model.busy, false, "unused", "", { teamID = "" }, {}) {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TeamMark(team.shortName.ifBlank { team.name }, 52)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(team.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(listOf(team.city.takeIf { it.isNotBlank() }, "${team.players.count { !it.archived }} 名球员").filterNotNull().joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("球队名单", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton({ editingTeam = true }, Modifier.testTag("edit-team-profile")) { Text("编辑球队", fontSize = 12.sp) }
            }
            if (!team.archived && team.source == "local") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ editingPlayer = "new" }, Modifier.weight(1f).testTag("add-player")) { Text("添加球员") }
                OutlinedButton({ addingBatch = true }, Modifier.weight(1f).testTag("add-players-batch")) { Text("批量添加") }
            }
            if (team.players.isEmpty()) CardBlock {
                Text("名单中还没有球员", style = MaterialTheme.typography.titleSmall)
                Text("可逐个添加，也可粘贴多行名单。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            team.players.sortedWith(compareBy<Player> { it.number.toIntOrNull() ?: 0 }.thenBy { it.number }.thenBy { it.id })
                .forEach { p ->
                    var menu by remember(p.id) { mutableStateOf(false) }
                    CardBlock {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.weight(1f).clickable(enabled = !team.isOpponent) { viewingPlayer = p.id }.testTag("roster-player-${p.id}"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Box(Modifier.size(39.dp), contentAlignment = Alignment.Center) {
                                        Text(p.number, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(p.name + if (p.archived) " · 已移出" else "", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(listOf(p.englishName.takeIf { it.isNotBlank() }, p.jerseyNumbers.joinToString(" / ") { "#$it" }).filterNotNull().joinToString(" · "),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!team.isOpponent) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton({ menu = true }, Modifier.size(48.dp).testTag("roster-player-menu-${p.id}")) {
                                    Icon(Icons.Outlined.MoreVert, "管理${p.name}", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(menu, { menu = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                                    DropdownMenuItem(text = { Text("编辑球员") }, modifier = Modifier.testTag("edit-player-${p.id}"),
                                        onClick = { menu = false; editingPlayer = p.id })
                                    DropdownMenuItem(text = { Text(if (p.archived) "恢复球员" else "移出名单") },
                                        modifier = Modifier.testTag("archive-player-${p.id}"), onClick = { menu = false; removingPlayer = p.id })
                                }
                            }
                        }
                    }
                }
            Text(
                "移出名单保留球员身份，已保存比赛继续显示当时的姓名、背号与统计。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                { model.updateRoster(team.copy(archived = !team.archived)) { teamID = "" } },
                Modifier.testTag("archive-roster"),
            ) {
                Text(if (team.archived) "恢复球队" else "移除球队（保留历史）")
            }
        }
        if (addingBatch) RosterAppendScreen(team, model) { addingBatch = false }
        viewingPlayer?.let { pid -> team.players.firstOrNull { it.id == pid }?.let { player ->
            PlayerStatisticsDetail(team, SeasonPlayerStatistics(player, currentRoster = !player.archived), model) { viewingPlayer = null }
        } }
        removingPlayer?.let { pid -> team.players.firstOrNull { it.id == pid }?.let { player ->
            AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { removingPlayer = null }, title = { Text(if (player.archived) "恢复球员？" else "移出名单？") },
                text = { Text("${player.name}的身份和已保存比赛记录会保留。") },
                confirmButton = { TextButton({ model.updateRoster(team.copy(players = team.players.map {
                    if (it.id == pid) it.copy(archived = !it.archived) else it
                })) { removingPlayer = null } }, enabled = !model.busy, modifier = Modifier.testTag("confirm-archive-player")) {
                    Text(if (player.archived) "恢复球员" else "确认移出")
                } }, dismissButton = { TextButton({ removingPlayer = null }) { Text("取消") } })
        } }
        editingPlayer?.let { pid ->
            key(pid) {
                PlayerProfileEditor(
                    team.players.firstOrNull { it.id == pid },
                    model.busy,
                    { editingPlayer = null },
                ) { p ->
                    model.updateRoster(
                        team.copy(
                            players =
                                if (pid == "new") team.players + p
                                else team.players.map { if (it.id == pid) p else it }
                        )
                    ) {
                        editingPlayer = null
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterCreateMenu(opponent: Boolean, add: () -> Unit, bulkAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val title = if (opponent) "添加对手" else "添加球队"
    Box {
        IconButton({ open = true }, Modifier.size(48.dp).testTag("roster-open-create")) {
            Icon(Icons.Outlined.Add, title, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(open, { open = false }, Modifier.semantics { testTagsAsResourceId = true }) {
            DropdownMenuItem(text = { Text(title) }, onClick = { open = false; add() }, modifier = Modifier.testTag("add-roster"))
            DropdownMenuItem(text = { Text("批量创建") }, onClick = { open = false; bulkAdd() }, modifier = Modifier.testTag("add-team"))
        }
    }
}

@Composable
private fun TeamProfileEditor(
    team: Team?,
    opponent: Boolean,
    busy: Boolean,
    close: () -> Unit,
    save: (String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(team?.name.orEmpty()) }
    var short by rememberSaveable { mutableStateOf(team?.shortName.orEmpty()) }
    var city by rememberSaveable { mutableStateOf(team?.city.orEmpty()) }
    FormPage(
        if (team == null) if (opponent) "添加对手" else "添加球队" else "编辑球队",
        busy,
        name.isNotBlank(),
        "save-roster-profile",
        "保存",
        close,
        { save(name.trim(), short.trim().ifBlank { name.trim() }, city.trim()) },
    ) {
        CardBlock {
            Text("基本资料", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("球队全称") },
                modifier = Modifier.fillMaxWidth().testTag("roster-name"),
            )
            OutlinedTextField(short, { short = it }, label = { Text("球队简称") })
            OutlinedTextField(city, { city = it }, label = { Text("所在城市") })
        }
    }
}

@Composable
private fun PlayerProfileEditor(
    player: Player?,
    busy: Boolean,
    close: () -> Unit,
    save: (Player) -> Unit,
) {
    val id = rememberSaveable { player?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf((player?.chineseName ?: player?.name).orEmpty()) }
    var english by rememberSaveable { mutableStateOf(player?.englishName.orEmpty()) }
    var numbers by rememberSaveable {
        mutableStateOf(player?.jerseyNumbers?.joinToString(" / ").orEmpty())
    }
    val candidate =
        Player(
            id,
            name.trim().ifBlank { english.trim() }.ifBlank { "${numbers.split('/').first().trim()}号球员" },
            numbers.split('/').first().trim(),
            english.trim(),
            jerseyNumbers = numbers.split('/').map { it.trim() },
            chineseName = name.trim(),
            archived = player?.archived ?: false,
            position = player?.position ?: 0,
        )
    val issue = runCatching { Roster.validate(candidate) }.exceptionOrNull()?.message
    FormPage(
        if (player == null) "添加球员" else "编辑球员",
        busy,
        issue == null,
        "save-player-profile",
        "保存",
        close,
        { save(candidate) },
    ) {
        CardBlock {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("中文姓名") },
                modifier = Modifier.fillMaxWidth().testTag("player-chinese"),
            )
            OutlinedTextField(
                english,
                { english = it },
                label = { Text("英文姓名") },
                modifier = Modifier.fillMaxWidth().testTag("player-english"),
            )
            OutlinedTextField(
                numbers,
                { numbers = it },
                label = { Text("背号；多个用 / 分隔") },
                modifier = Modifier.fillMaxWidth().testTag("player-numbers"),
            )
        }
        if (issue != null) Text(issue, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}
