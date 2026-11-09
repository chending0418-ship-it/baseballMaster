package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import kotlinx.serialization.json.*

private val baseNames = listOf("batter", "first", "second", "third", "home")

internal fun Game.voiceEligible() =
    !practice && !isScheduled && !isFinal && !isPregame && pendingDecision == null &&
        !requiresTiebreakPlacement && inning in 1..99 && outs in 0..2 && balls in 0..3 && strikes in 0..2

internal fun Game.voiceContext(): JsonObject = buildJsonObject {
    put("schemaVersion", 1); put("gameId", id); put("revision", revision); put("plateAppearanceId", "$id-$revision")
    put("rulesProfile", when (rules.mode) {
        GameMode.standard -> "OBR-2026"
        GameMode.coachPitch -> "BM-coachPitch"
        GameMode.slowPitch -> "BM-slowPitch"
    })
    put("inning", inning); put("half", if (isTop) "top" else "bottom")
    put("outs", outs); put("balls", balls); put("strikes", strikes)
    putJsonObject("scores") { put("away", awayRuns); put("home", homeRuns) }
    putJsonObject("batter") { put("id", batter.id); put("number", batter.number); put("name", batter.name) }
    put("pitcherId", if (rules.mode == GameMode.coachPitch) JsonNull else JsonPrimitive(pitcher.id))
    putJsonObject("fielders") { fieldingPositions.filterKeys { it <= 9 }.forEach { (pos, pid) -> put("$pos", pid) } }
    putJsonArray("runners") {
        bases.forEachIndexed { index, player ->
            if (player != null) addJsonObject {
                put("playerId", player); put("base", baseNames[index + 1]); put("responsiblePitcherId", runnerPitcherIDs[player])
                put("earnedStatus", "unknown")
            }
        }
    }
    put("historyComplete", false); put("virtualOuts", JsonNull); putJsonArray("pendingIssues") {}
}

private val missingLocation = setOf(
    "未提及落点", "未说明落点", "落点未知", "落点不明", "落点未说明", "落点未提及",
    "未提及击球落点", "未说明击球落点", "未描述落点", "落点未描述",
)
private val regions = mapOf(
    "内野左侧" to "infieldLeft", "内野中间" to "infieldMiddle", "内野右侧" to "infieldRight",
    "左外野" to "left", "中外野" to "center", "右外野" to "right",
    "左侧界外" to "foulLeft", "右侧界外" to "foulRight",
)

