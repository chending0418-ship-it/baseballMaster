package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*

/** In-app result reading must not require exporting a PDF. */
@Composable
internal fun GameResultsScreen(game: Game, model: ScorekeeperViewModel, close: () -> Unit, navigate: (String) -> Unit) {
    var home by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("batting") }
    FormPage("比赛结果", false, true, "close-game-results", "完成", close, close) {
        CardBlock {
            Text("${game.away.shortName}  ${game.awayRuns} : ${game.homeRuns}  ${game.home.shortName}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if(game.isFinal) "比赛已结束" else "${game.inning}局${if(game.isTop) "上" else "下"} · 正在记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        (game.playedAt ?: game.startedAt ?: game.scheduledAt)?.let { Text("比赛时间：${formatMatchDate(it, game.playedTimeZoneID)}", Modifier.testTag("result-game-date"), fontSize = 13.sp) }
        TextButton({ navigate("history") }, Modifier.testTag("result-history-correction")) { Text("纠正记录") }
        TextButton({ navigate("time") }, Modifier.testTag("edit-game-date")) { Text("修改比赛时间") }
        CardBlock(Modifier.testTag("game-linescore")) {
            val count = maxOf(game.inning, game.awayRunsByInning.size, game.homeRunsByInning.size)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    Text("球队", Modifier.width(90.dp), fontWeight = FontWeight.Bold)
                    (1..count).forEach { Text("$it", Modifier.width(32.dp)) }
                    listOf("R", "H", "E").forEach { Text(it, Modifier.width(32.dp), fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(Modifier.padding(vertical = 7.dp))
                listOf(false, true).forEach { isHome ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text((if(isHome) game.home else game.away).shortName, Modifier.width(90.dp), fontWeight = FontWeight.SemiBold)
                        val runs = if(isHome) game.homeRunsByInning else game.awayRunsByInning
                        (0 until count).forEach { index ->
                            val played = index < game.inning - 1 || index == game.inning - 1 && (!isHome || !game.isTop)
                            Text(if(played) (runs.getOrNull(index) ?: 0).toString() else "—", Modifier.width(32.dp))
                        }
                        listOf(if(isHome) game.homeRuns else game.awayRuns, if(isHome) game.homeHits else game.awayHits, if(isHome) game.homeErrors else game.awayErrors).forEach { Text("$it", Modifier.width(32.dp), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        if(category != "records") Choices("球队", mapOf("away" to game.away.shortName, "home" to game.home.shortName), if(home) "home" else "away") { home = it == "home" }
        Choices("数据", mapOf("batting" to "打击", "pitching" to "投手", "fielding" to "守备", "records" to "记录"), category) { category = it }
        val team = if(home) game.home else game.away
        val order = (if(home) game.homeOrder else game.awayOrder) ?: team.players.map { it.id }
        val participants = (order + team.players.filter { it.id in game.batting || it.id in game.pitching || it.id in game.fielding }.map { it.id }).distinct()
        if(category == "records") {
            val appearances = AppearanceProjection.build(game)
            Text("中文比赛记录 · 按打席 · ${appearances.size} 条", fontWeight = FontWeight.Bold)
            if (appearances.isEmpty()) CardBlock { Text("还没有完成或正在进行的打席记录") }
            appearances.reversed().forEach { appearance ->
                CardBlock(Modifier.testTag("result-appearance-${appearance.id}")) {
                    val batter = (game.away.players + game.home.players).firstOrNull { it.id == appearance.batterID }
                    Text("${appearance.inning}局${if(appearance.isTop) "上" else "下"} · 第${appearance.sequence}打席 · #${batter?.number.orEmpty()} ${batter?.name.orEmpty()}", fontWeight = FontWeight.SemiBold)
                    Text(appearance.events.joinToString("；") { it.text }, fontSize = 14.sp)
                    if(appearance.events.any { it.incomplete }) TextButton({ navigate("review") }, Modifier.testTag("result-review-${appearance.id}")) { Text("待确认 · 补录", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    else if(!appearance.complete) Text("当前打席进行中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!game.practice) ReportShareButton("导出本打席 · 详细版", "result-export-appearance-${appearance.id}") { ReportExport.game(it, game, true, appearanceID = appearance.id) }
                }
            }
        } else if(category == "pitching" && game.rules.mode == GameMode.coachPitch) Text("教练投手模式：投手数据不适用")
        else participants.mapNotNull { id -> team.players.firstOrNull { it.id == id } }.forEach { player ->
            CardBlock(Modifier.testTag("game-result-${category}-${player.id}")) {
                Text("#${player.number} ${player.name}", fontWeight = FontWeight.Bold)
                when(category) {
                    "batting" -> { val b = game.batting[player.id] ?: BattingLine()
                        Text("打席 ${b.plateAppearances} · 打数 ${b.atBats} · 安打 ${b.hits} · 得分 ${b.runs}", fontSize = 13.sp)
                        Text("打点 ${b.runsBattedIn} · 保送 ${b.walks} · 三振 ${b.strikeouts} · 盗垒 ${b.stolenBases}", fontSize = 13.sp)
                    }
                    "pitching" -> { val p = game.pitching[player.id] ?: PitchingLine()
                        Text("局数 ${p.outsRecorded/3}.${p.outsRecorded%3} · 用球 ${p.pitches} · 三振 ${p.strikeouts}", fontSize = 13.sp)
                        Text("被安打 ${p.hits} · 失分 ${p.runs} · 自责分 ${p.earnedRuns} · 保送 ${p.walks}", fontSize = 13.sp)
                    }
                    else -> { val f = game.fielding[player.id] ?: FieldingLine()
                        Text("刺杀 ${f.putouts} · 助杀 ${f.assists} · 失误 ${f.errors} · 参与双杀 ${f.doublePlays}", fontSize = 13.sp)
                    }
                }
            }
        }
        if (game.isFinal) TextButton({ model.command(Command("reopen"), done = close) }, Modifier.testTag("result-reopen-game")) { Text("恢复为进行中") }
        else if(!game.isScheduled) TextButton({ navigate("finish") }, Modifier.testTag("open-finish-game")) { Text("结束并保存比赛结果") }
        if(!game.practice) {
            ReportShareButton("导出比赛报告 PDF", "result-export-game-pdf") { ReportExport.game(it, game, false) }
            ReportShareButton("导出逐球记录 PDF", "result-export-play-pdf") { ReportExport.game(it, game, true) }
        }
    }
}
