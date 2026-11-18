package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

/** The score, line score and player tables can be read without generating a report. */
@Composable
internal fun GameResultsScreen(game: Game, model: ScorekeeperViewModel, close: () -> Unit, navigate: (String) -> Unit) {
    var home by rememberSaveable(game.id) { mutableStateOf(false) }
    var category by rememberSaveable(game.id) { mutableStateOf("batting") }
    ProductPage("比赛结果", onBack = close, backTag = "close-game-results", scrollableContent = false) {
        val appearances = if (category == "records") remember(game) { AppearanceProjection.build(game).asReversed() } else emptyList()
        LazyColumn(Modifier.fillMaxSize().testTag("product-page-scroll"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("summary") {
                ResultSummary(game, category, { category = it }, navigate) { model.openGameAnalysis(game) }
            }
            if (category == "records") {
                item("appearance-count") {
                    Text("中文比赛记录 · 按打席 · ${appearances.size} 条", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                if (appearances.isEmpty()) item("empty") { CardBlock { Text("还没有完成或正在进行的打席记录", fontSize = 13.sp) } }
                items(appearances, key = { "appearance-${it.id}" }) { appearance ->
                    ResultAppearance(game, appearance, navigate)
                }
            } else {
                item("player-table") {
                    ResultPlayerTable(game, home, category) { home = it }
                }
            }
            item("actions") {
                ResultActions(game, model, category, close, navigate)
            }
        }
    }
}

@Composable
private fun ResultSummary(game: Game, category: String, selectCategory: (String) -> Unit, navigate: (String) -> Unit, openAnalysis: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardBlock {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${game.inning}局${if (game.isTop) "上" else "下"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (game.isFinal) "比赛结束" else "正在记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeamMark(game.away.shortName, 36)
                    Text(game.away.shortName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text("${game.awayRuns} : ${game.homeRuns}", fontSize = 32.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.testTag("result-final-score"))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeamMark(game.home.shortName, 36)
                    Text(game.home.shortName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        (game.playedAt ?: game.startedAt ?: game.scheduledAt)?.let {
            Text("比赛时间：${formatMatchDate(it, game.playedTimeZoneID)}", Modifier.testTag("result-game-date"),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!game.isObservation) CardBlock {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("保存于本机", Modifier.weight(1f), fontSize = 13.sp)
                Text(game.postgameSharingChoice?.teamName?.let { "$it · 尚未上传" } ?: "仅本机",
                    Modifier.weight(1f).testTag("result-sharing-summary"), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
        ResultLineScore(game)
        if (game.isFinal && !game.practice && !game.isScheduled) {
            AnalysisEntry("比赛分析", "result-open-game-analysis", openAnalysis)
        }
        ProductActionRow("纠正记录", "核对漏记、换人、守位与原始打席", tag = "result-history-correction") { navigate("history") }
        Choices("数据", mapOf("batting" to "打击", "pitching" to "投手", "fielding" to "守备", "records" to "记录"), category) { selectCategory(it) }
    }
}

@Composable
private fun ResultPlayerTable(game: Game, home: Boolean, category: String, selectHome: (Boolean) -> Unit) {
    val team = if (home) game.home else game.away
    val participants = remember(game, team.id) { ReportStatisticsProjection.gameParticipants(game, team.id) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Choices("球队", mapOf("away" to game.away.shortName, "home" to game.home.shortName), if (home) "home" else "away") { selectHome(it == "home") }
        Text("${team.shortName} ${when (category) { "batting" -> "打击"; "pitching" -> "投手"; else -> "守备" }}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (category == "pitching" && game.rules.mode == GameMode.coachPitch) Text("教练投手模式：投手数据不适用", fontSize = 13.sp)
        else {
            val columns = when (category) {
                "batting" -> listOf("PA", "AB", "R", "H", "TB", "AVG", "RBI", "BB", "SO", "SB")
                "pitching" -> listOf("IP", "BF", "P", "P–S", "SO", "H", "R", "ER", "BB", "ERA")
                else -> listOf("PO", "A", "E", "DP", "CCS", "PB")
            }
            if (category == "pitching" && (game.incompletePrefix || game.statisticsIncomplete)) {
                Text("部分记录未完成，投手局数和投球统计可能不完整。", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val tableRows = if (category == "pitching") participants.filter { it.player.id in game.pitching } else participants
            if (tableRows.isEmpty()) Text("暂无符合当前范围的记录", fontSize = 13.sp)
            else CardBlock {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tableWidth = maxOf(maxWidth, 130.dp + 44.dp * columns.size)
                    val scroll = key(category, team.id) { rememberScrollState() }
                    Column(Modifier.horizontalScroll(scroll)) {
                        Row(Modifier.width(tableWidth).padding(vertical = 6.dp)) {
                            Text("球员", Modifier.width(130.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            columns.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        tableRows.forEach { row ->
                            val player = row.player
                            HorizontalDivider(Modifier.width(tableWidth), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            val values = when (category) {
                                "batting" -> row.batting.let {
                                    listOf("${it.plateAppearances}", "${it.atBats}", "${it.runs}", "${it.hits}",
                                        "${it.totalBases}", rate(if (it.atBats > 0) it.average else null),
                                        "${it.runsBattedIn}", "${it.walks}", "${it.strikeouts}", "${it.stolenBases}")
                                }
                                "pitching" -> row.pitching.let {
                                    listOf("${it.outsRecorded / 3}.${it.outsRecorded % 3}", "${it.battersFaced}",
                                        "${it.pitches}", "${it.pitches}–${it.strikes}", "${it.strikeouts}", "${it.hits}",
                                        "${it.runs}", "${it.earnedRuns}", "${it.walks}", pitchRate(era(it)))
                                }
                                else -> row.fielding.let { listOf("${it.putouts}", "${it.assists}", "${it.errors}", "${it.doublePlays}",
                                    it.catcherCaughtStealing?.toString() ?: "—", it.passedBalls?.toString() ?: "—") }
                            }
                            Row(Modifier.width(tableWidth).padding(vertical = 13.dp).testTag("game-result-$category-${player.id}")) {
                                Text("#${player.number} ${player.name}", Modifier.width(130.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                values.forEach { Text(it, Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.Center) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultActions(game: Game, model: ScorekeeperViewModel, category: String, close: () -> Unit, navigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (category == "fielding") CatcherStatisticsNote()
        ProductActionRow("修改比赛时间", tag = "edit-game-date") { navigate("time") }
        if (game.isFinal) ResumeGameAction(game, onResume = { model.command(it, done = close) }) { request ->
            ProductActionRow("恢复为进行中", tag = "result-reopen-game", onClick = request)
        }
        else if (!game.isScheduled) ProductActionRow("结束并保存比赛结果", tag = "open-finish-game") { navigate("finish") }
        if (!game.practice) {
            ReportShareButton("导出比赛报告 PDF", "result-export-game-pdf", previewTitle = "比赛报告") { ReportExport.game(it, game, false) }
            ReportShareButton("导出逐球记录 PDF", "result-export-play-pdf") { ReportExport.game(it, game, true) }
        }
    }
}

@Composable
private fun ResultAppearance(game: Game, appearance: ProjectedAppearance, navigate: (String) -> Unit) {
    CardBlock(Modifier.testTag("result-appearance-${appearance.id}")) {
        val batter = (game.away.players + game.home.players).firstOrNull { it.id == appearance.batterID }
        Text("${appearance.inning}局${if (appearance.isTop) "上" else "下"} · 第${appearance.sequence}打席 · #${batter?.number.orEmpty()} ${batter?.name.orEmpty()}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(appearance.events.joinToString("；") { it.text }, fontSize = 13.sp)
        val pending = appearance.events.filter { it.needsReview }
        pending.forEachIndexed { index, event ->
            val tag = if (index == 0) "result-review-${appearance.id}" else "result-review-event-${event.id}"
            TextButton({ navigate("review:${event.id}") }, Modifier.testTag(tag)) {
                Text(if (pending.size == 1) "待确认 · 补录" else "补录 · ${event.text}",
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        if (pending.isEmpty() && !appearance.complete) Text(if (game.isFinal) "本打席未完成（比赛已结束）" else "当前打席进行中",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!game.practice) ReportShareButton("导出本打席 · 详细版", "result-export-appearance-${appearance.id}") { ReportExport.game(it, game, true, appearanceID = appearance.id) }
    }
}

@Composable
private fun ResultLineScore(game: Game) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        WorkbenchLineScore(game, tag = "game-linescore")
    }
}
