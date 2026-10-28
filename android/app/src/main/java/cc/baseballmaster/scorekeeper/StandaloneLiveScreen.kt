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
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.*

@Composable internal fun StandaloneLiveScreen(game: Game,live: AndroidLive,dismiss: ()->Unit) {
    val context=LocalContext.current;val binding=live.bindings.singleOrNull {it.gameID==game.id};val url=live.urls[game.id]
    var confirmClose by remember {mutableStateOf(false)}
    LaunchedEffect(game.id) {live.requestSync()}
    FormPage("文字直播",live.busy,false,"unused","",dismiss,{}) {
        Text("让亲友跟上每一个打席",style=MaterialTheme.typography.titleLarge)
        Text("网页每 10 秒自动刷新比分、垒况和打席过程，打开分享链接即可观看。")
        live.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        live.messages[game.id]?.let {Text(it)}
        if(binding==null) {
            Button(onClick={live.start(game)},enabled=!live.busy && live.error==null && !game.isFinal && !game.isScheduled && !game.practice) {Text("开启文字直播")}
            if(game.isFinal || game.isScheduled) Text("进行中的正式比赛可以开启直播。")
        } else {
            binding.lastSync?.let {Text("最近同步：${java.text.DateFormat.getTimeInstance().format(java.util.Date(it))}")}
            if(url!=null) {
                val bitmap=remember(url) {val qr=liveViewQr(url);Bitmap.createBitmap(qr.pixels,qr.width,qr.height,Bitmap.Config.ARGB_8888).asImageBitmap()}
                Image(bitmap,"观赛链接二维码",Modifier.size(208.dp),filterQuality=FilterQuality.None)
                Text(url)
                Button(onClick={runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type="text/plain";putExtra(Intent.EXTRA_TEXT,"${game.away.name} vs ${game.home.name}｜文字直播\n$url")
                },"分享观赛链接"))}.onFailure {SharingClipboard.copy(context,"观赛链接",url)}}) {Text("分享观赛链接")}
            }
            if(!binding.blocked && !binding.closing) TextButton(onClick={live.retry(game.id)},enabled=!live.busy) {Text("立即重试同步")}
            TextButton(onClick={confirmClose=true},enabled=!live.busy) {Text(if(binding.closing) "重试关闭直播" else "关闭直播并删除云端资料")}
        }
        HorizontalDivider()
        Text("分享与保留说明",style=MaterialTheme.typography.titleMedium)
        Text("开启后公开本场球队名称、当前双方阵容与守位、球员姓名及背号、投打表现、比分、规则、计时和比赛过程。任何持有链接的人都能观看，请确认适合分享。")
        Text("终场一小时后，链接和云端记录自动删除，不保留回放；连续一小时未同步也会关闭。本地比赛记录和备份不受影响。")
        Text("记分时请保持 App 在前台并联网。断网不影响本地记分，恢复后自动补传。终场请等待“终场已同步”；关闭后重新开播需要分享新链接。")
        Text("服务中断可能使链接提前失效。请勿公开未经授权的球员资料，未成年人资料尤其需要适当授权。")
    }
    if(confirmClose) AlertDialog(onDismissRequest={confirmClose=false},title={Text("关闭文字直播")},text={Text("旧链接将失效，云端直播资料删除；本地比赛保留。")},
        confirmButton={TextButton(onClick={confirmClose=false;live.close(game.id)}) {Text("关闭直播")}},dismissButton={TextButton(onClick={confirmClose=false}) {Text("取消")}})
}
