package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonStatisticsParityTest {
    @Test fun seasonTotalsMatchRealIOSReference() {
        val fixture = GameJson.parseToJsonElement(File(System.getProperty("parity.fixtures"), "stats/ios-season-reference.json").readText()).jsonObject
        var game = GameJson.decodeFromJsonElement<Game>(fixture.getValue("initialGame"))
        val team = game.away
        game = game.copy(ourTeamID = team.id, createdAt = fixture.getValue("createdAt").jsonPrimitive.long)
        fixture.getValue("steps").jsonArray.forEachIndexed { i, step ->
            val command = GameJson.decodeFromJsonElement<Command>(step.jsonObject.getValue("command"))
            game = MatchEngine.apply(game, command.copy(at = fixture.getValue("createdAt").jsonPrimitive.long + (i + 1) * 1000L), "season-$i")
        }
        val actual = SeasonStatisticsCalculator.calculate(team, listOf(game), fixture.getValue("year").jsonPrimitive.int)
        val expected = fixture.getValue("expected").jsonObject
        assertEquals(expected.getValue("games").jsonPrimitive.int, actual.games.size)
        assertEquals(expected.getValue("wins").jsonPrimitive.int, actual.wins)
        assertEquals(expected.getValue("losses").jsonPrimitive.int, actual.losses)
        assertEquals(expected.getValue("ties").jsonPrimitive.int, actual.ties)
        assertEquals(expected.getValue("runs").jsonPrimitive.int, actual.runs)
        assertEquals(expected.getValue("runsAllowed").jsonPrimitive.int, actual.runsAllowed)
        assertEquals(expected.getValue("batting"), GameJson.encodeToJsonElement(actual.batting))
        assertEquals(expected.getValue("pitching"), GameJson.encodeToJsonElement(actual.pitching))
        assertEquals(expected.getValue("fielding"), GameJson.encodeToJsonElement(actual.fielding))
    }
}
