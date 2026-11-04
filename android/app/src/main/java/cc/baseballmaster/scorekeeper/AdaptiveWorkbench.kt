package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

/** The task has a single stable composition slot; resizing only changes its measurement. */
@Composable
internal fun AdaptiveScoreWorkbench(
    game: Game,
    inlineTask: Boolean,
    locked: Boolean,
    ruleNotices: List<String>,
    toolbar: @Composable () -> Unit,
    scoreboard: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    task: @Composable () -> Unit,
    openPanel: (String) -> Unit,
) {
    val wide = LocalWorkbenchWide.current
    val fold = LocalSeparatingFold.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val outerScroll = rememberScrollState()
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val viewportHeight = maxHeight
        val scroll = if (!wide && !inlineTask) Modifier.verticalScroll(outerScroll) else Modifier
        Layout(
            modifier = Modifier.widthIn(max = 1280.dp).fillMaxWidth().then(scroll)
                .onGloballyPositioned { origin = it.positionInWindow() }
                .testTag(if (wide) "scorekeeping-wide-layout" else "scorekeeping-compact-layout"),
            content = {
                Box { toolbar() }
                Box {
                    if (wide) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            scoreboard()
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            WorkbenchLineScore(game)
                        }
                    } else scoreboard()
                }
                Column(Modifier.then(if (wide) Modifier.verticalScroll(leftScroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            Field(game, if (wide) Modifier.height(320.dp) else Modifier.weight(1f))
                            WorkbenchCurrentPlayers(game, locked, ruleNotices, openPanel)
                        }
                    }
                    if (wide) {
                        WorkbenchMatchup(game)
                        WorkbenchLineups(game, locked, openPanel)
                    }
                }
                Column(Modifier.then(if (wide) Modifier.verticalScroll(rightScroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 6.dp)) {
                    if (wide) WorkbenchHeading("现场记录", if (game.isFinal) "本场已结束" else "当前打席 · 第 ${game.batterIndex + 1} 棒")
                    controls()
                    if (wide) {
                        WorkbenchRecentLog(game)
                        WorkbenchAppearances(game, locked, openPanel)
                    }
                }
                Box(Modifier.testTag("workbench-recording-host")) { task() }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val fullHeight = with(density) { viewportHeight.roundToPx() }
            val pad = 12.dp.roundToPx()
            val gap = (if (wide) 16.dp else 7.dp).roundToPx()
            val innerWidth = (width - pad * 2).coerceAtLeast(0)
            val header = measurables[0].measure(Constraints(maxWidth = innerWidth))
            val summary = measurables[1].measure(Constraints(maxWidth = innerWidth))
            val bodyY = pad + header.height + gap + summary.height + gap
            val bodyHeight = (fullHeight - bodyY - pad).coerceAtLeast(0)
            val localFold = fold?.bounds?.translate(-origin.x, -origin.y)
            val splitVertically = wide && fold?.vertical == true && localFold != null &&
                localFold.left > width * .24f && localFold.right < width * .76f
            val horizontalFold = fold?.takeIf { !it.vertical }?.bounds?.translate(-origin.x, -origin.y)
                ?.takeIf { it.top > bodyY + 160.dp.toPx() && it.bottom < fullHeight - 160.dp.toPx() }
            var leftX = pad
            var rightX = pad
            var leftY = bodyY
            var rightY = bodyY
            val left: androidx.compose.ui.layout.Placeable
            val right: androidx.compose.ui.layout.Placeable
            val editor: androidx.compose.ui.layout.Placeable
            val layoutHeight: Int
            if (wide && horizontalFold != null) {
                val topHeight = (horizontalFold.top.toInt() - gap - bodyY).coerceAtLeast(0)
                rightY = horizontalFold.bottom.toInt() + gap
                val lowerHeight = (fullHeight - rightY - pad).coerceAtLeast(0)
                left = measurables[2].measure(Constraints.fixed(innerWidth, topHeight))
                right = measurables[3].measure(Constraints.fixed(innerWidth, lowerHeight))
                editor = measurables[4].measure(Constraints.fixed(if (inlineTask) innerWidth else 0, if (inlineTask) lowerHeight else 0))
                layoutHeight = fullHeight
            } else if (wide) {
                val leftWidth = if (splitVertically) (localFold!!.left.toInt() - pad - gap / 2).coerceAtLeast(0)
                    else ((innerWidth - gap) * .54f).toInt()
                rightX = if (splitVertically) localFold!!.right.toInt() + gap / 2 else pad + leftWidth + gap
                val rightWidth = (width - pad - rightX).coerceAtLeast(0)
                left = measurables[2].measure(Constraints.fixed(leftWidth, bodyHeight))
                right = measurables[3].measure(Constraints.fixed(rightWidth, bodyHeight))
                editor = measurables[4].measure(Constraints.fixed(if (inlineTask) rightWidth else 0, if (inlineTask) bodyHeight else 0))
                layoutHeight = fullHeight
            } else if (inlineTask) {
                left = measurables[2].measure(Constraints.fixed(0, 0))
                right = measurables[3].measure(Constraints.fixed(0, 0))
                editor = measurables[4].measure(Constraints.fixed(innerWidth, bodyHeight))
                layoutHeight = fullHeight
            } else {
                // Reserve the actual recording controls first. The field uses the
                // remaining height, so a rule notice never pushes primary actions
                // below a standard phone's first viewport. Short windows still scroll.
                right = measurables[3].measure(Constraints(maxWidth = innerWidth))
                val fieldCardHeight = maxOf(270.dp.roundToPx(), bodyHeight - right.height - gap)
                left = measurables[2].measure(Constraints.fixed(innerWidth, fieldCardHeight))
                rightY = bodyY + left.height + gap
                editor = measurables[4].measure(Constraints.fixed(0, 0))
                layoutHeight = maxOf(fullHeight, rightY + right.height + pad)
            }
            layout(width, layoutHeight) {
                header.placeRelative(pad, pad)
                summary.placeRelative(pad, pad + header.height + gap)
                if (!inlineTask || wide) left.placeRelative(leftX, leftY)
                if (!inlineTask) right.placeRelative(rightX, rightY)
                editor.placeRelative(rightX, rightY)
            }
        }
    }
}

