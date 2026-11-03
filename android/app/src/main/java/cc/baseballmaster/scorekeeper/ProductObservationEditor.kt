package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

/** The field is the primary input. Approximate regions and pending details are secondary actions. */
@Composable
internal fun ProductObservationEditor(
    game: Game,
    observation: Observation,
    outcome: Outcome,
    onTypeChosen: () -> Unit = {},
    typeSelected: Boolean = true,
    change: ((Observation) -> Observation) -> Unit,
) {
    var mode by rememberSaveable(game.id, game.revision, outcome.name) { mutableStateOf("landing") }
    var otherActions by rememberSaveable(game.id, game.revision, outcome.name) { mutableStateOf(false) }
    val fielding = observation.fielding ?: Fielding(status = "")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ProductBallTypeGrid(game, observation.type.takeIf { typeSelected },
            columns = if (LocalProductTaskEmbedded.current && LocalWorkbenchWide.current) 4 else 3) { type ->
            onTypeChosen()
            change { it.copy(type = type) }
        }
        ProductFieldTabs(
            mode,
            if (outcome == Outcome.error || fielding.errorPosition != null)
                linkedMapOf("landing" to "落点", "route" to "守备／传球", "error" to "失误责任")
            else linkedMapOf("landing" to "落点", "route" to "守备／传球"),
        ) { mode = it }
        SituationField(
            game,
            observation,
            fielding,
            mode = SituationFieldMode.valueOf(mode),
            onPoint = { x, y ->
                change { it.copy(precision = "point", region = null, x = x, y = y) }
                mode = "route"
            },
            onFielder = { position ->
                change { current ->
                    val before = current.fielding ?: Fielding(status = "")
                    current.copy(fielding = if (mode == "error") before.copy(errorPosition = position)
                    else before.copy(status = "recorded", route = appendProductTouch(before.route, position)))
                }
            },
        )
        ProductRouteStrip(game, fielding, outcome) { next ->
            change { it.copy(fielding = next) }
            mode = "route"
        }
        Text(
            productLocationSummary(observation),
            Modifier.testTag("landing-selection-state"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fielding.status.isNotBlank()) {
            Text(
                productFieldingSummary(game, fielding),
                Modifier.testTag("situation-defense-description"),
                fontSize = 12.sp,
                color = if (fielding.status == "pending") ProductAmber else MaterialTheme.colorScheme.onSurface,
            )
        }
        fielding.errorPosition?.let { position ->
            Text("失误责任：${productFielderName(game, position)}", fontSize = 12.sp,
                color = ProductRed, modifier = Modifier.testTag("situation-error-responsibility"))
        }
        TextButton(
            { otherActions = !otherActions },
            modifier = Modifier.fillMaxWidth().testTag("contact-other-actions"),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp),
        ) {
            Text("区域选择／其他处理", Modifier.weight(1f), textAlign = TextAlign.Start, fontSize = 12.sp)
            Icon(if (otherActions) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
        }
        if (otherActions) {
            ProductOptionGrid("区域", ScoringOptions.regions, observation.region, columns = 2) { region ->
                change { it.copy(precision = "region", region = region, x = null, y = null) }
                mode = "route"
            }
            if (outcome == Outcome.homeRun) {
                ProductAuxiliaryAction("越过围墙，无人处理", "situation-no-fielding") {
                    change { it.copy(fielding = Fielding(status = "noFielding")) }
                }
            }
            ProductAuxiliaryAction("没看清守备，明确标记待补", "choice-守备处理-pending") {
                change { current -> current.copy(fielding = (current.fielding ?: Fielding()).copy(status = "pending")) }
            }
            ProductAuxiliaryAction("清空守备路线，重新记录", "situation-clear-route") {
                change { it.copy(fielding = Fielding(status = "")) }
                mode = "route"
            }
        }
    }
}

@Composable
internal fun ProductBallTypeGrid(game: Game, selected: String?, columns: Int = 3, choose: (String) -> Unit) {
    ProductOptionGrid(
        "球型",
        ScoringOptions.ballTypes,
        selected,
        columns = columns,
        unavailable = { if (it == "bunt" && game.rules.mode == GameMode.slowPitch) "成人慢垒不允许触击" else null },
        choose = choose,
    )
}

