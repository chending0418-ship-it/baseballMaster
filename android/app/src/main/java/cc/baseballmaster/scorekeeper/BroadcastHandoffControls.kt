package cc.baseballmaster.scorekeeper

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import cc.baseballmaster.core.MatchControl
import cc.baseballmaster.core.SharedMatchDocument
import cc.baseballmaster.core.TeamRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun SharedBroadcastHandoffControls(
    api: AndroidTeamSharing, broadcast: TeamBroadcastCoordinator, ui: SharingUi,
    document: SharedMatchDocument, scope: CoroutineScope, refresh: suspend () -> Unit,
) {
    val own = ui.state.deviceID ?: return
    val currentDeviceWrites = document.canWrite && document.writerDeviceID.equals(own, true)
    val handoff = document.handoff
    val pending = ui.state.matchPending[document.id]
    var choices by remember(document.id) { mutableStateOf<List<JsonObject>?>(null) }
    var confirm by remember(document.id) { mutableStateOf<SharingConfirmation?>(null) }

    suspend fun send() {
        val receipt = api.sendHandoff(document.id)
        broadcast.continueAfterHandoff(receipt)
        refresh()
    }

    fun confirmHandoff(kind: MatchControl, title: String, description: String, target: String? = null) {
        confirm = SharingConfirmation(title, description) {
            ui.call(scope) {
                api.prepareHandoff(document, kind, target, confirmRisk = kind == MatchControl.force)
                send()
            }
        }
    }

    if (pending != null) {
        ProductActionRow("续取交接结果", "使用原操作编号核对结果", tag = "retry-broadcast-handoff", enabled = !ui.busy) {
            ui.call(scope) { send() }
        }
    } else if (handoff != null) {
        val target = handoff["targetDeviceID"]?.jsonPrimitive?.contentOrNull
        val source = handoff["sourceDeviceID"]?.jsonPrimitive?.contentOrNull
        Text("正在等待确认记分交接，球队转播暂停接收新画面。", style = MaterialTheme.typography.bodySmall)
        if (target.equals(own, true)) ProductActionRow("接收记分权", tag = "accept-broadcast-handoff", enabled = ui.canPrepare) {
            confirmHandoff(MatchControl.accept, "接收这场比赛？", "接收后，这台设备可以继续更新原来的四条转播链接。")
        }
        if (source.equals(own, true)) ProductActionRow("取消记分交接", tag = "cancel-broadcast-handoff", enabled = ui.canPrepare) {
            confirmHandoff(MatchControl.cancel, "取消记分交接？", "比赛仍由当前设备负责。")
        }
    } else if (currentDeviceWrites) {
        ProductActionRow("交给其他记分设备", tag = "propose-broadcast-handoff", enabled = ui.canPrepare) {
            ui.call(scope) { choices = api.participants(document.teamID, document.id).mapNotNull { it as? JsonObject } }
        }
    }
    if (!document.writerDeviceID.equals(own, true) && ui.team?.role == TeamRole.admin && pending == null) {
        ProductActionRow("接管记分", tag = "takeover-broadcast-match", enabled = ui.canPrepare) {
            confirmHandoff(MatchControl.force, "接管到这台设备？",
                "旧设备尚未同步的记录可能无法恢复。接管后，已选中的转播继续使用原链接。", own)
        }
    }

    choices?.let { devices ->
        val available = devices.filterNot { it["deviceID"]?.jsonPrimitive?.contentOrNull.equals(own, true) }
        ProductPage("选择记分设备", onBack = { choices = null }, modal = true) {
            if (available.isEmpty()) Text("目前没有另一台具备记分权限的设备。")
            available.forEach { device ->
                val id = device["deviceID"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                ProductActionRow(device["label"]?.jsonPrimitive?.contentOrNull ?: "记分设备",
                    device["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(), tag = "broadcast-handoff-device-$id") {
                    choices = null
                    confirmHandoff(MatchControl.propose, "交给这台设备？", "对方确认前，转播暂不接收新画面。", id)
                }
            }
        }
    }
    confirm?.let { request ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirm = null },
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton({ confirm = null; request.action() }, Modifier.testTag("confirm-broadcast-handoff")) { Text("确认") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("取消") } },
        )
    }
}
