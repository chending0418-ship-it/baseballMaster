package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Use only with the Build 31 disposable simulator and the adb-reversed analysis fixture. */
@RunWith(AndroidJUnit4::class)
class AnalysisNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidence by lazy { File(requireNotNull(context.getExternalFilesDir(null)), "build31-analysis").apply { mkdirs() } }
    private val submissions = CopyOnWriteArrayList<JsonObject>()
    private val polls = CopyOnWriteArrayList<String>()

    @After fun resetOverride() { androidTeamDirectoryTestOverride = null }

    @Test fun nativeAnalysisHonorsConsentAndTheThreeRecordedScopes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedAnalysis") == "true")
        assertTrue(BuildConfig.DEBUG)
        assertEquals(31, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        Configurator.getInstance().setWaitForIdleTimeout(450).setWaitForSelectorTimeout(2_000)
        val games = seed()
        val before = libraryRows()
        val token = TeamSecrets.random()
        val transport = TeamTransport { method, path, body ->
            if (path == "game-analysis" && method == "POST") submissions.add(requireNotNull(body))
            if (path.startsWith("game-analysis/") && method == "GET") polls.add(path)
            request(method, path, token, body)
        }
        val session = TeamDirectorySession(TeamDirectoryClient(transport),
            TeamDirectoryFile(File(context.noBackupFilesDir, "build31-analysis/${UUID.randomUUID()}"),
                { Base64.getUrlDecoder().decode(token) }), "Build31 Android analysis fixture")
        androidTeamDirectoryTestOverride = { session }
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-0")), 45_000))
        click("tab-0")
        click("game-${games.single.id}")
        click("result-open-game-analysis")
        shot("single-consent")
        click("close-game-analysis")
        assertTrue(submissions.isEmpty())
        assertNull(session.snapshot().deviceID)
        click("result-open-game-analysis")
        generate("single", matchingGames = 1)
        click("report-preview-share")
        shot("single-share-options")
        clickShareChoice("分享 PDF")
        awaitSharesheet()
        shot("single-share-pdf")
        device.pressBack()
        await("report-preview-share")
        click("report-preview-share")
        clickShareChoice("分享文字")
        awaitSharesheet()
        shot("single-share-text")
        device.pressBack()
        await("report-preview-close")
        click("report-preview-close")
        click("close-game-results")
        if (!device.hasObject(By.res("tab-2"))) device.pressBack()
        click("tab-2")
        click("stats-open-game-analysis")
        shot("season-consent")
        generate("season", matchingGames = 3)
        click("report-preview-close")
        click("stats-open-game-filter")
        StatisticsUi.setChecked(device, "stats-team-game-${games.latestSeason.id}", false)
        click("stats-save-filter")
        click("stats-open-game-analysis")
        shot("filtered-consent")
        generate("filtered", matchingGames = 2)
        click("report-preview-close")
        assertEquals(setOf("single", "season", "filtered"), submissions.map { it.input.getValue("scope").jsonPrimitive.content }.toSet())
        assertTrue(polls.size >= 3)
        assertEquals(before, libraryRows())
    }

    private fun generate(scope: String, matchingGames: Int) {
        val previous = submissions.size
        await("game-analysis-privacy")
        click("generate-game-analysis")
        await("game-analysis-pdf-summary", 45_000)
        assertEquals(previous + 1, submissions.size)
        val body = submissions.last()
        assertEquals(scope, body.input.getValue("scope").jsonPrimitive.content)
        assertEquals(matchingGames, body.input.getValue("games").jsonArray.size)
        val wire = body.toString()
        listOf("青岛", "北京", "陈昊", "赵一鸣", "Asia/Shanghai", "2024-02-29").forEach { assertFalse(wire.contains(it)) }
        File(evidence, "$scope-wire.json").writeText(wire)
        val cache = File(context.cacheDir, "reports").walkTopDown().filter { it.isFile && it.name.endsWith(".pdf") &&
            it.parentFile?.name?.startsWith("analysis-") == true }.maxBy { it.lastModified() }
        ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY).let {
            PdfRenderer(it).use { pdf ->
                assertTrue(pdf.pageCount >= 1)
                pdf.openPage(0).use { page -> assertEquals(595, page.width); assertEquals(842, page.height) }
            }
        }
        cache.copyTo(File(evidence, "$scope-report.pdf"), overwrite = true)
        shot("$scope-pdf")
    }

    private data class Fixture(val single: Game, val latestSeason: Game)

    private fun seed(): Fixture = withDatabase { db ->
        val wavesPositions = listOf(6, 8, 2, 1, 5, 7, 3, 4, 9)
        val waves = Team(id(), "青岛海浪", listOf("陈昊", "周子墨", "林宇轩", "王星野", "许嘉树", "韩一川", "顾晨", "沈亦航", "唐乐天")
            .zip(listOf("12", "7", "18", "23", "5", "9", "3", "16", "21")).mapIndexed { index, (name, number) ->
                Player(id(), name, number, position = wavesPositions[index], jerseyNumbers = if (index == 0) listOf("12", "88") else listOf(number))
            }, shortName = "海浪")
        val falcons = Team(id(), "北京飞鹰", listOf("赵一鸣", "高博文", "邵子谦", "何俊熙", "梁天佑", "彭奕辰", "罗凯", "杜明泽", "郑文轩")
            .mapIndexed { index, name -> Player(id(), name, "${index + 1}", position = index + 1) }, isOpponent = true, shortName = "飞鹰")
        for (team in listOf(waves, falcons)) db.dao().insertTeam(TeamEntity(team.id, team.name, GameJson.encodeToString(team), team.isOpponent))
        require(context.getSharedPreferences("baseballmaster-settings", 0).edit().putString("current_team_id", waves.id).commit())
        val year = Year.now().value
        val zone = ZoneId.of("Asia/Shanghai")
        fun date(forYear: Int, day: Int) = LocalDate.of(forYear, 9, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        fun built(ownHome: Boolean, at: Long, innings: Int = 6, useRosterPositions: Boolean = false, record: FixtureScorer.() -> Unit): Game {
            val home = if (ownHome) waves else falcons
            val away = if (ownHome) falcons else waves
            val game = MatchFactory.create(id(), home, away, Rules(innings = innings), waves.id).apply {
                if (useRosterPositions) {
                    homePositions = home.players.associate { it.position to it.id }
                    awayPositions = away.players.associate { it.position to it.id }
                    homePitcherID = homePositions?.get(1)
                    awayPitcherID = awayPositions?.get(1)
                }
                playedAt = at
                startedAt = at
                playedTimeZoneID = zone.id
            }
            val recorder = FixtureScorer(game, at).apply(record)
            recorder.record(Command("finish"))
            return recorder.game
        }
        val single = built(false, Instant.parse("2024-02-29T06:00:00Z").toEpochMilli(), innings = 7, useRosterPositions = true) {
            pitch(PitchAction.ball); pitch(PitchAction.foul); hit(Outcome.single); pitch(PitchAction.calledStrike)
            record(Command("runner", runnerKind = "stolenBase", decisions = MatchEngine.suggestedRunner(game, "stolenBase")))
            hit(Outcome.double); hit(Outcome.pending)
        }
        // The iOS season preview assigns its lineup by batting index (positions 1 through 9).
        val first = built(false, date(year, 1)) {
            hit(Outcome.single); hit(Outcome.homeRun); repeat(3) { groundOut() }
            repeat(3) { pitch(PitchAction.swingingStrike) }; repeat(2) { groundOut() }
        }
        val middle = built(false, date(year, 2)) { hit(Outcome.double) }
        val latest = built(true, date(year, 3)) { hit(Outcome.single) }
        val previous = built(true, date(year - 1, 1)) { hit(Outcome.homeRun) }
        for (game in listOf(single, first, middle, latest, previous)) {
            game.validate()
            db.dao().insertGame(GameEntity(game.id, GameJson.encodeToString(game), game.revision, game.updatedAt, 4))
        }
        Fixture(single, latest)
    }

    private class FixtureScorer(var game: Game, private val at: Long) {
        fun record(command: Command) { game = MatchEngine.apply(game, command.copy(at = at + game.revision * 2_000)) }
        fun hit(outcome: Outcome) = record(Command("play", outcome = outcome))
        fun pitch(action: PitchAction) = record(Command("pitch", pitch = action))
        fun groundOut() = record(Command("play", outcome = Outcome.groundOut,
            fielding = Fielding(listOf(6, 3), assists = listOf(6), putouts = listOf(3))))
    }

    private fun libraryRows() = withDatabase { db -> db.dao().allGames().associateBy { it.id } }
    private fun <T> withDatabase(block: suspend (LocalDatabase) -> T): T = runBlocking {
        val db = LocalDatabase.open(context)
        try { block(db) } finally { db.close() }
    }

    private val JsonObject.input get() = getValue("input").jsonObject
    private fun id() = UUID.randomUUID().toString()
    private fun await(tag: String, timeout: Long = 8_000) = assertTrue("Expected $tag", device.wait(Until.hasObject(By.res(tag)), timeout))
    private fun awaitSharesheet() = assertTrue("Expected Android Sharesheet",
        device.wait(Until.hasObject(By.pkg("com.android.intentresolver")), 6_000) ||
            device.wait(Until.hasObject(By.pkg("android").textContains("分享")), 6_000))
    private fun clickShareChoice(title: String) {
        NativeUi.find(device, By.text(title)).click()
        // The resolver runs in a system process and cannot answer the app-only tree refresh signal.
        device.waitForIdle(450)
    }
    private fun click(tag: String) = click(By.res(tag))
    private fun click(selector: BySelector) {
        NativeUi.find(device, selector).click()
        device.waitForIdle(450)
        UiTreeCache.refresh()
    }

    private fun shot(name: String) {
        device.waitForIdle(450)
        val png = File(evidence, "$name.png")
        if (!device.takeScreenshot(png)) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p")).use { data ->
                png.outputStream().use { data.copyTo(it) }
            }
        }
        assertTrue(png.length() > 1024)
        device.dumpWindowHierarchy(File(evidence, "$name.xml"))
    }

    private fun request(method: String, path: String, credential: String, body: JsonObject?): JsonObject = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", 18788), 6_000)
        socket.soTimeout = 8_000
        val bytes = body?.toString()?.encodeToByteArray() ?: byteArrayOf()
        val headers = "$method /api/v3/$path HTTP/1.0\r\nHost: 127.0.0.1:18788\r\nConnection: close\r\n" +
            "Authorization: Bearer $credential\r\nX-BM-Protocol: 1\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
        socket.getOutputStream().apply { write(headers.encodeToByteArray()); write(bytes); flush() }
        val reply = socket.getInputStream().bufferedReader().readText()
        val status = reply.substringBefore("\r\n").split(' ')[1].toInt()
        val result = Json.parseToJsonElement(reply.substringAfter("\r\n\r\n")).jsonObject
        if (status !in 200..299) throw TeamServiceFault(status, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "fixture_failure")
        result
    }
}
