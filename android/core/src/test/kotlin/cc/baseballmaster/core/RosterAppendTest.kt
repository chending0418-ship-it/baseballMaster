package cc.baseballmaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterAppendTest {
    @Test fun importsHeaderEnglishOnlyAndDistinctZeroJerseyNumbers() {
        val existing = listOf(Player("prior", "旧球员", "27", chineseName = "旧球员"))
        val preview = RosterAppend.preview("\uFEFF姓名,英文名,背号\r\n,Lee,00\r\n王一，，0", existing)
        assertTrue(preview.issues.isEmpty())
        assertTrue(preview.warnings.isEmpty())
        assertEquals(listOf(2, 3), preview.rows.map { it.lineNumber })
        val players = preview.confirmedPlayers(false)
        assertEquals(listOf("Lee", "王一"), players.map { it.name })
        assertEquals(listOf("", "王一"), players.map { it.chineseName })
        assertEquals(listOf("00", "0"), players.map { it.number })
        assertEquals(3, (existing + players).map { it.id }.toSet().size)
    }

    @Test fun identicalRowsBlockTheBatchAndDistinctNamesakesRequireConfirmation() {
        val existing = Player("prior", "王一", "00", englishName = "Lee", chineseName = "王一")
        val exact = RosterAppend.preview("王一,Lee,00\n李二,7\n李二,7", listOf(existing))
        assertEquals(listOf(1, 3), exact.issues.map { it.lineNumber })
        assertThrows(IllegalArgumentException::class.java) { exact.confirmedPlayers(true) }

        val warning = RosterAppend.preview("王一,Will,00 / 12\n王一,West,00\n王一,Wen,0", listOf(existing))
        assertEquals(listOf(1, 2), warning.warnings.map { it.lineNumber })
        assertThrows(IllegalArgumentException::class.java) { warning.confirmedPlayers(false) }
        val confirmed = warning.confirmedPlayers(true)
        assertEquals(listOf("Will", "West", "Wen"), confirmed.map { it.englishName })
        assertEquals(listOf("00", "12"), confirmed.first().jerseyNumbers)
        confirmed.forEach { assertNotEquals(existing.id, it.id) }
        assertEquals("prior", existing.id)
        assertEquals("Lee", existing.englishName)
    }

    @Test fun malformedJerseyNumberBlocksOtherRowsWithoutInventingANumber() {
        val preview = RosterAppend.preview("林一,00\n张三,\n陈四,01", emptyList())
        assertEquals(listOf(2, 3), preview.issues.map { it.lineNumber })
        assertEquals("00", preview.rows.single().player.number)
        assertThrows(IllegalArgumentException::class.java) { preview.confirmedPlayers(true) }
    }
}
