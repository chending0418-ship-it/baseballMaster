package cc.baseballmaster.core

import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamScheduleTest {
    private val teamID = "aaaaaaaa-0000-4000-8000-000000000001"
    private val secondTeamID = "aaaaaaaa-0000-4000-8000-000000000002"
    private val gameID = "bbbbbbbb-0000-4000-8000-000000000001"
    private val memberID = "cccccccc-0000-4000-8000-000000000001"
    private val deviceID = "dddddddd-0000-4000-8000-000000000001"
    private val epoch = "eeeeeeee-0000-4000-8000-000000000001"
    private val startsAt = 4_102_444_800_000L
    private val json = TeamDirectoryJson.format

    private fun team(id: String = teamID, role: TeamRole = TeamRole.scorer, accessEpoch: Long? = 1) =
        RemoteTeam(id, "主队", 1, role, memberID, accessEpoch)

    private fun notice(id: String = teamID, venue: String = "原球场", revision: Long = 1) =
        TeamScheduleNotice(id, gameID.uppercase(), startsAt, "客队", "主队", venue, false, revision)

    @Test fun memberFeedOnlyAllowsCurrentMembershipAndDoesNotEnroll() {
        val service = Service()
        val empty = Store(TeamDirectoryState())
        assertTrue(service.session(empty).teamSchedules().notices.isEmpty())
        assertTrue(service.calls.isEmpty())

        val member = team(role = TeamRole.member)
        service.teams = listOf(member)
        service.notices = listOf(notice(secondTeamID), notice(teamID.uppercase()))
        val session = service.session(store(member))
        assertEquals(listOf(notice().key), session.teamSchedules().notices.map { it.key })
        assertThrows(IllegalStateException::class.java) { session.publishSchedule(teamID, gameID, startsAt, "客队", "主队", "") }
        assertThrows(IllegalStateException::class.java) { session.updateRemoteSchedule(notice(), startsAt, "无权改期") }
        service.teams = emptyList()
        assertTrue(session.teamSchedules().notices.isEmpty())
        assertEquals(listOf("GET teams", "GET team-schedules", "GET teams"), service.calls)
    }

    @Test fun unknownWriteResultSurvivesRestartAndReplaysExactlyOnce() {
        val service = Service()
        var store = store(team())
        var original: JsonObject? = null
        service.handleWrite = { path, body ->
            val pending = store.load().schedulePublications.getValue(notice().key).pending
            assertEquals(body, json.encodeToJsonElement(pending))
            assertEquals("teams/$teamID/schedules/$gameID", path)
            if (original == null) {
                original = body
                throw IOException("simulated lost response after commit")
            }
            assertEquals(original, body)
            receipt(path, body)
        }
        assertThrows(IOException::class.java) {
            service.session(store).publishSchedule(teamID.uppercase(), gameID.uppercase(), startsAt, "客队", "主队", " 球场 ")
        }
        val first = requireNotNull(original)
        assertEquals(setOf("operationID", "expectedRevision", "startsAt", "away", "home", "venue", "cancelled"), first.keys)
        assertEquals(0L, first.getValue("expectedRevision").jsonPrimitive.long)
        store = Store(store.load())
        val restarted = service.session(store)
        val published = restarted.retrySchedulePublication(gameID)
        assertEquals(1L, published.revision)
        assertEquals("球场", published.notice?.venue)
        assertTrue(published.needsLocalSync)
        assertNull(published.pending)
        restarted.confirmScheduleLocalSync(requireNotNull(published.notice))
        assertFalse(restarted.scheduleState().publications.getValue(gameID).needsLocalSync)
    }

    @Test fun remoteAdminEntriesAreScopedToTeamAndRefreshConflictBeforeNewOperation() {
        val admin = team(role = TeamRole.admin)
        val otherAdmin = team(secondTeamID, TeamRole.admin)
        val service = Service()
        service.teams = listOf(admin, otherAdmin)
        service.notices = listOf(notice(venue = "其他设备已改期", revision = 2), notice(secondTeamID))
        val session = service.session(store(admin, otherAdmin))
        service.handleWrite = { path, body ->
            if (path.contains(teamID) && body.getValue("expectedRevision").jsonPrimitive.long == 1L)
                throw TeamServiceFault(409, "schedule_changed")
            receipt(path, body)
        }
        val fault = assertThrows(TeamServiceFault::class.java) { session.updateRemoteSchedule(notice(), startsAt + 1000, "旧修订") }
        assertEquals("schedule_changed", fault.code)
        val reviewed = session.scheduleState().remotePublications.getValue(notice().key)
        assertEquals("其他设备已改期", reviewed.notice?.venue)
        assertNull(reviewed.pending)

        val other = session.updateRemoteSchedule(notice(secondTeamID), startsAt + 2000, "第二球队", cancelled = true)
        val updated = session.updateRemoteSchedule(requireNotNull(reviewed.notice), startsAt + 3000, "第一球队")
        assertTrue(other.cancelled)
        assertEquals(startsAt, other.notice?.startsAt)
        assertEquals(3L, updated.revision)
        assertEquals(setOf(notice().key, notice(secondTeamID).key), session.scheduleState().remotePublications.keys)
        assertTrue(session.scheduleState().publications.isEmpty())
        assertFalse(updated.needsLocalSync)
    }

    @Test fun conflictWithUnavailableFeedKeepsOriginalRequestAcrossRestartUntilReview() {
        val service = Service()
        val store = store(team())
        val latest = notice(venue = "另一个设备已改期", revision = 2)
        service.notices = listOf(latest)
        service.readFailure = IOException("conflict feed unavailable")
        val requests = mutableListOf<JsonObject>()
        service.handleWrite = { _, body ->
            requests += body
            throw TeamServiceFault(409, "schedule_changed")
        }
        val firstSession = service.session(store)
        assertThrows(IOException::class.java) {
            firstSession.publishSchedule(teamID, gameID, startsAt + 1000, "客队", "主队", "本机填写的场地")
        }
        val afterFailure = store.load().schedulePublications.getValue(latest.key)
        assertNull(afterFailure.notice)
        assertEquals("本机填写的场地", afterFailure.pending?.venue)
        assertNotNull(afterFailure.pending?.operationID)

        service.readFailure = null
        val restart = service.session(Store(store.load()))
        val conflict = assertThrows(TeamServiceFault::class.java) { restart.retrySchedulePublication(gameID) }
        assertEquals("schedule_changed", conflict.code)
        assertEquals(listOf(requests.first(), requests.first()), requests)
        val reviewed = restart.scheduleState().publications.getValue(gameID)
        assertNull(reviewed.pending)
        assertEquals(latest, reviewed.notice)
        assertTrue(reviewed.needsLocalSync)
        val staleForm = assertThrows(TeamServiceFault::class.java) {
            restart.publishSchedule(teamID, gameID, startsAt, "客队", "主队", "旧表单", expectedRevision = 0)
        }
        assertEquals("schedule_changed", staleForm.code)
        assertEquals(2, requests.size)
    }

    @Test fun roleDowngradeAndLostAccessNeverReplayStoredRequests() {
        val admin = team(role = TeamRole.admin)
        val store = store(admin)
        val service = Service()
        service.teams = listOf(admin)
        service.handleWrite = { _, _ -> throw IOException("offline") }
        assertThrows(IOException::class.java) {
            service.session(store).updateRemoteSchedule(notice(), startsAt + 1000, "管理员改期")
        }
        val pending = store.load().schedulePublications.getValue(notice().key).pending
        assertNotNull(pending)
        service.teams = listOf(team(role = TeamRole.scorer))
        val restarted = service.session(store)
        assertThrows(IllegalStateException::class.java) { restarted.retrySchedulePublication(gameID, teamID) }
        assertTrue(restarted.scheduleState().remotePublications.isEmpty())
        assertEquals(pending, store.load().schedulePublications.getValue(notice().key).pending)
        assertEquals(1, service.calls.count { it.startsWith("PUT ") })

        val scorer = team()
        val localStore = store(scorer)
        assertThrows(IOException::class.java) { service.session(localStore).publishSchedule(teamID, gameID, startsAt, "客队", "主队", "") }
        val localPending = localStore.load().schedulePublications.getValue(notice().key).pending
        service.teams = emptyList()
        val localRestart = service.session(localStore)
        assertThrows(IllegalStateException::class.java) { localRestart.retrySchedulePublication(gameID) }
        assertTrue(localRestart.teamSchedules().publications.isEmpty())
        assertEquals(localPending, localStore.load().schedulePublications.getValue(notice().key).pending)
        assertEquals(2, service.calls.count { it.startsWith("PUT ") })
    }

    private fun store(vararg teams: RemoteTeam) = Store(TeamDirectoryState(deviceID, TeamDirectory(teams.toList(), epoch)))

    private inner class Store(initial: TeamDirectoryState) : TeamDirectoryStorage {
        override val monitor = Any()
        private var content = json.encodeToString(initial)
        override fun load(): TeamDirectoryState = json.decodeFromString(content)
        override fun save(state: TeamDirectoryState) { content = json.encodeToString(state) }
    }

    private inner class Service {
        var teams = listOf(team())
        var notices = emptyList<TeamScheduleNotice>()
        var readFailure: IOException? = null
        val calls = mutableListOf<String>()
        var handleWrite: (String, JsonObject) -> JsonObject = ::receipt

        fun session(store: Store) = TeamDirectorySession(TeamDirectoryClient { method, path, body ->
            calls += "$method $path"
            when {
                method == "GET" && path == "teams" -> json.encodeToJsonElement(TeamDirectory(teams, epoch)).jsonObject
                method == "GET" && path == "team-schedules" -> {
                    readFailure?.let { throw it }
                    buildJsonObject { put("schedules", json.encodeToJsonElement(notices)) }
                }
                method == "PUT" -> handleWrite(path, requireNotNull(body))
                else -> error("Unexpected schedule request: $method $path")
            }
        }, store, "球队赛程测试设备")
    }

    private fun receipt(path: String, body: JsonObject) = buildJsonObject {
        val segments = path.split('/')
        put("teamID", segments[1])
        put("id", segments[3])
        put("revision", body.getValue("expectedRevision").jsonPrimitive.long + 1)
    }
}
