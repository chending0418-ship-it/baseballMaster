package cc.baseballmaster.scorekeeper

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.TeamDirectoryClient
import cc.baseballmaster.core.TeamDirectorySession
import cc.baseballmaster.core.TeamRole
import cc.baseballmaster.core.TeamServiceFault
import cc.baseballmaster.core.TeamTransport
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the Build 6 loopback service fixture and a disposable simulator with adb reverse. */
@RunWith(AndroidJUnit4::class)
class Build30TeamScheduleNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidence by lazy { File(context.getExternalFilesDir(null), "build30").apply { mkdirs() } }

    @Before fun configure() {
        assumeTrue("Use -e isolatedSchedules true only for the loopback fixture",
            InstrumentationRegistry.getArguments().getString("isolatedSchedules") == "true")
        assertTrue(BuildConfig.DEBUG)
        assertEquals(30, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        Configurator.getInstance().setWaitForIdleTimeout(400).setWaitForSelectorTimeout(1500)
    }

    @After fun dropOverride() {
        androidTeamDirectoryTestOverride = null
    }

    @Test fun androidMemberReadsIOSPublicationWithoutCreatingALocalMatch() {
        val row = scheduleRows().single { it.value("venue") == "iOS Field B" }
        val id = row.value("id")
        val membership = join("member")
        assertEquals(row.value("teamID"), membership.session.snapshot().directory!!.teams.single().id)
        val localBefore = localGames().associateBy(Game::id)
        launch(membership.session)
        awaitNode("team-schedule-$id")
        assertFalse(node("team-schedule-$id").isClickable)
        assertText(node("team-schedule-$id"), "iOS Field B")
        assertFalse(device.hasObject(By.res("game-$id")))
        assertEquals(localBefore.keys, localGames().map(Game::id).toSet())
        shot("android-reads-ios")
    }

    @Test fun androidMemberSeesEachIOSRevisionWithoutCreatingALocalMatch() {
        val args = InstrumentationRegistry.getArguments()
        val source = requireNotNull(args.getString("sourceID"))
        val revision = requireNotNull(args.getString("revision"))
        val remote = scheduleRows().single { it.value("id") == source }
        assertEquals(revision, remote.value("revision"))
        val localBefore = localGames().map(Game::id).toSet()
        launch(join("member").session)
        val row = node("team-schedule-$source")
        assertText(row, remote.value("away") + " 对 " + remote.value("home"))
        assertText(row, remote.value("venue"))
        assertText(row, memberScheduleDate(remote.value("startsAt").toLong()))
        assertText(row, if (remote.value("cancelled") == "true") "已取消" else "球队赛程")
        assertFalse(row.isClickable)
        assertEquals(1, device.findObjects(By.res("team-schedule-$source")).size)
        assertEquals(localBefore, localGames().map(Game::id).toSet())
        shot("android-ios-member-r$revision")
    }

    @Test fun androidScorerPublishesAndRetriesWithoutAddingAnotherSchedule() {
        val membership = join("scorer")
        val opponent = openDatabase { db -> GameRepository(db).createTeam("北京飞鹰", "赵一鸣,1", true) }
        launch(membership.session)
        click("new-game")
        click("setup-scheduled-toggle")
        click("setup-own-team")
        click("setup-shared-${membership.teamID}")
        awaitNode("setup-shared-source")
        click("setup-opponent")
        click("choose-roster-${opponent.id}")
        edit("setup-schedule-venue", "Android Field B")
        assertTrue(node("publish-team-schedule").isEnabled)
        shot("android-publish-form")
        control("POST", "hold-next")
        click("publish-team-schedule")

        val row = waitFor("Android schedule publication") {
            scheduleRows().singleOrNull { it.value("venue") == "Android Field B" }
        }
        val id = row.value("id")
        assertEquals("1", row.value("revision"))
        launch(join("scorer").session)
        click("game-$id")
        assertText(node("team-schedule-status"), "待确认")
        shot("android-lost-receipt")
        click("team-schedule-retry")
        waitFor("same operation receipt") { receiptsFor(id).takeIf { it.size == 2 } }
        val attempts = receiptsFor(id)
        assertEquals(attempts[0].value("operationID"), attempts[1].value("operationID"))
        assertEquals("true", attempts[0].value("withheld"))
        assertEquals("200", attempts[1].value("status"))
        awaitText("赛程已发布给球队成员")
        assertFalse(device.hasObject(By.res("team-schedule-$id")))
        assertEquals(1, localGames().count { it.id == id && it.isScheduled })
        assertEquals(1, scheduleRows().count { it.value("id") == id })
        shot("android-publication-detail")
        launch(membership.session)
        awaitNode("game-$id")
        assertFalse(device.hasObject(By.res("team-schedule-$id")))
        shot("android-owned-home")
    }

    @Test fun androidMemberShowsBothIndependentPublicationsAtTheCurrentRevision() {
        val rows = scheduleRows()
        val ios = rows.single { it.value("venue") == "iOS Field B" }
        val android = androidPublication()
        val source = android.value("id")
        val revision = android.value("revision")
        val previousLocalIDs = localGames().map(Game::id).toSet()
        launch(join("member").session)
        val row = node("team-schedule-$source")
        assertText(row, android.value("venue"))
        assertText(row, if (android.value("cancelled") == "true") "已取消" else "球队赛程")
        assertFalse(row.isClickable)
        val peer = node("team-schedule-${ios.value("id")}")
        assertText(peer, "iOS Field B")
        assertFalse(peer.isClickable)
        assertEquals(previousLocalIDs, localGames().map(Game::id).toSet())
        shot("android-member-revision-$revision")
    }

    @Test fun androidScorerReschedulesTheSamePublishedSource() {
        val before = androidPublication()
        val source = before.value("id")
        assertEquals("1", before.value("revision"))
        val later = before.value("startsAt").toLong() + 3_600_000
        launch(join("scorer").session)
        click("game-$source")
        click("team-schedule-edit")
        click("team-schedule-edit-date-manual")
        edit("team-schedule-edit-date", formatMatchDate(later, TimeZone.getDefault().id))
        edit("team-schedule-edit-venue", "Android Field C")
        shot("android-reschedule-form")
        click("team-schedule-commit")
        val updated = waitFor("updated Android schedule") {
            androidPublication().takeIf { it.value("revision") == "2" }
        }
        assertEquals(source, updated.value("id"))
        assertEquals(later, updated.value("startsAt").toLong())
        assertEquals("Android Field C", updated.value("venue"))
        awaitText("赛程已发布给球队成员")
        shot("android-rescheduled-detail")
    }

    @Test fun androidScorerCancelsTheSamePublishedSource() {
        val before = androidPublication()
        val source = before.value("id")
        assertEquals("2", before.value("revision"))
        launch(join("scorer").session)
        click("game-$source")
        click("team-schedule-cancel")
        shot("android-cancel-confirmation")
        click("team-schedule-confirm-cancel")
        val cancelled = waitFor("cancelled Android schedule") {
            androidPublication().takeIf { it.value("cancelled") == "true" }
        }
        assertEquals(source, cancelled.value("id"))
        assertEquals("3", cancelled.value("revision"))
        assertEquals(before.value("startsAt"), cancelled.value("startsAt"))
        awaitText("球队赛程已取消")
        assertTrue(localGames().single { it.id == source }.isScheduled)
        shot("android-cancelled-detail")
    }

    @Test fun androidScorerCanSaveAPlannedGameWithoutPublishingAnySchedule() {
        val membership = join("scorer")
        val venue = "Private field ${UUID.randomUUID().toString().take(6)}"
        val opponent = openDatabase { db ->
            GameRepository(db).createTeam("本机场地对手 ${venue.takeLast(6)}", "对手一,00", true)
        }
        launch(membership.session)
        click("new-game")
        click("setup-scheduled-toggle")
        click("setup-own-team")
        click("setup-shared-${membership.teamID}")
        awaitNode("setup-shared-source")
        click("setup-opponent")
        click("choose-roster-${opponent.id}")
        edit("setup-schedule-venue", venue)
        assertTrue(node("publish-team-schedule").isEnabled)
        assertText(node("start-game"), "仅保存比赛安排")
        shot("android-local-only-choice")

        click("start-game")
        val game = waitFor("local-only game") { localGames().singleOrNull { it.scheduleVenue == venue } }
        assertTrue(game.isScheduled)
        assertTrue(game.scheduledSetupPending)
        awaitText("赛程仅自己可见")
        assertTrue(receiptsFor(game.id).isEmpty())
        assertFalse(scheduleRows().any { it.value("id") == game.id })
        shot("android-local-only-detail")
    }

    private data class FixtureMembership(val teamID: String, val session: TeamDirectorySession)

    private fun join(role: String): FixtureMembership {
        val invite = control("GET", "join/android-$role")
        val token = invite.value("deviceSecret")
        val teamID = invite.value("teamID")
        val transport = TeamTransport { method, path, input -> request(method, "/api/v3/$path", token, input) }
        val storage = TeamDirectoryFile(File(context.noBackupFilesDir, "build30-isolated/$teamID/$role"),
            secret = { Base64.getUrlDecoder().decode(token) })
        val session = TeamDirectorySession(TeamDirectoryClient(transport), storage, "Build 30 Android $role")
        if (session.refresh().teams.none { it.id == teamID }) {
            session.prepareJoin(invite.value("token"), "Android $role ${UUID.randomUUID().toString().take(5)}")
            session.sendPending()
        }
        assertEquals(TeamRole.valueOf(role), session.refresh().teams.single { it.id == teamID }.role)
        return FixtureMembership(teamID, session)
    }

    private fun launch(session: TeamDirectorySession) {
        device.wakeUp()
        androidTeamDirectoryTestOverride = { session }
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("The app did not return to the four tabs", device.wait(Until.hasObject(By.res("tab-0")), 45_000))
        click("tab-0")
    }

    private fun node(tag: String): UiObject2 = NativeUi.find(device, By.res(tag))

    private fun click(tag: String) {
        node(tag).click()
        device.waitForIdle(400)
        UiTreeCache.refresh()
    }

    private fun awaitNode(tag: String) {
        waitFor(tag) { device.findObject(By.res(tag)) }
    }

    private fun awaitText(text: String) {
        waitFor(text) { device.findObject(By.text(text)) }
    }

    private fun assertText(node: UiObject2, value: String) {
        assertTrue("Expected $value in ${node.resourceName}",
            node.text.orEmpty().contains(value) || node.findObject(By.textContains(value)) != null)
    }

    private fun edit(tag: String, text: String) {
        node(tag).text = text
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(InputMethodManager::class.java)
            WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        device.waitForIdle(400)
    }

    private fun <T> waitFor(label: String, building: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            building()?.let { return it }
            SystemClock.sleep(150)
        }
        error("Timed out waiting for $label")
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        device.waitForIdle(400)
        val png = File(evidence, "$name.png")
        if (!device.takeScreenshot(png)) {
            val capture = instrumentation.uiAutomation.executeShellCommand("screencap -p")
            ParcelFileDescriptor.AutoCloseInputStream(capture).use { input ->
                png.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("No PNG for $name", png.length() > 1024)
        device.dumpWindowHierarchy(File(evidence, "$name.xml"))
    }

    private fun localGames() = openDatabase { db -> db.dao().allGames().map { GameRepository.decode(it) } }
    private fun <T> openDatabase(action: suspend (LocalDatabase) -> T): T = runBlocking {
        val db = LocalDatabase.open(context)
        try { action(db) } finally { db.close() }
    }

    private fun scheduleRows() = control("GET", "schedules").getValue("schedules").jsonArray.map { it.jsonObject }
    private fun androidPublication() = scheduleRows().single { it.value("venue").startsWith("Android Field") }
    private fun receiptsFor(id: String) = control("GET", "receipts").getValue("receipts").jsonArray
        .map { it.jsonObject }.filter { it.value("path").endsWith("/$id") }
    private fun control(method: String, path: String) = request(method, "/__fixture/$path")
    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content

    /**
     * Test-only cleartext for the local socket mapped by adb. Instrumentation uses
     * the product client, session, payload codec and Compose Activity above.
     */
    private fun request(method: String, path: String, credential: String? = null, body: JsonObject? = null): JsonObject {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 18788), 4_000)
                socket.soTimeout = 6_000
                val bytes = body?.toString()?.encodeToByteArray() ?: byteArrayOf()
                val headers = buildString {
                    append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:18788\r\nConnection: close\r\n")
                    if (credential != null) append("Authorization: Bearer $credential\r\nX-BM-Protocol: 1\r\n")
                    append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(headers.encodeToByteArray())
                    write(bytes)
                    flush()
                }
                val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                val headersEnd = raw.indexOf("\r\n\r\n")
                check(headersEnd >= 0)
                val status = raw.substringBefore("\r\n").split(' ')[1].toInt()
                val result = Json.parseToJsonElement(raw.substring(headersEnd + 4)).jsonObject
                if (status !in 200..299) {
                    val code = result["error"] as? JsonObject
                    throw TeamServiceFault(status, code?.get("code")?.jsonPrimitive?.content ?: "fixture_failure")
                }
                return result
            }
        } catch (fault: TeamServiceFault) {
            throw fault
        } catch (_: Exception) {
            throw TeamServiceFault(503, "fixture_transport_failure")
        }
    }
}
