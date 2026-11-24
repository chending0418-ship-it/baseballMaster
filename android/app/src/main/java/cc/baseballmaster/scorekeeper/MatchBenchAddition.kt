package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.*
import java.util.UUID

@Composable
internal fun MatchBenchAddition(team: Team, roster: Team?, game: Game, add: (Player) -> Unit) {
    var open by rememberSaveable(team.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("添加本场队员") }
    if (!open) return
    val occupied = (game.home.players + game.away.players).mapTo(mutableSetOf()) { it.id.lowercase() }
    val choices = roster?.players.orEmpty().filter { !it.archived && it.id.lowercase() !in occupied }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    val temporaryID = rememberSaveable { UUID.randomUUID().toString() }
    val player = if (selected == null) Player(temporaryID, name.trim(), number.trim(), chineseName = name.trim())
        else choices.singleOrNull { it.id == selected }
    val duplicate = player != null && team.players.any { it.name == player.name && it.number == player.number }
    var checked by rememberSaveable(selected, name, number) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("添加到${team.shortName}替补席") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (candidate in choices) {
                    FilterChip(selected == candidate.id, { selected = candidate.id },
                        label = { Text("#${candidate.number}  ${candidate.name}") })
                }
                FilterChip(selected == null, { selected = null }, label = { Text("登记本场临时队员") })
                if (selected == null) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("姓名") })
                    OutlinedTextField(number, { number = it }, singleLine = true, label = { Text("背号（00 或 0–99）") })
                }
                if (duplicate) FilterChip(checked, { checked = !checked }, label = { Text("已核对同名同号，两人为不同队员") })
            }
        },
        confirmButton = {
            TextButton(enabled = player != null && runCatching { Roster.validate(player) }.isSuccess &&
                (!duplicate || checked), onClick = { player?.let(add); open = false }) { Text("加入本场名单") }
        }, dismissButton = { TextButton({ open = false }) { Text("取消") } })
}
