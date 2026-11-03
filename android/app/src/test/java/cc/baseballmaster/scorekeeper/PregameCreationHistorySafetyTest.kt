package cc.baseballmaster.scorekeeper

import androidx.room.Room
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PregameCreationHistorySafetyTest {
    private fun database()=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),LocalDatabase::class.java).build()
    private val roster="陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12"
    private fun prepared(initial:Game):Game {
        val old=initial.awayOrder!!.first();val incoming=initial.away.players[9].id
        val corrected=initial.away.players.first().copy(name="陈靖森更正",chineseName="陈靖森更正")
        var result=MatchEngine.apply(initial,Command("lineup",homeSide=false,
            order=initial.awayOrder!!.map {if(it==old)incoming else it},
            positions=initial.awayPositions!!.mapValues {if(it.value==old)incoming else it.value},
            playerUpdates=listOf(corrected),lineupReplacements=mapOf(old to incoming)))
        result=MatchEngine.apply(result,Command("lineup",homeSide=true,order=result.homeOrder!!.reversed(),positions=result.homePositions))
        assertEquals(2,result.entries.size)
        assertTrue(result.entries.all {it.kind=="lineup" && it.notation=="LINEUP" && it.action==null})
        return result
    }
    @Test fun orphaningPregameEventsExplainsWhyTheOldCreationPathMarkedLegacyIncomplete() {
        fun team(id:String)=Team(id,id,Roster.parse(roster))
        val draft=prepared(MatchFactory.create("explanation",team("h"),team("a"),Rules()))
        val orphaned=draft.copy(journal=emptyList(),cursor=0,checkpoint=null)
        assertTrue(MatchEngine.upgradeLegacy(orphaned).incompletePrefix)
        // The real fix must normalize creation, not disable legacy evidence checks.
        assertFalse(draft.incompletePrefix)
    }
    @Test fun bothPregameLineupsBecomeACleanBaselineAndFirstPitchUndoRedoRemainComplete():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db)
            val own=repo.createTeam("海风少年队",roster)
            val opponent=repo.createTeam("青禾棒球队",roster,true)
            val draft=prepared(MatchFactory.create(UUID.randomUUID().toString(),opponent,own,Rules(),own.id))
            val game=repo.createPreparedGame(draft)
            assertTrue("Pregame lineup events are setup, not legacy scoring entries",game.entries.isEmpty())
            assertTrue(game.journal.isEmpty());assertEquals(0,game.cursor);assertNull(game.checkpoint)
            assertFalse(game.incompletePrefix);assertFalse(game.statisticsIncomplete)
            assertTrue(game.ruleNotices(System.currentTimeMillis()).isEmpty())
            assertEquals(draft.awayOrder,game.awayOrder);assertEquals(draft.homeOrder,game.homeOrder)
            assertEquals(draft.awayPositions,game.awayPositions);assertEquals(draft.homePositions,game.homePositions)
            assertEquals("陈靖森更正",GameJson.decodeFromString<Team>(db.dao().team(own.id)!!.payload).players.first().name)
            val first=repo.pitch(game.id,PitchAction.ball,game.revision)
            assertFalse("A newly created game must never enter the incomplete legacy fallback",first.incompletePrefix)
            assertFalse(first.statisticsIncomplete);assertTrue(first.ruleNotices(System.currentTimeMillis()).isEmpty())
            assertEquals(1,first.totalPitches);assertEquals(1,first.entries.size);assertEquals("pitch",first.entries.single().kind)
            assertEquals(1,first.journal.size);assertEquals(draft.awayOrder!!.first(),first.batter.id)
            val baseline=GameJson.decodeFromString<Game>(requireNotNull(first.checkpoint))
            assertTrue(baseline.entries.isEmpty());assertFalse(baseline.incompletePrefix)
            val undone=repo.undo(first.id,first.revision)
            assertEquals(0,undone.totalPitches);assertTrue(undone.entries.isEmpty())
            assertFalse(undone.incompletePrefix);assertFalse(undone.statisticsIncomplete)
            assertEquals(game.awayOrder,undone.awayOrder);assertEquals(game.homeOrder,undone.homeOrder)
            val redone=repo.redo(undone.id,undone.revision)
            assertFalse(redone.incompletePrefix);assertFalse(redone.statisticsIncomplete)
            assertEquals(first.entries,redone.entries);assertEquals(first.batting,redone.batting);assertEquals(first.pitching,redone.pitching)
            assertEquals(first.balls,redone.balls);assertEquals(first.totalPitches,redone.totalPitches)
        } finally {db.close()}
    }
    @Test fun observationCreationAlsoClearsOnlyValidatedSetupHistoryBeforeTheFirstPitch():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db)
            val away=repo.createTeam("观赛海风少年队",roster,true)
            val home=repo.createTeam("观赛青禾棒球队",roster,true)
            val draft=prepared(MatchFactory.create(UUID.randomUUID().toString(),home,away,Rules()))
            val created=repo.createObservationGame(draft)
            assertNull(created.ourTeamID);assertTrue(created.entries.isEmpty());assertTrue(created.journal.isEmpty())
            val first=repo.pitch(created.id,PitchAction.ball,created.revision)
            assertFalse(first.incompletePrefix);assertFalse(first.statisticsIncomplete)
            assertEquals(1,first.entries.size);assertEquals("pitch",first.entries.single().kind)
            assertTrue(first.ruleNotices(System.currentTimeMillis()).isEmpty())
        } finally {db.close()}
    }
    @Test fun nonLineupPregameHistoryIsRejectedAtomicallyBeforeAnyProfileOrGameWrite():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db)
            val own=repo.createTeam("海风少年队",roster)
            val opponent=repo.createTeam("青禾棒球队",roster,true)
            val draft=prepared(MatchFactory.create(UUID.randomUUID().toString(),opponent,own,Rules(),own.id))
            val note=MatchEngine.apply(draft,Command("note",text="不得静默丢弃的赛前备注"))
            val teams=db.dao().allTeams();val games=db.dao().allGames();val operations=db.dao().allOperations()
            val candidates=listOf(note,note.copy(journal=draft.journal,cursor=draft.cursor,checkpoint=draft.checkpoint))
            for(candidate in candidates) {
                try {repo.createPreparedGame(candidate);fail("Non-lineup preparation must not be silently discarded")}catch(_:IllegalArgumentException){}
                assertEquals(teams,db.dao().allTeams());assertEquals(games,db.dao().allGames());assertEquals(operations,db.dao().allOperations())
                assertEquals("陈靖森",GameJson.decodeFromString<Team>(db.dao().team(own.id)!!.payload).players.first().name)
            }
            assertEquals("不得静默丢弃的赛前备注",note.entries.last().text)
            assertEquals("note",note.journal.last().command.kind)
        } finally {db.close()}
    }

}
