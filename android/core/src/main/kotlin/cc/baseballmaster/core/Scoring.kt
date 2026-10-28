package cc.baseballmaster.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val GameJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

@Serializable
enum class GameMode(val title: String) {
    standard("普通比赛"),
    coachPitch("教练投手"),
    slowPitch("成人慢垒"),
}

@Serializable
enum class PitchAction(val title: String) {
    ball("坏球"),
    calledStrike("看振"),
    swingingStrike("挥空"),
    foul("界外"),
}

@Serializable
data class Player(
    val id: String,
    var name: String,
    var number: String,
    var englishName: String = "",
    var position: Int = 0,
    var jerseyNumbers: List<String> = listOf(number),
    var chineseName: String? = null,
    var archived: Boolean = false,
)

@Serializable
data class Team(
    val id: String,
    var name: String,
    var players: List<Player>,
    val isOpponent: Boolean = false,
    var city: String = "",
    var shortName: String = name,
    var archived: Boolean = false,
    var source: String = "local",
)

@Serializable
data class Rules(
    val mode: GameMode = GameMode.standard,
    val initialBalls: Int = if (mode == GameMode.slowPitch) 1 else 0,
    val initialStrikes: Int = if (mode == GameMode.slowPitch) 1 else 0,
    val coachPitchLimit: Int = 6,
    val oneExtraFoul: Boolean = true,
    val innings: Int = 6,
    val timed: Boolean = mode == GameMode.slowPitch,
    val timeLimitMinutes: Int? = if (mode == GameMode.slowPitch) 90 else null,
    val inningsLimit: Int? = null,
    val halfRunLimit: Int? = null,
    val pitchLimit: Int? = null,
    val pitcherInningsLimit: Int? = null,
    val recordingMethod: String = "traditional",
    val designatedHitter: Boolean = false,
    val twoWayPlayer: Boolean = false,
    val timeWarningMinutes: Int? = null,
    val pitchWarningRemaining: Int? = null,
) {
    init {
        require(
            initialBalls in 0..3 &&
                initialStrikes in 0..2 &&
                coachPitchLimit in 1..20 &&
                innings in 1..99
        )
        require(timeLimitMinutes == null || timeLimitMinutes in 1..600)
        require(halfRunLimit == null || halfRunLimit in 1..99)
        require(recordingMethod in listOf("traditional", "situation"))
    }

    val dhEnabled
        get() = mode == GameMode.standard && designatedHitter

    val twoWayEnabled
        get() = dhEnabled && twoWayPlayer

    val fielders
        get() = if (mode == GameMode.slowPitch) 10 else 9

    val regulationInnings
        get() = if (timed) inningsLimit else innings
}

@Serializable
data class BattingLine(
    var plateAppearances: Int = 0,
    var atBats: Int = 0,
    var runs: Int = 0,
    var hits: Int = 0,
    var doubles: Int = 0,
    var triples: Int = 0,
    var homeRuns: Int = 0,
    var runsBattedIn: Int = 0,
    var walks: Int = 0,
    var hitByPitch: Int = 0,
    var strikeouts: Int = 0,
    var stolenBases: Int = 0,
    var caughtStealing: Int = 0,
    var sacrifices: Int = 0,
) {
    val average
        get() = if (atBats == 0) 0.0 else hits.toDouble() / atBats

    val totalBases
        get() = hits + doubles + triples * 2 + homeRuns * 3

    val onBasePercentage
        get() =
            if (atBats + walks + hitByPitch + sacrifices == 0) 0.0
            else (hits + walks + hitByPitch).toDouble() / (atBats + walks + hitByPitch + sacrifices)

    val slugging
        get() = if (atBats == 0) 0.0 else totalBases.toDouble() / atBats
}

@Serializable
data class PitchingLine(
    var outsRecorded: Int = 0,
    var battersFaced: Int = 0,
    var hits: Int = 0,
    var runs: Int = 0,
    var earnedRuns: Int = 0,
    var walks: Int = 0,
    var hitByPitch: Int = 0,
    var strikeouts: Int = 0,
    var wildPitches: Int = 0,
    var pitches: Int = 0,
    var strikes: Int = 0,
)

