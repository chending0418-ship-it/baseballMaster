package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class DirectoryRequestKind { create, join, invite, leave, roster, member, revoke, security }
@Serializable data class PendingDirectoryRequest(
    val kind: DirectoryRequestKind, val body: JsonObject, val recoveryEpoch: String,
    val teamID: String? = null, val accessEpoch: Long? = null,
    val targetID: String? = null, val localTeamID: String? = null,
    val securityAction: TeamSecurityAction? = null,
) {
    init { appleUUID(body.getValue("operationID").jsonPrimitive.content); appleUUID(recoveryEpoch); teamID?.let(::appleUUID); targetID?.let(::appleUUID); localTeamID?.let(::appleUUID) }
    val id: String get() = body.getValue("operationID").jsonPrimitive.content
    val path: String get() = when (kind) {
        DirectoryRequestKind.create -> "teams"
        DirectoryRequestKind.join -> "invitations/redeem"
        DirectoryRequestKind.invite -> "teams/${appleUUID(requireNotNull(teamID))}/invitations"
        DirectoryRequestKind.leave -> "teams/${appleUUID(requireNotNull(teamID))}/leave"
        DirectoryRequestKind.roster -> "teams/${appleUUID(requireNotNull(teamID))}/roster"
        DirectoryRequestKind.member -> "teams/${appleUUID(requireNotNull(teamID))}/members/${appleUUID(requireNotNull(targetID))}"
        DirectoryRequestKind.revoke -> "teams/${appleUUID(requireNotNull(teamID))}/invitations/${appleUUID(requireNotNull(targetID))}"
        DirectoryRequestKind.security -> securityPath()
    }
    val method: String get() = when (kind) {
        DirectoryRequestKind.roster, DirectoryRequestKind.member -> "PATCH"
        DirectoryRequestKind.revoke -> "DELETE"
        DirectoryRequestKind.security -> if(securityAction==TeamSecurityAction.deviceRemove) "DELETE" else "POST"
        else -> "POST"
    }
    override fun toString() = "PendingDirectoryRequest(kind=$kind, id=$id)"
}
@Serializable data class DirectoryCompletion(val request: PendingDirectoryRequest, val response: JsonObject) {
    override fun toString() = "DirectoryCompletion(kind=${request.kind}, id=${request.id})"
}
@Serializable data class DirectoryFailure(val operationID: String, val status: Int, val code: String)
@Serializable data class ReviewedDirectoryRequest(val request: PendingDirectoryRequest, val failure: DirectoryFailure)
@Serializable data class TeamDirectoryState(
    val deviceID: String? = null, val directory: TeamDirectory? = null,
    val pending: PendingDirectoryRequest? = null, val lastCompletion: DirectoryCompletion? = null,
    val requiresRecoveryReview: Boolean = false,
    // Keep generated recovery/invitation material when a later receipt replaces
    // lastCompletion. Only the encrypted device store may persist this state.
    val retainedSecrets: List<DirectoryCompletion> = emptyList(),
    val profiles: Map<String, CachedTeamRoster> = emptyMap(),
    val bindings: List<SharedTeamBinding> = emptyList(),
    val lastFailure: DirectoryFailure? = null,
    val reviewedRequests: List<ReviewedDirectoryRequest> = emptyList(),
    val matches: Map<String, CachedSharedMatch> = emptyMap(),
    val matchCursors: Map<String, Long> = emptyMap(),
    val matchPending: Map<String, PendingMatchControl> = emptyMap(),
    val matchReceipts: Map<String, CompletedMatchControl> = emptyMap(),
    val pairing: SavedDevicePairing? = null, val outgoingPairing: DevicePairing? = null,
    val recoveryCards: List<SavedManagementCard> = emptyList(),
) {
    override fun toString() = "TeamDirectoryState(pending=${pending?.kind}, requiresRecoveryReview=$requiresRecoveryReview)"
}

