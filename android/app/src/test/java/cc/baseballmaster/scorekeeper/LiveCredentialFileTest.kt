package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],manifest=Config.NONE)
class LiveCredentialFileTest {
    private fun binding()=LiveBinding(UUID.randomUUID().toString(),createdAt=System.currentTimeMillis(),token="ab".repeat(32))
    @Test fun credentialsAreEncryptedIndependentAndReopenWithTheStableKeyAfterTeamAuthenticationChanges() {
        val dir=Files.createTempDirectory("live-credentials").toFile()
        try {
            val context=RuntimeEnvironment.getApplication();val key=SecretKeySpec(ByteArray(32) {it.toByte()},"AES")
            val vault=CredentialVault(context,key,writeGuard=CredentialWriteGuard());val stable=vault.stableEncryptionSecret();val b=binding()
            LiveCredentialFile(dir,vault::stableEncryptionSecret).save(listOf(b))
            assertFalse(dir.resolve("publishers-v1.sealed").readBytes().toString(Charsets.UTF_8).contains(b.token))
            vault.finishRotation(vault.beginRotation());assertArrayEquals(stable,vault.stableEncryptionSecret())
            assertEquals(listOf(b),LiveCredentialFile(dir,CredentialVault(context,key,writeGuard=CredentialWriteGuard())::stableEncryptionSecret).load())
            assertThrows(Exception::class.java) {LiveCredentialFile(dir,{ByteArray(32) {99}}).load()}
        } finally {dir.deleteRecursively()}
    }
    @Test fun failedAtomicCommitKeepsOriginalClosingIntentAndNeverClaimsAnAcknowledgement() {
        val dir=Files.createTempDirectory("live-atomic").toFile();val secret={ByteArray(32) {it.toByte()}}
        try {
            val b=binding();val file=LiveCredentialFile(dir,secret);file.save(listOf(b))
            assertThrows(IOException::class.java) {LiveCredentialFile(dir,secret,{throw IOException("injected disk failure")}).save(listOf(b.copy(closing=true)))}
            assertEquals(listOf(b),LiveCredentialFile(dir,secret).load())
            file.save(listOf(b.copy(closing=true)));assertTrue(LiveCredentialFile(dir,secret).load().single().closing)
        } finally {dir.deleteRecursively()}
    }
    @Test fun tamperingOrDuplicateIdentitiesDoNotSilentlyResetSavedPublication() {
        val dir=Files.createTempDirectory("live-tamper").toFile();val secret={ByteArray(32) {it.toByte()}}
        try {
            val b=binding();val file=LiveCredentialFile(dir,secret);file.save(listOf(b))
            assertThrows(IllegalStateException::class.java) {file.save(listOf(b,b))};assertEquals(listOf(b),file.load())
            val path=dir.resolve("publishers-v1.sealed");val corrupted=path.readBytes();corrupted[corrupted.lastIndex]=(corrupted.last().toInt() xor 1).toByte();path.writeBytes(corrupted)
            assertThrows(Exception::class.java) {file.load()};assertArrayEquals(corrupted,path.readBytes())
        } finally {dir.deleteRecursively()}
    }
}
