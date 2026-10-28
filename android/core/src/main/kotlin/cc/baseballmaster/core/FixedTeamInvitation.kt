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
fun FixedTeamInvitation.qr(size: Int = 256): TeamInvitationQr {
    require(size in 128..1024)
    val matrix=MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,size,size,mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return TeamInvitationQr(matrix.width,matrix.height,IntArray(matrix.width*matrix.height) { index -> if(matrix[index%matrix.width,index/matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() })
}
