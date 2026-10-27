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
import java.util.UUID

@Composable
internal fun RosterScreen(model: ScorekeeperViewModel, quickAdd: () -> Unit) {
    var teamID by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("own") }
    var editingTeam by rememberSaveable { mutableStateOf(false) }
    var editingPlayer by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val team = model.rosters.firstOrNull { it.id == teamID }
    Text("本机球队管理", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Choices("名单", mapOf("own" to "我的球队", "opponent" to "对手", "archived" to "已移除"), filter) {
        filter = it
    }
    TextButton({ adding = true }, Modifier.testTag("add-roster")) {
        Text(if (filter == "opponent") "添加对手" else "添加球队")
    }
    TextButton(quickAdd, Modifier.testTag("add-team")) { Text("批量创建球队与名单") }
    model.rosters
        .filter {
            if (filter == "archived") it.archived
            else !it.archived && it.isOpponent == (filter == "opponent")
        }
        .forEach { t ->
            CardBlock {
                Text(t.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${t.city} · ${t.players.count{!it.archived}} 名球员",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton({ teamID = t.id }, Modifier.testTag("roster-${t.id}")) {
                    Text("球队资料与名单")
                }
            }
        }
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
        FormPage(team.shortName, model.busy, false, "unused", "", { teamID = "" }, {}) {
            CardBlock {
                Text(team.name, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("${team.city} · 简称 ${team.shortName}")
            }
            TextButton({ editingTeam = true }, Modifier.testTag("edit-team-profile")) {
                Text("编辑球队")
            }
            TextButton({ editingPlayer = "new" }, Modifier.testTag("add-player")) { Text("添加球员") }
            team.players
                .sortedWith(
                    compareBy<Player> { it.number.toIntOrNull() ?: 0 }
                        .thenBy { it.number }
                        .thenBy { it.id }
                )
                .forEach { p ->
                    CardBlock {
                        Text(
                            "${p.jerseyNumbers.joinToString(" / "){"#$it"}} ${p.name}${if(p.archived)" · 已移出"else""}",
                            fontWeight = FontWeight.Bold,
                        )
                        if (p.englishName.isNotBlank()) Text(p.englishName, fontSize = 13.sp)
                        Row {
                            TextButton(
                                { editingPlayer = p.id },
                                Modifier.testTag("edit-player-${p.id}"),
                            ) {
                                Text("编辑")
                            }
                            TextButton({
                                model.updateRoster(
                                    team.copy(
                                        players =
                                            team.players.map {
                                                if (it.id == p.id) it.copy(archived = !it.archived)
                                                else it
                                            }
                                    )
                                )
                            }) {
                                Text(if (p.archived) "恢复球员" else "移出名单")
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
        Text("球员数量由名单自动统计。", fontSize = 12.sp)
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
            name.trim().ifBlank { english.trim() },
            numbers.split('/').first().trim(),
            english.trim(),
            jerseyNumbers = numbers.split('/').map { it.trim() },
            chineseName = name.trim(),
            archived = player?.archived ?: false,
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
        Text("支持 0 和 00；同名球员保留各自独立身份。", fontSize = 12.sp)
        if (issue != null) Text(issue, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}
