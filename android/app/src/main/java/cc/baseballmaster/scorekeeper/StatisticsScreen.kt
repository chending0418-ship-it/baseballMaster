package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import cc.baseballmaster.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Year
import java.util.Locale

private enum class StatsCategory(val label: String) { BATTING("打击"), PITCHING("投手"), FIELDING("守备") }
private enum class StatsMetric(val label: String, val category: StatsCategory, val lowFirst: Boolean = false) {
    AVG("打率 AVG", StatsCategory.BATTING), OPS("攻击指数 OPS", StatsCategory.BATTING),
    HITS("安打 H", StatsCategory.BATTING), HOMERS("本垒打 HR", StatsCategory.BATTING),
    RBI("打点 RBI", StatsCategory.BATTING), STOLEN("盗垒 SB", StatsCategory.BATTING),
    PA("打席 PA", StatsCategory.BATTING), ERA("防御率 ERA", StatsCategory.PITCHING, true),
    WHIP("每局被上垒率 WHIP", StatsCategory.PITCHING, true), SO("三振 SO", StatsCategory.PITCHING),
    IP("投球局数 IP", StatsCategory.PITCHING), PITCHES("用球数 P", StatsCategory.PITCHING),
    FPCT("守备率 FPCT", StatsCategory.FIELDING), PO("刺杀 PO", StatsCategory.FIELDING),
    ASSISTS("助杀 A", StatsCategory.FIELDING), ERRORS("失误 E", StatsCategory.FIELDING, true),
    DOUBLE_PLAYS("参与双杀 DP", StatsCategory.FIELDING),
}
private fun rate(n: Double?): String = n?.let { String.format(Locale.US, "%.3f", it).let { formatted -> if (it < 1) formatted.removePrefix("0") else formatted } } ?: "—"
private fun pitchRate(n: Double?): String = n?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
private fun avg(b: BattingLine) = if (b.atBats > 0) b.average else null
private fun ops(b: BattingLine) = if (b.atBats > 0) b.onBasePercentage + b.slugging else null
internal fun era(p: PitchingLine) = if (p.outsRecorded > 0) p.earnedRuns * 21.0 / p.outsRecorded else null
private fun whip(p: PitchingLine) = if (p.outsRecorded > 0) (p.walks + p.hits) * 3.0 / p.outsRecorded else null
private fun fpct(f: FieldingLine) = (f.putouts + f.assists + f.errors).let { if (it > 0) (f.putouts + f.assists).toDouble() / it else null }
private fun innings(p: PitchingLine) = "${p.outsRecorded / 3}.${p.outsRecorded % 3}"
private fun recorded(row: SeasonPlayerStatistics, category: StatsCategory): Boolean = when (category) {
    StatsCategory.BATTING -> row.batting != BattingLine()
    StatsCategory.PITCHING -> row.pitching != PitchingLine()
    StatsCategory.FIELDING -> row.fielding != FieldingLine()
}
private fun metricValue(row: SeasonPlayerStatistics, metric: StatsMetric): Double? = when (metric) {
    StatsMetric.AVG -> avg(row.batting); StatsMetric.OPS -> ops(row.batting)
    StatsMetric.HITS -> row.batting.hits.toDouble(); StatsMetric.HOMERS -> row.batting.homeRuns.toDouble()
    StatsMetric.RBI -> row.batting.runsBattedIn.toDouble(); StatsMetric.STOLEN -> row.batting.stolenBases.toDouble()
    StatsMetric.PA -> row.batting.plateAppearances.toDouble(); StatsMetric.ERA -> era(row.pitching)
    StatsMetric.WHIP -> whip(row.pitching); StatsMetric.SO -> row.pitching.strikeouts.toDouble()
    StatsMetric.IP -> row.pitching.outsRecorded.toDouble(); StatsMetric.PITCHES -> row.pitching.pitches.toDouble()
    StatsMetric.FPCT -> fpct(row.fielding); StatsMetric.PO -> row.fielding.putouts.toDouble()
    StatsMetric.ASSISTS -> row.fielding.assists.toDouble(); StatsMetric.ERRORS -> row.fielding.errors.toDouble()
    StatsMetric.DOUBLE_PLAYS -> row.fielding.doublePlays.toDouble()
}

@Composable
private fun StatsPicker(label: String, selected: String, values: Map<String, String>, tag: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TextButton({ expanded = true }, modifier = Modifier.fillMaxWidth().testTag(tag), contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text((values[selected] ?: "请选择") + "  ⌄", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(key) })
            }
        }
    }
}

