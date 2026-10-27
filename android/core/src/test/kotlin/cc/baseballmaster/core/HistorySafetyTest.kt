package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class HistorySafetyTest {
    private fun seed(): Game {
        fun team(id: String) = Team(id, id, (1..12).map { Player("$id-$it", "球员$it", "$it") })
        return MatchFactory.create("history", team("home"), team("away"), Rules())
    }

    private fun pitch(g: Game, p: PitchAction = PitchAction.ball) =
        MatchEngine.apply(g, Command("pitch", pitch = p))

    private fun unchanged(g: Game, block: () -> Unit) {
        val bytes = GameJson.encodeToString(g)
        block()
        assertEquals(bytes, GameJson.encodeToString(g))
    }

    @Test
    fun previewIsDetachedAndCannotOverwriteNewerScoringOrAnotherGame() {
        val g = pitch(seed())
        val d =
            History.insert(
                    g,
                    History.draft(g),
                    0,
                    Command("pitch", pitch = PitchAction.calledStrike),
                )
                .copy(reason = "漏球")
        unchanged(g) { assertTrue(History.preview(g, d).canSave) }
        assertFalse(History.preview(pitch(g), d).canSave)
        assertFalse(History.preview(g.copy(id = "another"), d).canSave)
        val empty = History.draft(g).copy(reason = "没有修改")
        try {
            History.commit(g, empty)
            fail("No-op created an audit revision")
        } catch (_: IllegalArgumentException) {}
        assertTrue(g.historyRevisions.isEmpty())
    }

    @Test
    fun changedBattingContextRequiresExplicitAcknowledgementAndUndoCannotEraseAudit() {
        var g = seed()
        repeat(3) { g = pitch(g) }
        g = pitch(g, PitchAction.calledStrike)
        var draft =
            History.insert(g, History.draft(g), 0, Command("pitch", pitch = PitchAction.ball))
                .copy(reason = "漏记坏球")
        val conflict = History.preview(g, draft)
        assertFalse(conflict.canSave)
        assertEquals(draft.records.last().id, conflict.conflictID)
        unchanged(g) {
            try {
                History.commit(g, draft)
                fail("Conflicting history committed")
            } catch (_: IllegalStateException) {}
        }
        draft =
            draft.copy(
                records =
                    draft.records.map {
                        if (it.id == conflict.conflictID) it.copy(acceptContext = true) else it
                    }
            )
        val corrected = History.commit(g, draft)
        assertEquals(0, corrected.balls)
        assertEquals(1, corrected.strikes)
        assertNotNull(corrected.bases[0])
        assertEquals(1, corrected.historyRevisions.size)
        try {
            MatchEngine.undo(corrected)
            fail("Audit boundary crossed")
        } catch (_: IllegalArgumentException) {}
        val subsequent = pitch(corrected)
        val undone = MatchEngine.undo(subsequent)
        assertEquals(corrected.situation(), undone.situation())
        assertEquals(corrected.historyRevisions, undone.historyRevisions)
    }

    @Test
    fun finishedGameCannotBeSilentlyReopenedByDeletingItsFinishRecord() {
        val final = MatchEngine.apply(pitch(seed()), Command("finish", text = "weather"))
        val draft = History.draft(final)
        val deletion = draft.copy(records = draft.records.dropLast(1), reason = "删除结束")
        unchanged(final) { assertFalse(History.preview(final, deletion).canSave) }
        val correction =
            draft.copy(
                records =
                    draft.records.mapIndexed { i, r ->
                        if (i == 0)
                            r.copy(
                                command = r.command.copy(pitch = PitchAction.calledStrike),
                                edited = true,
                            )
                        else r
                    },
                reason = "投球纠错",
            )
        val saved = History.commit(final, correction)
        assertTrue(saved.isFinal)
        assertEquals(final.endedAt, saved.endedAt)
        assertNull(saved.clockRunningSince)
    }

    @Test
    fun impossibleManualStatisticsAndBenchErrorCreditsAreRejectedAtomically() {
        val g = MatchEngine.apply(seed(), Command("play", outcome = Outcome.homeRun))
        val draft = History.draft(g)
        val r = draft.records.single()
        val invalid =
            listOf(
                r.copy(edited = true, statisticsRuling = StatisticsRuling(2, emptyMap())),
                r.copy(edited = true, statisticsRuling = StatisticsRuling(1, mapOf("home-1" to 2))),
                r.copy(
                    edited = true,
                    statisticsRuling = StatisticsRuling(1, emptyMap(), setOf("home-1")),
                ),
                r.copy(edited = true, additionalErrors = listOf(ErrorCredit("home-12"))),
                r.copy(edited = true, additionalErrors = listOf(ErrorCredit("home-2", 10))),
            )
        for (record in invalid) unchanged(g) {
            assertFalse(History.preview(g, draft.copy(records = listOf(record))).canSave)
        }
        val uncertain =
            History.commit(
                g,
                draft.copy(
                    records =
                        listOf(
                            r.copy(edited = true, additionalErrors = listOf(ErrorCredit("home-2")))
                        ),
                    reason = "补记失误",
                ),
            )
        assertTrue(uncertain.statisticsIncomplete)
        assertEquals(1, uncertain.totalPitches)
        assertEquals(1, uncertain.batting["away-1"]!!.plateAppearances)
        val reviewing = History.draft(uncertain)
        val reviewed =
            History.commit(
                uncertain,
                reviewing.copy(
                    records =
                        reviewing.records.map {
                            it.copy(statisticsRuling = StatisticsRuling(0, mapOf("home-1" to 0)))
                        },
                    reason = "复核责任",
                ),
            )
        assertFalse(reviewed.statisticsIncomplete)
        assertEquals(1, reviewed.homeErrors)
        assertEquals(0, reviewed.batting["away-1"]!!.runsBattedIn)
        assertEquals(0, reviewed.pitching["home-1"]!!.earnedRuns)
    }

    @Test
    fun suspensionPausesClockAndLegacySuspensionRemainsReplayable() {
        val started =
            MatchEngine.apply(seed(), Command("pitch", pitch = PitchAction.ball, at = 1000))
        val paused = MatchEngine.apply(started, Command("suspend", at = 11000))
        assertFalse(paused.isFinal)
        assertNull(paused.clockRunningSince)
        assertEquals(10000L, paused.clockElapsedMs)
        val resumed =
            MatchEngine.apply(
                paused,
                Command("pitch", pitch = PitchAction.calledStrike, at = 21000),
            )
        assertEquals(1, resumed.balls)
        assertEquals(1, resumed.strikes)
        assertNull(resumed.clockRunningSince)
        assertEquals(10000L, resumed.elapsed(26000))
        val running = MatchEngine.apply(resumed, Command("clock", text = "resume", at = 31000))
        assertEquals(15000L, running.elapsed(36000))
        val legacy = MatchEngine.apply(started, Command("finish", text = "suspended", at = 11000))
        assertTrue(legacy.isFinal)
        assertTrue(MatchEngine.redo(MatchEngine.undo(legacy)).isFinal)
    }

    @Test
    fun correctionRevertKeepsLaterActionsAndEachWithdrawalCreatesANewRevision() {
        val g = pitch(seed())
        val d = History.draft(g)
        val corrected =
            History.commit(
                g,
                d.copy(
                    reason = "实际为看振",
                    records =
                        d.records.map {
                            it.copy(
                                command = it.command.copy(pitch = PitchAction.calledStrike),
                                edited = true,
                            )
                        },
                ),
            )
        val later = pitch(corrected)
        val restored = History.commit(later, History.revertingDraft(later))
        assertEquals(2, restored.balls)
        assertEquals(0, restored.strikes)
        assertEquals(2, restored.historyRevisions.size)
        val reapply = History.commit(restored, History.revertingDraft(restored))
        assertEquals(1, reapply.balls)
        assertEquals(1, reapply.strikes)
        assertEquals(3, reapply.historyRevisions.size)
    }
}
