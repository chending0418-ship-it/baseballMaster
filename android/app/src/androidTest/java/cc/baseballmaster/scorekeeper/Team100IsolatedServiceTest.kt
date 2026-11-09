package cc.baseballmaster.scorekeeper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.baseballmaster.core.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses adb reverse to a disposable collaboration service. No production traffic.
 * Only the test adapter uses HTTP; the installed app's HTTPS policy is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class Team100IsolatedServiceTest {
    private fun id() = UUID.randomUUID().toString()
    private class Memory : TeamDirectoryStorage {
        override val monitor = Any()
        var state = TeamDirectoryState()
        override fun load() = state
        override fun save(state: TeamDirectoryState) { this.state = state }
    }
    private val port = InstrumentationRegistry.getArguments().getString("teamTestPort")?.toIntOrNull()
    private fun raw(method: String, path: String, token: String?, body: JsonObject? = null): Pair<Int, JsonObject> {
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val header = buildString {
            append("$method /api/v3/$path HTTP/1.0\r\nHost: localhost\r\nX-BM-Protocol: 1\r\n")
            append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
            if (token != null) append("Authorization: Bearer $token\r\n")
            append("Connection: close\r\n\r\n")
        }
        val result = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", requireNotNull(port)), 3000)
            socket.soTimeout = 5000
            socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            socket.getInputStream().readBytes().decodeToString()
        }
        val status = result.substringBefore("\r\n").split(' ')[1].toInt()
        return status to GameJson.parseToJsonElement(result.substringAfter("\r\n\r\n")).jsonObject
    }
    private class Device(val session: TeamDirectorySession, val transport: TeamTransport)
    private fun device(dropResponse: (String, String) -> Boolean = { _, _ -> false }): Device {
        val token = TeamSecrets.random()
        val transport = TeamTransport { method, path, body ->
            val (status, reply) = raw(method, path, token, body)
            if (status !in 200..299) throw TeamServiceFault(status, reply["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "service_error")
            if (dropResponse(method, path)) throw IOException("simulated lost response")
            reply
        }
        return Device(TeamDirectorySession(TeamDirectoryClient(transport), Memory(), "模拟器测试设备"), transport)
    }
    private fun join(owner: Device, team: String, other: Device, role: TeamRole) {
        owner.session.refresh()
        owner.session.prepareInvite(team, role)
        val invite = owner.session.sendPending().request.body.getValue("token").jsonPrimitive.content
        other.session.refresh()
        other.session.prepareJoin(invite, "模拟成员")
        other.session.sendPending()
        other.session.refresh()
    }

    @Test fun rosterBatchIsAtomicAndLostResponseRetriesOriginalOperation() {
        assumeTrue("Pass teamTestPort with adb reverse for the isolated fixture", port != null)
        var lose = true
        val owner = device { method, path ->
            if (lose && method == "PATCH" && path.endsWith("/roster")) { lose = false; true } else false
        }
        owner.session.refresh()
        owner.session.prepareCreate("模拟批量名单", "管理员")
        val team = owner.session.sendPending().response.getValue("teamID").jsonPrimitive.content.lowercase()
        val before = owner.session.roster(team)
        val text = "合成球员甲,Alpha,0/00\n\n合成球员乙,8"
        val rows = Roster.previewAppend(text, emptyList())
        assertEquals(listOf(1, 3), rows.map { it.line })
        assertEquals(before, owner.session.roster(team))
        val changes = rows.map { (_, p) -> SharedRosterPlayer.new(p.chineseName.orEmpty(), p.englishName, p.jerseyNumbers) }
        val operation = owner.session.preparePlayers(team, before.revision, changes)
        assertThrows(IOException::class.java) { owner.session.sendPending() }
        assertEquals(operation, owner.session.snapshot().pending?.id)
        assertEquals(operation, owner.session.sendPending().request.id)
        val after = owner.session.roster(team, true)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(changes.map { it.sourceID.lowercase() }.toSet(), after.players.map { it.sourceID.lowercase() }.toSet())
        assertEquals(listOf("0", "00"), after.players.single { it.englishName == "Alpha" }.numbers)
        val member = device()
        join(owner, team, member, TeamRole.member)
        assertThrows(IllegalArgumentException::class.java) {
            member.session.preparePlayers(team, after.revision, listOf(SharedRosterPlayer.new("只读角色", "", listOf("9"))))
        }
        assertEquals(after, owner.session.roster(team, true))
    }

    @Test fun fixedLinksKeepOneCodeAndSwitchOnlyAfterConfirmationThenFenceOldWriter() {
        assumeTrue("Pass teamTestPort with adb reverse for the isolated fixture", port != null)
        val fixture = InstrumentationRegistry.getArguments().getString("broadcastFixture")
        assumeTrue(fixture != null)
        val owner = device()
        val scorer = device()
        val localHome = Team(id(), "合成主队", (1..9).map { Player(id(), "合成主队$it", "$it") })
        val localAway = Team(id(), "合成客队", (1..9).map { Player(id(), "合成客队$it", "$it") })
        owner.session.refresh()
        owner.session.prepareCreate(localHome, "管理员")
        val team = owner.session.sendPending().response.getValue("teamID").jsonPrimitive.content.lowercase()
        join(owner, team, scorer, TeamRole.scorer)
        val source = owner.session.broadcastSources(team)
        val code = requireNotNull(source.code)
        assertTrue(BroadcastSource.entries.all { it.url(code).endsWith("#team/$code") })
        assertEquals(4, BroadcastSource.entries.map { it.url(code) }.toSet().size)
        assertFalse(raw("GET", "broadcast/$code", null).second.getValue("active").jsonPrimitive.boolean)
        val pregame = MatchFactory.create(id(), localHome, localAway, Rules(), localHome.id)
        assertNull(owner.session.publishLocalBroadcast(pregame, BroadcastSelection(null)))
        val game = MatchEngine.apply(pregame, Command("pitch", pitch = PitchAction.calledStrike))
        assertNull(owner.session.resumeLocalBroadcast(game))
        assertFalse(raw("GET", "broadcast/$code", null).second.getValue("active").jsonPrimitive.boolean)
        assertTrue(owner.session.publishLocalBroadcast(game, BroadcastSelection(null))?.selected == true)
        assertEquals(game.id, raw("GET", "broadcast/$code", null).second.getValue("snapshot").jsonObject.getValue("gameID").jsonPrimitive.content)
        val otherMatch = MatchEngine.apply(pregame.copy(id = id()), Command("pitch", pitch = PitchAction.ball))
        assertNull(owner.session.resumeLocalBroadcast(otherMatch))
        assertEquals(game.id, owner.session.broadcastSources(team).activeGameID)
        val nextPitch = MatchEngine.apply(game, Command("pitch", pitch = PitchAction.ball))
        assertTrue(owner.session.resumeLocalBroadcast(nextPitch)?.selected == true)
        assertEquals(nextPitch.revision, raw("GET", "broadcast/$code", null).second.getValue("snapshot").jsonObject.getValue("revision").jsonPrimitive.long)

        val template = GameJson.parseToJsonElement(java.util.Base64.getDecoder().decode(requireNotNull(fixture)).decodeToString()).jsonObject
        val body = JsonObject(template + ("operationID" to JsonPrimitive(id())))
        val created = SharedMatchDocument(owner.transport.request("POST", "teams/$team/games", body))
        val document = owner.session.match(team, created.id)
        val wireID = requireNotNull(document.payload).getValue("id").jsonPrimitive.content.lowercase()
        assertFalse(owner.session.publishSharedBroadcast(team, document.id, BroadcastSelection(id()))?.selected ?: true)
        assertTrue(owner.session.publishSharedBroadcast(team, document.id, BroadcastSelection(game.id))?.selected == true)
        assertEquals(wireID, owner.session.broadcastSources(team).activeGameID)
        val previous = owner.session.match(team, document.id)
        val previousStream = raw("GET", "broadcast/$code", null).second.getValue("revision").jsonPrimitive.content
        owner.session.prepareMatchControl(previous, MatchControl.propose, scorer.session.snapshot().deviceID)
        owner.session.sendMatchControl(document.id)
        assertNull(owner.session.publishSharedBroadcast(team, document.id))
        scorer.session.prepareMatchControl(scorer.session.match(team, document.id), MatchControl.accept)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = LocalDatabase.open(context, "broadcast-test-${id()}.db")
        try {
            kotlinx.coroutines.runBlocking { AndroidTeamSharing(GameRepository(db), scorer.session).sendHandoff(document.id) }
        } finally {
            val name = db.openHelper.databaseName
            db.close()
            if (name != null) context.deleteDatabase(name)
        }
        assertNotEquals(previousStream, raw("GET", "broadcast/$code", null).second.getValue("revision").jsonPrimitive.content)
        assertNull(owner.session.publishSharedBroadcast(team, document.id))
        val delayed = buildJsonObject {
            put("writerEpoch", previous.writerEpoch)
            put("snapshot", previous.raw.getValue("projection").jsonObject.getValue("snapshot"))
        }
        assertEquals(409, assertThrows(TeamServiceFault::class.java) {
            owner.transport.request("PUT", "teams/$team/broadcast", delayed)
        }.status)
        val newCode = requireNotNull(owner.session.rotateBroadcast(team, code).code)
        assertNotEquals(code, newCode)
        assertEquals(404, raw("GET", "broadcast/$code", null).first)
        assertTrue(raw("GET", "broadcast/$newCode", null).second.getValue("active").jsonPrimitive.boolean)
    }
}