@Composable
private fun StatisticsSegmented(label: String, values: Map<String, String>, selected: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEach { (key, text) ->
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape)
                .background(if (selected == key) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                .toggleable(value = selected == key, role = Role.RadioButton, onValueChange = { if (it) onSelect(key) })
                .testTag("choice-$label-$key"), contentAlignment = Alignment.Center) {
                Text(text, fontSize = 12.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StatisticsMetricGrid(values: List<Pair<String, String>>, tagPrefix: String? = null) {
    values.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) ->
                val metricModifier = if (tagPrefix == null) Modifier else Modifier
                    .testTag("$tagPrefix-$label").semantics(mergeDescendants = true) { contentDescription = "$label：$value" }
                Column(Modifier.weight(1f).then(metricModifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PlayerStatisticsMetrics(category: StatsCategory, row: SeasonPlayerStatistics, coachOnly: Boolean) {
    CardBlock(Modifier.testTag("stats-player-summary-${category.name}")) {
        Text(category.label, fontWeight = FontWeight.Bold)
        when (category) {
            StatsCategory.BATTING -> {
                val b = row.batting
                // Keep this aggregate distinct from the unchanged per-game rows below.
                Text("打席 ${b.plateAppearances} · 打数 ${b.atBats} · 安打 ${b.hits} · 打点 ${b.runsBattedIn}",
                    Modifier.testTag("stats-player-batting-totals"), fontSize = 12.sp)
                StatisticsMetricGrid(listOf(
                    "打率 AVG" to rate(avg(b)),
                    "上垒率 OBP" to rate(if (b.atBats + b.walks + b.hitByPitch + b.sacrifices > 0) b.onBasePercentage else null),
                    "长打率 SLG" to rate(if (b.atBats > 0) b.slugging else null),
                    "攻击指数 OPS" to rate(ops(b)), "得分 R" to "${b.runs}", "二垒安打 2B" to "${b.doubles}",
                    "三垒安打 3B" to "${b.triples}", "本垒打 HR" to "${b.homeRuns}", "保送 BB" to "${b.walks}",
                    "触身球 HBP" to "${b.hitByPitch}", "三振 SO" to "${b.strikeouts}", "盗垒 SB" to "${b.stolenBases}",
                    "盗垒失败 CS" to "${b.caughtStealing}", "牺牲打 SAC" to "${b.sacrifices}", "垒打数 TB" to "${b.totalBases}",
                ), "stats-player-batting")
            }
            StatsCategory.PITCHING -> if (coachOnly) {
                Text("教练投手模式：投手数据不适用", Modifier.testTag("stats-player-pitching-not-applicable"))
            } else {
                val p = row.pitching
                StatisticsMetricGrid(listOf(
                    "投球局数 IP" to innings(p), "防御率 ERA" to pitchRate(era(p)), "每局被上垒率 WHIP" to pitchRate(whip(p)),
                    "用球数 P" to "${p.pitches}", "好球 S" to "${p.strikes}", "面对打者 BF" to "${p.battersFaced}",
                    "被安打 H" to "${p.hits}", "失分 R" to "${p.runs}", "自责分 ER" to "${p.earnedRuns}",
                    "保送 BB" to "${p.walks}", "触身球 HBP" to "${p.hitByPitch}", "三振 SO" to "${p.strikeouts}",
                    "暴投 WP" to "${p.wildPitches}",
                ), "stats-player-pitching")
                Text("ERA 按 7 局计算；局数小数位表示出局数，0.1 为 1 人出局。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatsCategory.FIELDING -> {
                val f = row.fielding
                StatisticsMetricGrid(listOf(
                    "守备率 FPCT" to rate(fpct(f)), "守备机会 TC" to "${f.putouts + f.assists + f.errors}",
                    "刺杀 PO" to "${f.putouts}", "助杀 A" to "${f.assists}", "失误 E" to "${f.errors}", "参与双杀 DP" to "${f.doublePlays}",
                ), "stats-player-fielding")
                Text("守备率 =（刺杀 + 助杀）÷ 守备机会；DP 为球员参与次数。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("“—”表示没有计算该比率所需的记录。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerRecentSummary(title: String, tag: String, row: SeasonPlayerStatistics) {
    CardBlock(Modifier.testTag(tag)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text("${row.games} 场 · ${row.batting.hits} 安打 · ${row.batting.atBats} 打数 · ${row.batting.runsBattedIn} 打点",
            Modifier.testTag("$tag-totals"), fontSize = 12.sp)
        StatisticsMetricGrid(listOf("打率 AVG" to rate(avg(row.batting)), "攻击指数 OPS" to rate(ops(row.batting))), tag)
    }
}

@Composable
internal fun ReportShareButton(label: String, tag: String, mime: String = "application/pdf", generate: (android.content.Context) -> java.io.File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val file = generate(context)
                        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                            ?: error("无法写入所选文件")
                    }
                    saved = true
                } catch (failure: Exception) { error = failure.message ?: "导出未完成" }
                finally { busy = false }
            }
        }
    }
    fun share() {
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { generate(context) }
                ReportExport.share(context, file)
            } catch (failure: Exception) {
                error = failure.message ?: "导出未完成"
            } finally { busy = false }
        }
    }
    TextButton(onClick = { choose = true }, enabled = !busy, modifier = Modifier.testTag(tag)) { Text(if (busy) "正在生成…" else label) }
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text(label) }, text = { Text("选择保存到文件，或通过系统分享。") },
        confirmButton = { TextButton({ choose = false; saveLauncher.launch(if (mime == "image/png") "比赛海报.png" else "比赛记录.pdf") }) { Text("保存到文件") } },
        dismissButton = { TextButton({ choose = false; share() }) { Text("系统分享") } })
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("导出未完成") }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton({ error = null }) { Text("知道了") } })
    if (saved) AlertDialog(onDismissRequest = { saved = false }, title = { Text("已保存") }, confirmButton = { TextButton({ saved = false }) { Text("完成") } })
}

