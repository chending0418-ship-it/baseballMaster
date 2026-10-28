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
    suspend fun prepareCreate(name: String, nickname: String) = withContext(Dispatchers.IO) { session.prepareCreate(name,nickname) }
    suspend fun previewInvitation(input: String) = withContext(Dispatchers.IO) { session.invitationPreview(input) }
    suspend fun prepareJoin(input: String, nickname: String) = withContext(Dispatchers.IO) { session.prepareJoin(TeamServiceLink.invitation(input),nickname) }
    suspend fun prepareLeave(teamID: String) = withContext(Dispatchers.IO) { session.prepareLeave(teamID) }
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
    suspend fun prepareFixedInvite(teamID: String, rotate: Boolean = false) = withContext(Dispatchers.IO) { session.prepareFixedInvite(teamID,rotate) }
    suspend fun prepareRevoke(teamID: String, invitationID: String) = withContext(Dispatchers.IO) { session.prepareRevoke(teamID,invitationID) }
    suspend fun sendPending() = withContext(Dispatchers.IO) { session.sendPending() }
    suspend fun keepRejectedAsDraft(operationID: String) = withContext(Dispatchers.IO) { session.keepRejectedAsDraft(operationID) }
    suspend fun archiveRejectedOperation(operationID: String, confirmed: Boolean) = withContext(Dispatchers.IO) { session.archiveRejectedOperation(operationID,confirmed) }
    suspend fun matches(teamID: String) = withContext(Dispatchers.IO) { session.matches(teamID) }
    suspend fun match(teamID: String, gameID: String) = withContext(Dispatchers.IO) { session.match(teamID,gameID) }
    suspend fun participants(teamID: String, gameID: String) = withContext(Dispatchers.IO) { session.matchParticipants(teamID,gameID) }
    suspend fun prepareHandoff(expected: SharedMatchDocument, kind: MatchControl, targetDeviceID: String? = null, confirmRisk: Boolean = false) = withContext(Dispatchers.IO) { session.prepareMatchControl(expected,kind,targetDeviceID,confirmRisk) }
    suspend fun sendHandoff(gameID: String) = withContext(Dispatchers.IO) { session.sendMatchControl(gameID) }
    suspend fun devices(teamID: String) = withContext(Dispatchers.IO) { session.teamDevices(teamID) }
    suspend fun pairing(pairingID: String) = withContext(Dispatchers.IO) { session.pairing(pairingID) }
    suspend fun previewPairing(teamID: String, token: String) = withContext(Dispatchers.IO) { session.previewPairing(teamID,token) }
    suspend fun preparePairing(label: String) = withContext(Dispatchers.IO) { session.preparePairing(label) }
    suspend fun preparePairApproval(preview: DevicePairing, token: String, confirmed: Boolean) = withContext(Dispatchers.IO) { session.preparePairApproval(preview,token,confirmed) }
    suspend fun preparePairAcceptance(offer: DevicePairing, confirmed: Boolean) = withContext(Dispatchers.IO) { session.preparePairAcceptance(offer,confirmed) }
    suspend fun preparePairCancellation(pairingID: String, teamID: String? = null) = withContext(Dispatchers.IO) { session.preparePairCancellation(pairingID,teamID) }
    suspend fun prepareDeviceRemoval(teamID: String, device: PairedTeamDevice, confirmed: Boolean) = withContext(Dispatchers.IO) { session.prepareDeviceRemoval(teamID,device,confirmed) }
    suspend fun recoveryStatus(teamID: String) = withContext(Dispatchers.IO) { session.recoveryStatus(teamID) }
    suspend fun previewRecovery(input: ManagementRecoveryInput) = withContext(Dispatchers.IO) { session.previewManagementRecovery(input) }
    suspend fun prepareRecoveryRotation(status: ManagementRecoveryStatus, confirmed: Boolean) = withContext(Dispatchers.IO) { session.prepareRecoveryRotation(status,confirmed) }
    suspend fun prepareManagementRecovery(input: ManagementRecoveryInput, preview: ManagementRecoveryStatus, nickname: String, confirmed: Boolean) = withContext(Dispatchers.IO) { session.prepareManagementRecovery(input,preview,nickname,confirmed) }
    suspend fun confirmRecoveryCardSaved(teamID: String, version: Long) = withContext(Dispatchers.IO) { session.confirmRecoveryCardSaved(teamID,version) }
    suspend fun quarantineRejectedSecurity(operationID: String, confirmed: Boolean) = withContext(Dispatchers.IO) { session.quarantineRejectedSecurity(operationID,confirmed) }
}
