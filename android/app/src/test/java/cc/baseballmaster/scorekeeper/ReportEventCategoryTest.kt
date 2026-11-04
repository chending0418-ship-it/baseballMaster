package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** iOS GameStore category contract; old prototype wire export categories are not authoritative. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class ReportEventCategoryTest {
    @Test fun specialPitchOutAndRulingEventsUseTheIOSReportCategories() {
        fun label(kind: String, notation: String = "", command: Command? = null) =
            ReportExport.eventCategory(PitchEntry("event", text = "原始记录", kind = kind, notation = notation), command)
        assertEquals("击球", label("intentionalWalk"))
        assertEquals("击球", label("hitByPitch", "HBP"))
        assertEquals("投球", label("hitByPitch", "HBP-CONTINUE"))
        assertEquals("出局", label("foulBunt"))
        assertEquals("出局", label("droppedThirdStrike"))
        assertEquals("判罚", label("ruling"))
        assertEquals("修正", label("review"))
        assertEquals("延长／TB", label("skipTB"))
        assertEquals("出局", label("runner", command = Command("runner", runnerKind = "caughtStealing")))
        assertEquals("跑垒", label("runner", command = Command("runner", runnerKind = "wildPitch")))
        assertEquals("击球", label("illegal", command = Command("illegal", outcome = Outcome.single)))
        assertEquals("投球", label("illegal", command = Command("illegal", pitch = PitchAction.ball)))
        assertEquals("判罚", label("illegal", command = Command("illegal")))
    }
}