@Serializable
data class FieldingLine(
    var putouts: Int = 0,
    var assists: Int = 0,
    var errors: Int = 0,
    var doublePlays: Int = 0,
)

@Serializable
data class Decision(
    val playerID: String,
    val origin: Int,
    val destination: Int,
    val cause: String = "hit",
    val route: List<Int> = emptyList(),
    val errorPosition: Int? = null,
    val fielding: Fielding? = null,
) // origin 0=batter, 1..3=base; destination -1=out, 0=hold, 1..3=base, 4=score

@Serializable
data class Fielding(
    val route: List<Int> = emptyList(),
    val assists: List<Int> = emptyList(),
    val putouts: List<Int> = emptyList(),
    val errorPosition: Int? = null,
    val status: String = "recorded",
) {
    fun validate(rules: Rules) {
        require(
            status in listOf("recorded", "pending", "noFielding") &&
                route.size <= 50 &&
                (route + assists + putouts).all { it in 1..rules.fielders } &&
                (errorPosition == null || errorPosition in 1..rules.fielders) &&
                (status != "recorded" || route.isNotEmpty()) &&
                (status != "noFielding" || route.isEmpty() && errorPosition == null)
        ) {
            "请核对守备路线"
        }
    }
}

@Serializable
data class Participant(
    val position: Int,
    val playerID: String,
    val name: String,
    val number: String,
)

@Serializable
data class Observation(
    val type: String = "unknown",
    val precision: String = "unknown",
    val region: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val fielding: Fielding? = null,
) {
    fun validate(rules: Rules) {
        require(type in listOf("ground", "hardGround", "line", "fly", "popup", "bunt", "unknown")) {
            "击球类型无效"
        }
        require(!(rules.mode == GameMode.slowPitch && type == "bunt")) { "成人慢垒不能记录触击" }
        require(
            when (precision) {
                "unknown" -> region == null && x == null && y == null
                "region" ->
                    region in
                        listOf(
                            "infieldLeft",
                            "infieldMiddle",
                            "infieldRight",
                            "left",
                            "center",
                            "right",
                            "foulLeft",
                            "foulRight",
                        ) && x == null && y == null
                "point" ->
                    region == null &&
                        x != null &&
                        y != null &&
                        x.isFinite() &&
                        y.isFinite() &&
                        x in 0.0..1.0 &&
                        y in 0.0..1.0
                else -> false
            }
        ) {
            "请核对击球位置"
        }
        fielding?.validate(rules)
    }
}

@Serializable
enum class Outcome(val title: String, val notation: String) {
    single("一垒安打", "1B"),
    double("二垒安打", "2B"),
    triple("三垒安打", "3B"),
    homeRun("本垒打", "HR"),
    groundOut("滚地出局", "GO"),
    flyOut("飞球出局", "FO"),
    lineOut("平飞接杀", "LO"),
    foulFlyOut("界外飞球接杀", "FF"),
    infieldFly("内野高飞必死", "IF"),
    error("对方失误", "E"),
    fieldersChoice("野手选择", "FC"),
    runnerTagOut("触杀其他跑者", "TAG"),
    sacrificeBunt("牺牲触击", "SH"),
    sacrificeFly("牺牲高飞", "SF"),
    doublePlay("双杀", "DP"),
    triplePlay("三杀", "TP"),
    pendingOut("待确认出局", "待确认"),
    pending("待确认上垒", "待确认"),
    other("其他", "待补");

    val isHit
        get() = this in listOf(single, double, triple, homeRun)

    val outCount
        get() =
            when (this) {
                doublePlay -> 2
                triplePlay -> 3
                groundOut,
                flyOut,
                lineOut,
                foulFlyOut,
                infieldFly,
                sacrificeBunt,
                sacrificeFly,
                pendingOut -> 1
                else -> 0
            }
}