/** Stable keys expose the same choices to assistive technology and device checks. */
@Composable
internal fun ProductOptionGrid(
    label: String,
    values: Map<String, String>,
    selected: String?,
    columns: Int = 3,
    unavailable: (String) -> String? = { null },
    choose: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.entries.toList().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { (key, title) ->
                    val isSelected = selected == key
                    val enabled = unavailable(key) == null
                    val tint = if (isSelected) ProductAmber else MaterialTheme.colorScheme.primary
                    Surface(
                        onClick = { choose(key) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                            .testTag("choice-$label-$key")
                            .semantics { this.selected = isSelected; role = Role.RadioButton },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) ProductAmber.copy(alpha = .12f) else MaterialTheme.colorScheme.surface,
                        contentColor = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
                        border = BorderStroke(1.dp, if (isSelected) tint.copy(alpha = .55f) else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.padding(horizontal = 5.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(title, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun ProductFieldTabs(selected: String, values: Map<String, String>, choose: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            values.forEach { (key, title) ->
                Surface(
                    onClick = { choose(key) },
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).testTag("choice-球场操作-$key")
                        .semantics { this.selected = selected == key; role = Role.RadioButton },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected == key) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = if (selected == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(title, fontSize = 12.sp, fontWeight = if (selected == key) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** The same compact field is used for a basic defensive play and an individual runner's route. */
@Composable
internal fun ProductFieldingEditor(
    game: Game,
    fielding: Fielding,
    allowNone: Boolean = false,
    defaultErrorMode: Boolean = false,
    change: ((Fielding) -> Fielding) -> Unit,
) {
    var mode by rememberSaveable(game.id, game.revision) {
        mutableStateOf(if (defaultErrorMode) "error" else "route")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ProductFieldTabs(mode, linkedMapOf("route" to "守备／传球", "error" to "失误责任")) { mode = it }
        SituationField(game, fielding = fielding, mode = SituationFieldMode.valueOf(mode), onFielder = { position ->
            change { current ->
                if (mode == "error") current.copy(errorPosition = position)
                else current.copy(status = "recorded", route = appendProductTouch(current.route, position))
            }
        })
        ProductRouteStrip(game, fielding, null) { next -> change { next } }
        Text(productFieldingSummary(game, fielding), fontSize = 12.sp,
            color = if (fielding.status == "pending") ProductAmber else MaterialTheme.colorScheme.onSurfaceVariant)
        fielding.errorPosition?.let { position ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("失误责任：${productFielderName(game, position)}", Modifier.weight(1f), fontSize = 12.sp, color = ProductRed)
                TextButton({ change { it.copy(errorPosition = null) } }, modifier = Modifier.testTag("clear-fielding-error")) { Text("清除", fontSize = 12.sp) }
            }
        }
        if (allowNone) ProductAuxiliaryAction("越过围墙，无人处理", "situation-no-fielding") {
            change { Fielding(status = "noFielding") }
        }
        ProductAuxiliaryAction("没看清处理，标记待补", "choice-守备处理-pending") {
            change { it.copy(status = "pending") }
        }
    }
}

@Composable
private fun ProductRouteStrip(game: Game, fielding: Fielding, outcome: Outcome?, change: (Fielding) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (fielding.route.isEmpty() && outcome in listOf(Outcome.groundOut, Outcome.doublePlay)) {
            val routes = if (outcome == Outcome.groundOut) listOf(listOf(6, 3), listOf(4, 3), listOf(5, 3))
            else listOf(listOf(6, 4, 3), listOf(4, 6, 3), listOf(5, 4, 3))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                routes.filter { route -> route.all { it in game.fieldingPositions } }.forEach { route ->
                    TextButton({ change(fielding.copy(route = route, status = "recorded")) },
                        modifier = Modifier.weight(1f).testTag("situation-route-${route.joinToString("-")}"),
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp)) {
                        Text(route.joinToString("-"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Text(
                if (fielding.route.isEmpty()) "按实际传接顺序点选守备员"
                else fielding.route.joinToString(" → ") { position ->
                    game.fieldingTeam.players.firstOrNull { it.id == game.fieldingPositions[position] }?.name
                        ?: ScoringOptions.positions.getOrNull(position - 1).orEmpty()
                },
                Modifier.weight(1f).testTag("situation-route"), fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
        TextButton({ change(fielding.copy(route = fielding.route.dropLast(1),
            status = if (fielding.route.size <= 1) "" else "recorded")) },
            enabled = fielding.route.isNotEmpty(), modifier = Modifier.testTag("situation-undo-route"),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
            Text("撤回", fontSize = 12.sp)
        }
    }
}

@Composable
internal fun ProductAuxiliaryAction(label: String, tag: String, action: () -> Unit) {
    OutlinedButton(action, Modifier.fillMaxWidth().heightIn(min = 43.dp).testTag(tag),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

internal val ProductRed = Color(0xFFB4493D)
internal val ProductAmber = Color(0xFFAE6C24)

private fun appendProductTouch(route: List<Int>, position: Int) =
    if (route.lastOrNull() == position) route else (route + position).take(50)

internal fun productFielderName(game: Game, position: Int): String {
    val role = ScoringOptions.positions.getOrNull(position - 1).orEmpty()
    val player = game.fieldingTeam.players.firstOrNull { it.id == game.fieldingPositions[position] }
    return if (player == null) "$position $role" else "$role ${player.name} #${player.number}"
}

internal fun productFieldingSummary(game: Game, fielding: Fielding): String {
    if (fielding.status == "noFielding") return "越过围墙，无人处理"
    val route = fielding.route.joinToString(" → ") { productFielderName(game, it) }
    val suffix = when {
        fielding.status == "pending" -> if (route.isBlank()) "守备待补" else "；后续待补"
        fielding.route.size == 1 -> " · 处理后未传球"
        route.isBlank() -> "请点第一位处理球的守备员"
        else -> ""
    }
    return if (route.isBlank()) suffix else "${fielding.route.joinToString("-")} · $route$suffix"
}

internal fun productLocationSummary(observation: Observation): String = when (observation.precision) {
    "point" -> if (observation.x != null && observation.y != null) "已点选落点 · 可重新点选" else "请在球场点选落点"
    "region" -> "大致区域：${ScoringOptions.regions[observation.region].orEmpty()}"
    else -> "请在球场点选落点，或展开区域选择"
}
