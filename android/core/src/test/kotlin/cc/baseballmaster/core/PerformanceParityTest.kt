package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PerformanceParityTest {
    @Test
    fun annualScopeIdentityDedupAndIncompleteGamesMatchIOS() {
        val f =
            GameJson.parseToJsonElement(
                    File(System.getProperty("parity.fixtures"), "performance/performance.json")
                        .readText()
                )
                .jsonObject
        var template = GameJson.decodeFromJsonElement<Game>(f.getValue("initialGame"))
        f.getValue("commands").jsonArray.forEachIndexed { i, c ->
            template =
                MatchEngine.apply(
                    template,
                    GameJson.decodeFromJsonElement<Command>(c).copy(at = (i + 1) * 1000L),
                    "event-$i",
                )
        }
        val games =
            f.getValue("sources").jsonArray.map { element ->
                val m = element.jsonObject
                GameJson.decodeFromString<Game>(GameJson.encodeToString(template)).let { g ->
                    g.copy(id = m.getValue("id").jsonPrimitive.content).apply {
                        playedAt = m.getValue("playedAt").jsonPrimitive.long
                        playedTimeZoneID = m.getValue("timeZoneID").jsonPrimitive.content
                        rules =
                            rules.copy(
                                mode = GameMode.valueOf(m.getValue("mode").jsonPrimitive.content)
                            )
                        historySourceID = m["historySourceID"]?.jsonPrimitive?.contentOrNull
                        updatedAt = m.getValue("updatedAt").jsonPrimitive.long
                        statisticsIncomplete = m.getValue("incomplete").jsonPrimitive.boolean
                        if (m.getValue("foreignTeam").jsonPrimitive.boolean)
                            away = away.copy(id = "foreign-team")
                    }
                }
            }
        val current = games.first()
        val pid = f.getValue("playerID").jsonPrimitive.content
        for (annual in listOf(false, true)) {
            val p = Performances.calculate(current, games, pid, annual)
            val expected = f.getValue(if (annual) "annual" else "current").jsonObject
            assertEquals(expected.getValue("line"), GameJson.encodeToJsonElement(p.line))
            assertEquals(expected.getValue("gameCount").jsonPrimitive.int, p.gameCount)
            assertEquals(expected.getValue("contacts").jsonPrimitive.int, p.contacts.size)
            assertEquals(
                expected.getValue("missingLocations").jsonPrimitive.int,
                p.missingLocations,
            )
            assertEquals(
                expected.getValue("completedAppearances").jsonPrimitive.int,
                p.line.plateAppearances,
            )
        }
        assertEquals(2026, Performances.year(current))
        assertEquals(2025, Performances.year(games[4]))
        assertEquals(1, Performances.calculate(current, games, pid, true).incompleteGames)
    }
}
