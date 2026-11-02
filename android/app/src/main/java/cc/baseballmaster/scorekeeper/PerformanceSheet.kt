package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import kotlin.math.hypot

@Composable
internal fun PerformanceSheet(game: Game, games: List<Game>, close: () -> Unit) {
    var pid by rememberSaveable { mutableStateOf(game.batter.id) }
    var annual by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("") }
    var showLegend by remember { mutableStateOf(false) }
    val player = (game.home.players + game.away.players).first { it.id == pid }
    val performance =
        remember(game, games, pid, annual) { Performances.calculate(game, games, pid, annual) }
    FormPage("打击表现", false, true, "close-performance", "完成", close, close) {
        PlayerChoice("球员", game.battingTeam.players, pid) {
            pid = it
            selected = ""
        }
        Text("#${player.number} ${player.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Choices(
            "统计范围",
            if (game.practice) mapOf("game" to "本次练习")
            else if (Performances.year(game) == null) mapOf("game" to "本场")
            else mapOf("game" to "本场", "annual" to "${Performances.year(game)?:"当前"} 年"),
            if (annual) "annual" else "game",
        ) {
            annual = it == "annual"
            selected = ""
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf(
                    "打席" to "${performance.line.plateAppearances}",
                    "安打／打数" to "${performance.line.hits} / ${performance.line.atBats}",
                    "打击率" to
                        if (performance.line.atBats == 0) "—"
                        else String.format(java.util.Locale.ROOT, "%.3f", performance.line.average),
                )
                .forEach { (label, value) ->
                    Column {
                        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(label, fontSize = 12.sp)
                    }
                }
        }
        Text("${game.rules.mode.title} · ${performance.gameCount} 场 · 仅统计已完成打席", fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("击球图", Modifier.weight(1f), fontWeight=FontWeight.Bold)
            TextButton({showLegend=true}) {Text("图例",fontSize=12.sp)}
        }
        ContactPlot(performance.contacts) { selected = it.id }
        Text("● 安打    ● 出局    ● 其他", fontSize = 12.sp)
        Text(
            "有去向 ${performance.contacts.size} 球 · 缺少去向 ${performance.missingLocations} 球",
            Modifier.testTag("contact-coverage"),
        )
        if (performance.incompleteGames > 0)
            Text(
                "${performance.incompleteGames} 场待复核",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        performance.contacts
            .firstOrNull { it.id == selected }
            ?.let { CardBlock { Text(it.detail, Modifier.testTag("selected-contact")) } }
        if (performance.contacts.isEmpty()) Text("暂无有位置的击球记录")
        performance.contacts.forEach { contact ->
            TextButton({ selected = contact.id }) { Text(contact.detail, fontSize = 12.sp) }
        }
    }
    if(showLegend) AlertDialog(onDismissRequest={showLegend=false},title={Text("击球图图例")},text={
        Text("实线圈：点选位置\n虚线圈：区域位置\n\n缺少去向的击球、保送和三振不画线。年度范围为本设备保存的同队、同模式比赛。")
    },confirmButton={TextButton({showLegend=false}) {Text("完成")}})
}

@Composable
private fun ContactPlot(contacts: List<Contact>, select: (Contact) -> Unit) {
    val green = MaterialTheme.colorScheme.primary

    val latest by rememberUpdatedState(contacts)
    Canvas(
        Modifier.fillMaxWidth().height(235.dp).clip(RoundedCornerShape(16.dp)).testTag("contact-plot").pointerInput(Unit) {
            detectTapGestures { tap ->
                latest
                    .mapNotNull { c ->
                        val point =
                            if (c.observation.precision == "point")
                                c.observation.x?.let { x -> c.observation.y?.let { y -> x to y } }
                            else Performances.regionPoint(c.observation.region)
                        point?.let {
                            val scale=density.toDouble()
                            val mapped=BaseballFieldGeometry(size.width/scale,size.height/scale).contactPoint(FieldPoint(it.first,it.second))
                            c to hypot(tap.x-mapped.x*scale,tap.y-mapped.y*scale)
                        }
                    }
                    .minByOrNull { it.second }
                    ?.takeIf { it.second < 36.dp.toPx() }
                    ?.let { select(it.first) }
            }
        }
    ) {
        val geometry=BaseballFieldGeometry((size.width/density).toDouble(),(size.height/density).toDouble())
        drawBaseballField(geometry)
        fun p(x:Float,y:Float):Offset {
            val mapped=geometry.contactPoint(FieldPoint(x.toDouble(),y.toDouble()))
            return Offset(mapped.x.toFloat()*density,mapped.y.toFloat()*density)
        }
        contacts.forEach { c ->
            val o = c.observation
            val point =
                if (o.precision == "point") o.x?.let { x -> o.y?.let { y -> x to y } }
                else Performances.regionPoint(o.region)
            point?.let { (x, y) ->
                val color =
                    if (c.outcome?.isHit == true) green
                    else if (
                        c.outcome in
                            listOf(
                                Outcome.groundOut,
                                Outcome.flyOut,
                                Outcome.lineOut,
                                Outcome.foulFlyOut,
                                Outcome.infieldFly,
                                Outcome.doublePlay,
                                Outcome.triplePlay,
                                Outcome.pendingOut,
                                Outcome.sacrificeBunt,
                                Outcome.sacrificeFly,
                            )
                    )
                        Color(0xFFC7463D)
                    else Color(0xFFA35513)
                val at = p(x.toFloat(), y.toFloat())
                val home=Offset(geometry.home.x.toFloat()*density,geometry.home.y.toFloat()*density)
                val dash=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))
                drawLine(Color.White.copy(alpha=.65f),home,at,2.8.dp.toPx(),pathEffect=dash)
                drawLine(color.copy(alpha=.9f),home,at,1.3.dp.toPx(),pathEffect=dash)
                val radius=(if(o.precision=="region") 18.dp else 11.5.dp).toPx()
                drawCircle(Color.White.copy(alpha=.95f),radius,at)
                drawCircle(
                    color,
                    radius,
                    at,
                    style =
                        Stroke(
                            2.dp.toPx(),
                            pathEffect =
                                if (o.precision == "region")
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                                    )
                                else null,
                        ),
                )
            }
        }
    }
}
