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
class LineupProfileStorageTest {
    private fun database()=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),LocalDatabase::class.java).build()
    private val roster=(1..12).joinToString("\n"){"球员$it,$it"}
    private fun command(g:Game,home:Boolean=false):Command {
        val player=(if(home)g.home else g.away).players.first()
        return Command("lineup",homeSide=home,order=if(home)g.homeOrder else g.awayOrder,positions=if(home)g.homePositions else g.awayPositions,
            playerUpdates=listOf(player.copy(name="更正姓名",chineseName="更正姓名",englishName="Corrected Player",number="00",jerseyNumbers=listOf("00","0"))))
    }
    @Test fun profileSavesToMatchAndSameLocalIdentityWithoutChangingOlderGamesOrRosterMembership():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db);val own=repo.createTeam("本机球队",roster)
            val old=repo.createGame(own,"旧对手",roster,GameMode.standard,false)
            var game=repo.createGame(own,"新对手",roster,GameMode.standard,false)
            game=repo.pitch(game.id,PitchAction.ball,game.revision)
            val currentTeam=GameJson.decodeFromString<Team>(db.dao().team(own.id)!!.payload)
            // A local roster's independent archival/position metadata is retained.
            val archived=currentTeam.copy(city="上海",players=currentTeam.players.mapIndexed { i,p->if(i==0)p.copy(archived=true,position=6)else p })
            repo.updateRoster(archived)
            val before=GameRepository.decode(db.dao().game(old.id)!!)
            val result=repo.command(game.id,command(game),game.revision,"profile-patch")
            val local=GameJson.decodeFromString<Team>(db.dao().team(own.id)!!.payload)
            assertEquals("更正姓名",result.batter.name);assertEquals(1,result.balls)
            assertEquals(game.batter.id,result.batter.id)
            assertEquals("上海",local.city);assertTrue(local.players.first().archived);assertEquals(6,local.players.first().position)
            assertEquals(listOf("00","0"),local.players.first().jerseyNumbers)
            assertEquals(own.players.map {it.id},local.players.map {it.id})
            assertEquals(before,GameRepository.decode(db.dao().game(old.id)!!))
            val count=db.dao().allOperations().size
            assertEquals(result,repo.command(game.id,command(game),game.revision,"profile-patch"))
            assertEquals(count,db.dao().allOperations().size)
        } finally {db.close()}
    }
    @Test fun failedCommitRollsBackGameRosterAndOperationTogether():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db);val own=repo.createTeam("回滚球队",roster)
            val game=repo.createGame(own,"对手",roster,GameMode.standard,false)
            val teams=db.dao().allTeams();val games=db.dao().allGames();val operations=db.dao().allOperations()
            try {GameRepository(db){error("synthetic commit failure")}.command(game.id,command(game),game.revision);fail("write should roll back")}catch(_:IllegalStateException){}
            assertEquals(teams,db.dao().allTeams());assertEquals(games,db.dao().allGames());assertEquals(operations,db.dao().allOperations())
            try {repo.command(game.id,command(game),game.revision+1);fail("stale write")}catch(_:IllegalStateException){}
            assertEquals(teams,db.dao().allTeams());assertEquals(games,db.dao().allGames())
        } finally {db.close()}
    }
    @Test fun preparedPregameCorrectionsCommitBothRosterAndGameThenClearDraftJournal():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db);val own=repo.createTeam("赛前球队",roster)
            val opponent=Team(UUID.randomUUID().toString(),"赛前对手",Roster.parse(roster),true)
            val initial=MatchFactory.create(UUID.randomUUID().toString(),opponent,own,Rules(),own.id)
            val prepared=MatchEngine.apply(initial,command(initial))
            val result=repo.createPreparedGame(prepared)
            assertEquals("更正姓名",GameJson.decodeFromString<Team>(db.dao().team(own.id)!!.payload).players.first().name)
            assertEquals("更正姓名",result.batter.name)
            assertEquals(0L,result.revision);assertTrue(result.journal.isEmpty());assertNull(result.checkpoint)
            assertEquals(0,result.totalPitches)
        } finally {db.close()}
    }
    @Test fun snapshotProfileCorrectionNeverCreatesLocalRosterOrRemoteAuthority():Unit=runBlocking {
        val db=database()
        try {
            val repo=GameRepository(db)
            fun id()=UUID.randomUUID().toString()
            val profile=SharedTeamProfile(id(),"共享",5,"共享","上海",(1..12).map { SharedRosterPlayer(id(),id(),"球员$it","",listOf(it.toString())) })
            val own=LocalMatchSource.sharedSnapshot(profile)
            val opponent=Team(id(),"对手",Roster.parse(roster),true)
            val initial=MatchFactory.create(id(),opponent,own,Rules(),own.id).also {it.sharedRosterOrigin=SharedRosterOrigin.from(profile,RemoteTeam(profile.id,profile.name,profile.revision,TeamRole.member,id()))}
            val g=repo.createSharedRosterGame(initial);val teams=db.dao().allTeams()
            val result=repo.command(g.id,command(g),g.revision)
            assertEquals("更正姓名",result.batter.name)
            assertEquals(teams,db.dao().allTeams());assertNull(db.dao().team(own.id))
            assertEquals(g.sharedRosterOrigin,result.sharedRosterOrigin)
        } finally {db.close()}
    }
}
