package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
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
    ProductPage("比赛结果", onBack = close, backTag = "close-game-results") {
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
                Text("队内共享", fontSize = 13.sp)
                Text(game.postgameSharingChoice?.teamName ?: "仅保存在本机", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("result-sharing-summary"))
            }
        }
        ResultLineScore(game)
        ProductActionRow("纠正记录", "核对漏记、换人、守位与原始打席", tag = "result-history-correction") { navigate("history") }
        Choices("数据", mapOf("batting" to "打击", "pitching" to "投手", "fielding" to "守备", "records" to "记录"), category) { category = it }
        val team = if (home) game.home else game.away
        val order = (if (home) game.homeOrder else game.awayOrder) ?: team.players.map { it.id }
        val participants = (order + team.players.filter { it.id in game.batting || it.id in game.pitching || it.id in game.fielding }.map { it.id }).distinct()
        if (category == "records") {
            val appearances = AppearanceProjection.build(game)
            Text("中文比赛记录 · 按打席 · ${appearances.size} 条", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (appearances.isEmpty()) CardBlock { Text("还没有完成或正在进行的打席记录", fontSize = 13.sp) }
            appearances.reversed().forEach { appearance ->
                CardBlock(Modifier.testTag("result-appearance-${appearance.id}")) {
                    val batter = (game.away.players + game.home.players).firstOrNull { it.id == appearance.batterID }
                    Text("${appearance.inning}局${if (appearance.isTop) "上" else "下"} · 第${appearance.sequence}打席 · #${batter?.number.orEmpty()} ${batter?.name.orEmpty()}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(appearance.events.joinToString("；") { it.text }, fontSize = 13.sp)
                    if (appearance.events.any { it.incomplete }) TextButton({ navigate("review") }, Modifier.testTag("result-review-${appearance.id}")) { Text("待确认 · 补录", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    else if (!appearance.complete) Text(if (game.isFinal) "本打席未完成（比赛已结束）" else "当前打席进行中",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!game.practice) ReportShareButton("导出本打席 · 详细版", "result-export-appearance-${appearance.id}") { ReportExport.game(it, game, true, appearanceID = appearance.id) }
                }
            }
        } else {
            Choices("球队", mapOf("away" to game.away.shortName, "home" to game.home.shortName), if (home) "home" else "away") { home = it == "home" }
            Text("${team.shortName} ${when (category) { "batting" -> "打击"; "pitching" -> "投手"; else -> "守备" }}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (category == "pitching" && game.rules.mode == GameMode.coachPitch) Text("教练投手模式：投手数据不适用", fontSize = 13.sp)
            else {
                val columns = when (category) {
                    "batting" -> listOf("PA", "AB", "R", "H", "RBI", "BB", "SO", "SB")
                    "pitching" -> listOf("IP", "P", "SO", "H", "R", "ER", "BB")
                    else -> listOf("PO", "A", "E", "DP")
                }
                CardBlock {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val tableWidth = maxOf(maxWidth, 130.dp + 44.dp * columns.size)
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row(Modifier.width(tableWidth).padding(vertical = 6.dp)) {
                                Text("球员", Modifier.width(130.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                columns.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            participants.mapNotNull { id -> team.players.firstOrNull { it.id == id } }.forEach { player ->
                                HorizontalDivider(Modifier.width(tableWidth), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                val values = when (category) {
                                    "batting" -> (game.batting[player.id] ?: BattingLine()).let { listOf(it.plateAppearances, it.atBats, it.runs, it.hits, it.runsBattedIn, it.walks, it.strikeouts, it.stolenBases).map(Int::toString) }
                                    "pitching" -> (game.pitching[player.id] ?: PitchingLine()).let { listOf("${it.outsRecorded / 3}.${it.outsRecorded % 3}", "${it.pitches}", "${it.strikeouts}", "${it.hits}", "${it.runs}", "${it.earnedRuns}", "${it.walks}") }
                                    else -> (game.fielding[player.id] ?: FieldingLine()).let { listOf(it.putouts, it.assists, it.errors, it.doublePlays).map(Int::toString) }
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
        ProductActionRow("修改比赛时间", tag = "edit-game-date") { navigate("time") }
        if (game.isFinal) ProductActionRow("恢复为进行中", tag = "result-reopen-game") { model.command(Command("reopen"), done = close) }
        else if (!game.isScheduled) ProductActionRow("结束并保存比赛结果", tag = "open-finish-game") { navigate("finish") }
        if (!game.practice) {
            ReportShareButton("导出比赛报告 PDF", "result-export-game-pdf") { ReportExport.game(it, game, false) }
            ReportShareButton("导出逐球记录 PDF", "result-export-play-pdf") { ReportExport.game(it, game, true) }
        }
    }
}

@Composable
private fun ResultLineScore(game: Game) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        WorkbenchLineScore(game, tag = "game-linescore")
    }
}
