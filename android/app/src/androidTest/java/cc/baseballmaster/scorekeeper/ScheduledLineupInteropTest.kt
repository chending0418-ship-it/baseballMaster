package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Configurator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import java.io.File
import java.net.Socket
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The other device runs the iOS 3.1.4 scoring test against the same ephemeral service. */
@RunWith(AndroidJUnit4::class)
class ScheduledLineupInteropTest {
    private inline fun <T> LocalDatabase.withDatabase(block: (LocalDatabase) -> T): T = try { block(this) } finally { close() }
    @After fun clearFixture() { androidTeamDirectoryTestOverride = null }
    @Test fun androidLineupWithIOSScorer() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("fixtureToken")
        assumeNotNull(token)
        val teamID = requireNotNull(args.getString("team"))
        val partner = requireNotNull(args.getString("partner"))
        var offline = false
        var dropReply: String? = null
        val transport = TeamTransport { method,path,body ->
            if (offline) throw java.io.IOException("测试设备离线")
            // HTTP/1.0 on an adb-reversed loopback socket, independent of release network config.
            Socket("127.0.0.1",args.getString("fixturePort")?.toInt() ?: 18786).use { socket ->
                socket.soTimeout = 8000
                val bytes = body?.toString()?.encodeToByteArray() ?: byteArrayOf()
                val header = "$method /api/v3/$path HTTP/1.0\r\nHost: localhost\r\nAuthorization: Bearer $token\r\nX-BM-Protocol: 1\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                socket.getOutputStream().apply { write(header.encodeToByteArray()); write(bytes); flush() }
                val response = socket.getInputStream().bufferedReader().readText()
                val data = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
                val status = response.substringBefore("\r\n").split(' ')[1].toInt()
                if (status !in 200..299) throw TeamServiceFault(status, data["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "service_error")
                if (dropReply != null && path.endsWith("/" + dropReply)) {
                    dropReply = null
                    throw java.io.IOException("测试：服务已提交，连接断开")
                }
                data
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = TeamDirectoryFile(File(context.noBackupFilesDir, "isolated-086/$teamID"), secret = {
            java.util.Base64.getUrlDecoder().decode(token)
        })
        fun session() = TeamDirectorySession(TeamDirectoryClient(transport),directory,"Android 0.8.6")
        val android = session()
        val member = android.refresh().teams.single { it.id == teamID }
        if (args.getString("writer") == "1") {
            runBlocking {
                val notice = (1..240).firstNotNullOfOrNull {
                    android.teamSchedules().notices.firstOrNull { it.scoring?.openingReady == true } ?: run { Thread.sleep(300); null }
                } ?: error("等待阵容员确认先发超时")
                androidTeamDirectoryTestOverride = { android }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                Configurator.getInstance().setWaitForIdleTimeout(300)
                device.wakeUp()
                context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                NativeUi.find(device,By.res("team-schedule-${notice.id.lowercase()}")).click()
                NativeUi.find(device,By.text("确认双方先发并记局面")).click()
                NativeUi.find(device,By.res("pitch-ball")).click()
                NativeUi.find(device,By.res("confirm-opening-pitch")).click()
                assertEquals("1",NativeUi.find(device,By.res("count-坏球")).text)
                device.pressHome()
                val dbName = "baseballmaster.db"
                LocalDatabase.open(context,dbName).withDatabase { db ->
                    val writer = ScheduleWriter(db,android)
                    val repo = GameRepository(db,writerIdentity=android::snapshot)
                    val game = writer.enter(notice)
                    assertEquals(1,game.balls)
                    offline = true
                    val walked = repo.command(game.id,Command("intentionalWalk"),game.revision)
                    assertNotNull(walked.bases.first())
                    assertTrue(runCatching { writer.sync(notice) }.exceptionOrNull() is java.io.IOException)
                    assertNotNull(ScheduleWriter(db,session()).cached(game.id))
                    offline = false
                    dropReply = "frame"
                    assertTrue(runCatching { writer.sync(notice) }.exceptionOrNull() is java.io.IOException)
                    ScheduleWriter(db,session()).sync(notice)
                    var substituted: Game? = null
                    for (i in 1..220) {
                        writer.sync(notice)
                        val current = GameRepository.decode(requireNotNull(db.dao().game(game.id)))
                        if (current.away.players.find { it.id == current.bases.first() }?.name == "临场代跑") { substituted=current; break }
                        Thread.sleep(300)
                    }
                    val gameWithRunner = requireNotNull(substituted) { "等待临场代跑超时" }
                    val runner = requireNotNull(gameWithRunner.bases.first())
                    val scored = repo.command(game.id,Command("runner",runnerKind="wildPitch",decisions=listOf(Decision(runner,1,4))),gameWithRunner.revision)
                    assertEquals(1,scored.awayRuns)
                    assertEquals(1,scored.batting[runner]?.runs)
                    writer.sync(notice)
                    if (args.getString("handoff") == "1") {
                        val pitchProposal = requireNotNull((1..180).firstNotNullOfOrNull {
                            android.scoringDetail(notice,false).pending ?: run { Thread.sleep(300); null }
                        })
                        val beforeSwitch = repo.pitch(game.id,PitchAction.calledStrike,scored.revision)
                        val oldPitcher = requireNotNull(scored.homePitcherID)
                        assertEquals(oldPitcher,beforeSwitch.entries.last().pitcherID)
                        assertTrue(runCatching { writer.sync(notice) }.exceptionOrNull() is ScheduleLineupReview)
                        writer.sync(notice,accept=pitchProposal)
                        val switched = GameRepository.decode(requireNotNull(db.dao().game(game.id)))
                        assertNotEquals(oldPitcher,switched.homePitcherID)
                        val foul = repo.pitch(game.id,PitchAction.foul,switched.revision)
                        assertEquals(switched.homePitcherID,foul.entries.last().pitcherID)
                        assertEquals(1,foul.pitching[switched.homePitcherID]?.pitches)
                        writer.sync(notice)
                        check((1..200).any { if (android.scoringDetail(notice,false).access.transfer != null) true else { Thread.sleep(300); false } })
                        writer.release(notice)
                        assertNull(writer.cached(game.id))
                    } else {
                        val observed = File(context.filesDir,"observed-${notice.teamID}")
                        check((1..240).any { if (observed.exists()) true else { Thread.sleep(350); false } }) { "等待 iOS 核对得分超时" }
                        val final = repo.command(game.id,Command("finish",text="scorerDecision"),scored.revision)
                        assertTrue(final.isFinal)
                        writer.sync(notice)
                        assertTrue(android.scoringDetail(notice,false).access.closed)
                    }
                }
            }
            return
        }
        fun team(id: String, name: String) = Team(id, name, (1..9).map { Player(UUID.randomUUID().toString(),"队员$it",it.toString()) })
        val game = MatchFactory.create(UUID.randomUUID().toString(), team(UUID.randomUUID().toString(),"主队"), team(teamID,"客队"),
            Rules(), teamID, System.currentTimeMillis() + 3600000L)
        android.publishSchedule(teamID,game.id,game.scheduledAt!!,game.away.name,game.home.name,"双端验证")
        val notice = android.teamSchedules().notices.single()
        val unassigned = android.scoringDetail(notice)
        assertEquals("scorer",unassigned.access.role)
        android.prepareSchedule(notice,unassigned,game)
        if (args.getString("handoff") == "1") {
            android.assignScorers(notice,unassigned.access.epoch,partner,member.memberID)
            val assigned = android.scoringDetail(notice,false)
            android.claimLineup(notice,assigned.access.epoch)
            android.confirmLineup(notice,android.scoringDetail(notice,false))
        } else {
            androidTeamDirectoryTestOverride = { android }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            Configurator.getInstance().setWaitForIdleTimeout(350)
            device.wakeUp()
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            NativeUi.find(device, By.res("team-schedule-${notice.id.lowercase()}")).click()
            fun click(text: String) = NativeUi.find(device, By.text(text)).click()
            click("安排记录员"); click("待指派"); click("局面员 · " + partner.takeLast(4))
            click("由局面员兼任"); click("阵容员 · " + member.memberID.takeLast(4))
            click("保存指派")
            click("在此设备领取阵容岗位")
            click("确认双方先发")
        }
        fun await(check: (ScheduleScoringDetail) -> Boolean): ScheduleScoringDetail {
            repeat(240) {
                val detail = android.scoringDetail(notice,false)
                if (check(detail)) return detail
                Thread.sleep(350)
            }
            error("等待 iOS 岗位超时")
        }
        val current = await { it.frame?.editingView(game.id)?.bases?.firstOrNull() != null }
        val view = requireNotNull(current.frame).editingView(game.id)
        val outgoing = requireNotNull(view.bases.first())
        val late = Player(UUID.randomUUID().toString(),"临场代跑","00",chineseName="临场代跑")
        val change = Command("lineup", homeSide=false, benchAdditions=listOf(late),
            order=view.awayOrder!!.map { if (it == outgoing) late.id else it },
            positions=view.awayPositions!!.mapValues { if (it.value == outgoing) late.id else it.value },
            designatedHitterID=view.awayDesignatedHitterID, lineupReplacements=mapOf(outgoing to late.id))
        android.saveLineupDraft(notice,current,change)
        val restored = session()
        restored.sendLineupDraft(notice)
        restored.sendLineupDraft(notice)
        val scored = await { it.receipt?.applied == true && it.frame?.editingView(game.id)?.awayRuns == 1 }
        val players = scored.frame!!.editingView(game.id).away.players
        assertEquals("00",players.single { it.id == late.id }.number)
        assertEquals(10,players.size)
        assertEquals(9,game.away.players.size)
        if (args.getString("handoff") == "1") runBlocking {
            if (args.getString("roundtrip") != "1") {
                val currentGame = scored.frame!!.editingView(game.id)
                val pitcher = requireNotNull(currentGame.homePitcherID)
                val replacement = Player(UUID.randomUUID().toString(),"接替投手","98",position=1)
                val changePitcher = Command("lineup",homeSide=true,benchAdditions=listOf(replacement),
                    order=currentGame.homeOrder!!.map { if (it == pitcher) replacement.id else it },
                    positions=currentGame.homePositions!!.mapValues { if (it.value == pitcher) replacement.id else it.value },
                    lineupReplacements=mapOf(pitcher to replacement.id))
                android.saveLineupDraft(notice,scored,changePitcher)
                android.sendLineupDraft(notice)
                await { it.receipt?.id == it.draft?.id && it.receipt?.applied == true && it.frame?.editingView(game.id)?.strikes == 2 }
            }
            android.assignScorers(notice,scored.access.epoch,member.memberID,null)
            await { it.access.hasCheckpoint && it.access.role == "scorer" }
            val dbName = "recipient-${notice.id}.db"
            val originalIDs = LocalDatabase.open(context,dbName).withDatabase { db ->
                val writer = ScheduleWriter(db,android)
                dropReply = "received"
                assertTrue(runCatching { writer.enter(notice) }.exceptionOrNull() is java.io.IOException)
                val recovered = ScheduleWriter(db,session()).enter(notice)
                val expected = if (args.getString("roundtrip") == "1") listOf("intentionalWalk","lineup","runner")
                    else listOf("pitch","intentionalWalk","lineup","runner","pitch","lineup","pitch")
                assertEquals(expected,recovered.journal.map { it.command.kind })
                assertEquals(1,recovered.awayRuns)
                assertEquals(1,recovered.batting[late.id]?.runs)
                assertTrue(recovered.bases.all { it == null })
                recovered.journal.map { it.id }
            }
            LocalDatabase.open(context,dbName).withDatabase { db ->
                val writer = ScheduleWriter(db,android)
                val continued = writer.enter(notice)
                val next = GameRepository(db,writerIdentity=android::snapshot).pitch(continued.id,PitchAction.ball,continued.revision)
                assertEquals(originalIDs,next.journal.dropLast(1).map { it.id })
                assertEquals(1,next.balls)
                writer.sync(notice)
                val synced = android.scoringDetail(notice,false)
                assertEquals(1,synced.frame!!.editingView(game.id).balls)
                if (args.getString("roundtrip") == "1") {
                    android.assignScorers(notice,synced.access.epoch,partner,null)
                    writer.release(notice)
                    assertNull(writer.cached(game.id))
                } else {
                    val final = GameRepository(db,writerIdentity=android::snapshot).command(next.id,Command("finish",text="scorerDecision"),next.revision)
                    assertEquals(1,final.awayRuns)
                    writer.sync(notice)
                    assertTrue(android.scoringDetail(notice,false).access.closed)
                }
            }
        }

    }
}