@Serializable
data class Command(
    val kind: String,
    val pitch: PitchAction? = null,
    val outcome: Outcome? = null,
    val decisions: List<Decision>? = null,
    val fielding: Fielding? = null,
    val observation: Observation? = null,
    val timingRunCounts: Boolean? = null,
    val runnerKind: String? = null,
    val playerID: String? = null,
    val replacementID: String? = null,
    val position: Int? = null,
    val homeSide: Boolean? = null,
    val order: List<String>? = null,
    val positions: Map<Int, String>? = null,
    val text: String = "",
    val flag: Boolean = false,
    val wasThrown: Boolean = true,
    val penaltyApplied: Boolean = true,
    val at: Long = 0,
    val situation: Situation? = null,
    val earnedRuns: Int? = null,
    val runsBattedIn: Int? = null,
    val previousDisposition: String = "notApplicable",
    val battingCredit: Outcome? = null,
    val adjudication: Ruling? = null,
    val rules: Rules? = null,
    val dateMillis: Long? = null,
    val timeZoneID: String = "Asia/Shanghai",
    val recordingMethod: String? = null,
    val timeUnknown: Boolean = false,
    val designatedHitterID: String? = null,
    val secondPlayerID: String? = null,
    val secondReplacementID: String? = null,
    val secondPosition: Int? = null,
    val runnerBases: List<Int> = listOf(2),
    val eventID: String? = null,
    val reviewNote: String? = null,
    /** Historical names captured with a cross-platform fielding observation. */
    val participantSnapshots: List<Participant>? = null,
)

@Serializable
data class RecordedCommand(
    val id: String,
    val command: Command,
    val batterID: String,
    val bases: List<String?>,
    val acceptContext: Boolean = false,
    val context: HistoryContext? = null,
    val inserted: Boolean = false,
    val edited: Boolean = false,
    val additionalErrors: List<ErrorCredit> = emptyList(),
    val statisticsRuling: StatisticsRuling? = null,
)

@Serializable
data class Situation(
    val inning: Int,
    val isTop: Boolean,
    val balls: Int,
    val strikes: Int,
    val outs: Int,
    val awayScore: Int,
    val homeScore: Int,
    val bases: List<String?>,
    val batterIndex: Int,
    val pitcherID: String? = null,
)

@Serializable
data class PitchEntry(
    val id: String,
    val action: PitchAction? = null,
    val text: String,
    val kind: String = "pitch",
    val notation: String = "",
    val batterID: String? = null,
    val pitcherID: String? = null,
    val outcome: Outcome? = null,
    val decisions: List<Decision> = emptyList(),
    val observation: Observation? = null,
    val participants: List<Participant> = emptyList(),
    val inning: Int = 1,
    val isTop: Boolean = true,
    val at: Long = 0,
    val before: Situation? = null,
    val after: Situation? = null,
    val incomplete: Boolean = false,
    val reviewNote: String? = null,
    val reviewedAt: Long? = null,
    val runnerOwnersBefore: Map<String, String> = emptyMap(),
    val unearnedBefore: Set<String> = emptySet(),
    val automaticBefore: Set<String> = emptySet(),
)