@Composable
internal fun StatisticsScreen(model: ScorekeeperViewModel) {
    val teams = model.rosters.filter { !it.isOpponent && it.source == "local" }
    if (teams.isEmpty()) {
        Text("统计", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        CardBlock { Text("还没有球队", fontWeight = FontWeight.Bold); Text("先在球队页面创建球队并添加球员。") }
        return
    }
    var teamID by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableIntStateOf(Year.now().value) }
    var modeKey by rememberSaveable { mutableStateOf("all") }
    var categoryName by rememberSaveable { mutableStateOf(StatsCategory.BATTING.name) }
    var metricName by rememberSaveable { mutableStateOf(StatsMetric.AVG.name) }
    var metricMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var recordsOnly by rememberSaveable { mutableStateOf(true) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var playerID by remember { mutableStateOf<String?>(null) }
    val team = teams.firstOrNull { it.id == teamID } ?: model.currentTeam ?: teams.first()
    val mode = GameMode.entries.firstOrNull { it.name == modeKey }
    val category = StatsCategory.valueOf(categoryName)
    val metric = StatsMetric.valueOf(metricName)
    val eligible = SeasonStatisticsCalculator.calculate(team, model.games, year, mode)
    val selectedValid = selected?.intersect(eligible.games.map { it.id }.toSet())
    val summary = SeasonStatisticsCalculator.calculate(team, model.games, year, mode, selectedValid)
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("统计", Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.CenterEnd)) {
            ReportShareButton("导出 PDF", "export-team-season-pdf") { context -> ReportExport.team(context, team, year, mode, summary) }
        }
    }
    CardBlock {
        StatsPicker("球队", team.id, teams.associate { it.id to (it.name + if (it.archived) " · 已归档" else " · 本机") }, "stats-team-picker") { teamID = it; selected = null }
        HorizontalDivider()
        StatsPicker("年度", year.toString(), SeasonStatisticsCalculator.years(model.games).associate { it.toString() to "${it} 年" }, "stats-season-picker") {
            year = it.toInt(); selected = null
        }
        Text("按比赛日期自动归入年度，无需创建或划分赛季。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ filterOpen = true }, Modifier.testTag("stats-open-game-filter")) { Text("筛选比赛") }
            Text("已选 ${selectedValid?.size ?: eligible.games.size} / ${eligible.games.size} 场", Modifier.testTag("stats-game-scope"), fontSize = 12.sp)
        }
        if (selected != null) TextButton({ selected = null }) { Text("恢复全部比赛") }
    }
    StatisticsSegmented("比赛模式", mapOf("all" to "全部模式", *GameMode.entries.map { it.name to it.title }.toTypedArray()), modeKey) { modeKey = it; selected = null }
    CardBlock {
        if (summary.incomplete > 0) Text("含过程或责任待确认的比赛 · 统计可能不完整", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Text("${summary.games.size} 场比赛 · ${summary.wins}胜${summary.losses}负${summary.ties}平", Modifier.testTag("stats-team-record"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        StatisticsMetricGrid(listOf("累计得分" to "${summary.runs}", "累计失分" to "${summary.runsAllowed}", "净胜分" to "${summary.runs - summary.runsAllowed}"))
    }
    Text("仅统计已结束的本队比赛；观赛、练习和进行中比赛不计入。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (summary.pending > 0) Text("有 ${summary.pending} 条待确认记录，统计将随赛后复核更新。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    StatisticsSegmented("统计分类", StatsCategory.entries.associate { it.name to it.label }, categoryName) {
        categoryName = it
        val first = StatsMetric.entries.first { metric -> metric.category.name == it }
        metricName = first.name; ascending = first.lowFirst
    }
    CardBlock(Modifier.testTag("stats-summary-${category.name}")) {
        if (category == StatsCategory.PITCHING && mode == GameMode.coachPitch) Text("教练投手模式：投手数据不适用")
        else when (category) {
            StatsCategory.BATTING -> {
                val b = summary.batting
                StatisticsMetricGrid(listOf("打率 AVG" to rate(avg(b)), "上垒率 OBP" to rate(if (b.atBats + b.walks + b.hitByPitch + b.sacrifices > 0) b.onBasePercentage else null), "长打率 SLG" to rate(if (b.atBats > 0) b.slugging else null), "攻击指数 OPS" to rate(ops(b)), "打席 PA" to "${b.plateAppearances}", "打数 AB" to "${b.atBats}", "安打 H" to "${b.hits}", "本垒打 HR" to "${b.homeRuns}", "打点 RBI" to "${b.runsBattedIn}", "保送 BB" to "${b.walks}", "三振 SO" to "${b.strikeouts}", "盗垒 SB" to "${b.stolenBases}"))
            }
            StatsCategory.PITCHING -> {
                val p = summary.pitching
                StatisticsMetricGrid(listOf("投球局数 IP" to innings(p), "防御率 ERA" to pitchRate(era(p)), "每局被上垒率 WHIP" to pitchRate(whip(p)), "用球数 P" to "${p.pitches}", "好球 S" to "${p.strikes}", "面对打者 BF" to "${p.battersFaced}", "被安打 H" to "${p.hits}", "失分 R" to "${p.runs}", "自责分 ER" to "${p.earnedRuns}", "保送 BB" to "${p.walks}", "三振 SO" to "${p.strikeouts}", "暴投 WP" to "${p.wildPitches}"))
                Text("ERA 按 7 局计算；局数小数位表示出局数，0.1 为 1 人出局。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatsCategory.FIELDING -> {
                val f = summary.fielding
                StatisticsMetricGrid(listOf("守备率 FPCT" to rate(fpct(f)), "守备机会 TC" to "${f.putouts + f.assists + f.errors}", "刺杀 PO" to "${f.putouts}", "助杀 A" to "${f.assists}", "失误 E" to "${f.errors}", "参与双杀 DP" to "${f.doublePlays}"))
                Text("守备率 =（刺杀 + 助杀）÷ 守备机会；DP 为球员参与次数。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (summary.games.isEmpty()) CardBlock { Text(if (selected == null) "这个年度还没有已结束的比赛" else "当前筛选范围没有比赛"); Text("完成并保存比赛后会自动加入可选范围。", fontSize = 12.sp) }
    Text("球员表现", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("stats-search"), label = { Text("搜索姓名、英文名或背号") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(recordsOnly, { recordsOnly = it }, Modifier.testTag("stats-records-only"))
        Text("仅显示有记录球员")
        Spacer(Modifier.weight(1f))
        TextButton({ ascending = !ascending }, Modifier.testTag("stats-sort-direction")) { Text(if (ascending) "升序" else "降序") }
    }
    val rows = summary.players.filter { row ->
        (!recordsOnly || recorded(row, category)) && listOf(row.player.name, row.player.englishName, row.player.jerseyNumbers.joinToString(" ")).any { it.contains(query.trim(), ignoreCase = true) }
    }.sortedWith { a, b ->
        val x = metricValue(a, metric)
        val y = metricValue(b, metric)
        when { x == null && y != null -> 1; x != null && y == null -> -1; x != null && y != null && x != y -> if (ascending) x.compareTo(y) else y.compareTo(x); else -> compareValuesBy(a, b, { it.player.number.toIntOrNull() ?: 0 }, { it.player.name }, { it.player.id }) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton({ metricMenu = true }, Modifier.testTag("stats-sort-picker")) { Text("排序：${metric.label}") }
            DropdownMenu(expanded = metricMenu, onDismissRequest = { metricMenu = false }) {
                StatsMetric.entries.filter { it.category == category }.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        metricName = option.name; ascending = option.lowFirst; metricMenu = false
                    })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("${rows.size} 名球员", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
    if (rows.isEmpty()) CardBlock { Text("没有符合条件的球员"); Text("可清空搜索或关闭“仅显示有记录球员”。", fontSize = 12.sp) }
    rows.forEach { row ->
        CardBlock(Modifier.testTag("stats-player-${row.player.id}").clickable { playerID = row.player.id }) {
            Text(row.player.name, fontWeight = FontWeight.Bold)
            Text("#${row.player.number} · ${row.games} 场" + if (row.currentRoster) "" else " · 历史球员", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(when (category) { StatsCategory.BATTING -> "打率 ${rate(avg(row.batting))} · 安打 ${row.batting.hits} · 打点 ${row.batting.runsBattedIn}"; StatsCategory.PITCHING -> "ERA ${pitchRate(era(row.pitching))} · ${innings(row.pitching)} 局 · 三振 ${row.pitching.strikeouts}"; StatsCategory.FIELDING -> "守备率 ${rate(fpct(row.fielding))} · 刺杀 ${row.fielding.putouts} · 助杀 ${row.fielding.assists}" })
        }
    }
    if (filterOpen) {
        var draft by remember(team.id, year, modeKey) { mutableStateOf(selectedValid ?: eligible.games.map { it.id }.toSet()) }
        FormPage("筛选比赛", false, true, "stats-save-filter", "完成", { filterOpen = false }, {
            selected = if (draft.size == eligible.games.size) null else draft; filterOpen = false
        }) {
            Text("选择参与统计的比赛", fontWeight = FontWeight.Bold)
            Row { TextButton({ draft = eligible.games.map { it.id }.toSet() }) { Text("全选") }; TextButton({ draft = emptySet() }) { Text("清除") } }
            eligible.games.forEach { game ->
                Row(Modifier.fillMaxWidth().clickable { draft = if (game.id in draft) draft - game.id else draft + game.id }) {
                    Checkbox(game.id in draft, { checked -> draft = if (checked) draft + game.id else draft - game.id }, Modifier.testTag("stats-team-game-${game.id}"))
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text("${game.away.name} vs ${game.home.name}")
                        Text("${game.awayRuns} : ${game.homeRuns} · ${game.rules.mode.title}", fontSize = 12.sp)
                        Text(MatchDates.label(game), Modifier.testTag("stats-team-game-date-${game.id}"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    val player = summary.players.firstOrNull { it.player.id == playerID }
    if (player != null) {
        var playerYear by remember(player.player.id) { mutableIntStateOf(year) }
        var playerModeKey by remember(player.player.id) { mutableStateOf(modeKey) }
        var playerCategoryName by remember(player.player.id) { mutableStateOf(categoryName) }
        // iOS passes the eligible team IDs even when no manual subset was selected. This
        // prevents a personal mode change from escaping the team's initial mode scope.
        var inheritedIDs by remember(player.player.id) {
            mutableStateOf<Set<String>?>(selectedValid ?: eligible.games.map { it.id }.toSet())
        }
        val playerMode = GameMode.entries.firstOrNull { it.name == playerModeKey }
        val playerCategory = StatsCategory.valueOf(playerCategoryName)
        val allPlayerGames = PlayerStatisticsProjection.calculate(team, player.player, model.games, playerYear, playerMode, inheritedIDs)
        val eligiblePlayerIDs = allPlayerGames.eligibleGames.map { it.id }.toSet()
        // A new visit, year or mode selects all then-eligible games, without mutating the team filter.
        // Later data refreshes intersect the selection instead of silently adding unseen games.
        var selectedPlayerGames by remember(player.player.id, playerYear, playerModeKey, inheritedIDs) { mutableStateOf(eligiblePlayerIDs) }
        val projection = if (selectedPlayerGames == eligiblePlayerIDs) allPlayerGames else
            PlayerStatisticsProjection.calculate(team, player.player, model.games, playerYear, playerMode, inheritedIDs, selectedPlayerGames)
        val playerGames = projection.eligibleGames
        val selectedGames = projection.selectedGames
        val selectedPlayerIDs = selectedGames.map { it.id }.toSet()
        val shown = projection.statistics
        FormPage("球员统计", false, true, "stats-player-close", "完成", { playerID = null }, { playerID = null }) {
            Text("#${player.player.number} ${player.player.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (player.player.englishName.isNotBlank()) Text(player.player.englishName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${playerYear} 年 · 已选 ${selectedGames.size} / ${playerGames.size} 场" + if (player.currentRoster) "" else " · 历史球员",
                Modifier.testTag("stats-player-game-scope"))
            CardBlock {
                StatsPicker("个人统计年度", playerYear.toString(), SeasonStatisticsCalculator.years(model.games).associate { it.toString() to "${it} 年" }, "stats-player-season-picker") {
                    // iOS clears an inherited match subset when changing the personal year.
                    val chosen = it.toInt()
                    if (chosen != playerYear) { inheritedIDs = null; playerYear = chosen }
                }
                if (inheritedIDs != null) Text("范围继承球队所选比赛；切换个人年度后恢复该年度范围。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatisticsSegmented("球员比赛模式", mapOf("all" to "全部模式", *GameMode.entries.map { it.name to it.title }.toTypedArray()), playerModeKey) { playerModeKey = it }
            StatisticsSegmented("球员统计分类", StatsCategory.entries.associate { it.name to it.label }, playerCategoryName) { playerCategoryName = it }
            ReportShareButton("导出个人年度 PDF", "export-player-season-pdf") { context -> ReportExport.player(context, team, playerYear, shown, selectedGames) }
            if (projection.scope.incompleteGames > 0) Text("含过程或责任待确认的比赛 · 统计可能不完整", Modifier.testTag("stats-player-incomplete"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (projection.scope.pendingRecords > 0) Text("所选比赛有 ${projection.scope.pendingRecords} 条待确认记录，统计将随赛后复核更新。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            PlayerStatisticsMetrics(playerCategory, shown, playerMode == GameMode.coachPitch || projection.scope.coachPitchOnly)
            if (playerCategory == StatsCategory.BATTING) {
                Text("表现总结", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                PlayerRecentSummary("近 3 场", "stats-player-recent3", projection.recent3)
                PlayerRecentSummary("近 10 场", "stats-player-recent10", projection.recent10)
                PlayerRecentSummary(if (inheritedIDs == null && selectedGames.size == playerGames.size) "本年度" else "当前筛选", "stats-player-current", shown)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("逐场比赛", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton({ selectedPlayerGames = eligiblePlayerIDs }, Modifier.testTag("stats-player-select-all")) { Text("全选") }
                TextButton({ selectedPlayerGames = emptySet() }, Modifier.testTag("stats-player-clear")) { Text("清除") }
            }
            if (playerGames.isEmpty()) CardBlock { Text("这个范围还没有该球员的已结束比赛"); Text("可切换个人年度或比赛模式。", fontSize = 12.sp) }
            playerGames.forEach { game ->
                CardBlock {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(game.id in selectedPlayerIDs, { checked -> selectedPlayerGames = if (checked) selectedPlayerIDs + game.id else selectedPlayerIDs - game.id }, modifier = Modifier.testTag("stats-player-game-${game.id}"))
                        Column(Modifier.weight(1f)) {
                            Text("${game.away.name} vs ${game.home.name}", fontWeight = FontWeight.Bold)
                            Text("${game.awayRuns} : ${game.homeRuns} · ${game.rules.mode.title}")
                            Text(MatchDates.label(game), Modifier.testTag("stats-player-game-date-${game.id}"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (playerCategory) {
                        StatsCategory.BATTING -> game.batting[player.player.id]?.let { Text("打数 ${it.atBats} · 安打 ${it.hits} · 打点 ${it.runsBattedIn}", fontSize = 12.sp) } ?: Text("无打击记录", fontSize = 12.sp)
                        StatsCategory.PITCHING -> if (game.rules.mode == GameMode.coachPitch) Text("教练投手模式：投手数据不适用", fontSize = 12.sp)
                            else game.pitching[player.player.id]?.let { Text("投球 ${innings(it)} 局 · 三振 ${it.strikeouts} · ERA ${pitchRate(era(it))}", fontSize = 12.sp) } ?: Text("无投手记录", fontSize = 12.sp)
                        StatsCategory.FIELDING -> game.fielding[player.player.id]?.let { Text("刺杀 ${it.putouts} · 助杀 ${it.assists} · 失误 ${it.errors}", fontSize = 12.sp) } ?: Text("无守备记录", fontSize = 12.sp)
                    }
                    TextButton({ playerID = null; model.open(game.id) }) { Text("查看比赛") }
                }
            }
        }
    }
}
