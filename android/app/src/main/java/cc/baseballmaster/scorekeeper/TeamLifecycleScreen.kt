package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import cc.baseballmaster.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun teamDate(value: Long)=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))

/** Current DissolveTeamView / DissolvedTeamsView / TeamAuditView; no new service semantics. */
@Composable internal fun TeamLifecycleScreen(api: AndroidTeamSharing, page: String, teamID: String, dismiss: ()->Unit) {
    val ui=remember(api) {SharingUi(api).also {it.selectedID=teamID}};val scope=rememberCoroutineScope()
    var profile by remember {mutableStateOf<SharedTeamProfile?>(null)}
    var dissolved by remember {mutableStateOf<List<DissolvedTeam>>(emptyList())}
    var name by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf<SharingConfirmation?>(null)}
    var completed by remember {mutableStateOf(false)}
    var events by remember {mutableStateOf<List<TeamAuditEvent>>(emptyList())};var actions by remember {mutableStateOf<List<String>>(emptyList())}
    var days by remember {mutableIntStateOf(30)};var action by remember {mutableStateOf("")}
    var before by remember {mutableStateOf<Long?>(null)};var more by remember {mutableStateOf(false)}
    var filterMenu by remember {mutableStateOf(false)}
    suspend fun loadAudit(reset: Boolean) {
        val from=if(days==0) 0 else System.currentTimeMillis()-days*86_400_000L
        if(reset) {events=emptyList();before=null;more=false}
        try {
            val result=api.audit(teamID,from,action,if(reset) null else before)
            events=if(reset) result.events else (events+result.events).distinctBy {it.id}
            actions=result.actions;before=result.before;more=result.hasMore
        } catch(problem: Exception) {events=emptyList();more=false;throw problem}
    }
    suspend fun refresh() {
        api.refresh();ui.state=api.state();ui.enabled=ui.state.directory!=null
        when(page) {
            "dissolve" -> {profile=null;profile=api.roster(teamID,true)}
            "restore" -> {dissolved=emptyList();dissolved=api.dissolvedTeams()}
            else -> loadAudit(true)
        }
        ui.state=api.state()
    }
    var baseline by remember {mutableStateOf<String?>(null)};var loaded by remember {mutableStateOf(false)}
    LaunchedEffect(api) {ui.call(scope) {ui.state=api.state();baseline=ui.state.lastCompletion?.request?.id;loaded=true;refresh()}}
    LaunchedEffect(ui.state.lastCompletion?.request?.id,loaded) {
        val receipt=ui.state.lastCompletion
        if(loaded && receipt!=null && receipt.request.id!=baseline) {
            baseline=receipt.request.id
            if(receipt.request.securityAction==TeamSecurityAction.teamDissolve) completed=true
            if(receipt.request.securityAction==TeamSecurityAction.teamRestore) {
                completed=true;dissolved=dissolved.filterNot {it.id.equals(receipt.request.targetID,true)}
                if(!ui.busy) ui.call(scope){refresh()}
            }
        }
    }
    val title=when(page) {"dissolve"->"解散球队";"restore"->"已解散的球队";else->"球队操作记录"}
    FormPage(title,ui.busy,!ui.busy && !(page=="dissolve" && completed),"refresh-team-lifecycle","刷新",dismiss,{ui.call(scope){refresh()}},dismissLabel="返回") {
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(ui.state.requiresRecoveryReview) Text("请先完成服务恢复后的核对，原操作保持。")
        ui.state.pending?.let {pending -> CardBlock {
            Text("有一笔操作结果待确认，不会更换编号重做。")
            TextButton(enabled=!ui.busy && !ui.state.requiresRecoveryReview,onClick={ui.retry(scope)}) {Text("续取结果")}
            if(ui.state.lastFailure?.status in setOf(400,403,404,409,410,413,426)) TextButton(enabled=!ui.busy && !ui.state.requiresRecoveryReview,onClick={confirmation=SharingConfirmation("保留原操作待核对","原操作可能已经执行过。归档后不会自动重做，请先核对当前资料。") {
                ui.call(scope){api.archiveRejectedOperation(pending.id,true);refresh()}
            }}) {Text("保留为待核对")}
        } }
        when(page) {
            "dissolve" -> {
                val current=profile
                if(completed) Text("球队已解散。成员访问、记分、邀请和观看链接已关闭；可在 30 天内从“已解散的球队”恢复档案。")
                else if(current!=null) {
                    Text("解散「${current.name}」",fontWeight=FontWeight.Bold)
                    Text("这是对全队生效的操作。所有成员立即停止访问、记分和分享；原本机资料保留。请先导出需要留存的球队档案。")
                    Text("解散前管理员的原设备可在 30 天内恢复档案，成员与观看权限需要重新授权。超过期限将清除档案，备份随后按保留期限到期。")
                    OutlinedTextField(name,{name=it},enabled=ui.canPrepare,label={Text("输入完整球队名称")},modifier=Modifier.testTag("dissolve-team-name"))
                    TextButton(enabled=ui.canPrepare && name==current.name,onClick={val typed=name;confirmation=SharingConfirmation("确认解散这支球队？","全队成员将无法继续使用共享资料，所有直播和历史观看链接将关闭。") {
                        ui.mutate(scope) {api.prepareTeamDissolution(current,typed,true)}
                    }},modifier=Modifier.testTag("confirm-team-dissolution")) {Text("解散球队")}
                }
            }
            "restore" -> {
                Text("解散前管理员的原设备，可在 30 天内恢复球队档案。恢复后只有执行恢复的这台设备保留管理权限，需要重新邀请成员、生成并保存新的恢复卡和设置观看方式。")
                if(completed) Text("恢复回执已保存，请返回共享列表核对球队，并生成及另存新恢复卡。")
                if(dissolved.isEmpty()) Text("这台设备没有可恢复的已解散球队。")
                dissolved.forEach {team -> CardBlock {
                    Text(team.name,fontWeight=FontWeight.Bold);Text("恢复期限：${teamDate(team.expiresAt)}")
                    TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("恢复球队档案？","恢复「${team.name}」的名单和比赛。原成员、邀请、观看链接和恢复卡不会重新生效。恢复后请保存新恢复卡。") {
                        ui.mutate(scope) {api.prepareTeamRestoration(team,true)}
                    }},modifier=Modifier.testTag("restore-dissolved-team")) {Text("恢复球队档案")}
                } }
            }
            else -> {
                Text("称呼和设备名称用于队内核对，不代表实名认证。仅管理员可查看。")
                Choices("时间范围",mapOf("7" to "最近 7 天","30" to "最近 30 天","0" to "全部记录"),days.toString()) {value ->
                    if(!ui.busy) {days=value.toInt();ui.call(scope){loadAudit(true)}}
                }
                Box {
                    TextButton(enabled=!ui.busy,onClick={filterMenu=true}) {Text("操作类型：${if(action.isEmpty()) "全部操作" else teamAuditTitle(action)}")}
                    DropdownMenu(expanded=filterMenu,onDismissRequest={filterMenu=false}) {
                        (listOf("")+actions).forEach {kind -> DropdownMenuItem(text={Text(if(kind.isEmpty()) "全部操作" else teamAuditTitle(kind))},onClick={filterMenu=false;action=kind;ui.call(scope){loadAudit(true)}}) }
                    }
                }
                events.forEach {event -> CardBlock {
                    Text(teamAuditTitle(event.action),fontWeight=FontWeight.Bold);Text("${event.nickname} · ${event.deviceName}")
                    Text(teamDate(event.occurredAt));Text("记录编号 ${event.id}")
                } }
                if(events.isEmpty() && !ui.busy) Text("此范围内暂无操作记录。")
                if(more) TextButton(enabled=!ui.busy,onClick={ui.call(scope){loadAudit(false)}}) {Text("更早的记录")}
            }
        }
    }
    confirmation?.let {value -> AlertDialog(onDismissRequest={confirmation=null},title={Text(value.title)},text={Text(value.message)},confirmButton={TextButton(onClick={confirmation=null;value.action()}) {Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}) {Text("取消")}}) }
}
