package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class DirectoryRequestKind { create, join, invite, leave }
@Serializable data class PendingDirectoryRequest(
    val kind: DirectoryRequestKind, val body: JsonObject, val recoveryEpoch: String,
    val teamID: String? = null, val accessEpoch: Long? = null,
) {
    init { appleUUID(body.getValue("operationID").jsonPrimitive.content); appleUUID(recoveryEpoch); teamID?.let(::appleUUID) }
    val id: String get() = body.getValue("operationID").jsonPrimitive.content
    val path: String get() = when (kind) {
        DirectoryRequestKind.create -> "teams"
        DirectoryRequestKind.join -> "invitations/redeem"
        DirectoryRequestKind.invite -> "teams/${appleUUID(requireNotNull(teamID))}/invitations"
        DirectoryRequestKind.leave -> "teams/${appleUUID(requireNotNull(teamID))}/leave"
    }
    override fun toString() = "PendingDirectoryRequest(kind=$kind, id=$id)"
}
@Serializable data class DirectoryCompletion(val request: PendingDirectoryRequest, val response: JsonObject) {
    override fun toString() = "DirectoryCompletion(kind=${request.kind}, id=${request.id})"
}
@Serializable data class TeamDirectoryState(
    val deviceID: String? = null, val directory: TeamDirectory? = null,
    val pending: PendingDirectoryRequest? = null, val lastCompletion: DirectoryCompletion? = null,
    val requiresRecoveryReview: Boolean = false,
    // Keep generated recovery/invitation material when a later receipt replaces
    // lastCompletion. Only the encrypted device store may persist this state.
    val retainedSecrets: List<DirectoryCompletion> = emptyList(),
) {
    override fun toString() = "TeamDirectoryState(pending=${pending?.kind}, requiresRecoveryReview=$requiresRecoveryReview)"
}

/** Shared monitor must cover read/save and network calls across sessions using the same file. */
interface TeamDirectoryStorage {
    val monitor: Any
    fun load(): TeamDirectoryState
    fun save(state: TeamDirectoryState)
}

class TeamDirectorySession(private val client: TeamDirectoryClient, private val storage: TeamDirectoryStorage, private val deviceLabel: String) {
    fun snapshot(): TeamDirectoryState = synchronized(storage.monitor) { storage.load() }

    fun refresh(): TeamDirectory = synchronized(storage.monitor) { refreshLocked() }
    private fun refreshLocked(): TeamDirectory {
        var state = storage.load()
        if (state.deviceID == null) {
            val id = client.enroll(deviceLabel)
            state = state.copy(deviceID = id); storage.save(state)
        }
        val remote = client.teams()
        val changed = state.directory?.recoveryEpoch?.let { it != remote.recoveryEpoch } ?: false
        storage.save(state.copy(directory = remote, requiresRecoveryReview = state.requiresRecoveryReview || changed))
        return remote
    }

    fun prepareCreate(name: String, nickname: String): String = prepare(DirectoryRequestKind.create, null) {
        label(name); label(nickname); put("name", name); put("nickname", nickname)
        // Retained in the encrypted completion until a future recovery-card UI consumes it.
        put("recoveryToken", TeamSecrets.random())
    }
    fun prepareJoin(token: String, nickname: String): String = prepare(DirectoryRequestKind.join, null) {
        label(nickname); put("token", TeamSecrets.validate(token)); put("nickname", nickname)
    }
    fun prepareInvite(teamID: String, role: TeamRole): String = prepare(DirectoryRequestKind.invite, appleUUID(teamID)) {
        require(role != TeamRole.admin) { "邀请只允许记录员或成员" }
        put("token", TeamSecrets.random()); put("role", role.name)
    }
    fun prepareLeave(teamID: String): String = prepare(DirectoryRequestKind.leave, appleUUID(teamID)) { }

    private fun prepare(kind: DirectoryRequestKind, teamID: String?, fields: JsonObjectBuilder.() -> Unit): String = synchronized(storage.monitor) {
        val state = storage.load()
        check(state.pending == null) { "上一笔操作结果尚未确认，请先续取；不能换内容重试" }
        check(!state.requiresRecoveryReview && state.deviceID != null && state.directory != null) { "请先刷新并核对球队资料" }
        val directory = requireNotNull(state.directory)
        val team = teamID?.let { id -> directory.teams.singleOrNull { it.id.equals(id, true) } ?: error("请先核对球队成员关系") }
        if (kind == DirectoryRequestKind.invite) require(team?.role == TeamRole.admin) { "当前角色不能邀请成员" }
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject { put("operationID", id); fields() }
        storage.save(state.copy(pending = PendingDirectoryRequest(kind, body, directory.recoveryEpoch, teamID, team?.accessEpoch)))
        id
    }

    /** An explicit call retries exactly the saved request. No background resubmission. */
    fun sendPending(): DirectoryCompletion = synchronized(storage.monitor) {
        val pending = storage.load().pending ?: error("没有待处理操作")
        val remote = refreshLocked()
        val state = storage.load()
        check(!state.requiresRecoveryReview && pending.recoveryEpoch == remote.recoveryEpoch) { "服务恢复代次已变化，旧操作已保留，请先核对" }
        val remoteTeam = remote.teams.singleOrNull { it.id.equals(pending.teamID, true) }
        check(remoteTeam == null || remoteTeam.accessEpoch == pending.accessEpoch) { "球队访问代次已变化，旧操作已保留，请先核对" }
        val response = client.send(pending)
        val completed = DirectoryCompletion(pending, response)
        // If this save fails, the original pending request remains the only retry.
        val secrets = if (pending.kind in setOf(DirectoryRequestKind.create, DirectoryRequestKind.invite)) state.retainedSecrets + completed else state.retainedSecrets
        storage.save(state.copy(pending = null, lastCompletion = completed, retainedSecrets = secrets))
        completed
    }

    private fun label(value: String) = require(value.isNotBlank() && value.length <= 80 && value.none { it.code < 32 || it.code == 127 }) { "请核对名称或成员称呼" }
}
