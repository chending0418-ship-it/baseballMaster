package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.baseballmaster.core.*
import kotlinx.serialization.json.*

private fun SharedMatchDocument.matchTitle(): String = payload?.get("state")?.jsonObject?.let {state ->
    "${state["awayTeam"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty()} vs ${state["homeTeam"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty()}"
} ?: raw["title"]?.jsonPrimitive?.content?.takeIf {it.isNotBlank()} ?: "比赛 ${id.takeLast(6)}"
private fun HistoricalWatchMode.title()=when(this) {HistoricalWatchMode.closed->"不开放";HistoricalWatchMode.public->"永久开放";HistoricalWatchMode.restricted->"仅指定人员"}

@Composable internal fun SharedMatchesScreen(api: AndroidTeamSharing,teamID: String,dismiss: ()->Unit) {
    val ui=remember(api,teamID) {SharingUi(api).apply {selectedID=teamID}}
    val scope=rememberCoroutineScope();val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    var selected by remember {mutableStateOf<String?>(null)};var rows by remember {mutableStateOf<List<SharedMatchDocument>>(emptyList())}
    var viewers by remember {mutableStateOf<MatchViewers?>(null)};var history by remember {mutableStateOf<JsonObject?>(null)}
    var confirmation by remember {mutableStateOf<SharingConfirmation?>(null)};var shownLink by remember {mutableStateOf<String?>(null)}
    val document=ui.state.matches[selected]?.document?.takeIf {it.teamID==teamID}
    suspend fun refresh() {
        viewers=null;ui.refresh()
        rows=api.matches(teamID);ui.state=api.state()
        selected?.let {id ->
            val current=api.match(teamID,id);ui.state=api.state()
            if(current.canManageWatch && current.finalForWatching) {viewers=api.viewers(teamID,id);ui.state=api.state()}
        }
    }
    fun mutate(prepare: suspend ()->String) = ui.call(scope) {prepare();api.sendWatching(requireNotNull(selected));refresh()}
    LaunchedEffect(api,teamID) {ui.call(scope){refresh()}}
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) {shownLink=null;confirmation=null;history=null}}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    val canAct=ui.fresh && ui.canPrepare && document!=null && document.id !in ui.state.watchPending && document.id !in ui.state.matchPending
    FormPage(document?.matchTitle() ?: "球队比赛",ui.busy,!ui.busy,"shared-matches-refresh","刷新",{
        if(selected!=null) {selected=null;viewers=null;shownLink=null} else dismiss()
    },{ui.call(scope){refresh()}},secure=true,dismissLabel="返回") {
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(!ui.fresh) Text("以下为本机缓存；请刷新核对球队授权后再分享或修改。")
        if(ui.state.requiresRecoveryReview) Text("请先返回完成恢复后的资料核对，旧操作不会自动重做。")
        if(selected==null) {
            if(rows.isEmpty() && !ui.busy) Text("还没有球队比赛。")
            var visible by remember {mutableIntStateOf(50)}
            rows.take(visible).forEach {row -> CardBlock {
                Text(row.matchTitle(),fontWeight=FontWeight.Bold)
                Text(if(row.deleted) "已删除" else when(row.raw["status"]?.jsonPrimitive?.content) {"completed"->"已结束";"scheduled"->"比赛预告";else->"进行中"})
                TextButton(enabled=!ui.busy && !row.deleted,onClick={selected=row.id;ui.call(scope){refresh()}}) {Text("查看比赛与观看设置")}
            }}
            if(visible<rows.size) TextButton(onClick={visible+=50}) {Text("显示更多（已显示 $visible／${rows.size}）")}
        } else if(document==null || document.deleted || ui.state.credentialExpired) Text("当前无法访问这场比赛，请刷新核对。")
        else {
            Text(document.matchTitle(),fontWeight=FontWeight.Bold)
            document.payload?.get("state")?.jsonObject?.let {state ->
                val away=state["awayRunsByInning"]?.jsonArray?.sumOf {it.jsonPrimitive.int} ?: 0
                val home=state["homeRunsByInning"]?.jsonArray?.sumOf {it.jsonPrimitive.int} ?: 0
                Text("$away : $home · ${if(document.finalForWatching) "已结束" else "进行中"}")
            }
            TextButton(enabled=ui.fresh && !ui.busy && document.payload!=null,onClick={history=document.payload}) {Text("查看比赛历史与纠错记录")}
            ui.state.watchPending[document.id]?.let {pending -> CardBlock {
                Text("观看操作结果待确认",fontWeight=FontWeight.Bold)
                Text("续取同一请求，确认前不会另建邀请或更改内容。")
                TextButton(enabled=!ui.busy && !ui.state.requiresRecoveryReview,onClick={ui.call(scope){api.sendWatching(document.id);refresh()}}) {Text("续取结果")}
                if(ui.state.watchFailures[document.id]?.status in setOf(400,401,403,404,409,410,413,426)) TextButton(enabled=!ui.busy,onClick={confirmation=SharingConfirmation("保留为待核对","原请求会保留，不自动再做。请先核对球队当前观看设置，再决定后续操作。") {
                    ui.call(scope){api.parkWatching(document.id,true);refresh()}
                }}) {Text("保留为待核对")}
                Text("操作编号 ${pending.id.takeLast(6)}")
            }}
            if(!document.finalForWatching) {
                Text("实时文字直播",fontWeight=FontWeight.Bold)
                Text("仅当前记分设备可开关，公开本场阵容、球员姓名／背号和比赛过程；连续一小时未同步会到期。历史档案另行授权。")
                if(document.liveAvailable) document.sharedLiveURL?.let {url -> TextButton(enabled=ui.fresh && !ui.busy,onClick={shownLink=url}) {Text("分享实时观赛链接")}}
                if(document.canWrite) TextButton(enabled=canAct,onClick={val enabled=!document.liveAvailable;confirmation=SharingConfirmation(if(enabled) "开启实时直播？" else "关闭实时直播？",if(enabled) "任何持有链接的人都能看到本场公开比赛资料，请确认适合分享。" else "旧直播链接将失效，球队比赛档案保留。") {
                    mutate {api.sharedLive(document,enabled,true)}
                }}) {Text(if(document.liveAvailable) "关闭实时直播" else "开启实时直播")}
            } else {
                Text("历史观看：${document.watchMode.title()}",fontWeight=FontWeight.Bold)
                Text("档案长期保留与观看权限分开；关闭观看不删除比赛。")
                if(document.watchMode==HistoricalWatchMode.public) TextButton(enabled=ui.fresh && !ui.busy,onClick={shownLink=document.watchURL}) {Text("分享历史观赛链接")}
                if(document.canManageWatch) {
                    HistoricalWatchMode.entries.forEach {mode -> TextButton(enabled=canAct && mode!=document.watchMode,onClick={confirmation=SharingConfirmation("设为${mode.title()}？",when(mode) {
                        HistoricalWatchMode.closed->"历史网页将关闭，指定人员和旧观看邀请同时撤销。球队比赛档案保留。"
                        HistoricalWatchMode.public->"任何持有链接的人都能观看本场公开比赛资料，直到你关闭观看。"
                        HistoricalWatchMode.restricted->"只允许明确指定的成员或经你批准的外部设备观看。"
                    }) {mutate {api.watchMode(document,mode,true)}}}) {Text("设为${mode.title()}")}}
                }
                if(document.watchMode==HistoricalWatchMode.restricted) {
                    TextButton(enabled=canAct,onClick={mutate {api.watchTicket(document)}}) {Text("取得我的网页观看链接")}
                    if(document.canManageWatch) {
                        TextButton(enabled=canAct,onClick={mutate {api.watchInvitation(document)}}) {Text("邀请一名外部观看者")}
                        Text("外部邀请一天内有效，使用后仍需批准；已撤销的设备需要重新申请。")
                        viewers?.members?.forEach {member -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(member.nickname,Modifier.weight(1f));TextButton(enabled=canAct,onClick={confirmation=SharingConfirmation(if(member.granted==1) "撤销成员观看？" else "允许成员观看？",member.nickname) {
                                mutate {api.watchMember(document,member.id,member.granted!=1)}
                            }}) {Text(if(member.granted==1) "撤销" else "允许")}
                        }}
                        viewers?.viewers?.filter {it.memberID==null}?.forEach {viewer -> CardBlock {
                            Text("${viewer.nickname} · ${when(viewer.status) {"pending"->"待批准";"approved"->"已批准";else->"已撤销"}}")
                            if(viewer.status!="revoked") TextButton(enabled=canAct,onClick={confirmation=SharingConfirmation(if(viewer.status=="pending") "批准此设备观看？" else "撤销此设备观看？",viewer.nickname) {
                                mutate {api.watchExternal(document,viewer,viewer.status=="pending")}
                            }}) {Text(if(viewer.status=="pending") "批准" else "撤销")}
                            if(viewer.status=="pending") TextButton(enabled=canAct,onClick={confirmation=SharingConfirmation("撤销此设备的观看申请？",viewer.nickname) {
                                mutate {api.watchExternal(document,viewer,false)}
                            }}) {Text("撤销此人观看")}
                        }}
                    }
                    ui.state.watchLinks[document.id]?.url(document)?.let {url -> TextButton(enabled=ui.fresh && !ui.busy,onClick={shownLink=url}) {Text(if(ui.state.watchLinks[document.id]?.memberTicket==true) "打开我的观看链接" else "显示观看邀请")}}
                }
                if(document.liveAvailable) document.sharedLiveURL?.let {url -> TextButton(enabled=ui.fresh && !ui.busy,onClick={shownLink=url}) {Text("分享尚未到期的直播链接")}}
            }
        }
    }
    history?.let {stored -> ArchiveGameHistory(stored,secure=true) {history=null}}
    confirmation?.let {value -> AlertDialog(onDismissRequest={confirmation=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),title={Text(value.title)},text={Text(value.message)},
        confirmButton={TextButton(onClick={confirmation=null;value.action()}) {Text("确认")}},dismissButton={TextButton(onClick={confirmation=null}) {Text("取消")}})}
    shownLink?.let {url -> FormPage("观赛链接",false,false,"unused","",{shownLink=null},{},secure=true,dismissLabel="完成") {
        val bitmap=remember(url) {val qr=watchViewQr(url);Bitmap.createBitmap(qr.pixels,qr.width,qr.height,Bitmap.Config.ARGB_8888).asImageBitmap()}
        Image(bitmap,"观赛链接二维码",Modifier.size(208.dp),filterQuality=FilterQuality.None);Text(url)
        Button(onClick={runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,"${document?.matchTitle() ?: "球队比赛"}｜文字直播\n$url")},"分享观赛链接"))}.onFailure {SharingClipboard.copy(context,"观赛链接",url)}}) {Text("分享链接")}
        TextButton(onClick={SharingClipboard.copy(context,"观赛链接",url)}) {Text("复制链接")}
        TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}.onFailure {SharingClipboard.copy(context,"观赛链接",url)}}) {Text("用浏览器打开")}
    }}
}
