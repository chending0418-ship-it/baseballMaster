package cc.baseballmaster.scorekeeper

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedStatisticsFlowTest {
    @get:Rule val activity = ActivityTestRule(MainActivity::class.java)

    @Test fun memberWithNoLocalDataSeesFinishedMatchesAndLatestCorrectionUntilAccessIsRemoved() {
        fun id() = UUID.randomUUID().toString()
        val teamID = id()
        val localOriginalTeamID = id()
        val localOriginalPlayerID = id()
        val officialPlayerID = id()
        val opponentID = id()
        val finalGameID = id()
        val ongoingGameID = id()
        val deviceID = id()
        val player = SharedRosterPlayer(id(), officialPlayerID, "正式名册球员", "", listOf("00"))
        val profile = SharedTeamProfile(teamID, "云端统计队", 1, "云端", "", listOf(player))
        val member = RemoteTeam(teamID, profile.name, 1, TeamRole.member, id())
        val directory = AtomicReference(TeamDirectory(listOf(member), id()))
        val revision = AtomicInteger(1)
        val reads = AtomicInteger()
        val fixtureTime = Instant.now().epochSecond - 978307200

        fun team(team: String, name: String, withPlayer: Boolean) = buildJsonObject {
            put("id", team)
            put("name", name)
            put("players", buildJsonArray {
                if (withPlayer) add(buildJsonObject {
                    put("id", localOriginalPlayerID); put("chineseName", "当场名字"); put("englishName", "")
                    put("numbers", buildJsonArray { add(0) })
                    put("jerseyNumbers", buildJsonArray { add("00") })
                })
            })
        }
        fun match(game: String, isFinal: Boolean, details: Boolean) = buildJsonObject {
            val runs = revision.get()
            put("id", game); put("teamID", teamID); put("serverRevision", runs)
            put("writerEpoch", 1); put("writerDeviceID", deviceID); put("responsibleMemberID", member.memberID)
            put("digest", (if (isFinal) runs.toString() else "0").repeat(64)); put("deleted", false); put("canWrite", false)
            put("status", if (isFinal) "completed" else "ongoing")
            put("playerLinks", buildJsonArray {
                add(buildJsonObject { put("sourceID", localOriginalPlayerID); put("targetID", officialPlayerID) })
            })
            if (details) put("payload", buildJsonObject {
                put("id", game); put("status", if (isFinal) 1 else 0); put("ourTeamID", localOriginalTeamID)
                put("isHome", false); put("createdAt", fixtureTime); put("updatedAt", fixtureTime)
                put("rules", buildJsonObject { put("mode", "standard") })
                put("state", buildJsonObject {
                    put("isFinal", isFinal)
                    put("homeTeam", team(opponentID, "对手", false)); put("awayTeam", team(localOriginalTeamID, "历史名字", true))
                    put("homeRunsByInning", buildJsonArray { add(0) }); put("awayRunsByInning", buildJsonArray { add(runs) })
                    put("batting", buildJsonArray {
                        add(localOriginalPlayerID)
                        add(GameJson.encodeToJsonElement(BattingLine(plateAppearances = runs, atBats = runs, hits = runs)))
                    })
                    put("pitching", buildJsonArray {
                        add(localOriginalPlayerID); add(GameJson.encodeToJsonElement(PitchingLine(outsRecorded = 3)))
                    })
                    put("fielding", buildJsonArray {
                        add(localOriginalPlayerID); add(GameJson.encodeToJsonElement(FieldingLine(putouts = 2)))
                    })
                })
            })
        }

        val store = object : TeamDirectoryStorage {
            override val monitor = Any()
            var value = TeamDirectoryState(deviceID = deviceID)
            override fun load() = value
            override fun save(state: TeamDirectoryState) { value = state }
        }
        val transport = TeamTransport { method, path, _ ->
            check(method == "GET")
            when {
                path == "teams" -> GameJson.encodeToJsonElement(directory.get()).jsonObject
                path == "teams/$teamID" -> GameJson.encodeToJsonElement(SharedTeamDetail(profile, member.role, member.memberID)).jsonObject
                path.startsWith("teams/$teamID/games?cursor=") -> buildJsonObject {
                    put("cursor", revision.get()); put("hasMore", false); put("recoveryEpoch", directory.get().recoveryEpoch)
                    put("games", buildJsonArray {
                        add(match(finalGameID, true, false)); add(match(ongoingGameID, false, false))
                    })
                }
                path == "teams/$teamID/games/$finalGameID" -> {
                    reads.incrementAndGet()
                    match(finalGameID, true, true)
                }
                else -> error("Unexpected request $path")
            }
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        val db = LocalDatabase.open(inst.targetContext)
        try {
            val sharing = AndroidTeamSharing(GameRepository(db), TeamDirectorySession(TeamDirectoryClient(transport), store, "Statistics UI"))
            inst.runOnMainSync {
                val model = ViewModelProvider(activity.activity)[ScorekeeperViewModel::class.java]
                activity.activity.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .semantics { testTagsAsResourceId = true }) {
                            StatisticsScreen(model, sharing, localRosters = emptyList(), localGames = emptyList())
                        }
                    }
                }
            }
            fun node(tag: String) = NativeUi.find(device, By.res(tag))
            assertTrue(device.wait(Until.hasObject(By.text("1 场比赛 · 1胜0负0平")), 15_000))
            assertEquals(1, reads.get())
            assertNotNull(NativeUi.find(device, By.textContains(profile.name)))
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null), "083-shared-statistics.png"))

            revision.incrementAndGet()
            node("stats-refresh-shared").click()
            assertTrue(device.wait(Until.hasObject(By.text("按最近同步的已结束比赛汇总。")), 10_000))
            assertEquals(2, reads.get())
            assertEquals("1 场比赛 · 1胜0负0平", node("stats-team-record").text)
            StatisticsUi.use(device, By.res("stats-player-$officialPlayerID"), true) { it.click() }
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("打席 2 · 打数 2 · 安打 2")), 10_000))
            device.pressBack()

            directory.updateAndGet { it.copy(teams = emptyList()) }
            StatisticsUi.use(device, By.res("stats-refresh-shared"), true) { it.click() }
            assertTrue(device.wait(Until.hasObject(By.text("还没有球队")), 10_000))
        } finally {
            db.close()
        }
    }
}