/** Shared monitor must cover read/save and network calls across sessions using the same file. */
interface TeamDirectoryStorage {
    val monitor: Any
    fun load(): TeamDirectoryState
    fun save(state: TeamDirectoryState)
}

class TeamDirectorySession(internal val client: TeamDirectoryClient, internal val storage: TeamDirectoryStorage, private val deviceLabel: String) {
    fun snapshot(): TeamDirectoryState = synchronized(storage.monitor) { storage.load() }

    fun refresh(): TeamDirectory = synchronized(storage.monitor) { refreshLocked() }
    private fun refreshLocked(): TeamDirectory {
        var state = storage.load()
        if (state.deviceID == null) {
            val id = client.enroll(deviceLabel)
            state = state.copy(deviceID = id); storage.save(state)
        }
        val remote = try { client.teams() } catch (fault: TeamServiceFault) {
            if (fault.status == 401) storage.save(state.copy(directory = null, profiles = emptyMap(), bindings = emptyList(), retainedSecrets = emptyList(), lastCompletion = null,matches=emptyMap(),matchCursors=emptyMap(),matchReceipts=emptyMap(),recoveryCards=emptyList(),pairing=null,outgoingPairing=null))
            throw fault
        }
        storage.save(applyDirectory(state,remote))
        return remote
    }

    private fun applyDirectory(state: TeamDirectoryState, remote: TeamDirectory): TeamDirectoryState {
        val changed = state.directory?.recoveryEpoch?.let { it != remote.recoveryEpoch } ?: false
        fun accessible(id: String): RemoteTeam? = remote.teams.singleOrNull { it.id.equals(id,true) }?.takeIf { next ->
            !changed && state.directory?.teams?.singleOrNull { it.id.equals(id,true) }?.let { old ->
                old.memberID.equals(next.memberID,true) && old.accessEpoch == next.accessEpoch
            } != false
        }
        fun usableSecret(completion: DirectoryCompletion): Boolean {
            val id = completion.request.teamID ?: completion.response["teamID"]?.jsonPrimitive?.content ?: return false
            val team = accessible(id) ?: return false
            if (team.role != TeamRole.admin) return false
            return when (completion.request.kind) {
                DirectoryRequestKind.invite -> completion.request.accessEpoch == team.accessEpoch
                DirectoryRequestKind.create -> team.recoveryVersion == null || completion.response["recoveryVersion"]?.jsonPrimitive?.longOrNull == team.recoveryVersion
                else -> true
            }
        }
        val legacyCards=state.retainedSecrets.filter { it.request.kind==DirectoryRequestKind.create }.mapNotNull { completion -> runCatching {
            SavedManagementCard(completion.response.getValue("teamID").jsonPrimitive.content,completion.request.body.getValue("name").jsonPrimitive.content,
                completion.request.body.getValue("recoveryToken").jsonPrimitive.content,completion.response.getValue("recoveryVersion").jsonPrimitive.long)
        }.getOrNull() }
        val cards=(state.recoveryCards + legacyCards).groupBy { appleUUID(it.teamID) }.values.map { group -> group.maxBy { it.version } }
        return state.copy(directory = remote, requiresRecoveryReview = state.requiresRecoveryReview || changed,
            pairing=state.pairing?.takeUnless { changed || it.request.status=="accepted" && it.request.teamID?.let { id -> accessible(id)==null }==true },outgoingPairing=state.outgoingPairing?.takeIf { pair -> !changed && pair.teamID?.let { accessible(it) != null } == true },
            recoveryCards=cards.filter { card -> !changed && remote.teams.any { it.id.equals(card.teamID,true) && it.role==TeamRole.admin && (it.recoveryVersion==null || it.recoveryVersion==card.version) } },
            profiles = state.profiles.filter { (id, cache) -> accessible(id)?.let { member -> cache.memberID.equals(member.memberID,true) && cache.accessEpoch == member.accessEpoch && cache.recoveryEpoch == remote.recoveryEpoch } == true },
            bindings = state.bindings.filter { accessible(it.cloudTeamID) != null },
            retainedSecrets = state.retainedSecrets.filter(::usableSecret),
            matches = state.matches.filterValues { cache -> accessible(cache.document.teamID)?.let { team -> cache.memberID.equals(team.memberID,true) && cache.accessEpoch == team.accessEpoch && cache.recoveryEpoch == remote.recoveryEpoch } == true },
            matchCursors = state.matchCursors.filterKeys { accessible(it) != null },
            matchReceipts = state.matchReceipts.filterValues { accessible(it.request.teamID) != null },
            lastCompletion = state.lastCompletion?.takeIf { completion ->
                if (completion.request.kind in setOf(DirectoryRequestKind.create,DirectoryRequestKind.invite)) usableSecret(completion)
                else completion.request.teamID?.let { accessible(it) != null } ?: !changed
            })
    }

