package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** App-facing suspend API. Local Room data is read only; bindings and shared
 * rosters stay in the independent encrypted directory, never imported over it.
 */
internal class AndroidTeamSharing(private val local: GameRepository, private val session: TeamDirectorySession) {
    suspend fun state() = withContext(Dispatchers.IO) { session.snapshot() }
    suspend fun refresh() = withContext(Dispatchers.IO) { session.refresh() }
    suspend fun roster(teamID: String, force: Boolean = false) = withContext(Dispatchers.IO) { session.roster(teamID,force) }
    suspend fun members(teamID: String) = withContext(Dispatchers.IO) { session.members(teamID) }
    suspend fun invitations(teamID: String) = withContext(Dispatchers.IO) { session.invitations(teamID) }
    suspend fun preparePublish(localTeamID: String, nickname: String): String = withContext(Dispatchers.IO) {
        val row = local.teams.first().singleOrNull { it.id.equals(localTeamID,true) } ?: error("本机球队不存在，未发送任何资料")
        val team = GameJson.decodeFromString<Team>(row.payload)
        session.prepareCreate(team,nickname)
    }
    suspend fun prepareProfile(teamID: String, revision: Long, name: String, shortName: String, city: String) = withContext(Dispatchers.IO) { session.prepareProfile(teamID,revision,name,shortName,city) }
    suspend fun preparePlayer(teamID: String, revision: Long, player: SharedRosterPlayer) = withContext(Dispatchers.IO) { session.preparePlayer(teamID,revision,player) }
    suspend fun prepareMember(teamID: String, member: SharedTeamMember, role: TeamRole, remove: Boolean = false) = withContext(Dispatchers.IO) { session.prepareMember(teamID,member,role,remove) }
    suspend fun prepareInvite(teamID: String, role: TeamRole) = withContext(Dispatchers.IO) { session.prepareInvite(teamID,role) }
    suspend fun prepareRevoke(teamID: String, invitationID: String) = withContext(Dispatchers.IO) { session.prepareRevoke(teamID,invitationID) }
    suspend fun sendPending() = withContext(Dispatchers.IO) { session.sendPending() }
    suspend fun keepRejectedAsDraft(operationID: String) = withContext(Dispatchers.IO) { session.keepRejectedAsDraft(operationID) }
}
