package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android preferences and JDK AES; intentionally does not claim a real Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],manifest=Config.NONE)
class CredentialRotationStorageTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val key=SecretKeySpec(ByteArray(32) { (it+7).toByte() },"AES")

    @Test fun originalEncryptedDirectoryStillOpensAfterAuthenticationRotatesAndVaultReopens() {
        val guard=CredentialWriteGuard(); val vault=CredentialVault(context,key,writeGuard=guard); val old=vault.secret()
        val dir=Files.createTempDirectory("credential-state-").toFile()
        try {
            val state=TeamDirectoryState(deviceID=java.util.UUID.randomUUID().toString())
            TeamDirectoryFile(dir,vault::secret).save(state)
            assertArrayEquals(old,vault.stableEncryptionSecret())
            val pending=vault.beginRotation(); vault.finishRotation(pending)
            val reopened=CredentialVault(context,key,writeGuard=CredentialWriteGuard())
            assertEquals(pending.token,reopened.currentToken()); assertArrayEquals(old,reopened.stableEncryptionSecret())
            assertEquals(state,TeamDirectoryFile(dir,reopened::stableEncryptionSecret).load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun pendingRotationUsesTheSameCandidateAfterReopenAndNeverStoresPlaintext() {
        val vault=CredentialVault(context,key,writeGuard=CredentialWriteGuard()); val candidate=vault.beginRotation()
        val reopened=CredentialVault(context,key,writeGuard=CredentialWriteGuard())
        assertEquals(candidate,reopened.pendingRotation()); assertEquals(candidate,reopened.beginRotation())
        val saved=context.getSharedPreferences("device_credentials",0).getString("rotation_sealed",null)!!
        assertFalse(saved.contains(candidate.token)); assertFalse(candidate.toString().contains(candidate.token))
    }

    @Test fun reconnectKeepsEncryptionKeyAndCandidateAcrossActivationAndReopen() {
        val vault=CredentialVault(context,key,writeGuard=CredentialWriteGuard());val old=vault.currentToken();val stable=vault.stableEncryptionSecret()
        vault.beginRotation();val candidate=vault.beginReconnect()
        assertEquals(old,vault.currentToken());assertFalse(candidate.toString().contains(candidate.token))
        val reopened=CredentialVault(context,key,writeGuard=CredentialWriteGuard())
        assertEquals(candidate,reopened.beginReconnect());reopened.activateReconnect(candidate)
        val activated=CredentialVault(context,key,writeGuard=CredentialWriteGuard())
        assertEquals(candidate.token,activated.currentToken());assertNull(activated.pendingRotation());assertEquals(candidate,activated.pendingReconnect())
        assertArrayEquals(stable,activated.stableEncryptionSecret());activated.finishReconnect(candidate)
        assertNull(CredentialVault(context,key,writeGuard=CredentialWriteGuard()).pendingReconnect())
        assertFalse(context.getSharedPreferences("device_credentials",0).all.values.any {it.toString().contains(candidate.token)})
    }

    @Test fun failedReconnectCandidateCommitBlocksUnconfirmedSecretAndEveryVaultInProcess() {
        val guard=CredentialWriteGuard();val vault=CredentialVault(context,key,writeGuard=guard);vault.stableEncryptionSecret()
        val failing=CredentialVault(context,key,commitEditor={it.commit();false},writeGuard=guard)
        assertThrows(IllegalStateException::class.java) {failing.beginReconnect()}
        assertThrows(IllegalStateException::class.java) {vault.currentToken()}
        assertThrows(IllegalStateException::class.java) {vault.pendingReconnect()}
    }

    @Test fun failedReconnectActivationPreservesResumableCandidateAndStableFileKey() {
        val guard=CredentialWriteGuard();val vault=CredentialVault(context,key,writeGuard=guard);val stable=vault.stableEncryptionSecret();val candidate=vault.beginReconnect()
        val failing=CredentialVault(context,key,commitEditor={it.commit();false},writeGuard=guard)
        assertThrows(IllegalStateException::class.java) {failing.activateReconnect(candidate)}
        assertThrows(IllegalStateException::class.java) {vault.currentToken()}
        val reopened=CredentialVault(context,key,writeGuard=CredentialWriteGuard())
        assertEquals(candidate,reopened.pendingReconnect());assertArrayEquals(stable,reopened.stableEncryptionSecret())
        reopened.activateReconnect(candidate);reopened.finishReconnect(candidate)
    }

    @Test fun aFailedSecretCommitCannotBeMistakenForAPersistedInMemoryPreference() {
        val guard=CredentialWriteGuard(); var commits=0
        val vault=CredentialVault(context,key,commitEditor={ it.commit(); commits++; false },writeGuard=guard)
        assertThrows(IllegalStateException::class.java) { vault.secret() }
        assertThrows(IllegalStateException::class.java) { CredentialVault(context,key,writeGuard=guard).secret() }
        assertEquals(1,commits)
    }

    @Test fun failedCandidateCommitBlocksEveryVaultSharingTheSameProcessGuard() {
        val guard=CredentialWriteGuard(); val original=CredentialVault(context,key,writeGuard=guard)
        original.secret(); original.stableEncryptionSecret()
        val failing=CredentialVault(context,key,commitEditor={ it.commit(); false },writeGuard=guard)
        assertThrows(IllegalStateException::class.java) { failing.beginRotation() }
        assertThrows(IllegalStateException::class.java) { original.pendingRotation() }
        assertThrows(IllegalStateException::class.java) { original.currentToken() }
    }

    @Test fun ambiguousFinishBlocksThisProcessAndAReopenedVaultKeepsTheEncryptionSecret() {
        val guard=CredentialWriteGuard(); val original=CredentialVault(context,key,writeGuard=guard)
        val stable=original.stableEncryptionSecret(); val candidate=original.beginRotation()
        val failing=CredentialVault(context,key,commitEditor={ it.commit(); false },writeGuard=guard)
        assertThrows(IllegalStateException::class.java) { failing.finishRotation(candidate) }
        assertThrows(IllegalStateException::class.java) { original.currentToken() }
        val reopened=CredentialVault(context,key,writeGuard=CredentialWriteGuard())
        assertEquals(candidate.token,reopened.currentToken()); assertArrayEquals(stable,reopened.stableEncryptionSecret())
    }

    @Test fun credentialMaintenanceUsesTheExistingNinetyDayPolicyAndFinishResetsTheDate() {
        var now=1_800_000_000_000L
        val vault=CredentialVault(context,key,writeGuard=CredentialWriteGuard(),now={now})
        assertFalse(vault.rotationDue()); now+=90*86_400_000L-1; assertFalse(vault.rotationDue()); now++
        assertTrue(vault.rotationDue()); vault.finishRotation(vault.beginRotation()); assertFalse(vault.rotationDue())
    }

    @Test fun aThrowingPreferenceCommitAlsoBlocksTheUnconfirmedInMemorySecret() {
        val guard=CredentialWriteGuard()
        val vault=CredentialVault(context,key,commitEditor={ it.commit(); throw java.io.IOException("injected commit exception") },writeGuard=guard)
        assertThrows(java.io.IOException::class.java) { vault.secret() }
        assertThrows(IllegalStateException::class.java) { CredentialVault(context,key,writeGuard=guard).currentToken() }
    }
}
