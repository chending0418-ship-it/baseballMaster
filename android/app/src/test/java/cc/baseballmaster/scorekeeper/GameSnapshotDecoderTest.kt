package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import org.junit.Assert.*
import org.junit.Test

class GameSnapshotDecoderTest {
    @Test fun unchangedRowsReuseSnapshotsButChangedPayloadAndRemovedRowsNeverReuseStaleState() {
        val players = (1..10).map { Player("p$it", "球员$it", "$it") }
        val template = Game("sample", Team("home", "主队", players), Team("away", "客队", players), Rules())
        var calls = 0
        val decoder = GameSnapshotDecoder { row -> calls++; template.copy(id = row.id, revision = row.revision) }
        val a = GameEntity("a", "one", 1, 1)
        val b = GameEntity("b", "two", 1, 1)
        val first = decoder.decode(listOf(a, b))
        val unchanged = decoder.decode(listOf(b.copy(), a.copy()))
        assertEquals(2, calls); assertSame(first[0], unchanged[1]); assertSame(first[1], unchanged[0])
        val replaced = decoder.decode(listOf(a.copy(payload = "changed"), b))
        assertEquals(3, calls); assertNotSame(first[0], replaced[0]); assertSame(first[1], replaced[1])
        decoder.decode(listOf(b))
        decoder.decode(listOf(a, b))
        assertEquals(4, calls)
    }
}
