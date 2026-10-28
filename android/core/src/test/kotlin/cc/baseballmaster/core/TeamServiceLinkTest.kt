package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class TeamServiceLinkTest {
    @Test fun supportedEntryLinksKeepOnlyTheCanonicalFragmentSecret() {
        val token=TeamSecrets.random()
        for(link in listOf(token,"https://baseballmaster.cc/join#$token","https://baseballmaster.cc/join/#$token","baseballmaster://join#$token")) assertEquals(token,TeamServiceLink.invitation(link))
        for(link in listOf(token,"https://baseballmaster.cc/pair#$token","baseballmaster://pair#$token")) assertEquals(token,TeamServiceLink.pairing(link))
    }
    @Test fun otherOriginsQueriesCredentialsPortsAndEntryMixupsNeverBecomeInvitations() {
        val token=TeamSecrets.random()
        for(link in listOf("https://baseballmaster.cc.evil.test/join#$token","http://baseballmaster.cc/join#$token","https://user@baseballmaster.cc/join#$token","https://baseballmaster.cc:443/join#$token","https://baseballmaster.cc/join?token=$token","https://baseballmaster.cc/pair#$token","baseballmaster://join/other#$token"))
            assertThrows(Exception::class.java) {TeamServiceLink.invitation(link)}
    }
}
