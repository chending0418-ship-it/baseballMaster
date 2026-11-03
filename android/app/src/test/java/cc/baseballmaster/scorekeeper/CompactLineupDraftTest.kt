package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import org.junit.Assert.*
import org.junit.Test

class CompactLineupDraftTest {
    @Test fun aLargeHistoryIsNeverIncludedInSavedLineupEditsAndRebuildPreservesTheSource() {
        fun team(id:String)=Team(id,id,(1..12).map {Player("$id-$it","球员$it",it.toString())})
        var source=MatchFactory.create("large",team("h"),team("a"),Rules())
        source=MatchEngine.apply(source,Command("pitch",pitch=PitchAction.ball))
        source=MatchEngine.apply(source,Command("note",text="ARCHIVE-CONTEXT|"+"atbat context ".repeat(45000)))
        assertTrue(GameJson.encodeToString(source).toByteArray().size>1024*1024)
        val original=GameJson.encodeToString(source)
        val edited=source.copy(awayOrder=source.awayOrder!!.reversed(),away=source.away.copy(players=source.away.players.mapIndexed {i,p->if(i==0)p.copy(name="更正姓名",chineseName="更正姓名")else p}))
        val compact=captureCompactLineupDraft(source,edited)
        val encoded=GameJson.encodeToString(compact)
        assertTrue(encoded.toByteArray().size<16*1024)
        assertFalse(encoded.contains("ARCHIVE-CONTEXT"));assertFalse(encoded.contains("journal"));assertFalse(encoded.contains("entries"))
        val restored=applyCompactLineupDraft(source,GameJson.decodeFromString<CompactLineupDraft>(encoded))
        assertEquals(edited.awayOrder,restored.awayOrder);assertEquals("更正姓名",restored.away.players.first().name)
        assertEquals(source.batter.id,restored.batter.id)
        assertSame(source.entries,restored.entries);assertSame(source.journal,restored.journal)
        assertEquals(source.batting,restored.batting);assertEquals(source.pitching,restored.pitching)
        assertEquals(source.balls,restored.balls);assertEquals(source.strikes,restored.strikes)
        assertEquals(original,GameJson.encodeToString(source))
        assertEquals(CompactLineupDraft(),captureCompactLineupDraft(source,source))
    }
}
