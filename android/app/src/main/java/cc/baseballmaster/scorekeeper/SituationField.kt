package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import kotlin.math.hypot
import kotlin.math.min

internal enum class SituationFieldMode {
    landing,
    route,
    error,
}

/** Shared iOS 3.1.1 geometry; persisted observations retain their legacy coordinates. */
@Composable
internal fun SituationField(
    game: Game,
    observation: Observation? = null,
    fielding: Fielding = Fielding(status = "pending"),
    mode: SituationFieldMode = SituationFieldMode.landing,
    onPoint: ((Double, Double) -> Unit)? = null,
    onFielder: ((Int) -> Unit)? = null,
) {
    val pointHandler by rememberUpdatedState(onPoint)
    val touch =
        if (mode == SituationFieldMode.landing && onPoint != null)
            Modifier.testTag("contact-field")
                .semantics { contentDescription = "在球场点选击球落点" }
                .pointerInput(Unit) {
                    detectTapGestures { location ->
                        val scale=density.toDouble()
                        val geometry=BaseballFieldGeometry(size.width/scale,size.height/scale)
                        val stored=geometry.contactCoordinate(FieldPoint(location.x/scale,location.y/scale))
                        pointHandler?.invoke(stored.x,stored.y)
                    }
                }
        else Modifier
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(15.dp)).then(touch)
    ) {
        val width = maxWidth.value
        val geometry=BaseballFieldGeometry(width.toDouble(),260.0)
        Canvas(Modifier.fillMaxSize()) {
            drawBaseballField(geometry)
            fielding.route.zipWithNext().forEach { (a, b) ->
                val start=geometry.fielder(a);val end=geometry.fielder(b)
                val scale=size.width/width
                val from=Offset(start.x.toFloat()*scale,start.y.toFloat()*scale)
                val to=Offset(end.x.toFloat()*scale,end.y.toFloat()*scale)
                val dx = to.x - from.x
                val dy = to.y - from.y
                val length = hypot(dx, dy)
                if (length > 1f) {
                    val ux = dx / length
                    val uy = dy / length
                    val inset = min(27.dp.toPx(), length / 3)
                    val tip = Offset(to.x - ux * inset, to.y - uy * inset)
                    val color = Color(0xFFFFE399)
                    drawLine(
                        color,
                        Offset(from.x + ux * inset, from.y + uy * inset),
                        tip,
                        2.5.dp.toPx(),
                        StrokeCap.Round,
                    )
                    val h = 8.dp.toPx()
                    val w = 4.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(tip.x - ux * h - uy * w, tip.y - uy * h + ux * w)
                            lineTo(tip.x, tip.y)
                            lineTo(tip.x - ux * h + uy * w, tip.y - uy * h - ux * w)
                        },
                        color,
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        if (mode != SituationFieldMode.landing)
            game.fieldingPositions.toSortedMap().forEach { (position, pid) ->
                val p = game.fieldingTeam.players.first { it.id == pid }
                val at = geometry.fielder(position)
                val selected =
                    if (mode == SituationFieldMode.error) fielding.errorPosition == position
                    else position in fielding.route
                Box(
                    Modifier.offset((at.x - 30).coerceIn(0.0, (width - 60).coerceAtLeast(0f).toDouble()).dp,
                        (at.y - 22).coerceIn(0.0, 216.0).dp)
                        .size(60.dp, 44.dp)
                        .shadow(3.dp, RoundedCornerShape(12.dp))
                        .background(
                            if (selected) Color(0xFFFFDB7D) else Color(0xFFFFFCF0),
                            RoundedCornerShape(12.dp),
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onFielder?.invoke(position) }
                        .testTag("fielding-$position")
                        .semantics {
                            this.selected = selected
                            contentDescription =
                                "${if(mode==SituationFieldMode.error)"失误责任人"else"处理球"}，${ScoringOptions.positions[position-1]}，${p.name}，${p.number}号"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            p.name,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) Color(0xFF4D3B12) else Color(0xFF214235),
                        )
                        Text(
                            "#${p.number}",
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color(0xFF4D3B12) else Color(0xFF214235),
                        )
                    }
                    if (fielding.errorPosition == position)
                        Text(
                            "E",
                            Modifier.align(Alignment.TopEnd).padding(end = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC7463D),
                        )
                }
            }
        val point = observationPoint(observation)
        if (point != null)
            Canvas(Modifier.fillMaxSize()) {
                val mapped=geometry.contactPoint(FieldPoint(point.first,point.second))
                val scale=size.width/width
                val center=Offset(mapped.x.toFloat()*scale,mapped.y.toFloat()*scale)
                if (observation?.precision == "region") {
                    drawCircle(Color(0xFFFFD14A).copy(alpha = .20f), 19.dp.toPx(), center)
                    drawCircle(
                        Color(0xFFFFD14A),
                        16.dp.toPx(),
                        center,
                        style =
                            Stroke(
                                1.5.dp.toPx(),
                                pathEffect =
                                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                                    ),
                            ),
                    )
                } else {
                    drawCircle(Color(0xFFFFD14A).copy(alpha = .30f), 14.5.dp.toPx(), center)
                    drawCircle(Color.White, 8.5.dp.toPx(), center)
                    drawCircle(Color(0xFFFFD14A), 6.dp.toPx(), center)
                }
            }
        Text(
            when (mode) {
                SituationFieldMode.landing ->
                    if (observation?.precision == "region") "大致区域 · 仅表示方向"
                    else if (point == null) "点选落点" else "已选落点 · 可重新点选"
                SituationFieldMode.error -> "点选失误责任人"
                SituationFieldMode.route -> if (fielding.route.isEmpty()) "点第一位处理球的人" else "继续点传球对象"
            },
            Modifier.align(Alignment.BottomStart)
                .padding(9.dp)
                .background(Color(0xFFF9EFDA).copy(alpha = .92f), RoundedCornerShape(6.dp))
                .padding(6.dp),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = Color(0xFF684F2C),
        )
    }
}

private fun observationPoint(o: Observation?): Pair<Double, Double>? {
    if (o == null) return null
    if (o.precision == "point") return o.x?.let { x -> o.y?.let { y -> x to y } }
    if (o.precision != "region") return null
    return when (o.region) {
        "infieldLeft" -> .36 to .60
        "infieldMiddle" -> .50 to .51
        "infieldRight" -> .64 to .60
        "left" -> .23 to .31
        "center" -> .50 to .20
        "right" -> .77 to .31
        "foulLeft" -> .12 to .70
        "foulRight" -> .88 to .70
        else -> null
    }
}
