package cc.baseballmaster.scorekeeper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.baseballmaster.core.GameJson
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.IOException

private const val cookie = "voice_assistant_session"
private val sessionToken = Regex("[A-Za-z0-9_-]{32}")
// Voice responses may add fields independently of the app (for example, grant expiry).
// Keep the game's persisted and backup schemas strict.
private val voiceResponseJson = Json(GameJson) { ignoreUnknownKeys = true }

private inline fun <reified T> decodeVoiceResponse(value: JsonElement): T =
    try {
        voiceResponseJson.decodeFromJsonElement<T>(value)
    } catch (_: SerializationException) {
        // Decoding failures can include the signed URL or transcript in their message.
        throw VoiceIssue("当前 App 无法读取语音服务响应。")
    }
internal class VoiceIssue(message: String, val unauthorized: Boolean = false) : IOException(message)

@Serializable internal data class VoiceGrant(val id: String, val url: String, val maxRecordingSeconds: Int) {
    override fun toString() = "VoiceGrant(maxRecordingSeconds=$maxRecordingSeconds)"
}
@Serializable internal data class VoiceAnalysis(val schemaVersion: Int, val summary: String, val ball: Ball, val events: List<Event>, val observations: List<String>) {
    @Serializable data class Ball(val type: String, val region: String, val outcome: String, val intent: String? = null)
    @Serializable data class Event(
        val sequence: Int, val kind: String, val evidence: String, val playerId: String? = null,
        val fromBase: String? = null, val toBase: String? = null, val outType: String? = null,
        val pitchResult: String? = null, val fromFielder: Int? = null, val toFielder: Int? = null, val cause: String? = null,
    )
}

internal interface VoiceService {
    suspend fun redeem(code: String, device: String, operation: String): String
    suspend fun entitlement(token: String): Boolean
    suspend fun grant(context: JsonObject, token: String): VoiceGrant
    suspend fun end(id: String, token: String)
    suspend fun analyze(text: String, context: JsonObject, token: String): VoiceAnalysis
}

internal val voiceHttp = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(240, TimeUnit.SECONDS).callTimeout(250, TimeUnit.SECONDS)
    .build()

