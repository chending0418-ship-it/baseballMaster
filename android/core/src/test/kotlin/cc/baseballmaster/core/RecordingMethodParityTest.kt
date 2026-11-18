package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class RecordingMethodParityTest {
    private fun game(): Game {
        fun team(id: String) = Team(id, id, (1..9).map { Player("$id-$it", "球员$it", "$it") })
        return MatchFactory.create("mode-parity", team("home"), team("away"), Rules())
    }

    @Test
    fun changesModeMidAtBatAndKeepsTheChoiceThroughUndoRedoAndCorrection() {
        val started = MatchEngine.apply(game(), Command("pitch", pitch = PitchAction.ball))
        val pro = MatchEngine.changeRecordingMethod(started, "situation")
        assertEquals(started.situation(), pro.situation())
        assertEquals(started.entries, pro.entries)
        assertEquals(started.journal, pro.journal)
        val next = MatchEngine.apply(pro, Command("pitch", pitch = PitchAction.calledStrike))
        val undo = MatchEngine.undo(next)
        assertEquals(1, undo.balls)
        assertEquals(0, undo.strikes)
        assertEquals("situation", undo.rules.recordingMethod)

        val basic = MatchEngine.changeRecordingMethod(undo, "traditional")
        val reloaded = GameJson.decodeFromString<Game>(GameJson.encodeToString(basic))
        val redo = MatchEngine.redo(reloaded)
        assertEquals(next.situation(), redo.situation())
        assertEquals("traditional", redo.rules.recordingMethod)

        val proAgain = MatchEngine.changeRecordingMethod(redo, "situation")
        val corrected = MatchEngine.correct(proAgain, 1, Command("pitch", pitch = PitchAction.ball), "第二球为坏球")
        assertEquals("situation", corrected.rules.recordingMethod)
        assertEquals(2, corrected.balls)
        assertEquals(0, corrected.strikes)
        assertEquals(started.batter.id, corrected.batter.id)
    }

    @Test
    fun settingDoesNotRewriteRecordedContactOrChangeReadOnlyMatches() {
        val initial = MatchEngine.changeRecordingMethod(game(), "situation")
        val contact = MatchEngine.apply(initial, Command("play", outcome = Outcome.single, recordingMethod = "situation"))
        val basic = MatchEngine.changeRecordingMethod(contact, "traditional")
        assertEquals(contact.journal, basic.journal)
        assertEquals(contact.entries, basic.entries)
        assertEquals(contact.situation(), basic.situation())
        assertEquals("situation", basic.journal.single().command.recordingMethod)
        assertThrows(IllegalArgumentException::class.java) {
            MatchEngine.changeRecordingMethod(basic.copy(appleSourceID = "original-ios-backup"), "situation")
        }
        val final = MatchEngine.apply(basic, Command("finish", text = "weather"))
        assertThrows(IllegalArgumentException::class.java) {
            MatchEngine.changeRecordingMethod(final, "situation")
        }
    }
}
