package cc.baseballmaster.scorekeeper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import cc.baseballmaster.core.GameJson
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

internal enum class VoicePhase { connecting, recording, finishing }

internal interface VoiceRecording {
    suspend fun run(context: JsonObject, token: String, stop: AtomicBoolean, phase: (VoicePhase) -> Unit, transcript: (String) -> Unit): String
}

/** Audio is sent directly to Tencent ASR and never written to a file. */
internal class TencentVoiceRecording(private val service: VoiceService) : VoiceRecording {
    override suspend fun run(context: JsonObject, token: String, stop: AtomicBoolean, phase: (VoicePhase) -> Unit, transcript: (String) -> Unit): String {
        var grant: VoiceGrant? = null
        var socket: WebSocket? = null
        try {
            phase(VoicePhase.connecting)
            val granted = withTimeout(15_000) { service.grant(context, token).also { grant = it } }
            val url = runCatching { URI(granted.url) }.getOrNull()
            if (url?.scheme != "wss" || url.host != "asr.cloud.tencent.com" || url.port != -1 ||
                url.rawUserInfo != null || url.rawFragment != null || !Regex("/asr/v2/[0-9]+").matches(url.rawPath.orEmpty())
            ) throw VoiceIssue("未取得有效的腾讯语音识别连接。")
            val messages = Channel<String>(32)
            val connection = voiceHttp.newWebSocket(Request.Builder().url(granted.url).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 64_000 || messages.trySend(text).isFailure) {
                        messages.close(VoiceIssue("语音识别响应过大，请重新录音。"))
                        webSocket.cancel()
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    messages.close(VoiceIssue("语音服务返回了无法读取的数据。")); webSocket.cancel()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    messages.close(VoiceIssue("语音连接中断，请重试或继续手动记分。"))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    messages.close(VoiceIssue("语音连接提前结束，请重新录音。"))
                }
            })
            socket = connection
            val parts = TencentTranscript()
            fun receive(message: String): Boolean {
                val finished = parts.accept(message)
                transcript(parts.text)
                return finished
            }
            if (withTimeout(15_000) { receive(messages.receive()) })
                throw VoiceIssue("识别提前结束，请重新录音。")
            val sentEnd = AtomicBoolean(false)
            val final = coroutineScope {
                val result = async {
                    while (!receive(messages.receive())) { /* receive partial segments */ }
                    if (!sentEnd.get()) throw VoiceIssue("识别提前结束，请重新录音。")
                    parts.text.trim().takeIf { it.isNotEmpty() } ?: throw VoiceIssue("没有听到清楚的口述，请重新录音。")
                }
                phase(VoicePhase.recording)
                withContext(Dispatchers.IO) { sendAudio(connection, stop, granted.maxRecordingSeconds.coerceIn(1, 90)) }
                phase(VoicePhase.finishing)
                sentEnd.set(true)
                if (!connection.send("""{"type":"end"}""")) throw VoiceIssue("录音未发送完整，请重试。")
                withTimeout(20_000) { result.await() }
            }
            return final
        } catch (_: TimeoutCancellationException) {
            throw VoiceIssue("等待语音服务超时，请重试或继续手动记分。")
        } finally {
            socket?.cancel()
            grant?.let { acquired ->
                withContext(NonCancellable) { withTimeoutOrNull(4_000) { runCatching { service.end(acquired.id, token) } } }
            }
        }
    }

    @SuppressLint("MissingPermission") // The Compose permission result gates this call.
    private suspend fun sendAudio(socket: WebSocket, stop: AtomicBoolean, maxSeconds: Int) {
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw VoiceIssue("当前麦克风不可用，请检查输入设备。")
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(12_800, minimum))
        val samples = ShortArray(3200)
        val frame = ByteArray(samples.size * 2)
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw VoiceIssue("当前麦克风不可用。")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw VoiceIssue("无法启动麦克风。")
            val deadline = android.os.SystemClock.elapsedRealtime() + maxSeconds * 1000L
            while (!stop.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) throw VoiceIssue("录音已中断，请重新录音或继续手动记分。")
                if (count == 0) continue
                for (i in 0 until count) {
                    frame[i * 2] = samples[i].toByte()
                    frame[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                }
                if (socket.queueSize() > 128_000 || !socket.send(frame.toByteString(0, count * 2)))
                    throw VoiceIssue("网络跟不上录音，请重试。")
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

internal class TencentTranscript {
    private val segments = sortedMapOf<Int, String>()
    val text: String get() = segments.values.joinToString("")
    fun accept(message: String): Boolean {
        val json = GameJson.parseToJsonElement(message).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true)
            throw VoiceIssue("腾讯语音识别未完成，请重试。")
        fun add(index: Int, text: String) {
            if (text.length > 4000 || (segments.size >= 256 && index !in segments))
                throw VoiceIssue("转写过长，请分段口述。")
            segments[index] = text
        }
        val result = json["result"] as? JsonObject
        val standard = (result?.get("voice_text_str") as? JsonPrimitive)?.contentOrNull
        if (standard != null) add(result["index"]?.jsonPrimitive?.intOrNull ?: 0, standard)
        else {
            val container = json["sentences"]
            val list = when (container) {
                is JsonArray -> container
                is JsonObject -> container["sentence_list"] as? JsonArray ?: JsonArray(listOf(container))
                else -> emptyList()
            }
            list.forEachIndexed { i, value ->
                val sentence = value as? JsonObject ?: return@forEachIndexed
                val nested = sentence["sentence"] as? JsonObject ?: sentence
                val id = ((sentence["sentence_id"] ?: nested["sentence_id"]) as? JsonPrimitive)?.intOrNull ?: i
                val text = ((sentence["sentence"] as? JsonPrimitive) ?: (nested["text"] as? JsonPrimitive)
                    ?: (nested["voice_text_str"] as? JsonPrimitive))?.contentOrNull
                if (text != null) add(id, text)
            }
        }
        return json["final"]?.jsonPrimitive?.intOrNull == 1
    }
}
