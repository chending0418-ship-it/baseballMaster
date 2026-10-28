package cc.baseballmaster.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Current iOS fixed member invitation, not the earlier one-use scorer link. */
@Serializable data class FixedTeamInvitation(val teamID: String, val token: String, val invitation: TeamInvitation) {
    init { appleUUID(teamID); TeamSecrets.validate(token); require(invitation.role==TeamRole.member) }
    val url: String get()="https://baseballmaster.cc/join#$token"
    override fun toString()="FixedTeamInvitation(teamID=$teamID, invitationID=${invitation.invitationID})"
}
data class TeamInvitationQr(val width: Int, val height: Int, val pixels: IntArray)
fun FixedTeamInvitation.qr(size: Int = 256): TeamInvitationQr = teamServiceQr(url,size)

/** Only the two existing fragment-based service entry points may be shared as an authorization QR. */
fun teamServiceQr(url: String, size: Int = 256): TeamInvitationQr {
    require(url.startsWith("https://baseballmaster.cc/join#") || url.startsWith("https://baseballmaster.cc/pair#"))
    if(url.startsWith("https://baseballmaster.cc/join#")) TeamServiceLink.invitation(url) else TeamServiceLink.pairing(url)
    return serviceQr(url,size)
}
fun liveViewQr(url: String,size: Int=256): TeamInvitationQr {
    require(Regex("https://baseballmaster\\.cc/livestreaming/novideo/[A-F0-9]{24}").matches(url))
    return serviceQr(url,size)
}
fun watchViewQr(url: String,size: Int=256): TeamInvitationQr {
    require(Regex("https://baseballmaster\\.cc/watch/#(?:live/[A-Za-z0-9_-]{32}|[A-Za-z0-9_-]{32}(?:/[A-Za-z0-9_-]{43})?)").matches(url))
    url.substringAfter("/watch/#").takeIf {!it.startsWith("live/") && '/' in it}?.substringAfter('/')?.let(TeamSecrets::validate)
    return serviceQr(url,size)
}
private fun serviceQr(url: String,size: Int): TeamInvitationQr {
    require(size in 128..1024)
    val matrix=MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,size,size,mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return TeamInvitationQr(matrix.width,matrix.height,IntArray(matrix.width*matrix.height) { index -> if(matrix[index%matrix.width,index/matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() })
}
