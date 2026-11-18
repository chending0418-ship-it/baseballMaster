package cc.baseballmaster.scorekeeper

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MatchDateTimeControlsTest {
    @Test fun localMatchTimeKeepsTheDateAcrossUtcMidnight() {
        val match = parseMatchDate("2026-11-17 00:30", "Asia/Shanghai")
        assertEquals(Instant.parse("2026-11-16T16:30:00Z").toEpochMilli(), match)
        assertEquals("2026-11-17 00:30", formatMatchDate(match, "Asia/Shanghai"))
        assertNull(matchDateIssue(match, true, match - 1))
        assertNotNull(matchDateIssue(match, true, match))
        assertNull(matchDateIssue(match, false, match))
        assertNotNull(matchDateIssue(match, false, match - 1))
    }

    @Test fun daylightSavingGapAndOverlapCannotBeSilentlyChanged() {
        val zone = "America/Los_Angeles"
        assertThrows(IllegalArgumentException::class.java) { parseMatchDate("2026-03-08 02:30", zone) }
        assertThrows(IllegalArgumentException::class.java) { parseMatchDate("2026-11-01 01:30", zone) }
        assertEquals(Instant.parse("2026-11-01T08:30:00Z").toEpochMilli(), parseMatchDate("2026-11-01 01:30", zone, "-07:00"))
        assertEquals(Instant.parse("2026-11-01T09:30:00Z").toEpochMilli(), parseMatchDate("2026-11-01 01:30", zone, "-08:00"))
    }
}
