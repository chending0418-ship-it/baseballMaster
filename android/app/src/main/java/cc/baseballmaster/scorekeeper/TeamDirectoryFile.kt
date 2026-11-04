package cc.baseballmaster.scorekeeper

import android.content.Context
import android.util.AtomicFile
import cc.baseballmaster.core.*
import java.io.File
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Separate from Room/.bmandroid, in noBackupFilesDir. CredentialVault provides a
 * device-bound secret; neither plaintext requests nor credentials are written here.
 */
internal class TeamDirectoryFile(
    directory: File, private val secret: () -> ByteArray, private val beforeCommit: () -> Unit = {},
) : TeamDirectoryStorage {
    companion object { private val fileMonitor = Any() }
    override val monitor: Any get() = fileMonitor
    private val folder = directory
    private val file = AtomicFile(File(directory, "directory-v1.sealed"))
    private val aad = "BaseballMaster.android.team-directory.v1".toByteArray()
    private fun key(): SecretKeySpec {
        // HKDF-SHA256, with separate Android format/domain (not an iOS keychain export).
        val input = secret(); check(input.size == 32)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(aad, "HmacSHA256")); val prk = hmac.doFinal(input)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        return SecretKeySpec(hmac.doFinal("local-state".toByteArray() + byteArrayOf(1)), "AES")
    }
    override fun load(): TeamDirectoryState = synchronized(monitor) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized TeamDirectoryState()
        val sealed = file.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; check(output.size() + n <= 100 * 1_048_576) { "协作资料过大，原文件已保留" }; output.write(buffer,0,n) }
            output.toByteArray()
        }
        check(sealed.size >= 28) { "协作资料损坏，原文件已保留" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0,12)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(sealed.copyOfRange(12,sealed.size))
        GameJson.decodeFromJsonElement(TeamDirectoryState.serializer(), AppleWireJson.parse(plain))
    }
    override fun save(state: TeamDirectoryState) = synchronized(monitor) {
        check(folder.isDirectory || folder.mkdirs()) { "协作目录尚未保存" }
        val plain = GameJson.encodeToString(TeamDirectoryState.serializer(), state).toByteArray()
        check(plain.size <= 100 * 1_048_576 - 28) { "协作资料过大，原文件已保留" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
        val sealed = cipher.iv + cipher.doFinal(plain)
        val output = file.startWrite()
        try {
            output.write(sealed); output.fd.sync(); beforeCommit(); file.finishWrite(output)
            // AtomicFile logs certain rename failures instead of throwing. Do not
            // report a durable pending request until its exact bytes are readable.
            check(file.readFully().contentEquals(sealed)) { "协作资料尚未保存，未发送操作" }
        }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}

/** Explicit opt-in only. Construction does not enroll, refresh, or transmit a local team. */
internal fun androidTeamDirectory(context: Context, endpoint: String): TeamDirectorySession {
    val app = context.applicationContext
    val vault = CredentialVault(app)
    val store = TeamDirectoryFile(File(app.noBackupFilesDir, "team-directory"), vault::stableEncryptionSecret)
    val rotator=TeamCredentialRotator(vault) { token -> TeamHttpTransport(endpoint,TeamCredentialProvider { token }) }
    val credentials = TeamCredentialProvider {
        check(vault.pendingReconnect()==null) {"设备重新接入尚未完成，请续接"}
        val pending=vault.pendingRotation()
        if (pending != null || vault.rotationDue()) {
            val device=store.load().deviceID
            check(device != null || pending == null) { "请先核对设备身份后继续授权更新" }
            try { if (device != null) rotator.rotate(device) }
            catch (fault: TeamServiceFault) {
                when {
                    fault.status in setOf(409,426) -> Unit
                    fault.status == 410 && fault.code == "rotation_expired" -> vault.pendingRotation()?.let { vault.clearExpiredRotation(it.id) }
                    else -> throw fault
                }
            }
        }
        vault.currentToken()
    }
    val reconnect=TeamDeviceReconnector(vault,store,{token -> TeamHttpTransport(endpoint,TeamCredentialProvider {token})},android.os.Build.MODEL)
    return TeamDirectorySession(TeamDirectoryClient(TeamHttpTransport(endpoint, credentials)), store, android.os.Build.MODEL,
        reconnect::reconnect,{vault.pendingReconnect()!=null})
}

/** Explicit maintenance, never performed just because the app starts. Pending
 * rotations are resumed by the credential provider before ordinary API traffic.
 */
internal suspend fun rotateAndroidTeamCredential(context: Context, endpoint: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val app=context.applicationContext; val vault=CredentialVault(app)
    val file=TeamDirectoryFile(File(app.noBackupFilesDir,"team-directory"),vault::stableEncryptionSecret)
    val device=requireNotNull(file.load().deviceID) { "请先注册并核对设备身份" }
    TeamCredentialRotator(vault) { token -> TeamHttpTransport(endpoint,TeamCredentialProvider { token }) }.rotate(device)
}
