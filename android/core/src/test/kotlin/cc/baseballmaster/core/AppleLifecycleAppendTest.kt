package cc.baseballmaster.core

import java.io.File
import java.math.BigDecimal
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleLifecycleAppendTest {
    private val time=1_790_000_000_000L
    private fun source(): ByteArray {
        fun team(prefix:String,opponent:Boolean)=Team("$prefix-0000-0000-0000-000000000000","生命周期合成球队",
            (1..9).map {Player("$prefix-0000-0000-0000-${it.toString().padStart(12,'0')}","球员$it","$it",position=it)},isOpponent=opponent)
        val away=team("a1000000",false);val home=team("b1000000",true)
        val initial=MatchFactory.create("c1000000-0000-0000-0000-000000000000",home,away,Rules(),away.id).copy(createdAt=time,updatedAt=time)
        return AppleNativePitchExport.encode(MatchEngine.apply(initial,Command("pitch",pitch=PitchAction.ball,at=time+1000),"d1000000-0000-0000-0000-000000000001"))
    }
    private fun stored(bytes:ByteArray)=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
    private fun append(bytes:ByteArray,c:Command,n:Int):ByteArray=AppleLifecycleAppend.append(bytes,
        stored(bytes).getValue("id").jsonPrimitive.content,c,"e1000000-0000-0000-0000-${n.toString().padStart(12,'0')}")
    private fun save(name:String,bytes:ByteArray) {
        File(System.getProperty("wire.output"),"lifecycle/$name.bmbackup").also {it.parentFile.mkdirs();it.writeBytes(bytes)}
    }
    @Test fun finishAndReopenPreserveEveryPriorIdentityAndCheckpoint() {
        val source=source();val original=stored(source).getValue("state").jsonObject
        val final=append(source,Command("finish",text="scorerDecision",at=time+5000),1)
        val ended=AppleSourceBackup.prepare(final).games().single()
        assertTrue(ended.isFinal);assertEquals(time+5000,ended.endedAt);assertEquals(4000,ended.clockElapsedMs);assertNull(ended.clockRunningSince)
        val state=stored(final).getValue("state").jsonObject
        for(key in listOf("scoringEvents","playLog")) assertEquals(original.getValue(key).jsonArray,state.getValue(key).jsonArray.dropLast(1))
        assertEquals(original.getValue("historyJournal").jsonObject.getValue("checkpoint"),state.getValue("historyJournal").jsonObject.getValue("checkpoint"))
        assertEquals(original.getValue("plateAppearances"),state.getValue("plateAppearances"))
        save("finished",final)
        val reopened=append(final,Command("reopen",at=time+6000),2)
        val resumed=AppleSourceBackup.prepare(reopened).games().single()
        assertFalse(resumed.isFinal);assertNull(resumed.endedAt);assertNull(resumed.clockRunningSince);assertEquals(1,resumed.balls)
        save("reopened",reopened)
    }
    @Test fun suspendedGameClockAndNotesRetainTimeAndWholeHistory() {
        val source=source()
        val suspended=append(source,Command("suspend",at=time+5000),1)
        assertFalse(AppleSourceBackup.prepare(suspended).games().single().isFinal)
        val resumed=append(suspended,Command("clock",text="resume",at=time+7000),2)
        val paused=append(resumed,Command("clock",text="pause",at=time+10000),3)
        assertEquals(7000,AppleSourceBackup.prepare(paused).games().single().clockElapsedMs)
        val note=append(paused,Command("note",text="保留完整来源的合成备注",at=time+11000),4)
        save("suspended",suspended);save("clock-paused",paused);save("noted",note)
        assertEquals(5,stored(note).getValue("state").jsonObject.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.size)
    }
    @Test fun invalidRequestsCannotRewriteOrDropSourceData() {
        val source=source();val original=source.copyOf()
        for(command in listOf(Command("finish",text="suspended",at=time+5000),Command("finish",text="scorerDecision",at=time),
            Command("clock",text="display",at=time+5000),Command("note",text="备注",recordingMethod="situation",at=time+5000),Command("play",outcome=Outcome.single,at=time+5000)))
            assertThrows(IllegalArgumentException::class.java) {append(source,command,1)}
        assertThrows(IllegalArgumentException::class.java) {AppleLifecycleAppend.append(source,stored(source).getValue("id").jsonPrimitive.content,
            Command("finish",text="scorerDecision",at=time+5000),"d1000000-0000-0000-0000-000000000001")}
        assertArrayEquals(original,source)
    }
    @Test fun sourceClockFractionsArePreservedInsteadOfRoundingTheUIProjection() {
        val bytes=source();val outer=AppleWireJson.parse(bytes).jsonObject
        val snapshot=AppleBackupEnvelope.inspect(bytes).snapshot
        val original=stored(bytes);val state=original.getValue("state").jsonObject
        for ((accumulated, offset, expected) in listOf(Triple("0.0007","0.0004","4.0003"), Triple("0.0004","0.0007","3.9997"))) {
            val running=state.getValue("clockRunningSince").jsonPrimitive.content.toBigDecimal()+BigDecimal(offset)
            val changed=JsonObject(original+("state" to JsonObject(state+mapOf("clockRunningSince" to JsonPrimitive(running),"clockElapsedSeconds" to JsonPrimitive(BigDecimal(accumulated))))))
            val payload=JsonObject(snapshot+("games" to JsonArray(listOf(changed)))).toString().toByteArray()
            val fractional=JsonObject(outer+mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)),"checksum" to JsonPrimitive(AppleLifecycleAppend.hash(payload)))).toString().toByteArray()
            val finished=append(fractional,Command("finish",text="scorerDecision",at=time+5000),1)
            val elapsed=stored(finished).getValue("state").jsonObject.getValue("clockElapsedSeconds").jsonPrimitive.content.toBigDecimal()
            assertEquals(0,BigDecimal(expected).compareTo(elapsed))
            assertArrayEquals(finished,AppleSourceBackup.validateLineage(fractional,finished).export())
        }
    }
    @Test fun ios311SuggestedTargetAndExplicitLocalChoiceSurviveSourceContinuation() {
        val bytes=source();val snapshot=AppleBackupEnvelope.inspect(bytes).snapshot
        val original=stored(bytes);val id=original.getValue("id").jsonPrimitive.content
        val awayID=original.getValue("state").jsonObject.getValue("awayTeam").jsonObject.getValue("id")
        fun envelope(fields:Map<String,JsonElement>):ByteArray {
            val payload=JsonObject(snapshot+("games" to JsonArray(listOf(JsonObject(original+fields))))).toString().toByteArray()
            return JsonObject(AppleWireJson.parse(bytes).jsonObject+mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)),"checksum" to JsonPrimitive(AppleLifecycleAppend.hash(payload)))).toString().toByteArray()
        }
        for(fields in listOf(mapOf("postgameTargetConfigured" to JsonPrimitive(true)),
            mapOf("postgameTargetConfigured" to JsonPrimitive(true),"postgameUploadTeamID" to awayID))) {
            val input=envelope(fields);val source=AppleSourceBackup.prepare(input)
            val choice=requireNotNull(source.games().single().postgameSharingChoice)
            assertEquals(fields["postgameUploadTeamID"]?.jsonPrimitive?.content?.lowercase(),choice.teamID)
            val next=source.appendPitch(id,PitchAction.ball,"e3000000-0000-0000-0000-000000000001",time+3000).export()
            assertEquals(choice,AppleSourceBackup.prepare(next).games().single().postgameSharingChoice)
            fields.forEach {(key,value)->assertEquals(value,stored(next)[key])}
            assertArrayEquals(next,AppleSourceBackup.validateLineage(input,next).export())
        }
        assertThrows(IllegalArgumentException::class.java) {AppleSourceBackup.prepare(envelope(mapOf("postgameTargetConfigured" to JsonPrimitive("true"))))}
    }
}
