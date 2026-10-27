package cc.baseballmaster.scorekeeper

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import cc.baseballmaster.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportExportTest {
    @Test fun systemChooserOpensForPdfWithoutSending() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val team = Team("team", "春雷", listOf(Player("p", "球员", "1")))
        val summary = SeasonStatistics(emptyList(), emptyList(), 0, 0, 0, 0, 0, 0, 0)
        val file = ReportExport.team(context, team, 2026, null, summary)
        ReportExport.share(context, file)
        val device = androidx.test.uiautomator.UiDevice.getInstance(inst)
        // The system chooser replaces our supplied title on some Android releases.
        val shown = device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.textStartsWith("BaseballMaster-team-")), 10_000)
        val dir = context.getExternalFilesDir(null)!!
        device.takeScreenshot(File(dir, "an04-share-chooser.png"))
        device.dumpWindowHierarchy(File(dir, "an04-share-chooser.xml"))
        assertTrue(shown)
        device.pressBack()
    }
    @Test fun detailedAndTextReportsKeepIOSPageOrientationAndBattedBall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun team(id: String) = Team(id, id, (1..9).map { Player("$id-$it", "球员$it", "$it", position = it) })
        var game = MatchFactory.create("report", team("主队"), team("客队"), Rules())
        game = MatchEngine.apply(game, Command("play", outcome = Outcome.single, observation = Observation(type = "line", precision = "region", region = "center")))
        val appearances = AppearanceProjection.build(game)
        assertEquals(2, appearances.size)
        assertTrue(appearances.first().complete)
        assertFalse(appearances.last().complete)
        val detail = ReportExport.game(context, game, true)
        val text = ReportExport.game(context, game, true, textOnly = true)
        val single = ReportExport.game(context, game, true, appearanceID = appearances.first().id)
        assertTrue(single.length() > 1000)
        assertThrows(IllegalArgumentException::class.java) { ReportExport.game(context, game, true, appearanceID = "not-a-real-appearance") }
        val external = context.getExternalFilesDir(null)!!
        detail.copyTo(File(external, "an04-detail-report.pdf"), overwrite = true)
        text.copyTo(File(external, "an04-text-report.pdf"), overwrite = true)
        ParcelFileDescriptor.open(detail, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
            pdf.openPage(0).use { page -> assertTrue(page.width > page.height) }
        } }
        ParcelFileDescriptor.open(text, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
            pdf.openPage(0).use { page -> assertTrue(page.width < page.height) }
        } }
    }
    @Test fun chineseLongRosterMakesReadableMultipagePdfAndShareUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val players = (1..80).map { Player("p$it", "长姓名测试球员$it", (it % 99).toString()) }
        val team = Team("team", "春雷棒球队", players)
        val rival = Team("rival", "海风", listOf(Player("r", "对手", "9")), true)
        val game = Game("game", rival, team, Rules(), ourTeamID = team.id, isFinal = true, awayRuns = 3, homeRuns = 2, createdAt = System.currentTimeMillis())
        val rows = players.map { SeasonPlayerStatistics(it, true, games = 1, batting = BattingLine(atBats = 3, hits = 2, runsBattedIn = 1)) }
        val summary = SeasonStatistics(listOf(game), rows, 1, 0, 0, 3, 2, 0, 0)
        val file = ReportExport.team(context, team, 2026, null, summary)
        assertTrue(file.length() > 5_000)
        assertEquals("content", FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file).scheme)
        val external = context.getExternalFilesDir(null)!!
        file.copyTo(File(external, "an04-team-report.pdf"), overwrite = true)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                assertTrue("A long roster must span multiple pages", pdf.pageCount > 2)
                pdf.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    assertTrue((0 until bitmap.width step 8).any { x -> (0 until bitmap.height step 8).any { y -> bitmap.getPixel(x, y) != android.graphics.Color.WHITE } })
                    File(external, "an04-team-report-page1.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                for (i in 0 until pdf.pageCount) pdf.openPage(i).use { assertTrue(it.width > 0 && it.height > 0) }
            }
        }
        assertTrue(ReportExport.player(context, team, 2026, rows.first()).length() > 1000)
        assertTrue(ReportExport.game(context, game, true).length() > 1000)
        val poster = GamePoster.write(context, game, "一起到场，为球队加油", "城市棒球场", "欢迎队员、家长和朋友们到场观赛")
        val decoded = android.graphics.BitmapFactory.decodeFile(poster.absolutePath)
        assertEquals(1080, decoded.width); assertEquals(1440, decoded.height)
        assertEquals(android.graphics.Color.rgb(245, 242, 232), decoded.getPixel(500, 100))
        poster.copyTo(File(external, "an04-match-poster.png"), overwrite = true)
        decoded.recycle()
    }
}
