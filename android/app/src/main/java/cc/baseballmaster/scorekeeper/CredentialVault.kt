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

/** Device-bound secret only; never part of the Room business database or exports. */
class CredentialVault(context: Context) {
    companion object { private val lock = Any() }
    private val preferences = context.getSharedPreferences("device_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bm-device-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bm-device-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun secret(): ByteArray = synchronized(lock) {
        val saved = preferences.getString("sealed", null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (saved != null) {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            check(bytes.size >= 28) { "设备凭证损坏，原文件已保留" }
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return@synchronized cipher.doFinal(bytes.copyOfRange(12, bytes.size)).also { check(it.size == 32) }
        }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(secret)
        check(preferences.edit().putString("sealed", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) { "设备凭证尚未保存" }
        secret
    }
}
