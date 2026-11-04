package cc.baseballmaster.core

import kotlinx.serialization.json.*

data class TeamCredentialRotation(val id: String, val token: String) {
    init { appleUUID(id); TeamSecrets.validate(token) }
    override fun toString() = "TeamCredentialRotation(id=$id)"
}
interface RotatingTeamCredentials {
    val monitor: Any
    fun currentToken(): String
    /** Persist a separate stable encryption secret before changing authentication. */
    fun stableEncryptionSecret(): ByteArray
    fun pendingRotation(): TeamCredentialRotation?
    fun beginRotation(): TeamCredentialRotation
    /** Atomically replace current authentication and remove this pending rotation. */
    fun finishRotation(rotation: TeamCredentialRotation)
}

/** Existing protocol: commit with the new credential first, prepare with the old
 * credential only when missing, then commit. No ordinary request retries here.
 */
class TeamCredentialRotator(private val vault: RotatingTeamCredentials, private val transportFor: (String) -> TeamTransport) {
    fun rotate(expectedDeviceID: String): String = synchronized(vault.monitor) {
        val deviceID=appleUUID(expectedDeviceID)
        check(vault.stableEncryptionSecret().size == 32) { "设备加密资料尚未保存，未更新授权" }
        val rotation=vault.beginRotation()
        val body=buildJsonObject { put("rotationID",rotation.id) }
        val next=transportFor(rotation.token)
        val response=try { next.request("POST","devices/credential-commit",body) } catch (fault: TeamServiceFault) {
            if (fault.status != 404 || fault.code != "rotation_missing") throw fault
            val prepared=transportFor(vault.currentToken()).request("POST","devices/credential-prepare",buildJsonObject {
                put("operationID",rotation.id); put("newToken",rotation.token)
            })
            check(appleUUID(prepared.getValue("rotationID").jsonPrimitive.content) == rotation.id) { "设备授权更新回执不一致" }
            next.request("POST","devices/credential-commit",body)
        }
        check(response["rotated"]?.jsonPrimitive?.boolean == true && appleUUID(response.getValue("deviceID").jsonPrimitive.content) == deviceID) { "设备授权更新尚未确认，原待处理资料已保留" }
        vault.finishRotation(rotation)
        deviceID
    }
}
