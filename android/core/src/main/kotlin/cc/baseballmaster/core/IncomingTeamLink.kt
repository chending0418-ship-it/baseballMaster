package cc.baseballmaster.core

import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable enum class IncomingTeamLinkKind { join, pair }

/** A user-opened intent, not an invitation redemption or device authorization. */
@Serializable data class IncomingTeamLink(val id: String, val kind: IncomingTeamLinkKind, val token: String, val receivedAt: Long) {
    init {appleUUID(id);TeamSecrets.validate(token);require(receivedAt>0)}
    val url: String get()="https://baseballmaster.cc/${kind.name}#$token"
    override fun toString()="IncomingTeamLink(id=$id, kind=$kind)"
    companion object {
        fun parse(value: String, at: Long = System.currentTimeMillis()): IncomingTeamLink {
            require(value.length<=2048)
            val uri=URI(value)
            val kind=when {
                uri.scheme=="https" && uri.host=="baseballmaster.cc" && uri.rawPath in setOf("/join","/join/") -> IncomingTeamLinkKind.join
                uri.scheme=="https" && uri.host=="baseballmaster.cc" && uri.rawPath in setOf("/pair","/pair/") -> IncomingTeamLinkKind.pair
                uri.scheme=="baseballmaster" && uri.host=="join" -> IncomingTeamLinkKind.join
                uri.scheme=="baseballmaster" && uri.host=="pair" -> IncomingTeamLinkKind.pair
                else -> error("请使用有效的球队邀请或配对链接")
            }
            val token=if(kind==IncomingTeamLinkKind.join) TeamServiceLink.invitation(value) else TeamServiceLink.pairing(value)
            return IncomingTeamLink(UUID.randomUUID().toString(),kind,token,at)
        }
    }
}

/** Local encrypted inbox only. Opening a link never registers or grants access. */
fun TeamDirectorySession.retainIncomingLink(link: IncomingTeamLink): IncomingTeamLink = synchronized(storage.monitor) {
    val state=storage.load()
    state.incomingLinks.firstOrNull {it.kind==link.kind && it.token==link.token}?.let {return@synchronized it}
    require(state.incomingLinks.size<8) {"已有多份链接待核对，请先处理或关闭原链接"}
    storage.save(state.copy(incomingLinks=state.incomingLinks+link))
    link
}
fun TeamDirectorySession.consumeIncomingLink(id: String) = synchronized(storage.monitor) {
    val canonical=appleUUID(id);val state=storage.load()
    storage.save(state.copy(incomingLinks=state.incomingLinks.filterNot {it.id.equals(canonical,true)}))
}
