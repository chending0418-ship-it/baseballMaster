package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class MatchSafetyTest {
    private fun seed(): Game {
        fun team(id: String) =
            Team(id, id, (1..12).map { Player("$id-$it", "球员$it", it.toString()) })
        return Game(
            "safety",
            team("home"),
            team("away"),
            Rules(),
            homeOrder = (1..9).map { "home-$it" },
            awayOrder = (1..9).map { "away-$it" },
        )
    }

    private fun reject(game: Game, command: Command) {
        val before = GameJson.encodeToString(game)
        try {
            MatchEngine.apply(game, command)
            fail("Invalid action accepted: ${command.kind}")
        } catch (_: IllegalArgumentException) {}
        assertEquals(
            "A rejected action cannot mutate the input",
            before,
            GameJson.encodeToString(game),
        )
    }

    @Test
    fun heldRunnerCannotBeOverwrittenAndInvalidProDecisionsAreAtomic() {
        val game = MatchEngine.apply(seed(), Command("play", outcome = Outcome.single))
        reject(
            game,
            Command(
                "play",
                outcome = Outcome.other,
                decisions = listOf(Decision("away-1", 1, 0), Decision("away-2", 0, 1)),
            ),
        )
        reject(
            game,
            Command(
                "play",
                outcome = Outcome.single,
                observation =
                    Observation(type = "line", precision = "point", x = Double.NaN, y = .2),
            ),
        )
        reject(
            game,
            Command(
                "play",
                outcome = Outcome.single,
                observation =
                    Observation(
                        type = "ground",
                        precision = "region",
                        region = "left",
                        fielding = Fielding(status = "unrecognized"),
                    ),
            ),
        )
        reject(
            game,
            Command(
                "play",
                outcome = Outcome.error,
                observation =
                    Observation(
                        type = "ground",
                        precision = "region",
                        region = "left",
                        fielding = Fielding(listOf(6, 3)),
                    ),
            ),
        )
        reject(
            game,
            Command(
                "play",
                outcome = Outcome.doublePlay,
                observation =
                    Observation(
                        type = "ground",
                        precision = "region",
                        region = "left",
                        fielding = Fielding(listOf(6, 4, 3)),
                    ),
                decisions = listOf(Decision("away-1", 1, 2), Decision("away-2", 0, -1)),
            ),
        )
    }

    @Test
    fun undoRedoBranchingAndObservationCorrectionPreserveScoresAndHistoricalNames() {
        val initial = seed()
        val single =
            MatchEngine.apply(
                initial,
                Command(
                    "play",
                    outcome = Outcome.single,
                    observation =
                        Observation(
                            type = "ground",
                            precision = "region",
                            region = "center",
                            fielding = Fielding(listOf(8)),
                        ),
                ),
            )
        val homeRun = MatchEngine.apply(single, Command("play", outcome = Outcome.homeRun))
        val undone = MatchEngine.undo(homeRun)
        assertEquals(0, undone.awayRuns)
        assertEquals("away-1", undone.bases[0])
        assertEquals(2, homeRun.awayRuns)
        val redone = MatchEngine.redo(undone)
        assertEquals(homeRun.batting, redone.batting)
        assertEquals(homeRun.pitching, redone.pitching)
        val amended =
            MatchEngine.amendObservation(
                redone,
                0,
                Observation(
                    type = "fly",
                    precision = "region",
                    region = "center",
                    fielding = Fielding(listOf(8)),
                ),
                "更正球型",
            )
        assertEquals(homeRun.batting, amended.batting)
        assertEquals(homeRun.pitching, amended.pitching)
        assertEquals(1, amended.correctionRevisions.size)
        val substituted =
            MatchEngine.apply(
                amended,
                Command("fielder", playerID = "home-8", replacementID = "home-12"),
            )
        assertEquals("home-12", substituted.fieldingPositions[8])
        assertEquals(
            "home-8",
            substituted.entries.first().participants.first { it.position == 8 }.playerID,
        )
        val branch = MatchEngine.apply(undone, Command("pitch", pitch = PitchAction.ball))
        assertEquals(branch.cursor, branch.journal.size)
        try {
            MatchEngine.redo(branch)
            fail("Discarded redo branch was replayed")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, initial.totalPitches)
        assertTrue(initial.entries.isEmpty())
    }

    @Test
    fun illegalCommandsModesAndFinalStateNeverCreatePartialStatistics() {
        reject(seed().apply { rules = Rules(GameMode.coachPitch) }, Command("intentionalWalk"))
        val slow =
            seed().apply {
                rules = Rules(GameMode.slowPitch)
                balls = 1
                strikes = 1
            }
        reject(slow, Command("play", outcome = Outcome.sacrificeBunt))
        reject(slow, Command("runner", runnerKind = "stolenBase"))
        reject(seed(), Command("foulBunt"))
        reject(seed(), Command("runner", runnerKind = "invented"))
        val final = MatchEngine.apply(seed(), Command("finish", text = "weather", at = 20_000))
        reject(final, Command("pitch", pitch = PitchAction.ball))
        reject(final, Command("clock", text = "resume"))
        val reopened = MatchEngine.apply(final, Command("reopen", at = 30_000))
        assertFalse(reopened.isFinal)
        assertNull(reopened.clockRunningSince)
    }

    @Test
    fun clockRunsFromPlayBallAndCannotLoseTimeByStartingTwice() {
        val started = MatchEngine.apply(seed(), Command("clock", text = "start", at = 1_000))
        reject(started, Command("clock", text = "resume", at = 2_000))
        val paused = MatchEngine.apply(started, Command("clock", text = "pause", at = 11_000))
        assertEquals(10_000L, paused.elapsed(80_000))
        val resumed = MatchEngine.apply(paused, Command("clock", text = "resume", at = 90_000))
        assertEquals(15_000L, resumed.elapsed(95_000))
        val final = MatchEngine.apply(resumed, Command("finish", text = "timeLimit", at = 100_000))
        assertEquals(20_000L, final.elapsed(999_000))
    }

    @Test
    fun canceledPreviousPlayIsReplayedAsOneAdjudicationAndCanBeUndone() {
        val initial = seed()
        val homeRun = MatchEngine.apply(initial, Command("play", outcome = Outcome.homeRun))
        val canceled =
            MatchEngine.apply(
                homeRun,
                Command(
                    "adjudication",
                    runnerKind = "catcherInterference",
                    adjudication = Ruling(previousPlay = "cancel", plateAppearance = "batterFirst"),
                    decisions = listOf(Decision("away-1", 0, 1)),
                ),
            )
        assertEquals(0, canceled.awayRuns)
        assertEquals(0, canceled.awayHits)
        assertEquals("away-1", canceled.bases[0])
        assertEquals(1, canceled.awayBatterIndex)
        assertEquals(0, canceled.batting["away-1"]!!.atBats)
        val undone = MatchEngine.undo(canceled)
        assertEquals(1, undone.awayRuns)
        assertEquals(1, undone.awayHits)
        val redone = MatchEngine.redo(undone)
        assertEquals(canceled.batting, redone.batting)
        assertEquals(canceled.bases, redone.bases)
    }

    @Test
    fun changingBatterThroughHistoryRequiresResolvingLaterContext() {
        var game = MatchEngine.apply(seed(), Command("play", outcome = Outcome.single))
        game = MatchEngine.apply(game, Command("pitch", pitch = PitchAction.calledStrike))
        val before = GameJson.encodeToString(game)
        try {
            MatchEngine.correct(game, 0, null, "错误打席")
            fail("Later batter silently changed")
        } catch (_: IllegalStateException) {}
        assertEquals(before, GameJson.encodeToString(game))
    }

    @Test
    fun designatedHitterPitcherAndTwoWayReliefKeepTheirParticipation() {
        val seed = seed()
        val traditional =
            MatchFactory.create("dh", seed.home, seed.away, Rules(designatedHitter = true))
        assertEquals("home-10", traditional.homeDesignatedHitterID)
        assertEquals("home-1", traditional.pitcher.id)
        assertFalse(traditional.homeOrder!!.contains(traditional.pitcher.id))
        var twoWay =
            MatchFactory.create(
                "two-way",
                seed.home,
                seed.away,
                Rules(designatedHitter = true, twoWayPlayer = true),
            )
        twoWay = MatchEngine.apply(twoWay, Command("pitch", pitch = PitchAction.ball))
        twoWay = MatchEngine.apply(twoWay, Command("pitcher", playerID = "home-11"))
        assertEquals("home-1", twoWay.homeDesignatedHitterID)
        assertTrue(twoWay.homeOrder!!.contains("home-1"))
        assertFalse(twoWay.homeOrder!!.contains("home-11"))
        twoWay = MatchEngine.apply(twoWay, Command("pitcher", playerID = "home-1"))
        assertEquals("home-1", twoWay.pitcher.id)
        assertEquals(1, twoWay.homeOrder!!.count { it == "home-1" })
    }

    @Test
    fun pinchReplacementsOfPitcherSlotRemainThePitcherAfterChangingSides() {
        val seed = seed()
        var game = MatchFactory.create("sub", seed.home, seed.away, Rules())
        game = MatchEngine.apply(game, Command("batter", playerID = "away-11"))
        game = MatchEngine.apply(game, Command("play", outcome = Outcome.single))
        game = MatchEngine.apply(game, Command("pinchRunner", position = 1, playerID = "away-12"))
        game = MatchEngine.apply(game, Command("endHalf"))
        assertEquals("away-12", game.pitcher.id)
        assertEquals("away-12", game.awayOrder!!.first())
    }

    @Test
    fun upgradingUnknownLegacyPitchTimesDoesNotInventPlayedDateOrRunningClock() {
        val initial = seed()
        val legacy =
            initial.copy(
                balls = 1,
                totalPitches = 1,
                appearancePitches = 1,
                revision = 1,
                entries = listOf(PitchEntry("legacy", PitchAction.ball, "旧投球")),
            )
        val upgraded = MatchEngine.upgradeLegacy(legacy)
        assertEquals(1, upgraded.balls)
        assertFalse(upgraded.incompletePrefix)
        assertNull(upgraded.startedAt)
        assertFalse(upgraded.clockHasStarted)
        val afterRedo = MatchEngine.redo(MatchEngine.undo(upgraded))
        assertNull(afterRedo.startedAt)
        assertFalse(afterRedo.clockHasStarted)
    }
}
