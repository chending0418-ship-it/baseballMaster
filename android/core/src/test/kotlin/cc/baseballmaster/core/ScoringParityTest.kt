package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Expected values are exported by a real XCTest run of the iOS GameStore. */
class ScoringParityTest {
    @Test
    fun historyCorrectionsAndInterruptionMatchIOS() {
        val fixtures =
            File(System.getProperty("parity.fixtures"), "history").listFiles()!!.filter {
                it.name.endsWith("-scoring.json")
            }
        assertEquals(6, fixtures.size)
        for (file in fixtures.sortedBy { it.name }) {
            val fixture = GameJson.parseToJsonElement(file.readText()).jsonObject
            var game = GameJson.decodeFromJsonElement<Game>(fixture.getValue("initialGame"))
            for((key,value)in fixture.getValue("initial").jsonObject)assertEquals("${file.name}: initial: $key",value,canonical(game)[key])
            fixture.getValue("steps").jsonArray.forEachIndexed { index, element ->
                val step = element.jsonObject
                val command = step.getValue("command").jsonObject
                val kind = command.getValue("kind").jsonPrimitive.content
                fun insert(draft: CorrectionDraft, item: JsonObject): CorrectionDraft =
                    History.insert(
                        game,
                        draft,
                        item.getValue("index").jsonPrimitive.int,
                        GameJson.decodeFromJsonElement<Command>(item.getValue("insert")),
                    )
                game =
                    when (kind) {
                        "historyInsert" ->
                            History.commit(
                                game,
                                insert(History.draft(game), command).copy(reason = "补记"),
                            )
                        "historyInsertMany" -> {
                            var draft = History.draft(game)
                            command.getValue("insertions").jsonArray.forEach {
                                draft = insert(draft, it.jsonObject)
                            }
                            History.commit(game, draft.copy(reason = "补记"))
                        }
                        "historyRevert" -> History.commit(game, History.revertingDraft(game))
                        "historyAmend" -> {
                            val draft = History.draft(game)
                            val at = command.getValue("index").jsonPrimitive.int
                            val records = draft.records.toMutableList()
                            records[at] =
                                records[at].copy(
                                    edited = true,
                                    additionalErrors =
                                        command["errors"]?.let {
                                            GameJson.decodeFromJsonElement<List<ErrorCredit>>(it)
                                        } ?: records[at].additionalErrors,
                                    statisticsRuling =
                                        command["ruling"]?.let {
                                            GameJson.decodeFromJsonElement<StatisticsRuling>(it)
                                        } ?: records[at].statisticsRuling,
                                )
                            History.commit(game, draft.copy(records = records, reason = "人工复核"))
                        }
                        else ->
                            MatchEngine.apply(
                                game,
                                GameJson.decodeFromJsonElement<Command>(command)
                                    .copy(at = (index + 1) * 1000L),
                                "$index",
                            )
                    }
                val actual =
                    JsonObject(
                        canonical(game) +
                            mapOf(
                                "statisticsIncomplete" to JsonPrimitive(game.statisticsIncomplete),
                                "correctionCount" to JsonPrimitive(game.historyRevisions.size),
                                "clockRunning" to JsonPrimitive(game.clockRunningSince != null),
                            )
                    )
                for ((key, value) in step.getValue("expected").jsonObject) assertEquals(
                    "${file.name}: $index $kind: $key",
                    value,
                    actual[key],
                )
                assertEquals(game, GameJson.decodeFromString<Game>(GameJson.encodeToString(game)))
            }
        }
    }

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
