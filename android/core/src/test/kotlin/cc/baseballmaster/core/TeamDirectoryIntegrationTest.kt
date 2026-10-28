package cc.baseballmaster.core

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the repository's real Node/SQLite service on loopback, never production. */
class TeamDirectoryIntegrationTest {
    private class Memory : TeamDirectoryStorage {
        override val monitor = Any()
        var state = TeamDirectoryState()
        var reject: (TeamDirectoryState) -> Boolean = { false }
        override fun load() = state
        override fun save(state: TeamDirectoryState) { if (reject(state)) throw IOException("injected write failure"); this.state = state }
    }
    private class Service : AutoCloseable {
        private val script = File(System.getProperty("parity.fixtures")).parentFile.parentFile.resolve("scripts/android/an05-local-service.mjs")
        private val process = ProcessBuilder("node", script.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val output = process.inputStream.bufferedReader()
        private val input = process.outputStream.bufferedWriter()
        val endpoint = "http://127.0.0.1:" + GameJson.parseToJsonElement(requireNotNull(output.readLine())).jsonObject.getValue("port").jsonPrimitive.int + "/api/v3"
        fun change(kind: String, teamID: String? = null) {
            input.write(buildJsonObject { put("kind", kind); teamID?.let { put("teamID", it) } }.toString()); input.newLine(); input.flush()
            assertEquals("true", GameJson.parseToJsonElement(output.readLine()).jsonObject.getValue("updated").jsonPrimitive.content)
        }
        fun transport(secret: String = TeamSecrets.random()) = TeamHttpTransport(endpoint, TeamCredentialProvider { secret }, allowLoopbackHttp = true)
        override fun close() { input.close(); if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); output.close() }
    }

    @Test(timeout = 30000) fun lostCreationResponseRestartsWithTheSameIDAndNeverDuplicatesTeams() {
        Service().use { server ->
            val transport = server.transport(); val store = Memory(); var drop = true; var mutations = 0
            val faulty = TeamTransport { method, path, body ->
                val response = transport.request(method, path, body)
                if (method == "POST" && path == "teams") { mutations++; if (drop) { drop = false; throw IOException("injected response loss") } }
                response
            }
            val session = TeamDirectorySession(TeamDirectoryClient(faulty), store, "安卓设备")
            session.refresh(); val id = session.prepareCreate("同名球队", "管理员")
            val request = store.state.pending!!
            assertThrows(IOException::class.java) { session.sendPending() }
            assertEquals(id, store.state.pending!!.id)
            assertThrows(IllegalStateException::class.java) { session.prepareCreate("修改正文", "管理员") }
            val restarted = TeamDirectorySession(TeamDirectoryClient(faulty), store, "安卓设备")
            val completed = restarted.sendPending()
            assertEquals(request, completed.request); assertNull(store.state.pending)
            TeamSecrets.validate(completed.request.body.getValue("recoveryToken").jsonPrimitive.content)
            assertEquals(1, restarted.refresh().teams.size); assertEquals(2, mutations)
            restarted.prepareCreate("同名球队", "管理员"); restarted.sendPending()
            val teams = restarted.refresh().teams
            assertEquals(2, teams.size); assertNotEquals(teams[0].id, teams[1].id)
            assertEquals(2, store.state.retainedSecrets.size)
            assertEquals(completed, store.state.retainedSecrets.first())
            assertFalse(completed.toString().contains(request.body.getValue("recoveryToken").jsonPrimitive.content))
        }
    }

    @Test(timeout = 30000) fun persistBeforeSendingAndPersistingReceiptFailureBothProtectThePendingRequest() {
        Service().use { server ->
            val store = Memory(); val session = TeamDirectorySession(TeamDirectoryClient(server.transport()), store, "安卓")
            session.refresh(); store.reject = { it.pending != null }
            assertThrows(IOException::class.java) { session.prepareCreate("不应发出", "管理员") }
            assertNull(store.state.pending); assertTrue(session.refresh().teams.isEmpty())
            store.reject = { false }; val id = session.prepareCreate("写入回执失败", "管理员")
            store.reject = { it.lastCompletion != null }
            assertThrows(IOException::class.java) { session.sendPending() }
            assertEquals(id, store.state.pending!!.id)
            store.reject = { false }; session.sendPending()
            assertEquals(1, session.refresh().teams.size)
        }
    }

