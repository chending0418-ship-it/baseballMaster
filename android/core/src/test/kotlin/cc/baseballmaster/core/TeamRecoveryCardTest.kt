package cc.baseballmaster.core

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TeamRecoveryCardTest {
    @Test fun pastedCardKeepsTheExactTeamAndCanonicalSecretAndNeverPrintsTheSecret() {
        val card=SavedManagementCard(UUID.randomUUID().toString(),"测试球队",TeamSecrets.random(),1)
        val parsed=ManagementRecoveryInput.parse("球队管理恢复卡\n球队：测试球队\n${card.code}\n请离线保存")
        assertEquals(card.teamID,parsed.teamID); assertEquals(card.token,parsed.token)
        assertEquals(parsed,ManagementRecoveryInput.parse(card.text))
        assertFalse(parsed.toString().contains(card.token)); assertFalse(card.toString().contains(card.token))
    }
    @Test fun ambiguousTruncatedAndNoncanonicalRecoveryInputsAreRejected() {
        val id=UUID.randomUUID().toString(); val token=TeamSecrets.random(); val code="BMREC1.$id.$token"
        for (input in listOf("$code\n$code",code.dropLast(1),"BMREC1.$id.${token.dropLast(1)}_","BMREC1.bad-id.$token","x".repeat(65537)))
            assertThrows(IllegalArgumentException::class.java) { ManagementRecoveryInput.parse(input) }
    }
}