@Composable
private fun WorkbenchCurrentPlayers(game: Game, locked: Boolean, notices: List<String>, openPanel: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (game.rules.mode == GameMode.coachPitch) "P 位守备" else "投手 · 已投 ${game.pitching[game.pitcher.id]?.pitches ?: 0} 球",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("#${game.pitcher.number} ${game.pitcher.name}", Modifier.weight(1f, false), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (notices.isNotEmpty()) IconButton({ openPanel("rule-notices") }, enabled = !locked,
                    modifier = Modifier.size(24.dp).testTag("game-rule-warning")) {
                    Icon(Icons.Outlined.Notifications, notices.joinToString(" · "), Modifier.size(16.dp), tint = ProductAmber)
                }
            }
        }
        TextButton({ openPanel("performance") }, enabled = !locked, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.weight(1f).testTag("current-batter")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("第 ${game.batterIndex + 1} 棒", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("#${game.batter.number} ${game.batter.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun WorkbenchLineScore(game: Game, tag: String = "workbench-linescore") {
    val innings = maxOf(game.inning, game.rules.regulationInnings ?: 6, game.awayRunsByInning.size, game.homeRunsByInning.size)
    val inningsScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag)) {
        val compactTable = maxWidth < 500.dp
        val nameWidth = (maxWidth * .2f).coerceIn(if (compactTable) 90.dp else 110.dp, 180.dp)
        val totalCellWidth = (maxWidth * .055f).coerceIn(if (compactTable) 28.dp else 32.dp, 42.dp)
        val inningsWidth = (maxWidth - nameWidth - totalCellWidth * 3).coerceAtLeast(0.dp)
        val inningCellWidth = (inningsWidth / innings).coerceAtLeast(if (compactTable) 24.dp else 34.dp)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(nameWidth).testTag("$tag-teams")) {
                LineScoreCell("分局比分", nameWidth, header = true, alignStart = true)
                LineScoreCell(game.away.shortName, nameWidth, bold = true, alignStart = true)
                LineScoreCell(game.home.shortName, nameWidth, bold = true, alignStart = true)
            }
            Column(Modifier.width(inningsWidth).horizontalScroll(inningsScroll).testTag("$tag-innings")) {
                Row { (1..innings).forEach { LineScoreCell(it.toString(), inningCellWidth, header = true, tag = "$tag-inning-$it") } }
                listOf(false, true).forEach { home ->
                    val runs = if (home) game.homeRunsByInning else game.awayRunsByInning
                    Row { (0 until innings).forEach { index ->
                        val played = index < game.inning - 1 || index == game.inning - 1 && (!home || !game.isTop)
                        LineScoreCell(if (played) (runs.getOrNull(index) ?: 0).toString() else "—", inningCellWidth,
                            active = !game.isFinal && index == game.inning - 1 && home != game.isTop)
                    } }
                }
            }
            Column(Modifier.width(totalCellWidth * 3).testTag("$tag-rhe")) {
                Row { listOf("R", "H", "E").forEach { LineScoreCell(it, totalCellWidth, header = true, bold = true) } }
                listOf(false, true).forEach { home ->
                    val totals = listOf(if (home) game.homeRuns else game.awayRuns, if (home) game.homeHits else game.awayHits,
                        if (home) game.homeErrors else game.awayErrors)
                    Row { totals.forEachIndexed { index, total -> LineScoreCell(total.toString(), totalCellWidth, bold = true, primary = index == 0) } }
                }
            }
        }
    }
}

