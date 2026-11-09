package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.Roster

@Composable
internal fun BulkRosterScreen(
    teamName: String,
    existingNames: List<String>,
    previewRevision: Any,
    busy: Boolean,
    canSubmit: Boolean,
    issue: String? = null,
    initialInput: String = "",
    retry: (() -> Unit)? = null,
    recheck: (() -> Unit)? = null,
    dismiss: () -> Unit,
    save: (String, List<Roster.Row>) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(initialInput) }
    var preview by remember(previewRevision) { mutableStateOf<List<Roster.Row>?>(null) }
    var inputIssue by remember(previewRevision) { mutableStateOf<String?>(null) }
    val rows = preview
    ProductPage("批量添加球员", busy = busy, onBack = dismiss, backLabel = "取消",
        actionLabel = if (rows == null) "预览" else "确认添加 ${rows.size} 人", actionTag = "bulk-roster-confirm",
        actionEnabled = canSubmit && input.isNotBlank(), modal = true, onAction = {
            if (rows != null) save(input, rows)
            else {
                val result = runCatching { Roster.previewAppend(input, existingNames) }
                preview = result.getOrNull()
                inputIssue = result.exceptionOrNull()?.message
            }
        }) {
        Text(teamName, style = MaterialTheme.typography.titleMedium)
        if (rows == null) {
            Text("每行一位：中文名,背号 或 中文名,英文名,背号。多个背号用 / 分隔，例如 0/00。")
            OutlinedTextField(input, { input = it.take(64_000); inputIssue = null },
                Modifier.fillMaxWidth().testTag("bulk-roster-input"), label = { Text("球员名单") },
                minLines = 5, maxLines = 10, enabled = !busy && retry == null)
        } else {
            Text("将新增 ${rows.size} 人。已有球员和历史记录会保留。")
            TextButton({ preview = null }, enabled = !busy && retry == null, modifier = Modifier.testTag("bulk-roster-edit")) { Text("返回编辑") }
            rows.forEach { row -> CardBlock {
                Text("第 ${row.line} 行 · ${row.player.name}", style = MaterialTheme.typography.titleSmall)
                Text(listOf(row.player.englishName.takeUnless { it == row.player.name }, row.player.jerseyNumbers.joinToString(" / ")).filterNot { it.isNullOrBlank() }.joinToString(" · "))
            } }
        }
        (inputIssue ?: issue)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        retry?.let { TextButton(it, enabled = !busy) { Text("续取同一次操作") } }
        recheck?.let { TextButton({ preview = null; it() }, enabled = !busy) { Text("保留输入并刷新名单") } }
    }
}
