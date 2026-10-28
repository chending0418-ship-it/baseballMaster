package cc.baseballmaster.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FixedTeamInvitationTest {
    @Test fun generatedQRCodeDecodesToTheExactIOSCompatibleFragmentLink() {
        val invitation=FixedTeamInvitation(UUID.randomUUID().toString(),TeamSecrets.random(),TeamInvitation(UUID.randomUUID().toString(),8640000000000000L,TeamRole.member))
        for(size in listOf(128,256,512)) {
            val qr=invitation.qr(size); val source=RGBLuminanceSource(qr.width,qr.height,qr.pixels)
            val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
            assertEquals(invitation.url,decoded.text)
        }
        assertFalse(invitation.toString().contains(invitation.token))
    }
    @Test fun fixedLinkCannotSilentlyGrantScorerOrAdmin() {
        for(role in listOf(TeamRole.scorer,TeamRole.admin)) assertThrows(IllegalArgumentException::class.java) {
            FixedTeamInvitation(UUID.randomUUID().toString(),TeamSecrets.random(),TeamInvitation(UUID.randomUUID().toString(),8640000000000000L,role))
        }
    }
    @Test fun devicePairingQrKeepsTheExactFragmentAndRejectsOtherOriginsAndQueries() {
        val token=TeamSecrets.random();val link="https://baseballmaster.cc/pair#$token"
        val qr=teamServiceQr(link)
        val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width,qr.height,qr.pixels))))
        assertEquals(link,decoded.text);assertEquals(token,TeamServiceLink.pairing(decoded.text))
        for(value in listOf("https://example.com/pair#$token","https://baseballmaster.cc/pair?token=$token#$token","https://baseballmaster.cc/pair#invalid"))
            assertThrows(Exception::class.java) {teamServiceQr(value)}
    }
}