internal class HttpVoiceService(
    private val api: String = "https://baseballmaster.cc/recording/voice-assistant/api/",
    private val client: OkHttpClient = voiceHttp,
) : VoiceService {
    override suspend fun redeem(code: String, device: String, operation: String): String {
        val result = request("redeem", body = buildJsonObject {
            put("code", code); put("deviceId", device); put("operationId", operation)
        })
        val token = result.second.asSequence().map { it.substringBefore(';') }
            .firstOrNull { it.startsWith("$cookie=") }?.substringAfter('=')
        if (result.first["active"]?.jsonPrimitive?.booleanOrNull != true || token == null || !sessionToken.matches(token))
            throw VoiceIssue("开通响应未完整收到，请用同一个开启码重试。")
        return token
    }

    override suspend fun entitlement(token: String) =
        request("entitlement", token).first.getValue("active").jsonPrimitive.boolean

    override suspend fun grant(context: JsonObject, token: String) =
        decodeVoiceResponse<VoiceGrant>(request("asr-session", token, buildJsonObject { put("context", context) }).first)

    override suspend fun end(id: String, token: String) {
        request("asr-session/end", token, buildJsonObject { put("id", id) })
    }

    override suspend fun analyze(text: String, context: JsonObject, token: String): VoiceAnalysis {
        val response = request("analyze", token, buildJsonObject {
            put("text", text); put("context", context); put("modelId", "hy3"); put("thinking", "disabled")
        }).first
        return decodeVoiceResponse<VoiceAnalysis>(response["analysis"] ?: JsonNull).also {
            if (it.schemaVersion != 1) throw VoiceIssue("当前 App 无法读取此解析结果。")
        }
    }

    private suspend fun request(path: String, token: String? = null, body: JsonObject? = null): Pair<JsonObject, List<String>> =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(api + path).header("Cache-Control", "no-store")
            if (token != null) request.header("Cookie", "$cookie=$token")
            if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(VoiceIssue("语音服务连接中断，请重试或继续手动记分。"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if ((it.code == 401 || it.code == 403) && token != null)
                                throw VoiceIssue("语音权限已失效，请在 App 设置中重新开通。", unauthorized = true)
                            val source = it.body?.source()
                            if (source?.request(256_001) == true) throw VoiceIssue("语音服务响应超出限制。")
                            val json = source?.readUtf8()?.takeIf(String::isNotBlank)
                                ?.let { raw -> GameJson.parseToJsonElement(raw).jsonObject } ?: buildJsonObject {}
                            if (!it.isSuccessful) {
                                val message = when {
                                    path == "redeem" && it.code in setOf(403, 410) -> "开启码无效、已过期或已撤回，请核对或获取新的开启码。"
                                    path == "redeem" && it.code == 409 -> "开启码已使用，请核对设备或获取新的开启码。"
                                    path == "redeem" && it.code in 400..499 -> "无法开通，请核对开启码后重试。"
                                    else -> "语音服务暂时不可用，请重试或继续手动记分。"
                                }
                                throw VoiceIssue(message)
                            }
                            json to it.headers("Set-Cookie")
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
}

@Serializable internal data class VoiceDevice(
    val id: String = UUID.randomUUID().toString(), val token: String? = null,
    val pendingDigest: String? = null, val pendingOperation: String? = null,
) {
    override fun toString() = "VoiceDevice(active=${token != null}, pending=${pendingOperation != null})"
}

internal interface VoiceDeviceStore {
    fun read(): VoiceDevice
    fun write(device: VoiceDevice)
}

internal class SealedVoiceDevice(context: Context) : VoiceDeviceStore {
    private val file = AtomicFile(context.noBackupFilesDir.resolve("voice-access-v1.sealed"))
    private val aad = "cc.baseballmaster.voice-access-v1".toByteArray()
    private fun key(): SecretKey {
        val alias = "bm-voice-access-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
    }

    private fun sealedBytes(): ByteArray = file.openRead().use { stream ->
        val buffer = ByteArray(4097)
        var size = 0
        while (size < buffer.size) {
            val count = stream.read(buffer, size, buffer.size - size)
            if (count == -1) break
            size += count
        }
        buffer.copyOf(size)
    }

    override fun read(): VoiceDevice {
        val bytes = try { sealedBytes() }
            catch (_: java.io.FileNotFoundException) {
                if (file.baseFile.exists()) throw VoiceIssue("无法读取本机语音权限。")
                return VoiceDevice()
            }
        require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return GameJson.decodeFromString<VoiceDevice>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
    }

    override fun write(device: VoiceDevice) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(GameJson.encodeToString(device).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        check(sealedBytes().contentEquals(bytes))
    }
}

internal class VoiceAccess(private val store: VoiceDeviceStore, val service: VoiceService = HttpVoiceService()) {
    private val lock = Mutex()
    var token by mutableStateOf<String?>(null)
        private set

    suspend fun refresh() = lock.withLock {
        val saved = withContext(Dispatchers.IO) { store.read().token }
        token = saved
        if (saved != null) {
            val active = try { service.entitlement(saved) } catch (e: VoiceIssue) { if (!e.unauthorized) throw e; false }
            if (!active) revokeLocked(saved)
        }
    }

    suspend fun redeem(input: String) = lock.withLock {
        val code = input.trim().uppercase(Locale.ROOT)
        if (code.isEmpty() || code.length > 80) throw VoiceIssue("请核对开启码。")
        val digest = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(code.encodeToByteArray()))
        val pending = withContext(Dispatchers.IO) {
            val saved = store.read()
            if (saved.pendingDigest == digest && saved.pendingOperation != null) saved
            else saved.copy(pendingDigest = digest, pendingOperation = UUID.randomUUID().toString()).also(store::write)
        }
        val credential = service.redeem(code, pending.id, requireNotNull(pending.pendingOperation))
        withContext(Dispatchers.IO) {
            store.write(pending.copy(token = credential, pendingDigest = null, pendingOperation = null))
        }
        token = credential
    }

    suspend fun report(error: Throwable, credential: String) {
        if (error is VoiceIssue && error.unauthorized) lock.withLock { revokeLocked(credential) }
    }

    private suspend fun revokeLocked(credential: String) {
        if (token != credential) return
        token = null
        withContext(Dispatchers.IO) {
            val stored = store.read()
            if (stored.token == credential) store.write(stored.copy(token = null))
        }
    }
}
