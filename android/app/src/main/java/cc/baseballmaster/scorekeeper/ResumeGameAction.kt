package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.Command
import cc.baseballmaster.core.Game

@Composable
internal fun ResumeGameAction(game: Game, onResume: (Command) -> Unit, content: @Composable (() -> Unit) -> Unit) {
    var showCheck by rememberSaveable(game.id, game.revision) { mutableStateOf(false) }
    content {
        if (game.requiresLegacyResumeConfirmation) showCheck = true
        else onResume(Command("reopen"))
    }
    if (showCheck) {
        val occupied = game.bases.mapIndexedNotNull { index, id ->
            game.battingTeam.players.firstOrNull { it.id == id }?.let { "${index + 1} 垒 #${it.number} ${it.name}" }
        }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { showCheck = false },
            title = { Text("核对旧比赛的恢复局面") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${game.inning} 局${if (game.isTop) "上" else "下"} · ${game.outs} 出局")
                    Text("第 ${game.batterIndex + 1} 棒 · #${game.batter.number} ${game.batter.name}")
                    Text(occupied.ifEmpty { listOf("垒上无人") }.joinToString(" · "))
                    Text("确认后恢复比赛，计时保持暂停。")
                }
            },
            confirmButton = {
                TextButton({
                    showCheck = false
                    onResume(Command("reopen", flag = true))
                }, modifier = Modifier.testTag("confirm-legacy-resume")) { Text("确认恢复") }
            },
            dismissButton = { TextButton({ showCheck = false }) { Text("取消") } },
        )
    }
}
