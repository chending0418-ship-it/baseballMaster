package cc.baseballmaster.scorekeeper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Calls the production PDF exporter on an in-memory synthetic match. No Room writes. */
@RunWith(AndroidJUnit4::class)
class Product082ExportPreviewTest {
    @Test fun exportActualAndroidReportsForReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun team(id: String, name: String, names: List<String>) = Team(id, name,
            names.mapIndexed { i, n -> Player("$id-$i", n, "${i + 1}", position = i + 1) })
        val away = team("preview-away", "海浪少年队", listOf("陈靖森", "朱睿荃", "袁乐扬", "袁允司", "白兰田", "钱芳林", "林浩然", "许嘉辰", "周予安"))
        val home = team("preview-home", "飞鹰棒球队", listOf("赵景行", "孙浩宇", "李沐阳", "周子谦", "吴承泽", "郑明轩", "王梓睿", "冯逸辰", "陈奕帆"))
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
        assertTrue(game.isFinal)
        assertEquals(2, game.awayRuns)
        val files = linkedMapOf(
            "082-example-game-report.pdf" to ReportExport.game(context, game, false),
            "082-example-play-by-play.pdf" to ReportExport.game(context, game, true),
            "082-example-text-record.pdf" to ReportExport.game(context, game, true, textOnly = true),
        )
        files.forEach { (name, source) ->
            assertTrue(source.length() > 1000)
            source.copyTo(File(context.getExternalFilesDir(null), name), overwrite = true)
        }
    }
}
