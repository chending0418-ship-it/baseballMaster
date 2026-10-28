package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class ReportStatisticsProjectionTest {
    private fun team(id: String, count: Int = 13) = Team(
        id, id, (1..count).map { Player("$id-$it", "球员$it", if (it == 1) "00" else "$it") },
    )

    private fun game(rules: Rules = Rules()) = MatchFactory.create("report", team("home"), team("away"), rules)

    @Test fun boxScoreKeepsOrderAndActualParticipantsButNotUnusedBench() {
        val game = game(Rules(designatedHitter = true)).apply {
            // The DH bats for the pitcher; a substitute replaced the original second batter.
            awayOrder = listOf("away-10", "away-11") + (3..9).map { "away-$it" }
            awayPositions = (1..9).associateWith { if (it == 2) "away-11" else "away-$it" }
            exitedPlayers = setOf("away-2", "home-2")
            batting["away-2"] = BattingLine(atBats = 1, hits = 1)
            pitching["away-1"] = PitchingLine(outsRecorded = 3)
            fielding["away-12"] = FieldingLine(assists = 1)
            batting["home-13"] = BattingLine(atBats = 2, hits = 2)
        }
        val rows = ReportStatisticsProjection.gameParticipants(game, game.away.id)
        assertEquals(game.awayOrder!! + listOf("away-1", "away-2", "away-12"), rows.map { it.player.id })
        assertFalse(rows.any { it.player.id == "away-13" || it.player.id.startsWith("home-") })
        assertTrue(rows.all { it.games == 1 })
        assertEquals("00", rows.first { it.player.id == "away-1" }.player.number)
        assertEquals(1, rows.first { it.player.id == "away-2" }.batting.hits)
        assertEquals(3, rows.first { it.player.id == "away-1" }.pitching.outsRecorded)
        assertEquals(1, rows.first { it.player.id == "away-12" }.fielding.assists)
        // Formatting or aggregate operations must not mutate the saved match's statistics.
        rows.first { it.player.id == "away-2" }.batting.hits = 99
        assertEquals(1, game.batting.getValue("away-2").hits)
    }

    @Test fun slowPitchExtraBattersAndTwoWayPitcherAreNotDroppedOrCountedTwice() {
        val slow = game(Rules(GameMode.slowPitch)).apply {
            homeOrder = (1..11).map { "home-$it" }
        }
        assertEquals(slow.homeOrder, ReportStatisticsProjection.gameParticipants(slow, slow.home.id).map { it.player.id })
        val twoWay = game(Rules(designatedHitter = true, twoWayPlayer = true))
        val rows = ReportStatisticsProjection.gameParticipants(twoWay, twoWay.home.id)
        assertEquals(9, rows.size)
        assertEquals(1, rows.count { it.player.id == twoWay.homePitcherID })
    }

    @Test fun legacyImplicitLineupRetainsItsMeaningButExplicitEmptyLineupDoesNotBecomeFullRoster() {
        val legacy = game().apply { homeOrder = null; homePositions = null }
        assertEquals(legacy.home.players.map { it.id }, ReportStatisticsProjection.gameParticipants(legacy, legacy.home.id).map { it.player.id })
        val explicit = legacy.copy(homeOrder = emptyList(), homePositions = emptyMap())
        assertTrue(ReportStatisticsProjection.gameParticipants(explicit, explicit.home.id).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ReportStatisticsProjection.gameParticipants(legacy, "unrelated-team")
        }
    }

    @Test fun coachPitchRowsDoNotExposeAccidentallyStoredPitcherStatistics() {
        val coach = game(Rules(GameMode.coachPitch)).apply {
            pitching["home-1"] = PitchingLine(outsRecorded = 6, pitches = 30)
        }
        val pitcher = ReportStatisticsProjection.gameParticipants(coach, coach.home.id).first { it.player.id == "home-1" }
        assertEquals(PitchingLine(), pitcher.pitching)
        assertEquals(6, coach.pitching.getValue("home-1").outsRecorded)
    }

    @Test fun noticesReflectOnlyTheChosenGamesIncludingIncompletePrefixesAndPendingReviews() {
        val complete = game()
        val pending = game().copy(
            id = "pending", statisticsIncomplete = true,
            entries = listOf(PitchEntry("review", text = "待确认", incomplete = true), PitchEntry("ok", text = "已确认")),
        )
        val prefix = game().copy(id = "prefix", incompletePrefix = true)
        assertEquals(ReportStatisticsScope(0, 0, false), ReportStatisticsProjection.scope(listOf(complete)))
        assertEquals(ReportStatisticsScope(2, 1, false), ReportStatisticsProjection.scope(listOf(complete, pending, prefix)))
        assertEquals(ReportStatisticsScope(0, 0, false), ReportStatisticsProjection.scope(emptyList()))
        val coach = game(Rules(GameMode.coachPitch))
        assertEquals(ReportStatisticsScope(0, 0, true), ReportStatisticsProjection.scope(listOf(coach)))
        assertFalse(ReportStatisticsProjection.scope(listOf(coach, complete)).coachPitchOnly)
    }

    @Test fun reviewNotesRemainInBothReportStylesWithoutChangingTheirText() {
        val event = PitchEntry("review", text = "安打", incomplete = true, reviewNote = "改为二垒安打\n保留后续投球")
        assertEquals("安打（待确认）\n复核：改为二垒安打\n保留后续投球", ReportStatisticsProjection.eventDescription(event))
        assertEquals("安打（待确认）（复核：改为二垒安打\n保留后续投球）", ReportStatisticsProjection.eventDescription(event, textOnly = true))
        assertEquals("安打", ReportStatisticsProjection.eventDescription(event.copy(incomplete = false, reviewNote = "")))
        assertEquals("安打", ReportStatisticsProjection.eventDescription(event.copy(incomplete = false, reviewNote = null)))
    }

    @Test fun textReportPreservesPitcherChangesWithinTheRecordedAppearanceOnly() {
        val game = game()
        val first = game.situation().copy(pitcherID = "home-1")
        val second = first.copy(pitcherID = "home-2")
        val third = first.copy(pitcherID = "home-3")
        val events = listOf(
            PitchEntry("pitch", text = "好球", before = first, after = first),
            PitchEntry("change", text = "更换投手", before = first, after = second, reviewNote = "确认责任"),
            PitchEntry("pitch2", text = "坏球", before = second, after = second),
            PitchEntry("gap", text = "下一次投球", before = third, after = third),
            PitchEntry("end-half", text = "半局结束", before = third, after = third.copy(isTop = false, pitcherID = "away-1")),
        )
        val appearance = ProjectedAppearance("pa", 1, 1, true, "away-1", true, false, events)
        assertEquals(listOf(
            "投手：#00 球员1", "好球", "更换投手（复核：确认责任）（投手：#00 球员1 → #2 球员2）", "坏球",
            "投手：#2 球员2 → #3 球员3", "下一次投球", "半局结束",
        ), ReportStatisticsProjection.textAppearance(game, appearance))
    }

    @Test fun missingSnapshotsDoNotInventPitcherHistoryAndCoachPitchOmitsPitcherNames() {
        val game = game()
        val appearance = ProjectedAppearance("pa", 1, 1, true, "away-1", false, false,
            listOf(PitchEntry("legacy", text = "旧记录", pitcherID = "home-1")))
        assertEquals(listOf("投手：未记录", "旧记录"), ReportStatisticsProjection.textAppearance(game, appearance))
        assertEquals(listOf("投手：未记录"), ReportStatisticsProjection.textAppearance(game, appearance.copy(events = emptyList())))
        val before = game.situation().copy(pitcherID = "home-1")
        val change = PitchEntry("change", text = "记录", before = before, after = before.copy(pitcherID = "home-2"))
        assertEquals(listOf("投手不适用（教练投手）", "记录"), ReportStatisticsProjection.textAppearance(
            game.copy(rules = Rules(GameMode.coachPitch)), appearance.copy(events = listOf(change))))
    }
}
