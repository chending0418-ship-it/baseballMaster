package cc.baseballmaster.core

import java.time.Year

/** The home record only includes completed games for the selected local team and calendar year. */
data class HomeRecord(val wins: Int, val losses: Int, val ties: Int) {
    val label: String
        get() =
            if (wins + losses + ties == 0) "暂无战绩"
            else if (ties == 0) "${wins}胜 ${losses}负" else "${wins}胜 ${losses}负 ${ties}平"
}

object HomeSummary {
    fun record(games: List<Game>, teamID: String?, year: Int = Year.now().value): HomeRecord {
        if (teamID == null) return HomeRecord(0, 0, 0)
        var wins = 0
        var losses = 0
        var ties = 0
        games.forEach { game ->
            val owner =
                game.ourTeamID
                    ?: when {
                        !game.home.isOpponent && game.away.isOpponent -> game.home.id
                        !game.away.isOpponent && game.home.isOpponent -> game.away.id
                        else -> null
                    }
            if (
                !game.isFinal ||
                    game.practice ||
                    owner != teamID ||
                    runCatching { Performances.year(game) }.getOrNull() != year
            )
                return@forEach
            val ours =
                when (teamID) {
                    game.home.id -> game.homeRuns to game.awayRuns
                    game.away.id -> game.awayRuns to game.homeRuns
                    else -> return@forEach
                }
            when {
                ours.first > ours.second -> wins++
                ours.first < ours.second -> losses++
                else -> ties++
            }
        }
        return HomeRecord(wins, losses, ties)
    }
}
