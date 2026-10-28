package cc.baseballmaster.scorekeeper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.baseballmaster.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private fun TeamRole.title()=when(this) { TeamRole.admin->"管理员"; TeamRole.scorer->"记录员"; TeamRole.member->"成员" }
private class SharingUi(private val api: AndroidTeamSharing) {
    var state by mutableStateOf(TeamDirectoryState()); var enabled by mutableStateOf(false); var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null); var selectedID by mutableStateOf("")
    var members by mutableStateOf<List<SharedTeamMember>>(emptyList()); var invitations by mutableStateOf<List<TeamInvitation>>(emptyList())
    val team get()=state.directory?.teams?.singleOrNull { it.id==selectedID }
    val roster get()=state.profiles[selectedID]?.profile
    val canPrepare get()=enabled && !busy && state.pending==null && !state.requiresRecoveryReview
    fun call(scope: CoroutineScope, block: suspend ()->Unit) {
        if(busy) return
        busy=true; error=null
        scope.launch {
            try { block() }
            catch(cancel: CancellationException) { throw cancel }
            catch(fault: TeamServiceFault) { error=fault.message }
            catch(problem: Exception) { error=if(problem is kotlinx.serialization.SerializationException) "共享资料暂时无法读取，原资料已保留" else if(problem is IllegalArgumentException || problem is IllegalStateException) problem.message else "共享操作未确认，原资料已保留，请刷新或续取" }
            finally {
                runCatching { api.state() }.onSuccess { state=it }
                if(team?.role!=TeamRole.admin) {members=emptyList();invitations=emptyList()}
                busy=false
            }
        }
    }
    suspend fun refresh() {
        api.refresh(); state=api.state(); enabled=true
        if(team!=null) {
            api.roster(selectedID); state=api.state()
            if(team?.role==TeamRole.admin) { members=api.members(selectedID); invitations=api.invitations(selectedID) }
        } else { members=emptyList();invitations=emptyList() }
    }
    fun mutate(scope: CoroutineScope, prepared: ()->Unit = {}, operation: suspend ()->String) = call(scope) {
        operation(); prepared(); api.sendPending()
        try { refresh() } catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {error="操作回执已保存，暂未刷新，请点刷新核对"}
    }
}
private data class SharingConfirmation(val title: String, val message: String, val action: ()->Unit)
private object SharingClipboard {
    private val handler=Handler(Looper.getMainLooper()); private var expiry: Runnable?=null
    fun copy(context: Context, label: String, value: String) {
        val clipboard=context.getSystemService(ClipboardManager::class.java)
        val clip=ClipData.newPlainText(label,value)
        clip.description.extras=PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
        clipboard.setPrimaryClip(clip); expiry?.let(handler::removeCallbacks)
        val task=Runnable {
            if(clipboard.primaryClip?.getItemAt(0)?.text?.toString()==value) clipboard.clearPrimaryClip()
            expiry=null
        }
        expiry=task; handler.postDelayed(task,300_000)
    }
}

