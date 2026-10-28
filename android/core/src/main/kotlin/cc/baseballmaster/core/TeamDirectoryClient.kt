package cc.baseballmaster.core

import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable enum class TeamRole { admin, scorer, member }
@Serializable data class RemoteTeam(
    val id: String, val name: String, val revision: Long, val role: TeamRole,
    val memberID: String, val accessEpoch: Long? = null, val recoveryVersion: Long? = null,
) {
    init { appleUUID(id); appleUUID(memberID); require(name.isNotBlank() && revision > 0 && (accessEpoch == null || accessEpoch > 0)) }
}
@Serializable data class TeamDirectory(val teams: List<RemoteTeam>, val recoveryEpoch: String) {
    init { appleUUID(recoveryEpoch); require(teams.map { it.id.lowercase() }.distinct().size == teams.size) }
}
@Serializable data class TeamMembership(val teamID: String, val memberID: String, val role: TeamRole) {
    init { appleUUID(teamID); appleUUID(memberID) }
}
@Serializable data class TeamInvitation(val invitationID: String, val expiresAt: Long, val role: TeamRole) {
    init { appleUUID(invitationID); require(expiresAt > 0 && role != TeamRole.admin) }
}
@Serializable data class TeamInvitationPreview(val teamID: String, val teamName: String, val role: TeamRole, val expiresAt: Long) {
    init { appleUUID(teamID); require(teamName.isNotBlank() && role != TeamRole.admin && expiresAt > 0) }
}

/** Stable protocol codes, never a server body, URL, token or server-supplied message. */
class TeamServiceFault(val status: Int, val code: String) : IOException(when (status) {
    401 -> "设备授权已失效，请重新授权"
    403 -> "当前角色不能执行此操作"
    404 -> "球队暂不可用，或当前设备无权访问"
    409 -> if (code == "admin_limit") "每支球队最多三名管理员" else "资料已有变化，请核对后重试；本机操作已保留"
    410 -> "邀请或操作已失效，请重新核对；本机操作已保留"
    426 -> "请更新 App 后重试"
    429 -> "操作过于频繁，请稍后重试"
    else -> "球队服务暂时不可用；本机操作已保留"
})

fun interface TeamCredentialProvider { fun token(): String }
fun interface TeamTransport { fun request(method: String, path: String, body: JsonObject?): JsonObject }

object TeamSecrets {
    fun validate(value: String): String {
        require(Regex("[A-Za-z0-9_-]{43}").matches(value)) { "授权资料格式无效" }
        val decoded = Base64.getUrlDecoder().decode(value)
        require(decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) { "授权资料格式无效" }
        return value
    }
    fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
}

/** No default endpoint or startup connection. HTTP is only an explicit loopback test option.
 * Synchronous: Android callers must run this client on Dispatchers.IO.
 */
class TeamHttpTransport(baseURL: String, private val credentials: TeamCredentialProvider, allowLoopbackHttp: Boolean = false) : TeamTransport {
    private val base = URI(baseURL)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    init {
        require(base.isAbsolute && base.host != null && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null &&
            base.rawPath in setOf("/api/v3", "/api/v3/") &&
            (base.scheme == "https" || allowLoopbackHttp && base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "[::1]"))) { "球队服务地址无效" }
    }
    override fun request(method: String, path: String, body: JsonObject?): JsonObject {
        val cursorPath = Regex("teams/[a-f0-9-]{36}/games\\?cursor=[0-9]{1,10}").matches(path)
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE") && (Regex("[a-z0-9/-]+").matches(path) || method == "GET" && cursorPath) && !path.startsWith('/') && !path.contains("//"))
        // Saving the device secret must succeed before opening any connection.
        val token = TeamSecrets.validate(credentials.token())
        try {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
            require(bytes == null || bytes.size <= 18 * 1_048_576) { "请求过大，未发送" }
            val request = Request.Builder().url(base.toASCIIString().trimEnd('/') + "/" + path)
                .header("Authorization", "Bearer $token").header("X-BM-Protocol", "1")
                .header("Content-Type", "application/json").header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
                .method(method, bytes?.toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).execute().use { reply ->
            val status = reply.code
            // Do not follow redirects, reuse cookies, or include raw response text in errors.
            if (status in 300..399) throw TeamServiceFault(status, "redirect_refused")
            val responseBytes = reply.body?.byteStream()?.use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= 18 * 1_048_576); output.write(buffer, 0, n) }
                output.toByteArray()
            } ?: byteArrayOf()
            val response = runCatching { AppleWireJson.parse(responseBytes).jsonObject }.getOrNull()
            if (status !in 200..299) {
                val code = response?.get("error")?.let { runCatching { it.jsonObject["code"]?.jsonPrimitive?.content }.getOrNull() }
                throw TeamServiceFault(status, code?.takeIf { Regex("[a-z_]{1,64}").matches(it) } ?: "service_error")
            }
            response ?: throw TeamServiceFault(502, "invalid_response")
            }
        } catch (error: TeamServiceFault) { throw error }
        catch (_: Exception) { throw TeamServiceFault(503, "transport_or_data_error") }
    }
}

