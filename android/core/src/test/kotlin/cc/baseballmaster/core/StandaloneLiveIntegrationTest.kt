package cc.baseballmaster.core

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class StandaloneLiveIntegrationTest {
    private class Memory: LiveCredentialStorage {
        override val monitor=Any();var rows=emptyList<LiveBinding>();var reject: (List<LiveBinding>)->Boolean={false}
        override fun load()=rows
        override fun save(bindings: List<LiveBinding>) {if(reject(bindings)) throw IOException("injected credential save");rows=bindings}
    }
    private class Service: AutoCloseable {
        private val script=File(System.getProperty("parity.fixtures")).parentFile.parentFile.resolve("scripts/android/an06-local-live.mjs")
        private val process=ProcessBuilder("node",script.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val input=process.outputStream.bufferedWriter();private val output=process.inputStream.bufferedReader()
        private val initial=GameJson.parseToJsonElement(output.readLine()).jsonObject
        var now=initial.getValue("now").jsonPrimitive.long;private set
        val endpoint="http://127.0.0.1:${initial.getValue("port").jsonPrimitive.int}/livestreaming/novideo"
        val transport=LiveHttpTransport(endpoint,true)
        private val public=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        fun status(code: String): Pair<Int,JsonObject?> = public.newCall(Request.Builder().url("$endpoint/api/sessions/$code").build()).execute().use {r ->
            r.code to r.body?.string()?.let {GameJson.parseToJsonElement(it).jsonObject}
        }
        fun change(milliseconds: Long=0): Int {
            input.write(buildJsonObject {put("kind",if(milliseconds==0L) "count" else "advance");put("milliseconds",milliseconds)}.toString());input.newLine();input.flush()
            val reply=GameJson.parseToJsonElement(output.readLine()).jsonObject;now=reply.getValue("now").jsonPrimitive.long;return reply.getValue("count").jsonPrimitive.int
        }
        override fun close() {input.close();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();output.close()}
    }
    private fun game(mode: GameMode=GameMode.standard): Game {
        fun team(name: String)=Team(UUID.randomUUID().toString(),name,(1..12).map {Player(UUID.randomUUID().toString(),"球员$it",if(it==1) "00" else it.toString(),position=it.coerceAtMost(10))})
        val home=team("主队");val away=team("客队")
        return MatchFactory.create(UUID.randomUUID().toString(),home,away,Rules(mode),home.id)
    }

    @Test(timeout=30000) fun publishesAllThreeModesUsingTheOriginalStrictPublicSchemaAndNoPrivateGameFields() {
        Service().use {server ->
            for(mode in GameMode.entries) {
                val storage=Memory();val live=StandaloneLive(storage,server.transport,{server.now})
                var g=game(mode);g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.ball,at=server.now))
                live.start(g);live.synchronize(mapOf(g.id to g))
                val binding=storage.rows.single();assertEquals(g.revision,binding.ackRevision);assertNotNull(live.url(g.id));assertFalse(binding.toString().contains(binding.token))
                val read=server.status(binding.code!!);assertEquals(200,read.first)
                val projection=read.second!!.getValue("snapshot").jsonObject
                assertEquals(mode.name,projection.getValue("mode").jsonPrimitive.content)
                assertEquals("00",projection.getValue("away").jsonObject.getValue("lineup").jsonArray.first().jsonObject.getValue("player").jsonObject.getValue("number").jsonPrimitive.content)
                assertFalse(projection.keys.any {it in setOf("journal","checkpoint","correctionRevisions","token","homeTeam","awayTeam","appleSourceID")})
                assertEquals(g.balls,projection.getValue("balls").jsonPrimitive.int)
                assertEquals(if(mode==GameMode.slowPitch) 10 else 9,projection.getValue("home").jsonObject.getValue("lineup").jsonArray.size)
                live.close(g.id);live.synchronize(mapOf(g.id to g));assertTrue(storage.rows.isEmpty());assertEquals(404,server.status(binding.code).first)
            }
            assertEquals(0,server.change())
        }
    }

    @Test(timeout=30000) fun lostCreationResponseCanBeClosedByItsOriginalIdentityWithoutCreatingAnotherLink() {
        Service().use {server ->
            val storage=Memory();var lose=true;val live=StandaloneLive(storage,LiveTransport {m,c,t,b ->
                val response=server.transport.send(m,c,t,b)
                if(m=="POST" && c==null && lose) {lose=false;throw IOException("injected lost creation reply")};response
            },{server.now})
            val g=game();live.start(g);val pending=storage.rows.single();live.synchronize(mapOf(g.id to g))
            assertNull(storage.rows.single().code);assertEquals(1,server.change())
            live.close(g.id);live.synchronize(mapOf(g.id to g))
            assertTrue(storage.rows.isEmpty());assertEquals(0,server.change());assertNotNull(pending.requestID)
        }
    }

    @Test(timeout=30000) fun lostCreationAndUpdateAcknowledgementsResumeSameCredentialAndRevision() {
        Service().use {server ->
            val storage=Memory();var loseCreate=true;var loseUpdate=false
            val transport=LiveTransport {m,c,t,b -> val result=server.transport.send(m,c,t,b)
                if(m=="POST" && c==null && loseCreate) {loseCreate=false;throw IOException("lost creation")}
                if(m=="PUT" && loseUpdate) {loseUpdate=false;throw IOException("lost update")};result}
            var live=StandaloneLive(storage,transport,{server.now});var g=game();live.start(g);val request=storage.rows.single().requestID;val token=storage.rows.single().token
            live.synchronize(mapOf(g.id to g));assertEquals(1,server.change())
            live=StandaloneLive(storage,transport,{server.now});live.synchronize(mapOf(g.id to g));val code=storage.rows.single().code
            assertEquals(1,server.change());assertEquals(request,storage.rows.single().requestID);assertEquals(token,storage.rows.single().token)
            g=MatchEngine.apply(g,Command("play",outcome=Outcome.single,at=server.now+1000));loseUpdate=true
            live.synchronize(mapOf(g.id to g));assertEquals(0L,storage.rows.single().ackRevision)
            live.retry(g.id);live.synchronize(mapOf(g.id to g));assertEquals(g.revision,storage.rows.single().ackRevision);assertEquals(code,storage.rows.single().code)
        }
    }

    @Test(timeout=30000) fun finalHeartbeatDoesNotExtendTheOneHourDeletionDeadline() {
        Service().use {server ->
            val storage=Memory();val live=StandaloneLive(storage,server.transport,{server.now});var g=game()
            live.start(g);live.synchronize(mapOf(g.id to g));val code=storage.rows.single().code!!
            g=MatchEngine.apply(g,Command("finish",text="scorerDecision",at=server.now));live.synchronize(mapOf(g.id to g))
            val expires=storage.rows.single().expiresAt;assertTrue(live.messages.getValue(g.id).startsWith("终场已同步"))
            server.change(3_000_000);live.synchronize(mapOf(g.id to g));assertEquals(expires,storage.rows.single().expiresAt)
            assertEquals(0,server.change(600_001));live.synchronize(mapOf(g.id to g));assertTrue(storage.rows.isEmpty());assertNull(live.url(g.id));assertEquals(404,server.status(code).first)
        }
    }

    @Test(timeout=30000) fun sameRevisionDifferentContentBlocksInsteadOfOverwritingAndCloseStillWorks() {
        Service().use {server ->
            val storage=Memory();val live=StandaloneLive(storage,server.transport,{server.now});val g=game();live.start(g);live.synchronize(mapOf(g.id to g))
            val original=server.status(storage.rows.single().code!!).second!!.getValue("snapshot")
            val changed=g.copy(home=g.home.copy(name="不应覆盖的名字"));live.synchronize(mapOf(g.id to changed))
            assertTrue(storage.rows.single().blocked);assertEquals(original,server.status(storage.rows.single().code!!).second!!.getValue("snapshot"))
            live.close(g.id);live.synchronize(mapOf(g.id to changed));assertTrue(storage.rows.isEmpty())
        }
    }

    @Test fun storageFailureOrUnusableLocalStateCannotOpenABroadcastOrLeakItsCredential() {
        val storage=Memory();var calls=0;val live=StandaloneLive(storage,LiveTransport {_,_,_,_ -> calls++;error("must not send")})
        storage.reject={true};assertThrows(IOException::class.java) {live.start(game())};assertTrue(storage.rows.isEmpty());assertNotNull(live.credentialError)
        live.synchronize(emptyMap());assertEquals(0,calls)
        val isolated=StandaloneLive(Memory(),LiveTransport {_,_,_,_ -> calls++;error("must not send")})
        assertThrows(IllegalArgumentException::class.java) {isolated.start(game().copy(practice=true))}
        assertThrows(IllegalArgumentException::class.java) {isolated.start(game().let {it.copy(home=it.home.copy(source="shared"))})}
        assertThrows(IllegalArgumentException::class.java) {isolated.start(game().copy(isScheduled=true))}
        assertEquals(0,calls)
    }

    @Test(timeout=30000) fun restoreDetachesOldPublicationAndOnlyDeletesAfterRestart() {
        Service().use {server ->
            val storage=Memory();var live=StandaloneLive(storage,server.transport,{server.now});val g=game();live.start(g);live.synchronize(mapOf(g.id to g));val code=storage.rows.single().code!!
            live.prepareForRestore();assertTrue(storage.rows.single().closing);assertNull(live.url(g.id))
            live=StandaloneLive(storage,server.transport,{server.now});live.synchronize(mapOf(g.id to g.copy(homeRuns=2)))
            assertTrue(storage.rows.isEmpty());assertEquals(404,server.status(code).first)
        }
    }
    @Test(timeout=30000) fun restoreDuringCreationCannotReviveTheLinkOrUploadTheOldSnapshot() {
        Service().use {server ->
            val storage=Memory();lateinit var live: StandaloneLive;var updates=0
            live=StandaloneLive(storage,LiveTransport {m,c,t,b ->
                val response=server.transport.send(m,c,t,b)
                if(m=="POST" && c==null) live.prepareForRestore()
                if(m=="PUT") updates++
                response
            },{server.now})
            val g=game();live.start(g);live.synchronize(mapOf(g.id to g))
            assertTrue(storage.rows.single().closing);assertNull(live.url(g.id));assertEquals(0,updates)
            live.synchronize(emptyMap());assertEquals(0,server.change());assertTrue(storage.rows.isEmpty())
        }
    }
}
