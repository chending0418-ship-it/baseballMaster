package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApplePlayAppendTest {
    private val time=1_790_000_000_000L
    private fun source():ByteArray {
        fun team(prefix:String,opponent:Boolean)=Team("$prefix-0000-0000-0000-000000000000","击球合成球队",
            (1..9).map {Player("$prefix-0000-0000-0000-${it.toString().padStart(12,'0')}","球员$it","$it",position=it)},isOpponent=opponent)
        val away=team("a2000000",false);val home=team("b2000000",true)
        val game=MatchFactory.create("c2000000-0000-0000-0000-000000000000",home,away,Rules(),away.id).copy(createdAt=time,updatedAt=time)
        return AppleNativePitchExport.encode(MatchEngine.apply(game,Command("pitch",pitch=PitchAction.ball,at=time+1000),"d2000000-0000-0000-0000-000000000001"))
    }
    private fun game(bytes:ByteArray)=AppleSourceBackup.prepare(bytes).games().single()
    private fun save(name:String,bytes:ByteArray) {
        File(System.getProperty("wire.output"),"source-play/$name.bmbackup").also {it.parentFile.mkdirs();it.writeBytes(bytes)}
    }
    @Test fun hitsAndHalfChangesOwnTheirCompleteEventsWithoutRewritingThePrefix() {
        val original=source();var bytes=original;var expected=game(bytes)
        val outcomes=listOf(Outcome.single,Outcome.double,Outcome.homeRun,Outcome.groundOut,Outcome.flyOut,Outcome.lineOut,Outcome.triple,Outcome.single)
        outcomes.forEachIndexed {index,outcome->
            val command=Command("play",outcome=outcome,at=time+(index+2)*1000)
            val id="e2000000-0000-0000-0000-${(index+1).toString().padStart(12,'0')}"
            expected=MatchEngine.apply(expected,command,id)
            bytes=AppleSourceBackup.prepare(bytes).appendCommand(expected.id,command,id).export()
            assertEquals(NativeScoreSnapshot.from(expected),NativeScoreSnapshot.from(game(bytes)))
            save("play-${index+1}",bytes)
        }
        val before=AppleBackupEnvelope.inspect(original).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
        val after=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
        for(key in listOf("playLog","scoringEvents")) assertEquals(before.getValue(key).jsonArray,after.getValue(key).jsonArray.take(before.getValue(key).jsonArray.size))
        assertEquals(before.getValue("historyJournal").jsonObject.getValue("checkpoint"),after.getValue("historyJournal").jsonObject.getValue("checkpoint"))
        assertFalse(game(bytes).isTop)
        val operations=after.getValue("historyJournal").jsonObject.getValue("operations").jsonArray
        assertEquals(2,operations[6].jsonObject.getValue("eventIDs").jsonArray.size)
        assertArrayEquals(bytes,AppleSourceBackup.validateLineage(original,bytes).export())
        val pitch=Command("pitch",pitch=PitchAction.calledStrike,at=time+20_000)
        val pitchID="e2000000-0000-0000-0000-000000000020"
        expected=MatchEngine.apply(expected,pitch,pitchID)
        bytes=AppleSourceBackup.prepare(bytes).appendCommand(expected.id,pitch,pitchID).export()
        assertEquals(NativeScoreSnapshot.from(expected),NativeScoreSnapshot.from(game(bytes)))
        assertArrayEquals(bytes,AppleSourceBackup.validateLineage(original,bytes).export())
        save("play-then-pitch",bytes)
    }
    @Test fun unsupportedDetailsBackdatedCommandsAndDuplicateIDsLeaveOriginalUntouched() {
        val bytes=source();val original=bytes.copyOf();val g=game(bytes)
        for(c in listOf(Command("play",outcome=Outcome.single,at=time),Command("play",outcome=Outcome.single,fielding=Fielding(route=listOf(6,3)),at=time+2000)))
            assertThrows(IllegalArgumentException::class.java) {ApplePlayAppend.append(bytes,g.id,c,"e2000000-0000-0000-0000-000000000001")}
        assertThrows(IllegalArgumentException::class.java) {ApplePlayAppend.append(bytes,g.id,Command("play",outcome=Outcome.single,at=time+2000),"d2000000-0000-0000-0000-000000000001")}
        assertArrayEquals(original,bytes)
    }
}
