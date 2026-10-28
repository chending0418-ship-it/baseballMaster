package cc.baseballmaster.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class TeamSecurityAction { pairCreate, pairApprove, pairAccept, pairCancel, deviceRemove, recoveryRotate, recoveryRedeem }
@Serializable data class DevicePairing(
    val id: String, val status: String, val expiresAt: Long, val phrase: String,
    val targetLabel: String? = null, val teamID: String? = null, val teamName: String? = null,
    val memberID: String? = null, val nickname: String? = null, val role: TeamRole? = null, val memberRevision: Long? = null,
) {
    init { appleUUID(id); require(status in setOf("pending","offered","accepted","cancelled","expired","revoked") && expiresAt > 0 && phrase.isNotBlank()); teamID?.let(::appleUUID); memberID?.let(::appleUUID) }
}
@Serializable data class SavedDevicePairing(val token: String, val request: DevicePairing) {
    init { TeamSecrets.validate(token) }
    override fun toString()="SavedDevicePairing(id=${request.id}, status=${request.status})"
}
@Serializable data class PairedTeamDevice(val id: String, val label: String, val memberID: String, val nickname: String, val role: TeamRole, val revision: Long, val isCurrent: Int) {
    init { appleUUID(id); appleUUID(memberID); require(revision > 0 && isCurrent in 0..1) }
}
@Serializable data class ManagementRecoveryStatus(val teamID: String, val teamName: String, val version: Long, val hasCode: Boolean? = null) {
    init { appleUUID(teamID); require(version >= 0) }
}
@Serializable data class SavedManagementCard(val teamID: String, val teamName: String, val token: String, val version: Long, val confirmedSaved: Boolean = false) {
    init { appleUUID(teamID); TeamSecrets.validate(token); require(version > 0) }
    val code: String get()="BMREC1.${appleUUID(teamID)}.$token"
    override fun toString()="SavedManagementCard(teamID=$teamID, version=$version)"
}
data class ManagementRecoveryInput(val teamID: String, val token: String) {
    init { appleUUID(teamID); TeamSecrets.validate(token) }
    override fun toString()="ManagementRecoveryInput(teamID=$teamID)"
    companion object {
        fun parse(text: String): ManagementRecoveryInput {
            require(text.length <= 65536) { "恢复卡文本过长" }
            val results=Regex("BMREC1\\.([A-Fa-f0-9-]{36})\\.([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])").findAll(text).toList()
            require(results.size == 1) { "请提供一份完整的管理恢复卡" }
            return ManagementRecoveryInput(appleUUID(results.single().groupValues[1]),TeamSecrets.validate(results.single().groupValues[2]))
        }
    }
}

internal fun PendingDirectoryRequest.securityPath(): String = when(requireNotNull(securityAction)) {
    TeamSecurityAction.pairCreate -> "pairings"
    TeamSecurityAction.pairApprove -> "teams/${appleUUID(requireNotNull(teamID))}/pairings/${appleUUID(requireNotNull(targetID))}/approve"
    TeamSecurityAction.pairAccept -> "pairings/${appleUUID(requireNotNull(targetID))}/accept"
    TeamSecurityAction.pairCancel -> "pairings/${appleUUID(requireNotNull(targetID))}/cancel"
    TeamSecurityAction.deviceRemove -> "teams/${appleUUID(requireNotNull(teamID))}/devices/${appleUUID(requireNotNull(targetID))}"
    TeamSecurityAction.recoveryRotate -> "teams/${appleUUID(requireNotNull(teamID))}/recovery/rotate"
    TeamSecurityAction.recoveryRedeem -> "recovery/redeem"
}
internal fun PendingDirectoryRequest.validateSecurityReply(response: JsonObject) {
    when(requireNotNull(securityAction)) {
        TeamSecurityAction.pairCreate,TeamSecurityAction.pairApprove,TeamSecurityAction.pairAccept -> TeamDirectoryJson.format.decodeFromJsonElement<DevicePairing>(response).also { if(targetID!=null) require(it.id.equals(targetID,true)) }
        TeamSecurityAction.pairCancel -> require(response["cancelled"]?.jsonPrimitive?.boolean == true)
        TeamSecurityAction.deviceRemove -> require(response["removed"]?.jsonPrimitive?.boolean == true && response["deviceID"]?.jsonPrimitive?.content.equals(targetID,true) && response["teamID"]?.jsonPrimitive?.content.equals(teamID,true))
        TeamSecurityAction.recoveryRotate,TeamSecurityAction.recoveryRedeem -> TeamDirectoryJson.format.decodeFromJsonElement<ManagementRecoveryStatus>(response).also {
            require(it.version>0 && it.teamID.equals(teamID ?: body.getValue("teamID").jsonPrimitive.content,true))
            if(securityAction==TeamSecurityAction.recoveryRedeem) { require(response["recovered"]?.jsonPrimitive?.boolean==true && response["role"]?.jsonPrimitive?.content=="admin"); appleUUID(response.getValue("memberID").jsonPrimitive.content) }
        }
    }
}
private fun TeamDirectorySession.securityMember(teamID: String, admin: Boolean = false): RemoteTeam {
    val remote=refresh(); check(!storage.load().requiresRecoveryReview) { "请先核对恢复后的资料" }
    return requireNotNull(remote.teams.singleOrNull { it.id.equals(teamID,true) && (!admin || it.role == TeamRole.admin) }) { "当前角色不能进行此安全操作" }
}

