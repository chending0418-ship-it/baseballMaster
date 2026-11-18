package cc.baseballmaster.scorekeeper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.History
import cc.baseballmaster.core.RecordedCommand
import cc.baseballmaster.core.ScoringOptions

@Composable
internal fun HistorySubstitutionEditor(
    before: Game,
    record: RecordedCommand,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Command) -> Unit,
) {
    val original = record.command
    val dual = original.kind == "doubleSwitch"
    val batting = original.kind in setOf("batter", "pinchRunner")
    val team = if (batting) before.battingTeam else before.fieldingTeam
    val order = if (batting) before.battingOrder else
        (if (before.isTop) before.homeOrder else before.awayOrder) ?: team.players.map { it.id }
    val field = if (!batting) before.fieldingPositions else
        (if (before.isTop) before.awayPositions else before.homePositions).orEmpty()
    val firstOutgoing = when (original.kind) {
        "pitcher" -> before.pitcher.id
        "batter" -> before.batter.id
        "pinchRunner" -> original.position?.let { before.bases.getOrNull(it - 1) }
        else -> original.playerID
    }
    val firstOriginalIncoming = if (original.kind == "fielder" || dual) original.replacementID else original.playerID
    val secondOutgoing = original.secondPlayerID
    var firstIncoming by rememberSaveable(record.id) { mutableStateOf(firstOriginalIncoming.orEmpty()) }
    var secondIncoming by rememberSaveable(record.id) { mutableStateOf(original.secondReplacementID.orEmpty()) }
    var firstPosition by rememberSaveable(record.id) { mutableStateOf(original.position) }
    var secondPosition by rememberSaveable(record.id) { mutableStateOf(original.secondPosition) }
    var keepFirst by rememberSaveable(record.id) { mutableStateOf(true) }
    var keepSecond by rememberSaveable(record.id) { mutableStateOf(true) }
    val candidates = remember(before, original.kind) {
        History.substitutionCandidates(before, original.kind).associate { it.id to "#${it.number} ${it.name}" }
    }
    fun person(id: String?) = team.players.firstOrNull { it.id == id }?.let { "#${it.number} ${it.name}" } ?: "球员待确认"
    fun positionName(position: Int) = ScoringOptions.positions[position - 1]
    fun role(id: String?): String {
        val slot = order.indexOfFirst { it == id }.takeIf { it >= 0 }?.let { "第 ${it + 1} 棒" }
        val position = field.entries.firstOrNull { it.value == id }?.key?.let(::positionName)
        val base = original.position?.takeIf { original.kind == "pinchRunner" }?.let { "$it 垒跑者" }
        return listOfNotNull(slot, position, base).joinToString(" · ")
    }
    val positions = field.filterValues {
        it == firstOutgoing && keepFirst || it == secondOutgoing && keepSecond
    }.toSortedMap().mapKeys { (position, _) -> position.toString() }.mapValues { (position, _) ->
        positionName(position.toInt())
    }
    val checked = remember(before, original, firstIncoming, secondIncoming, firstPosition, secondPosition, keepFirst, keepSecond) {
        runCatching {
            val first = firstIncoming.takeIf { keepFirst }
            val second = secondIncoming.takeIf { keepSecond }
            require((first == null || first in candidates) && (!dual || second == null || second in candidates)) {
                "请选择当时可换入的球员"
            }
            when {
                dual -> History.reviseDoubleSwitch(before, original, first, firstPosition, second, secondPosition)
                original.kind == "fielder" -> original.copy(replacementID = first)
                else -> original.copy(playerID = first)
            }
        }
    }
    val command = checked.getOrNull()
    FormPage("更正${History.title(original)}", busy, command != null && command != original,
        "save-history-substitution", "加入草稿", dismiss, { if (command != null) save(command) }) {
        Text("第 ${before.inning} 局${if (before.isTop) "上" else "下"} · ${team.name}",
            style = MaterialTheme.typography.titleMedium)
        if (dual) Text("取消保留可撤掉单组换人。加入草稿后会重放后续记录。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fun choices(excluded: String?) = candidates.filterKeys { it != excluded }
        if (dual) {
            Text("第 1 组", style = MaterialTheme.typography.titleSmall)
        }
        Text("换下 ${person(firstOutgoing)}${role(firstOutgoing).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}")
        if (dual) ProductToggleRow("保留第 1 组", checked = keepFirst, tag = "history-keep-switch-1") { keepFirst = it }
        if (keepFirst) {
            ProductMenuPicker("换上球员", firstIncoming, choices(secondIncoming.takeIf { dual && keepSecond }),
                tag = "history-switch-incoming-1", enabled = !busy) { firstIncoming = it }
            if (dual) ProductMenuPicker("换上守位", firstPosition?.toString().orEmpty(), positions,
                tag = "history-switch-position-1", enabled = !busy) { firstPosition = it.toInt() }
        }
        if (dual) {
            Text("第 2 组", style = MaterialTheme.typography.titleSmall)
            Text("换下 ${person(secondOutgoing)}${role(secondOutgoing).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}")
            ProductToggleRow("保留第 2 组", checked = keepSecond, tag = "history-keep-switch-2") { keepSecond = it }
            if (keepSecond) {
                ProductMenuPicker("换上球员", secondIncoming, choices(firstIncoming.takeIf { keepFirst }),
                    tag = "history-switch-incoming-2", enabled = !busy) { secondIncoming = it }
                ProductMenuPicker("换上守位", secondPosition?.toString().orEmpty(), positions,
                    tag = "history-switch-position-2", enabled = !busy) { secondPosition = it.toInt() }
            }
        }
        checked.exceptionOrNull()?.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
