package cc.baseballmaster.core

import java.net.URI

object TeamServiceLink {
    fun invitation(input: String): String = token(input,"join")
    fun pairing(input: String): String = token(input,"pair")
    private fun token(input: String, entry: String): String {
        val value=input.trim(); require(value.length <= 2048) { "链接过长" }
        if (!value.contains(':')) return TeamSecrets.validate(value)
        val uri=URI(value)
        val web=uri.scheme=="https" && uri.host=="baseballmaster.cc" && uri.rawPath in setOf("/$entry","/$entry/")
        val app=uri.scheme=="baseballmaster" && uri.host==entry && uri.rawPath.isNullOrEmpty()
        require((web || app) && uri.rawUserInfo==null && uri.port == -1 && uri.rawQuery==null) { "请使用有效的球队邀请或配对链接" }
        return TeamSecrets.validate(requireNotNull(uri.rawFragment) { "链接缺少授权资料" })
    }
}
fun TeamDirectorySession.invitationPreview(input: String): TeamInvitationPreview = synchronized(storage.monitor) {
    val token=TeamServiceLink.invitation(input); refresh(); check(!storage.load().requiresRecoveryReview) { "请先核对恢复后的资料" }; client.preview(token)
}