    @Test(timeout = 30000) fun invitationPreviewDoesNotJoinAndRevocationIsEnforcedByTheExistingServer() {
        Service().use { server ->
            val adminTransport = server.transport(); val adminStore = Memory()
            val admin = TeamDirectorySession(TeamDirectoryClient(adminTransport), adminStore, "管理设备")
            admin.refresh(); admin.prepareCreate("共同球队", "管理员"); admin.sendPending(); val team = admin.refresh().teams.single()
            admin.prepareInvite(team.id.uppercase(), TeamRole.scorer); val invitation = admin.sendPending()
            val token = invitation.request.body.getValue("token").jsonPrimitive.content
            val scorerTransport = server.transport(); val client = TeamDirectoryClient(scorerTransport); val state = Memory()
            val scorer = TeamDirectorySession(client, state, "安卓记录员")
            scorer.refresh(); assertEquals(team.id, client.preview(token).teamID); assertTrue(scorer.refresh().teams.isEmpty())
            scorer.prepareJoin(token, "同名成员"); scorer.sendPending(); val joined = scorer.refresh().teams.single()
            assertEquals(TeamRole.scorer, joined.role)
            assertThrows(IllegalArgumentException::class.java) { scorer.prepareInvite(team.id, TeamRole.member) }
            val denied = assertThrows(TeamServiceFault::class.java) {
                scorerTransport.request("POST", "teams/${team.id}/invitations", buildJsonObject { put("operationID", UUID.randomUUID().toString()); put("role", "member"); put("token", TeamSecrets.random()) })
            }
            assertEquals(403, denied.status)
            adminTransport.request("PATCH", "teams/${team.id}/members/${joined.memberID}", buildJsonObject { put("operationID", UUID.randomUUID().toString()); put("baseRevision", 1); put("role", "member"); put("active", false) })
            assertTrue(scorer.refresh().teams.isEmpty())
            assertEquals(404, assertThrows(TeamServiceFault::class.java) { scorerTransport.request("GET", "teams/${team.id}", null) }.status)
        }
    }

    @Test(timeout = 30000) fun changedRecoveryEpochStopsOldRequestsAcrossRestartWithoutDroppingThem() {
        Service().use { server ->
            val store = Memory(); val client = TeamDirectoryClient(server.transport()); val session = TeamDirectorySession(client, store, "安卓")
            session.refresh(); val id = session.prepareCreate("恢复前请求", "管理员"); val before = store.state.pending
            server.change("recovery")
            assertThrows(IllegalStateException::class.java) { session.sendPending() }
            assertTrue(store.state.requiresRecoveryReview); assertEquals(before, store.state.pending)
            assertTrue(session.refresh().teams.isEmpty())
            val other = TeamDirectorySession(client, store, "安卓")
            assertThrows(IllegalStateException::class.java) { other.sendPending() }
            assertEquals(id, other.snapshot().pending!!.id)
        }
    }

    @Test(timeout = 30000) fun changedTeamAccessEpochStopsAnInvitationBeforeItIsSent() {
        Service().use { server ->
            val store = Memory(); val session = TeamDirectorySession(TeamDirectoryClient(server.transport()), store, "安卓")
            session.refresh(); session.prepareCreate("测试球队", "管理员"); session.sendPending(); val team = session.refresh().teams.single()
            val id = session.prepareInvite(team.id, TeamRole.member)
            server.change("access", team.id)
            assertThrows(IllegalStateException::class.java) { session.sendPending() }
            assertEquals(id, store.state.pending!!.id)
        }
    }

    @Test(timeout = 30000) fun twoSessionsCannotSendTheSameLocalPendingRequestConcurrently() {
        Service().use { server ->
            val store = Memory(); val client = TeamDirectoryClient(server.transport())
            val first = TeamDirectorySession(client, store, "安卓"); val second = TeamDirectorySession(client, store, "安卓")
            first.refresh(); first.prepareCreate("单次提交", "管理员")
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val results = executor.invokeAll(listOf(first,second).map { session -> java.util.concurrent.Callable { runCatching { session.sendPending() } } }).map { it.get() }
                assertEquals(1, results.count { it.isSuccess }); assertEquals(1, results.count { it.isFailure })
                assertNull(store.state.pending); assertEquals(1, first.refresh().teams.size)
            } finally { executor.shutdownNow() }
        }
    }

    @Test(timeout = 30000) fun lostLeaveResponseCanBeRetrievedWithoutRestoringMembership() {
        Service().use { server ->
            val transport = server.transport(); var drop = true
            val adminStore = Memory(); val admin = TeamDirectorySession(TeamDirectoryClient(transport), adminStore, "管理设备")
            admin.refresh(); admin.prepareCreate("退出回执", "管理员"); admin.sendPending(); val team = admin.refresh().teams.single()
            admin.prepareInvite(team.id, TeamRole.member); val invite = admin.sendPending().request.body.getValue("token").jsonPrimitive.content
            val memberTransport = server.transport()
            val memberClient = TeamDirectoryClient(TeamTransport { method, path, body ->
                // Only the member's leave response is dropped.
                if (path.endsWith("/leave")) {
                    val response = memberTransport.request(method,path,body)
                    if (drop) { drop = false; throw IOException("injected leave response loss") }; response
                } else memberTransport.request(method,path,body)
            })
            val store = Memory(); val member = TeamDirectorySession(memberClient, store, "成员设备")
            member.refresh(); member.prepareJoin(invite,"成员"); member.sendPending(); member.refresh()
            val id = member.prepareLeave(team.id)
            assertThrows(IOException::class.java) { member.sendPending() }
            assertTrue(member.refresh().teams.isEmpty()); assertEquals(id, store.state.pending!!.id)
            assertEquals(id, TeamDirectorySession(memberClient,store,"成员设备").sendPending().request.id)
            assertTrue(member.refresh().teams.isEmpty())
            assertEquals(404, assertThrows(TeamServiceFault::class.java) { memberTransport.request("GET", "teams/${team.id}", null) }.status)
        }
    }
}
