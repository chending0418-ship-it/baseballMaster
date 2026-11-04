package cc.baseballmaster.core

/** Local official projections. Shared sources will be supplied only after AN05 authorisation. */
data class Contact(
    val id: String,
    val observation: Observation,
    val outcome: Outcome?,
    val detail: String,
)

data class Performance(
    val line: BattingLine = BattingLine(),
    val contacts: List<Contact> = emptyList(),
    val missingLocations: Int = 0,
    val gameCount: Int = 0,
    val incompleteGames: Int = 0,
)

object Performances {
    fun year(g: Game): Int? = MatchDates.year(g)

    fun calculate(current: Game, all: List<Game>, playerID: String, annual: Boolean): Performance {
        val team =
            listOf(current.home, current.away).firstOrNull { t ->
                t.players.any { it.id == playerID }
            } ?: return Performance()
        val sources =
            if (!annual || current.practice || year(current) == null) listOf(current)
            else {
                val candidates =
                    (all.filter { it.id != current.id } + current).filter {
                        !it.practice &&
                            !it.isScheduled &&
                            it.rules.mode == current.rules.mode &&
                            year(it) == year(current)
                    }
                candidates
                    .groupBy { it.historySourceID ?: it.id }
                    .values
                    .map { group ->
                        group.maxWith(
                            compareBy<Game> { it.updatedAt }.thenBy { it.revision }.thenBy { it.id }
                        )
                    }
            }
        val line = BattingLine()
        val contacts = mutableListOf<Contact>()
        var missing = 0
        var count = 0
        var incomplete = 0
        for (g in sources) {
            if (
                listOf(g.home, g.away).none {
                    it.id == team.id && it.players.any { p -> p.id == playerID }
                }
            )
                continue
            count++
            if (g.statisticsIncomplete || g.incompletePrefix) incomplete++
            g.batting[playerID]?.let { b ->
                line.plateAppearances += b.plateAppearances
                line.atBats += b.atBats
                line.runs += b.runs
                line.hits += b.hits
                line.doubles += b.doubles
                line.triples += b.triples
                line.homeRuns += b.homeRuns
                line.runsBattedIn += b.runsBattedIn
                line.walks += b.walks
                line.hitByPitch += b.hitByPitch
                line.strikeouts += b.strikeouts
                line.stolenBases += b.stolenBases
                line.caughtStealing += b.caughtStealing
                line.sacrifices += b.sacrifices
            }
            g.entries
                .filter {
                    (it.kind == "play" || it.kind == "illegal" && it.outcome != null) &&
                        it.batterID == playerID
                }
                .forEach { e ->
                    val o = e.observation
                    if (o != null && o.precision != "unknown") {
                        contacts +=
                            Contact(
                                "${g.id}/${e.id}",
                                o,
                                e.outcome,
                                "${e.inning}局${if(e.isTop)"上"else"下"} · ${ScoringOptions.ballTypes[o.type]} · ${e.outcome?.title.orEmpty()}",
                            )
                    } else missing++
                }
        }
        return Performance(line, contacts, missing, count, incomplete)
    }

    fun regionPoint(region: String?) =
        when (region) {
            "infieldLeft" -> .36 to .60
            "infieldMiddle" -> .50 to .51
            "infieldRight" -> .64 to .60
            "left" -> .23 to .31
            "center" -> .5 to .2
            "right" -> .77 to .31
            "foulLeft" -> .12 to .70
            "foulRight" -> .88 to .70
            else -> null
        }
}