@Composable internal fun TeamSharingScreen(api: AndroidTeamSharing, localTeams: List<Team>, dismiss: ()->Unit) {
    val ui=remember(api) {SharingUi(api)}; val scope=rememberCoroutineScope(); val context=LocalContext.current
    var editor by remember { mutableStateOf<String?>(null) }; var player by remember {mutableStateOf<SharedRosterPlayer?>(null)}
    var confirmation by remember {mutableStateOf<SharingConfirmation?>(null)}
    var secret by remember {mutableStateOf<Pair<String,String>?>(null)}; var shownCard by remember {mutableStateOf<SavedManagementCard?>(null)}
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) {secret=null;shownCard=null;confirmation=null} }
        lifecycle.lifecycle.addObserver(observer); onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(api) { runCatching {api.state()}.onSuccess {ui.state=it} }
    FormPage("球队共享",ui.busy,!ui.busy,"shared-refresh",if(ui.enabled) "刷新" else "启用球队共享",dismiss,{ui.call(scope){ui.refresh()}}) {
        Text("仅受邀成员可访问。无需注册账号；本机球队与共享名册独立。")
        if(!ui.enabled) Text("启用后将连接球队服务核对设备授权，不会自动上传本机球队或比赛。")
        if(!ui.enabled && !ui.state.directory?.teams.isNullOrEmpty()) Text("以下为本机缓存，启用并刷新后才能核对当前访问权限。")
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(ui.state.requiresRecoveryReview) Text("服务恢复后需要核对，旧操作已保留，当前不会自动重做。",color=MaterialTheme.colorScheme.error)
        ui.state.pending?.let {pending ->
            CardBlock {
                Text("有一笔操作结果待确认",fontWeight=FontWeight.Bold)
                Text("重试将续取同一请求。确认前不能另建或更改操作内容。")
                TextButton(enabled=!ui.busy,onClick={ui.call(scope){api.sendPending();ui.refresh()}}) {Text("续取结果")}
                if(ui.state.lastFailure?.status in setOf(400,403,404,409,410,413,426)) TextButton(enabled=!ui.busy,onClick={confirmation=SharingConfirmation("保留为待核对","原操作可能已执行过，当前授权可能无法续取。保留原请求后请先核对最新资料；不会自动重做。") {
                    ui.call(scope){api.archiveRejectedOperation(pending.id,true);ui.refresh()}
                }}) {Text("保留为待核对")}
            }
        }
        if(ui.selectedID.isEmpty()) {
            Row {
                TextButton(enabled=ui.canPrepare,onClick={editor="create"}) {Text("新建共享球队")}
                TextButton(enabled=ui.canPrepare,onClick={editor="join"}) {Text("通过邀请加入")}
            }
            Text("已加入的球队",fontWeight=FontWeight.Bold)
            if(ui.enabled && ui.state.directory?.teams.isNullOrEmpty()) Text("尚未加入共享球队。")
            ui.state.directory?.teams.orEmpty().forEach {team -> CardBlock {
                Text(team.name,fontWeight=FontWeight.Bold); Text("${team.role.title()} · 编号 ${team.id.takeLast(6)}")
                TextButton(enabled=ui.enabled && !ui.busy,onClick={ui.selectedID=team.id;ui.call(scope){ui.refresh()}}) {Text("查看球队")}
            } }
        } else {
            TextButton(onClick={ui.selectedID="";ui.members=emptyList();ui.invitations=emptyList()}) {Text("返回球队列表")}
            val team=ui.team; val profile=ui.roster
            if(team==null) Text("球队不可用，请刷新核对。") else {
                Text(team.name,fontWeight=FontWeight.Bold); Text(team.role.title())
                if(team.role==TeamRole.admin) TextButton(enabled=ui.canPrepare && profile!=null,onClick={editor="profile"}) {Text("编辑球队资料")}
                Text("现役名单与停用档案",fontWeight=FontWeight.Bold)
                if(team.role!=TeamRole.member) TextButton(enabled=ui.canPrepare && profile!=null,onClick={player=null;editor="player"}) {Text("新增球员")}
                var visible by remember(ui.selectedID) {mutableIntStateOf(50)}
                profile?.players?.take(visible)?.forEach {p ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {Text(p.chineseName.ifBlank {p.englishName.ifBlank {"未填写姓名"}});Text(p.numbers.joinToString(" / ") {"#$it"} + if(p.archived==true) " · 已停用" else "");Text("身份编号 ${p.sourceID.takeLast(6)}")}
                        if(team.role!=TeamRole.member) TextButton(enabled=ui.canPrepare,onClick={player=p;editor="player"}) {Text("编辑")}
                    }
                }
                if(profile!=null && visible<profile.players.size) TextButton(onClick={visible+=50}) {Text("显示更多（已显示 $visible／${profile.players.size}）")}
                if(team.role==TeamRole.admin) {
                    ui.state.recoveryCards.singleOrNull {it.teamID==team.id}?.let {card ->
                        Text(if(card.confirmedSaved) "管理恢复卡已标记另存" else "请另存管理恢复卡，设备遗失后可找回管理权")
                        TextButton(enabled=!ui.busy,onClick={ui.call(scope) {
                            api.recoveryStatus(team.id); ui.state=api.state()
                            val current=ui.state.recoveryCards.singleOrNull {it.teamID==team.id} ?: error("当前恢复卡不可用，请核对后更换")
                            if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {shownCard=current;secret="管理恢复卡" to current.code}
                        }}) {Text("查看并保存恢复卡")}
                    }
                    TextButton(enabled=ui.canPrepare,onClick={ui.call(scope) {
                        val status=api.recoveryStatus(team.id); ui.state=api.state()
                        if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) confirmation=SharingConfirmation("更换管理恢复卡","更换后原恢复卡失效，请另存新卡；日常成员关系保持。") {
                            ui.mutate(scope) {api.prepareRecoveryRotation(status,true)}
                        }
                    }}) {Text("更换管理恢复卡")}
                    Text("成员管理",fontWeight=FontWeight.Bold)
                    ui.members.forEach {member -> CardBlock {
                        Text("${member.nickname} · ${member.role.title()} · ${member.id.takeLast(6)}")
                        Row {TeamRole.entries.forEach {role -> TextButton(enabled=ui.canPrepare && member.role!=role,onClick={confirmation=SharingConfirmation("更改成员角色","将“${member.nickname}”（${member.id.takeLast(6)}）设为${role.title()}？") {
                            ui.mutate(scope) {api.prepareMember(team.id,member,role)}
                        }}) {Text(role.title())} } }
                        TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("移除成员","移除“${member.nickname}”（${member.id.takeLast(6)}）的球队访问，已导出资料无法远程收回。") {
                            ui.mutate(scope) {api.prepareMember(team.id,member,member.role,true)}
                        }}) {Text("移除成员")}
                    } }
                    Text("单次邀请",fontWeight=FontWeight.Bold)
                    Row {listOf(TeamRole.scorer,TeamRole.member).forEach {role -> TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("创建邀请","创建 ${role.title()} 的 24 小时单次邀请？") {
                        ui.mutate(scope) {api.prepareInvite(team.id,role)}
                    }}) {Text("邀请${role.title()}")} } }
                    val saved=ui.state.lastCompletion
                    if(saved?.request?.kind==DirectoryRequestKind.invite && saved.request.teamID==team.id && ui.invitations.any {it.invitationID==saved.response["invitationID"]?.jsonPrimitive?.content}) TextButton(onClick={secret="球队邀请" to "https://baseballmaster.cc/join#${saved.request.body.getValue("token").jsonPrimitive.content}"}) {Text("查看刚创建的邀请链接")}
                    ui.invitations.forEach {invitation -> TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("撤销邀请","撤销这份 ${invitation.role.title()} 邀请？") {
                        ui.mutate(scope) {api.prepareRevoke(team.id,invitation.invitationID)}
                    }}) {Text("撤销邀请 · ${invitation.invitationID.takeLast(6)}")} }
                }
                TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("退出球队","退出后停止访问本队，本机原始球队仍保留。最后管理员需要先交给其他管理员。") {
                    ui.mutate(scope) {api.prepareLeave(team.id)}
                }}) {Text("退出球队")}
            }
        }
    }
    confirmation?.let {value -> AlertDialog(onDismissRequest={confirmation=null},title={Text(value.title)},text={Text(value.message)},confirmButton={TextButton(onClick={confirmation=null;value.action()}) {Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}) {Text("取消")}}) }
    secret?.let {value -> AlertDialog(
        onDismissRequest={secret=null;shownCard=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),title={Text(value.first)},
        text={Column { if(shownCard!=null) Text("持卡可恢复管理权并使旧授权失效，请离线妥善保存，勿发到球队群。")
            Text(value.second);Text("复制内容为敏感资料，请及时保存并清除剪贴板。")
            if(shownCard!=null) TextButton(onClick={secret=null;shownCard=null}) {Text("稍后保存")}
        }},
        confirmButton={TextButton(onClick={if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) SharingClipboard.copy(context.applicationContext,value.first,value.second)}) {Text("复制")}},
        dismissButton={TextButton(onClick={val card=shownCard;secret=null;shownCard=null;if(card!=null) ui.call(scope){api.confirmRecoveryCardSaved(card.teamID,card.version)}}) {Text(if(shownCard==null) "关闭" else "已另存")}})
    }
    editor?.let {kind -> SharingEditor(api,ui,scope,kind,localTeams,player) {editor=null} }
}

