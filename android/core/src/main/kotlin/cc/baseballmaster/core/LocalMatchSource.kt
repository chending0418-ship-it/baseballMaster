package cc.baseballmaster.core

import kotlinx.serialization.Serializable

/** null Game choice means undecided; a choice with no team means explicitly local.
 * A team is only a post-game suggestion, never permission or an upload command.
 */
@Serializable data class PostgameSharingChoice(val teamID: String? = null, val teamName: String? = null) {
    init {
        if(teamID==null) require(teamName==null) else {appleUUID(teamID);require(!teamName.isNullOrBlank())}
    }
}

/** Roster provenance only: this does not grant authority or mean a match was uploaded. */
@Serializable data class SharedRosterOrigin(
    val teamID: String,
    val memberID: String,
    val rosterRevision: Long,
    val rosterIDsBySourceID: Map<String, String>,
) {
    fun validate(game: Game) {
        appleUUID(teamID); appleUUID(memberID)
        require(rosterRevision > 0 && game.ourTeamID == teamID && !game.practice && game.appleSourceID == null)
        val own = listOf(game.home, game.away).single { it.id == teamID }
        require(own.source == "sharedSnapshot" && !own.isOpponent)
        require(own.players.map { it.id }.containsAll(rosterIDsBySourceID.keys))
        require(rosterIDsBySourceID.values.distinct().size == rosterIDsBySourceID.size)
        rosterIDsBySourceID.forEach { (source, roster) -> appleUUID(source); appleUUID(roster) }
    }
    companion object {
        fun from(profile: SharedTeamProfile, membership: RemoteTeam): SharedRosterOrigin {
            require(profile.id == membership.id && profile.revision == membership.revision)
            return SharedRosterOrigin(appleUUID(profile.id), appleUUID(membership.memberID), profile.revision,
                profile.players.filter { it.archived != true }.associate { appleUUID(it.sourceID) to appleUUID(it.id) })
        }
    }
}

object LocalMatchSource {
    fun sharedSnapshot(profile: SharedTeamProfile): Team = profile.gameTeam().copy(source = "sharedSnapshot")

    /** Local results only. Stable identities merge roster snapshots; never import them into roster management. */
    fun statisticsTeams(rosters: List<Team>, games: List<Game>): List<Team> {
        val locals=rosters.filter { !it.isOpponent && it.source=="local" }
        val localIDs=locals.map {it.id}.toSet()
        val snapshots=games.filter { !it.practice && it.sharedRosterOrigin!=null }
            .sortedWith(compareByDescending<Game> {it.sharedRosterOrigin?.rosterRevision ?: 0}
                .thenByDescending {maxOf(it.updatedAt,it.createdAt)}.thenBy {it.id})
            .mapNotNull {g -> listOf(g.home,g.away).singleOrNull {it.id==g.ourTeamID && it.source=="sharedSnapshot"} }
            .filterNot {it.id in localIDs}.groupBy {it.id}.values.map { versions ->
                val players=linkedMapOf<String,Player>()
                versions.forEach { team -> team.players.forEach { player ->
                    players.putIfAbsent(player.id,player.copy(jerseyNumbers=player.jerseyNumbers.toList()))
                }}
                versions.first().copy(players=players.values.toList())
            }
        return locals+snapshots
    }
}
