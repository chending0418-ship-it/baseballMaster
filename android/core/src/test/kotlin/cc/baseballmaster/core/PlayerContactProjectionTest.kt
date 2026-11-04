package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class PlayerContactProjectionTest {
    private fun seed(): Game {
        fun team(id:String)=Team(id,id,(1..9).map {Player("$id-$it","球员$it",it.toString())})
        return MatchFactory.create("contacts",team("home"),team("away"),Rules())
    }
    private fun final(game:Game)=game.copy(isFinal=true)
    private val point=Observation(type="line",precision="point",x=.42,y=.27)

    @Test fun stableIdentityIncludesRenamedPlayerAndExcludesSameNamedOtherBatterAndPitchRunnerNotes() {
        val game=seed().copy(isFinal=true,entries=listOf(
            PitchEntry("hit",text="旧名：一垒安打",kind="play",batterID="away-1",outcome=Outcome.single,observation=point),
            PitchEntry("other-player",text="相同名字",kind="play",batterID="away-2",outcome=Outcome.double,observation=point),
            PitchEntry("pitch",text="投球",kind="pitch",batterID="away-1",observation=point),
            PitchEntry("runner",text="盗垒",kind="runner",batterID="away-1",observation=point),
            PitchEntry("note",text="备注",kind="note",batterID="away-1",observation=point)))
        game.away=game.away.copy(players=game.away.players.map {it.copy(name="更正后的姓名")})
        val result=PlayerContactProjection.calculate("away-1",listOf(game))
        assertEquals(1,result.contacts.size)
        assertEquals("contacts/hit",result.contacts.single().id)
        assertEquals("旧名：一垒安打",result.contacts.single().originalText)
        assertEquals(FieldPoint(.42,.27),result.contacts.single().point)
        assertEquals(1,result.hitCount)
    }

    @Test fun regionAndUnknownNeverBecomeExactCoordinatesAndOutcomesRetainTheirClassification() {
        val game=seed().copy(isFinal=true,entries=listOf(
            PitchEntry("region",text="区域出局",kind="play",batterID="away-1",outcome=Outcome.flyOut,observation=Observation(precision="region",region="left")),
            PitchEntry("unknown",text="未记位置",kind="play",batterID="away-1",outcome=Outcome.error),
            PitchEntry("invalid",text="非法旧坐标",kind="play",batterID="away-1",outcome=Outcome.single,observation=point.copy(x=Double.NaN))))
        val result=PlayerContactProjection.calculate("away-1",listOf(game))
        assertEquals(3,result.contacts.size)
        assertTrue(result.contacts.all {it.point==null})
        assertEquals("left",result.contacts.first().region)
        assertEquals(2,result.unknownCount)
        assertEquals(1,result.outCount);assertEquals(1,result.otherCount);assertEquals(1,result.hitCount)
    }

    @Test fun cancelledAndUndoneCommittedContactsDisappearDespiteRetainedJournal() {
        val hit=MatchEngine.apply(seed(),Command("play",outcome=Outcome.homeRun,observation=point))
        assertEquals(1,PlayerContactProjection.calculate("away-1",listOf(final(hit))).contacts.size)
        val undone=MatchEngine.undo(hit)
        assertTrue(undone.journal.isNotEmpty())
        assertTrue(PlayerContactProjection.calculate("away-1",listOf(final(undone))).contacts.isEmpty())
        val canceled=MatchEngine.apply(hit,Command("adjudication",runnerKind="catcherInterference",
            adjudication=Ruling(previousPlay="cancel",plateAppearance="batterFirst"),decisions=listOf(Decision("away-1",0,1))))
        assertTrue(canceled.journal.isNotEmpty())
        assertTrue(PlayerContactProjection.calculate("away-1",listOf(final(canceled))).contacts.isEmpty())
    }

    @Test fun doesNotExpandSelectedScopeOrIncludePracticeScheduledOrUnfinishedGames() {
        val game=seed().copy(isFinal=true,entries=listOf(PitchEntry("hit",text="安打",kind="play",batterID="away-1",outcome=Outcome.single,observation=point)))
        val result=PlayerContactProjection.calculate("away-1",listOf(game,game.copy(id="practice",practice=true),
            game.copy(id="scheduled",isScheduled=true),game.copy(id="unfinished",isFinal=false)))
        assertEquals(1,result.gameCount);assertEquals(listOf("contacts/hit"),result.contacts.map {it.id})
        assertTrue(PlayerContactProjection.calculate("home-unknown",listOf(game)).contacts.isEmpty())
        assertEquals(0,PlayerContactProjection.calculate("away-1",emptyList()).gameCount)
    }
}