fun TeamDirectorySession.teamDevices(teamID: String): List<PairedTeamDevice> = synchronized(storage.monitor) {
    securityMember(teamID)
    client.transport.request("GET","teams/${appleUUID(teamID)}/devices",null).getValue("devices").jsonArray.map { TeamDirectoryJson.format.decodeFromJsonElement<PairedTeamDevice>(it) }
}
fun TeamDirectorySession.pairing(pairingID: String): DevicePairing = synchronized(storage.monitor) {
    refresh(); check(!storage.load().requiresRecoveryReview) { "请先核对恢复后的资料" }
    val result=TeamDirectoryJson.format.decodeFromJsonElement<DevicePairing>(client.transport.request("GET","pairings/${appleUUID(pairingID)}",null))
    require(result.id.equals(pairingID,true)); val state=storage.load()
    storage.save(state.copy(pairing=state.pairing?.let { if(it.request.id==result.id) it.copy(request=result) else it },outgoingPairing=state.outgoingPairing?.let { if(it.id==result.id) result else it }))
    result
}
fun TeamDirectorySession.previewPairing(teamID: String, token: String): DevicePairing = synchronized(storage.monitor) {
    securityMember(teamID)
    TeamDirectoryJson.format.decodeFromJsonElement(client.transport.request("POST","teams/${appleUUID(teamID)}/pairings/preview",buildJsonObject { put("token",TeamSecrets.validate(token)) }))
}
fun TeamDirectorySession.recoveryStatus(teamID: String): ManagementRecoveryStatus = synchronized(storage.monitor) {
    securityMember(teamID,true)
    val status=TeamDirectoryJson.format.decodeFromJsonElement<ManagementRecoveryStatus>(client.transport.request("GET","teams/${appleUUID(teamID)}/recovery",null))
    require(status.teamID.equals(teamID,true)); val state=storage.load(); val card=state.recoveryCards.singleOrNull { it.teamID.equals(teamID,true) }
    check(card==null || status.version >= card.version) { "恢复卡版本倒退，请核对服务状态" }
    if(card!=null && (status.version!=card.version || status.hasCode==false)) storage.save(state.copy(recoveryCards=state.recoveryCards.filterNot { it.teamID.equals(teamID,true) }))
    status
}
fun TeamDirectorySession.previewManagementRecovery(input: ManagementRecoveryInput): ManagementRecoveryStatus = synchronized(storage.monitor) {
    refresh(); check(!storage.load().requiresRecoveryReview) { "请先核对恢复后的资料" }
    TeamDirectoryJson.format.decodeFromJsonElement(client.transport.request("POST","recovery/preview",buildJsonObject { put("teamID",appleUUID(input.teamID)); put("token",input.token) }))
}
fun TeamDirectorySession.preparePairing(label: String): String = prepare(DirectoryRequestKind.security,null,securityAction=TeamSecurityAction.pairCreate) {
    teamText(label,true); put("label",label); put("token",TeamSecrets.random())
}
fun TeamDirectorySession.preparePairApproval(preview: DevicePairing, token: String, confirmed: Boolean): String = prepare(DirectoryRequestKind.security,appleUUID(requireNotNull(preview.teamID)),targetID=preview.id,securityAction=TeamSecurityAction.pairApprove) {
    require(confirmed && preview.status=="pending" && preview.memberRevision!=null) { "请先核对新设备、身份与配对短语" }
    put("token",TeamSecrets.validate(token)); put("phrase",preview.phrase); put("memberRevision",preview.memberRevision)
}
fun TeamDirectorySession.preparePairAcceptance(offer: DevicePairing, confirmed: Boolean): String = prepare(DirectoryRequestKind.security,null,targetID=offer.id,securityAction=TeamSecurityAction.pairAccept) {
    require(confirmed && offer.status=="offered" && offer.memberRevision!=null) { "请先核对球队、称呼、角色和短语" }
    check(storage.load().pairing?.request?.id==offer.id) { "不是此设备保存的配对请求" }
    put("phrase",offer.phrase); put("memberRevision",offer.memberRevision)
}
fun TeamDirectorySession.preparePairCancellation(pairingID: String, teamID: String? = null): String = prepare(DirectoryRequestKind.security,teamID?.let(::appleUUID),targetID=appleUUID(pairingID),securityAction=TeamSecurityAction.pairCancel) { }
fun TeamDirectorySession.prepareDeviceRemoval(teamID: String, device: PairedTeamDevice, confirmed: Boolean): String = prepare(DirectoryRequestKind.security,appleUUID(teamID),targetID=appleUUID(device.id),securityAction=TeamSecurityAction.deviceRemove) {
    require(confirmed) { "请先确认移除这台设备" }; put("memberID",appleUUID(device.memberID)); put("baseRevision",device.revision)
}
fun TeamDirectorySession.prepareRecoveryRotation(status: ManagementRecoveryStatus, confirmed: Boolean): String = prepare(DirectoryRequestKind.security,appleUUID(status.teamID),securityAction=TeamSecurityAction.recoveryRotate) {
    require(confirmed) { "请确认旧恢复卡将失效" }; require(storage.load().directory!!.teams.any { it.id.equals(status.teamID,true) && it.role==TeamRole.admin })
    put("newToken",TeamSecrets.random()); put("baseVersion",status.version); put("confirmReset",true)
}
fun TeamDirectorySession.prepareManagementRecovery(input: ManagementRecoveryInput, preview: ManagementRecoveryStatus, nickname: String, confirmed: Boolean): String = prepare(DirectoryRequestKind.security,null,securityAction=TeamSecurityAction.recoveryRedeem) {
    require(confirmed && input.teamID.equals(preview.teamID,true) && preview.version > 0) { "请明确确认：原授权、邀请和观看入口将失效，比赛保留" }
    teamText(nickname,true); put("teamID",appleUUID(input.teamID)); put("token",input.token); put("newToken",TeamSecrets.random())
    put("nickname",nickname); put("baseVersion",preview.version); put("confirmReset",true)
}