@Serializable
data class Game(
    val id: String,
    var home: Team,
    var away: Team,
    var rules: Rules,
    var inning: Int = 1,
    var isTop: Boolean = true,
    var balls: Int = rules.initialBalls,
    var strikes: Int = rules.initialStrikes,
    var outs: Int = 0,
    var homeBatterIndex: Int = 0,
    var awayBatterIndex: Int = 0,
    var appearancePitches: Int = 0,
    var totalPitches: Int = 0,
    var extraFoulUsed: Boolean = false,
    var bases: List<String?> = listOf(null, null, null),
    var homeRuns: Int = 0,
    var awayRuns: Int = 0,
    var revision: Long = 0,
    var entries: List<PitchEntry> = emptyList(),
    var homeOrder: List<String>? = null,
    var awayOrder: List<String>? = null,
    var homePositions: Map<Int, String>? = null,
    var awayPositions: Map<Int, String>? = null,
    var homeDesignatedHitterID: String? = null,
    var awayDesignatedHitterID: String? = null,
    var homePitcherID: String? = null,
    var awayPitcherID: String? = null,
    var exitedPlayers: Set<String> = emptySet(),
    var batting: MutableMap<String, BattingLine> = mutableMapOf(),
    var pitching: MutableMap<String, PitchingLine> = mutableMapOf(),
    var fielding: MutableMap<String, FieldingLine> = mutableMapOf(),
    var homeRunsByInning: List<Int> = listOf(homeRuns),
    var awayRunsByInning: List<Int> = listOf(awayRuns),
    var homeHits: Int = 0,
    var awayHits: Int = 0,
    var homeErrors: Int = 0,
    var awayErrors: Int = 0,
    var runnerPitcherIDs: MutableMap<String, String> = mutableMapOf(),
    var unearnedRunnerIDs: Set<String> = emptySet(),
    var automaticRunnerIDs: Set<String> = emptySet(),
    var walkResponsiblePitcherID: String? = null,
    var strikeoutResponsibleBatterID: String? = null,
    var isFinal: Boolean = false,
    var endReason: String? = null,
    var endedAt: Long? = null,
    var scheduledAt: Long? = null,
    var playedAt: Long? = null,
    var playedTimeZoneID: String = "Asia/Shanghai",
    var dateRevisions: List<String> = emptyList(),
    var pendingDecision: String? = null,
    var halfEndedAwaitingDecision: Boolean = false,
    var dismissedRunLimitHalf: String? = null,
    var dismissedWalkOffHalf: String? = null,
    var clockElapsedMs: Long = 0,
    var clockRunningSince: Long? = null,
    var clockHasStarted: Boolean = false,
    var openingPitchRecorded: Boolean = false,
    var practice: Boolean = false,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    var appleSourceID: String? = null,
    var isScheduled: Boolean = false,
    var startedAt: Long? = null,
    var ourTeamID: String? = null,
    var journal: List<RecordedCommand> = emptyList(),
    var cursor: Int = 0,
    var checkpoint: String? = null,
    var incompletePrefix: Boolean = false,
    var correctionRevisions: List<String> = emptyList(),
    var historyRevisions: List<HistoryRevision> = emptyList(),
    var correctionBoundary: Int = 0,
    var statisticsIncomplete: Boolean = false,
    var pitcherInnings: MutableMap<String, Set<Int>> = mutableMapOf(),
    var extraInningConfirmed: Boolean = false,
    var tiebreakStartInning: Int? = null,
    var tiebreakRunnerBases: List<Int> = listOf(2),
    var tiebreakPlacementKeys: Set<String> = emptySet(),
    var clockDisplayMode: String = if (rules.timed) "remaining" else "elapsed",
    var historySourceID: String? = null,
    var updatedAt: Long = 0,
    var createdAt: Long = 0,
) {
    val battingTeam
        get() = if (isTop) away else home

    val fieldingTeam
        get() = if (isTop) home else away

    val battingOrder
        get() = (if (isTop) awayOrder else homeOrder) ?: battingTeam.players.map { it.id }

    val batterIndex
        get() = if (isTop) awayBatterIndex else homeBatterIndex

    val batter
        get() = battingTeam.players.first { it.id == battingOrder[batterIndex % battingOrder.size] }

    val fieldingPositions
        get() =
            (if (isTop) homePositions else awayPositions)
                ?: fieldingTeam.players
                    .take(rules.fielders)
                    .mapIndexed { i, p -> i + 1 to p.id }
                    .toMap()

    val pitcher
        get() =
            fieldingTeam.players.first {
                it.id ==
                    ((if (isTop) homePitcherID else awayPitcherID)
                        ?: fieldingPositions[1]
                        ?: fieldingTeam.players.first().id)
            }

    val canUndo
        get() =
            (correctionBoundary until cursor).any {
                journal[it].command.kind !in setOf("clock", "date", "method")
            }

    val isPregame
        get() = !isFinal && !clockHasStarted && startedAt == null && canSwapOpeningSides

    fun canConfirmExtraInning(at: Long) =
        !isFinal &&
            (rules.regulationInnings?.let { inning > it }
                ?: (rules.timed && (remainingTime(at) ?: 1) <= 0)) &&
            homeRuns == awayRuns &&
            !extraInningConfirmed

    fun canEnableTiebreak(at: Long) =
        !isFinal &&
            (rules.regulationInnings?.let { inning > it }
                ?: (rules.timed && (remainingTime(at) ?: 1) <= 0)) &&
            tiebreakStartInning == null

    val requiresTiebreakPlacement
        get() =
            !isFinal &&
                tiebreakStartInning?.let { inning >= it } == true &&
                tiebreakRunnerBases.any { "$halfKey-base$it" !in tiebreakPlacementKeys }

    val eligibleTiebreakRunners
        get() =
            battingTeam.players.filter {
                it.id in battingOrder && it.id != batter.id && it.id !in bases
            }

    fun remainingTime(at: Long) = rules.timeLimitMinutes?.let { it * 60000L - elapsed(at) }

    val canSwapOpeningSides
        get() =
            !openingPitchRecorded &&
                !isFinal &&
                pendingDecision == null &&
                !halfEndedAwaitingDecision &&
                inning == 1 &&
                isTop &&
                balls == rules.initialBalls &&
                strikes == rules.initialStrikes &&
                outs == 0 &&
                homeRuns == 0 &&
                awayRuns == 0 &&
                bases.all { it == null } &&
                pitching.values.all { it.pitches == 0 && it.battersFaced == 0 } &&
                batting.values.all { it.plateAppearances == 0 }

    val halfKey
        get() = "$inning-${if(isTop)"top" else "bottom"}"

    val remainingRuns
        get() =
            rules.halfRunLimit?.let {
                (it -
                        (if (isTop) awayRunsByInning else homeRunsByInning).getOrElse(inning - 1) {
                            0
                        })
                    .coerceAtLeast(0)
            } ?: Int.MAX_VALUE

    fun situation() =
        Situation(
            inning,
            isTop,
            balls,
            strikes,
            outs,
            awayRuns,
            homeRuns,
            bases,
            batterIndex,
            pitcher.id,
        )

    fun elapsed(at: Long) =
        clockElapsedMs + (clockRunningSince?.let { (at - it).coerceAtLeast(0) } ?: 0)

    fun validate() {
        require(appleSourceID == null || Regex("[a-f0-9]{64}").matches(appleSourceID!!)) { "iOS 来源编号无效" }
        require(home.players.isNotEmpty() && away.players.isNotEmpty() && home.id != away.id)
        require(
            home.players.map { it.id }.distinct().size == home.players.size &&
                away.players.map { it.id }.distinct().size == away.players.size
        )
        require(home.players.map { it.id }.intersect(away.players.map { it.id }.toSet()).isEmpty())
        require(
            bases.size == 3 &&
                bases.filterNotNull().distinct().size == bases.filterNotNull().size &&
                bases.filterNotNull().all { id -> battingTeam.players.any { it.id == id } }
        )
        require(balls in 0..3 && strikes in 0..2 && outs in 0..3 && inning >= 1)
        require(homeRunsByInning.sum() == homeRuns && awayRunsByInning.sum() == awayRuns)
        for ((team, positions) in listOf(home to homePositions, away to awayPositions)) if (
            positions != null
        )
            require(
                positions.keys == (1..rules.fielders).toSet() &&
                    positions.values.distinct().size == rules.fielders &&
                    positions.values.all { id -> team.players.any { it.id == id } }
            )
        require(
            homeRuns >= 0 &&
                awayRuns >= 0 &&
                homeRunsByInning.all { it >= 0 } &&
                awayRunsByInning.all { it >= 0 }
        )
        for ((team, order) in
            listOf(
                home to (homeOrder ?: home.players.map { it.id }),
                away to (awayOrder ?: away.players.map { it.id }),
            )) require(
            order.isNotEmpty() &&
                order.distinct() == order &&
                order.all { id -> team.players.any { it.id == id } }
        )
    }
}

object PitchReducer {
    fun apply(game: Game, action: PitchAction, operationID: String) =
        MatchEngine.apply(game, Command("pitch", pitch = action), operationID)
}

@Serializable
data class Ruling(
    val previousPlay: String = "notApplicable",
    val plateAppearance: String = "continueAtBat",
    val countsAsAtBat: Boolean = false,
    val isFinal: Boolean = true,
    val battingCredit: String = "none",
    val runsBattedIn: Int = 0,
    val earnedRuns: Int = 0,
    val errorPlayerID: String? = null,
    val ballStatus: String = "dead",
)
