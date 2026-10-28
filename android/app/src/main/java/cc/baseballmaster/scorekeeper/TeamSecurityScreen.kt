package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.baseballmaster.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject

/** Ports TeamSecurityViews.swift. Tokens/previews stay out of SavedState and ordinary exports. */
@Composable internal fun TeamSecurityScreen(api: AndroidTeamSharing, initialPage: String, teamID: String = "", initialLink: String = "", dismiss: ()->Unit) {
    val ui=remember(api) {SharingUi(api).also {it.selectedID=teamID}}
    val scope=rememberCoroutineScope(); val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    var page by remember {mutableStateOf(initialPage)}
    var selectedTeam by remember {mutableStateOf(teamID)}
    var deviceLabel by remember {mutableStateOf("我的新 Android 手机")}
    var input by remember {mutableStateOf(initialLink)}; var nickname by remember {mutableStateOf("")}
    var pairingPreview by remember {mutableStateOf<DevicePairing?>(null)}
    var recoveryPreview by remember {mutableStateOf<ManagementRecoveryStatus?>(null)}
    var checked by remember {mutableStateOf(false)}
    var devices by remember {mutableStateOf<List<PairedTeamDevice>>(emptyList())}
    var confirmation by remember {mutableStateOf<SharingConfirmation?>(null)}
    var revealedLink by remember {mutableStateOf<String?>(null)}
    var card by remember {mutableStateOf<SavedManagementCard?>(null)}
    var recoveredTeamID by remember {mutableStateOf<String?>(null)}
    var preservedGame by remember {mutableStateOf<JsonObject?>(null)}
    var foreground by remember {mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var baseline by remember {mutableStateOf<String?>(null)}; var loaded by remember {mutableStateOf(false)}
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event ->
            foreground=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) {revealedLink=null;card=null;confirmation=null;checked=false}
        }
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    suspend fun refresh() {
        if(page=="devices") devices=emptyList()
        api.refresh();ui.state=api.state();ui.enabled=ui.state.directory!=null
        if(page=="newDevice") ui.state.pairing?.request?.let {api.pairing(it.id)}
        if(page=="devices") {
            devices=api.devices(selectedTeam)
            ui.state.outgoingPairing?.takeIf {it.teamID==selectedTeam && it.status in setOf("pending","offered")}?.let {api.pairing(it.id)}
        }
        ui.state=api.state()
    }
    LaunchedEffect(api) {ui.call(scope) {ui.state=api.state();baseline=ui.state.lastCompletion?.request?.id;loaded=true;refresh()}}
    LaunchedEffect(page,foreground) {
        // Current iOS polls only the new-device pairing flow. No automatic mutation/retry.
        while(page=="newDevice" && foreground) {delay(3_000);if(!ui.busy) ui.call(scope){refresh()}}
    }
    LaunchedEffect(ui.state.lastCompletion?.request?.id,loaded) {
        val receipt=ui.state.lastCompletion
        if(loaded && receipt!=null && receipt.request.id!=baseline) {
            baseline=receipt.request.id
            if(receipt.request.securityAction==TeamSecurityAction.recoveryRedeem) {
                input="";recoveryPreview=null
                recoveredTeamID=receipt.request.body["teamID"]?.jsonPrimitive?.content
                if(foreground) card=ui.state.recoveryCards.singleOrNull {it.teamID==recoveredTeamID}
            }
            if(receipt.request.securityAction==TeamSecurityAction.deviceRemove && receipt.request.targetID==ui.state.deviceID) dismiss()
            else if(page=="devices" && !ui.busy) ui.call(scope){refresh()}
        }
    }
    val title=when(page) {"newDevice"->"接入这台新设备";"approve"->"授权我的新设备";"recover"->"恢复球队管理权";"review"->"恢复后核对";else->"球队设备"}
    FormPage(title,ui.busy,!ui.busy,"refresh-team-security","刷新",{
        if(page=="approve" && initialPage=="devices") {page="devices";pairingPreview=null;input=""} else dismiss()
    },{ui.call(scope){refresh()}},secure=true,dismissLabel="返回") {
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(ui.state.requiresRecoveryReview) Text("服务恢复后需要核对资料，安全操作暂时停止。",color=MaterialTheme.colorScheme.error)
        ui.state.pending?.let {pending -> CardBlock {
            Text("有一笔操作尚未确认，请续取原请求。")
            TextButton(enabled=!ui.busy && !ui.state.requiresRecoveryReview,onClick={ui.retry(scope)}) {Text("续取结果")}
            if(ui.state.lastFailure?.status in setOf(400,403,404,409,410,413,426)) TextButton(enabled=!ui.busy,onClick={
                confirmation=SharingConfirmation("保留原请求待核对","原操作可能已执行，请先核对当前资料。归档后不会自动重新提交。") {
                    ui.call(scope) {api.archiveRejectedOperation(pending.id,true);refresh()}
                }
            }) {Text("保留为待核对")}
        } }
        when(page) {
            "review" -> {
                Text("服务恢复备份后，需要重新确认能访问的球队和比赛。之前未完成的邀请、删除和记分交接不会自动继续。")
                Text("本机记录先保留供核对，再获取球队当前已保存的记录。需要补录时，请交给管理员核对；旧记录不会自动覆盖球队比赛。")
                val work=ui.state.recoveryWork
                Text("已保留 ${work.sumOf {it.matches.size}} 份比赛记录、${work.count {it.directoryRequest!=null}+work.sumOf {it.matchRequests.size}} 笔待核对操作。")
                if(!ui.state.requiresRecoveryReview) Text("核对已完成。请逐场查看保留记录，需要补录时交给管理员。")
                TextButton(enabled=!ui.busy && ui.state.requiresRecoveryReview,onClick={confirmation=SharingConfirmation("开始核对恢复后的资料？","本机旧记录会保留，之前未完成的操作不会继续。已失效的访问、邀请和观看链接不会恢复。") {
                    ui.call(scope){api.reconcileAfterRecovery(true);refresh()}
                }}) {Text("保留本机记录并核对")}
                work.forEach {item -> CardBlock {
                    Text("恢复前保留记录",fontWeight=FontWeight.Bold)
                    item.matches.forEach {match ->
                        Text("比赛 ${match.document.id.takeLast(6)} · 修订 ${match.document.revision}")
                        match.document.payload?.let {payload -> TextButton(onClick={preservedGame=payload}) {Text("查看保留比赛原始历史")}}
                    }
                    item.directoryRequest?.let {Text("球队操作 ${it.id.takeLast(6)} · 已保留，不会自动重做")}
                    item.matchRequests.forEach {Text("比赛交接 ${it.gameID.takeLast(6)} · 已保留，不会自动重做")}
                } }
            }
            "newDevice" -> {
                Text("这台手机作为新设备。请用原设备打开配对请求，选择一支球队并核对短语；只关联该队，不转移比赛记分权。")
                val saved=ui.state.pairing; val pair=saved?.request
                val active=pair!=null && pair.status in setOf("pending","offered") && pair.expiresAt>System.currentTimeMillis()
                if(pair!=null) CardBlock {
                    Text("两台设备核对同一短语",fontWeight=FontWeight.Bold)
                    Text(pair.phrase,modifier=Modifier.testTag("pairing-phrase"))
                    if(active && pair.status=="pending") {
                        Text("等待原设备选择球队并确认。10 分钟内有效，仅可使用一次。")
                        TextButton(enabled=foreground && !ui.busy,onClick={revealedLink="https://baseballmaster.cc/pair#${requireNotNull(saved).token}"}) {Text("显示二维码／分享给原设备")}
                    }
                    if(active && pair.status=="offered") {
                        Text("球队：${pair.teamName.orEmpty()}\n成员：${pair.nickname.orEmpty()} · ${pair.role?.title().orEmpty()}")
                        TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("确认球队与身份？","${pair.teamName} · ${pair.nickname} · ${pair.role?.title()}\n请核对两台手机短语：${pair.phrase}。完成配对不会转移比赛记分权。") {
                            ui.mutate(scope) {api.preparePairAcceptance(pair,true)}
                        }}) {Text("球队与身份正确，完成配对")}
                    }
                    if(pair.status=="accepted") Text("配对完成，原设备继续有效，可在球队设备中管理。")
                    else if(!active) Text("配对已结束或失效，请生成新请求。")
                    if(active) TextButton(enabled=ui.canPrepare,onClick={ui.mutate(scope) {api.preparePairCancellation(pair.id)}}) {Text("取消本次配对")}
                }
                if(!active) {
                    OutlinedTextField(deviceLabel,{deviceLabel=it},enabled=ui.canPrepare,label={Text("新设备名称")},modifier=Modifier.fillMaxWidth())
                    TextButton(enabled=ui.canPrepare && deviceLabel.isNotBlank(),onClick={val label=deviceLabel.trim();ui.mutate(scope) {api.preparePairing(label)}}) {Text("生成新设备配对请求")}
                }
            }
            "approve" -> {
                Text("仅授权你在所选球队的成员身份。请当面或通过可信方式核对新手机上的同一短语，不给其他人使用的手机配对。")
                if(teamID.isEmpty()) ui.state.directory?.teams.orEmpty().forEach {team -> TextButton(enabled=ui.canPrepare && pairingPreview==null,onClick={selectedTeam=team.id}) {Text((if(selectedTeam==team.id) "✓ " else "")+team.name+" · ${team.id.takeLast(6)}")} }
                OutlinedTextField(input,{input=it;checked=false},enabled=ui.canPrepare && pairingPreview==null,visualTransformation=PasswordVisualTransformation(),label={Text("粘贴新设备配对请求")},modifier=Modifier.fillMaxWidth())
                val preview=pairingPreview
                if(preview==null) TextButton(enabled=ui.canPrepare && selectedTeam.isNotEmpty() && input.isNotBlank(),onClick={
                    val token=runCatching {TeamServiceLink.pairing(input)}
                    ui.call(scope) {pairingPreview=api.previewPairing(selectedTeam,token.getOrThrow());checked=false}
                }) {Text("核对新设备")}
                else CardBlock {
                    Text(preview.phrase,fontWeight=FontWeight.Bold)
                    Text("新设备：${preview.targetLabel.orEmpty()}\n球队：${preview.teamName.orEmpty()}\n你的身份：${preview.nickname.orEmpty()} · ${preview.role?.title().orEmpty()}")
                    val offered=ui.state.outgoingPairing?.takeIf {it.id==preview.id && it.status=="offered"}
                    if(offered!=null) {
                        Text("已授权，等待新设备确认完成。记分权保持原状。")
                        TextButton(enabled=ui.canPrepare,onClick={ui.mutate(scope,completed={pairingPreview=null;checked=false;input=""}) {api.preparePairCancellation(preview.id,selectedTeam)}}) {Text("撤销这次待确认配对")}
                    } else {
                        Row {Checkbox(checked,{checked=it},enabled=ui.canPrepare);Text("我确认两台手机的短语一致")}
                        TextButton(enabled=ui.canPrepare && checked,onClick={val token=TeamServiceLink.pairing(input);ui.mutate(scope) {api.preparePairApproval(preview,token,true)}}) {Text("授权这台新设备")}
                        TextButton(enabled=!ui.busy,onClick={pairingPreview=null;checked=false}) {Text("取消核对，重新输入")}
                    }
                }
            }
            "recover" -> {
                Text("原手机仍可用时优先配对；还有其他管理员时请联系对方重新邀请。仅在管理设备都不可用时使用恢复卡。没有恢复卡，不能凭队名或昵称找回管理权。")
                OutlinedTextField(input,{input=it},enabled=ui.canPrepare && recoveryPreview==null,visualTransformation=PasswordVisualTransformation(),label={Text("粘贴 BMREC1 开头的恢复码")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(nickname,{nickname=it},enabled=ui.canPrepare && recoveryPreview==null,label={Text("你的队内称呼")},modifier=Modifier.fillMaxWidth())
                val preview=recoveryPreview
                if(preview==null) TextButton(enabled=ui.canPrepare && input.isNotBlank() && nickname.isNotBlank(),onClick={val text=input;ui.call(scope){recoveryPreview=api.previewRecovery(ManagementRecoveryInput.parse(text))}}) {Text("核对恢复的球队")}
                else CardBlock {
                    Text("将恢复 ${preview.teamName}",fontWeight=FontWeight.Bold)
                    Text("你将成为新管理员。该队所有旧设备、未用邀请、配对和观看授权立即失效；原直播和历史链接关闭。比赛档案保留，需要重新邀请成员并重新设置观看方式。核对比赛后再接管记分。")
                    TextButton(enabled=ui.canPrepare,onClick={
                        val parsed=ManagementRecoveryInput.parse(input);val nick=nickname.trim()
                        confirmation=SharingConfirmation("撤销旧授权并恢复管理权？","请确认球队“${preview.teamName}”。现有设备与分享授权将失效，接下来必须另存新恢复卡。") {
                            ui.mutate(scope) {api.prepareManagementRecovery(parsed,preview,nick,true)}
                        }
                    }) {Text("恢复管理权")}
                    TextButton(enabled=!ui.busy,onClick={recoveryPreview=null}) {Text("取消核对")}
                }
                ui.state.recoveryCards.singleOrNull {it.teamID==recoveredTeamID}?.let {saved -> TextButton(enabled=!ui.busy,onClick={ui.call(scope){api.recoveryStatus(saved.teamID);ui.state=api.state();if(foreground) card=ui.state.recoveryCards.singleOrNull {it.teamID==saved.teamID}}}) {Text("保存新恢复卡 · ${saved.teamName}")} }
            }
            else -> {
                Text("管理员可管理本队设备，其他成员可管理自己的设备。移除只影响本队；最后一台有效管理设备不能直接移除。")
                TextButton(enabled=ui.canPrepare,onClick={page="approve"}) {Text("授权我的新设备")}
                ui.state.outgoingPairing?.takeIf {it.teamID==selectedTeam && it.status in setOf("pending","offered")}?.let {pair -> CardBlock {
                    Text("待新设备确认：${pair.targetLabel.orEmpty()}\n${pair.phrase}")
                    TextButton(enabled=ui.canPrepare,onClick={ui.mutate(scope) {api.preparePairCancellation(pair.id,selectedTeam)}}) {Text("撤销待确认配对")}
                } }
                devices.forEach {device -> CardBlock {
                    Text(device.label + if(device.isCurrent==1) " · 本机" else "",fontWeight=FontWeight.Bold)
                    Text("${device.nickname} · ${device.role.title()} · ${device.id.takeLast(6)}")
                    TextButton(enabled=ui.canPrepare,onClick={confirmation=SharingConfirmation("移除这台设备？","${device.label} 将无法继续访问或记分。本队其他设备和其他球队授权保留；管理员设备移除时旧恢复卡同时失效。已导出或离线资料无法远程收回。") {
                        ui.mutate(scope,completed={if(device.isCurrent==1) dismiss() else ui.call(scope){refresh()}}) {api.prepareDeviceRemoval(selectedTeam,device,true)}
                    }}) {Text("移除此设备")}
                } }
            }
        }
    }
    confirmation?.let {value -> AlertDialog(onDismissRequest={confirmation=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),title={Text(value.title)},text={Text(value.message)},
        confirmButton={TextButton(onClick={confirmation=null;value.action()}) {Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}) {Text("取消")}}) }
    revealedLink?.let {link ->
        val image=remember(link) {val qr=teamServiceQr(link);Bitmap.createBitmap(qr.pixels,qr.width,qr.height,Bitmap.Config.ARGB_8888).asImageBitmap()}
        AlertDialog(onDismissRequest={revealedLink=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),title={Text("新设备配对请求")},text={Column {
            Image(image,"新设备配对二维码",Modifier.size(210.dp).background(Color.White),filterQuality=FilterQuality.None)
            Text(link);Text("请只发给自己的原设备，并核对两台设备上的同一短语。")
            TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,link)},"分享配对请求"))}) {Text("分享给原设备")}
        }},confirmButton={TextButton(onClick={if(foreground) SharingClipboard.copy(context.applicationContext,"设备配对",link)}) {Text("复制配对链接")}},dismissButton={TextButton(onClick={revealedLink=null}) {Text("完成")}})
    }
    card?.let {saved ->
        var reveal by remember(saved.teamID,saved.version) {mutableStateOf(false)}
        AlertDialog(onDismissRequest={card=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),title={Text("管理恢复卡")},text={Column {
            Text("球队：${saved.teamName} · 版本 ${saved.version}")
            Text("新卡替代旧卡。请离线保存，勿发到球队群。")
            if(reveal) {
                Text(saved.code)
                TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,saved.text)},"保存／分享恢复卡"))}) {Text("保存／分享恢复卡")}
                TextButton(onClick={if(foreground) SharingClipboard.copy(context.applicationContext,"管理恢复卡",saved.code)}) {Text("复制恢复码")}
            }
            else TextButton(onClick={reveal=true}) {Text("显示恢复卡")}
        }},confirmButton={TextButton(enabled=!ui.busy && reveal,onClick={ui.call(scope){api.confirmRecoveryCardSaved(saved.teamID,saved.version);card=null}}) {Text("我已保存恢复卡")}},dismissButton={TextButton(enabled=!ui.busy,onClick={card=null}) {Text("稍后保存")}})
    }
    preservedGame?.let {stored -> ArchiveGameHistory(stored,secure=true) {preservedGame=null} }
}
