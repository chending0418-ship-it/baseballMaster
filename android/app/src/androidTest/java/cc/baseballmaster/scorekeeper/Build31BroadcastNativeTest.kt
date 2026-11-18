package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Activity and shared Build 6 loopback team service. */
@RunWith(AndroidJUnit4::class)
class Build31BroadcastNativeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = inst.targetContext
    private val ui = UiDevice.getInstance(inst)
    private val evidence by lazy { File(context.getExternalFilesDir(null), "build31-broadcast").apply { mkdirs() } }

    @Before fun prepare() {
        assumeTrue(args.getString("isolatedBroadcast") == "true")
        assertTrue(BuildConfig.DEBUG)
        assertEquals(31, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        Configurator.getInstance().setWaitForIdleTimeout(400).setWaitForSelectorTimeout(1500)
        androidTeamBroadcastOverlayBaseTestOverride = "http://127.0.0.1:18788/livestreaming/novideo/overlay"
    }

    @After fun restore() {
        androidTeamDirectoryTestOverride = null
        androidTeamBroadcastOverlayBaseTestOverride = null
    }

    @Test fun scorerReadsIOSSourceSwitchesAndPublishesNativeFollowUp() {
        val team = team()
        val actor = join(team, TeamRole.scorer)
        val before = fixtureSources(team)
        val code = before.value("code")
        val oldGameID = before.value("activeGameID")
        assertTrue(oldGameID.isNotBlank())
        assertTrue(publicFeed(code).getValue("active").jsonPrimitive.boolean)
        val match = seedMatch(actor, team, "北京飞鹰")

        launch(actor.session)
        openTeam(team)
        click("open-team-broadcast-sources")
        assertSources()
        assertFalse(ui.hasObject(By.res("rotate-broadcast")))
        shot("android-scorer-ios-current")
        launch(actor.session)
        click("tab-0")
        click("game-${match.id}")
        click("pitch-ball")
        click("confirm-opening-pitch")
        waitFor("native first pitch") { localMatch(match.id)?.takeIf { it.totalPitches == 1 } }
        assertEquals(oldGameID, fixtureSources(team).value("activeGameID"))

        openScoringSources()
        click("select-team-broadcast")
        click("confirm-team-broadcast")
        val selected = waitFor("same fixed sources select Android") {
            publicFeed(code)["snapshot"]?.jsonObject?.takeIf { it.value("gameID") == match.id }
        }
        assertEquals(1, selected["balls"]?.jsonPrimitive?.int)
        assertEquals(code, fixtureSources(team).value("code"))
        assertSources()
        shot("android-selected")
        mark(team, "android-selected")

        ui.pressBack()
        click("pitch-calledStrike")
        val next = waitFor("automatic public revision from native scoring") {
            publicFeed(code)["snapshot"]?.jsonObject?.takeIf { it["strikes"]?.jsonPrimitive?.int == 1 }
        }
        assertEquals(match.id, next.value("gameID"))
        assertTrue(next.getValue("revision").jsonPrimitive.long > selected.getValue("revision").jsonPrimitive.long)
        val stale = request("PUT", "/api/v3/teams/$team/broadcast", actor.secret,
            buildJsonObject { put("snapshot", selected); put("expectedGameID", match.id) })
        assertEquals(409, stale.first)
        assertEquals("broadcast_revision", stale.second.getValue("error").jsonObject.value("code"))
        assertEquals(403, request("POST", "/api/v3/teams/$team/broadcast/rotate", actor.secret,
            buildJsonObject { put("expectedCode", code) }).first)
        shot("android-recorded-next")
        openScoringSources()
        assertSources()
        shot("android-automatic-source")
        mark(team, "android-auto")
    }

    @Test fun memberCannotReadFixedSourceEntryOrEndpoint() {
        val team = team()
        val actor = join(team, TeamRole.member)
        launch(actor.session)
        openTeam(team)
        assertFalse(ui.hasObject(By.res("open-team-broadcast-sources")))
        assertEquals(403, request("GET", "/api/v3/teams/$team/broadcast", actor.secret).first)
        shot("android-member-no-source")
    }

    @Test fun androidAdminResetsFourLinksAndInvalidatesOldCode() {
        val actor = actor("admin")
        actor.session.refresh()
        actor.session.prepareCreate("Android OBS 管理验证", "Android管理员")
        val team = actor.session.sendPending().response.value("teamID").lowercase()
        val old = fixtureSources(team).value("code")
        assertFalse(publicFeed(old).getValue("active").jsonPrimitive.boolean)
        launch(actor.session)
        openTeam(team)
        click("open-team-broadcast-sources")
        assertSources()
        shot("android-idle-admin")
        click("rotate-broadcast")
        click("confirm-team-broadcast")
        val next = waitFor("reset fixed code") { fixtureSources(team).value("code").takeIf { it != old } }
        assertEquals(404, request("GET", "/api/v3/broadcast/$old").first)
        assertFalse(publicFeed(next).getValue("active").jsonPrimitive.boolean)
        assertSources()
        shot("android-reset-admin")
    }

    @Test fun linkedTeamAutomaticallyStartsFourSourcesWithFirstNativePitch() {
        val actor = actor("admin")
        actor.session.refresh()
        val own = database { db -> GameRepository(db).createTeam("Android OBS 自动首发", SampleRoster) }
        actor.session.prepareCreate(own, "自动首发验证")
        val team = actor.session.sendPending().response.value("teamID").lowercase()
        assertEquals(TeamRole.admin, actor.session.refresh().teams.single { it.id == team }.role)
        val code = fixtureSources(team).value("code")
        val match = database { db ->
            val repo = GameRepository(db)
            val away = repo.createTeam("南湾飞鹰", SampleRoster, true)
            repo.createPreparedGame(MatchFactory.create(id(), own, away, Rules(), own.id))
        }
        launch(actor.session)
        click("tab-0")
        click("game-${match.id}")
        assertFalse(publicFeed(code).getValue("active").jsonPrimitive.boolean)
        shot("android-first-auto-pregame")
        click("pitch-ball")
        click("confirm-opening-pitch")
        waitFor("persisted first pitch") { localMatch(match.id)?.takeIf { it.totalPitches == 1 } }
        val started = waitFor("automatically selected first native pitch") {
            publicFeed(code)["snapshot"]?.jsonObject?.takeIf { it.value("gameID") == match.id }
        }
        assertEquals(1, started.getValue("balls").jsonPrimitive.int)
        assertNotNull(started["pitcher"] as? JsonObject)
        assertNotNull(started["batter"] as? JsonObject)
        assertTrue(started.getValue("away").jsonObject.getValue("lineup").jsonArray.isNotEmpty())
        assertTrue(started.getValue("home").jsonObject.getValue("lineup").jsonArray.isNotEmpty())
        openScoringSources()
        assertSources()
        shot("android-first-auto")
        mark(team, "android-first-auto")
    }

    @Test fun acceptSharedBroadcastThenProposeBackToIOS() {
        val documentID = args.getString("sharedBroadcastDocumentID")
        assumeTrue("Supply the same-source iOS shared game document", documentID != null)
        val document = requireNotNull(documentID)
        val team = team()
        val actor = join(team, TeamRole.scorer)
        val original = actor.session.match(team, document)
        val originalWriter = original.writerDeviceID
        val gameID = requireNotNull(original.payload).value("id").lowercase()
        val initial = fixtureSources(team)
        val code = initial.value("code")
        assertEquals(gameID, initial.value("activeGameID"))
        val streamBefore = publicFeed(code).value("revision")
        val who = requireNotNull(actor.session.snapshot().deviceID)
        File(evidence, "handoff-target.json").writeText(buildJsonObject {
            put("teamID", team); put("documentID", document); put("gameID", gameID)
            put("androidDeviceID", who); put("iosWriterDeviceID", originalWriter); put("startingEpoch", original.writerEpoch)
        }.toString())
        waitFor("proposal from iOS", 180_000) {
            actor.session.match(team, document).handoff?.takeIf { it.value("targetDeviceID").equals(who, true) }
        }

        launch(actor.session)
        openTeam(team)
        NativeUi.find(ui, By.text("球队比赛与观看设置")).click()
        click("open-shared-match-$document")
        click("accept-broadcast-handoff")
        click("confirm-broadcast-handoff")
        val accepted = waitFor("Android is current shared writer") {
            actor.session.match(team, document).takeIf { it.handoff == null && it.writerDeviceID.equals(who, true) }
        }
        assertEquals(original.writerEpoch + 1, accepted.writerEpoch)
        val streamAfter = waitFor("same fixed source resumed at new writer generation") {
            publicFeed(code).takeIf { it["revision"]?.jsonPrimitive?.contentOrNull != streamBefore && it["snapshot"] != null }
        }
        assertEquals(gameID, streamAfter.getValue("snapshot").jsonObject.value("gameID"))
        click("shared-match-broadcast")
        assertSources()
        ui.takeScreenshot(File(evidence, "android-shared-resumed.png"))
        mark(team, "android-handoff-accepted-final")
        ui.pressBack()
        click("propose-broadcast-handoff")
        click("broadcast-handoff-device-$originalWriter")
        click("confirm-broadcast-handoff")
        val returned = waitFor("handoff back to iOS") {
            actor.session.match(team, document).handoff?.takeIf { it.value("targetDeviceID").equals(originalWriter, true) }
        }
        File(evidence, "handoff-return.json").writeText(buildJsonObject {
            put("teamID", team); put("documentID", document); put("gameID", gameID)
            put("acceptedEpoch", accepted.writerEpoch); put("handoffID", returned.value("id"))
            put("iosWriterDeviceID", originalWriter)
        }.toString())
        val restored = waitFor("iOS accepts and records next pitch", 180_000) {
            actor.session.match(team, document).takeIf { it.handoff == null &&
                it.writerEpoch == accepted.writerEpoch + 1 && it.writerDeviceID.equals(originalWriter, true) &&
                it.revision > accepted.revision }
        }
        val androidSnapshot = streamAfter.getValue("snapshot").jsonObject
        val pitchCountBefore = androidSnapshot["pitchCount"]?.jsonPrimitive?.intOrNull ?: 0
        val ios = waitFor("iOS pitch on same four sources") {
            val current = publicFeed(code)["snapshot"] as? JsonObject
            current?.takeIf { it.value("gameID") == gameID && (it["pitchCount"]?.jsonPrimitive?.intOrNull ?: 0) > pitchCountBefore }
        }
        val delayed = request("PUT", "/api/v3/teams/$team/broadcast", actor.secret, buildJsonObject {
            put("writerEpoch", accepted.writerEpoch)
            put("snapshot", androidSnapshot)
        })
        assertEquals(409, delayed.first)
        File(evidence, "handoff-native-result.json").writeText(buildJsonObject {
            put("gameID", gameID)
            put("initialIOSWriterEpoch", original.writerEpoch)
            put("androidAcceptedWriterEpoch", accepted.writerEpoch)
            put("restoredIOSWriterEpoch", restored.writerEpoch)
            put("restoredIOSRevision", restored.revision)
            put("pitchCountBeforeReturn", pitchCountBefore)
            put("pitchCountAfterIOS", ios.getValue("pitchCount"))
            put("delayedAndroidWriteStatus", delayed.first)
        }.toString())
    }

    private data class Actor(val session: TeamDirectorySession, val storage: TeamDirectoryFile, val secret: String)

    private fun team(): String = args.getString("broadcastTeamID") ?: control("GET", "teams")
        .getValue("teams").jsonArray.map { it.jsonObject }.last { it.value("name").contains("青岛海浪") }.value("teamID")

    private fun actor(role: String): Actor {
        val secret = TeamSecrets.random()
        val transport = TeamTransport { method, path, body ->
            val (status, response) = request(method, "/api/v3/$path", secret, body)
            if (status !in 200..299) throw TeamServiceFault(status,
                (response["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content ?: "fixture_error")
            response
        }
        val store = TeamDirectoryFile(File(context.noBackupFilesDir, "build31-obs-${id()}"),
            { Base64.getUrlDecoder().decode(secret) })
        return Actor(TeamDirectorySession(TeamDirectoryClient(transport), store, "Android OBS $role"), store, secret)
    }

    private fun join(team: String, role: TeamRole): Actor {
        val actor = actor(role.name)
        val invite = control("POST", "team/$team/invite/${role.name}").value("token")
        actor.session.refresh()
        actor.session.prepareJoin(invite, "Android OBS ${role.name}")
        actor.session.sendPending()
        assertEquals(role, actor.session.refresh().teams.single { it.id == team }.role)
        return actor
    }

    private fun seedMatch(actor: Actor, teamID: String, opponent: String): Game = database { db ->
        val repo = GameRepository(db)
        val own = repo.createTeam("青岛海浪", SampleRoster)
        val away = repo.createTeam(opponent, SampleRoster, true)
        actor.storage.save(actor.session.snapshot().copy(bindings = listOf(SharedTeamBinding(own.id, teamID))))
        repo.createPreparedGame(MatchFactory.create(id(), own, away, Rules(), own.id))
    }

    private fun localMatch(id: String) = database { db -> db.dao().game(id)?.let(GameRepository::decode) }
    private fun <T> database(block: suspend (LocalDatabase) -> T): T = runBlocking {
        val db = LocalDatabase.open(context)
        try { block(db) } finally { db.close() }
    }

    private fun launch(session: TeamDirectorySession) {
        androidTeamDirectoryTestOverride = { session }
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(ui.wait(Until.hasObject(By.res("tab-0")), 25_000))
    }
    private fun openTeam(team: String) {
        click("tab-1")
        click("open-team-sharing")
        click("open-shared-team-$team")
    }
    private fun openScoringSources() {
        click("scoring-more")
        NativeUi.find(ui, By.text("球队固定转播源")).click()
        ui.waitForIdle(400)
    }
    private fun click(tag: String) {
        NativeUi.find(ui, By.res(tag)).click()
        ui.waitForIdle(400)
    }
    private fun assertSources() {
        BroadcastSource.entries.forEach {
            val row = NativeUi.find(ui, By.res("team-broadcast-source-${it.name}"))
            assertTrue(row.text.orEmpty().contains(it.title) || row.findObject(By.text(it.title)) != null)
        }
    }
    private fun shot(name: String) {
        inst.waitForIdleSync()
        val destination = File(evidence, "$name.png")
        for (attempt in 0..2) {
            if (ui.takeScreenshot(destination)) {
                ui.dumpWindowHierarchy(File(evidence, "$name.xml"))
                return
            }
            SystemClock.sleep(250)
        }
        error("could not capture $name")
    }
    private fun <T> waitFor(label: String, timeout: Long = 20_000, query: () -> T?): T {
        val until = SystemClock.uptimeMillis() + timeout
        do {
            query()?.let { return it }
            SystemClock.sleep(250)
        } while (SystemClock.uptimeMillis() < until)
        error("timed out waiting for $label")
    }
    private fun mark(team: String, stage: String) {
        if (args.getString("obsGates") != "true") return
        control("POST", "obs-stage?stage=$stage&teamID=$team")
        waitFor(stage, 90_000) {
            true.takeIf { control("GET", "obs-gate?stage=$stage").getValue("released").jsonPrimitive.boolean }
        }
    }
    private fun fixtureSources(team: String) = control("GET", "team/$team/sources")
    private fun publicFeed(code: String) = request("GET", "/api/v3/broadcast/$code").second
    private fun control(method: String, path: String): JsonObject {
        val (status, response) = request(method, "/__build31/$path")
        check(status in 200..299) { "fixture status $status" }
        return response
    }
    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
    private fun id() = UUID.randomUUID().toString()

    private fun request(method: String, path: String, secret: String? = null, body: JsonObject? = null): Pair<Int, JsonObject> =
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 18788), 5_000)
            socket.soTimeout = 8_000
            val bytes = body?.toString()?.encodeToByteArray() ?: byteArrayOf()
            val headers = buildString {
                append("$method $path HTTP/1.0\r\nHost: 127.0.0.1:18788\r\nX-BM-Protocol: 1\r\nConnection: close\r\n")
                if (secret != null) append("Authorization: Bearer $secret\r\n")
                append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n")
            }
            socket.getOutputStream().apply { write(headers.encodeToByteArray()); write(bytes); flush() }
            val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            val status = raw.substringBefore("\r\n").split(' ')[1].toInt()
            status to Json.parseToJsonElement(raw.substringAfter("\r\n\r\n")).jsonObject
        }
}
