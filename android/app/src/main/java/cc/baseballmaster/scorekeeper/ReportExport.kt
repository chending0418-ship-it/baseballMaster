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

    /** Coordinates and pagination follow iOS ReportPDFCanvas (UIKit uses top-left text bounds). */
    internal class Writer(
        val kind: String, val title: String, portrait: Boolean = false,
        subtitle: String = kind, generatedAt: Long = System.currentTimeMillis(),
    ) : AutoCloseable {
        private val width = if (portrait) 595 else 842
        private val height = if (portrait) 842 else 595
        private val bottom get() = height - 42f
        private val contentWidth get() = width - margin * 2
        private val document = PdfDocument()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 0f
        private val generatedText = java.time.Instant.ofEpochMilli(generatedAt).atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        init { nextPage(); line(title, 25f, true); line(subtitle, 10f, muted = true); y += 8 }
        private fun font(size: Float, bold: Boolean) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.style = Paint.Style.FILL
        }
        private fun draw(text: String, x: Float, top: Float, size: Float, bold: Boolean = false,
                         color: Int = navy, limit: Float = contentWidth, align: Paint.Align = Paint.Align.LEFT) {
            font(size, bold); paint.color = color; paint.textAlign = align
            val canvas = page!!.canvas
            canvas.save(); canvas.clipRect(x, top, x + limit, top + size * 1.5f)
            val anchor = x + when (align) { Paint.Align.CENTER -> limit / 2; Paint.Align.RIGHT -> limit; else -> 0f }
            canvas.drawText(text, anchor, top - paint.fontMetrics.ascent, paint)
            canvas.restore(); paint.textAlign = Paint.Align.LEFT
        }
        private fun wrap(text: String, limit: Float = contentWidth, size: Float = 10f, bold: Boolean = false): List<String> {
            font(size, bold)
            return text.split('\n').flatMap { raw ->
                if (raw.isEmpty()) listOf("") else buildList {
                    var rest = raw
                    while (rest.isNotEmpty()) {
                        var n = paint.breakText(rest, true, limit.coerceAtLeast(1f), null).coerceAtLeast(1)
                        if (n < rest.length && Character.isHighSurrogate(rest[n - 1])) n = if (n > 1) n - 1 else 2
                        add(rest.substring(0, n)); rest = rest.substring(n)
                    }
                }
            }
        }
        private fun nextPage() {
            page?.let(document::finishPage); pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            page!!.canvas.drawColor(Color.WHITE)
            draw("BASEBALLMASTER", margin, 20f, 11f, true, limit = 250f)
            draw(kind, width / 2f, 20f, 10f, true, green, width / 2f - margin, Paint.Align.RIGHT)
            paint.color = green; page!!.canvas.drawRect(margin, 42f, width - margin, 44f, paint)
            if (pageNumber > 1) draw(title.take(72), margin, 49f, 8f, color = secondary)
            draw("BaseballMaster  |  本地记录导出  |  $generatedText", margin, height - 24f, 8f,
                color = secondary, limit = contentWidth - 100)
            draw("第 $pageNumber 页", width - margin - 90, height - 24f, 8f,
                color = secondary, limit = 90f, align = Paint.Align.RIGHT)
            y = if (pageNumber == 1) 62f else 70f
        }
        private fun ensure(space: Float) { if (y + space > bottom) nextPage() }
        fun line(text: String, size: Float = 10f, bold: Boolean = false, muted: Boolean = false) {
            val leading = kotlin.math.ceil(size * 1.5f)
            wrap(text, size = size, bold = bold).forEach {
                ensure(leading); draw(it, margin, y, size, bold, if (muted) secondary else navy); y += leading
            }
            y += 7
        }
        fun textRecord(title: String, body: String) {
            val lines = wrap(body, size = 11f)
            val headingHeight = wrap(title, size = 11f, bold = true).size * 17f + 7
            val total = headingHeight + lines.size * 17f + 14
            ensure(if (total <= (bottom - 70) / 2) total else headingHeight + 34)
            line(title, 11f, true)
            lines.forEach {
                if (y + 17 > bottom) { nextPage(); line("$title (续)", 11f, true) }
                draw(it, margin, y, 11f); y += 17
            }
            y += 14
        }
        fun metrics(values: List<Pair<String, String>>) {
            val columns = minOf(6, maxOf(1, values.size)); val gap = 10f
            val cardWidth = (contentWidth - (columns - 1) * gap) / columns
            values.chunked(columns).forEach { row ->
                ensure(68f)
                row.forEachIndexed { index, (label, value) ->
                    val x = margin + index * (cardWidth + gap)
                    paint.color = Color.rgb(240, 245, 242); page!!.canvas.drawRect(x, y, x + cardWidth, y + 58, paint)
                    font(21f, true)
                    val size = minOf(21f, 21f * (cardWidth - 20) / maxOf(1f, paint.measureText(value)))
                    draw(value, x + 10, y + 8, size, true, limit = cardWidth - 20)
                    draw(label, x + 10, y + 37, 9f, color = secondary, limit = cardWidth - 20)
                }
                y += 68
            }
        }
        fun table(title: String, headers: List<String>, rows: List<List<String>>, weights: List<Float>? = null, compact: Boolean = false) {
            require(headers.isNotEmpty() && rows.all { it.size == headers.size })
            val columnWeights = weights ?: headers.indices.map { if (it == 0) 3f else 1f }
            require(columnWeights.size == headers.size && columnWeights.all { it > 0 })
            val widths = columnWeights.map { contentWidth * it / columnWeights.sum() }
            val leading = 12f; val padding = if (compact) 3f else 5f; val headerHeight = if (compact) 22f else 24f
            fun header(continued: Boolean) {
                val heading = title + if (continued) " (续)" else ""
                val headingHeight = wrap(heading, size = 12f, bold = true).size * 18f + 7
                ensure(minOf(bottom - 70, headingHeight + headerHeight + leading + padding * 2))
                line(heading, 12f, true); ensure(headerHeight + leading + padding * 2)
                paint.color = navy; page!!.canvas.drawRect(margin, y, width - margin, y + headerHeight, paint)
                var x = margin
                headers.forEachIndexed { i, label ->
                    draw(label, x + 5, y + 6, 8f, true, Color.WHITE, widths[i] - 10,
                        if (i == 0) Paint.Align.LEFT else Paint.Align.CENTER); x += widths[i]
                }
                y += headerHeight
            }
            header(false)
            if (rows.isEmpty()) { y += 6; line(emptyTableMessage, 9f, muted = true); return }
            rows.forEachIndexed { rowIndex, row ->
                val total = row.firstOrNull() == "合计"
                val cells = row.mapIndexed { i, value -> wrap(value, widths[i] - 10, 8.5f, total) }
                val lineCount = cells.maxOf { it.size }
                var keep = lineCount * leading + padding * 2
                rows.getOrNull(rowIndex + 1)?.takeIf { it.firstOrNull() == "合计" }?.let { next ->
                    keep += next.mapIndexed { i, value -> wrap(value, widths[i] - 10, 8.5f, true).size }.max() * leading + padding * 2
                }
                if (y + keep > bottom && keep < bottom - 120) { nextPage(); header(true) }
                var offset = 0
                while (offset < lineCount) {
                    if (y + leading + padding * 2 > bottom) { nextPage(); header(true) }
                    val count = minOf(lineCount - offset, ((bottom - y - padding * 2) / leading).toInt().coerceAtLeast(1))
                    val rowHeight = count * leading + padding * 2
                    paint.color = if (total) Color.rgb(224, 240, 232) else if (rowIndex % 2 == 0) Color.rgb(246, 246, 246) else Color.WHITE
                    page!!.canvas.drawRect(margin, y, width - margin, y + rowHeight, paint)
                    var x = margin
                    cells.forEachIndexed { i, cell ->
                        for (n in offset until minOf(offset + count, cell.size)) draw(cell[n], x + 5,
                            y + padding + (n - offset) * leading, 8.5f, total, limit = widths[i] - 10,
                            align = if (i == 0) Paint.Align.LEFT else Paint.Align.CENTER)
                        x += widths[i]
                    }
                    y += rowHeight; offset += count
                }
            }
            y += if (compact) 10 else 13
        }
        fun situationPair(before: Situation?, after: Situation?, game: Game) {
            ensure(138f)
            val cardWidth = (contentWidth - 14) / 2
            val overflow = mutableListOf<String>()
            listOf("打席前" to before, "打席后 / 后续局面" to after).forEachIndexed { index, (label, state) ->
                val x = margin + index * (cardWidth + 14)
                paint.color = Color.rgb(240, 245, 242); page!!.canvas.drawRect(x, y, x + cardWidth, y + 125, paint)
                draw(label, x + 12, y + 8, 10f, true, green, cardWidth - 24)
                if (state == null) draw("此局面未记录", x + 12, y + 48, 12f, color = secondary, limit = cardWidth - 24)
                else {
                    val cx = x + 66; val cy = y + 77
                    val diamond = android.graphics.Path().apply { moveTo(cx, cy + 30); lineTo(cx + 32, cy); lineTo(cx, cy - 30); lineTo(cx - 32, cy); close() }
                    paint.color = green; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
                    page!!.canvas.drawPath(diamond, paint); paint.style = Paint.Style.FILL
                    listOf(cx + 32 to cy, cx to cy - 30, cx - 32 to cy).forEachIndexed { base, (px, py) ->
                        val occupied = state.bases.getOrNull(base) != null
                        paint.color = if (occupied) green else Color.WHITE; page!!.canvas.drawCircle(px, py, 6f, paint)
                        paint.color = green; paint.style = Paint.Style.STROKE; page!!.canvas.drawCircle(px, py, 6f, paint); paint.style = Paint.Style.FILL
                        draw("${base + 1}", px - 6, py - 5, 7f, true, if (occupied) Color.WHITE else green, 12f, Paint.Align.CENTER)
                    }
                    val tx = x + 120; val tw = cardWidth - 132
                    draw("${state.inning}局${if (state.isTop) "上" else "下"}  ·  比分 ${state.awayScore} : ${state.homeScore}", tx, y + 31, 10f, true, limit = tw)
                    draw("B ${state.balls}   S ${state.strikes}   O ${state.outs}", tx, y + 49, 11f, true, limit = tw)
                    state.bases.forEachIndexed { base, id ->
                        val name = (game.home.players + game.away.players).firstOrNull { it.id == id }?.name ?: if (id == null) "空" else "历史球员"
                        val text = "${base + 1} 垒：$name"
                        val lines = wrap(text, tw, 8f)
                        draw(if (lines.size > 1) "${base + 1} 垒：姓名见下方" else text, tx, y + 69 + base * 15, 8f, limit = tw)
                        if (lines.size > 1) overflow += "$label · $text"
                    }
                }
            }
            y += 138
            overflow.forEach { line(it, 9f) }
        }
        fun pageBreak() = nextPage()
        fun save(file: File) { page?.let(document::finishPage); page = null; file.outputStream().use(document::writeTo) }
        override fun close() { document.close() }
    }
    private fun number(n: Double?) = n?.let { String.format(Locale.US, "%.3f", it).let { formatted -> if (it < 1) formatted.removePrefix("0") else formatted } } ?: "-"
    private fun playerNumbers(p: Player) = p.jerseyNumbers.ifEmpty { listOf(p.number) }.joinToString(" / ") { "#$it" }
    private fun playerName(p: Player) = "${playerNumbers(p)} ${p.name}"
    private fun gameDate(game: Game): String = MatchDates.localPlayedAt(game)
        ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "比赛时间未记录"
    private fun tables(w: Writer, players: List<SeasonPlayerStatistics>, gameCount: Int, coachOnly: Boolean = false,
                       titlePrefix: String = "", compact: Boolean = false, includeTotal: Boolean = true,
                       categories: Set<String> = setOf("batting", "pitching", "fielding")) {
        fun batting(name: String, games: Int, b: BattingLine) = listOf(name, "$games", "${b.plateAppearances}", "${b.atBats}", "${b.runs}", "${b.hits}", "${b.doubles}", "${b.triples}", "${b.homeRuns}", "${b.runsBattedIn}", "${b.walks}", "${b.hitByPitch}", "${b.strikeouts}", "${b.stolenBases}", number(if (b.atBats > 0) b.average else null), number(if (b.atBats + b.walks + b.hitByPitch + b.sacrifices > 0) b.onBasePercentage else null), number(if (b.atBats > 0) b.slugging else null), number(if (b.atBats > 0) b.onBasePercentage + b.slugging else null))
        fun pitching(name: String, p: PitchingLine) = listOf(name, "${p.outsRecorded / 3}.${p.outsRecorded % 3}", "${p.battersFaced}", "${p.hits}", "${p.runs}", "${p.earnedRuns}", "${p.walks}", "${p.hitByPitch}", "${p.strikeouts}", "${p.wildPitches}", "${p.pitches}", "${p.strikes}", if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", p.earnedRuns * 21.0 / p.outsRecorded) else "-", if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", (p.walks + p.hits) * 3.0 / p.outsRecorded) else "-")
        fun fielding(name: String, f: FieldingLine): List<String> {
            val total = f.putouts + f.assists + f.errors
            return listOf(name, "${f.putouts}", "${f.assists}", "${f.errors}", "${f.doublePlays}", "$total", number(if (total > 0) (f.putouts + f.assists).toDouble() / total else null))
        }
        fun name(p: SeasonPlayerStatistics) = playerName(p.player) + if (p.currentRoster) "" else "（历史）"
        val batTotal = BattingLine(); val pitchTotal = PitchingLine(); val fieldTotal = FieldingLine()
        players.forEach { addBatting(batTotal, it.batting); addPitching(pitchTotal, it.pitching); addFielding(fieldTotal, it.fielding) }
        val bats = players.map { batting(name(it), it.games, it.batting) } + if (includeTotal && players.isNotEmpty()) listOf(batting("合计", gameCount, batTotal)) else emptyList()
        if ("batting" in categories) {
            w.table("${titlePrefix.ifEmpty { "打击" }} · BATTING", listOf("球员", "G", "PA", "AB", "R", "H", "2B", "3B", "HR", "RBI", "BB", "HBP", "SO", "SB", "AVG", "OBP", "SLG", "OPS"), bats,
                listOf(4f) + List(17) { 1f }, compact)
            val extras = players.filter { it.batting.caughtStealing + it.batting.sacrifices > 0 }.joinToString("；") { "${name(it)}：CS ${it.batting.caughtStealing}，SAC ${it.batting.sacrifices}" }
            if (extras.isNotEmpty()) w.line("其他进攻记录：$extras", 9f, muted = true)
        }
        if ("pitching" in categories) {
            if (coachOnly) w.line("教练投手模式：投手数据不适用") else {
                w.line("ERA 使用 7 局口径，按实际投手出局数计算；时间赛不按时长折算。", 9f, muted = true)
                val pitchers = players.filter { it.pitching != PitchingLine() }
                val rows = pitchers.map { pitching(name(it), it.pitching) } + if (includeTotal && pitchers.isNotEmpty()) listOf(pitching("合计", pitchTotal)) else emptyList()
                w.table("${titlePrefix.ifEmpty { "投手" }} · PITCHING", listOf("投手", "IP", "BF", "H", "R", "ER", "BB", "HBP", "SO", "WP", "P", "S", "ERA", "WHIP"), rows,
                    listOf(4f) + List(13) { 1f }, compact)
            }
        }
        if ("fielding" in categories) {
            val fielders = players.filter { it.fielding != FieldingLine() }
            val defense = fielders.map { fielding(name(it), it.fielding) } + if (includeTotal && fielders.isNotEmpty()) listOf(fielding("合计", fieldTotal)) else emptyList()
            w.table("${titlePrefix.ifEmpty { "守备" }} · FIELDING", listOf("球员", "PO", "A", "E", "DP", "TC", "FPCT"), defense,
                listOf(4f, 1f, 1f, 1f, 1f, 1f, 1.5f), compact)
        }
    }

    private fun file(context: Context, kind: String): File = File(context.cacheDir, "reports").let {
        check(it.isDirectory || it.mkdirs())
        File(it, "BaseballMaster-$kind-${UUID.randomUUID()}.pdf")
    }
    fun team(context: Context, team: Team, year: Int, mode: GameMode?, summary: SeasonStatistics, isFiltered: Boolean = false): File {
        val file = file(context, "team")
        val scope = ReportStatisticsProjection.scope(summary.games)
        Writer("ANNUAL REPORT / 球队年度报告", team.name,
            subtitle = "$year · ${if (isFiltered) "所选比赛统计" else "完整球队年度数据"} · ${summary.games.size} 场已结束比赛").use { w ->
            if (summary.incomplete > 0) w.line("统计范围包含过程或责任待确认的比赛，统计可能不完整。", bold = true)
            w.metrics(listOf("比赛" to "${summary.games.size}", "胜 / 负 / 平" to "${summary.wins} / ${summary.losses} / ${summary.ties}",
                "得分" to "${summary.runs}", "失分" to "${summary.runsAllowed}",
                "打率 AVG" to number(if (summary.batting.atBats > 0) summary.batting.average else null),
                "防御率 ERA" to eraText(summary.pitching)))
            w.line("范围：本球队、本年度${if (isFiltered) "所选" else "全部"}已结束比赛与全部球员；不受页面搜索和排序影响。观赛、练习、未来安排及进行中比赛不计入。历史参赛球员仍保留贡献；旧版独立个人记录不计入球队汇总。", 9f, muted = true)
            mode?.let { w.line("比赛模式：${it.title}", 9f, muted = true) }
            if (summary.pending > 0) w.line("数据待复核：当前范围内有 ${summary.pending} 条待确认记录，数据可能在复核后变化。", bold = true)
            w.table("比赛结果 · GAME LOG", listOf("日期", "对手", "主客", "结果", "比分（本队:对手）", "H", "E"), summary.games.map { g ->
                val home = g.home.id == team.id; val our = if (home) g.homeRuns else g.awayRuns; val their = if (home) g.awayRuns else g.homeRuns
                listOf(gameDate(g).substringBefore(' '), (if (home) g.away else g.home).name, if (home) "主场" else "客场",
                    if (our == their) "平" else if (our > their) "胜" else "负", "$our : $their",
                    "${if (home) g.homeHits else g.awayHits}", "${if (home) g.homeErrors else g.awayErrors}")
            }, listOf(1.8f, 4f, 1f, 1f, 2.8f, 1f, 1f))
            val players = summary.players.sortedWith(compareByDescending<SeasonPlayerStatistics> { it.batting.plateAppearances }.thenBy { it.player.number.toIntOrNull() ?: 0 }.thenBy { it.player.name }.thenBy { it.player.id })
            w.pageBreak()
            tables(w, players, summary.games.size, scope.coachPitchOnly, titlePrefix = "年度打击", categories = setOf("batting"))
            w.pageBreak()
            tables(w, players, summary.games.size, scope.coachPitchOnly, titlePrefix = "年度投手", categories = setOf("pitching"))
            tables(w, players, summary.games.size, scope.coachPitchOnly, titlePrefix = "年度守备", categories = setOf("fielding"))
            legend(w); w.save(file)
        }
        return file
    }
    private fun eraText(p: PitchingLine) = if (p.outsRecorded > 0) String.format(Locale.US, "%.2f", p.earnedRuns * 21.0 / p.outsRecorded) else "-"
    private fun innings(p: PitchingLine) = "${p.outsRecorded / 3}.${p.outsRecorded % 3}"
    fun player(context: Context, team: Team, year: Int, row: SeasonPlayerStatistics, games: List<Game> = emptyList(), eligibleGameCount: Int = row.games): File {
        val file = file(context, "player")
        val scope = ReportStatisticsProjection.scope(games)
        val p = row.player
        Writer("PLAYER REPORT / 球员个人报告", p.name,
            subtitle = "${p.englishName} · ${playerNumbers(p)} · ${team.name} · $year\n范围：已选 ${row.games} / $eligibleGameCount 场比赛").use { w ->
            if (scope.incompleteGames > 0) w.line("统计范围包含过程或责任待确认的比赛，统计可能不完整。", bold = true)
            w.metrics(listOf("已选比赛" to "${row.games}", "打率 AVG" to number(if (row.batting.atBats > 0) row.batting.average else null),
                "安打 H" to "${row.batting.hits}", "打点 RBI" to "${row.batting.runsBattedIn}", "投球局数 IP" to innings(row.pitching),
                "刺杀 + 助杀" to "${row.fielding.putouts + row.fielding.assists}"))
            w.line("仅汇总当前选择的比赛，包含打击、投手和守备三个分类。", 9f, muted = true)
            if (scope.pendingRecords > 0) w.line("所选比赛有 ${scope.pendingRecords} 条待确认记录，统计可能在复核后变化。", bold = true)
            val rows = if (row.games > 0) listOf(row) else emptyList()
            tables(w, rows, row.games, scope.coachPitchOnly, titlePrefix = "所选范围打击", includeTotal = false, categories = setOf("batting"))
            tables(w, rows, row.games, scope.coachPitchOnly, titlePrefix = "所选范围投手", includeTotal = false, categories = setOf("pitching"))
            tables(w, rows, row.games, scope.coachPitchOnly, titlePrefix = "所选范围守备", includeTotal = false, categories = setOf("fielding"))
            fun label(g: Game): String {
                val home = g.home.id == team.id; val our = if (home) g.homeRuns else g.awayRuns; val their = if (home) g.awayRuns else g.homeRuns
                return "${gameDate(g).substringBefore(' ')}\n${(if (home) g.away else g.home).name}\n${if (our == their) "平" else if (our > their) "胜" else "负"} $our : $their"
            }
            w.pageBreak()
            w.table("逐场打击 · BATTING GAME LOG", listOf("日期 / 对手 / 结果", "PA", "AB", "R", "H", "HR", "RBI", "BB", "SO", "AVG"), games.map { g ->
                val b = g.batting[p.id] ?: BattingLine()
                listOf(label(g), "${b.plateAppearances}", "${b.atBats}", "${b.runs}", "${b.hits}", "${b.homeRuns}", "${b.runsBattedIn}", "${b.walks}", "${b.strikeouts}", number(if (b.atBats > 0) b.average else null))
            }, listOf(5f) + List(9) { 1f })
            w.table("逐场投手 · PITCHING GAME LOG", listOf("日期 / 对手 / 结果", "IP", "H", "R", "ER", "BB", "SO", "P", "ERA"), games.map { g ->
                val pitch = g.pitching[p.id]
                listOf(label(g)) + if (g.rules.mode == GameMode.coachPitch) List(8) { "不适用" } else if (pitch == null) List(8) { "-" } else
                    listOf(innings(pitch), "${pitch.hits}", "${pitch.runs}", "${pitch.earnedRuns}", "${pitch.walks}", "${pitch.strikeouts}", "${pitch.pitches}", eraText(pitch))
            }, listOf(5f) + List(8) { 1f })
            w.table("逐场守备 · FIELDING GAME LOG", listOf("日期 / 对手 / 结果", "PO", "A", "E", "DP", "FPCT"), games.map { g ->
                val f = g.fielding[p.id]; val total = f?.let { it.putouts + it.assists + it.errors } ?: 0
                listOf(label(g)) + if (f == null) List(5) { "-" } else listOf("${f.putouts}", "${f.assists}", "${f.errors}", "${f.doublePlays}",
                    number(if (total > 0) (f.putouts + f.assists).toDouble() / total else null))
            }, listOf(5f) + List(5) { 1f })
            legend(w); w.save(file)
        }
        return file
    }
    private fun rulesText(r: Rules): String {
        val format = if (r.timed) "时间赛 ${r.timeLimitMinutes?.let { "$it 分钟" } ?: "时长待设置"}" else "${r.innings} 局"
        return "${r.mode.title} · $format" + (if (r.timed) r.inningsLimit?.let { " · 局数上限 $it" }.orEmpty() else "") +
            " · ${r.fielders} 人守备" + if (r.mode == GameMode.slowPitch)
                " · 初始 ${r.initialStrikes} 好 ${r.initialBalls} 坏 · ${if (r.oneExtraFoul) "再允许一次界外，第二次出局" else "两好后界外直接出局"}" else ""
    }
    private fun legend(w: Writer) {
        w.line("统计口径与缩写", 11f, true)
        w.line("比率按累计原始数据计算；ERA 按 7 局计算。IP 小数位为出局数，0.1 表示 1 人出局。\"-\" 表示未进行、无记录或无可用分母。DP 为球员参与双杀次数，合计不等同于球队双杀次数。", 8f, muted = true)
        w.line("G 场次 · PA 打席 · AB 打数 · R 得分/失分 · H 安打 · 2B/3B/HR 二垒/三垒/本垒打 · RBI 打点 · BB 保送 · HBP 触身球 · SO 三振 · SB/CS 盗垒/盗垒失败 · SAC 牺牲打 · AVG/OBP/SLG/OPS 打率/上垒率/长打率/攻击指数 · BF 面对打者 · ER 自责分 · WP 暴投 · P/S 用球/好球 · PO/A/E 刺杀/助杀/失误 · TC 守备机会 · FPCT 守备率", 8f, muted = true)
    }
    internal fun eventCategory(e: PitchEntry, command: Command? = null): String = when (e.kind) {
        "pitch" -> "投球"
        "play", "intentionalWalk" -> "击球"
        "hitByPitch" -> if (e.notation == "HBP-CONTINUE") "投球" else "击球"
        "foulBunt", "droppedThirdStrike" -> "出局"
        "runner" -> if (command?.runnerKind in setOf("caughtStealing", "pickoff", "tagOut", "forceOut", "appealOut", "leftEarly", "missedBase", "outOfBasePath", "passedRunner", "runnerInterference", "runnerHitByBall", "coachAssistance")) "出局" else "跑垒"
        "violation", "ruling" -> "判罚"
        "illegal" -> when { command?.outcome != null -> "击球"; command?.pitch != null -> "投球"; else -> "判罚" }
        "clock", "clockStart", "clockPause", "clockAdjust" -> "计时"
        "lineup", "pitcher", "batter", "position", "fielder", "pinchRunner", "doubleSwitch" -> "换人"
        "tiebreak", "skipTB" -> "延长／TB"
        "situation", "correction", "observe", "review" -> "修正"
        else -> "比赛"
    }
    fun game(context: Context, game: Game, playByPlay: Boolean, textOnly: Boolean = false, appearanceID: String? = null): File {
        val file = file(context, if (playByPlay) "plays" else "game")
        if (playByPlay) writeAppearances(file, game, textOnly, appearanceID) else writeBoxScore(file, game)
        return file
    }
    private fun writeAppearances(file: File, game: Game, textOnly: Boolean, appearanceID: String?) {
        val all = AppearanceProjection.build(game)
        val commandsByID = game.journal.associate { it.id to it.command }
        val selected = if (appearanceID == null) all else all.filter { it.id == appearanceID }
        require(appearanceID == null || selected.isNotEmpty()) { "所选打席不存在" }
        fun sequence(a: ProjectedAppearance) = "${if (a.isTop) "客队" else "主队"}第 ${a.sequence} 打席"
        fun inning(a: ProjectedAppearance) = "${a.inning}局${if (a.isTop) "上" else "下"}"
        fun player(id: String?) = if (id == null) "未记录" else (game.away.players + game.home.players)
            .firstOrNull { it.id == id }?.let { playerName(it) } ?: "历史球员"
        fun status(a: ProjectedAppearance) = when {
            a.events.any { it.incomplete } -> "待确认"; a.complete -> "打席已结束"
            a.id == all.lastOrNull()?.id -> "打席未完成"; else -> "打者更换或打席中断"
        }
        fun result(a: ProjectedAppearance) = a.events.lastOrNull { it.outcome != null }?.text
            ?: a.events.lastOrNull()?.text ?: "打席进行中"
        val title = if (textOnly) { if (appearanceID == null) "逐打席文字简版" else "单打席文字简版" }
            else if (appearanceID == null) "逐打席比赛速报" else "${sequence(selected.first())}速报"
        val kind = if (textOnly) "逐打席 / 文字简版" else "PLAY BY PLAY / 逐打席速报"
        val subtitle = "${game.away.name} vs ${game.home.name} · ${gameDate(game)} · ${if (game.isFinal) "已结束" else "进行中"}"
        Writer(kind, title, portrait = textOnly, subtitle = subtitle +
            if (!textOnly && game.rules.mode == GameMode.slowPitch) " · ${rulesText(game.rules)}" else "").use { w ->
            if (textOnly && game.rules.mode == GameMode.slowPitch) w.line(rulesText(game.rules), muted = true)
            if (game.statisticsIncomplete || game.incompletePrefix) w.line("过程或责任待确认 · 统计可能不完整", bold = true)
            if (selected.isEmpty()) {
                w.line(if (textOnly) "暂无可导出的打席文字记录。" else "暂无可导出的逐打席记录", if (textOnly) 11f else 16f, !textOnly)
            } else if (textOnly) {
                selected.forEach { a ->
                    val suffix = if (a.events.any { it.incomplete } || !a.complete) " · ${status(a)}" else ""
                    w.textRecord("${inning(a)} · ${sequence(a)} · ${player(a.batterID)}$suffix",
                        ReportStatisticsProjection.textAppearance(game, a).joinToString("；").ifEmpty { "暂无文字描述。" })
                }
            } else {
                if (appearanceID == null) {
                    w.metrics(listOf("打席记录" to "${selected.size}", "客队得分" to "${game.awayRuns}", "主队得分" to "${game.homeRuns}",
                        "待确认打席" to "${selected.count { a -> a.events.any { it.incomplete } }}"))
                    w.line("按比赛时间顺序展示，主客队的打席分别从 1 累计，跨局继续。每个打席包含前后局面、逐球及跑垒记录；换人、计时、TB 和修正保留在发生位置。事件序号不等同于投球数。", muted = true)
                    w.table("打席索引", listOf("打席 / 局次", "打者", "结果", "状态"), selected.map { a ->
                        listOf("${if (a.isTop) "客队" else "主队"} ${a.sequence} · ${inning(a)}", player(a.batterID), result(a), status(a))
                    }, listOf(1.8f, 2.3f, 6f, 1.8f))
                }
                selected.forEachIndexed { index, a ->
                    if (appearanceID == null || index > 0) w.pageBreak()
                    val label = "${sequence(a)} · ${inning(a)} · ${player(a.batterID)}"
                    w.line(label, 17f, true); w.line("${status(a)}  |  结果：${result(a)}", 10f, true)
                    val after = a.events.lastOrNull()?.let { if (it.id == game.entries.lastOrNull()?.id && it.after != null) game.situation() else it.after }
                    w.situationPair(a.events.firstOrNull()?.before, after, game)
                    w.table("$label · 事件过程", listOf("序号 / 类型", "逐球与比赛记录", "投手", "事件后 B/S/O · 比分（客:主）", "事件后垒况"), a.events.mapIndexed { i, e ->
                        val state = if (e.id == game.entries.lastOrNull()?.id && e.after != null) game.situation() else e.after
                        val bases = state?.bases?.mapIndexedNotNull { b, id -> id?.let { "${b + 1} 垒：${player(it)}" } }?.joinToString("\n")?.ifEmpty { "垒上无人" } ?: "未记录"
                        listOf("${i + 1} · ${eventCategory(e, commandsByID[e.id])}", ReportStatisticsProjection.eventDescription(e),
                            if (game.rules.mode == GameMode.coachPitch) "不适用" else player(e.before?.pitcherID ?: e.pitcherID),
                            state?.let { "${it.balls}/${it.strikes}/${it.outs} · ${it.awayScore}:${it.homeScore}\n${it.inning}局${if (it.isTop) "上" else "下"}" } ?: "未记录", bases)
                    }, listOf(1.25f, 5.5f, 1.9f, 2.65f, 2.8f))
                    // The iOS detailed report is a situation/event report, without an extra contact-field block.
                    w.line("B/S/O = 坏球 / 好球 / 出局。半局结束后的局面显示下一半局；自动保送、三振结果独立列出，不重复计为投球。未保存的球路、球速与局面不作推测。", 8f, muted = true)
                }
            }
            w.save(file)
        }
    }
    private fun writeBoxScore(file: File, game: Game) {
        val r = game.rules
        val subtitle = "比赛时间：${gameDate(game)} · ${if (game.isFinal) "已结束" else "进行中 / 非最终数据"} · " +
            if (r.mode == GameMode.slowPitch) rulesText(r) else "规定 ${r.innings} 局"
        Writer("BOX SCORE / 单场比赛战报", "${game.away.name} vs ${game.home.name}", subtitle = subtitle).use { w ->
            if (game.statisticsIncomplete || game.incompletePrefix) w.line("过程或责任待确认 · 统计可能不完整", bold = true)
            w.metrics(listOf("客队 · ${game.away.shortName.take(14)}" to "${game.awayRuns}", "主队 · ${game.home.shortName.take(14)}" to "${game.homeRuns}",
                "安打 H（客 / 主）" to "${game.awayHits} / ${game.homeHits}", "失误 E（客 / 主）" to "${game.awayErrors} / ${game.homeErrors}"))
            val reasons = mapOf("regulation" to "规定局数完成", "walkOff" to "再见分结束", "timeLimit" to "时间限制结束", "mercyRule" to "提前结束规则", "forfeit" to "弃权结束", "weather" to "天气或场地原因", "suspended" to "比赛中断", "scorerDecision" to "记录员结束记录")
            w.line(if (game.isFinal) "结束原因：${reasons[game.endReason] ?: "比赛结束"}" else "比赛尚未结束，本报告为导出时的比赛快照。", bold = true)
            val pending = ReportStatisticsProjection.scope(listOf(game)).pendingRecords
            if (pending > 0) w.line("数据待复核：$pending 条记录尚待确认。", bold = true)
            val scored = maxOf(game.awayRunsByInning.indexOfLast { it > 0 } + 1, game.homeRunsByInning.indexOfLast { it > 0 } + 1)
            val innings = if (r.timed) maxOf(1, game.inning, scored) else maxOf(1, game.inning, game.awayRunsByInning.size, game.homeRunsByInning.size)
            for (start in 1..innings step 12) {
                val numbers = (start..minOf(start + 11, innings)).toList()
                w.table("逐局比分 · LINE SCORE（$start-${numbers.last()} 局）", listOf("球队") + numbers.map { "$it" } + listOf("R", "H", "E"),
                    listOf(false, true).map { home ->
                        val team = if (home) game.home else game.away
                        val values = if (home) game.homeRunsByInning else game.awayRunsByInning
                        listOf(team.name) + numbers.map { inning ->
                            val value = values.getOrNull(inning - 1) ?: 0
                            if (value == 0 && (inning > game.inning || home && inning == game.inning && game.isTop)) "-" else "$value"
                        } + listOf("${if (home) game.homeRuns else game.awayRuns}", "${if (home) game.homeHits else game.awayHits}", "${if (home) game.homeErrors else game.awayErrors}")
                    }, listOf(4f) + List(numbers.size + 3) { 1f })
            }
            val scoring = game.entries.filter { event ->
                val before = event.before; val after = event.after
                before != null && after != null && (after.awayScore > before.awayScore || after.homeScore > before.homeScore)
            }
            w.table("得分过程 · SCORING SUMMARY", listOf("局次", "比赛记录", "比分（客:主）"), scoring.map {
                listOf("${it.inning} 局${if (it.isTop) "上" else "下"}", ReportStatisticsProjection.eventDescription(it), "${it.after!!.awayScore} : ${it.after!!.homeScore}")
            }, listOf(1.2f, 7f, 1.8f))
            legend(w)
            listOf(game.away, game.home).forEach { team ->
                w.pageBreak()
                val label = team.name + if (team.id == game.away.id) " · 客队" else " · 主队"
                if (r.mode == GameMode.slowPitch) {
                    val home = team.id == game.home.id
                    val order = (if (home) game.homeOrder else game.awayOrder) ?: team.players.take(r.fielders).map { it.id }
                    val positions = (if (home) game.homePositions else game.awayPositions)
                        ?: team.players.take(r.fielders).mapIndexed { i, p -> i + 1 to p.id }.toMap()
                    val labels = listOf("", "P", "C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "Free")
                    w.line("完整打序与守备：" + order.mapIndexedNotNull { slot, id ->
                        team.players.firstOrNull { it.id == id }?.let { p ->
                            val position = positions.entries.firstOrNull { it.value == id }?.key
                            "${slot + 1}棒 ${playerName(p)}（${position?.let { labels.getOrNull(it) } ?: "暂不守备"}）"
                        }
                    }.joinToString("；"), 9f)
                }
                tables(w, ReportStatisticsProjection.gameParticipants(game, team.id), 1, r.mode == GameMode.coachPitch,
                    titlePrefix = label, compact = true)
            }
            w.save(file)
        }
    }
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val image = file.extension.equals("png", ignoreCase = true)
        val intent = Intent(Intent.ACTION_SEND).setType(if (image) "image/png" else "application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, if (image) "分享图片" else "分享 PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