fun TeamDirectorySession.confirmRecoveryCardSaved(teamID: String, version: Long) = synchronized(storage.monitor) {
    val state=storage.load(); require(state.recoveryCards.any { it.teamID.equals(teamID,true) && it.version==version }) { "恢复卡已经变化，请重新保存" }
    storage.save(state.copy(recoveryCards=state.recoveryCards.map { if(it.teamID.equals(teamID,true) && it.version==version) it.copy(confirmedSaved=true) else it }))
}

/** Explicitly archive a rejected safety action for manual review; the action may
 * have succeeded earlier before authorization changed. Never replay it here.
 */
fun TeamDirectorySession.quarantineRejectedSecurity(operationID: String, confirmed: Boolean) = synchronized(storage.monitor) {
    val state=storage.load(); val pending=requireNotNull(state.pending); val failure=requireNotNull(state.lastFailure)
    require(confirmed && pending.kind==DirectoryRequestKind.security && pending.id==operationID && failure.operationID==operationID &&
        failure.status in setOf(400,403,404,409,410,413,426) && !state.requiresRecoveryReview) { "结果尚未明确或需恢复核对，不能归档重做" }
    storage.save(state.copy(pending=null,lastFailure=null,reviewedRequests=state.reviewedRequests + ReviewedDirectoryRequest(pending,failure)))
}

internal fun TeamDirectoryState.applyingSecurity(pending: PendingDirectoryRequest, response: JsonObject): TeamDirectoryState {
    return when(pending.securityAction) {
        TeamSecurityAction.pairCreate -> copy(pairing=SavedDevicePairing(pending.body.getValue("token").jsonPrimitive.content,TeamDirectoryJson.format.decodeFromJsonElement(response)))
        TeamSecurityAction.pairApprove -> copy(outgoingPairing=TeamDirectoryJson.format.decodeFromJsonElement(response))
        TeamSecurityAction.pairAccept -> copy(pairing=pairing?.copy(request=TeamDirectoryJson.format.decodeFromJsonElement(response)))
        TeamSecurityAction.pairCancel -> copy(pairing=pairing?.takeUnless { it.request.id.equals(pending.targetID,true) },outgoingPairing=outgoingPairing?.takeUnless { it.id.equals(pending.targetID,true) })
        TeamSecurityAction.deviceRemove -> if(pending.targetID==deviceID) withoutTeam(requireNotNull(pending.teamID)) else this
        TeamSecurityAction.recoveryRotate,TeamSecurityAction.recoveryRedeem -> {
            val status=TeamDirectoryJson.format.decodeFromJsonElement<ManagementRecoveryStatus>(response)
            val cleared=if(pending.securityAction==TeamSecurityAction.recoveryRedeem) withoutTeam(status.teamID).copy(pairing=null,outgoingPairing=null) else this
            cleared.copy(recoveryCards=cleared.recoveryCards.filterNot { it.teamID.equals(status.teamID,true) } + SavedManagementCard(status.teamID,status.teamName,pending.body.getValue("newToken").jsonPrimitive.content,status.version))
        }
        null -> this
    }
}
private fun TeamDirectoryState.withoutTeam(id: String): TeamDirectoryState = copy(
    directory=directory?.copy(teams=directory.teams.filterNot { it.id.equals(id,true) }),profiles=profiles - appleUUID(id),
    bindings=bindings.filterNot { it.cloudTeamID.equals(id,true) },matches=matches.filterValues { !it.document.teamID.equals(id,true) },matchCursors=matchCursors - appleUUID(id),
    matchReceipts=matchReceipts.filterValues { !it.request.teamID.equals(id,true) },retainedSecrets=retainedSecrets.filterNot { (it.request.teamID ?: it.response["teamID"]?.jsonPrimitive?.content).equals(id,true) },
    recoveryCards=recoveryCards.filterNot { it.teamID.equals(id,true) },fixedInvitations=fixedInvitations - appleUUID(id))
