package cc.baseballmaster.core

private val moundChanges = setOf("pitcher", "fielder", "position", "lineup", "doubleSwitch")
private val physicalPitchKinds = setOf("pitch", "play", "hitByPitch", "foulBunt", "droppedThirdStrike")
private val loosePitches = setOf("wildPitch", "passedBall", "uncertainLooseBall")
private val clockStartingActions = physicalPitchKinds + setOf("intentionalWalk", "runner", "violation", "adjudication")
private val gameActions = clockStartingActions + setOf("endHalf", "tiebreak")

internal fun PitchEntry.moundAppearance(command: Command?, alreadyPlaying: Boolean): Situation? {
    if (alreadyPlaying && kind in moundChanges && before != null && after != null &&
        before.inning == after.inning && before.isTop == after.isTop && before.pitcherID != after.pitcherID
    ) return after
    val actuallyPitched = when (kind) {
        in physicalPitchKinds -> true
        "illegal" -> command?.wasThrown == true
        "runner" -> notation in loosePitches
        else -> false
    }
    return if (actuallyPitched) before else null
}

internal fun Game.includeMoundAppearance(situation: Situation?) {
    val pitcherID = situation?.pitcherID ?: return
    pitcherInnings[pitcherID] = pitcherInnings[pitcherID].orEmpty() + situation.inning
}

/** Some older Android snapshots charged unplayed half innings. Rebuild from
 * confirmed changes and physical pitches recorded in the saved history. */
internal fun Game.restorePitcherInningUsage() {
    if (scheduledSetupPending || appleSourceID != null || pitcherInningUsageCurrent) return
    val hadUsage = pitcherInnings.isNotEmpty()
    pitcherInnings.clear()
    pitcherInningUsageCurrent = true
    if (rules.mode == GameMode.coachPitch) return

    val commands = journal.take(cursor).associate { it.id to it.command }
    var live = false
    var clockStarted = false
    var previousHalf: Int? = null
    fun observe(situation: Situation?) {
        if (situation?.pitcherID == null) {
            pitcherInningHistoryIncomplete = true
            return
        }
        val half = (situation.inning - 1) * 2 + if (situation.isTop) 0 else 1
        if (previousHalf?.let { half !in it..(it + 1) } ?: (half != 0)) {
            pitcherInningHistoryIncomplete = true
        }
        previousHalf = half
    }
    for (entry in entries) {
        val command = commands[entry.id]
        val appearance = entry.moundAppearance(command, live)
        val opensClock = !clockStarted && command != null &&
            (entry.kind == "clock" && command.text in setOf("start", "resume") ||
                !practice && !command.timeUnknown &&
                (entry.kind in clockStartingActions || entry.kind == "illegal" && command.wasThrown))
        val startsPlay = opensClock || appearance != null || entry.kind in gameActions
        if (command == null && (entry.kind == "illegal" || entry.kind == "clock" && !live)) {
            pitcherInningHistoryIncomplete = true
        }
        if (live || startsPlay) {
            observe(entry.before)
            observe(entry.after)
        }
        includeMoundAppearance(if (opensClock) entry.before else appearance)
        live = live || startsPlay
        clockStarted = clockStarted || opensClock
    }
    if (live || hadUsage || totalPitches > 0 || !isTop || inning > 1) observe(situation())
    if (!live && (hadUsage || totalPitches > 0 || clockHasStarted) || incompletePrefix) {
        pitcherInningHistoryIncomplete = true
    }
}
