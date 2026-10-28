package cc.baseballmaster.core

/** A new device identity requires explicit user action after a confirmed 401.
 * The stable file-encryption key is never replaced. Candidate credentials stay
 * durable until BOTH enrolment and the final directory save have succeeded.
 */
interface ReconnectingTeamCredentials {
    val monitor: Any
    fun stableEncryptionSecret(): ByteArray
    fun pendingReconnect(): TeamCredentialRotation?
    fun beginReconnect(): TeamCredentialRotation
    fun activateReconnect(candidate: TeamCredentialRotation)
    fun finishReconnect(candidate: TeamCredentialRotation)
}

internal fun TeamDirectoryState.withExpiredAuthorization(): TeamDirectoryState {
    val preserved=preservingRecoveryWork(directory?.recoveryEpoch ?: java.util.UUID.randomUUID().toString())
    return preserved.copy(credentialExpired=true,directory=null,profiles=emptyMap(),bindings=emptyList(),
        retainedSecrets=emptyList(),lastCompletion=null,matches=emptyMap(),matchCursors=emptyMap(),
        matchReceipts=emptyMap(),recoveryCards=emptyList(),pairing=null,outgoingPairing=null,fixedInvitations=emptyMap())
}

class TeamDeviceReconnector(
    private val vault: ReconnectingTeamCredentials, private val storage: TeamDirectoryStorage,
    private val transportFor: (String)->TeamTransport, private val deviceLabel: String,
) {
    fun reconnect(confirmed: Boolean): String = synchronized(storage.monitor) {
        synchronized(vault.monitor) {
            val original=storage.load()
            require(confirmed && (original.credentialExpired || original.reconnectRequestID!=null || vault.pendingReconnect()!=null)) {"请先确认失效授权及重新接入的影响"}
            check(vault.stableEncryptionSecret().size==32) {"本机加密资料尚未固定，未替换授权"}
            val candidate=vault.beginReconnect()
            check(original.reconnectRequestID==null || original.reconnectRequestID==candidate.id) {"重新接入资料已变化，请重新核对"}
            val isolated=original.withExpiredAuthorization().copy(deviceID=null,reconnectRequestID=candidate.id,
                pending=null,lastFailure=null,matchPending=emptyMap(),requiresRecoveryReview=true)
            // Old work is archived before even enrolling the new credential.
            storage.save(isolated)
            val id=TeamDirectoryClient(transportFor(candidate.token)).enroll(deviceLabel)
            vault.activateReconnect(candidate)
            storage.save(isolated.copy(deviceID=id,credentialExpired=false,reconnectRequestID=null))
            vault.finishReconnect(candidate)
            id
        }
    }
}
