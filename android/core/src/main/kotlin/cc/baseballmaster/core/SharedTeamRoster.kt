package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable

internal fun teamText(value: String, nonempty: Boolean = false) {
    require(value.length <= 80 && value.none { it.code < 32 || it.code == 127 } && (!nonempty || value.isNotBlank())) { "请核对球队或球员资料" }
}
private fun teamNumbers(values: List<String>) {
    require(values.size <= 10 && values.distinct().size == values.size && values.all { Regex("00|0|[1-9][0-9]?").matches(it) }) { "背号须为 0–99 或 00，不能重复" }
}

@Serializable data class SharedRosterPlayer(
    val id: String, val sourceID: String, val chineseName: String, val englishName: String,
    val numbers: List<String>, val archived: Boolean? = null,
) {
    init { appleUUID(id); appleUUID(sourceID); teamText(chineseName); teamText(englishName); teamNumbers(numbers) }
    companion object {
        fun new(chineseName: String, englishName: String, numbers: List<String>) = SharedRosterPlayer(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), chineseName, englishName, numbers, false)
    }
}
@Serializable data class SharedTeamProfile(
    val id: String, val name: String, val revision: Long, val shortName: String, val city: String,
    val players: List<SharedRosterPlayer>,
) {
    init {
        appleUUID(id); teamText(name, true); teamText(shortName, true); teamText(city)
        require(revision > 0 && players.size <= 2000 && players.count { it.archived != true } <= 200)
        require(players.map { appleUUID(it.id) }.distinct().size == players.size && players.map { appleUUID(it.sourceID) }.distinct().size == players.size)
    }
    /** A detached match snapshot, never an update of a local roster. Blank names/
     * numbers accepted by the current service remain readable, but are not invented.
     */
    fun gameTeam(): Team = Team(appleUUID(id), name, players.filter { it.archived != true }.map { player ->
        require(player.numbers.isNotEmpty() && (player.chineseName.isNotBlank() || player.englishName.isNotBlank())) { "共享名册存在空姓名或空背号，暂不能用于安卓新比赛；原名册已保留" }
        Player(appleUUID(player.sourceID), player.chineseName.ifBlank { player.englishName }, player.numbers.first(),
            englishName = player.englishName, chineseName = player.chineseName, jerseyNumbers = player.numbers.toList()).also(Roster::validate)
    }, city = city, shortName = shortName, source = "shared")
}
@Serializable data class SharedTeamDetail(val team: SharedTeamProfile, val role: TeamRole, val memberID: String) {
    init { appleUUID(memberID) }
}
@Serializable data class SharedTeamMember(val id: String, val nickname: String, val role: TeamRole, val revision: Long) {
    init { appleUUID(id); teamText(nickname, true); require(revision > 0) }
}
@Serializable data class TeamRosterSeed(val shortName: String, val city: String, val players: List<Entry>) {
    @Serializable data class Entry(val sourceID: String, val chineseName: String, val englishName: String, val numbers: List<String>) {
        init { appleUUID(sourceID); teamText(chineseName); teamText(englishName); teamNumbers(numbers) }
    }
    init { teamText(shortName,true); teamText(city); require(players.size <= 200 && players.map { appleUUID(it.sourceID) }.distinct().size == players.size) }
    companion object {
        fun fromLocal(team: Team): TeamRosterSeed {
            appleUUID(team.id); require(team.source == "local" && !team.archived && !team.isOpponent) { "只能明确发布有效本机球队" }
            return TeamRosterSeed(team.shortName, team.city, team.players.filter { !it.archived }.map { player ->
                Roster.validate(player)
                Entry(appleUUID(player.id), player.chineseName ?: player.name, player.englishName, player.jerseyNumbers.toList())
            })
        }
    }
}
@Serializable data class SharedTeamBinding(val localTeamID: String, val cloudTeamID: String) {
    init { appleUUID(localTeamID); appleUUID(cloudTeamID); require(!localTeamID.equals(cloudTeamID, true)) }
}
@Serializable data class CachedTeamRoster(val profile: SharedTeamProfile, val memberID: String, val accessEpoch: Long?, val recoveryEpoch: String)
