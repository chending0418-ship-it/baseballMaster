package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import androidx.room.Room
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlinx.coroutines.runBlocking

/** AtomicFile/AES-GCM under Android JVM; no Activity/UI, no real Keystore claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TeamDirectoryFileTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun state(): TeamDirectoryState {
        val epoch = UUID.randomUUID().toString()
        return TeamDirectoryState(directory = TeamDirectory(emptyList(), epoch), pending = PendingDirectoryRequest(
            DirectoryRequestKind.join, buildJsonObject { put("operationID", UUID.randomUUID().toString()); put("token", TeamSecrets.random()); put("nickname", "成员称呼") }, epoch))
    }
    private fun folder() = Files.createTempDirectory("android-team-directory-").toFile()

    @Test fun encryptedPendingRequestSurvivesReopenWithoutWritingAnyPlaintextSecret() {
        val dir = folder()
        try {
            val value = state(); val store = TeamDirectoryFile(dir, { key.copyOf() })
            assertEquals(TeamDirectoryState(), store.load()); store.save(value)
            assertEquals(value, TeamDirectoryFile(dir, { key.copyOf() }).load())
            val bytes = File(dir,"directory-v1.sealed").readBytes()
            for (text in listOf("成员称呼", "operationID", value.pending!!.body.getValue("token").jsonPrimitive.content))
                assertFalse(String(bytes, Charsets.UTF_8).contains(text))
            store.save(value); assertFalse(bytes.contentEquals(File(dir,"directory-v1.sealed").readBytes()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun viewingRequestLinkReceiptAndReviewArchiveSurviveEncryptionAndFailedReplacement() {
        val dir=folder()
        try {
            val game=UUID.randomUUID().toString();val team=UUID.randomUUID().toString();val member=UUID.randomUUID().toString();val epoch=UUID.randomUUID().toString();val id=UUID.randomUUID().toString();val token=TeamSecrets.random()
            val request=PendingMatchWatch(id,team,game,MatchWatchAction.invitation,buildJsonObject {put("operationID",id);put("token",token)},member,1,epoch,1)
            val receipt=CompletedMatchWatch(request,buildJsonObject {put("invitationID",UUID.randomUUID().toString());put("expiresAt",System.currentTimeMillis()+86_400_000)})
            val link=MatchWatchLink(game,id,token,System.currentTimeMillis()+86_400_000,1,false)
            val archive=PreservedRecoveryWork(UUID.randomUUID().toString(),epoch,epoch,System.currentTimeMillis(),watchRequests=listOf(request))
            val value=TeamDirectoryState(watchPending=mapOf(game to request),watchReceipts=mapOf(game to receipt),watchLinks=mapOf(game to link),recoveryWork=listOf(archive))
            val store=TeamDirectoryFile(dir,{key.copyOf()});store.save(value)
            assertEquals(value,TeamDirectoryFile(dir,{key.copyOf()}).load());assertFalse(File(dir,"directory-v1.sealed").readBytes().toString(Charsets.UTF_8).contains(token))
            assertFalse(request.toString().contains(token));assertFalse(receipt.toString().contains(token))
            assertThrows(IOException::class.java) {TeamDirectoryFile(dir,{key.copyOf()}) {throw IOException("injected viewing write")}.save(value.copy(watchPending=emptyMap()))}
            assertEquals(value,store.load())
        } finally {dir.deleteRecursively()}
    }
    @Test fun foregroundPresenceNeverEnrollsAndOnlyHeartbeatsAnAlreadyEnabledOwnedBroadcast() = runBlocking {
        val dir=folder();val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),LocalDatabase::class.java).build()
        try {
            val store=TeamDirectoryFile(dir,{key.copyOf()});val calls=mutableListOf<String>()
            val team=UUID.randomUUID().toString();val member=UUID.randomUUID().toString();val game=UUID.randomUUID().toString();val device=UUID.randomUUID().toString();val epoch=UUID.randomUUID().toString()
            val directory=TeamDirectory(listOf(RemoteTeam(team,"球队",1,TeamRole.admin,member,1)),epoch)
            val raw=buildJsonObject {
                put("id",game);put("teamID",team);put("writerDeviceID",device);put("responsibleMemberID",member);put("serverRevision",1);put("writerEpoch",1);put("digest","a".repeat(64));put("deleted",false);put("canWrite",true)
                put("liveEnabled",true);put("liveCode","a".repeat(32));put("liveExpiresAt",System.currentTimeMillis()+60_000)
                put("payload",buildJsonObject {put("id",game);put("state",buildJsonObject {put("isFinal",false)})})
            }
            val session=TeamDirectorySession(TeamDirectoryClient(TeamTransport {method,path,_ ->
                calls+="$method $path"
                when(path) {"teams"->TeamDirectoryJson.format.encodeToJsonElement(directory).jsonObject;"teams/$team/games/$game","teams/$team/games/$game/live-heartbeat"->raw;else->error("unexpected request")}
            }),store,"安卓")
            val api=AndroidTeamSharing(GameRepository(db),session)
            api.maintainSharedLive();assertTrue(calls.isEmpty())
            val cache=CachedSharedMatch(SharedMatchDocument(raw),member,1,epoch)
            val state=TeamDirectoryState(deviceID=device,directory=directory,matches=mapOf(game to cache))
            store.save(state.copy(requiresRecoveryReview=true));api.maintainSharedLive();assertTrue(calls.isEmpty())
            store.save(state.copy(deviceID=UUID.randomUUID().toString()));api.maintainSharedLive();assertTrue(calls.isEmpty())
            store.save(state);api.maintainSharedLive()
            assertEquals(1,calls.count {it=="POST teams/$team/games/$game/live-heartbeat"});assertTrue(calls.none {it=="POST devices"})
        } finally {db.close();dir.deleteRecursively()}
    }

    @Test fun failedReplacementRetainsTheOriginalPendingRequestAndExactCiphertext() {
        val dir = folder()
        try {
            val store = TeamDirectoryFile(dir, { key.copyOf() }); val original = state(); store.save(original)
            val sealed = File(dir,"directory-v1.sealed").readBytes()
            val failing = TeamDirectoryFile(dir, { key.copyOf() }) { throw IOException("injected full") }
            assertThrows(IOException::class.java) { failing.save(original.copy(pending = null)) }
            assertArrayEquals(sealed, File(dir,"directory-v1.sealed").readBytes()); assertEquals(original, store.load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptionAndLostKeyFailClosedAndNeverReplaceTheOriginalFile() {
        val dir = folder()
        try {
            val store = TeamDirectoryFile(dir, { key.copyOf() }); store.save(state())
            val file = File(dir,"directory-v1.sealed"); val before = file.readBytes()
            assertThrows(Exception::class.java) { TeamDirectoryFile(dir, { ByteArray(32) { 9 } }).load() }
            assertArrayEquals(before, file.readBytes())
            val corrupt = before.copyOf(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte(); file.writeBytes(corrupt)
            assertThrows(Exception::class.java) { store.load() }; assertArrayEquals(corrupt, file.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun sharedCacheBindingsAndReviewedDraftsSurviveAnEncryptedReopen() {
        val dir = folder()
        try {
            val original = state(); val id = UUID.randomUUID().toString(); val member = UUID.randomUUID().toString()
            val profile = SharedTeamProfile(id,"共享队",1,"共享","",listOf(SharedRosterPlayer.new("球员","English",listOf("0","00"))))
            val pending = requireNotNull(original.pending); val directory = requireNotNull(original.directory)
            val draft = ReviewedDirectoryRequest(pending,DirectoryFailure(pending.id,409,"roster_conflict"))
            val saved = original.copy(directory=TeamDirectory(listOf(RemoteTeam(id,"共享队",1,TeamRole.admin,member,1,1)),directory.recoveryEpoch),
                profiles=mapOf(id to CachedTeamRoster(profile,member,1,directory.recoveryEpoch)),bindings=listOf(SharedTeamBinding(UUID.randomUUID().toString(),id)),reviewedRequests=listOf(draft))
            TeamDirectoryFile(dir,{key.copyOf()}).save(saved)
            assertEquals(saved,TeamDirectoryFile(dir,{key.copyOf()}).load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun preparingLocalPublicationNeverChangesRoomOrAddsSharingSecretsToBusinessBackup() = runBlocking {
        val dir = folder(); val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),LocalDatabase::class.java).build()
        try {
            val repo = GameRepository(db); val team = repo.createTeam("本机队",(0..8).joinToString("\n") { "球员$it,${if (it==0) "00" else it}" })
            val original = db.dao().allTeams()
            val store = TeamDirectoryFile(dir,{key.copyOf()}); val epoch = UUID.randomUUID().toString()
            store.save(TeamDirectoryState(deviceID=UUID.randomUUID().toString(),directory=TeamDirectory(emptyList(),epoch)))
            val session = TeamDirectorySession(TeamDirectoryClient(TeamTransport { _,_,_ -> error("Preparing must never send a request") }),store,"安卓")
            val sharing = AndroidTeamSharing(repo,session); val id = sharing.preparePublish(team.id,"管理员")
            assertEquals(id,store.load().pending!!.id); assertEquals(original,db.dao().allTeams()); assertTrue(db.dao().allGames().isEmpty())
            val backup = AppleWireJson.parse(repo.exportDeviceBackup(team.id)).jsonObject
            val payload = AppleWireJson.parse(java.util.Base64.getDecoder().decode(backup.getValue("payload").jsonPrimitive.content)).toString()
            val pending = store.load().pending!!
            assertFalse(payload.contains(id)); assertFalse(payload.contains(pending.body.getValue("recoveryToken").jsonPrimitive.content))
            assertEquals(original,db.dao().allTeams())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun aFullOpaqueMatchAndItsPendingHandoffSurviveEncryptedReopenAndFailedReplacement() {
        val dir=folder()
        try {
            val epoch=UUID.randomUUID().toString(); val team=UUID.randomUUID().toString(); val game=UUID.randomUUID().toString(); val device=UUID.randomUUID().toString(); val member=UUID.randomUUID().toString()
            val raw=buildJsonObject {
                put("id",game); put("teamID",team); put("writerDeviceID",device); put("responsibleMemberID",member)
                put("serverRevision",1); put("writerEpoch",1); put("digest","a".repeat(64)); put("deleted",false); put("canWrite",true)
                put("payload",buildJsonObject { put("id",UUID.randomUUID().toString()); put("unknownHistory",buildJsonArray { add("keep-original-audit") }) })
            }
            val id=UUID.randomUUID().toString(); val request=PendingMatchControl(id,team,game,MatchControl.propose,buildJsonObject { put("operationID",id) },member,1,epoch)
            val value=TeamDirectoryState(deviceID=device,matches=mapOf(game to CachedSharedMatch(SharedMatchDocument(raw),member,1,epoch)),matchPending=mapOf(game to request))
            val store=TeamDirectoryFile(dir,{key.copyOf()}); store.save(value)
            assertEquals(value,TeamDirectoryFile(dir,{key.copyOf()}).load())
            val failing=TeamDirectoryFile(dir,{key.copyOf()}) { throw IOException("injected handoff full") }
            assertThrows(IOException::class.java) { failing.save(value.copy(matchPending=emptyMap())) }
            assertEquals(value,store.load()); assertFalse(File(dir,"directory-v1.sealed").readText().contains("keep-original-audit"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun recoveryCandidateAndSavedCardSurviveEncryptedReopenWithoutPlaintextSecrets() {
        val dir=folder()
        try {
            val epoch=java.util.UUID.randomUUID().toString(); val team=java.util.UUID.randomUUID().toString(); val id=java.util.UUID.randomUUID().toString()
            val token=TeamSecrets.random(); val candidate=TeamSecrets.random()
            val pending=PendingDirectoryRequest(DirectoryRequestKind.security,buildJsonObject {
                put("operationID",id); put("teamID",team); put("token",token); put("newToken",candidate); put("confirmReset",true)
            },epoch,securityAction=TeamSecurityAction.recoveryRedeem)
            val value=TeamDirectoryState(pending=pending,recoveryCards=listOf(SavedManagementCard(team,"原管理员",token,1)))
            TeamDirectoryFile(dir,{key.copyOf()}).save(value)
            assertEquals(value,TeamDirectoryFile(dir,{key.copyOf()}).load())
            val bytes=File(dir,"directory-v1.sealed").readBytes().toString(Charsets.UTF_8)
            assertFalse(bytes.contains(token)); assertFalse(bytes.contains(candidate))
        } finally { dir.deleteRecursively() }
    }
    @Test fun reusableMemberInvitationSurvivesEncryptedReopenWithoutPlaintextToken() {
        val dir=folder()
        try {
            val id=java.util.UUID.randomUUID().toString(); val saved=FixedTeamInvitation(id,TeamSecrets.random(),TeamInvitation(java.util.UUID.randomUUID().toString(),8640000000000000L,TeamRole.member))
            val value=TeamDirectoryState(fixedInvitations=mapOf(id to saved)); TeamDirectoryFile(dir,{key.copyOf()}).save(value)
            assertEquals(value,TeamDirectoryFile(dir,{key.copyOf()}).load())
            assertFalse(File(dir,"directory-v1.sealed").readBytes().toString(Charsets.UTF_8).contains(saved.token))
        } finally {dir.deleteRecursively()}
    }
    @Test fun openedInvitationAndPairingLinksResumeOnlyFromEncryptedDeviceStorage() {
        val dir=folder()
        try {
            val links=IncomingTeamLinkKind.entries.map {IncomingTeamLink.parse("baseballmaster://${it.name}#${TeamSecrets.random()}")}
            val value=TeamDirectoryState(incomingLinks=links);TeamDirectoryFile(dir,{key.copyOf()}).save(value)
            assertEquals(value,TeamDirectoryFile(dir,{key.copyOf()}).load())
            val bytes=File(dir,"directory-v1.sealed").readBytes().toString(Charsets.UTF_8)
            links.forEach {assertFalse(bytes.contains(it.token))}
        } finally {dir.deleteRecursively()}
    }
    @Test fun recoveryReviewArchiveKeepsTheOriginalPendingBodyOnlyInsideEncryptedStorage() {
        val dir=folder()
        try {
            val id=java.util.UUID.randomUUID().toString();val epoch=java.util.UUID.randomUUID().toString();val secret=TeamSecrets.random()
            val request=PendingDirectoryRequest(DirectoryRequestKind.create,kotlinx.serialization.json.buildJsonObject {
                put("operationID",kotlinx.serialization.json.JsonPrimitive(id));put("recoveryToken",kotlinx.serialization.json.JsonPrimitive(secret))
            },epoch)
            val archive=PreservedRecoveryWork(java.util.UUID.randomUUID().toString(),epoch,java.util.UUID.randomUUID().toString(),123,directoryRequest=request)
            val value=TeamDirectoryState(recoveryWork=listOf(archive));TeamDirectoryFile(dir,{key.copyOf()}).save(value)
            assertEquals(value,TeamDirectoryFile(dir,{key.copyOf()}).load())
            assertFalse(File(dir,"directory-v1.sealed").readBytes().toString(Charsets.UTF_8).contains(secret))
            assertFalse(archive.toString().contains(secret))
        } finally {dir.deleteRecursively()}
    }
}
