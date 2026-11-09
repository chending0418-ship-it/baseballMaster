package cc.baseballmaster.core

private val moundChanges = setOf("pitcher", "fielder", "position", "lineup", "doubleSwitch")

internal fun PitchEntry.moundAppearance(command: Command?, alreadyPlaying: Boolean): Situation? {
    if (command?.kind == "clock" && command.text == "start") return after
    if (alreadyPlaying && kind in moundChanges && before != null && after != null &&
        before.inning == after.inning && before.isTop == after.isTop && before.pitcherID != after.pitcherID
    ) return after
    val actuallyPitched = when (kind) {
        "pitch", "play", "hitByPitch", "foulBunt", "droppedThirdStrike" -> true
        "illegal" -> command?.wasThrown == true
        "runner" -> notation in setOf("wildPitch", "passedBall", "uncertainLooseBall")
        else -> false
    }
    return if (actuallyPitched) before else null
}

internal fun Game.includeMoundAppearance(situation: Situation?) {
    val pitcherID = situation?.pitcherID ?: return
    pitcherInnings[pitcherID] = pitcherInnings[pitcherID].orEmpty() + situation.inning
}

/** Old Android snapshots counted automatically inherited half innings. Rebuild
 * only what the saved events prove; do not derive mound usage from statistical IP. */
fun Game.restorePitcherInningUsage() {
    if (pitcherInningUsageCurrent) return
    val oldUsage = pitcherInnings.toMap()
    pitcherInnings.clear()
    pitcherInningUsageCurrent = true
    if (rules.mode == GameMode.coachPitch) return

    val commands = journal.take(cursor).associate { it.id to it.command }
    var live = false
    var previousHalf: Int? = null
    fun observe(s: Situation?) {
        if (s?.pitcherID == null) { pitcherInningHistoryIncomplete = true; return }
        val half = (s.inning - 1) * 2 + if (s.isTop) 0 else 1
        if (previousHalf?.let { half !in it..(it + 1) } ?: (half != 0)) pitcherInningHistoryIncomplete = true
        previousHalf = half
    }
    for (entry in entries) {
        val command = commands[entry.id]
        val appearance = entry.moundAppearance(command, live)
        val startsPlay = appearance != null || entry.kind in setOf(
            "pitch", "play", "hitByPitch", "foulBunt", "droppedThirdStrike", "intentionalWalk",
            "runner", "violation", "adjudication", "endHalf", "tiebreak",
        )
        if (entry.kind == "illegal" && command == null || entry.kind == "clock" && command == null && !live)
            pitcherInningHistoryIncomplete = true
        if (live || startsPlay) {
            observe(entry.before)
            observe(entry.after)
        }
        includeMoundAppearance(appearance)
        live = live || startsPlay
    }
    if (live || oldUsage.isNotEmpty() || totalPitches > 0 || !isTop || inning > 1) observe(situation())
    if (!live && (oldUsage.isNotEmpty() || totalPitches > 0 || clockHasStarted) || incompletePrefix)
        pitcherInningHistoryIncomplete = true
}
