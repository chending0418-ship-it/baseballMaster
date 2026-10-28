package cc.baseballmaster.scorekeeper

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import cc.baseballmaster.core.*
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Print-oriented local reports. The caller chooses whether to share the generated private file. */
internal object ReportExport {
    private val navy = Color.rgb(15, 41, 64)
    private val green = Color.rgb(26, 110, 82)
    private val secondary = Color.rgb(92, 109, 122)
    private const val margin = 36f
    internal const val emptyTableMessage = "暂无符合当前范围的记录。"

    internal fun emptyTableTextBaseline(headerBottom: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.DEFAULT }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(emptyTableMessage, 0, emptyTableMessage.length, bounds)
        // Canvas text positions are baselines, whereas table y is its lower edge.
        // Measure the actual Chinese glyphs so the message cannot rise into the dark header.
        return headerBottom - bounds.top.coerceAtMost(0) + 6f
    }

    private class Writer(val kind: String, val title: String, portrait: Boolean = false) : AutoCloseable {
        private val width = if (portrait) 595 else 842
        private val height = if (portrait) 842 else 595
        private val document = PdfDocument()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 0f
        init { nextPage(); line(title, 23f, true); line(kind, 10f, muted = true) }
        private fun draw(text: String, x: Float, at: Float, size: Float, bold: Boolean = false, color: Int = navy) {
            paint.textSize = size; paint.color = color
            paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            page!!.canvas.drawText(text, x, at, paint)
        }
        private fun nextPage() {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            page!!.canvas.drawColor(Color.WHITE)
            draw("BASEBALLMASTER", margin, 30f, 11f, true)
            draw(kind, width / 2f, 30f, 10f, true, green)
            paint.color = green; page!!.canvas.drawRect(margin, 42f, width - margin, 44f, paint)
            draw("BaseballMaster  |  本地记录导出", margin, height - 22f, 8f, color = secondary)
            draw("第 $pageNumber 页", width - 85f, height - 22f, 8f, color = secondary)
            y = 72f
        }
        fun line(text: String, size: Float = 11f, bold: Boolean = false, muted: Boolean = false) {
            paint.textSize = size; paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            for (raw in text.split('\n')) {
                var remaining = raw
                if (remaining.isEmpty()) { y += size * 1.5f; continue }
                while (remaining.isNotEmpty()) {
                    if (y + size * 2 > height - 42) nextPage()
                    paint.textSize = size
                    paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                    val n = paint.breakText(remaining, true, width - margin * 2, null).coerceAtLeast(1)
                    draw(remaining.substring(0, n), margin, y, size, bold, if (muted) secondary else navy)
                    y += size * 1.5f; remaining = remaining.substring(n)
                }
            }
            y += 6f
        }
        fun table(title: String, headers: List<String>, rows: List<List<String>>, weights: List<Float>? = null) {
            require(headers.isNotEmpty() && rows.all { it.size == headers.size })
            val availableWidth = width - margin * 2
            val columnWeights = weights ?: headers.indices.map { if (it == 0) 4f else 1f }
            require(columnWeights.size == headers.size && columnWeights.all { it > 0 })
            val widths = columnWeights.map { availableWidth * it / columnWeights.sum() }
            val leading = 11f
            fun header(continued: Boolean) {
                if (y + 55 > height - 42) nextPage()
                line(title + if (continued) "（续）" else "", 12f, true)
                if (y + 40 > height - 42) nextPage()
                paint.color = navy; page!!.canvas.drawRect(margin, y, width - margin, y + 22, paint)
                var x = margin
                headers.forEachIndexed { i, label ->
                    paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 8f
                    val inset = if (i == 0) 5f else ((widths[i] - paint.measureText(label)) / 2).coerceAtLeast(3f)
                    draw(label, x + inset, y + 15, 8f, true, Color.WHITE); x += widths[i]
                }
                y += 22
            }
            fun wrap(value: String, limit: Float): List<String> {
                paint.textSize = 8.5f; paint.typeface = Typeface.DEFAULT
                return value.split('\n').flatMap { part ->
                    if (part.isEmpty()) listOf("") else buildList {
                        var rest = part
                        while (rest.isNotEmpty()) { val n = paint.breakText(rest, true, limit.coerceAtLeast(5f), null).coerceAtLeast(1); add(rest.substring(0, n)); rest = rest.substring(n) }
                    }
                }
            }
            header(false)
            if (rows.isEmpty()) {
                y = emptyTableTextBaseline(y)
                line(emptyTableMessage, 9f, muted = true)
                return
            }
            rows.forEachIndexed { rowIndex, row ->
                val cells = row.mapIndexed { i, value -> wrap(value, widths[i] - 8) }
                val lines = cells.maxOf { it.size }
                var offset = 0
                while (offset < lines) {
                    if (y + leading + 10 > height - 42) { nextPage(); header(true) }
                    val count = minOf(lines - offset, ((height - 42 - y - 8) / leading).toInt().coerceAtLeast(1))
                    val rowHeight = count * leading + 8
                    paint.color = if (row.firstOrNull() == "合计") Color.rgb(224, 240, 232) else if (rowIndex % 2 == 0) Color.rgb(246, 246, 246) else Color.WHITE
                    page!!.canvas.drawRect(margin, y, width - margin, y + rowHeight, paint)
                    var x = margin
                    cells.forEachIndexed { i, cell ->
                        for (n in offset until minOf(offset + count, cell.size)) {
                            val text = cell[n]; paint.textSize = 8.5f; paint.typeface = Typeface.DEFAULT
                            val inset = if (i == 0) 4f else ((widths[i] - paint.measureText(text)) / 2).coerceAtLeast(4f)
                            draw(text, x + inset, y + 12 + (n - offset) * leading, 8.5f, row.firstOrNull() == "合计")
                        }
                        x += widths[i]
                    }
                    y += rowHeight; offset += count
                }
            }
            y += 12
        }
        fun situationPair(before: Situation?, after: Situation?, game: Game) {
            if (y + 128 > height - 42) nextPage()
            val cardWidth = (width - margin * 2 - 14) / 2f
            listOf("记录前" to before, "记录后" to after).forEachIndexed { i, (label, state) ->
                val x = margin + i * (cardWidth + 14)
                paint.color = Color.rgb(239, 245, 242); page!!.canvas.drawRect(x, y, x + cardWidth, y + 116, paint)
                draw(label, x + 12, y + 18, 10f, true, green)
                if (state == null) draw("此局面未记录", x + 12, y + 58, 10f, color = secondary)
                else {
                    val cx = x + 64; val cy = y + 72
                    val diamond = android.graphics.Path().apply { moveTo(cx, cy - 26); lineTo(cx + 29, cy); lineTo(cx, cy + 26); lineTo(cx - 29, cy); close() }
                    paint.color = green; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; page!!.canvas.drawPath(diamond, paint); paint.style = Paint.Style.FILL
                    listOf(cx + 29 to cy, cx to cy - 26, cx - 29 to cy).forEachIndexed { base, (px, py) ->
                        paint.color = if (state.bases.getOrNull(base) != null) green else Color.WHITE; page!!.canvas.drawCircle(px, py, 5f, paint)
                        paint.color = green; paint.style = Paint.Style.STROKE; page!!.canvas.drawCircle(px, py, 5f, paint); paint.style = Paint.Style.FILL
                    }
                    draw("${state.inning} 局${if (state.isTop) "上" else "下"} · ${state.awayScore}:${state.homeScore}", x + 115, y + 40, 10f, true)
                    draw("B ${state.balls}   S ${state.strikes}   O ${state.outs}", x + 115, y + 57, 10f, true)
                    state.bases.forEachIndexed { base, id ->
                        val name = (game.home.players + game.away.players).firstOrNull { it.id == id }?.name ?: "—"
                        draw("${base + 1} 垒：$name", x + 115, y + 73 + base * 12, 8f)
                    }
                }
            }
            y += 126
        }
        fun battedBall(observation: Observation) {
            if (y + 150 > height - 42) nextPage()
            val left = margin + 4; val top = y; val fieldWidth = 210f; val fieldHeight = 138f
            fun x(fraction: Float) = left + fieldWidth * fraction
            fun yy(fraction: Float) = top + fieldHeight * fraction
            val field = android.graphics.Path().apply {
                moveTo(x(.5f), yy(.9f)); lineTo(x(.08f), yy(.38f)); quadTo(x(.5f), yy(-.13f), x(.92f), yy(.38f)); close()
            }
            paint.color = Color.rgb(224, 239, 230); paint.style = Paint.Style.FILL; page!!.canvas.drawPath(field, paint)
            val diamond = android.graphics.Path().apply { moveTo(x(.5f), yy(.9f)); lineTo(x(.25f), yy(.63f)); lineTo(x(.5f), yy(.36f)); lineTo(x(.75f), yy(.63f)); close() }
            paint.color = Color.rgb(163, 85, 19); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; page!!.canvas.drawPath(diamond, paint)
            paint.color = green; page!!.canvas.drawLine(x(.02f), yy(.3f), x(.5f), yy(.9f), paint); page!!.canvas.drawLine(x(.5f), yy(.9f), x(.98f), yy(.3f), paint)
            val point = if (observation.precision == "point") observation.x?.let { px -> observation.y?.let { py -> px to py } } else Performances.regionPoint(observation.region)
            point?.let { (px, py) ->
                paint.color = green; paint.strokeWidth = 2f
                paint.pathEffect = if (observation.precision == "region") android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f) else null
                page!!.canvas.drawCircle(x(px.toFloat()), yy(py.toFloat()), if (observation.precision == "region") 10f else 6f, paint)
                paint.pathEffect = null
            }
            paint.style = Paint.Style.FILL
            val detailX = left + fieldWidth + 18
            draw("击球观察", detailX, top + 20, 11f, true, green)
            draw("球型：${ScoringOptions.ballTypes[observation.type] ?: "未知"}", detailX, top + 38, 10f)
            val position = when (observation.precision) { "point" -> "点选位置"; "region" -> ScoringOptions.regions[observation.region] ?: "大致区域"; else -> "没有看清" }
            draw("去向：$position", detailX, top + 55, 10f)
            observation.fielding?.let { fielding ->
                draw("守备路线：${fielding.route.joinToString(" → ").ifEmpty { "待补" }}", detailX, top + 72, 10f)
                fielding.errorPosition?.let { draw("失误责任：$it", detailX, top + 89, 10f) }
            }
            draw("实线圈为点选位置，虚线圈表示区域。", detailX, top + 110, 8f, color = secondary)
            y += 148
        }
        fun pageBreak() = nextPage()
        fun save(file: File) { page?.let(document::finishPage); page = null; file.outputStream().use(document::writeTo) }
        override fun close() { document.close() }
    }
    private fun number(n: Double?) = n?.let { String.format(Locale.US, "%.3f", it).let { formatted -> if (it < 1) formatted.removePrefix("0") else formatted } } ?: "—"
    private fun batted(b: BattingLine): String {
        val avg = number(if (b.atBats > 0) b.average else null)
        val obp = number(if (b.atBats + b.walks + b.hitByPitch + b.sacrifices > 0) b.onBasePercentage else null)
        val slg = number(if (b.atBats > 0) b.slugging else null)
        val ops = number(if (b.atBats > 0) b.onBasePercentage + b.slugging else null)
        return "PA ${b.plateAppearances} · AB ${b.atBats} · R ${b.runs} · H ${b.hits} · 2B ${b.doubles} · 3B ${b.triples} · HR ${b.homeRuns} · RBI ${b.runsBattedIn}\nBB ${b.walks} · HBP ${b.hitByPitch} · SO ${b.strikeouts} · SB ${b.stolenBases} · CS ${b.caughtStealing} · SAC ${b.sacrifices}\nAVG $avg · OBP $obp · SLG $slg · OPS $ops"
    }
    private fun pitched(p: PitchingLine): String {
        val era = if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", p.earnedRuns * 21.0 / p.outsRecorded) else "—"
        val whip = if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", (p.walks + p.hits) * 3.0 / p.outsRecorded) else "—"
        return "IP ${p.outsRecorded / 3}.${p.outsRecorded % 3} · BF ${p.battersFaced} · H ${p.hits} · R ${p.runs} · ER ${p.earnedRuns} · BB ${p.walks} · HBP ${p.hitByPitch}\nSO ${p.strikeouts} · WP ${p.wildPitches} · P ${p.pitches} · S ${p.strikes} · ERA $era · WHIP $whip"
    }
    private fun fielded(f: FieldingLine): String {
        val chances = f.putouts + f.assists + f.errors
        return "PO ${f.putouts} · A ${f.assists} · E ${f.errors} · DP ${f.doublePlays} · TC $chances · FPCT ${number(if (chances > 0) (f.putouts + f.assists).toDouble() / chances else null)}"
    }
    private const val legend = "比率按累计原始数据计算；ERA 按 7 局计算。IP 小数位为出局数，0.1 表示 1 人出局。— 表示无记录或无可用分母；DP 为球员参与双杀次数。"
    private fun gameDate(game: Game): String = MatchDates.localPlayedAt(game)
        ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "比赛时间未记录"
    private fun tables(w: Writer, players: List<SeasonPlayerStatistics>, gameCount: Int, coachOnly: Boolean = false) {
        fun batting(name: String, games: Int, b: BattingLine) = listOf(name, "$games", "${b.plateAppearances}", "${b.atBats}", "${b.runs}", "${b.hits}", "${b.doubles}", "${b.triples}", "${b.homeRuns}", "${b.runsBattedIn}", "${b.walks}", "${b.hitByPitch}", "${b.strikeouts}", "${b.stolenBases}", number(if (b.atBats > 0) b.average else null), number(if (b.atBats + b.walks + b.hitByPitch + b.sacrifices > 0) b.onBasePercentage else null), number(if (b.atBats > 0) b.slugging else null), number(if (b.atBats > 0) b.onBasePercentage + b.slugging else null))
        fun pitching(name: String, p: PitchingLine) = listOf(name, "${p.outsRecorded / 3}.${p.outsRecorded % 3}", "${p.battersFaced}", "${p.hits}", "${p.runs}", "${p.earnedRuns}", "${p.walks}", "${p.hitByPitch}", "${p.strikeouts}", "${p.wildPitches}", "${p.pitches}", "${p.strikes}", if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", p.earnedRuns * 21.0 / p.outsRecorded) else "—", if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", (p.walks + p.hits) * 3.0 / p.outsRecorded) else "—")
        fun fielding(name: String, f: FieldingLine): List<String> {
            val total = f.putouts + f.assists + f.errors
            return listOf(name, "${f.putouts}", "${f.assists}", "${f.errors}", "${f.doublePlays}", "$total", number(if (total > 0) (f.putouts + f.assists).toDouble() / total else null))
        }
        fun name(p: SeasonPlayerStatistics) = "#${p.player.number} ${p.player.name}" + if (p.currentRoster) "" else "（历史）"
        val batTotal = BattingLine(); val pitchTotal = PitchingLine(); val fieldTotal = FieldingLine()
        players.forEach { addBatting(batTotal, it.batting); addPitching(pitchTotal, it.pitching); addFielding(fieldTotal, it.fielding) }
        val bats = players.map { batting(name(it), it.games, it.batting) } + if (players.isNotEmpty()) listOf(batting("合计", gameCount, batTotal)) else emptyList()
        w.table("打击 · BATTING", listOf("球员", "G", "PA", "AB", "R", "H", "2B", "3B", "HR", "RBI", "BB", "HBP", "SO", "SB", "AVG", "OBP", "SLG", "OPS"), bats)
        if (coachOnly) w.line("教练投手模式：投手数据不适用。") else {
            val pitchers = players.filter { it.pitching != PitchingLine() }
            val rows = pitchers.map { pitching(name(it), it.pitching) } + if (pitchers.isNotEmpty()) listOf(pitching("合计", pitchTotal)) else emptyList()
            w.table("投手 · PITCHING", listOf("投手", "IP", "BF", "H", "R", "ER", "BB", "HBP", "SO", "WP", "P", "S", "ERA", "WHIP"), rows)
        }
        val fielders = players.filter { it.fielding != FieldingLine() }
        val defense = fielders.map { fielding(name(it), it.fielding) } + if (fielders.isNotEmpty()) listOf(fielding("合计", fieldTotal)) else emptyList()
        w.table("守备 · FIELDING", listOf("球员", "PO", "A", "E", "DP", "TC", "FPCT"), defense)
        val extras = players.filter { it.batting.caughtStealing + it.batting.sacrifices > 0 }.joinToString("；") { "${name(it)}：CS ${it.batting.caughtStealing}，SAC ${it.batting.sacrifices}" }
        if (extras.isNotEmpty()) w.line("其他进攻记录：$extras", 9f, muted = true)
    }
    private fun file(context: Context, kind: String): File = File(context.cacheDir, "reports").let {
        check(it.isDirectory || it.mkdirs())
        File(it, "BaseballMaster-$kind-${UUID.randomUUID()}.pdf")
    }
    fun team(context: Context, team: Team, year: Int, mode: GameMode?, summary: SeasonStatistics): File {
        val file = file(context, "team")
        val scope = ReportStatisticsProjection.scope(summary.games)
        Writer("球队年度报告", "${team.name} · $year 年").use { w ->
            w.line("${mode?.title ?: "全部比赛模式"} · ${summary.games.size} 场比赛 · ${summary.wins}胜${summary.losses}负${summary.ties}平")
            if (summary.incomplete > 0) w.line("含过程或责任待确认的比赛 · 统计可能不完整", bold = true)
            w.line("累计得分 ${summary.runs}  累计失分 ${summary.runsAllowed}  净胜分 ${summary.runs - summary.runsAllowed}")
            if (summary.pending > 0) w.line("数据待复核：${summary.pending} 条记录尚待确认。", bold = true)
            w.line("比赛结果 · GAME LOG", 15f, true)
            summary.games.forEach { game ->
                val home = game.home.id == team.id
                val opponent = if (home) game.away else game.home
                val ourScore = if (home) game.homeRuns else game.awayRuns
                val theirScore = if (home) game.awayRuns else game.homeRuns
                val result = if (ourScore > theirScore) "胜" else if (ourScore < theirScore) "负" else "平"
                w.line("${gameDate(game)} · ${if (home) "主场" else "客场"} · ${opponent.name} · $result $ourScore:$theirScore · H ${if (home) game.homeHits else game.awayHits} · E ${if (home) game.homeErrors else game.awayErrors}")
            }
            w.line("球队打击", 15f, true); w.line(batted(summary.batting))
            w.line("球队投手", 15f, true)
            if (scope.coachPitchOnly) w.line("教练投手模式：投手数据不适用。") else w.line(pitched(summary.pitching))
            w.line("球队守备", 15f, true); w.line(fielded(summary.fielding))
            val players = summary.players.sortedWith(compareBy({ it.player.number.toIntOrNull() ?: 0 }, { it.player.name }, { it.player.id }))
            tables(w, players, summary.games.size, scope.coachPitchOnly)
            w.line(legend, 8f, muted = true)
            w.save(file)
        }
        return file
    }
    fun player(context: Context, team: Team, year: Int, row: SeasonPlayerStatistics, games: List<Game> = emptyList()): File {
        val file = file(context, "player")
        val scope = ReportStatisticsProjection.scope(games)
        Writer("个人年度报告", "#${row.player.number} ${row.player.name}").use { w ->
            w.line("${team.name} · $year 年 · ${row.games} 场")
            if (scope.incompleteGames > 0) w.line("含过程或责任待确认的比赛 · 统计可能不完整", bold = true)
            if (scope.pendingRecords > 0) w.line("所选比赛有 ${scope.pendingRecords} 条待确认记录，统计可能在复核后变化。", bold = true)
            w.line("打击", 15f, true); w.line(batted(row.batting))
            w.line("投手", 15f, true)
            if (scope.coachPitchOnly) w.line("教练投手模式：投手数据不适用。") else w.line(pitched(row.pitching))
            w.line("守备", 15f, true); w.line(fielded(row.fielding))
            w.line("逐场比赛 · GAME LOG", 15f, true)
            games.forEach { game ->
                val id = row.player.id
                w.line("${gameDate(game)} · ${game.away.name} ${game.awayRuns}:${game.homeRuns} ${game.home.name}", 11f, true)
                game.batting[id]?.let { w.line("打击：${batted(it)}") } ?: w.line("打击：无记录", 10f, muted = true)
                if (game.rules.mode == GameMode.coachPitch) w.line("投手：教练投手模式不适用", 10f, muted = true)
                else game.pitching[id]?.let { w.line("投手：${pitched(it)}") } ?: w.line("投手：无记录", 10f, muted = true)
                game.fielding[id]?.let { w.line("守备：${fielded(it)}") } ?: w.line("守备：无记录", 10f, muted = true)
            }
            w.line(legend, 8f, muted = true)
            w.save(file)
        }
        return file
    }
    fun game(context: Context, game: Game, playByPlay: Boolean, textOnly: Boolean = false, appearanceID: String? = null): File {
        val file = file(context, if (playByPlay) "plays" else "game")
        val scope = ReportStatisticsProjection.scope(listOf(game))
        val kind = if (playByPlay) "逐球比赛记录" else "比赛报告"
        Writer(kind, "${game.away.name} vs ${game.home.name}", portrait = playByPlay && textOnly).use { w ->
            val date = gameDate(game)
            w.line("$date · ${game.rules.mode.title} · ${if (game.isFinal) "已结束" else "进行中"}")
            w.line("${game.away.name} ${game.awayRuns} : ${game.homeRuns} ${game.home.name}", 19f, true)
            w.line("安打 H（客 / 主）${game.awayHits} / ${game.homeHits}    失误 E（客 / 主）${game.awayErrors} / ${game.homeErrors}")
            if (game.statisticsIncomplete || game.incompletePrefix) w.line("过程或责任待确认 · 统计可能不完整", bold = true)
            if (scope.pendingRecords > 0) w.line("数据待复核：${scope.pendingRecords} 条记录尚待确认。", bold = true)
            if (playByPlay) {
                val all = AppearanceProjection.build(game)
                val appearances = if (appearanceID == null) all else all.filter { it.id == appearanceID }
                require(appearanceID == null || appearances.isNotEmpty()) { "所选打席不存在" }
                fun player(id: String?) = (game.away.players + game.home.players).firstOrNull { it.id == id }?.let { "#${it.number} ${it.name}" } ?: "未记录"
                fun label(a: ProjectedAppearance) = "${if (a.isTop) "客队" else "主队"}第 ${a.sequence} 打席 · 第${a.inning}局${if (a.isTop) "上" else "下"} · ${player(a.batterID)}"
                fun status(a: ProjectedAppearance) = when { a.events.any { it.incomplete } -> "待确认"; a.complete -> "打席已结束"; a.interrupted -> "打席中断"; else -> "打席未完成" }
                if (appearanceID == null && !textOnly) {
                    w.table("打席索引", listOf("打席 / 局次", "打者", "结果", "状态"), appearances.map { a ->
                        listOf("${if (a.isTop) "客队" else "主队"} ${a.sequence} · ${a.inning}局${if (a.isTop) "上" else "下"}", player(a.batterID), a.events.lastOrNull()?.text ?: "打席进行中", status(a))
                    }, listOf(1.8f, 2.3f, 6f, 1.8f))
                }
                appearances.forEach { appearance ->
                    if (!textOnly && appearanceID == null) w.pageBreak()
                    w.line(label(appearance), 15f, true)
                    w.line("${status(appearance)} · ${appearance.events.lastOrNull()?.text ?: "暂无记录"}", 10f, true)
                    if (textOnly) {
                        ReportStatisticsProjection.textAppearance(game, appearance).forEach { w.line(it, 11f) }
                    } else {
                        w.situationPair(appearance.events.firstOrNull()?.before, appearance.events.lastOrNull()?.after, game)
                        w.table("事件过程", listOf("序号 / 类型", "逐球与比赛记录", "投手", "事件后 B/S/O · 比分", "事件后垒况"), appearance.events.mapIndexed { index, event ->
                            val after = event.after
                            val bases = after?.bases?.mapIndexedNotNull { i, id -> id?.let { "${i + 1} 垒：${player(it)}" } }?.joinToString("\n").orEmpty().ifEmpty { "垒上无人" }
                            listOf("${index + 1} · ${event.notation}", ReportStatisticsProjection.eventDescription(event), if (game.rules.mode == GameMode.coachPitch) "不适用" else player(event.pitcherID), after?.let { "${it.balls}/${it.strikes}/${it.outs} · ${it.awayScore}:${it.homeScore}\n${it.inning}局${if (it.isTop) "上" else "下"}" } ?: "未记录", if (after == null) "未记录" else bases)
                        }, listOf(1.25f, 5.5f, 1.9f, 2.65f, 2.8f))
                        appearance.events.mapNotNull { it.observation }.forEach(w::battedBall)
                        w.line("B/S/O = 坏球 / 好球 / 出局。未保存的球路、球速与局面不作推测。", 8f, muted = true)
                    }
                }
            } else {
                val innings = maxOf(game.inning, game.awayRunsByInning.size, game.homeRunsByInning.size)
                for (start in 1..innings step 9) {
                    val numbers = (start..minOf(start + 8, innings)).toList()
                    w.line("逐局比分 · ${numbers.first()}–${numbers.last()} 局", 14f, true)
                    w.line("局次：" + numbers.joinToString("  "))
                    w.line("${game.away.name}：" + numbers.joinToString("  ") { game.awayRunsByInning.getOrNull(it - 1)?.toString() ?: "—" })
                    w.line("${game.home.name}：" + numbers.joinToString("  ") { game.homeRunsByInning.getOrNull(it - 1)?.toString() ?: "—" })
                }
                val scoring = game.entries.filter { e ->
                    val before = e.before; val after = e.after
                    before != null && after != null && (after.awayScore > before.awayScore || after.homeScore > before.homeScore)
                }
                if (scoring.isNotEmpty()) {
                    w.line("得分过程", 14f, true)
                    scoring.forEach { e ->
                        val after = requireNotNull(e.after)
                        w.line("${e.inning}局${if (e.isTop) "上" else "下"} · ${e.text} · ${after.awayScore}:${after.homeScore}")
                    }
                }
                for (team in listOf(game.away, game.home)) {
                    w.line(team.name + if (team.id == game.away.id) " · 客队" else " · 主队", 15f, true)
                    val players = ReportStatisticsProjection.gameParticipants(game, team.id)
                    tables(w, players, 1, game.rules.mode == GameMode.coachPitch)
                }
                w.line(legend, 8f, muted = true)
            }
            w.save(file)
        }
        return file
    }
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val image = file.extension.equals("png", ignoreCase = true)
        val intent = Intent(Intent.ACTION_SEND).setType(if (image) "image/png" else "application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, if (image) "分享图片" else "分享 PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
