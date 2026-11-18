package cc.baseballmaster.scorekeeper

import android.content.Context
import android.graphics.BitmapFactory
import cc.baseballmaster.core.AnalysisReport
import cc.baseballmaster.core.AnalysisSource
import java.io.File
import java.nio.file.Files

/** The same resolved names and narrative back the A4 preview and the text share. */
internal class AnalysisDocument(private val source: AnalysisSource, report: AnalysisReport) {
    private data class Group(val title: String, val rows: List<List<String>>)

    private val scope = source.input.scope
    private val overview = source.display(report.overview)
    private val situations = report.situations.map(source::display)
    private val disclosure = "AI 生成 · 请结合比赛记录核对" +
        if (source.input.games.any { it.incomplete }) "。部分比赛过程或统计记录不全。" else ""
    private val mvpName = report.mvp?.let { source.names[it.id] }
    private val mvpTitle = scope.mvpTitle + (mvpName?.let { " · $it" } ?: "")
    private val mvpDetail = report.mvp?.takeIf { mvpName != null }?.let { source.display(it.reason) } ?: "现有记录不足以评选。"
    private val groups = run {
        val reports = report.players.associateBy { it.id }
        val headings = if (source.teamNames.isEmpty()) listOf(2 to "本队球员") else source.teamNames.withIndex().map { it.index to it.value }
        headings.mapNotNull { (side, heading) ->
            val rows = source.input.players.filter { it.side == side }.map { player ->
                listOf(source.names.getValue(player.id), source.display(reports.getValue(player.id).evaluation))
            }
            if (rows.isEmpty()) null else Group("球员评价 · $heading", rows)
        }
    }

    fun write(context: Context): File {
        val directory = Files.createTempDirectory(Files.createDirectories(File(context.cacheDir, "reports").toPath()), "analysis-")
        val file = directory.resolve("BaseballMaster-${scope.title}.pdf").toFile()
        try {
            val brand = ReportExport.AnalysisBrand(
                BitmapFactory.decodeResource(context.resources, R.drawable.analysis_report_identity),
                BitmapFactory.decodeResource(context.resources, R.drawable.analysis_report_signoff),
            )
            ReportExport.Writer(scope.title, source.title, portrait = true, subtitle = source.subtitle, analysisBrand = brand).use { writer ->
                writer.metrics(source.metrics)
                writer.line(disclosure, muted = true)
                writer.highlight(mvpTitle, mvpDetail)
                writer.section("比赛综述", listOf(overview))
                writer.section(scope.situationsTitle, situations)
                for (group in groups) {
                    writer.table(group.title, listOf("球员", "评价"), group.rows, listOf(1f, 2.5f), compact = true,
                        leftAlignedColumns = setOf(0, 1))
                }
                writer.save(file)
            }
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun plainText(): String = buildList {
        addAll(listOf(scope.title, source.title, source.subtitle, disclosure, "", mvpTitle, mvpDetail, "", "比赛综述", overview))
        if (situations.isNotEmpty()) {
            addAll(listOf("", scope.situationsTitle))
            addAll(situations.map { "• $it" })
        }
        for (group in groups) {
            addAll(listOf("", group.title))
            for (row in group.rows) addAll(listOf("", row[0], row[1]))
        }
    }.joinToString("\n")
}
