package cc.baseballmaster.scorekeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import cc.baseballmaster.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Explicit choices: roster-only import or an atomically retained, supported source-backed match set. */
@Composable
internal fun BackupInspection(model: ScorekeeperViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var rosters by remember { mutableStateOf<List<Team>?>(null) }
    var source by remember { mutableStateOf<ByteArray?>(null) }
    var gameCount by remember { mutableIntStateOf(0) }
    var confirmRosters by remember { mutableStateOf(false) }
    var confirmGames by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun clear() { result = null; rosters = null; source = null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) {
                        val bytes = (context.contentResolver.openInputStream(uri) ?: error("无法打开备份")).use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 100_000_000) { "备份超过 100 MB" }; output.write(buffer, 0, count) }
                            output.toByteArray()
                        }
                        val inspection = AppleBackupEnvelope.inspect(bytes)
                        Triple(bytes, inspection, runCatching { AppleSourceBackup.prepare(bytes) })
                    }
                    val rosterResult = withContext(Dispatchers.IO) { runCatching { AppleRosterBackup.decode(selected.second) } }
                    rosters = rosterResult.getOrNull()
                    source = if (selected.third.isSuccess) selected.first else null
                    gameCount = selected.second.gameCount
                    result = "备份格式 ${selected.second.version}，${selected.second.teamCount} 支本队、${selected.second.opponentCount} 支对手、$gameCount 场比赛。\n\n" +
                        (if (source != null) "可以导入本批比赛及原始来源文件。比赛先作为来源保护的只读记录导入；只有符合条件的格式 7 进行中比赛可续记投球，以及不触发换边、终场或规则确认的保送（含挤分）和非第三出局三振，其他编辑会明确拒绝。原始历史、纠错审计和备份中的其他资料完整留在来源文件中，不恢复共享权限。" else "整批比赛暂不能无损导入：${selected.third.exceptionOrNull()?.message}。不会跳过不兼容场次后假装完成导入。") +
                        "\n\n也可以明确选择仅导入球队名单；这不会导入任何比赛。" + (rosterResult.exceptionOrNull()?.let { "\n名单也无法导入：${it.message}" } ?: "")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { clear(); result = "无法核验这份备份：${e.message}。本次没有修改本机资料。" }
                finally { busy = false }
            }
        }
    }
    TextButton(onClick = { launcher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, enabled = !busy && !model.busy, modifier = Modifier.testTag("inspect-ios-backup")) { Text(if (busy) "正在核验备份…" else "选择 iOS 备份文件") }
    AppleBackupLibrary(model)
    result?.let { text -> AlertDialog(onDismissRequest = { clear() }, title = { Text("备份核验") }, text = { Text(text) },
        confirmButton = { Column {
            if (source != null) TextButton(onClick = { result = null; confirmGames = true }, modifier = Modifier.testTag("backup-import-ios-games")) { Text("导入比赛及来源") }
            if (!rosters.isNullOrEmpty()) TextButton(onClick = { result = null; confirmRosters = true }, modifier = Modifier.testTag("backup-import-rosters")) { Text("仅导入名单") }
        } }, dismissButton = { TextButton(onClick = { clear() }) { Text("取消") } }) }
    if (confirmRosters) AlertDialog(onDismissRequest = { confirmRosters = false; clear() }, title = { Text("仅导入球队名单？") },
        text = { Text("将添加 ${rosters?.size ?: 0} 支球队或对手。比赛、逐球历史和共享权限不会导入；编号冲突时整批拒绝，不覆盖已有资料。") },
        confirmButton = { TextButton(onClick = { rosters?.let { list -> model.importRosters(list) { confirmRosters = false; clear(); notice = "本次仅导入名单，比赛及权限未导入。" } } }, enabled = !model.busy, modifier = Modifier.testTag("confirm-import-rosters")) { Text("确认导入名单") } },
        dismissButton = { TextButton(onClick = { confirmRosters = false; clear() }, enabled = !model.busy) { Text("取消") } })
    if (confirmGames) AlertDialog(onDismissRequest = { if (!model.busy) { confirmGames = false; clear() } }, title = { Text("导入比赛及原始来源？") },
        text = { Text("将导入 $gameCount 场已核对的比赛及球队，并保留完整原始备份。任一冲突或写入失败都会整批回滚。终场和旧格式比赛仅供查看；只有符合条件的格式 7 进行中比赛可续记投球，以及不触发换边、终场或规则确认的保送（含挤分）和非第三出局三振。旧式个人记录、共享或无法重放的复杂比赛会整批拒绝，不部分导入。正式完整互导仍未验收。") },
        confirmButton = { TextButton(onClick = { source?.let { bytes -> model.importAppleBackup(bytes) { receipt -> confirmGames = false; clear(); notice = if (receipt.alreadyPresent) "相同来源已导入；保留现有续记，没有重新覆盖。" else "已导入 ${receipt.gameCount} 场比赛及原始来源。可在“已导入的 iOS 来源备份”中另存原始文件或含续记的文件。" } } }, enabled = !model.busy, modifier = Modifier.testTag("confirm-import-ios-games")) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = { confirmGames = false; clear() }, enabled = !model.busy) { Text("取消") } })
    notice?.let { text -> AlertDialog(onDismissRequest = { notice = null }, title = { Text("导入结果") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("完成") } }) }
}
