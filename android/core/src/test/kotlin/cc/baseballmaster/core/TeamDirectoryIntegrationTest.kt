package cc.baseballmaster.core

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Uses the repository's real Node/SQLite service on loopback, never production. */
class TeamDirectoryIntegrationTest {
    private class Memory : TeamDirectoryStorage {
        override val monitor = Any()
        var state = TeamDirectoryState()
        var reject: (TeamDirectoryState) -> Boolean = { false }
        override fun load() = state
        override fun save(state: TeamDirectoryState) { if (reject(state)) throw IOException("injected write failure"); this.state = state }
    }
    private class Credentials : RotatingTeamCredentials {
        override val monitor=Any(); var current=TeamSecrets.random(); var fixed: ByteArray?=null; var pending: TeamCredentialRotation?=null
        var failBegin=false; var failFinish=false
        override fun currentToken()=current
        override fun stableEncryptionSecret(): ByteArray { if(fixed==null) fixed=java.util.Base64.getUrlDecoder().decode(current); return fixed!!.copyOf() }
        override fun pendingRotation()=pending
        override fun beginRotation(): TeamCredentialRotation {
            if(failBegin) throw IOException("injected credential save")
            return pending ?: TeamCredentialRotation(UUID.randomUUID().toString(),TeamSecrets.random()).also { pending=it }
        }
        override fun finishRotation(rotation: TeamCredentialRotation) { if(failFinish) throw IOException("injected credential finish"); check(pending==rotation); current=rotation.token; pending=null }
    }
    private class ReconnectCredentials : ReconnectingTeamCredentials {
        override val monitor=Any();val original=TeamSecrets.random();var current=original
        private val stable=java.util.Base64.getUrlDecoder().decode(original)
        var pending: TeamCredentialRotation?=null;var failBegin=false;var failActivate=false;var failFinish=false
        override fun stableEncryptionSecret()=stable.copyOf()
        override fun pendingReconnect()=pending
        override fun beginReconnect(): TeamCredentialRotation {
            if(failBegin) throw IOException("injected candidate write")
            return pending ?: TeamCredentialRotation(UUID.randomUUID().toString(),TeamSecrets.random()).also {pending=it}
        }
        override fun activateReconnect(candidate: TeamCredentialRotation) {
            if(failActivate) throw IOException("injected credential activation")
            check(candidate==pending);current=candidate.token
        }
        override fun finishReconnect(candidate: TeamCredentialRotation) {
            if(failFinish) throw IOException("injected candidate cleanup")
            check(candidate==pending && current==candidate.token);pending=null
        }
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
        fun matchFixture(final: Boolean=false): JsonObject {
            input.write(buildJsonObject {put("kind","fixture");put("final",final)}.toString()); input.newLine(); input.flush()
            return GameJson.parseToJsonElement(output.readLine()).jsonObject.getValue("document").jsonObject
        }
        fun transport(secret: String = TeamSecrets.random()) = TeamHttpTransport(endpoint, TeamCredentialProvider { secret }, allowLoopbackHttp = true)
        fun publicRequest(method: String,path: String,body: JsonObject?=null,cookie: String?=null): Pair<Int,JsonObject> {
            val http=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
            return http.newCall(Request.Builder().url("$endpoint/$path").header("Origin","https://baseballmaster.cc").header("X-BM-Protocol","1")
                .apply {cookie?.let {header("Cookie","bm_view=$it")}}
                .method(method,body?.toString()?.toRequestBody("application/json".toMediaType())).build()).execute().use {
                it.code to GameJson.parseToJsonElement(it.body!!.string()).jsonObject
            }
        }
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
    private fun publishFixture(server: Service, transport: TeamTransport, team: RemoteTeam,final: Boolean=false): SharedMatchDocument {
        val body=JsonObject(server.matchFixture(final) + ("operationID" to JsonPrimitive(UUID.randomUUID().toString())))
        return SharedMatchDocument(transport.request("POST","teams/${team.id}/games",body))
    }

