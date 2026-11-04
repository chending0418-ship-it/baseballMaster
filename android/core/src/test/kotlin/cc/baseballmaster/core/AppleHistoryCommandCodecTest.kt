package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleHistoryCommandCodecTest {
    private fun game(): Game {
        val file=File(System.getProperty("parity.fixtures"),"scoring/pro-groundOut-scoring.json")
        return GameJson.decodeFromJsonElement(GameJson.parseToJsonElement(file.readText()).jsonObject.getValue("initialGame"))
    }
    @Test fun metadataCannotBeDisguisedAsAnOpaqueOrUnrelatedScoringCommand() {
        val g=game()
        for(kind in listOf("configure","date","method","schedule","activate","tiebreak","unavailable")) {
            assertThrows(IllegalStateException::class.java) {AppleHistoryCommandCodec.encodeForJournal(Command(kind),g,g,emptyList())}
        }
    }
    @Test fun reviewingRequiresTheOriginalWireEventInsteadOfGuessingFromTheNativeOperationID() {
        val g=game()
        assertThrows(IllegalArgumentException::class.java) {AppleHistoryCommandCodec.encodeForJournal(Command("review",eventID="native-operation",text="复核说明"),g,g,emptyList())}
    }
    @Test fun pregameLineupMustChangeTheCheckpointRatherThanBecomeAnOrdinaryJournalOperation() {
        val g=game()
        val positions=g.awayPositions ?: g.away.players.take(g.rules.fielders).mapIndexed {index,p -> index+1 to p.id}.toMap()
        val c=Command("lineup",homeSide=false,order=g.awayOrder,positions=positions,at=1_800_000_000_000)
        val after=MatchEngine.apply(g,c)
        assertTrue(g.isPregame)
        assertThrows(IllegalArgumentException::class.java) {AppleHistoryCommandCodec.encodeForJournal(c,g,after,emptyList())}
        assertEquals(setOf("lineup"),AppleHistoryCommandCodec.encodeAction(c,g,after,emptyList()).keys)
    }
}
