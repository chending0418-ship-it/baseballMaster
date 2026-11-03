package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class LineupParitySafetyTest {
    private fun seed(): Game {
        fun team(id:String)=Team(id,id,(1..12).map {Player("$id-$it","球员$it",it.toString())})
        return MatchFactory.create("lineup-parity",team("home"),team("away"),Rules())
    }
    private fun replace(g:Game,home:Boolean,out:String,incoming:String)=Command("lineup",homeSide=home,
        order=(if(home)g.homeOrder else g.awayOrder)!!.map {if(it==out)incoming else it},
        positions=(if(home)g.homePositions else g.awayPositions)!!.mapValues {if(it.value==out)incoming else it.value},
        lineupReplacements=mapOf(out to incoming))

    @Test fun pregameFirstBatterCanBecomeBenchPlayerWithoutAnAnchorOrFakePitch() {
        val g=seed();val result=MatchEngine.apply(g,replace(g,false,"away-1","away-10").copy(lineupReplacements=null))
        assertEquals("away-10",result.batter.id)
        assertEquals("away-10",result.awayPositions!![1])
        assertEquals(0,result.totalPitches)
        assertTrue(result.isPregame)
        assertTrue(result.exitedPlayers.isEmpty())
        assertEquals("away-1",g.batter.id)
    }
    @Test fun liveReplacementPreservesCountAndStrikeoutResponsibilityAndReplays() {
        var g=seed()
        repeat(2) {g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.calledStrike))}
        val before=g
        val result=MatchEngine.apply(g,replace(g,false,"away-1","away-10"))
        assertEquals("away-10",result.batter.id)
        assertEquals(2,result.strikes)
        assertEquals(before.totalPitches,result.totalPitches)
        assertEquals("away-1",result.strikeoutResponsibleBatterID)
        val undone=MatchEngine.undo(result)
        assertEquals("away-1",undone.batter.id)
        val restored=MatchEngine.redo(undone)
        assertEquals("away-10",restored.batter.id)
        assertEquals("away-1",restored.strikeoutResponsibleBatterID)
    }
    @Test fun liveSortAfterPlayBallKeepsTheCurrentIdentityEvenBeforeFirstPitch() {
        val g=MatchEngine.apply(seed(),Command("clock",text="start",at=1000))
        val sorted=g.awayOrder!!.toMutableList().apply {java.util.Collections.swap(this,0,1)}
        val result=MatchEngine.apply(g,Command("lineup",homeSide=false,order=sorted,positions=g.awayPositions))
        assertEquals(g.batter.id,result.batter.id)
        assertEquals(1,result.awayBatterIndex)
    }
    @Test fun runnerReplacementKeepsOriginalPitcherAndAutomaticRunnerIdentity() {
        var g=seed()
        g=MatchEngine.apply(g,Command("play",outcome=Outcome.single))
        val runner=g.bases.first {it!=null}!!
        g.automaticRunnerIDs=setOf(runner);g.unearnedRunnerIDs=setOf(runner)
        val owner=g.runnerPitcherIDs.getValue(runner)
        val result=MatchEngine.apply(g,replace(g,false,runner,"away-10"))
        assertEquals("away-10",result.bases[0])
        assertEquals(owner,result.runnerPitcherIDs["away-10"])
        assertTrue("away-10" in result.automaticRunnerIDs)
        assertFalse(runner in result.automaticRunnerIDs)
        assertTrue("away-10" in result.unearnedRunnerIDs)
    }
    @Test fun lineupPitcherChangePreservesInheritedRunnerResponsibility() {
        var g=MatchEngine.apply(seed(),Command("play",outcome=Outcome.single))
        repeat(3) {g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.ball))}
        val original=g.pitcher.id;val runner=g.bases.first {it!=null}!!
        val result=MatchEngine.apply(g,replace(g,true,original,"home-10"))
        assertEquals("home-10",result.pitcher.id)
        assertEquals(original,result.runnerPitcherIDs[runner])
        assertEquals(original,result.walkResponsiblePitcherID)
        assertEquals(3,result.balls)
    }
    @Test fun profileCorrectionPreservesIdentityStatisticsAndPriorTextAndReplays() {
        var g=MatchEngine.apply(seed(),Command("pitch",pitch=PitchAction.ball))
        val player=g.away.players.first();val originalEntry=g.entries.first()
        g.batting[player.id]=BattingLine(hits=2)
        val update=player.copy(name="更正姓名",chineseName="更正姓名",number="00",jerseyNumbers=listOf("00","0"))
        val cmd=Command("lineup",homeSide=false,order=g.awayOrder,positions=g.awayPositions,playerUpdates=listOf(update))
        val result=MatchEngine.apply(g,cmd)
        assertEquals(player.id,result.batter.id)
        assertEquals("更正姓名",result.batter.name)
        assertEquals(listOf("00","0"),result.batter.jerseyNumbers)
        assertEquals(2,result.batting[player.id]!!.hits)
        assertEquals(originalEntry,result.entries.first())
        assertEquals("更正姓名",GameJson.decodeFromString<Game>(GameJson.encodeToString(result)).batter.name)
    }
    @Test fun unknownProfileIdentityAndImplicitLiveReplacementAreRejectedAtomically() {
        val g=MatchEngine.apply(seed(),Command("pitch",pitch=PitchAction.ball));val snapshot=GameJson.encodeToString(g)
        val invalid=replace(g,false,"away-1","away-10").copy(lineupReplacements=null)
        try {MatchEngine.apply(g,invalid);fail("Implicit live replacement accepted")} catch(_:IllegalArgumentException) {}
        try {MatchEngine.apply(g,Command("lineup",homeSide=false,order=g.awayOrder,positions=g.awayPositions,playerUpdates=listOf(Player("foreign","外人","1"))));fail("Foreign identity accepted")} catch(_:IllegalArgumentException) {}
        assertEquals(snapshot,GameJson.encodeToString(g))
    }
    @Test fun unsupportedAppleMetadataCannotBeSilentlyDropped() {
        val g=seed();val c=replace(g,false,"away-1","away-10");val result=MatchEngine.apply(g,c)
        try {AppleHistoryCommandCodec.encodeAction(c,g,result,emptyList());fail("Unsupported replacement metadata encoded")} catch(_:IllegalArgumentException) {}
        val updated=g.away.players.first().copy(name="更名")
        val profile=Command("lineup",homeSide=false,order=g.awayOrder,positions=g.awayPositions,playerUpdates=listOf(updated))
        try {AppleHistoryCommandCodec.encodeAction(profile,g,MatchEngine.apply(g,profile),emptyList());fail("Unsupported profile metadata encoded")} catch(_:IllegalArgumentException) {}
    }
    @Test fun noncurrentReplacementMovedBeforeSaveCannotSilentlyDropItsExitRelationship() {
        val g=MatchEngine.apply(seed(),Command("pitch",pitch=PitchAction.ball))
        val snapshot=GameJson.encodeToString(g)
        val replacement=replace(g,false,"away-3","away-10")
        val moved=replacement.order!!.toMutableList().apply {java.util.Collections.swap(this,2,3)}
        // This is precisely the UI's omitted inference after the replacement leaves its original slot.
        try {
            MatchEngine.apply(g,replacement.copy(order=moved,lineupReplacements=null))
            fail("A noncurrent/nonbase outgoing player must not silently lose its exit record")
        } catch(error:IllegalArgumentException) {assertTrue(error.message.orEmpty().contains("先保存换人，再调整棒次"))}
        // Supplying the old relationship cannot bypass the slot-preserving rule either.
        try {MatchEngine.apply(g,replacement.copy(order=moved));fail("Combined moved replacement accepted")}
        catch(_:IllegalArgumentException) {}
        assertEquals(snapshot,GameJson.encodeToString(g))
        val committed=MatchEngine.apply(g,replacement)
        assertTrue("away-3" in committed.exitedPlayers)
        val reordered=committed.awayOrder!!.toMutableList().apply {java.util.Collections.swap(this,2,3)}
        val result=MatchEngine.apply(committed,Command("lineup",homeSide=false,order=reordered,positions=committed.awayPositions))
        assertEquals(reordered,result.awayOrder)
        assertTrue("away-3" in result.exitedPlayers)
        assertEquals(g.batter.id,result.batter.id);assertEquals(g.balls,result.balls);assertEquals(g.strikes,result.strikes)
        assertEquals(g.totalPitches,result.totalPitches);assertEquals(g.batting,result.batting);assertEquals(g.pitching,result.pitching)
    }
    @Test fun coverageGuardDoesNotRequireBattingReplacementForDefenseOnlyPitcherChanges() {
        fun configured(rules:Rules):Game {
            val base=seed()
            return MatchFactory.create("defense-coverage",base.home,base.away,rules)
        }
        val twoWay=MatchEngine.apply(configured(Rules(designatedHitter=true,twoWayPlayer=true)),Command("pitch",pitch=PitchAction.ball))
        val original=twoWay.pitcher.id
        val changed=MatchEngine.apply(twoWay,Command("lineup",homeSide=true,order=twoWay.homeOrder,
            positions=twoWay.homePositions!!.toMutableMap().apply {put(1,"home-10")},designatedHitterID=twoWay.homeDesignatedHitterID))
        assertEquals(twoWay.homeOrder,changed.homeOrder);assertEquals(original,changed.homeDesignatedHitterID)
        assertEquals("home-10",changed.pitcher.id);assertFalse(original in changed.exitedPlayers)
        var slow=configured(Rules(GameMode.slowPitch))
        slow=MatchEngine.apply(slow,Command("lineup",homeSide=true,order=slow.home.players.map {it.id},positions=slow.homePositions))
        slow=MatchEngine.apply(slow,Command("pitch",pitch=PitchAction.ball))
        val originalSlow=slow.pitcher.id
        val entered=MatchEngine.apply(slow,Command("lineup",homeSide=true,order=slow.homeOrder,
            positions=slow.homePositions!!.toMutableMap().apply {put(1,"home-11")}))
        assertEquals(slow.homeOrder,entered.homeOrder);assertEquals(10,entered.homePositions!!.size)
        assertEquals("home-11",entered.pitcher.id);assertFalse(originalSlow in entered.exitedPlayers)
    }

    @Test fun legacyUnmappedLineupIncludingPersistedCorrectionFlagsStillReplaysWithoutInventingExits() {
        val g=MatchEngine.apply(seed(),Command("pitch",pitch=PitchAction.ball,at=1000))
        val command=replace(g,false,"away-3","away-10").copy(lineupReplacements=null,at=2000)
        // Reproduce an already saved 0.8.0 command, not a newly accepted 0.8.1 submission.
        val legacy=Session(GameJson.decodeFromString<Game>(GameJson.encodeToString(g)),command,"legacy-lineup").run()
        legacy.journal=g.journal+RecordedCommand("legacy-lineup",command,g.batter.id,g.bases,
            context=HistoryContext.capture(g),edited=true,inserted=true)
        legacy.cursor=legacy.journal.size;legacy.revision=g.revision+1;legacy.updatedAt=command.at
        legacy.validate()
        val originalPayload=GameJson.encodeToString(legacy)
        assertFalse("away-3" in legacy.exitedPlayers)
        val undone=MatchEngine.undo(legacy)
        assertEquals(g.awayOrder,undone.awayOrder)
        val restored=MatchEngine.redo(undone)
        assertEquals(legacy.awayOrder,restored.awayOrder)
        assertEquals(legacy.exitedPlayers,restored.exitedPlayers)
        assertNull(restored.journal.last().command.lineupReplacements)
        assertTrue(restored.journal.last().edited);assertTrue(restored.journal.last().inserted)
        // Revision/update metadata belongs to undo/redo, while all retained match/history data stays intact.
        assertEquals(GameJson.encodeToString(legacy.copy(revision=0,updatedAt=0)),
            GameJson.encodeToString(restored.copy(revision=0,updatedAt=0)))
        assertEquals(originalPayload,GameJson.encodeToString(legacy))
        val unchanged=History.preview(legacy,History.draft(legacy))
        assertTrue("Old persisted flags alone must not enable new-command validation",unchanged.canSave)
        assertEquals(legacy.exitedPlayers,unchanged.game!!.exitedPlayers)
    }
    @Test fun newlyEditedAndInsertedHistoryCannotBypassReplacementCoverage() {
        val g=MatchEngine.apply(seed(),Command("pitch",pitch=PitchAction.ball,at=1000))
        val invalid=replace(g,false,"away-3","away-10").copy(lineupReplacements=null,at=2000)
        val original=GameJson.encodeToString(g)
        val inserted=History.insert(g,History.draft(g),g.cursor,invalid)
        val insertPreview=History.preview(g,inserted)
        assertFalse(insertPreview.canSave)
        assertTrue(insertPreview.conflict.orEmpty().contains("先保存换人，再调整棒次"))
        val valid=MatchEngine.apply(g,Command("lineup",homeSide=false,order=g.awayOrder,positions=g.awayPositions,at=2000))
        val draft=History.draft(valid)
        val edited=draft.copy(records=draft.records.mapIndexed {index,record ->
            if(index==draft.records.lastIndex)record.copy(command=invalid,edited=true) else record
        })
        val editPreview=History.preview(valid,edited)
        assertFalse(editPreview.canSave)
        assertTrue(editPreview.conflict.orEmpty().contains("先保存换人，再调整棒次"))
        assertEquals(original,GameJson.encodeToString(g))
        assertTrue(valid.exitedPlayers.isEmpty())
    }

}
