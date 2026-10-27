package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PitchParityTest {
    @Test
    fun allThreeModesMatchIOSReferenceAtEveryPitch() {
        val fixtures =
            File(System.getProperty("parity.fixtures")).listFiles()!!.filter {
                it.name.endsWith("-pitch-seed.json")
            }
        assertEquals(3, fixtures.size)
        fixtures.forEach { file ->
            val fixture = GameJson.parseToJsonElement(file.readText()).jsonObject
            val mode = GameMode.valueOf(fixture.getValue("mode").jsonPrimitive.content)
            val count = if (mode == GameMode.slowPitch) 10 else 9
            fun team(id: String) =
                Team(id, id, (0 until count).map { Player("$id-$it", "球员${it + 1}", "${it + 1}") })
            var game = Game("fixture", team("home"), team("away"), Rules(mode))
            fun check(expected: JsonElement) {
                val canonical = buildJsonObject {
                    put("balls", game.balls)
                    put("strikes", game.strikes)
                    put("outs", game.outs)
                    put("inning", game.inning)
                    put("isTop", game.isTop)
                    put("batterIndex", game.batterIndex)
                    put("appearancePitches", game.appearancePitches)
                    put("extraFoulUsed", game.extraFoulUsed)
                    putJsonArray("bases") {
                        game.bases.forEach { id ->
                            add(game.battingTeam.players.indexOfFirst { it.id == id })
                        }
                    }
                }
                assertEquals("${file.name} revision ${game.revision}", expected, canonical)
            }
            check(fixture.getValue("initial"))
            fixture.getValue("steps").jsonArray.forEachIndexed { index, element ->
                val step = element.jsonObject
                game =
                    PitchReducer.apply(
                        game,
                        PitchAction.valueOf(step.getValue("action").jsonPrimitive.content),
                        "op-$index",
                    )
                check(step.getValue("expected"))
            }
            assertEquals(game, GameJson.decodeFromString<Game>(GameJson.encodeToString(game)))
        }
    }

    @Test
    fun threeOutsSwitchSidesAndResetCountsButKeepEachTeamsBattingOrder() {
        fun team(id: String) = Team(id, id, (0..8).map { Player("$id-$it", "$it", "$it") })
        var game = Game("id", team("home"), team("away"), Rules())
        repeat(9) { game = PitchReducer.apply(game, PitchAction.calledStrike, "op-$it") }
        assertFalse(game.isTop)
        assertEquals(1, game.inning)
        assertEquals(0, game.outs)
        assertEquals(3, game.awayBatterIndex)
        assertEquals(0, game.homeBatterIndex)
        assertEquals(listOf(null, null, null), game.bases)
    }
}
