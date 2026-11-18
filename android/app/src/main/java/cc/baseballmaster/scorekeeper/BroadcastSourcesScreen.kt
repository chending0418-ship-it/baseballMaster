package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cc.baseballmaster.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LocalMatchBroadcastScreen(game: Game, broadcast: TeamBroadcastCoordinator, close: () -> Unit) {
    var target by remember(game.id) { mutableStateOf<BroadcastTarget?>(null) }
    var loading by remember(game.id) { mutableStateOf(true) }
    LaunchedEffect(game.id) {
        try {
            target = broadcast.target(game)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            target = null
        } finally {
            loading = false
        }
    }
    val selected = target
    if (selected == null) ProductPage("转播源", onBack = close, backLabel = "返回比赛", modal = true) {
        Text(if (loading) "正在核对球队授权…" else "请用管理员或记录员身份加入本队后，再打开本场转播源。",
            style = MaterialTheme.typography.bodyMedium)
    } else BroadcastSourcesScreen(
        broadcast, selected.teamID, currentGameID = selected.gameID, canStart = !game.isPregame && !game.isFinal,
        select = { expected -> broadcast.selectLocal(game.id, selected.teamID, expected) },
        dismiss = close,
    )
}

@Composable
internal fun BroadcastSourcesScreen(
    broadcast: TeamBroadcastCoordinator,
    teamID: String,
    allowReset: Boolean = false,
    currentGameID: String? = null,
    canStart: Boolean = true,
    select: (suspend (BroadcastSelection) -> TeamBroadcastStatus?)? = null,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var current by remember(teamID) { mutableStateOf<TeamBroadcastStatus?>(null) }
    var issue by remember(teamID) { mutableStateOf<String?>(null) }
    var busy by remember(teamID) { mutableStateOf(false) }
    var confirm by remember(teamID) { mutableStateOf<SharingConfirmation?>(null) }

    suspend fun refresh() {
        current = broadcast.sources(teamID)
    }
    suspend fun runAction(reconcileError: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        issue = null
        try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (fault: TeamServiceFault) {
            current = null
            if (reconcileError && fault.status == 409) {
                try { refresh() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
            issue = when (fault.code) {
                "broadcast_changed", "broadcast_revision" -> "转播源已有更新，请核对当前场次后重试。"
                "broadcast_writer_changed" -> "记分权已变更，请刷新球队比赛后继续。"
                "broadcast_not_started" -> "请先开始本场比赛。"
                else -> when (fault.status) {
                    401, 403 -> "当前设备无权管理转播源。请刷新球队授权。"
                    404 -> "球队或固定转播服务暂不可用，请刷新后重试。"
                    else -> "转播源暂时无法更新，请刷新后重试。"
                }
            }
        } catch (_: Exception) {
            current = null
            issue = "转播源暂时无法更新，请刷新后重试。"
        } finally {
            busy = false
        }
    }
    LaunchedEffect(teamID, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runAction { refresh() }
            try { kotlinx.coroutines.awaitCancellation() }
            finally { current = null; confirm = null }
        }
    }
    val state = current
    ProductPage(
        "转播源", onBack = dismiss, modal = true,
        headerTrailing = {
            TextButton({ scope.launch { runAction { refresh() } } }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("refresh-broadcast-sources")) { Text("刷新") }
        },
    ) {
        Text("当前转播", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Text(when {
                        state?.activeMatch != null -> state.activeMatch.orEmpty()
                        state?.activeGameID != null -> "等待本场同步"
                        state != null -> "暂无比赛转播"
                        busy -> "正在读取转播源…"
                        else -> "未能读取当前转播"
                    }, Modifier.testTag("team-broadcast-current-match"), style = MaterialTheme.typography.bodyMedium)
                }
                if (state != null && select != null && currentGameID != null &&
                    !state.activeGameID.equals(currentGameID, true)) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    TextButton(onClick = {
                        confirm = SharingConfirmation("切换当前转播？", "四条固定链接会一起切换到本场比赛。") {
                            scope.launch {
                                runAction(reconcileError = true) {
                                    val accepted = select(BroadcastSelection(state.activeGameID))?.selected == true
                                    refresh()
                                    if (!accepted) issue = "本场尚未开赛，或当前场次已变化，请核对后重试。"
                                }
                            }
                        }
                    }, enabled = canStart && !busy, modifier = Modifier.fillMaxWidth().testTag("select-team-broadcast")) {
                        Text(if (canStart) "切换到本场比赛" else "开始比赛后即可切换到本场转播",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    }
                }
                issue?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("team-broadcast-error"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        state?.code?.let { code ->
            Text("固定链接", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    BroadcastSource.entries.forEachIndexed { index, source ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .testTag("team-broadcast-source-${source.name}")
                                .combinedClickable(
                                    enabled = !busy, role = Role.Button,
                                    onClickLabel = "分享${source.title}链接",
                                    onLongClickLabel = "复制${source.title}链接",
                                    onLongClick = {
                                        SharingClipboard.copy(context.applicationContext, source.title, broadcast.url(source, code))
                                    },
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, broadcast.url(source, code))
                                        context.startActivity(Intent.createChooser(intent, "分享转播源"))
                                    },
                                ).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(source.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(source.size, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Outlined.Share, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Text("把链接分享给导播。球队开赛后会自动更新这套链接。文字直播在比赛中单独分享。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (allowReset) TextButton({
                confirm = SharingConfirmation("重置四条链接？", "旧链接会失效。请把新链接发给导播。") {
                    scope.launch { runAction(reconcileError = true) { current = broadcast.rotate(teamID, code) } }
                }
            }, Modifier.heightIn(min = 48.dp).testTag("rotate-broadcast"), enabled = !busy) {
                Text("重置四条链接", color = MaterialTheme.colorScheme.error)
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
                TextButton({ confirm = null; request.action() }, Modifier.testTag("confirm-team-broadcast")) { Text("确认") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("取消") } },
        )
    }
}
