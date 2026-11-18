package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.Decision
import cc.baseballmaster.core.Game

private val baseTitles = listOf("本垒", "一垒", "二垒", "三垒")

private fun Decision.arrivalBase(): Int? = when (destination) {
    0 -> origin.takeIf { it in 1..3 }
    in 1..3 -> destination
    4 -> 0
    else -> null
}

internal fun Game.runnerName(decision: Decision): String =
    battingTeam.players.firstOrNull { it.id == decision.playerID }
        ?.let { "#${it.number} ${it.name}" } ?: "球员"

internal fun runnerBaseConflict(decisions: List<Decision>): String? {
    val conflicts = (1..3).filter { base -> decisions.count { it.arrivalBase() == base } > 1 }
    return if (conflicts.isEmpty()) null
    else "${conflicts.joinToString("、") { baseTitles[it] }}有多名跑者，请调整去向。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunnerReviewBoard(
    game: Game,
    decisions: List<Decision>,
    selectedRunner: String?,
    preview: Boolean,
    changeMode: (Boolean) -> Unit,
    selectRunner: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("本球前", "去向预览").forEachIndexed { index, label ->
                    val isPreview = index == 1
                    SegmentedButton(
                        selected = preview == isPreview,
                        onClick = { changeMode(isPreview) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag(if (isPreview) "runner-board-preview" else "runner-board-before"),
                    ) { Text(label, textAlign = TextAlign.Center) }
                }
            }
            Text(
                if (preview) "回本垒是否计分，按比赛规则判定。" else "点有球员的垒包，核对去向。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            val arrivals = decisions.groupBy { it.arrivalBase() }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("runner-base-diamond"),
                shape = RoundedCornerShape(16.dp),
                color = colors.primaryContainer,
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(8.dp)) {
                    val markerWidth = (maxWidth * .33f).coerceAtMost(132.dp)
                    Canvas(Modifier.matchParentSize()) {
                        // Connect the four positions behind their cards. Cards grow with font scale.
                        val edge = markerWidth.toPx() / 2
                        val diamond = Path().apply {
                            moveTo(size.width / 2, 20.dp.toPx())
                            lineTo(size.width - edge, size.height / 2)
                            lineTo(size.width / 2, size.height - 20.dp.toPx())
                            lineTo(edge, size.height / 2)
                            close()
                        }
                        drawPath(diamond, colors.primary.copy(alpha = .08f))
                        drawPath(diamond, colors.primary.copy(alpha = .35f), style = Stroke(1.5.dp.toPx()))
                    }
                    val marker: @Composable (Int) -> Unit = { base ->
                        val source = decisions.firstOrNull { it.origin == base }
                        val players = if (preview) arrivals[base].orEmpty() else listOfNotNull(source)
                        RunnerBaseMarker(
                            base = base,
                            names = players.map(game::runnerName),
                            source = source,
                            preview = preview,
                            isSelected = !preview && source?.playerID == selectedRunner && source != null,
                            modifier = Modifier.width(markerWidth),
                            selectRunner = selectRunner,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        marker(2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            marker(3)
                            marker(1)
                        }
                        marker(0)
                    }
                }
            }
            runnerBaseConflict(decisions)?.let { conflict ->
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("runner-base-conflict")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(conflict, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (preview) {
                val retired = decisions.filter { it.destination == -1 }.map(game::runnerName)
                if (retired.isNotEmpty()) {
                    Text("出局：${retired.joinToString("、")}", Modifier.testTag("runner-preview-outs"),
                        style = MaterialTheme.typography.bodyMedium, color = colors.error, fontWeight = FontWeight.SemiBold)
                }
                val returnedHome = arrivals[0].orEmpty().map(game::runnerName)
                if (returnedHome.size > 1) {
                    Text("回本垒：${returnedHome.joinToString("、")}", Modifier.testTag("runner-preview-home"),
                        style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun RunnerBaseMarker(
    base: Int,
    names: List<String>,
    source: Decision?,
    preview: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    selectRunner: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val conflict = preview && base != 0 && names.size > 1
    val detail = when {
        names.size > 1 -> "${names.size} 人"
        names.size == 1 -> names.single()
        base != 0 -> "空垒"
        preview -> "无人回垒"
        else -> "无打者变化"
    }
    val status = when {
        conflict -> "同垒冲突"
        preview -> "回本垒".takeIf { base == 0 && names.isNotEmpty() }
        else -> source?.let {
            when (val destination = it.destination) {
                -1 -> "出局"
                0 -> "留垒"
                4 -> "回本垒"
                in 1..3 -> "到${baseTitles[destination]}"
                else -> null
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val statusColor = if (conflict || (!preview && source?.destination == -1)) colors.error else colors.primary
    val border = BorderStroke(if (isSelected) 2.dp else 1.dp,
        if (isSelected || conflict) statusColor else colors.outlineVariant)
    val face: @Composable () -> Unit = {
        Column(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(baseTitles[base], style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
    if (!preview && source != null) {
        Surface(
            onClick = { selectRunner(source.playerID) },
            modifier = modifier.testTag("runner-card-$base").semantics {
                role = Role.Button
                selected = isSelected
            },
            color = colors.surface,
            shape = shape,
            border = border,
            content = face,
        )
    } else {
        Surface(
            modifier = modifier.testTag(if (preview) "runner-base-preview-$base" else "runner-base-empty-$base")
                .semantics(mergeDescendants = true) {
                    if (names.size > 1) contentDescription = names.joinToString("、")
                },
            color = colors.surface,
            shape = shape,
            border = border,
            content = face,
        )
    }
}
