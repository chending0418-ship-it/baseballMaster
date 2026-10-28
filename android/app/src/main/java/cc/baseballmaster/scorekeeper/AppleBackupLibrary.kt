package cc.baseballmaster.scorekeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppleBackupLibrary(model: ScorekeeperViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var original by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<AppleBackupSummary>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = selected ?: return@rememberLauncherForActivityResult
        val useOriginal = original
        selected = null
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = model.appleBackupBytes(id, useOriginal)
                withContext(Dispatchers.IO) {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入文件")).use { it.write(bytes) }
                }
                message = if (useOriginal) "已保存导入时的原始 iOS 文件，未改写历史。" else "已保存含续记的 iOS 来源备份；原始审计和其他比赛仍保留。实际 iOS 恢复仍待验证，请同时保管原始文件。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "保存失败：${e.message}。本机资料未修改；目标文件可能不完整，请重新保存。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(showing) {
        if (showing) {
            busy = true
            try { sources = model.appleBackupSummaries() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "无法读取来源备份：${e.message}" }
            finally { busy = false }
        }
    }
    TextButton(onClick = { showing = true }, modifier = Modifier.testTag("open-ios-sources")) { Text("已导入的 iOS 来源备份") }
    if (showing) FormPage("iOS 来源备份", busy || selected != null, true, "", "", { showing = false }, {}) {
        Text("原始来源与比赛在同一事务中保留；不恢复任何共享权限。含来源的 Android 本机备份也会保留这些原文件。")
        Text("旧格式和终场比赛目前只读；只有符合条件的格式 7 进行中比赛可续记投球，以及不触发换边、终场或规则确认的保送（含挤分）和非第三出局三振。其他操作明确拒绝，完整可编辑互导仍在开发。")
        if (sources.isEmpty() && !busy) Text("尚未导入 iOS 比赛")
        sources.forEach { source -> CardBlock {
            Text("${source.name} · ${source.gameCount} 场比赛")
            TextButton(onClick = { selected = source.id; original = true; save.launch("BaseballMaster-原始来源.bmbackup") }, enabled = !busy && selected == null) { Text("保存原始备份") }
            TextButton(onClick = { selected = source.id; original = false; save.launch("BaseballMaster-含续记.bmbackup") }, enabled = !busy && selected == null) { Text("保存含续记备份") }
        } }
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("iOS 来源备份") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("完成") } }) }
}
