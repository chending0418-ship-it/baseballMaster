package cc.baseballmaster.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleExportPrototypeTest {
    @Test fun exportsIOSRunnerDetailReplayForReturnToIOS() {
        val root = File(System.getProperty("parity.fixtures"), "backup")
        val catalog = GameJson.parseToJsonElement(File(root, "ios-journal-catalog.json").readText()).jsonArray
        val source = catalog.single { it.jsonObject.getValue("scenario").jsonPrimitive.content == "runner-defense-detail" }.jsonObject
        val game = AppleReplayPrototype.replay(source.getValue("storedGame").jsonObject)
        val bytes = AppleExportPrototype.encode(game)
        val fixture = File(root, "ios-android-runner-detail-prototype.bmbackup")
        assertArrayEquals(fixture.readBytes(), bytes)
    }
    @Test fun producesComplexSyntheticGameForIOSVerification() {
        fun team(prefix: String, name: String, opponent: Boolean): Team = Team("$prefix-0000-0000-0000-000000000000", name,
            (1..9).map { i -> Player("$prefix-0000-0000-0000-${i.toString().padStart(12, '0')}", "球员$i", i.toString(), position = i, chineseName = "球员$i") },
            isOpponent = opponent, city = "合成城市", shortName = name)
        val away = team("a3000000", "合成春雷", false); val home = team("b3000000", "合成海风", true)
        val time = 1_790_000_000_000L
        var game = MatchFactory.create("c3000000-0000-0000-0000-000000000000", home, away, Rules(), away.id).copy(createdAt = time, updatedAt = time)
        fun apply(command: Command, index: Int) { game = MatchEngine.apply(game, command.copy(at = time + index * 1000), "d3000000-0000-0000-0000-${index.toString().padStart(12, '0')}") }
        apply(Command("pitch", pitch = PitchAction.ball), 1)
        apply(Command("play", outcome = Outcome.single), 2)
        apply(Command("runner", runnerKind = "stolenBase", decisions = MatchEngine.suggestedRunner(game, "stolenBase")), 3)
        apply(Command("play", outcome = Outcome.groundOut, observation = Observation(type = "ground", precision = "point", x = .42, y = .58, fielding = Fielding(route = listOf(6, 3)))), 4)
        apply(Command("finish", text = "scorerDecision"), 5)
        val bytes = AppleExportPrototype.encode(game)
        val inspected = AppleBackupEnvelope.inspect(bytes)
        assertEquals(1, inspected.gameCount)
        val fixture = File(System.getProperty("parity.fixtures"), "backup/android-complex-prototype-v7.bmbackup")
        assertArrayEquals(fixture.readBytes(), bytes)
    }
    @Test fun producesDeterministicSyntheticGameForIOSVerification() {
        fun team(prefix: String, name: String, opponent: Boolean): Team {
            val teamID = "$prefix-0000-0000-0000-000000000000"
            val players = (1..9).map { i ->
                Player("$prefix-0000-0000-0000-${i.toString().padStart(12, '0')}", "球员$i", i.toString(), position = i, chineseName = "球员$i")
            }
            return Team(teamID, name, players, isOpponent = opponent, city = "合成城市", shortName = name)
        }
        val away = team("a1000000", "合成春雷", false)
        val home = team("b1000000", "合成海风", true)
        val time = 1_790_000_000_000L
        var game = MatchFactory.create("c1000000-0000-0000-0000-000000000000", home, away, Rules(), away.id).copy(createdAt = time, updatedAt = time)
        game = MatchEngine.apply(game, Command("pitch", pitch = PitchAction.ball, at = time + 1000), "d1000000-0000-0000-0000-000000000001")
        game = MatchEngine.apply(game, Command("play", outcome = Outcome.single, at = time + 2000), "d1000000-0000-0000-0000-000000000002")
        val bytes = AppleExportPrototype.encode(game)
        val inspected = AppleBackupEnvelope.inspect(bytes)
        assertEquals(1, inspected.gameCount)
        assertEquals(1, inspected.teamCount)
        val fixture = File(System.getProperty("parity.fixtures"), "backup/android-game-prototype-v7.bmbackup")
        assertArrayEquals(fixture.readBytes(), bytes)
    }
}