    @Test(timeout=30000) fun historicalWatchingPublicRestrictedAndClosedKeepTheFullArchiveAndRevokeImmediately() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();val owner=session(actual,store);val team=create(owner)
            var document=owner.match(team.id,publishFixture(server,actual,team,true).id);val original=document.payload
            val code=document.watchURL.substringAfter('#')
            assertEquals(404,server.publicRequest("GET","watch/$code").first)
            assertThrows(IllegalArgumentException::class.java) {owner.prepareWatchMode(document,HistoricalWatchMode.public,false)}
            owner.prepareWatchMode(document,HistoricalWatchMode.public,true);owner.sendWatching(document.id)
            document=owner.match(team.id,document.id);assertEquals(200,server.publicRequest("GET","watch/$code").first)
            owner.prepareWatchMode(document,HistoricalWatchMode.restricted,true);owner.sendWatching(document.id)
            document=owner.match(team.id,document.id);assertEquals(403,server.publicRequest("GET","watch/$code").first)
            owner.prepareWatchInvitation(document);owner.sendWatching(document.id);val link=store.state.watchLinks.getValue(document.id)
            assertNotNull(link.url(document));assertFalse(link.toString().contains(link.token))
            val viewerSession=TeamSecrets.random()
            assertEquals(200,server.publicRequest("POST","watch/$code/apply",buildJsonObject {put("token",link.token);put("session",viewerSession);put("nickname","外部观看者")}).first)
            assertEquals(403,server.publicRequest("GET","watch/$code",cookie=viewerSession).first)
            val viewer=owner.matchViewers(team.id,document.id).viewers.single();assertEquals("pending",viewer.status)
            owner.prepareExternalViewer(document,viewer,true);owner.sendWatching(document.id)
            document=owner.match(team.id,document.id);assertEquals(200,server.publicRequest("GET","watch/$code",cookie=viewerSession).first)
            owner.prepareWatchMode(document,HistoricalWatchMode.closed,true);owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            assertEquals(404,server.publicRequest("GET","watch/$code",cookie=viewerSession).first)
            assertEquals(original,document.payload);assertTrue(store.state.watchLinks.isEmpty())
            owner.prepareWatchMode(document,HistoricalWatchMode.restricted,true);owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            assertEquals(403,server.publicRequest("GET","watch/$code",cookie=viewerSession).first)
            assertThrows(IllegalArgumentException::class.java) {owner.prepareExternalViewer(document,owner.matchViewers(team.id,document.id).viewers.single(),true)}
        }
    }

    @Test(timeout=30000) fun specifiedMemberTicketDoesNotGrantManagementAndRevocationEndsExistingBrowserAccess() {
        Service().use {server ->
            val adminTransport=server.transport();val owner=session(adminTransport);val team=create(owner)
            val memberStore=Memory();val member=session(server.transport(),memberStore);join(owner,team,member,TeamRole.member)
            var document=owner.match(team.id,publishFixture(server,adminTransport,team,true).id)
            owner.prepareWatchMode(document,HistoricalWatchMode.restricted,true);owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            val memberID=member.snapshot().directory!!.teams.single().memberID
            owner.prepareViewerMember(document,memberID,true);owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            val memberView=member.match(team.id,document.id)
            assertFalse(memberView.canManageWatch)
            assertThrows(IllegalArgumentException::class.java) {member.prepareWatchMode(memberView,HistoricalWatchMode.public,true)}
            member.prepareWatchTicket(memberView);member.sendWatching(document.id)
            val ticket=memberStore.state.watchLinks.getValue(document.id);val viewerSession=TeamSecrets.random();val code=document.watchURL.substringAfter('#')
            assertEquals(200,server.publicRequest("POST","watch/$code/apply",buildJsonObject {put("token",ticket.token);put("session",viewerSession);put("nickname","本队成员")}).first)
            assertEquals(200,server.publicRequest("GET","watch/$code",cookie=viewerSession).first)
            owner.prepareViewerMember(document,memberID,false);owner.sendWatching(document.id)
            assertEquals(403,server.publicRequest("GET","watch/$code",cookie=viewerSession).first)
        }
    }

    @Test(timeout=30000) fun lostWatchingReceiptRetriesSameInvitationAndPermissionsRatherThanMintingNewSecrets() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();var loseAccess=true;var loseInvitation=true
            val owner=session(TeamTransport {m,p,b -> val reply=actual.request(m,p,b)
                if(p.endsWith("/access") && loseAccess) {loseAccess=false;throw IOException("lost access receipt")}
                if(p.endsWith("/viewer-invitations") && loseInvitation) {loseInvitation=false;throw IOException("lost invitation receipt")};reply
            },store)
            val team=create(owner);var document=owner.match(team.id,publishFixture(server,actual,team,true).id)
            val op=owner.prepareWatchMode(document,HistoricalWatchMode.restricted,true)
            assertThrows(IOException::class.java) {owner.sendWatching(document.id)};assertEquals(op,store.state.watchPending.getValue(document.id).id)
            owner.sendWatching(document.id);document=owner.match(team.id,document.id);assertEquals(2L,document.accessVersion)
            val invite=owner.prepareWatchInvitation(document);val token=store.state.watchPending.getValue(document.id).body.getValue("token")
            assertThrows(IOException::class.java) {owner.sendWatching(document.id)}
            assertThrows(IllegalStateException::class.java) {owner.prepareWatchInvitation(document)}
            owner.sendWatching(document.id);assertEquals(invite,store.state.watchLinks.getValue(document.id).operationID);assertEquals(token.jsonPrimitive.content,store.state.watchLinks.getValue(document.id).token)
            assertTrue(store.state.watchPending.isEmpty())
        }
    }

    @Test(timeout=30000) fun sharedLiveUsesWriterAuthorizationAndExpiresIndependentlyOfArchiveWatching() {
        Service().use {server ->
            val actual=server.transport();val owner=session(actual);val team=create(owner);val member=session(server.transport());join(owner,team,member,TeamRole.member)
            var document=owner.match(team.id,publishFixture(server,actual,team).id);val original=document.payload
            assertThrows(IllegalArgumentException::class.java) {member.prepareSharedLive(member.match(team.id,document.id),true,true)}
            owner.prepareSharedLive(document,true,true);owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            val code=document.sharedLiveURL!!.substringAfter("live/");assertEquals(200,server.publicRequest("GET","live/$code").first)
            owner.sharedLiveHeartbeat(team.id,document.id)
            owner.prepareSharedLive(document,false,true);owner.sendWatching(document.id)
            assertEquals(404,server.publicRequest("GET","live/$code").first);document=owner.match(team.id,document.id);assertEquals(original,document.payload)
            owner.prepareSharedLive(document,true,true);owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            val nextCode=document.sharedLiveURL!!.substringAfter("live/");assertNotEquals(code,nextCode)
            server.change("advance","3600001");assertEquals(404,server.publicRequest("GET","live/$nextCode").first)
            owner.sharedLiveHeartbeat(team.id,document.id);assertEquals(404,server.publicRequest("GET","live/$nextCode").first)
        }
    }

    @Test(timeout=30000) fun recoveryAndRevocationPreservePendingViewingActionsWithoutReplayingThem() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();val owner=session(actual,store);val team=create(owner)
            val document=owner.match(team.id,publishFixture(server,actual,team,true).id)
            val id=owner.prepareWatchMode(document,HistoricalWatchMode.public,true)
            server.change("recovery");owner.refresh()
            assertEquals(id,store.state.recoveryWork.single().watchRequests.single().id)
            assertThrows(IllegalStateException::class.java) {owner.sendWatching(document.id)}
            owner.reconcileAfterRecovery(true);assertTrue(store.state.watchPending.isEmpty())
            assertEquals(HistoricalWatchMode.closed,owner.match(team.id,document.id).watchMode)
            owner.prepareWatchMode(owner.match(team.id,document.id),HistoricalWatchMode.public,true)
            server.change("credential",store.state.deviceID!!);assertThrows(TeamServiceFault::class.java) {owner.refresh()}
            assertEquals(2,store.state.recoveryWork.sumOf {it.watchRequests.size});assertTrue(store.state.watchLinks.isEmpty())
        }
    }
    @Test(timeout=30000) fun watchingRequestsSaveBeforeSendingAndLostLocalReceiptCanRetryAfterItsOwnVersionChanges() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();var mutations=0
            val owner=session(TeamTransport {m,p,b -> if(m=="POST" && p.endsWith("/access")) mutations++;actual.request(m,p,b)},store)
            val team=create(owner);var document=owner.match(team.id,publishFixture(server,actual,team,true).id)
            store.reject={it.watchPending.isNotEmpty()}
            assertThrows(IOException::class.java) {owner.prepareWatchMode(document,HistoricalWatchMode.public,true)};assertEquals(0,mutations)
            store.reject={false};val op=owner.prepareWatchMode(document,HistoricalWatchMode.public,true)
            store.reject={it.watchPending.isEmpty() && it.watchReceipts.isNotEmpty()}
            assertThrows(IOException::class.java) {owner.sendWatching(document.id)};assertEquals(op,store.state.watchPending.getValue(document.id).id)
            store.reject={false};owner.sendWatching(document.id);document=owner.match(team.id,document.id)
            assertEquals(HistoricalWatchMode.public,document.watchMode);assertEquals(2L,document.accessVersion)
            assertTrue(store.state.watchPending.isEmpty());assertEquals(op,store.state.watchReceipts.getValue(document.id).request.id)
        }
    }
    @Test(timeout=30000) fun definiteAccessConflictCanBePreservedForReviewWithoutAutomaticResubmission() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();val owner=session(actual,store);val team=create(owner)
            val document=owner.match(team.id,publishFixture(server,actual,team,true).id)
            val op=owner.prepareWatchMode(document,HistoricalWatchMode.public,true)
            actual.request("POST","teams/${team.id}/games/${document.id}/access",buildJsonObject {put("operationID",UUID.randomUUID().toString());put("baseAccessVersion",document.accessVersion);put("mode","restricted")})
            assertEquals(409,assertThrows(TeamServiceFault::class.java) {owner.sendWatching(document.id)}.status)
            assertThrows(IllegalArgumentException::class.java) {owner.parkWatching(document.id,false)}
            owner.parkWatching(document.id,true);assertTrue(store.state.watchPending.isEmpty())
            assertEquals(op,store.state.recoveryWork.single().watchRequests.single().id)
            assertEquals(HistoricalWatchMode.restricted,owner.match(team.id,document.id).watchMode)
        }
    }

    @Test(timeout=30000) fun expiredDeviceReconnectIsExplicitAndKeepsOldWorkWithoutReplayingIt() {
        Service().use {server ->
            val vault=ReconnectCredentials();val store=Memory();val owner=session(server.transport(vault.current),store)
            val team=create(owner);val oldDevice=store.state.deviceID!!
            val document=owner.match(team.id,publishFixture(server,server.transport(vault.current),team).id)
            val control=owner.prepareMatchControl(document,MatchControl.force,oldDevice,true)
            val pending=owner.prepareCreate("失效身份的旧请求","管理员")
            server.change("credential",oldDevice)
            assertEquals(401,assertThrows(TeamServiceFault::class.java) {owner.refresh()}.status)
            assertTrue(store.state.credentialExpired);assertNull(store.state.directory)
            val retained=store.state.recoveryWork.single()
            assertEquals(pending,retained.directoryRequest!!.id);assertEquals(control,retained.matchRequests.single().id)
            assertEquals(document.raw,retained.matches.single().document.raw)
            var calls=0
            val reconnect=TeamDeviceReconnector(vault,store,{token -> TeamTransport {m,p,b -> calls++;server.transport(token).request(m,p,b)}},"安卓重新接入")
            assertThrows(IllegalArgumentException::class.java) {reconnect.reconnect(false)};assertEquals(0,calls)
            val stable=vault.stableEncryptionSecret();val newDevice=reconnect.reconnect(true)
            assertNotEquals(oldDevice,newDevice);assertEquals(1,calls);assertNotEquals(vault.original,vault.current)
            assertArrayEquals(stable,vault.stableEncryptionSecret());assertNull(vault.pending)
            assertNull(store.state.pending);assertTrue(store.state.matchPending.isEmpty());assertEquals(listOf(retained),store.state.recoveryWork)
            assertTrue(store.state.requiresRecoveryReview);assertFalse(store.state.credentialExpired)
            val renewed=session(server.transport(vault.current),store)
            renewed.refresh();renewed.reconcileAfterRecovery(true)
            assertTrue(renewed.refresh().teams.isEmpty());assertThrows(IllegalStateException::class.java) {renewed.sendPending()}
        }
    }

    @Test(timeout=30000) fun reconnectResumesSameCandidateAfterLostEnrollmentActivationAndFinalSave() {
        Service().use {server ->
            val vault=ReconnectCredentials();val store=Memory();val owner=session(server.transport(vault.current),store)
            create(owner);server.change("credential",store.state.deviceID!!)
            assertThrows(TeamServiceFault::class.java) {owner.refresh()}
            var lose=true;val enrolled=mutableSetOf<String>();var calls=0
            val reconnect=TeamDeviceReconnector(vault,store,{token -> TeamTransport {m,p,b ->
                val reply=server.transport(token).request(m,p,b);calls++;enrolled+=reply.getValue("deviceID").jsonPrimitive.content
                if(lose) {lose=false;throw IOException("injected lost enrolment reply")};reply
            }},"安卓重新接入")
            assertThrows(IOException::class.java) {reconnect.reconnect(true)}
            val candidate=vault.pending!!;assertEquals(candidate.id,store.state.reconnectRequestID)
            vault.failActivate=true
            assertThrows(IOException::class.java) {reconnect.reconnect(true)};assertEquals(candidate,vault.pending);assertEquals(vault.original,vault.current)
            vault.failActivate=false;store.reject={it.deviceID!=null && !it.credentialExpired}
            assertThrows(IOException::class.java) {reconnect.reconnect(true)}
            assertEquals(candidate.token,vault.current);assertTrue(store.state.credentialExpired);assertEquals(candidate,vault.pending)
            store.reject={false};val id=reconnect.reconnect(true)
            assertEquals(setOf(id),enrolled);assertEquals(4,calls);assertNull(vault.pending);assertEquals(id,store.state.deviceID)
        }
    }

    @Test(timeout=30000) fun reconnectDoesNotEnrollBeforeCandidateAndArchiveAreDurableAndResumesCleanup() {
        Service().use {server ->
            val vault=ReconnectCredentials();val store=Memory();val owner=session(server.transport(vault.current),store)
            create(owner);server.change("credential",store.state.deviceID!!);assertThrows(TeamServiceFault::class.java) {owner.refresh()}
            var calls=0
            val reconnect=TeamDeviceReconnector(vault,store,{token -> TeamTransport {m,p,b -> calls++;server.transport(token).request(m,p,b)}},"安卓重新接入")
            vault.failBegin=true;assertThrows(IOException::class.java) {reconnect.reconnect(true)};assertEquals(0,calls)
            vault.failBegin=false;store.reject={it.reconnectRequestID!=null}
            assertThrows(IOException::class.java) {reconnect.reconnect(true)};assertEquals(0,calls)
            store.reject={false};vault.failFinish=true
            assertThrows(IOException::class.java) {reconnect.reconnect(true)};assertEquals(1,calls)
            val id=store.state.deviceID;assertFalse(store.state.credentialExpired);assertNotNull(vault.pending)
            val guarded=TeamDirectorySession(TeamDirectoryClient(server.transport(vault.current)),store,"安卓",reconnect::reconnect,{vault.pending!=null})
            assertTrue(guarded.snapshot().credentialExpired);assertThrows(IllegalStateException::class.java) {guarded.refresh()}
            vault.failFinish=false;assertEquals(id,guarded.reconnect(true));assertNull(vault.pending);assertFalse(guarded.snapshot().credentialExpired)
        }
    }

    @Test fun anyAuthenticatedRequestMarksExpiredAuthorizationAndHidesStaleRoleWhenSaveFails() {
        val store=Memory();val team=RemoteTeam(UUID.randomUUID().toString(),"旧缓存球队",1,TeamRole.admin,UUID.randomUUID().toString(),1)
        store.state=TeamDirectoryState(deviceID=UUID.randomUUID().toString(),directory=TeamDirectory(listOf(team),UUID.randomUUID().toString()))
        val old=store.state;var fail=false
        val transport=TeamTransport {_,_,_ -> if(fail) throw TeamServiceFault(401,"revoked") else error("unexpected request")}
        val owner=session(transport,store)
        store.reject={true};fail=true
        assertThrows(IOException::class.java) {owner.client.teams()}
        assertEquals(old,store.state);assertTrue(owner.snapshot().credentialExpired);assertNull(owner.snapshot().directory)
    }

    @Test(timeout = 30000) fun recoveryReviewPreservesOldWorkDownloadsCurrentDocumentsAndNeverReplaysOldRequests() {
        Service().use {server ->
            val transport=server.transport();val store=Memory();val owner=session(transport,store);val team=create(owner)
            val published=publishFixture(server,transport,team);val document=owner.match(team.id,published.id)
            val control=owner.prepareMatchControl(document,MatchControl.force,store.state.deviceID,true)
            val pending=owner.prepareCreate("不应自动建立的球队","管理员")
            server.change("recovery");owner.refresh()
            assertTrue(store.state.requiresRecoveryReview);assertTrue(store.state.matches.isEmpty())
            assertEquals(pending,store.state.recoveryWork.single().directoryRequest!!.id)
            assertEquals(control,store.state.recoveryWork.single().matchRequests.single().id)
            assertEquals(document.raw,store.state.recoveryWork.single().matches.single().document.raw)
            assertThrows(IllegalArgumentException::class.java) {owner.reconcileAfterRecovery(false)}
            owner.reconcileAfterRecovery(true)
            assertFalse(store.state.requiresRecoveryReview);assertNull(store.state.pending);assertTrue(store.state.matchPending.isEmpty())
            assertEquals(document.payload,store.state.matches.getValue(document.id).document.payload)
            assertEquals(1,owner.refresh().teams.size)
            assertThrows(IllegalStateException::class.java) {owner.sendPending()}
            owner.prepareInvite(team.id,TeamRole.member);owner.sendPending()
            assertEquals(1,store.state.recoveryWork.size)
        }
    }

    @Test(timeout = 30000) fun dissolutionRetriesItsTerminalReceiptAndRestorationNeverRevivesOldAuthorizations() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();var lose=true
            val owner=session(TeamTransport {method,path,body ->
                val response=actual.request(method,path,body)
                if(path.endsWith("/dissolve") && lose) {lose=false;throw IOException("injected lost dissolution receipt")};response
            },store)
            val team=create(owner);val member=session(server.transport());join(owner,team,member,TeamRole.member)
            owner.prepareFixedInvite(team.id);owner.sendPending();val oldInvite=store.state.fixedInvitations.getValue(team.id)
            val document=owner.match(team.id,publishFixture(server,actual,team).id)
            val profile=owner.roster(team.id)
            assertThrows(IllegalArgumentException::class.java) {owner.prepareTeamDissolution(profile,"错误队名",true)}
            assertNull(store.state.pending)
            val id=owner.prepareTeamDissolution(profile,profile.name,true)
            assertThrows(IOException::class.java) {owner.sendPending()};assertEquals(id,store.state.pending!!.id)
            owner.sendPending();assertTrue(owner.refresh().teams.isEmpty())
            assertTrue(store.state.profiles.isEmpty());assertTrue(store.state.matches.isEmpty());assertTrue(store.state.fixedInvitations.isEmpty());assertTrue(store.state.recoveryCards.isEmpty())
            assertTrue(member.refresh().teams.isEmpty());assertTrue(member.dissolvedTeams().isEmpty())
            val deleted=owner.dissolvedTeams().single();assertEquals(team.id,deleted.id)
            assertThrows(IllegalArgumentException::class.java) {owner.prepareTeamRestoration(deleted,false)}
            owner.prepareTeamRestoration(deleted,true);owner.sendPending();assertEquals(TeamRole.admin,owner.refresh().teams.single().role)
            assertEquals(1,owner.members(team.id).size);assertTrue(member.refresh().teams.isEmpty())
            assertEquals(410,assertThrows(TeamServiceFault::class.java) {owner.invitationPreview(oldInvite.url)}.status)
            val restored=owner.match(team.id,document.id)
            assertEquals(document.payload,restored.payload);assertTrue(restored.writerEpoch>document.writerEpoch)
            assertTrue(owner.dissolvedTeams().isEmpty());assertFalse(owner.recoveryStatus(team.id).hasCode ?: true)
        }
    }

    @Test(timeout = 30000) fun restoringAfterALostReceiptKeepsOneNewMembershipAndRequiresANewRecoveryCard() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();var lose=true
            val owner=session(TeamTransport {method,path,body ->
                val response=actual.request(method,path,body)
                if(path.endsWith("/restore") && lose) {lose=false;throw IOException("injected lost restoration receipt")};response
            },store)
            val team=create(owner);val profile=owner.roster(team.id)
            owner.prepareTeamDissolution(profile,profile.name,true);owner.sendPending();owner.refresh()
            val id=owner.prepareTeamRestoration(owner.dissolvedTeams().single(),true)
            assertThrows(IOException::class.java) {owner.sendPending()};assertEquals(id,store.state.pending!!.id)
            owner.sendPending();owner.refresh();assertEquals(1,owner.members(team.id).size)
            val status=owner.recoveryStatus(team.id);assertEquals(false,status.hasCode)
            owner.prepareRecoveryRotation(status,true);owner.sendPending();owner.refresh()
            assertEquals(1,store.state.recoveryCards.size)
            assertEquals(1,owner.audit(team.id,action="teamRestored").events.size)
        }
    }

    @Test(timeout = 30000) fun nonAdminCannotDissolveAndExpiredRestorationCannotResurrectTheTeam() {
        Service().use {server ->
            val owner=session(server.transport());val team=create(owner);val member=session(server.transport());join(owner,team,member,TeamRole.member)
            val readable=member.roster(team.id)
            assertThrows(IllegalArgumentException::class.java) {member.prepareTeamDissolution(readable,readable.name,true)}
            assertThrows(IllegalStateException::class.java) {member.audit(team.id)}
            val profile=owner.roster(team.id);owner.prepareTeamDissolution(profile,profile.name,true);owner.sendPending();owner.refresh()
            val stale=owner.dissolvedTeams().single();server.change("advance",(31L*86_400_000).toString())
            assertTrue(owner.dissolvedTeams().isEmpty())
            owner.prepareTeamRestoration(stale,true)
            assertEquals(404,assertThrows(TeamServiceFault::class.java) {owner.sendPending()}.status)
            assertTrue(owner.refresh().teams.isEmpty())
        }
    }

    @Test(timeout = 30000) fun administratorAuditHasStablePaginationActionFiltersAndNoInvitationSecrets() {
        Service().use {server ->
            val owner=session(server.transport());val team=create(owner)
            repeat(55) {owner.prepareFixedInvite(team.id,true);owner.sendPending()}
            val first=owner.audit(team.id);assertEquals(50,first.events.size);assertTrue(first.hasMore)
            val second=owner.audit(team.id,before=first.before)
            assertFalse(second.hasMore);assertTrue(second.events.all {it.id<first.before})
            assertEquals((first.events+second.events).size,(first.events+second.events).map {it.id}.distinct().size)
            val filtered=owner.audit(team.id,action="teamInvitationRotated")
            assertEquals(50,filtered.events.size);assertTrue(filtered.events.all {it.action=="teamInvitationRotated"})
            val tail=owner.audit(team.id,action="teamInvitationRotated",before=filtered.before);assertEquals(5,tail.events.size)
            assertTrue(owner.audit(team.id,action="handoff:force").events.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {owner.audit(team.id,action="teamCreated&from=0")}
            val secret=owner.snapshot().fixedInvitations.getValue(team.id).token
            assertFalse(GameJson.encodeToString(TeamAuditPage.serializer(),first).contains(secret))
        }
    }

    @Test(timeout = 30000) fun lostRecoveryAcknowledgementAndFailedLocalCommitKeepTheGateAndOriginalRequest() {
        Service().use {server ->
            val actual=server.transport();val store=Memory();var lose=true;var acknowledgements=0
            val owner=session(TeamTransport {method,path,body ->
                val response=actual.request(method,path,body)
                if(path=="devices/recovery-review") {acknowledgements++;if(lose) {lose=false;throw IOException("injected lost review acknowledgement")}}
                response
            },store)
            create(owner);val pending=owner.prepareCreate("待核对原请求","管理员")
            server.change("recovery");owner.refresh()
            assertThrows(IOException::class.java) {owner.reconcileAfterRecovery(true)}
            assertTrue(store.state.requiresRecoveryReview);assertEquals(pending,store.state.pending!!.id)
            store.reject={!it.requiresRecoveryReview}
            assertThrows(IOException::class.java) {owner.reconcileAfterRecovery(true)}
            assertTrue(store.state.requiresRecoveryReview);assertEquals(pending,store.state.pending!!.id)
            store.reject={false};owner.reconcileAfterRecovery(true)
            assertEquals(3,acknowledgements);assertEquals(1,store.state.recoveryWork.size)
            assertFalse(store.state.requiresRecoveryReview);assertNull(store.state.pending)
            assertEquals(1,owner.refresh().teams.size)
        }
    }

    @Test(timeout = 30000) fun restoreOrPermissionChangeDuringReviewCannotClearTheGateAgainstAnEarlierSnapshot() {
        for(kind in listOf("recovery","access")) Service().use {server ->
            val actual=server.transport();val store=Memory();var change=false;var acknowledgements=0;var teamID: String?=null
            val owner=session(TeamTransport {method,path,body ->
                val response=actual.request(method,path,body)
                if(change && method=="GET" && path.contains("/games?cursor=")) {change=false;server.change(kind,teamID)}
                if(path=="devices/recovery-review") acknowledgements++
                response
            },store)
            teamID=create(owner).id;val pending=owner.prepareCreate("待核对原请求","管理员")
            server.change("recovery");owner.refresh();change=true
            assertThrows(IllegalStateException::class.java) {owner.reconcileAfterRecovery(true)}
            assertEquals(0,acknowledgements);assertTrue(store.state.requiresRecoveryReview);assertEquals(pending,store.state.pending!!.id)
            owner.refresh();owner.reconcileAfterRecovery(true)
            assertFalse(store.state.requiresRecoveryReview);assertEquals(1,store.state.recoveryWork.size)
        }
    }

    @Test(timeout = 30000) fun restoreBetweenPermissionRefreshAndMutationImmediatelyClosesTheLocalGate() {
        for(control in listOf(false,true)) Service().use {server ->
            val actual=server.transport();val store=Memory();var restore=false
            val owner=session(TeamTransport {method,path,body ->
                if(restore && method=="POST" && (path.endsWith("/invitations") || path.contains("/handoff/"))) {restore=false;server.change("recovery")}
                actual.request(method,path,body)
            },store)
            val team=create(owner)
            val document=if(control) owner.match(team.id,publishFixture(server,actual,team).id) else null
            val id=if(document!=null) owner.prepareMatchControl(document,MatchControl.force,store.state.deviceID,true) else owner.prepareInvite(team.id,TeamRole.member)
            restore=true
            val fault=assertThrows(TeamServiceFault::class.java) {if(document!=null) owner.sendMatchControl(document.id) else owner.sendPending()}
            assertEquals("recovery_review",fault.code);assertTrue(store.state.requiresRecoveryReview)
            owner.reconcileAfterRecovery(true)
            val work=store.state.recoveryWork.single()
            if(document!=null) assertEquals(id,work.matchRequests.single().id) else assertEquals(id,work.directoryRequest!!.id)
            assertFalse(store.state.requiresRecoveryReview)
        }
    }

    @Test(timeout = 30000) fun currentIOSFixedInvitationIsReusableByMultipleMembersAndStableWhenFetchedAgain() {
        Service().use { server ->
            val store=Memory(); val owner=session(server.transport(),store); val team=create(owner)
            owner.prepareFixedInvite(team.id); owner.sendPending(); val first=store.state.fixedInvitations.getValue(team.id)
            repeat(2) {
                val member=session(server.transport()); member.refresh()
                assertEquals(TeamRole.member,member.invitationPreview(first.url).role)
                member.prepareJoin(first.token,"同名成员"); member.sendPending()
                assertEquals(TeamRole.member,member.refresh().teams.single().role)
            }
            owner.prepareFixedInvite(team.id); owner.sendPending()
            assertEquals(first,store.state.fixedInvitations.getValue(team.id))
            assertEquals(3,owner.members(team.id).size); assertEquals(1,owner.invitations(team.id).size)
        }
    }

    @Test(timeout = 30000) fun rotatingFixedLinkRetriesTheSameRequestAndNeverRemovesExistingMembers() {
        Service().use { server ->
            val actual=server.transport(); val store=Memory(); var drop=true
            val owner=session(TeamTransport { method,path,body ->
                val response=actual.request(method,path,body)
                if(path.endsWith("/team-invitation") && body?.get("rotate")?.jsonPrimitive?.boolean==true && drop) {drop=false;throw IOException("injected fixed-link response loss")}; response
            },store)
            val team=create(owner); owner.prepareFixedInvite(team.id); owner.sendPending(); val old=store.state.fixedInvitations.getValue(team.id)
            val member=session(server.transport()); member.refresh(); member.prepareJoin(old.token,"成员"); member.sendPending(); member.refresh()
            val id=owner.prepareFixedInvite(team.id,true); assertThrows(IOException::class.java) {owner.sendPending()}; assertEquals(id,store.state.pending!!.id)
            owner.sendPending(); val current=store.state.fixedInvitations.getValue(team.id)
            assertNotEquals(old.token,current.token); assertEquals(team.id,member.refresh().teams.single().id)
            assertEquals(410,assertThrows(TeamServiceFault::class.java) {member.invitationPreview(old.url)}.status)
            assertEquals(TeamRole.member,member.invitationPreview(current.url).role)
            assertEquals(1,owner.invitations(team.id).size)
        }
    }

    @Test(timeout = 30000) fun revokedFixedLinkAndDowngradedAdminDoNotKeepAnActiveInvitationCache() {
        Service().use { server ->
            val store=Memory(); val owner=session(server.transport(),store); val team=create(owner)
            val other=session(server.transport()); join(owner,team,other,TeamRole.member)
            owner.prepareMember(team.id,owner.members(team.id).single {it.id!=team.memberID},TeamRole.admin); owner.sendPending(); other.refresh()
            owner.prepareFixedInvite(team.id); owner.sendPending(); val first=store.state.fixedInvitations.getValue(team.id)
            other.prepareRevoke(team.id,first.invitation.invitationID); other.sendPending(); owner.invitations(team.id)
            assertTrue(store.state.fixedInvitations.isEmpty())
            owner.prepareFixedInvite(team.id); owner.sendPending()
            other.prepareMember(team.id,other.members(team.id).single {it.id==team.memberID},TeamRole.member); other.sendPending(); owner.refresh()
            assertTrue(store.state.fixedInvitations.isEmpty()); assertNull(store.state.lastCompletion)
        }
    }
    private fun pair(owner: TeamDirectorySession, team: RemoteTeam, target: TeamDirectorySession): DevicePairing {
        target.refresh(); target.preparePairing("新的安卓设备"); target.sendPending()
        val saved=target.snapshot().pairing!!; val preview=owner.previewPairing(team.id,saved.token)
        owner.preparePairApproval(preview,saved.token,true); owner.sendPending()
        val offered=target.pairing(saved.request.id); target.preparePairAcceptance(offered,true); target.sendPending(); target.refresh()
        return target.snapshot().pairing!!.request
    }

    @Test(timeout = 30000) fun pairingSharesOnlyTheSelectedMembershipAndNeverTransfersWriterAuthority() {
        Service().use { server ->
            val transport=server.transport(); val owner=session(transport); val team=create(owner); val game=publishFixture(server,transport,team)
            val target=session(server.transport()); val accepted=pair(owner,team,target)
            assertEquals("accepted",accepted.status); assertEquals(team.memberID,target.snapshot().directory!!.teams.single().memberID)
            assertNotEquals(owner.snapshot().deviceID,target.snapshot().deviceID); assertEquals(2,owner.teamDevices(team.id).size)
            assertFalse(target.match(team.id,game.id).canWrite); assertEquals(owner.snapshot().deviceID,target.match(team.id,game.id).writerDeviceID)
            assertTrue(target.snapshot().recoveryCards.isEmpty())
        }
    }

    @Test(timeout = 30000) fun lostPairAcceptanceResponseRetriesTheSavedRequestWithoutAddingAnotherDevice() {
        Service().use { server ->
            val owner=session(server.transport()); val team=create(owner); val transport=server.transport(); val store=Memory(); var drop=true
            val target=session(TeamTransport { method,path,body ->
                val response=transport.request(method,path,body)
                if(path.startsWith("pairings/") && path.endsWith("/accept") && drop) {drop=false;throw IOException("injected pairing accept loss")}; response
            },store)
            target.refresh(); target.preparePairing("新设备"); target.sendPending(); val saved=store.state.pairing!!
            val preview=owner.previewPairing(team.id,saved.token)
            assertThrows(IllegalArgumentException::class.java) { owner.preparePairApproval(preview,saved.token,false) }
            owner.preparePairApproval(preview,saved.token,true); owner.sendPending()
            val offered=target.pairing(saved.request.id); val id=target.preparePairAcceptance(offered,true)
            assertThrows(IOException::class.java) { target.sendPending() }; assertEquals(id,store.state.pending!!.id)
            target.sendPending(); assertEquals("accepted",store.state.pairing!!.request.status); assertEquals(2,owner.teamDevices(team.id).size)
        }
    }

    @Test(timeout = 30000) fun cancellingPairingMakesItsSecretUnusableWithoutGrantingMembership() {
        Service().use { server ->
            val owner=session(server.transport()); val team=create(owner); val target=session(server.transport()); target.refresh()
            target.preparePairing("取消测试"); target.sendPending(); val saved=target.snapshot().pairing!!
            target.preparePairCancellation(saved.request.id); target.sendPending()
            assertNull(target.snapshot().pairing); assertTrue(target.refresh().teams.isEmpty())
            assertEquals(410,assertThrows(TeamServiceFault::class.java) { owner.previewPairing(team.id,saved.token) }.status)
        }
    }

    @Test(timeout = 30000) fun lastAdminDeviceIsProtectedAndPairedDeviceSurvivesRemovingTheOriginal() {
        Service().use { server ->
            val owner=session(server.transport()); val team=create(owner); val original=owner.teamDevices(team.id).single()
            val failed=owner.prepareDeviceRemoval(team.id,original,true)
            assertEquals("last_admin_device",assertThrows(TeamServiceFault::class.java) { owner.sendPending() }.code); owner.keepRejectedAsDraft(failed)
            val target=session(server.transport()); pair(owner,team,target)
            owner.prepareDeviceRemoval(team.id,original,true); owner.sendPending(); assertTrue(owner.refresh().teams.isEmpty())
            assertEquals(team.memberID,target.refresh().teams.single().memberID); assertEquals(1,target.teamDevices(team.id).size)
            assertFalse(target.recoveryStatus(team.id).hasCode!!)
        }
    }

    @Test(timeout = 30000) fun managementRecoveryRevokesOldAccessRotatesTheCardAndKeepsGamePayload() {
        Service().use { server ->
            val transport=server.transport(); val owner=session(transport); val team=create(owner); val game=publishFixture(server,transport,team)
            val original=owner.match(team.id,game.id); val card=owner.snapshot().recoveryCards.single(); val input=ManagementRecoveryInput.parse(card.code)
            val target=session(server.transport()); target.refresh(); val preview=target.previewManagementRecovery(input)
            assertTrue(target.refresh().teams.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { target.prepareManagementRecovery(input,preview,"新管理员",false) }
            target.prepareManagementRecovery(input,preview,"新管理员",true); val newToken=target.snapshot().pending!!.body.getValue("newToken").jsonPrimitive.content
            target.sendPending(); target.refresh(); val next=target.snapshot().recoveryCards.single()
            assertEquals(2,next.version); assertEquals(newToken,next.token); assertNotEquals(card.token,next.token)
            assertTrue(owner.refresh().teams.isEmpty()); assertTrue(owner.snapshot().matches.isEmpty()); assertTrue(owner.snapshot().recoveryCards.isEmpty())
            val official=target.match(team.id,game.id); assertEquals(original.payload,official.payload); assertFalse(official.canWrite); assertTrue(official.writerEpoch>original.writerEpoch)
            assertEquals(404,assertThrows(TeamServiceFault::class.java) { target.previewManagementRecovery(input) }.status)
            target.confirmRecoveryCardSaved(team.id,next.version); assertTrue(target.snapshot().recoveryCards.single().confirmedSaved)
        }
    }

    @Test(timeout = 30000) fun lostManagementRecoveryResponseRecoversTheOnlyNewCardFromTheSameRequest() {
        Service().use { server ->
            val owner=session(server.transport()); create(owner); val input=ManagementRecoveryInput.parse(owner.snapshot().recoveryCards.single().code)
            val transport=server.transport(); val store=Memory(); var drop=true
            val target=session(TeamTransport { method,path,body ->
                val response=transport.request(method,path,body)
                if(path=="recovery/redeem" && drop) {drop=false;throw IOException("injected management recovery loss")}; response
            },store)
            target.refresh(); val preview=target.previewManagementRecovery(input); val id=target.prepareManagementRecovery(input,preview,"新管理员",true)
            val token=store.state.pending!!.body.getValue("newToken").jsonPrimitive.content
            assertThrows(IOException::class.java) { target.sendPending() }; assertEquals(id,store.state.pending!!.id)
            target.sendPending(); target.refresh(); assertEquals(token,store.state.recoveryCards.single().token); assertEquals(2,store.state.recoveryCards.single().version)
        }
    }

    @Test(timeout = 30000) fun rotatingRecoveryCardInvalidatesTheOldOneAndMigratesEarlierCreationReceipts() {
        Service().use { server ->
            val store=Memory(); val owner=session(server.transport(),store); val team=create(owner); val original=store.state.recoveryCards.single()
            store.state=store.state.copy(recoveryCards=emptyList()); owner.refresh(); assertEquals(original,store.state.recoveryCards.single())
            val status=owner.recoveryStatus(team.id)
            assertThrows(IllegalArgumentException::class.java) { owner.prepareRecoveryRotation(status,false) }
            owner.prepareRecoveryRotation(status,true); owner.sendPending(); owner.refresh()
            val current=store.state.recoveryCards.single(); assertEquals(2,current.version); assertNotEquals(original.token,current.token)
            assertEquals(404,assertThrows(TeamServiceFault::class.java) { owner.previewManagementRecovery(ManagementRecoveryInput.parse(original.code)) }.status)
        }
    }

    @Test(timeout = 30000) fun anExpiredJoinCanBeArchivedForExplicitReviewButNeverAutomaticallyRetried() {
        Service().use { server ->
            val owner=session(server.transport()); val team=create(owner); val target=session(server.transport()); target.refresh()
            owner.prepareInvite(team.id,TeamRole.member); val token=owner.sendPending().request.body.getValue("token").jsonPrimitive.content
            val id=target.prepareJoin(token,"成员"); val original=target.snapshot().pending
            server.change("advance","86400001")
            assertEquals(410,assertThrows(TeamServiceFault::class.java) {target.sendPending()}.status)
            assertThrows(IllegalArgumentException::class.java) {target.archiveRejectedOperation(id,false)}
            target.archiveRejectedOperation(id,true); assertEquals(original,target.snapshot().reviewedRequests.single().request)
            assertTrue(target.refresh().teams.isEmpty()); assertNull(target.snapshot().pending)
        }
    }

    @Test(timeout = 30000) fun aLostRetryCannotLeaveTheEarlierDefinitiveRejectionAsProofForRecreatingTheOperation() {
        Service().use { server ->
            val transport=server.transport(); val store=Memory(); var lose=false
            val owner=session(TeamTransport { method,path,body -> if(lose && method=="POST" && path.endsWith("/leave")) throw IOException("injected unknown retry outcome"); transport.request(method,path,body) },store)
            val team=create(owner); val id=owner.prepareLeave(team.id)
            assertEquals("last_admin",assertThrows(TeamServiceFault::class.java) {owner.sendPending()}.code)
            lose=true; assertThrows(IOException::class.java) {owner.sendPending()}
            assertNull(store.state.lastFailure); assertEquals(id,store.state.pending!!.id)
            assertThrows(IllegalArgumentException::class.java) {owner.archiveRejectedOperation(id,true)}
        }
    }

    @Test(timeout = 30000) fun expiredPairAcceptanceCanOnlyBeArchivedAfterExplicitConfirmation() {
        Service().use { server ->
            val owner=session(server.transport()); val team=create(owner); val target=session(server.transport()); target.refresh()
            target.preparePairing("过期测试"); target.sendPending(); val saved=target.snapshot().pairing!!
            owner.preparePairApproval(owner.previewPairing(team.id,saved.token),saved.token,true); owner.sendPending()
            val id=target.preparePairAcceptance(target.pairing(saved.request.id),true); val pending=target.snapshot().pending
            server.change("advance","600001")
            assertEquals(410,assertThrows(TeamServiceFault::class.java) { target.sendPending() }.status)
            assertThrows(IllegalArgumentException::class.java) { target.quarantineRejectedSecurity(id,false) }
            target.quarantineRejectedSecurity(id,true)
            assertEquals(pending,target.snapshot().reviewedRequests.single().request)
            target.preparePairing("新的配对"); target.sendPending(); assertNotEquals(saved.request.id,target.snapshot().pairing!!.request.id)
        }
    }

    @Test(timeout = 30000) fun lostSelfDeviceRemovalReceiptDoesNotRegrantAccessOnRetry() {
        Service().use { server ->
            val transport=server.transport(); val store=Memory(); var drop=true
            val owner=session(TeamTransport { method,path,body ->
                val response=transport.request(method,path,body)
                if(method=="DELETE" && path.contains("/devices/") && drop) {drop=false;throw IOException("injected self-removal loss")}; response
            },store)
            val team=create(owner); val target=session(server.transport()); pair(owner,team,target)
            val device=owner.teamDevices(team.id).single { it.isCurrent==1 }
            val id=owner.prepareDeviceRemoval(team.id,device,true)
            assertThrows(IOException::class.java) { owner.sendPending() }; assertEquals(id,store.state.pending!!.id)
            assertTrue(owner.refresh().teams.isEmpty()); owner.sendPending(); assertTrue(owner.refresh().teams.isEmpty())
            assertNull(store.state.pending); assertEquals(team.memberID,target.refresh().teams.single().memberID)
        }
    }

    @Test(timeout = 30000) fun removingThePairedDeviceClearsItsAcceptedPairingCacheAndKeepsTheOriginalDeviceActive() {
        Service().use { server ->
            val owner=session(server.transport()); val team=create(owner); val target=session(server.transport()); pair(owner,team,target)
            val self=target.teamDevices(team.id).single { it.isCurrent==1 }
            target.prepareDeviceRemoval(team.id,self,true); target.sendPending(); assertTrue(target.refresh().teams.isEmpty())
            assertNull(target.snapshot().pairing); assertEquals(team.memberID,owner.refresh().teams.single().memberID)
        }
    }

    @Test(timeout = 30000) fun credentialRotationKeepsDeviceAndTeamIdentityButRetiresTheOldBearer() {
        Service().use { server ->
            val vault=Credentials(); val old=vault.current; val session=session(server.transport(old)); val team=create(session)
            val device=session.snapshot().deviceID!!; val encryption=vault.stableEncryptionSecret()
            assertEquals(device,TeamCredentialRotator(vault,server::transport).rotate(device))
            assertNull(vault.pending); assertNotEquals(old,vault.current); assertArrayEquals(encryption,vault.stableEncryptionSecret())
            assertEquals(401,assertThrows(TeamServiceFault::class.java) { TeamDirectoryClient(server.transport(old)).teams() }.status)
            assertEquals(team.memberID,TeamDirectoryClient(server.transport(vault.current)).teams().teams.single().memberID)
            assertEquals(device,TeamDirectoryClient(server.transport(vault.current)).enroll("同一设备"))
        }
    }

    @Test(timeout = 30000) fun lostCredentialPrepareResponseCommitsTheSavedCandidateWithoutPreparingAgain() {
        Service().use { server ->
            val vault=Credentials(); val owner=session(server.transport(vault.current)); create(owner); val device=owner.snapshot().deviceID!!
            var drop=true; var prepares=0
            val transportFor: (String)->TeamTransport = { token -> val actual=server.transport(token); TeamTransport { method,path,body ->
                val response=actual.request(method,path,body)
                if(path.endsWith("credential-prepare")) { prepares++; if(drop) {drop=false;throw IOException("injected prepare response loss")} }; response
            } }
            val rotator=TeamCredentialRotator(vault,transportFor)
            assertThrows(IOException::class.java) { rotator.rotate(device) }; val pending=vault.pending!!
            assertEquals(device,TeamCredentialRotator(vault,transportFor).rotate(device)); assertEquals(pending.token,vault.current); assertEquals(1,prepares)
        }
    }

    @Test(timeout = 30000) fun lostCredentialCommitResponseResumesWithTheNewBearerAfterRestart() {
        Service().use { server ->
            val vault=Credentials(); val old=vault.current; val owner=session(server.transport(old)); create(owner); val device=owner.snapshot().deviceID!!
            var drop=true
            val transportFor: (String)->TeamTransport = { token -> val actual=server.transport(token); TeamTransport { method,path,body ->
                val response=actual.request(method,path,body)
                if(path.endsWith("credential-commit") && drop) {drop=false;throw IOException("injected commit response loss")}; response
            } }
            assertThrows(IOException::class.java) { TeamCredentialRotator(vault,transportFor).rotate(device) }
            val pending=vault.pending!!; assertEquals(old,vault.current)
            assertEquals(device,TeamCredentialRotator(vault,transportFor).rotate(device)); assertEquals(pending.token,vault.current)
        }
    }

    @Test(timeout = 30000) fun credentialLocalFinishFailureCanRetrieveTheSameCommittedRotation() {
        Service().use { server ->
            val vault=Credentials(); val owner=session(server.transport(vault.current)); create(owner); val device=owner.snapshot().deviceID!!
            vault.failFinish=true
            assertThrows(IOException::class.java) { TeamCredentialRotator(vault,server::transport).rotate(device) }
            val pending=vault.pending!!; vault.failFinish=false
            assertEquals(device,TeamCredentialRotator(vault,server::transport).rotate(device)); assertEquals(pending.token,vault.current)
        }
    }

    @Test fun failedCredentialStagingNeverOpensATransport() {
        val vault=Credentials(); vault.failBegin=true; var calls=0
        val rotator=TeamCredentialRotator(vault) { calls++; error("must not open a transport") }
        assertThrows(IOException::class.java) { rotator.rotate(UUID.randomUUID().toString()) }; assertEquals(0,calls)
    }

    @Test(timeout = 30000) fun mismatchedCredentialDeviceReceiptRetainsTheOnlyCandidateForReview() {
        Service().use { server ->
            val vault=Credentials(); val owner=session(server.transport(vault.current)); create(owner); val device=owner.snapshot().deviceID!!
            val rotator=TeamCredentialRotator(vault,server::transport)
            assertThrows(IllegalStateException::class.java) { rotator.rotate(UUID.randomUUID().toString()) }
            val candidate=vault.pending!!; assertEquals(device,rotator.rotate(device)); assertEquals(candidate.token,vault.current)
        }
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
