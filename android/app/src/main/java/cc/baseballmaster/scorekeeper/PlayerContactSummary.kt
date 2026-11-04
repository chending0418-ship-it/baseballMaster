package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import kotlin.math.hypot

/** Read-only personal contact map; games must come from PlayerStatisticsProjection.selectedGames. */
@Composable
internal fun PlayerContactSummary(playerID: String, games: List<Game>) {
    val projection = PlayerContactProjection.calculate(playerID, games)
    // Persist the owner alongside the selection: returning to an older filter must not reopen its dialog.
    // Sorted IDs make selection order irrelevant, while revisions invalidate stale contact details.
    val scopeKey = games.map { "${it.id.length}:${it.id}:${it.revision}" }.sorted().joinToString("|")
    var selectionOwner by rememberSaveable(playerID) { mutableStateOf(scopeKey) }
    var selectedID by rememberSaveable(playerID) { mutableStateOf<String?>(null) }
    val ownsSelection = selectionOwner == scopeKey
    val selected = if (ownsSelection) projection.contacts.firstOrNull { it.id == selectedID } else null
    SideEffect {
        if (!ownsSelection) {
            selectedID = null
            selectionOwner = scopeKey
        }
    }
    Column(Modifier.fillMaxWidth().testTag("player-contact-summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("击球落点", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("当前筛选 ${projection.gameCount} 场", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        if (projection.contacts.isEmpty()) {
            Text("当前筛选没有已记录的击球落点", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 700.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                        PlayerContactPlot(playerID,projection.contacts,Modifier.weight(.57f).height(300.dp)) {selectedID=it}
                        Column(Modifier.weight(.43f).heightIn(max=300.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            PlayerContactLegend(projection)
                            PlayerContactRecords(projection.contacts,compact=true) {selectedID=it}
                        }
                    }
                } else {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        PlayerContactPlot(playerID,projection.contacts,Modifier.fillMaxWidth().height(250.dp)) {selectedID=it}
                        PlayerContactLegend(projection)
                        PlayerContactRecords(projection.contacts,compact=false) {selectedID=it}
                    }
                }
            }
        }
    }
    if (selected != null) AlertDialog(onDismissRequest={selectedID=null},title={Text(selected.result)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            games.firstOrNull { it.id == selected.gameID }?.let { source ->
                Text(MatchDates.label(source))
                Text("${source.away.shortName} ${source.awayRuns} : ${source.homeRuns} ${source.home.shortName}")
            }
            Text("第${selected.inning}局${if(selected.isTop)"上" else "下"}")
            Text(selected.observation?.type?.let { ScoringOptions.ballTypes[it] } ?: "击球类型未记录")
            Text(when {
                selected.point != null -> "位置：记录点位"
                selected.region != null -> "位置：${ScoringOptions.regions[selected.region]}（区域范围，图上为近似示意）"
                else -> "位置：未记录，不推测落点"
            })
            if (selected.originalText.isNotBlank()) Text(selected.originalText)
        }
    },confirmButton={TextButton({selectedID=null},Modifier.testTag("player-contact-close")) {Text("完成")}})
}


@Composable
private fun PlayerContactPlot(playerID:String,contacts:List<PlayerContactRecord>,modifier:Modifier,onSelect:(String)->Unit) {
    val green=MaterialTheme.colorScheme.primary
    val located=contacts.filter {it.hasLocation}
    val latest by rememberUpdatedState(located)
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).testTag("player-contact-field").pointerInput(playerID) {
        detectTapGestures { tap ->
            val geometry=BaseballFieldGeometry(size.width/density.toDouble(),size.height/density.toDouble())
            latest.mapNotNull {contact ->
                val coordinate=contact.point ?: Performances.regionPoint(contact.region)?.let {FieldPoint(it.first,it.second)}
                coordinate?.let {geometry.contactPoint(it)}?.let {at -> contact to hypot(tap.x-at.x*density,tap.y-at.y*density)}
            }.minByOrNull {it.second}?.takeIf {it.second<=38.dp.toPx()}?.let {onSelect(it.first.id)}
        }
    }) {
        val geometry=BaseballFieldGeometry(size.width/density.toDouble(),size.height/density.toDouble())
        drawBaseballField(geometry)
        located.forEach {contact ->
            val coordinate=contact.point ?: Performances.regionPoint(contact.region)?.let {FieldPoint(it.first,it.second)}
            if(coordinate!=null) {
                val p=geometry.contactPoint(coordinate)
                val at=Offset((p.x*density).toFloat(),(p.y*density).toFloat())
                val color=when(contact.category) {"hit"->green;"out"->Color(0xFFC7463D);else->Color(0xFFB96D20)}
                if(contact.point!=null) {
                    drawCircle(Color.White,6.dp.toPx(),at)
                    drawCircle(color,4.dp.toPx(),at)
                } else {
                    // A deliberately broad dashed range, never a precise pin or trajectory.
                    val radius=22.dp.toPx()
                    drawOval(color.copy(alpha=.22f),at-Offset(radius,radius*.65f),Size(radius*2,radius*1.3f))
                    drawOval(color,at-Offset(radius,radius*.65f),Size(radius*2,radius*1.3f),
                        style=Stroke(2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx()))))
                }
            }
        }
    }
}

@Composable
private fun PlayerContactLegend(projection:PlayerContactSelection) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("● 安打 ${projection.hitCount}",color=MaterialTheme.colorScheme.primary,fontSize=12.sp)
            Text("● 出局 ${projection.outCount}",color=Color(0xFFC7463D),fontSize=12.sp)
            Text("● 其他 ${projection.otherCount}",color=Color(0xFFB96D20),fontSize=12.sp)
        }
        Text("实心点：记录位置 · 虚线范围：区域近似示意\n未记录位置 ${projection.unknownCount} 次，不绘制落点",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerContactRecords(contacts:List<PlayerContactRecord>,compact:Boolean,onSelect:(String)->Unit) {
    // Explicit records keep overlapping marks and missing locations accessible in either viewport.
    if(compact) {
        Column(Modifier.fillMaxWidth().heightIn(max=218.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            contacts.indices.chunked(2).forEach {indices ->
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    indices.forEach {index -> PlayerContactRecordButton(index,contacts[index],Modifier.weight(1f),onSelect)}
                    if(indices.size==1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            contacts.forEachIndexed {index,contact -> PlayerContactRecordButton(index,contact,Modifier,onSelect)}
        }
    }
}

@Composable
private fun PlayerContactRecordButton(index:Int,contact:PlayerContactRecord,modifier:Modifier,onSelect:(String)->Unit) {
    OutlinedButton(onClick={onSelect(contact.id)},modifier=modifier.testTag("player-contact-record-$index"),contentPadding=PaddingValues(horizontal=10.dp,vertical=4.dp)) {
        Text("${index+1} · ${contact.result}",fontSize=11.sp)
    }
}
