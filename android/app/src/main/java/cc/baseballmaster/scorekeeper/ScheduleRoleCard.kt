package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cc.baseballmaster.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
internal fun ScheduleRoleCard(notice: TeamScheduleNotice, model: ScorekeeperViewModel, local: Game?) = key(notice.key) {
    val session = model.sharing.session
    var detail by remember { mutableStateOf<ScheduleScoringDetail?>(null) }
    var issue by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf<WriterBinding?>(null) }
    var members by remember { mutableStateOf<List<SharedTeamMember>?>(null) }
    var sharedRoster by remember { mutableStateOf<Team?>(null) }
    var edit by remember { mutableStateOf<Pair<Boolean, ScheduleScoringDetail>?>(null) }
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val admin = model.canManageRemoteSchedule(notice)
    fun failed(e: Exception) {
        if (e is CancellationException) throw e
        if (e is TeamServiceFault && e.status in listOf(401,403,404,426)) {
            detail = null; edit = null; members = null
        }
        issue = e.localizedMessage ?: "请联网刷新本场岗位"
    }
    suspend fun refresh(checkCapability: Boolean) {
        val next = withContext(Dispatchers.IO) { model.scheduleWriter.status(notice, checkCapability) }
        if (next.access.role == "scorer" && next.access.holdsDevice && next.proposal != null && !next.access.closed && next.access.transfer == null) {
            runCatching { withContext(Dispatchers.IO) { model.scheduleWriter.sync(notice) } }.onFailure { if (it is CancellationException) throw it; issue = it.localizedMessage }
        }
        if (edit != null && (edit?.second?.access?.epoch != next.access.epoch || !next.access.holdsDevice || next.access.closed)) edit = null
        offline = null
        detail = next
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            lock.withLock {
                try { issue = null; action(); refresh(false) }
                catch (e: Exception) { failed(e) }
                finally { busy = false }
            }
        }
    }
    LaunchedEffect(notice.key, lifecycle, notice.cancelled, notice.scoring?.epoch) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var checked = false
            while (isActive) {
                lock.withLock {
                    try { refresh(!checked); checked = true }
                    catch (e: Exception) {
                        failed(e)
                        offline = if (e is java.io.IOException && (e !is TeamServiceFault || e.status >= 500))
                            withContext(Dispatchers.IO) { model.scheduleWriter.cached(notice.id.lowercase()) }?.takeIf { it.opening }
                        else null
                    }
                }
                delay(if (detail?.access?.role == "lineup") 5000L else 15000L)
            }
        }
    }
    val current = detail
    val role = current?.access
    val draft = current?.draft
    val receipt = current?.receipt?.takeIf { it.id == draft?.id }
    val editing = edit
    if (editing != null) {
        val (home, base) = editing
        val game = remember(base) { runCatching { base.frame?.editingView(notice.id) }.getOrNull() }
        val own = game?.let { if (base.frame?.isHome == true) it.home else it.away }
        val shared = sharedRoster?.let { team -> own?.let { team.copy(id = it.id) } }
        val rosters = if (shared != null && own != null) {
            val localPlayers = model.rosters.firstOrNull { it.id.equals(own.id,true) }?.players.orEmpty()
            listOf(shared.copy(players = (shared.players + localPlayers).distinctBy { it.id.lowercase() })) + model.rosters
        } else model.rosters
        LaunchedEffect(base.access.epoch, base.access.lineup) {
            try { sharedRoster = model.sharing.roster(notice.teamID).gameTeam() }
            catch (e: Exception) { failed(e) }
        }
        if (game != null) ParityLineupEditor(game, busy, { edit = null }, home, rosters) { command ->
            perform {
                withContext(Dispatchers.IO) { session.saveLineupDraft(notice, base, command) }
                edit = null
                detail = base.copy(draft = withContext(Dispatchers.IO) { session.snapshot().lineupDrafts[notice.key] })
                withContext(Dispatchers.IO) { session.sendLineupDraft(notice) }
            }
        } else LaunchedEffect(base) { edit = null; issue = "本场阵容暂不能在 Android 上编辑，请核对人数和守位。" }
    }
    CardBlock {
        Text("本场记录员", fontWeight = FontWeight.Bold)
        Text(role?.label ?: notice.scoring?.label ?: "正在核对岗位")
        role?.transfer?.let { Text("局面记录待交接给${it.targetName}") }
        if (issue != null) Text(issue!!, color = MaterialTheme.colorScheme.error)
        if (offline != null) Button({ model.recordingSchedule = notice; model.open(notice.id.lowercase()) }) { Text("继续本机局面记录") }
        if (current == null) TextButton({ perform { refresh(true) } }, enabled = !busy) { Text("刷新岗位") }
        if (current != null && role != null && !role.closed && !notice.cancelled) {
            if (admin) TextButton(enabled = !busy, onClick = { perform {
                members = model.sharing.members(notice.teamID).filter { it.role != TeamRole.member }
            } }) { Text("安排记录员") }
            if (role.canPrepare && local?.isScheduled == true) TextButton(enabled = !busy, onClick = { perform {
                withContext(Dispatchers.IO) { session.prepareSchedule(notice, current, if (local.scheduledSetupPending) {
                    fun updated(team: Team) = model.rosters.firstOrNull { it.id == team.id && !it.archived }
                        ?.let { it.copy(players = it.players.filterNot(Player::archived)) } ?: team
                    local.copy(home = updated(local.home), away = updated(local.away))
                } else local) }
            } }) { Text("提供本机双方先发") }
            when (role.role) {
                "viewer" -> Text("本场仅供查看", style = MaterialTheme.typography.bodySmall)
                "scorer" -> {
                    if (role.transfer != null && role.holdsDevice) Button({ perform {
                        withContext(Dispatchers.IO) { model.scheduleWriter.release(notice) }
                    } },enabled=!busy) { Text("交接完整逐球记录") }
                    else if (current.frame == null) Text("等待发布人提供双方先发")
                    else if (role.transfer == null) Button({ perform { model.enterWriter(notice) } },enabled=!busy) {
                        Text(when {
                            role.hasCheckpoint -> "接收完整逐球记录"
                            role.openingConfirmed -> "进入局面记录"
                            role.openingReady || role.lineup == null -> "确认双方先发并记局面"
                            else -> "领取局面岗位，等待先发"
                        })
                    }
                    if (role.holdsDevice && current.pending != null && issue != null) TextButton({ perform {
                        withContext(Dispatchers.IO) { model.scheduleWriter.sync(notice,reject=current.pending) }
                    } }) { Text("退回这笔阵容调整") }
                }
                "lineup" -> {
                    if (current.frame == null) Text("等待发布人提供双方先发")
                    else if (!role.holdsDevice) Button({ perform { withContext(Dispatchers.IO) {
                        session.claimLineup(notice, role.epoch)
                    } } }, enabled = !busy) { Text("在此设备领取阵容岗位") }
                    else {
                        val frame = current.frame!!
                        val preview = remember(frame) { runCatching { frame.editingView(notice.id) }.getOrNull() }
                        if (preview != null) {
                            Text("${preview.away.shortName} ${preview.awayRuns} – ${preview.homeRuns} ${preview.home.shortName}　${preview.inning}局${if (preview.isTop) "上" else "下"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (home in listOf(false,true)) OutlinedButton({ edit = home to current },
                                    enabled = !busy && current.pending == null && role.transfer == null && (draft == null || receipt != null)) {
                                    Text(if (home) "主队阵容" else "客队阵容")
                                }
                            }
                            if (!role.openingConfirmed && !role.openingReady) Button(onClick = { perform {
                                withContext(Dispatchers.IO) { session.confirmLineup(notice, current) }
                            } }, enabled = !busy && current.pending == null && (draft == null || receipt != null)) { Text("确认双方先发") }
                            if (role.openingReady && !role.openingConfirmed) Text("双方先发已提交，等待局面员确认")
                        } else Text("本场阵容无法完整读取，请核对人数和守位。")
                    }
                }
            }
        }
        if (draft != null) {
            Text(when {
                receipt != null -> if (receipt.applied) "阵容调整已生效" else "阵容调整已退回"
                current.pending == draft.id -> "已送达，等待局面设备核对"
                role?.epoch != draft.epoch || role.closed || !role.holdsDevice -> "岗位已变化，原草稿保留供核对"
                else -> "本机草稿待同步"
            })
            Text(draft.summary, style = MaterialTheme.typography.bodySmall)
            if (current.pending != draft.id && receipt == null)
                TextButton({ discard = draft.id }, enabled = !busy) { Text("放弃这笔草稿") }
            if (receipt == null && current.pending == null && role?.epoch == draft.epoch && role.holdsDevice)
                TextButton({ perform { withContext(Dispatchers.IO) { session.sendLineupDraft(notice) } } }, enabled = !busy) { Text("重试发送") }
        }
        if (role?.closed == true || notice.cancelled) Text("本场任务已关闭")
    }
    discard?.let { id ->
        AlertDialog(onDismissRequest = { discard = null }, title = { Text("放弃本机草稿？") },
            text = { Text("将联网核对送达状态。已送达的操作会保留。") },
            confirmButton = { TextButton({ discard = null; perform { withContext(Dispatchers.IO) { session.discardLineupDraft(notice,id) } } }) { Text("确认放弃") } },
            dismissButton = { TextButton({ discard = null }) { Text("保留") } })
    }
    val eligible = members
    if (admin && eligible != null && role != null) key(role.epoch) {
        ScorerSelection(role, eligible, { members = null }) { scorer, lineup ->
            members = null
            perform { withContext(Dispatchers.IO) { session.assignScorers(notice, role.epoch, scorer, lineup) } }
        }
    }
}

@Composable
private fun ScorerSelection(role: ScheduleRole, members: List<SharedTeamMember>, close: () -> Unit,
    save: (String?, String?) -> Unit) {
    var scorer by remember { mutableStateOf(role.scorer) }
    var lineup by remember { mutableStateOf(role.lineup) }
    AlertDialog(onDismissRequest = close, title = { Text("安排记录员") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MemberPicker("整场／局面记录员", members, scorer, "待指派") { scorer = it; if (it == null || it == lineup) lineup = null }
            MemberPicker("阵容记录员", members.filter { it.id != scorer }, lineup, "由局面员兼任", scorer != null) { lineup = it }
        }
    }, confirmButton = { TextButton({ save(scorer,lineup) }, enabled = scorer != role.scorer || lineup != role.lineup) { Text("保存指派") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable
private fun MemberPicker(label: String, members: List<SharedTeamMember>, selected: String?, empty: String,
    enabled: Boolean = true, choose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton({ open = true }, enabled = enabled) { Text(members.find { it.id == selected }?.nickname ?: empty) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(empty) }, { choose(null); open = false })
            for (member in members) DropdownMenuItem({ Text(member.nickname + " · " + member.id.takeLast(4)) },
                { choose(member.id); open = false })
        }
    }
}
