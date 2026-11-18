package cc.baseballmaster.scorekeeper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cc.baseballmaster.core.Decision
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.RunnerDefenseCredit
import cc.baseballmaster.core.ScoringOptions
import cc.baseballmaster.core.advancesFromBase

@Composable
internal fun RunnerCatcherPicker(
    game: Game,
    selection: String?,
    busy: Boolean,
    change: (String?) -> Unit,
) {
    val options = linkedMapOf("pending" to "守备归属待确认")
    game.fieldingPositions[2]?.let { id ->
        options[id] = game.defensivePlayerLabel(2, id)
    }
    ProductMenuPicker("捕逸捕手", selection ?: "pending", options, tag = "runner-passed-ball-catcher", enabled = !busy) {
        change(it.takeUnless { value -> value == "pending" })
    }
}

@Composable
internal fun RunnerCaughtStealingPicker(
    game: Game,
    decision: Decision,
    busy: Boolean,
    change: (RunnerDefenseCredit?) -> Unit,
) {
    if (decision.destination != -1 && !decision.advancesFromBase) return
    val catcher = game.fieldingPositions[2]
    val credit = decision.runnerDefense as? RunnerDefenseCredit.CaughtStealing
    val receiver = when (decision.runnerDefense) {
        is RunnerDefenseCredit.CaughtStealing -> credit?.receiverID.orEmpty()
        RunnerDefenseCredit.NoCatcherAssist -> "none"
        else -> "pending"
    }
    val out = decision.destination == -1
    val receiverOptions = linkedMapOf("pending" to "守备归属待确认")
    if (out) receiverOptions["none"] = "无捕手助杀"
    if (catcher != null) {
        game.fieldingPositions.toSortedMap().forEach { (position, player) ->
            if (player != catcher) receiverOptions[player] = game.defensivePlayerLabel(position, player)
        }
        Text("传球捕手 · ${game.defensivePlayerLabel(2, catcher)}", style = MaterialTheme.typography.bodySmall)
    }
    ProductMenuPicker(if (out) "接球野手" else "接球失误野手", receiver, receiverOptions,
        tag = "runner-steal-receiver", enabled = !busy) { selected ->
        change(when (selected) {
            "pending" -> null
            "none" -> RunnerDefenseCredit.NoCatcherAssist
            else -> catcher?.let {
                RunnerDefenseCredit.CaughtStealing(it, selected, credit?.target ?: decision.origin + 1)
            }
        })
    }
    if (!out) Text("原可阻杀，因接球失误上垒时才选。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (credit != null) {
        val targets = (decision.origin + 1)..(if (out) 4 else decision.destination)
        val options = targets.associate { it.toString() to listOf("一垒", "二垒", "三垒", "本垒")[it - 1] }
        ProductMenuPicker("盗垒目标", credit.target.toString(), options, tag = "runner-steal-target", enabled = !busy) {
            change(credit.copy(target = it.toInt()))
        }
    }
}

private fun Game.defensivePlayerLabel(position: Int, id: String): String {
    val player = fieldingTeam.players.first { it.id == id }
    return "${ScoringOptions.positions[position - 1]} · #${player.number} ${player.name}"
}