object TeamDirectoryJson { val format = Json { ignoreUnknownKeys = true; encodeDefaults = true } }

class TeamDirectoryClient(internal val transport: TeamTransport) {
    @Serializable private data class Members(val members: List<SharedTeamMember>)
    @Serializable private data class Invitations(val invitations: List<TeamInvitation>)
    fun enroll(label: String): String {
        require(label.isNotBlank() && label.length <= 80 && label.none { it.code < 32 || it.code == 127 })
        val response = transport.request("POST", "devices", buildJsonObject { put("label", label) })
        require(response["protocol"]?.jsonPrimitive?.int == 1) { "请更新 App 后重试" }
        return appleUUID(response.getValue("deviceID").jsonPrimitive.content)
    }
    fun teams(): TeamDirectory = TeamDirectoryJson.format.decodeFromJsonElement(transport.request("GET", "teams", null))
    fun preview(token: String): TeamInvitationPreview = TeamDirectoryJson.format.decodeFromJsonElement(
        transport.request("POST", "invitations/preview", buildJsonObject { put("token", TeamSecrets.validate(token)) }))
    fun detail(teamID: String): SharedTeamDetail = TeamDirectoryJson.format.decodeFromJsonElement(
        transport.request("GET", "teams/${appleUUID(teamID)}", null))
    fun members(teamID: String): List<SharedTeamMember> = TeamDirectoryJson.format.decodeFromJsonElement<Members>(
        transport.request("GET", "teams/${appleUUID(teamID)}/members", null)).members.also { rows ->
        require(rows.map { appleUUID(it.id) }.distinct().size == rows.size)
    }
    fun invitations(teamID: String): List<TeamInvitation> = TeamDirectoryJson.format.decodeFromJsonElement<Invitations>(
        transport.request("GET", "teams/${appleUUID(teamID)}/invitations", null)).invitations
    internal fun send(pending: PendingDirectoryRequest): JsonObject {
        val response = transport.request(pending.method, pending.path, pending.body)
        when (pending.kind) {
            DirectoryRequestKind.create, DirectoryRequestKind.join -> TeamDirectoryJson.format.decodeFromJsonElement<TeamMembership>(response)
            DirectoryRequestKind.invite -> TeamDirectoryJson.format.decodeFromJsonElement<TeamInvitation>(response)
            DirectoryRequestKind.leave -> require(response["left"]?.jsonPrimitive?.boolean == true)
            DirectoryRequestKind.roster -> TeamDirectoryJson.format.decodeFromJsonElement<SharedTeamDetail>(response).also { require(it.team.id.equals(pending.teamID,true)) }
            DirectoryRequestKind.member -> require(appleUUID(response.getValue("memberID").jsonPrimitive.content).equals(pending.targetID,true))
            DirectoryRequestKind.revoke -> require(response["revoked"]?.jsonPrimitive?.boolean == true)
        }
        return response
    }
}
