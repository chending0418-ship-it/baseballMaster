package cc.baseballmaster.core

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleCheckpointTest {
    private fun uuid() = UUID.randomUUID().toString()
    private fun opening(): Pair<Game,ScheduleField> {
        fun team() = Team(uuid(),"球队", (1..9).map { Player(uuid(),"球员$it",it.toString(),position=it) })
        val home = team()
        val now = System.currentTimeMillis()
        val scheduled = MatchFactory.create(uuid(),home,team(),Rules(),home.id,now+30000)
        val field = ScheduleField.opening(scheduled)
        return field.editingView(scheduled.id).copy(createdAt=now) to field
    }
    private fun notice(game: Game) = TeamScheduleNotice(game.home.id,game.id,System.currentTimeMillis(),
        game.away.name,game.home.name,"球场",false,1)

    @Test fun handoffRetainsUndoCursorAndRedoTail() {
        val (game,opening) = opening()
        val first = MatchEngine.apply(game,Command("pitch",pitch=PitchAction.ball))
        val second = MatchEngine.apply(first,Command("pitch",pitch=PitchAction.calledStrike))
        val undone = MatchEngine.undo(second)
        val field = opening.withGame(undone)
        val doc = ScheduleCheckpoint.document(undone,null,field).getValue("payload").jsonObject
        val restored = ScheduleCheckpoint.receive(doc,notice(game),field)
        assertEquals(undone.journal,restored.journal)
        assertEquals(undone.cursor,restored.cursor)
        assertEquals(second.strikes,MatchEngine.redo(restored).strikes)
        assertEquals(listOf(first.journal.single().id.uppercase()),
            doc.getValue("state").jsonObject.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun undoPitchRetainsIndependentBenchAdditionAndRedo() {
        val (game,opening) = opening()
        val pitched = MatchEngine.apply(game,Command("pitch",pitch=PitchAction.ball))
        val field = opening.withGame(pitched)
        val late = Player(uuid(),"临时队员","88",position=2)
        val proposal = field.proposal(game.id,Command("lineup",homeSide=false,order=pitched.awayOrder,
            positions=pitched.awayPositions,benchAdditions=listOf(late)),2)
        val joined = field.applyProposal(pitched,proposal)
        val undone = undoSchedulePlay(joined)
        assertEquals(0,undone.balls)
        assertEquals(1,undone.away.players.count { it.id == late.id })
        val redo = MatchEngine.redo(undone)
        assertEquals(1,redo.balls)
        assertEquals(1,redo.away.players.count { it.id == late.id })
        val saved = ScheduleCheckpoint.document(undone,null,field.withGame(undone)).getValue("payload").jsonObject
        val restored = ScheduleCheckpoint.receive(saved,notice(game),field.withGame(undone))
        assertEquals(1,MatchEngine.redo(restored).balls)
    }

    @Test fun swiftSnapshotCommandCanBeContinuedWithItsOriginalWireData() {
        val (game,opening) = opening()
        val configured = MatchEngine.apply(game,Command("configure",rules=game.rules.copy(initialBalls=1)))
        val field = opening.withGame(configured)
        val exported = ScheduleCheckpoint.document(configured,null,field).getValue("payload").jsonObject
        val state = exported.getValue("state").jsonObject
        val journal = state.getValue("historyJournal").jsonObject
        val operation = journal.getValue("operations").jsonArray.single().jsonObject
        val fields = operation.getValue("command").jsonObject.getValue("unavailable").jsonObject
        val base64 = java.util.Base64.getDecoder().decode(fields.getValue("_2").jsonPrimitive.content)
        val after = JsonObject(GameJson.parseToJsonElement(base64.decodeToString()).jsonObject - "androidScheduleCommand")
        val raw = JsonObject(fields + ("_2" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(after.toString().encodeToByteArray()))))
        val op = JsonObject(operation + ("command" to buildJsonObject { put("unavailable",raw) }))
        val history = JsonObject(journal + ("operations" to JsonArray(listOf(op))))
        val source = JsonObject((exported - "androidScheduleGame") + ("state" to JsonObject(state + ("historyJournal" to history))))
        val restored = ScheduleCheckpoint.receive(source,notice(game),field)
        assertEquals(1,restored.balls)
        val next = MatchEngine.apply(restored,Command("pitch",pitch=PitchAction.ball))
        assertEquals(2,next.balls)
        val doc = ScheduleCheckpoint.document(next,source,field.withGame(next)).getValue("payload").jsonObject
        assertEquals(op,doc.getValue("state").jsonObject.getValue("historyJournal").jsonObject.getValue("operations").jsonArray.first())
    }

    @Test fun supportedPreJournalGameKeepsOriginalLogsWhenHandedBack() {
        val stored = AppleBackupEnvelope.inspect(File(System.getProperty("parity.fixtures"),
            "backup/ios-original-v1.bmbackup").readBytes()).snapshot.getValue("games").jsonArray.single().jsonObject
        val old = stored.getValue("state").jsonObject
        val projected = AppleReplayPrototype.replay(stored)
        val field = ScheduleField(stored.getValue("isHome").jsonPrimitive.boolean,stored.getValue("rules").jsonObject,false,old)
        val received = ScheduleCheckpoint.receive(stored,notice(projected),field)
        val next = MatchEngine.apply(received,Command("pitch",pitch=PitchAction.ball))
        val saved = ScheduleCheckpoint.document(next,stored,field.withGame(next)).getValue("payload").jsonObject.getValue("state").jsonObject
        for (key in listOf("playLog","scoringEvents")) {
            val original = old.getValue(key).jsonArray
            assertEquals(original.toList(),saved.getValue(key).jsonArray.take(original.size))
        }
    }

    @Test fun swiftCorrectionAuditAndEventIdentitiesSurviveNextPitch() {
        val stored = AppleBackupEnvelope.inspect(File(System.getProperty("parity.fixtures"),
            "backup/ios-correction-reference.bmbackup").readBytes()).snapshot.getValue("games").jsonArray.single().jsonObject
        val before = AppleReplayPrototype.replay(stored)
        val old = stored.getValue("state").jsonObject
        val field = ScheduleField(stored.getValue("isHome").jsonPrimitive.boolean,stored.getValue("rules").jsonObject,false,old)
        val received = ScheduleCheckpoint.receive(stored,notice(before),field)
        val next = MatchEngine.apply(received,Command("pitch",pitch=PitchAction.ball))
        val sent = ScheduleCheckpoint.document(next,stored,field.withGame(next)).getValue("payload").jsonObject.getValue("state").jsonObject
        for (key in listOf("playLog","scoringEvents","correctionRevisions")) {
            val original = old.getValue(key).jsonArray
            assertEquals(key,original.toList(),sent.getValue(key).jsonArray.take(original.size))
        }
        val journal = old.getValue("historyJournal").jsonObject
        val appended = sent.getValue("historyJournal").jsonObject
        assertEquals(journal["checkpoint"],appended["checkpoint"])
        assertEquals(journal.getValue("operations").jsonArray.toList(),appended.getValue("operations").jsonArray.dropLast(1))
    }
}