    /** Permission list before and after downloading fences a changed/revoked roster. */
    fun roster(teamID: String, force: Boolean = false): SharedTeamProfile = synchronized(storage.monitor) {
        val id = appleUUID(teamID); val directory = refreshLocked(); val state = storage.load()
        check(!state.requiresRecoveryReview) { "请先核对恢复后的球队资料" }
        val member = directory.teams.singleOrNull { it.id.equals(id,true) } ?: error("当前设备无权访问这支球队")
        val cached = state.profiles[id]
        if (!force && cached != null && cached.profile.revision == member.revision) return@synchronized cached.profile
        val detail = try { client.detail(id) } catch (fault: TeamServiceFault) {
            if (fault.status in setOf(401,403,404)) storage.save(state.copy(profiles = state.profiles - id, bindings = state.bindings.filterNot { it.cloudTeamID.equals(id,true) }))
            throw fault
        }
        val final = refreshLocked(); val current = storage.load()
        val finalMember = final.teams.singleOrNull { it.id.equals(id,true) }
        check(!current.requiresRecoveryReview && final.recoveryEpoch == directory.recoveryEpoch && finalMember == member &&
            detail.team.id.equals(id,true) && detail.memberID.equals(member.memberID,true) && detail.role == member.role && detail.team.revision == member.revision) { "名单或权限仍在变化，请重新核对" }
        check(cached == null || detail.team.revision >= cached.profile.revision) { "名单修订倒退，原缓存已保留，请核对服务恢复情况" }
        storage.save(current.copy(profiles = current.profiles + (id to CachedTeamRoster(detail.team, member.memberID, member.accessEpoch, final.recoveryEpoch))))
        detail.team
    }

    fun members(teamID: String): List<SharedTeamMember> = synchronized(storage.monitor) {
        requireAdmin(refreshLocked(), teamID); client.members(teamID)
    }
    fun invitations(teamID: String): List<TeamInvitation> = synchronized(storage.monitor) {
        requireAdmin(refreshLocked(), teamID); client.invitations(teamID)
    }
    private fun requireAdmin(directory: TeamDirectory, teamID: String) {
        check(!storage.load().requiresRecoveryReview) { "请先核对恢复后的球队资料" }
        require(directory.teams.any { it.id.equals(teamID,true) && it.role == TeamRole.admin }) { "当前角色不能管理成员或邀请" }
    }

