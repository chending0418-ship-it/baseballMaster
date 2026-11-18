package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamBroadcastTest {
    @Test fun allFourBrowserSourcesShareOneCodeAndKeepTheirOverlayQuery() {
        val code = "A".repeat(32)
        val prefix = "https://baseballmaster.cc/livestreaming/novideo/overlay?"
        assertEquals(listOf("view=score", "view=batter", "view=lineup&team=away", "view=lineup&team=home").map {
            "$prefix$it#team/$code"
        }, BroadcastSource.entries.map { it.url(code) })
        assertEquals(listOf("640 × 116", "600 × 108", "440 × 540", "440 × 540"), BroadcastSource.entries.map { it.size })
    }

    @Test fun localPublicationRequiresStartedMatchAndAnExplicitOrPreviouslySelectedTarget() {
        val api = Fixture(TeamRole.scorer)
        val setup = MatchFactory.create(id(), api.own, team("对手"), Rules(), api.own.id)
        assertNull(api.session.publishLocalBroadcast(setup, BroadcastSelection(null)))
        assertNull(api.session.startLocalBroadcast(setup))
        assertNull(api.session.startLocalBroadcast(setup.copy(isFinal = true)))
        assertTrue(api.writes.isEmpty())

        val game = MatchEngine.apply(setup, Command("pitch", pitch = PitchAction.ball))
        api.session.publishLocalBroadcast(game, BroadcastSelection(null))
        api.session.resumeLocalBroadcast(game)
        api.session.startLocalBroadcast(game)
        assertEquals(JsonNull, api.writes[0]["expectedGameID"])
        assertEquals(JsonPrimitive(game.id), api.writes[1]["expectedGameID"])
        assertFalse(api.writes[2].containsKey("expectedGameID"))
        for (write in api.writes) {
            val projected = write.getValue("snapshot").jsonObject
            assertEquals(game.revision, projected.getValue("revision").jsonPrimitive.long)
            assertEquals(1, projected.getValue("balls").jsonPrimitive.int)
            assertFalse(write.containsKey("writerEpoch"))
            assertFalse(projected.containsKey("journal"))
            assertFalse(projected.containsKey("rosters"))
        }
    }

    @Test fun refreshingAuthorizationRejectsMemberReadsAndScorerRotationBeforeRequests() {
        val member = Fixture(TeamRole.member)
        assertThrows(IllegalArgumentException::class.java) { member.session.broadcastSources(member.club.id) }
        assertTrue(member.reads.isEmpty())
        val scorer = Fixture(TeamRole.scorer)
        assertNotNull(scorer.session.broadcastSources(scorer.club.id).code)
        assertThrows(IllegalArgumentException::class.java) { scorer.session.rotateBroadcast(scorer.club.id, "A".repeat(32)) }
        assertTrue(scorer.writes.isEmpty())
    }

    private class Fixture(role: TeamRole) {
        val own = team("本队")
        val club = RemoteTeam(id(), "本队", 1, role, id(), 1)
        private val directory = TeamDirectory(listOf(club), id())
        private val storage = object : TeamDirectoryStorage {
            override val monitor = Any()
            private var current = TeamDirectoryState(deviceID = id(), directory = directory,
                bindings = listOf(SharedTeamBinding(own.id, club.id)))
            override fun load() = current
            override fun save(state: TeamDirectoryState) { current = state }
        }
        val reads = ArrayList<String>()
        val writes = ArrayList<JsonObject>()
        val session = TeamDirectorySession(TeamDirectoryClient(TeamTransport { method, path, body ->
            when {
                method == "GET" && path == "teams" -> TeamDirectoryJson.format.encodeToJsonElement(directory).jsonObject
                method == "GET" && path == "teams/${club.id}/broadcast" -> {
                    reads += path
                    buildJsonObject { put("code", "A".repeat(32)) }
                }
                method == "PUT" && path == "teams/${club.id}/broadcast" -> {
                    writes += requireNotNull(body)
                    buildJsonObject { put("selected", true) }
                }
                else -> error("unexpected $method $path")
            }
        }), storage, "Broadcast fixture")
    }

    private companion object {
        fun id() = UUID.randomUUID().toString()
        fun team(name: String) = Team(id(), name, (1..12).map { Player(id(), "$name$it", it.toString()) })
    }
}
