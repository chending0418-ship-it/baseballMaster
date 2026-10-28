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
        fun matchFixture(): JsonObject {
            input.write("{\"kind\":\"fixture\"}"); input.newLine(); input.flush()
            return GameJson.parseToJsonElement(output.readLine()).jsonObject.getValue("document").jsonObject
        }
        fun transport(secret: String = TeamSecrets.random()) = TeamHttpTransport(endpoint, TeamCredentialProvider { secret }, allowLoopbackHttp = true)
        override fun close() { input.close(); if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); output.close() }
    }
    private fun session(transport: TeamTransport, store: Memory = Memory()) = TeamDirectorySession(TeamDirectoryClient(transport),store,"安卓测试设备")
    private fun create(session: TeamDirectorySession): RemoteTeam {
        session.refresh(); session.prepareCreate("共享名单测试","管理员"); session.sendPending(); return session.refresh().teams.single()
    }
    private fun join(admin: TeamDirectorySession, team: RemoteTeam, member: TeamDirectorySession, role: TeamRole = TeamRole.scorer) {
        admin.prepareInvite(team.id,role); val invite = admin.sendPending().request.body.getValue("token").jsonPrimitive.content
        member.refresh(); member.prepareJoin(invite,"同名成员"); member.sendPending(); member.refresh()
    }
    private fun publishFixture(server: Service, transport: TeamTransport, team: RemoteTeam): SharedMatchDocument {
        val body=JsonObject(server.matchFixture() + ("operationID" to JsonPrimitive(UUID.randomUUID().toString())))
        return SharedMatchDocument(transport.request("POST","teams/${team.id}/games",body))
    }

    @Test(timeout = 30000) fun fullOpaqueMatchAndCursorKeepWireIdentityAndUnknownHistoryFields() {
        Service().use { server ->
            val transport=server.transport(); val store=Memory(); val owner=session(transport,store); val team=create(owner)
            val created=publishFixture(server,transport,team)
            assertEquals(created.id,owner.matches(team.id).single().id)
            val full=owner.match(team.id,created.id)
            assertNotEquals(full.id,full.payload!!.getValue("id").jsonPrimitive.content)
            assertEquals(listOf("original-event","audit-checkpoint"),full.payload!!.getValue("opaqueFutureHistory").jsonObject.getValue("retained").jsonArray.map { it.jsonPrimitive.content })
            val cursor=store.state.matchCursors.getValue(team.id); assertTrue(cursor > 0)
            assertEquals(full.payload,owner.matches(team.id).single().payload); assertEquals(cursor,store.state.matchCursors.getValue(team.id))
        }
    }

    @Test(timeout = 30000) fun handoffAcceptSavesTheFullDocumentAndChangesWriterWithoutRewritingPayload() {
        Service().use { server ->
            val transport=server.transport(); val ownerStore=Memory(); val owner=session(transport,ownerStore); val team=create(owner)
            val targetStore=Memory(); val target=session(server.transport(),targetStore); join(owner,team,target)
            val created=publishFixture(server,transport,team); val before=owner.match(team.id,created.id)
            val targetID=targetStore.state.deviceID!!
            assertTrue(owner.matchParticipants(team.id,created.id).any { it.jsonObject.getValue("deviceID").jsonPrimitive.content == targetID })
            owner.prepareMatchControl(before,MatchControl.propose,targetID); owner.sendMatchControl(created.id)
            val offered=target.match(team.id,created.id); assertFalse(owner.match(team.id,created.id).canWrite)
            val op=target.prepareMatchControl(offered,MatchControl.accept)
            assertEquals(before.payload,targetStore.state.matches.getValue(created.id).document.payload)
            assertEquals(op,target.sendMatchControl(created.id).request.id)
            val current=target.match(team.id,created.id)
            assertEquals(2,current.writerEpoch); assertEquals(targetID,current.writerDeviceID); assertTrue(current.canWrite)
            assertEquals(before.payload,current.payload); assertFalse(owner.match(team.id,created.id).canWrite)
        }
    }

    @Test(timeout = 30000) fun lostAcceptResponseKeepsTheSameOperationAcrossRestart() {
        Service().use { server ->
            val transport=server.transport(); val owner=session(transport); val team=create(owner)
            val targetTransport=server.transport(); val store=Memory(); var drop=true
            val client=TeamTransport { method,path,body ->
                val response=targetTransport.request(method,path,body)
                if (path.endsWith("handoff/accept") && drop) { drop=false; throw IOException("injected accept loss") }; response
            }
            val target=session(client,store); join(owner,team,target)
            val created=publishFixture(server,transport,team)
            owner.prepareMatchControl(owner.match(team.id,created.id),MatchControl.propose,store.state.deviceID); owner.sendMatchControl(created.id)
            val id=target.prepareMatchControl(target.match(team.id,created.id),MatchControl.accept)
            assertThrows(IOException::class.java) { target.sendMatchControl(created.id) }
            assertEquals(id,store.state.matchPending.getValue(created.id).id)
            val restarted=session(client,store); assertEquals(id,restarted.sendMatchControl(created.id).request.id)
            assertEquals(2,restarted.match(team.id,created.id).writerEpoch)
            assertTrue(store.state.matchPending.isEmpty())
        }
    }

    @Test(timeout = 30000) fun controlSaveFailureSendsNothingAndForceRequiresTheReviewedVersionAndRiskConsent() {
        Service().use { server ->
            val transport=server.transport(); val store=Memory(); val owner=session(transport,store); val team=create(owner)
            val other=session(server.transport()); join(owner,team,other)
            val created=publishFixture(server,transport,team); val before=owner.match(team.id,created.id); val targetID=other.snapshot().deviceID!!
            store.reject={ it.matchPending.isNotEmpty() }
            assertThrows(IOException::class.java) { owner.prepareMatchControl(before,MatchControl.propose,targetID) }
            store.reject={ false }; assertNull(owner.match(team.id,created.id).handoff)
            assertThrows(IllegalArgumentException::class.java) { owner.prepareMatchControl(before,MatchControl.force,targetID) }
            owner.prepareMatchControl(before,MatchControl.force,targetID,true); owner.sendMatchControl(created.id)
            assertEquals(targetID,other.match(team.id,created.id).writerDeviceID)
            assertThrows(IllegalStateException::class.java) { owner.prepareMatchControl(before,MatchControl.force,store.state.deviceID,true) }
        }
    }

    @Test(timeout = 30000) fun revokedTeamClearsDownloadedMatchesAndCursorsButKeepsUnconfirmedControl() {
        Service().use { server ->
            val transport=server.transport(); val owner=session(transport); val team=create(owner)
            val store=Memory(); val target=session(server.transport(),store); join(owner,team,target)
            val created=publishFixture(server,transport,team)
            owner.prepareMatchControl(owner.match(team.id,created.id),MatchControl.propose,store.state.deviceID); owner.sendMatchControl(created.id)
            val offered=target.match(team.id,created.id); target.matches(team.id)
            val op=target.prepareMatchControl(offered,MatchControl.accept)
            val member=owner.members(team.id).single { it.id == store.state.directory!!.teams.single().memberID }
            owner.prepareMember(team.id,member,TeamRole.member,true); owner.sendPending()
            assertThrows(IllegalStateException::class.java) { target.sendMatchControl(created.id) }
            assertTrue(store.state.matches.isEmpty()); assertTrue(store.state.matchCursors.isEmpty()); assertEquals(op,store.state.matchPending.getValue(created.id).id)
        }
    }

    @Test(timeout = 30000) fun expiredCursorRebuildsMetadataWithoutDroppingTheUnconfirmedControl() {
        Service().use { server ->
            val transport=server.transport(); val store=Memory(); val owner=session(transport,store); val team=create(owner)
            val target=session(server.transport()); join(owner,team,target)
            val created=publishFixture(server,transport,team); owner.matches(team.id)
            val full=owner.match(team.id,created.id)
            val pending=owner.prepareMatchControl(full,MatchControl.propose,target.snapshot().deviceID)
            repeat(2) { n -> transport.request("POST","teams/${team.id}/games/${created.id}/handoff/force",buildJsonObject {
                put("operationID",UUID.randomUUID().toString()); put("baseRevision",1); put("writerEpoch",n+1); put("targetDeviceID",store.state.deviceID); put("confirmRisk",true)
            }) }
            server.change("compact",team.id)
            val current=owner.matches(team.id).single()
            assertEquals(3,current.writerEpoch); assertEquals(pending,store.state.matchPending.getValue(created.id).id)
            assertNull(current.payload); assertEquals(full.payload,owner.match(team.id,created.id).payload)
        }
    }

    @Test(timeout = 30000) fun retryingAnOldProposeReceiptFetchesCurrentAuthorityInsteadOfRevivingTheHandoff() {
        Service().use { server ->
            val transport=server.transport(); var drop=true; val store=Memory()
            val owner=session(TeamTransport { method,path,body ->
                val response=transport.request(method,path,body)
                if(path.endsWith("handoff/propose") && drop) { drop=false; throw IOException("injected proposal loss") }; response
            },store)
            val team=create(owner); val target=session(server.transport()); join(owner,team,target)
            val created=publishFixture(server,transport,team)
            owner.prepareMatchControl(owner.match(team.id,created.id),MatchControl.propose,target.snapshot().deviceID)
            assertThrows(IOException::class.java) { owner.sendMatchControl(created.id) }
            target.prepareMatchControl(target.match(team.id,created.id),MatchControl.accept); target.sendMatchControl(created.id)
            val oldReceipt=owner.sendMatchControl(created.id)
            assertEquals(1,oldReceipt.response.writerEpoch)
            val current=store.state.matches.getValue(created.id).document
            assertEquals(2,current.writerEpoch); assertNull(current.handoff); assertFalse(current.canWrite)
            assertEquals(target.snapshot().deviceID,current.writerDeviceID)
        }
    }

    @Test(timeout = 30000) fun eachMatchHasAnIndependentDurableHandoffRequest() {
        Service().use { server ->
            val transport=server.transport(); val store=Memory(); val owner=session(transport,store); val team=create(owner)
            val target=session(server.transport()); join(owner,team,target)
            val games=List(2) { publishFixture(server,transport,team) }
            games.forEach { game -> owner.prepareMatchControl(owner.match(team.id,game.id),MatchControl.propose,target.snapshot().deviceID) }
            assertEquals(2,store.state.matchPending.size)
            owner.sendMatchControl(games.first().id); assertEquals(setOf(games.last().id),store.state.matchPending.keys)
            owner.sendMatchControl(games.last().id); assertTrue(store.state.matchPending.isEmpty())
        }
    }

    @Test(timeout = 30000) fun seededRosterKeepsLocalIDsAndZeroLabelsWithoutOverwritingTheOriginalTeam() {
        Service().use { server ->
            val store = Memory(); val session = session(server.transport(),store); session.refresh()
            val local = Team(UUID.randomUUID().toString(),"同名球队",(0..8).map { n -> Player(UUID.randomUUID().toString(),"球员$n",if(n==0) "00" else n.toString(),chineseName="球员$n") })
            val before = GameJson.encodeToJsonElement(local)
            session.prepareCreate(local,"管理员"); val request = store.state.pending!!
            session.sendPending(); val cloud = session.refresh().teams.single()
            assertNotEquals(local.id,cloud.id); assertEquals(SharedTeamBinding(local.id,cloud.id),store.state.bindings.single())
            val profile = session.roster(cloud.id)
            assertEquals(local.players.map { it.id },profile.players.map { it.sourceID })
            assertEquals("00",profile.players.first().numbers.single()); assertTrue(profile.players.all { it.id != it.sourceID })
            val projected = profile.gameTeam(); assertEquals("shared",projected.source); assertEquals(cloud.id,projected.id)
            projected.name = "只改分离的比赛快照"; projected.players.first().name = "另一个名字"
            assertEquals(before,GameJson.encodeToJsonElement(local)); assertEquals("球员0",session.roster(cloud.id).players.first().chineseName)
            assertEquals(local.id,request.localTeamID)
            assertThrows(IllegalStateException::class.java) { session.prepareCreate(local,"管理员") }
        }
    }

    @Test(timeout = 30000) fun rosterConflictKeepsTheExactDraftAndRequiresAnExplicitNewRevision() {
        Service().use { server ->
            val admin = session(server.transport()); val team = create(admin)
            val store = Memory(); val scorer = session(server.transport(),store); join(admin,team,scorer)
            val original = scorer.roster(team.id); admin.roster(team.id)
            val a = SharedRosterPlayer.new("同名", "Alpha",listOf("0","00"))
            admin.preparePlayer(team.id,1,a); admin.sendPending()
            val b = SharedRosterPlayer.new("同名","Beta",listOf("00"))
            val id = scorer.preparePlayer(team.id,original.revision,b); val draft = store.state.pending!!
            assertEquals("roster_conflict",assertThrows(TeamServiceFault::class.java) { scorer.sendPending() }.code)
            assertEquals(draft,store.state.pending); assertEquals(2,scorer.roster(team.id).revision)
            scorer.keepRejectedAsDraft(id); assertEquals(draft,store.state.reviewedRequests.single().request)
            val next = scorer.preparePlayer(team.id,2,b); assertNotEquals(id,next); scorer.sendPending()
            val final = scorer.roster(team.id); assertEquals(3,final.revision); assertEquals(listOf(a.sourceID,b.sourceID),final.players.map { it.sourceID })
            assertEquals(listOf("0","00"),final.players.first().numbers)
            assertThrows(IllegalArgumentException::class.java) { scorer.preparePlayer(team.id,3,b.copy(archived=true)) }
            assertThrows(IllegalArgumentException::class.java) { scorer.prepareProfile(team.id,3,"不应允许","不应允许","") }
        }
    }

    @Test(timeout = 30000) fun aLostRosterResponseNeverDowngradesANewerCachedRosterOnRetry() {
        Service().use { server ->
            val token = TeamSecrets.random(); val transport = server.transport(token); val store = Memory(); var drop = true
            val faulty = TeamTransport { method,path,body ->
                val response = transport.request(method,path,body)
                if (path.endsWith("/roster") && drop) { drop = false; throw IOException("injected roster loss") }; response
            }
            val admin = session(faulty,store); val team = create(admin); admin.roster(team.id)
            val player = SharedRosterPlayer.new("原始球员","",listOf("00"))
            val id = admin.preparePlayer(team.id,1,player)
            assertThrows(IOException::class.java) { admin.sendPending() }
            val other = session(server.transport(token)); other.refresh(); other.roster(team.id)
            other.prepareProfile(team.id,2,"新的队名","新简称","上海"); other.sendPending()
            assertEquals(3,admin.roster(team.id).revision)
            assertEquals(id,admin.sendPending().request.id)
            assertEquals(3,store.state.profiles.getValue(team.id).profile.revision)
            assertEquals("新的队名",admin.roster(team.id).name)
        }
    }

    @Test(timeout = 30000) fun archivingRestoresTheSameIDsAndRevokingAnInvitationDoesNotGrantMembership() {
        Service().use { server ->
            val admin = session(server.transport()); val team = create(admin); admin.roster(team.id)
            val player = SharedRosterPlayer.new("待停用","",listOf("0"))
            admin.preparePlayer(team.id,1,player); admin.sendPending()
            admin.preparePlayer(team.id,2,player.copy(archived=true)); admin.sendPending()
            assertTrue(admin.roster(team.id).gameTeam().players.isEmpty())
            admin.preparePlayer(team.id,3,player.copy(archived=false)); admin.sendPending()
            val restored = admin.roster(team.id).players.single(); assertEquals(player.id,restored.id); assertEquals(player.sourceID,restored.sourceID)
            assertThrows(IllegalArgumentException::class.java) { admin.preparePlayer(team.id,4,restored.copy(sourceID=UUID.randomUUID().toString())) }
            admin.prepareInvite(team.id,TeamRole.member); val invite = admin.sendPending(); val id = invite.response.getValue("invitationID").jsonPrimitive.content
            assertTrue(admin.invitations(team.id).any { it.invitationID == id })
            admin.prepareRevoke(team.id,id); admin.sendPending(); assertTrue(admin.invitations(team.id).isEmpty())
            val stranger = TeamDirectoryClient(server.transport()); stranger.enroll("陌生设备")
            assertEquals(410,assertThrows(TeamServiceFault::class.java) { stranger.preview(invite.request.body.getValue("token").jsonPrimitive.content) }.status)
        }
    }

    @Test(timeout = 30000) fun currentAdminLimitAndLastAdminProtectionRemainAuthoritative() {
        Service().use { server ->
            val store = Memory(); val admin = session(server.transport(),store); val team = create(admin)
            val owner = admin.members(team.id).single()
            val failed = admin.prepareMember(team.id,owner,TeamRole.member)
            assertEquals("last_admin",assertThrows(TeamServiceFault::class.java) { admin.sendPending() }.code)
            admin.keepRejectedAsDraft(failed)
            repeat(3) { index ->
                val member = session(server.transport()); join(admin,team,member,TeamRole.member)
                val target = admin.members(team.id).single { it.id == member.snapshot().directory!!.teams.single().memberID }
                val id = admin.prepareMember(team.id,target,TeamRole.admin)
                if (index < 2) admin.sendPending() else {
                    assertEquals("admin_limit",assertThrows(TeamServiceFault::class.java) { admin.sendPending() }.code)
                    admin.keepRejectedAsDraft(id)
                }
            }
            assertEquals(3,admin.members(team.id).count { it.role == TeamRole.admin })
        }
    }

    @Test(timeout = 30000) fun revokedMembershipDropsActiveRosterBindingAndSecretsButRetainsThePendingRequest() {
        Service().use { server ->
            val store = Memory(); val owner = session(server.transport(),store); owner.refresh()
            val local = Team(UUID.randomUUID().toString(),"本机队",emptyList())
            owner.prepareCreate(local,"原管理员"); owner.sendPending(); val team = owner.refresh().teams.single(); owner.roster(team.id)
            val other = session(server.transport()); join(owner,team,other,TeamRole.member)
            val target = owner.members(team.id).single { it.id == other.snapshot().directory!!.teams.single().memberID }
            owner.prepareMember(team.id,target,TeamRole.admin); owner.sendPending(); other.refresh()
            val ownMember = other.members(team.id).single { it.id == team.memberID }
            val pendingID = owner.prepareInvite(team.id,TeamRole.member)
            other.prepareMember(team.id,ownMember,TeamRole.member,remove=true); other.sendPending()
            assertTrue(owner.refresh().teams.isEmpty()); assertTrue(store.state.profiles.isEmpty()); assertTrue(store.state.bindings.isEmpty()); assertTrue(store.state.retainedSecrets.isEmpty())
            assertEquals(pendingID,store.state.pending!!.id)
            assertEquals(404,assertThrows(TeamServiceFault::class.java) { owner.sendPending() }.status)
            assertEquals(pendingID,store.state.pending!!.id)
            assertThrows(IllegalArgumentException::class.java) { owner.keepRejectedAsDraft(pendingID) }
        }
    }

    @Test(timeout = 30000) fun revocationDuringRosterDownloadNeverInstallsTheStaleResponse() {
        Service().use { server ->
            val transport = server.transport(); val store = Memory(); var afterRead: (() -> Unit)? = null
            val owner = session(TeamTransport { method,path,body ->
                val response = transport.request(method,path,body)
                if (method == "GET" && Regex("teams/[a-f0-9-]{36}").matches(path)) afterRead?.also { afterRead=null }?.invoke()
                response
            },store)
            val team = create(owner); owner.roster(team.id)
            val other = session(server.transport()); join(owner,team,other,TeamRole.member)
            owner.prepareMember(team.id,owner.members(team.id).single { it.id != team.memberID },TeamRole.admin); owner.sendPending(); other.refresh()
            val target = other.members(team.id).single { it.id == team.memberID }
            afterRead = { other.prepareMember(team.id,target,TeamRole.member,true); other.sendPending() }
            assertThrows(IllegalStateException::class.java) { owner.roster(team.id,true) }
            assertTrue(store.state.profiles.isEmpty()); assertTrue(store.state.directory!!.teams.isEmpty())
        }
    }

    @Test(timeout = 30000) fun revokedDeviceCredentialClearsActiveCachesAndNeverGeneratesANewIdentity() {
        Service().use { server ->
            val store = Memory(); val session = session(server.transport(),store); val team = create(session); session.roster(team.id)
            val device = store.state.deviceID; val pending = session.prepareInvite(team.id,TeamRole.member)
            server.change("credential",device)
            assertEquals(401,assertThrows(TeamServiceFault::class.java) { session.refresh() }.status)
            assertNull(store.state.directory); assertTrue(store.state.profiles.isEmpty()); assertTrue(store.state.retainedSecrets.isEmpty())
            assertEquals(device,store.state.deviceID); assertEquals(pending,store.state.pending!!.id)
        }
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
