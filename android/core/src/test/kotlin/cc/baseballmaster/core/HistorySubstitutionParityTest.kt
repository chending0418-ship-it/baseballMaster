package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class HistorySubstitutionParityTest {
    private fun started(): Game {
        fun team(side: String) = Team(side, side, (1..12).map { Player("$side-$it", "球员$it", "$it") })
        val seed = MatchFactory.create("substitution", team("home"), team("away"), Rules())
        return MatchEngine.apply(seed, Command("pitch", pitch = PitchAction.calledStrike))
    }

    private fun first() = Command("fielder", playerID = "home-5", replacementID = "home-10")
    private fun second() = Command("fielder", playerID = "home-7", replacementID = "home-11")
    private fun both() = Command(
        "doubleSwitch", playerID = "home-5", replacementID = "home-10", position = 5,
        secondPlayerID = "home-7", secondReplacementID = "home-11", secondPosition = 7,
    )

    @Test
    fun removingWrongPairPreservesTheOtherSubstitutionAndLaterPitch() {
        val before = started()
        for (simultaneous in listOf(false, true)) {
            val substituted = if (simultaneous) MatchEngine.apply(before, both())
                else MatchEngine.apply(MatchEngine.apply(before, first()), second())
            val original = MatchEngine.apply(substituted, Command("pitch", pitch = PitchAction.ball))
            val serialized = GameJson.encodeToString(original)
            val source = History.draft(original)
            val records = source.records.toMutableList()
            if (simultaneous) {
                val record = records[1]
                val onePair = History.reviseDoubleSwitch(before, record.command, null, 5, "home-11", 7)
                records[1] = record.copy(command = onePair, edited = true)
            } else records.removeAt(1)
            val draft = source.copy(records = records, reason = "第一组换人误录")
            val preview = History.preview(original, draft)
            assertTrue(preview.conflict, preview.canSave)
            assertTrue(preview.changes.any { it.contains("第 5 棒") })
            assertEquals(serialized, GameJson.encodeToString(original))

            val saved = History.commit(original, draft)
            val reopened = GameJson.decodeFromString<Game>(GameJson.encodeToString(saved))
            assertEquals("home-5", reopened.homePositions?.get(5))
            assertEquals("home-11", reopened.homePositions?.get(7))
            assertEquals("home-5", reopened.homeOrder?.get(4))
            assertEquals("home-11", reopened.homeOrder?.get(6))
            assertEquals(original.journal.last().id, reopened.journal.last().id)
            assertEquals(1, reopened.balls)
            assertEquals(1, reopened.strikes)
            assertEquals(original.pitching, reopened.pitching)
        }
    }

    @Test
    fun changingOneIncomingPlayerLeavesSecondPairAndReportsFirstLaterConflict() {
        val before = started()
        val original = MatchEngine.apply(before, both())
        val source = History.draft(original)
        val record = source.records.last()
        val revised = History.reviseDoubleSwitch(before, record.command, "home-12", 5, "home-11", 7)
        val updated = History.commit(original, source.copy(
            reason = "确认第一组替补", records = source.records.dropLast(1) + record.copy(command = revised, edited = true),
        ))
        assertEquals("home-12", updated.homePositions?.get(5))
        assertEquals("home-11", updated.homePositions?.get(7))

        val later = MatchEngine.apply(original, Command("fielder", playerID = "home-8", replacementID = "home-12"))
        val draft = History.draft(later)
        val conflict = draft.copy(
            reason = "确认第一组替补",
            records = draft.records.map { if (it.id == record.id) it.copy(command = revised, edited = true) else it },
        )
        val originalJSON = GameJson.encodeToString(later)
        val preview = History.preview(later, conflict)
        assertEquals(later.journal.last().id, preview.conflictID)
        assertFalse(preview.canSave)
        assertThrows(IllegalStateException::class.java) { History.commit(later, conflict) }
        assertEquals(originalJSON, GameJson.encodeToString(later))
    }

    @Test
    fun retainingOneCrossedPairRequiresItsOriginalFieldingPosition() {
        val before = started()
        val crossed = both().copy(position = 7, secondPosition = 5)
        assertThrows(IllegalArgumentException::class.java) {
            History.reviseDoubleSwitch(before, crossed, null, 7, "home-11", 5)
        }
        val restored = History.reviseDoubleSwitch(before, crossed, null, 7, "home-11", 7)
        assertEquals("fielder", restored.kind)
        assertEquals("home-7", restored.playerID)
        assertEquals("home-11", restored.replacementID)
    }

    @Test
    fun laterSubstitutionInSameSlotCanReplayAfterCorrectingAndReloading() {
        val before = started()
        val original = MatchEngine.apply(MatchEngine.apply(before, first()), Command(
            "fielder", playerID = "home-10", replacementID = "home-12",
        ))
        val draft = History.draft(original)
        val firstRecord = draft.records[1]
        val corrected = History.commit(original, draft.copy(
            reason = "确认第一组替补",
            records = listOf(draft.records.first(),
                firstRecord.copy(command = firstRecord.command.copy(replacementID = "home-11"), edited = true),
                draft.records.last()),
        ))
        assertEquals("home-11", corrected.journal.last().command.playerID)
        val reopened = GameJson.decodeFromString<Game>(GameJson.encodeToString(corrected))
        val replayed = History.stateBefore(reopened, History.draft(reopened), reopened.cursor)
        assertEquals("home-12", replayed.homePositions?.get(5))
        assertEquals(original.journal.last().id, replayed.journal.last().id)
    }
}
