package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class IncomingTeamLinkTest {
    private class Memory : TeamDirectoryStorage {
        override val monitor=Any();var state=TeamDirectoryState()
        override fun load()=state
        override fun save(state: TeamDirectoryState) {this.state=state}
    }
    @Test fun httpsAndCustomSchemeResolveToTheSameTypedFragmentWithoutPrintingTheSecret() {
        val token=TeamSecrets.random()
        for(kind in IncomingTeamLinkKind.entries) for(url in listOf("https://baseballmaster.cc/${kind.name}#$token","https://baseballmaster.cc/${kind.name}/#$token","baseballmaster://${kind.name}#$token")) {
            val value=IncomingTeamLink.parse(url,123)
            assertEquals(kind,value.kind);assertEquals(token,value.token);assertEquals(123,value.receivedAt)
            assertEquals("https://baseballmaster.cc/${kind.name}#$token",value.url)
            assertFalse(value.toString().contains(token))
        }
    }
    @Test fun malformedOriginsQueriesPortsUserInfoPathsAndSecretsAreRejectedBeforeStorage() {
        val t=TeamSecrets.random()
        for(url in listOf(t,"http://baseballmaster.cc/join#$t","https://other.example/join#$t","https://baseballmaster.cc.evil.test/pair#$t","https://baseballmaster.cc/join?token=$t#$t","https://baseballmaster.cc:443/pair#$t","https://user@baseballmaster.cc/join#$t","baseballmaster://pair/unexpected#$t","https://baseballmaster.cc/join#$t/","https://baseballmaster.cc/pair#%41${t.drop(1)}","https://baseballmaster.cc/watch/#$t"))
            assertThrows(Exception::class.java) {IncomingTeamLink.parse(url)}
    }
    @Test fun encryptedInboxIsBoundedIdempotentAndNeverMakesANetworkRequestOrGrantsMembership() {
        val storage=Memory();var calls=0
        val session=TeamDirectorySession(TeamDirectoryClient(TeamTransport {_,_,_->calls++;error("Unexpected network")}),storage,"测试设备")
        val first=IncomingTeamLink.parse("baseballmaster://join#${TeamSecrets.random()}")
        assertEquals(first,session.retainIncomingLink(first))
        assertEquals(first,session.retainIncomingLink(IncomingTeamLink.parse(first.url)))
        repeat(7) {session.retainIncomingLink(IncomingTeamLink.parse("baseballmaster://pair#${TeamSecrets.random()}"))}
        val before=storage.state
        assertThrows(IllegalArgumentException::class.java) {session.retainIncomingLink(IncomingTeamLink.parse("baseballmaster://pair#${TeamSecrets.random()}"))}
        assertEquals(before,storage.state);assertNull(storage.state.deviceID);assertNull(storage.state.directory)
        session.consumeIncomingLink(first.id);session.consumeIncomingLink(first.id)
        assertEquals(7,storage.state.incomingLinks.size);assertEquals(0,calls)
    }
}
