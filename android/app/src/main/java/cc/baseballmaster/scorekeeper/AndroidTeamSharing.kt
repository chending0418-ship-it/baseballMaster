package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/** App-facing suspend API. Local Room data is read only; bindings and shared
 * rosters stay in the independent encrypted directory, never imported over it.
 */
internal class AndroidTeamSharing(private val local: GameRepository, private val session: TeamDirectorySession) {
    suspend fun state() = withContext(Dispatchers.IO) { session.snapshot() }
    suspend fun reconnect(confirmed: Boolean) = withContext(Dispatchers.IO) {session.reconnect(confirmed)}
    suspend fun retainIncomingLink(link: IncomingTeamLink) = withContext(Dispatchers.IO) {session.retainIncomingLink(link)}
    suspend fun consumeIncomingLink(id: String) = withContext(Dispatchers.IO) {session.consumeIncomingLink(id)}
    suspend fun reconcileAfterRecovery(confirmed: Boolean) = withContext(Dispatchers.IO) {session.reconcileAfterRecovery(confirmed)}
    suspend fun dissolvedTeams() = withContext(Dispatchers.IO) {session.dissolvedTeams()}
    suspend fun prepareTeamDissolution(profile: SharedTeamProfile, name: String, confirmed: Boolean) = withContext(Dispatchers.IO) {session.prepareTeamDissolution(profile,name,confirmed)}
    suspend fun prepareTeamRestoration(team: DissolvedTeam, confirmed: Boolean) = withContext(Dispatchers.IO) {session.prepareTeamRestoration(team,confirmed)}
    suspend fun audit(teamID: String, from: Long, action: String, before: Long?) = withContext(Dispatchers.IO) {session.audit(teamID,from,action,before)}
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
    suspend fun viewers(teamID: String,gameID: String) = withContext(Dispatchers.IO) {session.matchViewers(teamID,gameID)}
    suspend fun watchMode(expected: SharedMatchDocument,mode: HistoricalWatchMode,confirmed: Boolean) = withContext(Dispatchers.IO) {session.prepareWatchMode(expected,mode,confirmed)}
    suspend fun watchMember(expected: SharedMatchDocument,id: String,allow: Boolean) = withContext(Dispatchers.IO) {session.prepareViewerMember(expected,id,allow)}
    suspend fun watchExternal(expected: SharedMatchDocument,viewer: MatchViewer,allow: Boolean) = withContext(Dispatchers.IO) {session.prepareExternalViewer(expected,viewer,allow)}
    suspend fun watchInvitation(expected: SharedMatchDocument) = withContext(Dispatchers.IO) {session.prepareWatchInvitation(expected)}
    suspend fun watchTicket(expected: SharedMatchDocument) = withContext(Dispatchers.IO) {session.prepareWatchTicket(expected)}
    suspend fun sharedLive(expected: SharedMatchDocument,enabled: Boolean,confirmed: Boolean) = withContext(Dispatchers.IO) {session.prepareSharedLive(expected,enabled,confirmed)}
    suspend fun sendWatching(gameID: String) = withContext(Dispatchers.IO) {session.sendWatching(gameID)}
    suspend fun parkWatching(gameID: String,confirmed: Boolean) = withContext(Dispatchers.IO) {session.parkWatching(gameID,confirmed)}
    suspend fun sharedHeartbeat(teamID: String,gameID: String) = withContext(Dispatchers.IO) {session.sharedLiveHeartbeat(teamID,gameID)}
    /** Foreground presence only for an already enabled broadcast owned by this
     * device. An empty/unreviewed cache never enrolls or contacts the service.
     */
    suspend fun maintainSharedLive() = withContext(Dispatchers.IO) {
        val state=session.snapshot()
        val directory=state.directory ?: return@withContext
        if(state.deviceID==null || state.credentialExpired || state.requiresRecoveryReview) return@withContext
        val candidates=state.matches.values.map {it.document}.filter {doc ->
            !doc.deleted && doc.canWrite && doc.writerDeviceID==state.deviceID && doc.liveAvailable && !doc.finalForWatching &&
                directory.teams.any {it.id==doc.teamID && it.role!=TeamRole.member}
        }
        for(doc in candidates) {
            ensureActive()
            runCatching {session.sharedLiveHeartbeat(doc.teamID,doc.id)}
        }
    }
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
