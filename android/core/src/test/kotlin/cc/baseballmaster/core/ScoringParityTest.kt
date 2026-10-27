package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Expected values are exported by a real XCTest run of the iOS GameStore. */
class ScoringParityTest {
    private fun canonical(game: Game): JsonObject {
        val source = GameJson.encodeToJsonElement(game).jsonObject
        val keys =
            listOf(
                "inning",
                "isTop",
                "balls",
                "strikes",
                "outs",
                "homeBatterIndex",
                "awayBatterIndex",
                "appearancePitches",
                "extraFoulUsed",
                "bases",
                "homeRuns",
                "awayRuns",
                "homeRunsByInning",
                "awayRunsByInning",
                "homeHits",
                "awayHits",
                "homeErrors",
                "awayErrors",
                "homeOrder",
                "awayOrder",
                "isFinal",
                "pendingDecision",
                "halfEndedAwaitingDecision",
                "walkResponsiblePitcherID",
                "strikeoutResponsibleBatterID",
            )
        return buildJsonObject {
            keys.forEach { put(it, source.getValue(it)) }
            put("pitcherID", game.pitcher.id)
            listOf("batting", "pitching", "fielding").forEach { key ->
                put(
                    key,
                    JsonObject(
                        source.getValue(key).jsonObject.filterValues { line ->
                            line.jsonObject.values.any { it.jsonPrimitive.int != 0 }
                        }
                    ),
                )
            }
            val active = game.bases.filterNotNull().toSet()
            put(
                "runnerPitcherIDs",
                JsonObject(
                    game.runnerPitcherIDs
                        .filterKeys { it in active }
                        .mapValues { JsonPrimitive(it.value) }
                ),
            )
            put(
                "unearnedRunnerIDs",
                JsonArray(
                    game.unearnedRunnerIDs.filter { it in active }.sorted().map(::JsonPrimitive)
                ),
            )
            put(
                "automaticRunnerIDs",
                JsonArray(
                    game.automaticRunnerIDs.filter { it in active }.sorted().map(::JsonPrimitive)
                ),
            )
        }
    }

    @Test
    fun scoringStatesAndAllOfficialStatisticsMatchIOS() {
        val fixtures =
            File(System.getProperty("parity.fixtures"), "scoring").listFiles()!!.filter {
                it.name.endsWith("-scoring.json")
            }
        assertTrue("The checked-in iOS evidence must exist", fixtures.size >= 20)
        val failures = mutableListOf<String>()
        for (file in fixtures.sortedBy { it.name }) {
            try {
                val fixture = GameJson.parseToJsonElement(file.readText()).jsonObject
                var game = GameJson.decodeFromJsonElement<Game>(fixture.getValue("initialGame"))
                fun check(label: String, expected: JsonElement) {
                    val actual = canonical(game)
                    for ((key, value) in expected.jsonObject) assertEquals(
                        "${file.name}: $label: $key",
                        value,
                        actual[key],
                    )
                }
                check("initial", fixture.getValue("initial"))
                fixture.getValue("steps").jsonArray.forEachIndexed { index, element ->
                    val step = element.jsonObject
                    val command = GameJson.decodeFromJsonElement<Command>(step.getValue("command"))
                    game =
                        when (command.kind) {
                            "undo" -> MatchEngine.undo(game)
                            "redo" -> MatchEngine.redo(game)
                            else ->
                                MatchEngine.apply(
                                    game,
                                    command.copy(at = 1000L * (index + 1)),
                                    "${file.name}-$index",
                                )
                        }
                    check(
                        "step $index ${command.kind}/${command.outcome}",
                        step.getValue("expected"),
                    )
                    assertEquals(
                        "Lossless local serialization",
                        game,
                        GameJson.decodeFromString<Game>(GameJson.encodeToString(game)),
                    )
                }
            } catch (failure: Throwable) {
                failures += "${file.name}: ${failure.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
