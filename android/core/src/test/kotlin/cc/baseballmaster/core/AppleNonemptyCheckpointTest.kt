package cc.baseballmaster.core

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic legacy-prefix boundary; source fixtures are never overwritten. */
class AppleNonemptyCheckpointTest {
    private fun stored()=AppleBackupEnvelope.inspect(File(System.getProperty("parity.fixtures"),"backup/ios-reference-v7.bmbackup").readBytes()).snapshot.getValue("games").jsonArray.single().jsonObject
    private fun atBoundary(): JsonObject {
        val old=stored();val state=old.getValue("state").jsonObject
        val checkpoint=JsonObject(state-"historyJournal"-"correctionRevisions")
        val journal=buildJsonObject {
            put("version",1);put("checkpoint",Base64.getEncoder().encodeToString(checkpoint.toString().toByteArray()))
            put("operations",JsonArray(emptyList()));put("legacyPrefix",true)
        }
        return JsonObject(old+("state" to JsonObject(state+("historyJournal" to journal))))
    }
    @Test fun nonemptyCheckpointRetainsSavedScoresPitchCountsAndLegacyPrefixWithoutInventingOperations() {
        val old=AppleReplayPrototype.replay(stored());val raw=atBoundary();val before=raw.toString()
        val result=AppleReplayPrototype.replay(raw)
        for((key,value) in NativeScoreSnapshot.from(old)) assertEquals(key,value,NativeScoreSnapshot.from(result)[key])
        assertTrue(result.incompletePrefix);assertTrue(result.journal.isEmpty());assertTrue(result.entries.isEmpty())
        assertEquals(before,raw.toString())
    }
    @Test fun aChangedCurrentScoreCannotBeHiddenBehindTheUnchangedCheckpoint() {
        val raw=atBoundary();val state=raw.getValue("state").jsonObject
        val scores=state.getValue("awayRunsByInning").jsonArray.toMutableList();scores[0]=JsonPrimitive(scores[0].jsonPrimitive.int+1)
        val bad=JsonObject(raw+("state" to JsonObject(state+("awayRunsByInning" to JsonArray(scores)))))
        assertThrows(Exception::class.java) {AppleReplayPrototype.replay(bad)}
    }
    @Test fun checkpointProjectionDoesNotChangeWhenTheOuterFileRevisionAndUpdateDateAdvance() {
        val raw=atBoundary();val first=AppleReplayPrototype.replay(raw)
        val changed=JsonObject(raw+mapOf("revision" to JsonPrimitive(first.revision+10),"updatedAt" to JsonPrimitive(900_000_000)))
        val next=AppleReplayPrototype.replay(changed)
        assertEquals(first.checkpoint,next.checkpoint)
        assertEquals(first.revision+10,next.revision)
    }
}
