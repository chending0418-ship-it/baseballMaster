package cc.baseballmaster.scorekeeper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import cc.baseballmaster.core.*
import android.content.SharedPreferences

internal class CredentialWriteGuard { var failed = false }

/** Device-bound secret only; never part of the Room business database or exports. */
class CredentialVault internal constructor(
    context: Context, private val injectedKey: SecretKey? = null,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
    private val writeGuard: CredentialWriteGuard = sharedWriteGuard,
    private val now: () -> Long = System::currentTimeMillis,
) : RotatingTeamCredentials {
    companion object {
        private val lock = Any()
        private val sharedWriteGuard = CredentialWriteGuard()
    }
    override val monitor: Any get() = lock

    private val preferences =
        context.getSharedPreferences("device_credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        injectedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bm-device-v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "bm-device-v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    private fun open(saved: String): ByteArray {
        val bytes = Base64.decode(saved,Base64.NO_WRAP)
        check(bytes.size >= 28) { "设备凭证损坏，原文件已保留" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).also { check(it.size == 32) }
    }
    private fun seal(secret: ByteArray): String {
        check(secret.size == 32)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(secret),Base64.NO_WRAP)
    }
    private fun loadOrCreate(slot: String, seed: () -> ByteArray): ByteArray =
        synchronized(lock) {
            checkReadable()
            preferences.getString(slot,null)?.let { return@synchronized open(it) }
            val secret=seed(); persist(preferences.edit().putString(slot,seal(secret)))
            secret
        }
    private fun checkReadable() = check(!writeGuard.failed) { "设备凭证保存结果不明，请重启 App 后重试；不会发送未确认凭证" }
    private fun persist(editor: SharedPreferences.Editor) {
        checkReadable()
        // SharedPreferences updates its in-memory map even when commit fails.
        // Block every vault in this process instead of trusting that unsaved map.
        val saved=try { commitEditor(editor) } catch (error: Exception) { writeGuard.failed=true; throw error }
        if (!saved) { writeGuard.failed=true; error("设备凭证尚未保存，请重启 App 后重试") }
    }
    fun secret(): ByteArray = loadOrCreate("sealed") { ByteArray(32).also { SecureRandom().nextBytes(it) } }
    override fun currentToken(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret())
    override fun stableEncryptionSecret(): ByteArray = loadOrCreate("state_sealed") { secret() }
    override fun pendingRotation(): TeamCredentialRotation? = synchronized(lock) {
        checkReadable()
        val id=preferences.getString("rotation_id",null)
        val saved=preferences.getString("rotation_sealed",null)
        check((id == null) == (saved == null)) { "设备授权更新资料不完整，原文件已保留" }
        if (id == null || saved == null) null else TeamCredentialRotation(id,java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(open(saved)))
    }
    override fun beginRotation(): TeamCredentialRotation = synchronized(lock) {
        stableEncryptionSecret()
        pendingRotation()?.let { return@synchronized it }
        val rotation=TeamCredentialRotation(java.util.UUID.randomUUID().toString(),TeamSecrets.random())
        val secret=java.util.Base64.getUrlDecoder().decode(rotation.token)
        persist(preferences.edit().putString("rotation_id",rotation.id).putString("rotation_sealed",seal(secret)))
        rotation
    }
    override fun finishRotation(rotation: TeamCredentialRotation) = synchronized(lock) {
        check(pendingRotation() == rotation) { "设备授权更新资料已变化" }
        check(preferences.contains("state_sealed")) { "设备加密资料尚未固定" }
        persist(preferences.edit().putString("sealed",seal(java.util.Base64.getUrlDecoder().decode(rotation.token)))
            .putLong("rotation_date",now()).remove("rotation_id").remove("rotation_sealed"))
    }
    fun rotationDue(): Boolean = synchronized(lock) {
        checkReadable()
        if (!preferences.contains("rotation_date")) { persist(preferences.edit().putLong("rotation_date",now())); return@synchronized false }
        now() - preferences.getLong("rotation_date",0) >= 90 * 86_400_000L
    }
    internal fun clearExpiredRotation(id: String) = synchronized(lock) {
        if (pendingRotation()?.id == id) persist(preferences.edit().remove("rotation_id").remove("rotation_sealed"))
    }
}