    fun prepareCreate(name: String, nickname: String): String = prepare(DirectoryRequestKind.create, null) {
        label(name); label(nickname); put("name", name); put("nickname", nickname)
        // Retained in the encrypted completion until a future recovery-card UI consumes it.
        put("recoveryToken", TeamSecrets.random())
    }
    fun prepareCreate(localTeam: Team, nickname: String): String {
        val local = GameJson.decodeFromJsonElement<Team>(GameJson.encodeToJsonElement(localTeam))
        val seed = TeamRosterSeed.fromLocal(local)
        return prepare(DirectoryRequestKind.create, null, localTeamID = appleUUID(local.id)) {
            check(storage.load().bindings.none { it.localTeamID.equals(local.id,true) }) { "本机球队已绑定共享球队，不会重复创建" }
            teamText(local.name,true); label(nickname); put("name",local.name); put("nickname",nickname)
            put("profile",TeamDirectoryJson.format.encodeToJsonElement(seed)); put("recoveryToken",TeamSecrets.random())
        }
    }
    fun prepareJoin(token: String, nickname: String): String = prepare(DirectoryRequestKind.join, null) {
        label(nickname); put("token", TeamSecrets.validate(token)); put("nickname", nickname)
    }
    fun prepareInvite(teamID: String, role: TeamRole): String = prepare(DirectoryRequestKind.invite, appleUUID(teamID)) {
        require(role != TeamRole.admin) { "邀请只允许记录员或成员" }
        put("token", TeamSecrets.random()); put("role", role.name)
    }
    fun prepareLeave(teamID: String): String = prepare(DirectoryRequestKind.leave, appleUUID(teamID)) { }

    fun prepareProfile(teamID: String, baseRevision: Long, name: String, shortName: String, city: String): String =
        prepare(DirectoryRequestKind.roster,appleUUID(teamID)) {
            requireAdmin(requireNotNull(storage.load().directory),teamID); checkedProfile(teamID,baseRevision)
            teamText(name,true); teamText(shortName,true); teamText(city)
            put("kind","profile"); put("baseRevision",baseRevision); put("name",name); put("shortName",shortName); put("city",city)
        }
    fun preparePlayer(teamID: String, baseRevision: Long, player: SharedRosterPlayer): String = prepare(DirectoryRequestKind.roster,appleUUID(teamID)) {
        val state = storage.load(); val member = state.directory!!.teams.single { it.id.equals(teamID,true) }
        require(member.role in setOf(TeamRole.admin,TeamRole.scorer)) { "当前角色不能修改名单" }
        val profile = checkedProfile(teamID,baseRevision)
        val old = profile.players.singleOrNull { it.id.equals(player.id,true) }
        require(old == null || old.sourceID.equals(player.sourceID,true)) { "球员来源身份不能更换" }
        require(profile.players.none { !it.id.equals(player.id,true) && it.sourceID.equals(player.sourceID,true) }) { "该来源球员已有档案，请修改原档案" }
        require(old != null || player.archived != true) { "新球员不能直接停用" }
        if ((old?.archived == true) != (player.archived == true)) require(member.role == TeamRole.admin) { "只有管理员可以停用或恢复球员" }
        put("kind","player"); put("baseRevision",baseRevision)
        put("player",TeamDirectoryJson.format.encodeToJsonElement(player.copy(id=appleUUID(player.id),sourceID=appleUUID(player.sourceID),archived=player.archived == true)))
    }
    private fun checkedProfile(teamID: String, baseRevision: Long): SharedTeamProfile {
        val profile = storage.load().profiles[appleUUID(teamID)]?.profile ?: error("请先读取共享名单")
        require(profile.revision == baseRevision) { "本机名单预览已更新，请核对后再保存" }
        return profile
    }
    fun prepareMember(teamID: String, member: SharedTeamMember, role: TeamRole, remove: Boolean = false): String =
        prepare(DirectoryRequestKind.member,appleUUID(teamID),targetID=appleUUID(member.id)) {
            requireAdmin(requireNotNull(storage.load().directory),teamID)
            put("baseRevision",member.revision); put("role",role.name); if (remove) put("active",false)
        }
    fun prepareRevoke(teamID: String, invitationID: String): String = prepare(DirectoryRequestKind.revoke,appleUUID(teamID),targetID=appleUUID(invitationID)) {
        requireAdmin(requireNotNull(storage.load().directory),teamID)
    }

