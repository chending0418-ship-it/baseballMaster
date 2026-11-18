package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisSourceTest {
    private fun uuid() = UUID.randomUUID().toString()
    private val own = team("私有主队", false)
    private val opponent = team("私有对手", true)
    private val date = 1_773_700_000_000L

    @Test fun singleWireAllowsStatisticsAndAliasesOnly() {
        val game = finalGame(date, ownHome = false)
        val source = AnalysisSource.single(game)
        val id = uuid()
        val request = analysisRequest(source.input, id)
        assertEquals(id, request.id)
        assertEquals(setOf("requestID", "input"), request.body.keys)
        val input = request.body.getValue("input").jsonObject
        assertEquals("single", input.getValue("scope").jsonPrimitive.content)
        val first = input.getValue("games").jsonArray.single().jsonObject
        assertEquals(listOf(1, 1, 0, 0, 0, 0), first.getValue("totals").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(first.getValue("incomplete").jsonPrimitive.boolean)
        val hr = first.getValue("events").jsonArray.map { it.jsonObject }.single { it.getValue("kind").jsonPrimitive.content == "HR" }
        assertEquals("P1", hr.getValue("batter").jsonPrimitive.content)
        assertEquals(listOf(0, 0), hr.getValue("beforeScore").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(1, 0), hr.getValue("afterScore").jsonArray.map { it.jsonPrimitive.int })
        val strikeout = first.getValue("events").jsonArray.map { it.jsonObject }.single { it.getValue("kind").jsonPrimitive.content == "K" }
        assertEquals(3, strikeout.getValue("before").jsonArray.last().jsonPrimitive.int)
        val finish = first.getValue("events").jsonArray.last().jsonObject
        assertFalse("A game-level event must not be attributed to a batter", "batter" in finish)
        val player = input.getValue("players").jsonArray.first().jsonObject
        assertEquals(4, player.getValue("batting").jsonArray[3].jsonPrimitive.int)
        assertFalse("Unrecorded pitching must be omitted", "pitching" in player)
        val wire = request.body.toString()
        listOf(game.id, own.id, opponent.id, own.name, opponent.name, own.players.first().id, own.players.first().name,
            game.playedTimeZoneID, date.toString(), "保密说明").forEach { assertFalse(it, wire.contains(it)) }
        assertTrue(source.display("P1 和 P999").contains(own.players.first().name))
        assertFalse(source.display("P1 和 P999").contains("P999"))
    }

    @Test fun seasonAndSelectedScopeUseTheSameOfficialStatisticsAndChronologicalGames() {
        val early = finalGame(date, ownHome = false)
        val late = finalGame(date + 60_000, ownHome = true)
        val unfinished = MatchFactory.create(uuid(), own, opponent, Rules(), own.id).copy(playedAt = date)
        val year = requireNotNull(MatchDates.year(early))
        val games = listOf(late, early, unfinished)
        val season = AnalysisSource.season(own, year, SeasonStatisticsCalculator.calculate(own, games, year), false)
        assertEquals(AnalysisScope.season, season.input.scope)
        assertEquals(listOf(false, true), season.input.games.map { it.ownHome })
        assertTrue(season.input.players.all { it.side == 2 && it.games == 2 })
        val selected = AnalysisSource.season(own, year,
            SeasonStatisticsCalculator.calculate(own, games, year, selectedIDs = setOf(late.id)), true)
        assertEquals(AnalysisScope.filtered, selected.input.scope)
        assertEquals(true, selected.input.games.single().ownHome)
        assertTrue(selected.input.players.all { it.games == 1 })
    }

    @Test fun deviceRegistrationOnlyOnceAndCredentialExpiryStopsAnalysisPolls() {
        val source = AnalysisSource.single(finalGame(date, ownHome = false))
        val calls = mutableListOf<String>()
        val device = uuid()
        var directoryState = TeamDirectoryState()
        val client = TeamDirectoryClient { method, path, body ->
            calls += "$method $path"
            when (path) {
                "devices" -> buildJsonObject { put("deviceID", device); put("protocol", 1) }
                "game-analysis" -> {
                    assertEquals(device, directoryState.deviceID)
                    assertEquals(setOf("requestID", "input"), requireNotNull(body).keys)
                    buildJsonObject { put("state", "pending") }
                }
                else -> throw TeamServiceFault(401, "unauthorized")
            }
        }
        val session = TeamDirectorySession(client, object : TeamDirectoryStorage {
            override val monitor = Any()
            override fun load() = directoryState
            override fun save(state: TeamDirectoryState) { directoryState = state }
        }, "合成设备")
        assertEquals(device, session.analysisDevice())
        assertEquals(device, session.analysisDevice())
        val request = analysisRequest(source.input, uuid())
        assertEquals(AnalysisState.pending, session.startAnalysis(device, request).state)
        assertThrows(TeamServiceFault::class.java) { session.analysisStatus(device, request.id) }
        assertThrows(IllegalStateException::class.java) { session.analysisStatus(device, request.id) }
        assertEquals(listOf("POST devices", "POST game-analysis", "GET game-analysis/${request.id}"), calls)
    }

    private fun team(name: String, opponent: Boolean) = Team(uuid(), name,
        (1..9).map { Player(uuid(), "保密名称 $it", "$it") }, opponent)

    private fun finalGame(whenPlayed: Long, ownHome: Boolean): Game {
        val game = MatchFactory.create(uuid(), if (ownHome) own else opponent, if (ownHome) opponent else own, Rules(), own.id)
            .copy(playedAt = whenPlayed)
        val scored = MatchEngine.apply(game, Command("play", outcome = Outcome.homeRun, at = whenPlayed))
        var pending = MatchEngine.apply(scored, Command("play", outcome = Outcome.pending, at = whenPlayed))
        repeat(3) { pending = MatchEngine.apply(pending, Command("pitch", pitch = PitchAction.swingingStrike, at = whenPlayed)) }
        val noted = MatchEngine.apply(pending, Command("note", text = "保密说明", at = whenPlayed))
        return MatchEngine.apply(noted, Command("finish", at = whenPlayed))
    }
}