@Composable
private fun LineScoreCell(value: String, width: androidx.compose.ui.unit.Dp, header: Boolean = false,
    bold: Boolean = false, active: Boolean = false, primary: Boolean = false, alignStart: Boolean = false, tag: String = "") {
    Box(Modifier.testTag(tag).width(width).heightIn(min = if (header) 24.dp else 28.dp)
        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .padding(end = if (alignStart) 8.dp else 0.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center) {
        Text(value, fontSize = if (header) 11.sp else 12.sp,
            color = when { primary -> MaterialTheme.colorScheme.primary; header -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface },
            fontWeight = if (bold || active) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WorkbenchHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkbenchMatchup(game: Game) {
    CardBlock(Modifier.testTag("workbench-matchup")) {
        WorkbenchHeading("本场投打表现", "随现场记录更新")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val line = game.batting[game.batter.id] ?: BattingLine()
                Text("当前打者", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("#${game.batter.number} ${game.batter.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("${line.atBats} 打数 · ${line.hits} 安打 · ${line.runsBattedIn} 打点", fontSize = 11.sp)
                Text("${line.walks} 四坏 · ${line.strikeouts} 三振", fontSize = 11.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val line = game.pitching[game.pitcher.id] ?: PitchingLine()
                Text(if (game.rules.mode == GameMode.coachPitch) "P 位守备" else "当前投手", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("#${game.pitcher.number} ${game.pitcher.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (game.rules.mode == GameMode.coachPitch) Text("教练投球，投手数据不适用", fontSize = 11.sp)
                else {
                    Text("${line.pitches} 球 · ${line.outsRecorded / 3}.${line.outsRecorded % 3} 局 · ${line.strikeouts} 三振", fontSize = 11.sp)
                    Text("${line.hits} 被安打 · ${line.walks} 四坏", fontSize = 11.sp)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("接下来", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val nextCount = minOf(2, game.battingOrder.size - 1).coerceAtLeast(0)
            repeat(nextCount) { offset ->
                val id = game.battingOrder[(game.batterIndex + offset + 1) % game.battingOrder.size]
                val player = game.battingTeam.players.firstOrNull { it.id == id }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (offset == 0) "待打" else "准备", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(player?.let { "#${it.number} ${it.name}" }.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun WorkbenchLineups(game: Game, locked: Boolean, openPanel: (String) -> Unit) {
    CardBlock(Modifier.testTag("workbench-lineups")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { WorkbenchHeading("场上阵容", "攻守交换后自动更新") }
            TextButton({ openPanel("substitution") }, enabled = !locked && !game.isFinal,
                modifier = Modifier.testTag("workbench-adjust-lineup")) { Text("调整", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${game.battingTeam.shortName} · 进攻", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                game.battingOrder.forEachIndexed { index, id ->
                    val player = game.battingTeam.players.firstOrNull { it.id == id }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${index + 1}", Modifier.width(17.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(player?.let { "#${it.number} ${it.name}" }.orEmpty(), Modifier.weight(1f), fontSize = 11.sp,
                            color = if (id == game.batter.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (id == game.batter.id) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${game.fieldingTeam.shortName} · 守备", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                game.fieldingPositions.toSortedMap().forEach { (position, id) ->
                    val player = game.fieldingTeam.players.firstOrNull { it.id == id }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ScoringOptions.positions.getOrNull(position - 1).orEmpty(), Modifier.width(27.dp),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(player?.let { "#${it.number} ${it.name}" }.orEmpty(), Modifier.weight(1f), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchRecentLog(game: Game) {
    CardBlock(Modifier.testTag("workbench-recent-log")) {
        WorkbenchHeading("最近实况", "最近 6 条 · 包含投球与场上事件")
        if (game.entries.isEmpty()) Text("尚无现场记录，记球后显示在这里。", Modifier.padding(vertical = 10.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        game.entries.takeLast(6).asReversed().forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${entry.inning}${if (entry.isTop) "上" else "下"}", Modifier.width(25.dp), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(entry.text, Modifier.weight(1f), fontSize = 12.sp)
                if (entry.incomplete) Text("待补", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WorkbenchAppearances(game: Game, locked: Boolean, openPanel: (String) -> Unit) {
    val recent = remember(game.id, game.revision, game.cursor) { AppearanceProjection.build(game).filter { it.complete }.takeLast(3).asReversed() }
    CardBlock(Modifier.testTag("workbench-recent-appearances")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { WorkbenchHeading("最近打席", "最近 3 个已完成打席") }
            TextButton({ openPanel("results") }, enabled = !locked) { Text("全部", fontSize = 12.sp) }
        }
        if (recent.isEmpty()) Text("尚无已完成打席。当前打席确认后显示结果。", Modifier.padding(vertical = 10.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        recent.forEachIndexed { index, appearance ->
            val batter = (game.home.players + game.away.players).firstOrNull { it.id == appearance.batterID }
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("${appearance.inning}局${if (appearance.isTop) "上" else "下"} · #${batter?.number.orEmpty()} ${batter?.name.orEmpty()}",
                Modifier.padding(top = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(appearance.events.lastOrNull { it.outcome != null }?.text ?: appearance.events.lastOrNull()?.text.orEmpty(),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
