package cc.baseballmaster.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AppleRosterBackupTest {
    @Test fun authenticIOSBackupRosterConvertsWithoutImportingMatches() {
        val root = File(System.getProperty("parity.fixtures"))
        val bytes = File(root, "backup/ios-reference-v7.bmbackup").readBytes()
        val inspection = AppleBackupEnvelope.inspect(bytes)
        val teams = AppleRosterBackup.decode(inspection)
        assertEquals(1, inspection.gameCount)
        assertEquals(3, teams.size)
        assertEquals(1, teams.count { !it.isOpponent })
        assertEquals(2, teams.count { it.isOpponent })
        assertTrue(teams.flatMap { it.players }.any { it.chineseName == "陈昊" && it.englishName == "Chen Hao" && it.jerseyNumbers == listOf("12", "88") })
    }
    @Test fun rosterOnlyFixtureKeepsNamesAndZeroLabelsAndRejectsGames() {
        val player = Player("a1000000-0000-0000-0000-000000000001", "林安", "00", englishName = "An Lin", position = 6, jerseyNumbers = listOf("00", "7"), chineseName = "林安")
        val team = Team("a1000000-0000-0000-0000-000000000000", "合成春雷", listOf(player), city = "合成城市", shortName = "春雷")
        val data = AppleRosterBackup.encode(listOf(team), emptyList(), team.id, 1_790_000_000_000)
        val decoded = AppleBackupEnvelope.inspect(data)
        assertEquals(1, decoded.teamCount); assertEquals(0, decoded.gameCount)
        val root = File(System.getProperty("parity.fixtures"))
        assertArrayEquals(File(root, "backup/android-roster-reference-v7.bmbackup").readBytes(), data)
        val opponent = Team("b1000000-0000-0000-0000-000000000000", "对手", listOf(Player("b1000000-0000-0000-0000-000000000001", "对手", "2")), true)
        val game = Game("c1000000-0000-0000-0000-000000000000", team, opponent, Rules())
        assertThrows(IllegalArgumentException::class.java) { AppleRosterBackup.encode(listOf(team), listOf(game), team.id, 0) }
        assertThrows(IllegalArgumentException::class.java) { AppleRosterBackup.encode(listOf(team.copy(players = listOf(player.copy(archived = true)))), emptyList(), team.id, 0) }
    }
}