    internal fun prepare(kind: DirectoryRequestKind, teamID: String?, targetID: String? = null, localTeamID: String? = null, securityAction: TeamSecurityAction? = null, fields: JsonObjectBuilder.() -> Unit): String = synchronized(storage.monitor) {
        val state = storage.load()
        check(state.pending == null) { "上一笔操作结果尚未确认，请先续取；不能换内容重试" }
        check(!state.requiresRecoveryReview && state.deviceID != null && state.directory != null) { "请先刷新并核对球队资料" }
        val directory = requireNotNull(state.directory)
        val team = teamID?.let { id -> directory.teams.singleOrNull { it.id.equals(id, true) } ?: error("请先核对球队成员关系") }
        if (kind == DirectoryRequestKind.invite) require(team?.role == TeamRole.admin) { "当前角色不能邀请成员" }
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject { put("operationID", id); fields() }
        storage.save(state.copy(pending = PendingDirectoryRequest(kind, body, directory.recoveryEpoch, teamID, team?.accessEpoch,targetID,localTeamID,securityAction), lastFailure=null))
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
        val response = try { client.send(pending) } catch (fault: TeamServiceFault) {
            storage.save(state.copy(lastFailure=DirectoryFailure(pending.id,fault.status,fault.code)))
            throw fault
        }
        val completed = DirectoryCompletion(pending, response)
        // If this save fails, the original pending request remains the only retry.
        val secrets = if (pending.kind in setOf(DirectoryRequestKind.create, DirectoryRequestKind.invite)) state.retainedSecrets + completed else state.retainedSecrets
        var profiles = state.profiles
        if (pending.kind == DirectoryRequestKind.roster) {
            val detail = TeamDirectoryJson.format.decodeFromJsonElement<SharedTeamDetail>(response)
            val id = appleUUID(detail.team.id); val previous = profiles[id]
            if (previous == null || detail.team.revision >= previous.profile.revision) profiles = profiles + (id to CachedTeamRoster(detail.team,detail.memberID,pending.accessEpoch,pending.recoveryEpoch))
        }
        val bindings = if (pending.localTeamID != null && pending.kind == DirectoryRequestKind.create) state.bindings + SharedTeamBinding(pending.localTeamID,appleUUID(response.getValue("teamID").jsonPrimitive.content)) else state.bindings
        var next=state.copy(pending = null, lastCompletion = completed, retainedSecrets = secrets, profiles=profiles,bindings=bindings,lastFailure=null)
        if(pending.kind==DirectoryRequestKind.create) {
            val token=pending.body["recoveryToken"]?.jsonPrimitive?.content; val version=response["recoveryVersion"]?.jsonPrimitive?.longOrNull
            if(token!=null && version!=null && version>0) next=next.copy(recoveryCards=next.recoveryCards + SavedManagementCard(appleUUID(response.getValue("teamID").jsonPrimitive.content),pending.body.getValue("name").jsonPrimitive.content.trim(),token,version))
        }
        if(pending.kind==DirectoryRequestKind.security) next=next.applyingSecurity(pending,response)
        storage.save(next)
        completed
    }

    /** Only a definite version/rule rejection may be parked for explicit re-edit.
     * Unknown transport outcomes, expired authorization and operation-ID conflicts
     * stay pending; this method never retries or chooses a new base revision.
     */
    fun keepRejectedAsDraft(operationID: String) = synchronized(storage.monitor) {
        val state = storage.load(); val pending = requireNotNull(state.pending); val failure = requireNotNull(state.lastFailure)
        require(pending.id == operationID && failure.operationID == operationID && failure.status == 409 &&
            failure.code in setOf("roster_conflict","revision_conflict","identity_conflict","admin_limit","last_admin","last_admin_device","capacity")) { "这笔操作结果尚未明确，不能更换编号" }
        storage.save(state.copy(pending=null,lastFailure=null,reviewedRequests=state.reviewedRequests + ReviewedDirectoryRequest(pending,failure)))
    }

    private fun label(value: String) = require(value.isNotBlank() && value.length <= 80 && value.none { it.code < 32 || it.code == 127 }) { "请核对名称或成员称呼" }
}
