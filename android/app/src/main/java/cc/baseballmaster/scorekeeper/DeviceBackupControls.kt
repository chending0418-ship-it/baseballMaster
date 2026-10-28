package cc.baseballmaster.scorekeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun DeviceBackupControls(model: ScorekeeperViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = model.deviceBackupBytes()
                withContext(Dispatchers.IO) { (context.contentResolver.openOutputStream(uri) ?: error("无法写入文件")).use { it.write(bytes) } }
                message = "本机备份已保存，包含球队、比赛、操作记录、纠错草稿及已导入 iOS 比赛的原始来源和续记；只读 .bmteam 档案仍须另存。"
            } catch (e: Exception) { message = "保存失败：${e.message ?: "请检查存储空间"}" } finally { busy = false }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    (context.contentResolver.openInputStream(uri) ?: error("无法打开文件")).use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 100_000_000) { "备份超过 100 MB" }; output.write(buffer, 0, n) }
                        output.toByteArray().also { AndroidDeviceBackup.decode(it) }
                    }
                }
                pending = bytes
            } catch (e: Exception) { message = "备份无法恢复：${e.message ?: "文件格式不受支持"}。原有资料未修改。" } finally { busy = false }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { save.launch("BaseballMaster-Android.bmandroid") }, enabled = !busy, modifier = Modifier.testTag("device-backup-save")) { Text("备份本机") }
        TextButton(onClick = { open.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.testTag("device-backup-restore")) { Text("恢复本机") }
    }
    Text("本机备份用于 Android 设备恢复；iOS 格式互导仍在开发。")
    if (pending != null) AlertDialog(onDismissRequest = { pending = null }, title = { Text("恢复 Android 本机备份？") }, text = { Text("将恢复备份中的球队、比赛、操作记录、纠错草稿与 iOS 来源备份。为保护原有资料，只有当前本机没有资料时才能恢复。") },
        confirmButton = { TextButton(onClick = {
            val bytes = pending ?: return@TextButton
            pending = null; busy = true
            scope.launch {
                try { model.restoreDeviceBackup(bytes); message = "本机资料已恢复。" }
                catch (e: Exception) { message = "恢复失败：${e.message ?: "备份不兼容"}。原有资料未修改。" }
                finally { busy = false }
            }
        }, enabled = !busy, modifier = Modifier.testTag("device-backup-confirm")) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { pending = null }, enabled = !busy) { Text("取消") } })
    if (message != null) AlertDialog(onDismissRequest = { message = null }, title = { Text("本机备份") }, text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { message = null }) { Text("完成") } })
}