/** Treat the model output as a proposal. Unclear plays remain manual and never mutate a match. */
internal fun voiceProposal(analysis: VoiceAnalysis, game: Game, transcript: String): Command? {
    if (analysis.schemaVersion != 1) return null
    val events = analysis.events.sortedBy { it.sequence }
    if (events.isEmpty() || events.size > 100 || events.map { it.sequence }.toSet().size != events.size ||
        events.any { it.evidence.isBlank() || !transcript.contains(it.evidence) }
    ) return null
    if (events.size == 1 && events[0].kind == "pitch" && analysis.ball.outcome == "unknown" && analysis.observations.isEmpty()) {
        val action = when (events[0].pitchResult) {
            "ball" -> PitchAction.ball
            "called_strike" -> PitchAction.calledStrike
            "swinging_strike" -> PitchAction.swingingStrike
            "foul" -> PitchAction.foul
            else -> return null
        }
        return Command("pitch", pitch = action)
    }
    if (analysis.observations.any { it.replace("。", "").trim() !in missingLocation } || analysis.ball.intent == "sacrifice" ||
        events.any { it.kind !in setOf("field", "throw", "advance", "out", "score") && !(it.kind == "pitch" && it.pitchResult == "in_play") } ||
        events.count { it.kind == "pitch" } > 1
    ) return null
    val type = analysis.ball.type.takeIf { it in ScoringOptions.ballTypes } ?: "unknown"
    if (game.rules.mode == GameMode.slowPitch && type == "bunt") return null
    val out = events.firstOrNull { it.kind == "out" }
    val outcome = when (analysis.ball.outcome) {
        "single" -> Outcome.single
        "double" -> Outcome.double
        "triple" -> Outcome.triple
        "home_run" -> Outcome.homeRun
        "out" -> {
            if (out?.playerId != game.batter.id) return null
            when {
                type == "ground" && out.outType == "batter_before_first" -> Outcome.groundOut
                type == "line" && out.outType == "catch" -> Outcome.lineOut
                type in setOf("fly", "popup") && out.outType == "catch" -> Outcome.flyOut
                else -> return null
            }
        }
        else -> return null
    }
    val batterDestination = when (outcome) {
        Outcome.single -> 1
        Outcome.double -> 2
        Outcome.triple -> 3
        Outcome.homeRun -> 4
        else -> -1
    }
    val players = (listOf(game.batter.id) + game.bases).mapIndexedNotNull { i, pid -> pid?.let { i to it } }
    val movements = events.filter { it.kind in setOf("advance", "out", "score") }
    if (movements.any { it.cause != null && it.cause != "batted_ball" || players.none { p -> p.second == it.playerId } }) return null
    val decisions = players.map { (origin, player) ->
        val legs = movements.filter { it.playerId == player }
        if (legs.isEmpty()) return null
        var from = origin
        var destination = 0
        for (leg in legs) {
            if (from == 4 || destination == -1 || leg.fromBase != baseNames[from]) return null
            destination = when (leg.kind) {
                "advance" -> baseNames.indexOf(leg.toBase).takeIf { it in (from + 1)..3 } ?: return null
                "score" -> if (leg.toBase == "home" && (origin != 0 || outcome == Outcome.homeRun)) 4 else return null
                "out" -> if (origin == 0 && legs.size == 1 && batterDestination == -1 &&
                    leg.toBase == if (leg.outType == "catch") "batter" else "first") -1 else return null
                else -> return null
            }
            if (destination > 0) from = destination
        }
        if (origin == 0 && destination != batterDestination || outcome == Outcome.homeRun && destination != 4) return null
        Decision(player, origin, destination)
    }
    if (decisions.map { it.destination }.filter { it > 0 }.zipWithNext().any { (a, b) -> a > b || a == b && a != 4 } ||
        game.outs == 2 && batterDestination == -1 && decisions.any { it.destination == 4 }) return null

    val defense = out?.takeIf { batterDestination == -1 }?.let { voiceDefense(it, events, outcome, game) }
    val pro = game.rules.recordingMethod == "situation"
    if (!pro && batterDestination == -1 && defense == null) return null
    val region = if (analysis.observations.isEmpty()) regions.entries.firstOrNull { (word, _) ->
        word in analysis.ball.region && word in transcript.replace("野手", "手")
    }?.value else null
    val command = Command("play", outcome = outcome, decisions = decisions,
        fielding = if (!pro) defense else null, recordingMethod = game.rules.recordingMethod,
        observation = if (pro) Observation(type, if (region == null) "unknown" else "region", region, fielding = defense) else null)
    return command.takeIf { runCatching { MatchEngine.apply(game, it) }.isSuccess }
}

private fun voiceDefense(out: VoiceAnalysis.Event, events: List<VoiceAnalysis.Event>, outcome: Outcome, game: Game): Fielding? {
    fun active(position: Int?) = position?.takeIf { it in game.fieldingPositions.keys }
    val putout = active(out.toFielder) ?: return null
    val handling = events.filter { it.kind == "field" || it.kind == "throw" }
    if (outcome == Outcome.flyOut || outcome == Outcome.lineOut) {
        if (handling.any { it.kind != "field" || it.sequence >= out.sequence || active(it.toFielder) != putout }) return null
        return Fielding(route = listOf(putout), putouts = listOf(putout))
    }
    val first = handling.firstOrNull() ?: return null
    if (first.kind != "field" || first.sequence >= out.sequence || handling.size > 50) return null
    val route = mutableListOf(active(first.toFielder) ?: return null)
    for (leg in handling.drop(1)) {
        val target = active(leg.toFielder) ?: return null
        if (leg.kind != "throw" || leg.sequence >= out.sequence || active(leg.fromFielder) != route.last() || target == route.last()) return null
        route += target
    }
    return if (route.last() == putout) Fielding(route = route, assists = route.dropLast(1).distinct(), putouts = listOf(putout)) else null
}
