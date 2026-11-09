package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.baseballmaster.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BroadcastSourcesScreen(
    api: AndroidTeamSharing,
    teamID: String,
    canRotate: Boolean = false,
    currentGameID: String? = null,
    select: (suspend (BroadcastSelection) -> TeamBroadcastStatus?)? = null,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var sources by remember(teamID) { mutableStateOf<TeamBroadcastStatus?>(null) }
    var issue by remember(teamID) { mutableStateOf<String?>(null) }
    var busy by remember(teamID) { mutableStateOf(false) }
    var confirmation by remember(teamID) { mutableStateOf<SharingConfirmation?>(null) }
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                sources = null; confirmation = null; foreground = false
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    suspend fun runAction(block: suspend () -> TeamBroadcastStatus) {
        if (busy) return
        busy = true
        issue = null
        try {
            val current = block()
            if (foreground) sources = current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sources = null
            issue = if (e is TeamServiceFault && e.status in setOf(403, 404))
                "固定转播源暂不可用，请刷新核对球队权限。现场记分仍可继续。"
            else "暂时无法更新转播源，请刷新后重试。现场记分仍可继续。"
        } finally { busy = false }
    }
    LaunchedEffect(teamID, foreground) { if (foreground) runAction { api.broadcastSources(teamID) } }

    FormPage("球队固定转播源", busy, !busy, "refresh-broadcast-sources", "刷新", dismiss,
        { scope.launch { runAction { api.broadcastSources(teamID) } } }, secure = true, dismissLabel = "返回") {
        val current = sources
        Text("当前转播", style = MaterialTheme.typography.titleMedium)
        Text(current?.activeMatch ?: if (current?.activeGameID != null) "等待已选比赛同步" else "暂无比赛转播",
            modifier = Modifier.testTag("team-broadcast-current-match"))
        issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (current != null && select != null && currentGameID != null) {
            val same = current.activeGameID.equals(currentGameID, true)
            if (!same || current.activeMatch == null) TextButton(onClick = {
                confirmation = SharingConfirmation(if (same) "确认同步本场？" else "切换当前转播？", "四条固定链接会一起显示本场比赛。") {
                    scope.launch {
                        runAction {
                            val reply = select(BroadcastSelection(current.activeGameID))
                            if (reply?.selected != true) issue = "当前场次或记分权已变化，或本场尚未开赛。请核对后重试。"
                            api.broadcastSources(teamID)
                        }
                    }
                }
            }, enabled = !busy, modifier = Modifier.testTag("select-team-broadcast")) { Text(if (same) "同步本场比赛" else "切换到本场比赛") }
        }
        current?.code?.let { code ->
            Text("四条固定链接", style = MaterialTheme.typography.titleMedium)
            Text("把链接添加到 OBS 浏览器源。文字直播从比赛页面单独分享。")
            BroadcastSource.entries.forEach { source -> CardBlock {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(source.title); Text(source.size, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        SharingClipboard.copy(context.applicationContext, source.title, source.url(code))
                    }, Modifier.testTag("copy-broadcast-${source.name}"), enabled = !busy) { Text("复制链接") }
                    TextButton({
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, source.url(code))
                        context.startActivity(Intent.createChooser(intent, "分享转播源"))
                    }, Modifier.testTag("share-broadcast-${source.name}"), enabled = !busy) { Text("分享") }
                }
            } }
            if (canRotate) TextButton({
                confirmation = SharingConfirmation("重置四条链接？", "旧链接会失效。请把新链接发给导播。") {
                    scope.launch { runAction { api.rotateBroadcast(teamID, code) } }
                }
            }, Modifier.testTag("rotate-broadcast"), enabled = !busy) { Text("重置四条链接", color = MaterialTheme.colorScheme.error) }
        }
    }
    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(request.title) }, text = { Text(request.message) },
            confirmButton = { TextButton({ confirmation = null; request.action() }) { Text("确认") } },
            dismissButton = { TextButton({ confirmation = null }) { Text("取消") } })
    }
}
