package cc.baseballmaster.scorekeeper
import android.content.ContextWrapper
import cc.baseballmaster.core.*
import java.io.File
object NativePdfHarness {
 @JvmStatic fun main(args: Array<String>) {
  android.graphics.Typeface::class.java.declaredMethods.filter { it.name.contains("SystemFont") || it.name.contains("initSystem") }.forEach { println(it.toString()) }
  android.graphics.Typeface::class.java.getDeclaredMethod("loadPreinstalledSystemFontMap").apply { isAccessible = true }.invoke(null)
  val out = File(args[0]).apply { mkdirs() }
  val context = object: ContextWrapper(null) { override fun getCacheDir(): File = out }
        fun team(id: String, name: String, names: List<String>) = Team(id, name,
            names.mapIndexed { i, n -> Player("$id-$i", n, "${i + 1}", position = i + 1) })
        val away = team("preview-away", "海浪少年队", listOf("陈靖森", "朱睿荃", "袁乐扬", "袁允司", "白兰田", "钱芳林", "林浩然", "许嘉辰", "周予安"))
        val home = team("preview-home", "飞鹰棒球队", listOf("赵景行", "孙浩宇", "李沐阳", "周子谦", "吴承泽", "郑明轩", "王梓睿", "冯逸辰", "陈奕帆"))
        home.players.first().apply { number = "00"; jerseyNumbers = listOf("00", "2") }
        var game = MatchFactory.create("android-export-preview", home, away, Rules(recordingMethod = "situation"), away.id)
        game = MatchEngine.apply(game, Command("pitch", pitch = PitchAction.ball))
        game = MatchEngine.apply(game, Command("pitch", pitch = PitchAction.calledStrike))
        game = MatchEngine.apply(game, Command("play", outcome = Outcome.single,
            observation = Observation(type = "line", precision = "point", x = .38, y = .42, fielding = Fielding(route = listOf(8, 6)))))
        game = MatchEngine.apply(game, Command("play", outcome = Outcome.homeRun,
            observation = Observation(type = "fly", precision = "region", region = "left", fielding = Fielding(status = "noFielding"))))
        repeat(6) { game = MatchEngine.apply(game, Command("play", outcome = Outcome.groundOut, fielding = Fielding(route = listOf(6, 3)))) }
        game = MatchEngine.apply(game, Command("finish", text = "weather"))
        game = game.copy(playedAt = java.time.Instant.parse("2026-11-04T01:00:00Z").toEpochMilli(), playedTimeZoneID = "Asia/Shanghai")
        check(game.isFinal)
        check(game.awayRuns == 2)

  val files = linkedMapOf(
   "game-report.pdf" to ReportExport.game(context, game, false),
   "play-by-play.pdf" to ReportExport.game(context, game, true),
   "text-record.pdf" to ReportExport.game(context, game, true, textOnly = true))
  val summary = SeasonStatisticsCalculator.calculate(away, listOf(game), 2026)
  val row = summary.players.first { it.player.id == away.players.first().id }
  files["team-report.pdf"] = ReportExport.team(context, away, 2026, GameMode.standard, summary)
  files["player-report.pdf"] = ReportExport.player(context, away, 2026, row, listOf(game))
  val thirty = game.copy(inning = 30, awayRunsByInning = List(30) { if(it == 0) 2 else 0 }, homeRunsByInning = List(30) { 0 })
  files["stress-30-innings.pdf"] = ReportExport.game(context, thirty, false)
  val players64 = (1..64).map { Player("preview-away-long-$it", "第${it}位" + "超长中文姓名".repeat(6) + "末尾姓名标记${it}", "$it", position = (it - 1) % 9 + 1) }
  val longTeam = away.copy(players = players64)
  val longMatch = game.copy(away = longTeam, awayOrder = players64.map { it.id },
      batting = players64.associate { it.id to BattingLine(atBats = 3, hits = 1, plateAppearances = 3) }.toMutableMap())
  files["stress-64-players.pdf"] = ReportExport.game(context, longMatch, false)
  val longText = "超长事件原文测试".repeat(500) + "事件末尾保留标记"
  val longEvent = game.copy(entries = game.entries.mapIndexed { index, entry -> if (index == 0) entry.copy(text = longText) else entry })
  files["stress-long-event.pdf"] = ReportExport.game(context, longEvent, true)
  files["stress-long-event-text.pdf"] = ReportExport.game(context, longEvent, true, textOnly = true)
  files["single-appearance.pdf"] = ReportExport.game(context, game, true, appearanceID = AppearanceProjection.build(game).first().id)
  val empty = MatchFactory.create("preview-empty", home, away, Rules(), away.id)
  files["empty-appearances.pdf"] = ReportExport.game(context, empty, true)
  files["coach-report.pdf"] = ReportExport.game(context, game.copy(rules = Rules(GameMode.coachPitch)), false)
  files["timed-report.pdf"] = ReportExport.game(context, game.copy(rules = Rules(timed = true, timeLimitMinutes = 90)), false)
  fun tenth(t: Team) = t.copy(players = t.players + Player(t.id + "-10", "慢垒第十名", "00", position = 10))
  val slow = MatchFactory.create("preview-slow", tenth(home), tenth(away), Rules(GameMode.slowPitch), away.id)
  files["slow-pitch-report.pdf"] = ReportExport.game(context, slow, false)
  files["empty-boxscore.pdf"] = ReportExport.game(context, empty, false)
  files.forEach { (name, source) -> source.copyTo(File(out,name),overwrite=true); println("PDF $name ${source.length()}") }
 }
}
