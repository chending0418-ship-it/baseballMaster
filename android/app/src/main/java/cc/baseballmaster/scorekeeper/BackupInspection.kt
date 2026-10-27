package cc.baseballmaster.scorekeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import cc.baseballmaster.core.AppleBackupEnvelope
import cc.baseballmaster.core.AppleRosterBackup
import cc.baseballmaster.core.Team
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The partial roster import is explicit; games and permissions are never silently restored. */
@Composable
internal fun BackupInspection(model: ScorekeeperViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var rosters by remember { mutableStateOf<List<Team>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val pair = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri) ?: error("无法打开备份")
                        val bytes = stream.use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var count: Int
                            while (input.read(buffer).also { count = it } != -1) {
                                require(output.size() + count <= 100_000_000) { "备份超过 100 MB" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val inspection = AppleBackupEnvelope.inspect(bytes)
                        inspection to runCatching { AppleRosterBackup.decode(inspection) }
                    }
                    rosters = pair.second.getOrNull()
                    result = "备份格式 ${pair.first.version}，${pair.first.teamCount} 支本队、${pair.first.opponentCount} 支对手、${pair.first.gameCount} 场比赛。外层校验与基础资料检查通过。\n\n可以选择仅导入球队名单；比赛、历史记录和共享权限不会导入。完整比赛互导正在开发。" +
                        (pair.second.exceptionOrNull()?.let { "\n\n名单暂不能导入：${it.message ?: "格式不受支持"}" } ?: "")
                } catch (failure: Exception) {
                    rosters = null
                    result = "无法核验这份备份：${failure.message ?: "文件格式不受支持"}。本次没有修改设备资料。"
                } finally { busy = false }
            }
        }
    }
    TextButton(onClick = { launcher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.testTag("inspect-ios-backup")) {
        Text(if (busy) "正在核验备份…" else "选择 iOS 备份文件")
    }
    if (result != null) AlertDialog(onDismissRequest = { result = null; rosters = null }, title = { Text("备份核验") }, text = { Text(result.orEmpty()) },
        confirmButton = {
            if (!rosters.isNullOrEmpty()) TextButton({ result = null; confirm = true }, Modifier.testTag("backup-import-rosters")) { Text("仅导入名单") }
        }, dismissButton = { TextButton({ result = null; rosters = null }) { Text("取消") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; rosters = null }, title = { Text("仅导入球队名单？") },
        text = { Text("将添加 ${rosters?.size ?: 0} 支球队或对手。比赛、逐球历史和共享权限不会导入；本机若有相同球队或球员编号，将拒绝整批导入，不覆盖现有资料。") },
        confirmButton = { TextButton(onClick = { rosters?.let { list -> model.importRosters(list) { confirm = false; rosters = null; imported = true } } }, enabled = !model.busy, modifier = Modifier.testTag("confirm-import-rosters")) { Text("确认导入名单") } },
        dismissButton = { TextButton({ confirm = false; rosters = null }, enabled = !model.busy) { Text("取消") } })
    if (imported) AlertDialog(onDismissRequest = { imported = false }, title = { Text("名单已导入") }, text = { Text("本次仅导入球队名单，比赛和共享权限未导入。") }, confirmButton = { TextButton({ imported = false }) { Text("完成") } })
}
