package cc.baseballmaster.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleNativePitchExportTest {
    private val time = 1_790_000_000_000L
    private fun initial(): Game {
        fun team(prefix: String, opponent: Boolean) = Team("$prefix-0000-0000-0000-000000000000", "合成球队$prefix",
            (1..9).map { Player("$prefix-0000-0000-0000-${it.toString().padStart(12,'0')}", "球员$it", it.toString(), position=it, chineseName="球员$it") }, isOpponent=opponent)
        val away=team("aa000000",false); val home=team("bb000000",true)
        return MatchFactory.create("cc000000-0000-0000-0000-000000000000",home,away,Rules(),away.id).copy(createdAt=time,updatedAt=time)
    }
    private fun play(actions: List<PitchAction>): Game {
        var game=initial()
        actions.forEachIndexed { index, action -> game=MatchEngine.apply(game,Command("pitch",pitch=action,at=time+(index+1)*1000),"dd000000-0000-0000-0000-${(index+1).toString().padStart(12,'0')}") }
        return game
    }
    private fun save(name: String, bytes: ByteArray) {
        File(System.getProperty("wire.output"),"native-pitch/$name.bmbackup").also { it.parentFile.mkdirs();it.writeBytes(bytes) }
    }
    @Test fun nativeStandardGameIncludesHitsHalfChangesLaterPitchAndManualFinish() {
        var game=play(listOf(PitchAction.ball))
        val commands=listOf(Command("play",outcome=Outcome.single),Command("play",outcome=Outcome.homeRun),
            Command("play",outcome=Outcome.groundOut),Command("play",outcome=Outcome.groundOut),Command("play",outcome=Outcome.flyOut),
            Command("pitch",pitch=PitchAction.calledStrike),Command("finish",text="scorerDecision"))
        commands.forEachIndexed {index,c->game=MatchEngine.apply(game,c.copy(at=time+(index+2)*1000),"dd000000-0000-0000-0000-${(index+100).toString().padStart(12,'0')}")}
        val bytes=AppleNativePitchExport.encodeStandardHistory(game)
        val replay=AppleSourceBackup.prepare(bytes).games().single()
        assertTrue(replay.isFinal);assertEquals(2,replay.awayRuns);assertFalse(replay.isTop)
        assertEquals(NativeScoreSnapshot.from(game),NativeScoreSnapshot.from(replay))
        assertEquals(game.endedAt,replay.endedAt);assertEquals(game.clockElapsedMs,replay.clockElapsedMs)
        val state=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
        assertEquals(listOf(2,1,1,1,1,2,1,1),state.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.map {it.jsonObject.getValue("eventIDs").jsonArray.size})
        save("native-finished-game",bytes)
    }
    @Test fun firstActionCanBeAHitWithoutInventingAnOpeningPitch() {
        var game=initial()
        val commands=listOf(Command("play",outcome=Outcome.homeRun),Command("pitch",pitch=PitchAction.ball),
            Command("play",outcome=Outcome.single),Command("finish",text="weather"))
        commands.forEachIndexed {index,c->game=MatchEngine.apply(game,c.copy(at=time+(index+1)*1000),"dd000000-0000-0000-0000-${(index+200).toString().padStart(12,'0')}")}
        val bytes=AppleNativePitchExport.encodeStandardHistory(game)
        val replay=AppleSourceBackup.prepare(bytes).games().single()
        assertEquals(NativeScoreSnapshot.from(game),NativeScoreSnapshot.from(replay));assertEquals(3,replay.totalPitches)
        val state=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject.getValue("state").jsonObject
        assertEquals(listOf("PLAY","HR","B","1B","END"),state.getValue("scoringEvents").jsonArray.map {it.jsonObject.getValue("notation").jsonPrimitive.content})
        assertEquals(listOf(2,1,1,1),state.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.map {it.jsonObject.getValue("eventIDs").jsonArray.size})
        save("native-first-play-finished",bytes)
    }
    @Test fun openingAndTerminalPitchesHaveTheirFullOwnedGraphAndStableIdentities() {
        val actions=List(4) {PitchAction.ball} + List(3) {PitchAction.swingingStrike} + listOf(PitchAction.foul)
        val game=play(actions)
        val bytes=AppleNativePitchExport.encode(game)
        assertArrayEquals(bytes,AppleNativePitchExport.encode(game))
        val stored=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
        val state=stored.getValue("state").jsonObject
        val events=state.getValue("scoringEvents").jsonArray
        val operations=state.getValue("historyJournal").jsonObject.getValue("operations").jsonArray
        assertEquals(listOf(2,1,1,2,1,1,2,1),operations.map {it.jsonObject.getValue("eventIDs").jsonArray.size})
        assertEquals(events.map {it.jsonObject.getValue("id")},operations.flatMap {it.jsonObject.getValue("eventIDs").jsonArray})
        assertEquals("PLAY",events.first().jsonObject.getValue("notation").jsonPrimitive.content)
        val identities=events.map {it.jsonObject.getValue("id").jsonPrimitive.content} + state.getValue("playLog").jsonArray.map {it.jsonObject.getValue("id").jsonPrimitive.content} + operations.map {it.jsonObject.getValue("id").jsonPrimitive.content}
        assertEquals(identities.size,identities.distinct().size)
        val replay=AppleReplayPrototype.replay(stored)
        assertEquals(NativeScoreSnapshot.from(game),NativeScoreSnapshot.from(replay))
        save("walk-strikeout",bytes)
    }
    @Test fun filledBasesWalkUsesMultipleEventsWithoutLosingForcedRun() {
        val game=play(List(16) {PitchAction.ball})
        val bytes=AppleNativePitchExport.encode(game)
        val stored=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
        val last=stored.getValue("state").jsonObject.getValue("scoringEvents").jsonArray.last().jsonObject
        assertEquals(1,last.getValue("effectiveScorerIDs").jsonArray.size)
        assertEquals(NativeScoreSnapshot.from(game),NativeScoreSnapshot.from(AppleReplayPrototype.replay(stored)))
        save("forced-run",bytes)
    }
    @Test fun thirdOutHalfChangesAndNewDefensivePitchersRetainTheFullGraph() {
        val game=play(List(21) {PitchAction.calledStrike})
        val bytes=AppleNativePitchExport.encode(game)
        val stored=AppleBackupEnvelope.inspect(bytes).snapshot.getValue("games").jsonArray.single().jsonObject
        val state=stored.getValue("state").jsonObject
        val operations=state.getValue("historyJournal").jsonObject.getValue("operations").jsonArray
        assertEquals(3,operations[8].jsonObject.getValue("eventIDs").jsonArray.size)
        assertEquals(3,operations[17].jsonObject.getValue("eventIDs").jsonArray.size)
        assertEquals(NativeScoreSnapshot.from(game),NativeScoreSnapshot.from(AppleReplayPrototype.replay(stored)))
        save("third-out-half-changes",bytes)
    }
    @Test fun sourceContinuationAcrossThirdOutHasIndependentlyReconstructedLineage() {
        val original=AppleNativePitchExport.encode(play(List(8) {PitchAction.calledStrike}))
        val stored=AppleBackupEnvelope.inspect(original).snapshot.getValue("games").jsonArray.single().jsonObject
        val id=stored.getValue("id").jsonPrimitive.content
        val after=AppleSourceBackup.prepare(original)
            .appendPitch(id,PitchAction.calledStrike,"ee000000-0000-0000-0000-000000000001",time+30_000)
            .appendPitch(id,PitchAction.ball,"ee000000-0000-0000-0000-000000000002",time+31_000).export()
        assertArrayEquals(after,AppleSourceBackup.validateLineage(original,after).export())
        val game=AppleSourceBackup.prepare(after).games().single()
        assertFalse(game.isTop);assertEquals(1,game.balls);assertEquals(0,game.outs)
        save("continued-third-out",after)
    }
    @Test fun unsupportedRuleDecisionsAuditAndUndoBranchesCannotProduceAPartialBackup() {
        val game=play(listOf(PitchAction.ball))
        assertThrows(IllegalArgumentException::class.java) {AppleNativePitchExport.encode(MatchEngine.undo(game))}
        val hit=MatchEngine.apply(game,Command("play",outcome=Outcome.single,at=time+20_000),"dd000000-0000-0000-0000-000000000100")
        assertThrows(IllegalArgumentException::class.java) {AppleNativePitchExport.encode(hit)}
        val third=play(List(6 * 18) {PitchAction.calledStrike})
        assertThrows(IllegalArgumentException::class.java) {AppleNativePitchExport.encode(third)}
        val hash=MessageDigest.getInstance("SHA-256").digest(GameJson.encodeToString(third).toByteArray()).toList()
        assertThrows(IllegalArgumentException::class.java) {AppleNativePitchExport.encode(third)}
        assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(GameJson.encodeToString(third).toByteArray()).toList())
    }
}
