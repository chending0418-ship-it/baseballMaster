package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val TeamServiceFault.requiresRecoveryReview: Boolean
    get()=status==409 && code in setOf("recovery_review","recovery_changed")

/** Old requests are evidence for manual review, never an active retry queue. */
@Serializable data class PreservedRecoveryWork(
    val id: String, val previousEpoch: String?, val recoveryEpoch: String, val savedAt: Long,
    val directoryRequest: PendingDirectoryRequest? = null,
    val matchRequests: List<PendingMatchControl> = emptyList(), val matches: List<CachedSharedMatch> = emptyList(),
) {
    init {appleUUID(id);previousEpoch?.let(::appleUUID);appleUUID(recoveryEpoch);require(savedAt>0)}
    override fun toString()="PreservedRecoveryWork(id=$id, matches=${matches.size}, controls=${matchRequests.size})"
}

internal fun TeamDirectoryState.preservingRecoveryWork(epoch: String): TeamDirectoryState {
    val request=pending?.takeUnless {p -> recoveryWork.any {it.directoryRequest?.id==p.id}}
    val controls=matchPending.values.filterNot {p -> recoveryWork.any {a -> a.matchRequests.any {it.id==p.id}}}
    val authored=matches.values.filter {it.document.writerDeviceID==deviceID || it.document.id in matchPending}
        .filterNot {c -> recoveryWork.any {a -> a.matches.any {it.document.id==c.document.id && it.document.revision==c.document.revision && it.document.digest==c.document.digest}}}
    if(request==null && controls.isEmpty() && authored.isEmpty()) return this
    val saved=PreservedRecoveryWork(UUID.randomUUID().toString(),directory?.recoveryEpoch,appleUUID(epoch),System.currentTimeMillis(),request,controls,authored)
    return copy(recoveryWork=recoveryWork+saved)
}

/** Ports the explicit ServiceRecoveryReviewView / reconcileAfterRecovery flow.
 * No pre-restore request is sent. All downloads and the acknowledgement must
 * succeed before the local gate is cleared, including after a lost response.
 */
fun TeamDirectorySession.reconcileAfterRecovery(confirmed: Boolean): TeamDirectory = synchronized(storage.monitor) {
    val original=storage.load()
    require(confirmed && original.requiresRecoveryReview && original.deviceID!=null) {"请先明确确认保留本机记录并核对恢复后的资料"}
    val remote=client.teams()
    val profiles=mutableMapOf<String,CachedTeamRoster>()
    val documents=mutableMapOf<String,CachedSharedMatch>();val cursors=mutableMapOf<String,Long>()
    for(team in remote.teams) {
        val id=appleUUID(team.id);val detail=client.detail(id)
        check(detail.team.id.equals(id,true) && detail.memberID.equals(team.memberID,true) && detail.role==team.role && detail.team.revision==team.revision) {"球队或成员关系仍在变化，请重新核对"}
        profiles[id]=CachedTeamRoster(detail.team,team.memberID,team.accessEpoch,remote.recoveryEpoch)
        var cursor=0L;var finished=false
        repeat(128) {
            if(finished) return@repeat
            val page=client.transport.request("GET","teams/$id/games?cursor=$cursor",null)
            check(page.getValue("recoveryEpoch").jsonPrimitive.content==remote.recoveryEpoch) {"服务恢复代次仍在变化，请重新核对"}
            val next=page.getValue("cursor").jsonPrimitive.long;val more=page.getValue("hasMore").jsonPrimitive.boolean
            check(next>=cursor && (!more || next>cursor)) {"比赛游标无效，原记录已保留"}
            for(raw in page.getValue("games").jsonArray) {
                val metadata=SharedMatchDocument(raw.jsonObject);require(metadata.teamID==id)
                val document=if(metadata.deleted) metadata else SharedMatchDocument(client.transport.request("GET","teams/$id/games/${metadata.id}",null))
                check(document.id==metadata.id && document.teamID==id && document.revision>=metadata.revision && document.writerEpoch>=metadata.writerEpoch && (document.deleted || document.payload!=null)) {"完整比赛仍在变化，请重新核对"}
                val previous=documents[document.id]
                if(previous==null || document.revision>=previous.document.revision) documents[document.id]=CachedSharedMatch(document,team.memberID,team.accessEpoch,remote.recoveryEpoch)
            }
            cursor=next;finished=!more
        }
        check(finished) {"比赛数量超过本次核对上限，原记录已保留"}
        cursors[id]=cursor
    }
    check(client.teams()==remote) {"核对期间球队授权发生变化，请重新核对"}
    // Persist the old work before acknowledging the service. A lost acknowledgement
    // or failed final save still leaves the local mutation gate closed.
    val preserved=original.preservingRecoveryWork(remote.recoveryEpoch)
    storage.save(preserved)
    val reply=client.transport.request("POST","devices/recovery-review",buildJsonObject {put("recoveryEpoch",remote.recoveryEpoch)})
    check(reply["reviewed"]?.jsonPrimitive?.boolean==true && reply["recoveryEpoch"]?.jsonPrimitive?.content==remote.recoveryEpoch) {"服务尚未确认核对结果，原记录已保留"}
    check(client.teams()==remote) {"服务资料再次变化，请重新核对"}
    storage.save(preserved.copy(directory=remote,profiles=profiles,matches=documents,matchCursors=cursors,
        pending=null,lastFailure=null,lastCompletion=null,retainedSecrets=emptyList(),fixedInvitations=emptyMap(),
        matchPending=emptyMap(),matchReceipts=emptyMap(),pairing=null,outgoingPairing=null,recoveryCards=emptyList(),
        bindings=preserved.bindings.filter {binding ->
            val prior=preserved.directory?.teams?.singleOrNull {it.id.equals(binding.cloudTeamID,true)}
            prior!=null && remote.teams.any {it.id.equals(binding.cloudTeamID,true) && it.memberID==prior.memberID && it.accessEpoch==prior.accessEpoch}
        },requiresRecoveryReview=false))
    remote
}
