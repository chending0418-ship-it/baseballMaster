package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.GameMode
import cc.baseballmaster.core.MatchFactory
import cc.baseballmaster.core.RemoteTeam
import cc.baseballmaster.core.Rules
import cc.baseballmaster.core.SharedTeamBinding
import cc.baseballmaster.core.Team
import cc.baseballmaster.core.TeamRole
import cc.baseballmaster.core.TeamScheduleFeed
import cc.baseballmaster.core.TeamScheduleNotice
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingSchedulesTest {
    private val now = Instant.parse("2030-11-18T14:00:00Z").toEpochMilli()

    @Test fun futureNoticesSortWithLocalArrangementsAndRetainCancellations() {
        val own = Team(id(), "北岸蓝队", emptyList())
        val local = localSchedule(own, now + 1_800_000, "本机球场")
        val club = membership("北岸蓝队")
        val early = notice(club, id(), now + 600_000).copy(cancelled = true)
        val late = notice(club, id(), now + 3_600_000)
        val expired = notice(club, id(), now - 1)
        val feed = TeamScheduleFeed(notices = listOf(late, expired, early), memberships = listOf(club))

        val rows = upcomingSchedules(listOf(local), feed, now)

        assertEquals(listOf(early.startsAt, local.scheduledAt, late.startsAt), rows.map { it.startsAt })
        assertEquals(early, (rows.first() as UpcomingSchedule.Member).notice)
        assertTrue((rows.first() as UpcomingSchedule.Member).notice.cancelled)
        val localOnly = rows[1] as UpcomingSchedule.Local
        assertSame(local, localOnly.game)
        assertEquals("本机球场", localOnly.game.scheduleVenue)
        assertNull(localOnly.publication)
        assertNull(localOnly.notice)
        assertEquals(listOf(local.scheduledAt, late.startsAt),
            upcomingSchedules(listOf(local), feed, early.startsAt + 1).map { it.startsAt })
    }

    @Test fun sameSourceInTwoSameNamedClubsOnlyCoalescesWithItsBoundLocalClub() {
        val own = Team(id(), "北岸蓝队", emptyList())
        val local = localSchedule(own, now + 600_000)
        val first = membership("北岸蓝队")
        val second = membership("北岸蓝队")
        val matched = notice(first, local.id.uppercase(), now + 900_000).copy(venue = "已改期场地")
        val independent = notice(second, local.id, now + 1_200_000)
        val feed = TeamScheduleFeed(
            notices = listOf(independent, matched),
            memberships = listOf(first, second),
            bindings = listOf(SharedTeamBinding(own.id, first.id)),
        )

        val rows = upcomingSchedules(listOf(local), feed, now)

        assertEquals(2, rows.size)
        val linked = rows.first() as UpcomingSchedule.Local
        assertSame(local, linked.game)
        assertEquals(matched.startsAt, linked.startsAt)
        assertEquals(matched, linked.notice)
        assertEquals(independent.key, (rows[1] as UpcomingSchedule.Member).notice.key)
    }

    @Test fun leavingAClubHidesItsCachedNoticesWithoutRemovingLocalOrAnotherMembership() {
        val local = localSchedule(Team(id(), "北岸蓝队", emptyList()), now + 100_000)
        val left = membership("北岸蓝队")
        val remaining = membership("北岸蓝队")
        val stale = notice(left, id(), now + 500_000)
        val authorized = notice(remaining, id(), now + 600_000)
        val feed = TeamScheduleFeed(notices = listOf(stale, authorized), memberships = listOf(left, remaining))

        assertEquals(3, upcomingSchedules(listOf(local), feed, now).size)
        val rows = upcomingSchedules(listOf(local), feed.copy(memberships = listOf(remaining)), now)

        assertSame(local, (rows.first() as UpcomingSchedule.Local).game)
        assertEquals(listOf(authorized), rows.filterIsInstance<UpcomingSchedule.Member>().map { it.notice })
        assertEquals(listOf(rows.first()), upcomingSchedules(listOf(local), feed.copy(memberships = emptyList()), now))
    }

    private fun id() = UUID.randomUUID().toString()
    private fun membership(name: String) = RemoteTeam(id(), name, 1, TeamRole.member, id())
    private fun notice(team: RemoteTeam, source: String, date: Long) =
        TeamScheduleNotice(team.id, source, date, "北岸蓝队", "飞鹰队", "北岸球场", false, 1)

    private fun localSchedule(own: Team, date: Long, venue: String? = null) =
        MatchFactory.schedule(id(), Team(id(), "飞鹰队", emptyList(), isOpponent = true), own,
            Rules(GameMode.standard), date, own.id, venue)
}
