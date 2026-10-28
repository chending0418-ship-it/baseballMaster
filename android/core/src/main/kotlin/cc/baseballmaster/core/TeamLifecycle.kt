package cc.baseballmaster.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class DissolvedTeam(val id: String, val name: String, val deletedAt: Long, val expiresAt: Long) {
    init {appleUUID(id);require(deletedAt>=0 && expiresAt>deletedAt)}
}
@Serializable data class TeamAuditEvent(val id: Long, val action: String, val objectID: String, val occurredAt: Long, val nickname: String, val deviceName: String) {
    init {require(id>0 && occurredAt>=0 && Regex("[A-Za-z:]{1,60}").matches(action))}
    override fun toString()="TeamAuditEvent(id=$id, action=$action)"
}
@Serializable data class TeamAuditPage(val events: List<TeamAuditEvent>, val before: Long, val hasMore: Boolean, val actions: List<String>) {
    init {require(before>=0 && events.size<=50 && events.map {it.id}.distinct().size==events.size && events.zipWithNext().all {(a,b)->a.id>b.id})}
    override fun toString()="TeamAuditPage(count=${events.size}, before=$before, hasMore=$hasMore)"
}

fun TeamDirectorySession.dissolvedTeams(): List<DissolvedTeam> = synchronized(storage.monitor) {
    refresh();check(!storage.load().requiresRecoveryReview) {"请先核对恢复后的资料"}
    val teams=client.transport.request("GET","dissolved-teams",null).getValue("teams").jsonArray.map {TeamDirectoryJson.format.decodeFromJsonElement<DissolvedTeam>(it)}
    require(teams.map {appleUUID(it.id)}.distinct().size==teams.size)
    teams
}
fun TeamDirectorySession.prepareTeamDissolution(profile: SharedTeamProfile, name: String, confirmed: Boolean): String =
    prepare(DirectoryRequestKind.security,appleUUID(profile.id),securityAction=TeamSecurityAction.teamDissolve) {
        require(confirmed && name==profile.name) {"请核对并输入完整球队名称，确认解散全队"}
        val state=storage.load();val member=state.directory!!.teams.singleOrNull {it.id.equals(profile.id,true)}
        require(member?.role==TeamRole.admin && state.profiles[appleUUID(profile.id)]?.profile==profile) {"请先核对当前球队资料与管理员身份"}
        put("name",name);put("baseRevision",profile.revision);put("confirm",true)
    }
fun TeamDirectorySession.prepareTeamRestoration(team: DissolvedTeam, confirmed: Boolean): String =
    prepare(DirectoryRequestKind.security,null,targetID=appleUUID(team.id),securityAction=TeamSecurityAction.teamRestore) {
        require(confirmed) {"请明确确认：原成员、邀请、观看链接和恢复卡不会恢复"}
        put("name",team.name);put("confirm",true)
    }

fun TeamDirectorySession.audit(teamID: String, from: Long = 0, action: String = "", before: Long? = null): TeamAuditPage = synchronized(storage.monitor) {
    require(from in 0..9_007_199_254_740_991 && (before==null || before in 0..9_007_199_254_740_991) && Regex("[A-Za-z:]{0,60}").matches(action))
    val id=appleUUID(teamID);val directory=refresh();val member=directory.teams.singleOrNull {it.id.equals(id,true)}
    check(!storage.load().requiresRecoveryReview && member?.role==TeamRole.admin) {"仅管理员可查看球队操作记录"}
    val encoded=URLEncoder.encode(action,StandardCharsets.UTF_8.name())
    val path="teams/$id/audit?from=$from&action=$encoded" + (before?.let {"&before=$it"} ?: "")
    val page=TeamDirectoryJson.format.decodeFromJsonElement<TeamAuditPage>(client.transport.request("GET",path,null))
    val final=refresh();val current=storage.load()
    check(!current.requiresRecoveryReview && final.recoveryEpoch==directory.recoveryEpoch && final.teams.singleOrNull {it.id.equals(id,true)}==member) {"读取期间球队权限已变化，请重新核对"}
    require(page.events.all {it.occurredAt>=from && (action.isEmpty() || it.action==action) && (before==null || it.id<before)})
    require(!page.hasMore || page.events.isNotEmpty() && page.before==page.events.last().id)
    page
}

fun teamAuditTitle(action: String): String = mapOf(
    "teamCreated" to "创建球队","teamProfileUpdated" to "修改球队资料","playerUpdated" to "修改球员资料","playerArchived" to "停用球员",
    "invitationCreated" to "邀请成员","invitationRedeemed" to "加入球队","invitationRevoked" to "撤销邀请",
    "memberLeft" to "退出球队","memberRemoved" to "移除成员","memberRoleChanged" to "调整成员角色",
    "devicePairingApproved" to "确认设备配对","devicePaired" to "完成设备配对","devicePairingCancelled" to "取消设备配对","teamDeviceRemoved" to "移除设备",
    "recoveryCodeInvalidated" to "停用旧恢复卡","recoveryCodeRotated" to "更换恢复卡","managementRecovered" to "恢复球队管理权",
    "gameCreated" to "共享比赛","gameUpdated" to "更新球队比赛","gameDeleted" to "删除比赛","gameRestored" to "恢复比赛",
    "historyImported" to "导入历史比赛","playerLinksUpdated" to "修改球员对应","handoffProposed" to "发起记分交接","handoffAccepted" to "接手记分",
    "handoffCancelled" to "取消记分交接","handoffForced" to "管理员接管记分","accessChanged" to "调整历史观看方式",
    "handoff:propose" to "发起记分交接","handoff:accept" to "接手记分","handoff:cancel" to "取消记分交接","handoff:force" to "管理员接管记分",
    "historicalAccessChanged" to "调整历史观看方式","writerAuthorizationRevoked" to "停用原设备记分权","handoffTargetRemoved" to "撤销接手设备",
    "viewerAuthorizationChanged" to "调整指定观看者","viewerInvited" to "邀请观看者","liveOpened" to "开启实时直播","liveClosed" to "关闭实时直播",
    "draftSubmitted" to "提交未同步记录","draftResolved" to "保存记录核对说明","draftSupplemented" to "完成补录核对","draftDiscarded" to "核对后保留球队记录",
    "teamDissolved" to "解散球队","teamRestored" to "恢复球队档案","gameClosedByDissolution" to "随球队解散关闭比赛","gameRestoredWithTeam" to "随球队恢复比赛",
    "deviceCredentialRotated" to "更新设备授权","archiveExported" to "导出球队档案","archiveRestored" to "恢复球队档案",
)[action] ?: "球队操作记录"
