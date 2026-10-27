package cc.baseballmaster.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val GameJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable enum class GameMode(val title: String) {
    standard("普通比赛"), coachPitch("教练投手"), slowPitch("成人慢垒")
}
@Serializable enum class PitchAction(val title: String) {
    ball("坏球"), calledStrike("看振"), swingingStrike("挥空"), foul("界外")
}
@Serializable data class Player(val id: String, val name: String, val number: String)
@Serializable data class Team(val id: String, val name: String, val players: List<Player>, val isOpponent: Boolean = false)
@Serializable data class Rules(
    val mode: GameMode = GameMode.standard,
    val initialBalls: Int = if (mode == GameMode.slowPitch) 1 else 0,
    val initialStrikes: Int = if (mode == GameMode.slowPitch) 1 else 0,
    val coachPitchLimit: Int = 6,
    val oneExtraFoul: Boolean = true,
    val innings: Int = 6
) {
    init {
        require(initialBalls in 0..3 && initialStrikes in 0..2)
        require(coachPitchLimit in 1..20 && innings in 1..99)
    }
}
@Serializable data class PitchEntry(val id: String, val action: PitchAction, val text: String)
@Serializable data class Game(
    val id: String,
    val home: Team,
    val away: Team,
    val rules: Rules,
    val inning: Int = 1,
    val isTop: Boolean = true,
    val balls: Int = rules.initialBalls,
    val strikes: Int = rules.initialStrikes,
    val outs: Int = 0,
    val homeBatterIndex: Int = 0,
    val awayBatterIndex: Int = 0,
    val appearancePitches: Int = 0,
    val totalPitches: Int = 0,
    val extraFoulUsed: Boolean = false,
    val bases: List<String?> = listOf(null, null, null),
    val homeRuns: Int = 0,
    val awayRuns: Int = 0,
    val revision: Long = 0,
    val entries: List<PitchEntry> = emptyList()
) {
    val battingTeam get() = if (isTop) away else home
    val fieldingTeam get() = if (isTop) home else away
    val batterIndex get() = if (isTop) awayBatterIndex else homeBatterIndex
    val batter get() = battingTeam.players[batterIndex % battingTeam.players.size]
    val pitcher get() = fieldingTeam.players.first()
    init {
        require(home.players.isNotEmpty() && away.players.isNotEmpty())
        require(home.id != away.id && home.players.map { it.id }.intersect(away.players.map { it.id }.toSet()).isEmpty())
        require(bases.size == 3 && bases.filterNotNull().distinct().size == bases.filterNotNull().size)
        require(balls in 0..3 && strikes in 0..2 && outs in 0..2 && inning >= 1)
    }
}

/** AN02 implements pitches only. Full plays, corrections and timing arrive in AN03. */
object PitchReducer {
    fun apply(game: Game, action: PitchAction, operationID: String): Game {
        require(operationID.isNotBlank())
        require(game.entries.none { it.id == operationID }) { "重复操作" }
        var balls = game.balls
        var strikes = game.strikes
        var foulGrace = game.extraFoulUsed
        var out = false
        var walk = false
        when (action) {
            PitchAction.ball -> { if (game.rules.mode != GameMode.coachPitch) balls++; walk = balls >= 4 }
            PitchAction.calledStrike, PitchAction.swingingStrike -> { strikes++; out = strikes >= 3 }
            PitchAction.foul -> {
                if (strikes < 2) strikes++
                else if (game.rules.mode == GameMode.slowPitch) {
                    out = !game.rules.oneExtraFoul || foulGrace
                    if (!out) foulGrace = true
                }
            }
        }
        val appearancePitches = game.appearancePitches + 1
        if (game.rules.mode == GameMode.coachPitch && appearancePitches >= game.rules.coachPitchLimit) out = true
        var bases = game.bases.toMutableList()
        var homeRuns = game.homeRuns
        var awayRuns = game.awayRuns
        if (walk) {
            if (bases[0] != null) {
                if (bases[1] != null) {
                    if (bases[2] != null) { if (game.isTop) awayRuns++ else homeRuns++ }
                    bases[2] = bases[1]
                }
                bases[1] = bases[0]
            }
            bases[0] = game.batter.id
        }
        var outs = game.outs + if (out) 1 else 0
        var top = game.isTop
        var inning = game.inning
        var awayIndex = game.awayBatterIndex
        var homeIndex = game.homeBatterIndex
        val nextAppearance = out || walk
        if (nextAppearance) {
            if (game.isTop) awayIndex = (awayIndex + 1) % game.away.players.size
            else homeIndex = (homeIndex + 1) % game.home.players.size
            balls = game.rules.initialBalls; strikes = game.rules.initialStrikes; foulGrace = false
        }
        if (outs == 3) {
            outs = 0; top = !top
            if (top) inning++
            bases = mutableListOf(null, null, null)
        }
        val result = when { walk -> "四坏球保送"; out -> if (game.rules.mode == GameMode.coachPitch && strikes < 3) "达到教练投球上限" else "三振出局"; else -> action.title }
        return game.copy(inning = inning, isTop = top, balls = balls, strikes = strikes, outs = outs,
            homeBatterIndex = homeIndex, awayBatterIndex = awayIndex, appearancePitches = if (nextAppearance) 0 else appearancePitches,
            totalPitches = game.totalPitches + 1, extraFoulUsed = foulGrace, bases = bases,
            homeRuns = homeRuns, awayRuns = awayRuns, revision = game.revision + 1,
            entries = game.entries + PitchEntry(operationID, action, "${game.inning}局${if (game.isTop) "上" else "下"} · ${game.batter.name} · $result"))
    }
}
