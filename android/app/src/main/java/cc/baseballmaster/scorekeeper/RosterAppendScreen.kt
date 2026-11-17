package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.RosterAppend
import cc.baseballmaster.core.RosterAppendPreview
import cc.baseballmaster.core.Team

@Composable
internal fun RosterAppendScreen(team: Team, model: ScorekeeperViewModel, close: () -> Unit) {
    var input by rememberSaveable(team.id) { mutableStateOf("") }
    var preview by remember(team.id) { mutableStateOf<RosterAppendPreview?>(null) }
    var baseTeam by remember(team.id) { mutableStateOf<Team?>(null) }
    var confirmed by remember(team.id) { mutableStateOf(false) }
    val result = preview
    val stale = baseTeam != null && baseTeam != team
    val previewReady = result != null && result.issues.isEmpty() && result.rows.isNotEmpty()
    fun check() {
        preview = RosterAppend.preview(input, team.players)
        baseTeam = team
        confirmed = false
    }

    ProductPage(
        title = "批量添加球员",
        busy = model.busy,
        actionTag = if (previewReady && !stale) "roster-batch-submit" else "roster-batch-preview",
        actionLabel = if (previewReady && !stale) "确认添加 ${requireNotNull(result).rows.size} 人" else "预览名单",
        actionEnabled = !team.archived && team.source == "local" && input.isNotBlank() &&
            (stale || !previewReady || result!!.warnings.isEmpty() || confirmed),
        onBack = close,
        backLabel = "取消",
        onAction = {
            if (previewReady && !stale) model.appendRoster(requireNotNull(baseTeam), input, confirmed, close)
            else check()
        },
    ) {
        Text(team.name, style = MaterialTheme.typography.titleMedium)
        if (stale) {
            Text("球队名单已更新，请重新预览。", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("roster-batch-stale"))
        }
        if (!previewReady || stale) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; preview = null; baseTeam = null; confirmed = false },
                label = { Text("球员名单") },
                supportingText = { Text("每行一位：姓名,英文名,背号。英文名可省，多个背号用 / 分隔。") },
                minLines = 5,
                maxLines = 10,
                enabled = !model.busy,
                modifier = Modifier.fillMaxWidth().testTag("roster-batch-input"),
            )
            result?.issues?.forEach { note ->
                Text("第 ${note.lineNumber} 行 · ${note.message}", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("roster-batch-issue-${note.lineNumber}"))
            }
        } else {
            val checked = requireNotNull(result)
            Text("预计新增 ${checked.rows.size} 人，原名单和历史比赛会保留。",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("roster-batch-preview-count"))
            TextButton(onClick = { preview = null; baseTeam = null; confirmed = false }, enabled = !model.busy,
                modifier = Modifier.testTag("roster-batch-edit")) { Text("返回编辑") }
            if (checked.warnings.isNotEmpty()) {
                checked.warnings.forEach { note ->
                    Text("第 ${note.lineNumber} 行 · ${note.message}", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("roster-batch-warning-${note.lineNumber}"))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it }, enabled = !model.busy, modifier = Modifier.testTag("roster-batch-distinct"))
                    Text("确认同名同背号的是不同球员", style = MaterialTheme.typography.bodyMedium)
                }
            }
            CardBlock {
                checked.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("roster-batch-row-${row.lineNumber}")) {
                        Text(row.player.name, style = MaterialTheme.typography.titleSmall)
                        val numbers = row.player.jerseyNumbers.joinToString(" / ") { "#$it" }
                        val detail = listOfNotNull(row.player.englishName.takeIf { it.isNotBlank() && it != row.player.name }, numbers)
                        Text(detail.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
