package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
        Text("击球图", fontWeight = FontWeight.Bold)
        ContactPlot(performance.contacts) { selected = it.id }
        Text("● 安打    ● 出局    ● 其他", fontSize = 12.sp)
        Text(
            "有去向 ${performance.contacts.size} 球 · 缺少去向 ${performance.missingLocations} 球",
            Modifier.testTag("contact-coverage"),
        )
        Text(
            "实线圈表示点选位置，虚线圈只表示区域。旧记录缺少资料时保持未知，保送与三振不画线。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (performance.incompleteGames > 0)
            Text(
                "其中 ${performance.incompleteGames} 场过程或统计不完整，请结合原记录核对。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        if (annual) Text("年度仅包含本设备实际保存、同球队身份、同比赛模式的比赛；副本按来源去重。", fontSize = 12.sp)
        performance.contacts
            .firstOrNull { it.id == selected }
            ?.let { CardBlock { Text(it.detail, Modifier.testTag("selected-contact")) } }
        if (performance.contacts.isEmpty()) Text("暂无有位置的击球记录")
        performance.contacts.forEach { contact ->
            TextButton({ selected = contact.id }) { Text(contact.detail, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ContactPlot(contacts: List<Contact>, select: (Contact) -> Unit) {
    val green = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.secondaryContainer
    val latest by rememberUpdatedState(contacts)
    Canvas(
        Modifier.fillMaxWidth().height(235.dp).testTag("contact-plot").pointerInput(Unit) {
            detectTapGestures { tap ->
                latest
                    .mapNotNull { c ->
                        val point =
                            if (c.observation.precision == "point")
                                c.observation.x?.let { x -> c.observation.y?.let { y -> x to y } }
                            else Performances.regionPoint(c.observation.region)
                        point?.let {
                            c to
                                hypot(
                                    tap.x - it.first * size.width,
                                    tap.y - it.second * size.height,
                                )
                        }
                    }
                    .minByOrNull { it.second }
                    ?.takeIf { it.second < 36.dp.toPx() }
                    ?.let { select(it.first) }
            }
        }
    ) {
        drawRoundRect(soft, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
        fun p(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        val field =
            Path().apply {
                moveTo(size.width * .5f, size.height * .90f)
                lineTo(size.width * .08f, size.height * .38f)
                quadraticTo(
                    size.width * .5f,
                    -size.height * .13f,
                    size.width * .92f,
                    size.height * .38f,
                )
                close()
            }
        drawPath(field, green.copy(alpha = .18f))
        val diamond =
            Path().apply {
                moveTo(size.width * .5f, size.height * .90f)
                lineTo(size.width * .25f, size.height * .63f)
                lineTo(size.width * .5f, size.height * .36f)
                lineTo(size.width * .75f, size.height * .63f)
                close()
            }
        drawPath(diamond, Color(0xFFA35513).copy(alpha = .65f), style = Stroke(2.dp.toPx()))
        drawLine(green, p(.02f, .3f), p(.5f, .9f), 1.5.dp.toPx())
        drawLine(green, p(.5f, .9f), p(.98f, .3f), 1.5.dp.toPx())
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
                drawCircle(color.copy(alpha = .3f), 9.dp.toPx(), at)
                drawCircle(
                    color,
                    7.dp.toPx(),
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
