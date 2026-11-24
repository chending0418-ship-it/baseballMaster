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
    @After fun clearFixture() { androidTeamDirectoryTestOverride = null }
    @Test fun androidLineupWithIOSScorer() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("fixtureToken")
        assumeNotNull(token)
        val teamID = requireNotNull(args.getString("team"))
        val partner = requireNotNull(args.getString("partner"))
        val transport = TeamTransport { method,path,body ->
            // HTTP/1.0 on an adb-reversed loopback socket, independent of release network config.
            Socket("127.0.0.1",18786).use { socket ->
                socket.soTimeout = 8000
                val bytes = body?.toString()?.encodeToByteArray() ?: byteArrayOf()
                val header = "$method /api/v3/$path HTTP/1.0\r\nHost: localhost\r\nAuthorization: Bearer $token\r\nX-BM-Protocol: 1\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                socket.getOutputStream().apply { write(header.encodeToByteArray()); write(bytes); flush() }
                val response = socket.getInputStream().bufferedReader().readText()
                val data = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
                val status = response.substringBefore("\r\n").split(' ')[1].toInt()
                if (status !in 200..299) throw TeamServiceFault(status, data["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "service_error")
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
        fun team(id: String, name: String) = Team(id, name, (1..9).map { Player(UUID.randomUUID().toString(),"队员$it",it.toString()) })
        val game = MatchFactory.create(UUID.randomUUID().toString(), team(UUID.randomUUID().toString(),"主队"), team(teamID,"客队"),
            Rules(), teamID, System.currentTimeMillis() + 3600000L)
        android.publishSchedule(teamID,game.id,game.scheduledAt!!,game.away.name,game.home.name,"双端验证")
        val notice = android.teamSchedules().notices.single()
        val unassigned = android.scoringDetail(notice)
        assertEquals("scorer",unassigned.access.role)
        android.prepareSchedule(notice,unassigned,game)
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
    }
}
