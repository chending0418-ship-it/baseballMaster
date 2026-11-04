package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class BaseballFieldGeometryTest {
    @Test fun oldBaseCoordinatesAndEdgesRemainPinnedAcrossPortraitSizes() {
        for((width,height) in listOf(324.0 to 260.0,376.0 to 260.0,448.0 to 235.0,360.0 to 520.0)) {
            val g=BaseballFieldGeometry(width,height)
            fun same(a:FieldPoint,b:FieldPoint) {assertEquals(a.x,b.x,1e-9);assertEquals(a.y,b.y,1e-9)}
            same(g.home,g.contactPoint(FieldPoint(.5,.90)))
            same(g.first,g.contactPoint(FieldPoint(.75,.63)))
            same(g.second,g.contactPoint(FieldPoint(.5,.36)))
            same(g.third,g.contactPoint(FieldPoint(.25,.63)))
            same(FieldPoint(0.0,0.0),g.contactPoint(FieldPoint(0.0,0.0)))
            same(FieldPoint(width,height),g.contactPoint(FieldPoint(1.0,1.0)))
            for(x in 0..20) for(y in 0..20) {
                val old=FieldPoint(x/20.0,y/20.0)
                same(old,g.contactCoordinate(g.contactPoint(old)))
            }
            assertEquals(g.center,g.fielder(1))
            assertTrue(g.fielder(8).y>=29)
        }
    }
    @Test fun tapBoundsClampWithoutMovingPreviouslyStoredObservations() {
        val g=BaseballFieldGeometry(360.0,260.0)
        val previous=FieldPoint(.23,.31)
        assertEquals(previous,g.contactCoordinate(g.contactPoint(previous)))
        assertEquals(FieldPoint(0.0,1.0),g.contactCoordinate(FieldPoint(-80.0,400.0)))
        assertEquals(225.0,Math.toDegrees(kotlin.math.atan2(g.leftFence.y-g.home.y,g.leftFence.x-g.home.x))+360,1e-9)
    }
}
