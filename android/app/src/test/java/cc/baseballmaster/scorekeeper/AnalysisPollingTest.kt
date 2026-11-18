package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisPollingTest {
    @Test fun parserDetailsAndOtherUnexpectedMessagesAreNeverShown() {
        val raw = "{\"deviceID\":\"private-value\"}"
        for (fault in listOf(SerializationException(raw), IllegalArgumentException(raw), IllegalStateException(raw))) {
            val message = analysisMessage(fault)
            assertEquals("比赛分析暂时无法生成，请重试。", message)
            assertFalse(message.contains("private-value"))
        }
        assertEquals("请先在球队共享页面核对设备授权。", analysisMessage(AnalysisAuthorizationRequired()))
    }

    private fun uuid() = UUID.randomUUID().toString()
    private val device = uuid()
    private val teams = listOf(Team(uuid(), "主队", emptyList()), Team(uuid(), "客队", emptyList(), true))
    private val input = AnalysisSource.single(Game(uuid(), teams[0], teams[1], Rules(), isFinal = true)).input
    private val pending = buildJsonObject { put("state", "pending") }
    private val ready = Json.parseToJsonElement("""{"state":"ready","report":{"overview":"已结束","situations":[],"players":[],"mvp":null}}""").jsonObject

    @Test fun timeoutThenSameRequestIDReattachesAndCompletes() = runBlocking {
        val writes = CopyOnWriteArrayList<JsonObject>()
        var returnReport = false
        val session = session { method, _, body ->
            if (method == "POST") writes.add(requireNotNull(body))
            if (returnReport && method == "GET") ready else pending
        }
        val id = uuid()
        val timedOut = runCatching { session.generateAnalysis(input, id, pollMillis = 25, timeoutMillis = 400) }
        assertTrue(timedOut.exceptionOrNull() is AnalysisTimeout)
        assertEquals(1, writes.size)
        returnReport = true
        assertEquals("已结束", session.generateAnalysis(input, id, pollMillis = 1).overview)
        assertEquals(writes[0], writes[1])
    }

    @Test fun closingWhilePendingCancelsPollingAndPropagatesExpiredResponse() = runBlocking {
        val sent = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        val session = session { method, path, _ ->
            calls.add("$method $path")
            if (method == "GET") throw TeamServiceFault(404, "analysis_expired")
            sent.complete(Unit)
            pending
        }
        val id = uuid()
        val task = launch { session.generateAnalysis(input, id, pollMillis = 60_000) }
        sent.await()
        task.cancelAndJoin()
        assertEquals(listOf("POST game-analysis"), calls.toList())
        val expired = runCatching { session.generateAnalysis(input, id, pollMillis = 1) }.exceptionOrNull() as TeamServiceFault
        assertEquals("analysis_expired", expired.code)
        assertEquals("GET game-analysis/$id", calls.last())
    }

    private fun session(handler: (String, String, JsonObject?) -> JsonObject) = TeamDirectorySession(TeamDirectoryClient(handler),
        object : TeamDirectoryStorage {
            override val monitor = Any()
            override fun load() = TeamDirectoryState(deviceID = device)
            override fun save(state: TeamDirectoryState) = error("Existing synthetic device")
        }, "分析合成设备")
}