@Composable private fun SharingEditor(api: AndroidTeamSharing, ui: SharingUi, scope: CoroutineScope, kind: String, localTeams: List<Team>, player: SharedRosterPlayer?, dismiss: ()->Unit) {
    val profile=remember(kind,ui.selectedID,player?.id) {ui.roster}
    var localID by remember {mutableStateOf("")}; var name by remember {mutableStateOf(if(kind=="profile") profile?.name.orEmpty() else "")}
    var nickname by remember {mutableStateOf("")}; var shortName by remember {mutableStateOf(profile?.shortName.orEmpty())}; var city by remember {mutableStateOf(profile?.city.orEmpty())}
    var chinese by remember {mutableStateOf(player?.chineseName.orEmpty())}; var english by remember {mutableStateOf(player?.englishName.orEmpty())}
    var numbers by remember {mutableStateOf(player?.numbers?.joinToString("/").orEmpty())}; var archived by remember {mutableStateOf(player?.archived==true)}
    var invitation by remember {mutableStateOf("")}; var preview by remember {mutableStateOf<TeamInvitationPreview?>(null)}; var previewInput by remember {mutableStateOf<String?>(null)}
    val title=when(kind) {"create"->"新建共享球队";"join"->"通过邀请加入";"profile"->"球队资料";else->if(player==null) "新增球员" else "编辑球员"}
    val enabled=ui.canPrepare && when(kind) {"create"->nickname.isNotBlank() && (localID.isNotEmpty() || name.isNotBlank());"join"->nickname.isNotBlank() && preview!=null && previewInput==invitation;"profile"->name.isNotBlank() && shortName.isNotBlank();else->numbers.isNotBlank() && (chinese.isNotBlank() || english.isNotBlank())}
    FormPage(title,ui.busy,enabled,"save-shared-$kind",if(kind=="join") "确认加入" else "保存",dismiss,{
        val submittedName=name;val submittedNick=nickname;val submittedLocal=localID;val submittedShort=shortName;val submittedCity=city
        val submittedChinese=chinese;val submittedEnglish=english;val submittedNumbers=numbers;val submittedArchive=archived;val submittedInvitation=invitation
        val reviewedPreview=preview;val reviewedInput=previewInput
        ui.mutate(scope,dismiss) {
            when(kind) {
                "create" -> if(submittedLocal.isEmpty()) api.prepareCreate(submittedName,submittedNick) else api.preparePublish(submittedLocal,submittedNick)
                "join" -> { check(reviewedPreview!=null && reviewedInput==submittedInvitation) { "邀请已改变，请重新预览" }; api.prepareJoin(submittedInvitation,submittedNick) }
                "profile" -> api.prepareProfile(requireNotNull(profile).id,profile.revision,submittedName,submittedShort,submittedCity)
                else -> {
                    val labels=submittedNumbers.split('/').map {it.trim()}.filter {it.isNotEmpty()}
                    val edited=player?.copy(chineseName=submittedChinese,englishName=submittedEnglish,numbers=labels,archived=submittedArchive) ?: SharedRosterPlayer.new(submittedChinese,submittedEnglish,labels)
                    api.preparePlayer(requireNotNull(profile).id,profile.revision,edited)
                }
            }
        }
    },secure=kind=="join") {
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        when(kind) {
            "create" -> {
                Text("创建独立共享球队；选择本机球队时，仅发送当前现役名单，原球队与比赛保持。")
                TextButton(onClick={localID=""}) {Text(if(localID.isEmpty()) "✓ 空白新球队" else "空白新球队")}
                localTeams.forEach {team -> TextButton(onClick={localID=team.id}) {Text((if(localID==team.id) "✓ " else "")+"${team.name} · ${team.players.count {!it.archived}} 人 · ${team.id.takeLast(6)}")} }
                if(localID.isEmpty()) OutlinedTextField(name,{name=it},enabled=ui.canPrepare,label={Text("球队名称")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(nickname,{nickname=it},enabled=ui.canPrepare,label={Text("你的称呼")},modifier=Modifier.fillMaxWidth())
            }
            "join" -> {
                OutlinedTextField(invitation,{if(ui.canPrepare) {invitation=it;preview=null;previewInput=null}},enabled=ui.canPrepare,visualTransformation=PasswordVisualTransformation(),label={Text("邀请链接或邀请码")},modifier=Modifier.fillMaxWidth())
                TextButton(enabled=ui.canPrepare && invitation.isNotBlank(),onClick={val requested=invitation;ui.call(scope){val result=api.previewInvitation(requested);if(invitation==requested) {preview=result;previewInput=requested};ui.state=api.state()}}) {Text("预览邀请")}
                preview?.let {Text("球队：${it.teamName}\n加入角色：${it.role.title()}\n请核对后再确认加入。")}
                OutlinedTextField(nickname,{nickname=it},enabled=ui.canPrepare,label={Text("你的称呼")},modifier=Modifier.fillMaxWidth())
            }
            "profile" -> {
                OutlinedTextField(name,{name=it},enabled=ui.canPrepare,label={Text("球队名称")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(shortName,{shortName=it},enabled=ui.canPrepare,label={Text("简称")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(city,{city=it},enabled=ui.canPrepare,label={Text("城市")},modifier=Modifier.fillMaxWidth())
            }
            else -> {
                OutlinedTextField(chinese,{chinese=it},enabled=ui.canPrepare,label={Text("中文姓名")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(english,{english=it},enabled=ui.canPrepare,label={Text("英文姓名")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(numbers,{numbers=it},enabled=ui.canPrepare,label={Text("背号，多个用 / 分隔，保留 00")},modifier=Modifier.fillMaxWidth())
                if(player!=null && ui.team?.role==TeamRole.admin) Row {Checkbox(archived,{archived=it},enabled=ui.canPrepare);Text("停用球员（保留原档案及身份）")}
            }
        }
    }
}
