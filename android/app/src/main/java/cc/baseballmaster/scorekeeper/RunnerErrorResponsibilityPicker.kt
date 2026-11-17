package cc.baseballmaster.scorekeeper

import androidx.compose.runtime.Composable
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.RunnerErrorResponsibility
import cc.baseballmaster.core.ScoringOptions

@Composable
internal fun RunnerErrorResponsibilityPicker(
    game: Game,
    responsibility: RunnerErrorResponsibility?,
    busy: Boolean,
    onSelect: (RunnerErrorResponsibility) -> Unit,
) {
    val team = game.fieldingTeam
    val options = linkedMapOf("pending" to "责任人待确认")
    game.fieldingPositions.toSortedMap().forEach { (position, id) ->
        val player = team.players.first { it.id == id }
        val role = ScoringOptions.positions[position - 1]
        options["fielder-${player.id}"] = "$role · #${player.number} ${player.name}"
    }
    val selected = when {
        responsibility == null -> ""
        responsibility.fielderID == null -> "pending"
        else -> "fielder-${responsibility.fielderID}"
    }
    ProductMenuPicker("失误责任人", selected, options, tag = "runner-error-responsibility", enabled = !busy) { choice ->
        val fielderID = if (choice == "pending") null else choice.removePrefix("fielder-")
        onSelect(RunnerErrorResponsibility(fielderID))
    }
}
