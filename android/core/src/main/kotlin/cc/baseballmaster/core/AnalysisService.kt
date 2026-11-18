package cc.baseballmaster.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class AnalysisScopeLimit : IllegalArgumentException("本次比赛资料超出分析上限，请缩小统计范围。")
class AnalysisAuthorizationRequired : IllegalStateException("请先在球队共享页面核对设备授权。")
class AnalysisInvalidReport : IllegalStateException("分析报告暂不可读，请重试。")

@Serializable
data class AnalysisReport(val overview: String, val situations: List<String>, val players: List<Evaluation>, val mvp: Mvp? = null) {
    @Serializable data class Evaluation(val id: String, val evaluation: String)
    @Serializable data class Mvp(val id: String, val reason: String)

    fun verify(input: AnalysisInput): AnalysisReport {
        val ids = input.players.mapTo(mutableSetOf()) { it.id }
        val received = players.map { it.id }
        val valid = overview.isNotBlank() && overview.length <= 1800 && situations.size <= 6 &&
            situations.all { it.isNotBlank() && it.length <= 600 } && received.size == ids.size && received.toSet() == ids &&
            players.all { it.evaluation.isNotBlank() && it.evaluation.length <= 360 } &&
            (mvp == null || mvp.id in ids && mvp.reason.isNotBlank() && mvp.reason.length <= 600)
        if (!valid) throw AnalysisInvalidReport()
        return this
    }
}

@Serializable
enum class AnalysisState { pending, ready, failed }

@Serializable
data class AnalysisStatus(val state: AnalysisState, val report: AnalysisReport? = null)

@Serializable
private data class AnalysisStart(val requestID: String, val input: AnalysisInput)

class AnalysisRequest internal constructor(val id: String, internal val body: JsonObject)

@OptIn(ExperimentalSerializationApi::class)
private val analysisJson = Json { explicitNulls = false; ignoreUnknownKeys = true }

/** Validates the immutable snapshot before device registration or a network request. */
fun analysisRequest(input: AnalysisInput, requestID: String): AnalysisRequest {
    if (input.games.size !in 1..1000 || input.players.size > 200) throw AnalysisScopeLimit()
    val id = appleUUID(requestID)
    val body = analysisJson.encodeToJsonElement(AnalysisStart(id, input)) as JsonObject
    if (body.toString().toByteArray(Charsets.UTF_8).size > 256 * 1024) throw AnalysisScopeLimit()
    return AnalysisRequest(id, body)
}

/** The same stable authorization used for team operations can analyze without joining a team. */
fun TeamDirectorySession.analysisDevice(): String = synchronized(storage.monitor) {
    val current = snapshot()
    if (current.credentialExpired || current.requiresRecoveryReview) throw AnalysisAuthorizationRequired()
    current.deviceID ?: client.enroll(deviceLabel).also { id -> storage.save(current.copy(deviceID = id)) }
}

fun TeamDirectorySession.startAnalysis(deviceID: String, request: AnalysisRequest): AnalysisStatus =
    analysisRequest(deviceID, "POST", "game-analysis", request.body)

fun TeamDirectorySession.analysisStatus(deviceID: String, requestID: String): AnalysisStatus =
    analysisRequest(deviceID, "GET", "game-analysis/${appleUUID(requestID)}")

private fun TeamDirectorySession.analysisRequest(deviceID: String, method: String, path: String, body: JsonObject? = null): AnalysisStatus =
    synchronized(storage.monitor) {
        val current = snapshot()
        if (current.deviceID != deviceID || current.credentialExpired || current.requiresRecoveryReview) throw AnalysisAuthorizationRequired()
        val response = client.transport.request(method, path, body)
        try {
            analysisJson.decodeFromJsonElement(response)
        } catch (_: SerializationException) {
            throw AnalysisInvalidReport()
        }
    }
