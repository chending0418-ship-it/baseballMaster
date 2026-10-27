package cc.baseballmaster.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AppleTeamArchiveIntegrityTest {
    private fun source() = File(System.getProperty("parity.fixtures"), "team-archive/ios-team-reference.bmteam").readBytes()
    @Test fun acceptsActualIOSTeamArchiveAndChecksSeal() {
        val data = source()
        val result = AppleTeamArchiveIntegrity.inspect(data.inputStream())
        assertEquals(2, result.version)
        assertEquals("互导测试球队", result.teamName)
        assertEquals(1, result.gameCount)
        assertEquals(1, result.activeGameCount)
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.inspect(data.dropLast(20).toByteArray().inputStream()) }
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.inspect((data + data).inputStream()) }
        val changed = data.copyOf(); changed[changed.size / 4] = (changed[changed.size / 4].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { AppleTeamArchiveIntegrity.inspect(changed.inputStream()) }
    }
}
