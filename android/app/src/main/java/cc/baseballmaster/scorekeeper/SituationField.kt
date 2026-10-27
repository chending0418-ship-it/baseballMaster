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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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

/** Approved iOS SituationFieldBackground, in the same 360 × 260 coordinate system. */
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
                        pointHandler?.invoke(
                            (location.x / size.width).toDouble().coerceIn(0.0, 1.0),
                            (location.y / size.height).toDouble().coerceIn(0.0, 1.0),
                        )
                    }
                }
        else Modifier
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(15.dp)).then(touch)
    ) {
        val width = maxWidth.value
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / 360f
            val sy = size.height / 260f
            fun path(build: Path.() -> Unit) = Path().apply(build)
            fun Path.move(x: Float, y: Float) = moveTo(x * sx, y * sy)
            fun Path.line(x: Float, y: Float) = lineTo(x * sx, y * sy)
            fun diamond(points: List<Offset>) = path {
                points.forEachIndexed { i, p -> if (i == 0) move(p.x, p.y) else line(p.x, p.y) }
                close()
            }
            drawRect(Color(0xFFEDE3CE))
            drawPath(
                path {
                    move(-5f, 72f)
                    quadraticTo(180f * sx, -80f * sy, 365f * sx, 72f * sy)
                    line(180f, 251f)
                    close()
                },
                Color(0xFFD5B780),
            )
            drawPath(
                path {
                    move(3f, 77f)
                    quadraticTo(180f * sx, -68f * sy, 357f * sx, 77f * sy)
                    line(180f, 242f)
                    close()
                },
                Color(0xFF2B7558),
            )
            drawPath(
                diamond(
                    listOf(
                        Offset(180f, 130f),
                        Offset(247f, 192f),
                        Offset(180f, 251f),
                        Offset(113f, 192f),
                    )
                ),
                Color(0xFFCDB27F),
            )
            drawPath(
                diamond(
                    listOf(
                        Offset(180f, 148f),
                        Offset(228f, 192f),
                        Offset(180f, 236f),
                        Offset(132f, 192f),
                    )
                ),
                Color(0xFF397C60),
            )
            drawPath(
                path {
                    move(0f, 65f)
                    line(180f, 246f)
                    line(360f, 65f)
                    move(116f, 192f)
                    line(180f, 134f)
                    line(244f, 192f)
                },
                Color(0xFFFFF5D9),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
            // Small bases make the same approved infield geometry legible without changing
            // coordinates.
            listOf(Offset(116f, 192f), Offset(180f, 134f), Offset(244f, 192f)).forEach { base ->
                val center = Offset(base.x * sx, base.y * sy)
                val r = 3.3.dp.toPx()
                drawPath(
                    path {
                        moveTo(center.x, center.y - r)
                        lineTo(center.x + r, center.y)
                        lineTo(center.x, center.y + r)
                        lineTo(center.x - r, center.y)
                        close()
                    },
                    Color(0xFFFFF8E8),
                )
            }
            val plate = Offset(180f * sx, 246f * sy)
            val r = 3.6.dp.toPx()
            drawPath(
                path {
                    moveTo(plate.x - r, plate.y - r)
                    lineTo(plate.x + r, plate.y - r)
                    lineTo(plate.x + r, plate.y)
                    lineTo(plate.x, plate.y + r)
                    lineTo(plate.x - r, plate.y)
                    close()
                },
                Color(0xFFFFF8E8),
            )
            // Retain the exact iOS fielder coordinates and arrow insets for cross-platform routes.
            fielding.route.zipWithNext().forEach { (a, b) ->
                val start = defensivePoint(a)
                val end = defensivePoint(b)
                val from = Offset(start.x * size.width, start.y * size.height)
                val to = Offset(end.x * size.width, end.y * size.height)
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
                        path {
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
                val at = defensivePoint(position)
                val selected =
                    if (mode == SituationFieldMode.error) fielding.errorPosition == position
                    else position in fielding.route
                Box(
                    Modifier.offset((at.x * width - 30).dp, (at.y * 260 - 22).dp)
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
                val center =
                    Offset(point.first.toFloat() * size.width, point.second.toFloat() * size.height)
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

private fun defensivePoint(position: Int): Offset =
    when (position) {
        1 -> Offset(.5f, .70f)
        2 -> Offset(.5f, .91f)
        3 -> Offset(.84f, .65f)
        4 -> Offset(.65f, .49f)
        5 -> Offset(.16f, .65f)
        6 -> Offset(.35f, .49f)
        7 -> Offset(.22f, .23f)
        8 -> Offset(.5f, .12f)
        9 -> Offset(.78f, .23f)
        10 -> Offset(.5f, .31f)
        else -> error("Invalid field position")
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
