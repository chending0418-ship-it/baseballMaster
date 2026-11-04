package cc.baseballmaster.core

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LocalMatchSourceTest {
    private fun id()=UUID.randomUUID().toString()
    private fun seed(): Game {
        val profile=SharedTeamProfile(id(),"共享",1,"共享","",(1..10).map {
            SharedRosterPlayer(id(),id(),"球员$it","",listOf(it.toString()))
        })
        val own=LocalMatchSource.sharedSnapshot(profile)
        val opponent=Team(id(),"对手",(1..10).map {Player(id(),"对手$it",it.toString())},true)
        return MatchFactory.create(id(),own,opponent,Rules(),own.id).also {
            it.sharedRosterOrigin=SharedRosterOrigin.from(profile,RemoteTeam(profile.id,profile.name,1,TeamRole.member,id()))
        }
    }
    @Test fun undecidedLocalOnlyAndSharedTargetStayDistinctAcrossHistoryAndSerialization() {
        val initial=seed()
        assertNull(GameJson.decodeFromString<Game>(GameJson.encodeToString(initial)).postgameSharingChoice)
        initial.postgameSharingChoice=PostgameSharingChoice(initial.ourTeamID,"共享目标")
        val recorded=MatchEngine.apply(initial,Command("pitch",pitch=PitchAction.ball))
        // An explicit local-only selection made after the first pitch belongs to
        // current match metadata, not an old checkpoint restored by undo/redo.
        recorded.postgameSharingChoice=PostgameSharingChoice()
        val restored=GameJson.decodeFromString<Game>(GameJson.encodeToString(recorded))
        assertEquals(PostgameSharingChoice(),MatchEngine.undo(restored).postgameSharingChoice)
        assertEquals(PostgameSharingChoice(),MatchEngine.redo(MatchEngine.undo(restored)).postgameSharingChoice)
        assertEquals(recorded.sharedRosterOrigin,restored.sharedRosterOrigin)
        assertThrows(IllegalArgumentException::class.java) {PostgameSharingChoice("not-a-team","目标")}
        assertThrows(IllegalArgumentException::class.java) {PostgameSharingChoice(null,"不能把队名当作球队身份")}
    }
    @Test fun lateAddedLocalPlayerDoesNotInventOrInvalidateSharedProvenance() {
        val game=MatchEngine.apply(seed(),Command("pitch",pitch=PitchAction.ball))
        val player=Player(id(),"漏记球员","00")
        val draft=History.draft(game).copy(reason="补漏名单",addedHomePlayers=listOf(player))
        val preview=History.preview(game,draft)
        assertNull(preview.conflict);assertTrue(preview.canSave)
        val reviewed=requireNotNull(preview.game)
        assertTrue(reviewed.home.players.any {it.id==player.id})
        assertEquals(game.sharedRosterOrigin,reviewed.sharedRosterOrigin)
        assertFalse(reviewed.sharedRosterOrigin!!.rosterIDsBySourceID.containsKey(player.id))
        reviewed.validate()
    }
    @Test fun statisticsMergeStablePlayersAcrossSnapshotsAndKeepTheLatestDisplayAndOldParticipants() {
        val old=seed().apply {updatedAt=10}
        val newer=GameJson.decodeFromString<Game>(GameJson.encodeToString(old)).copy(id=id(),updatedAt=20)
        val oldPlayer=old.home.players.last()
        val renamed=newer.home.players.first().copy(name="最新姓名")
        val newPlayer=Player(id(),"新球员","00")
        newer.home=newer.home.copy(name="最新队名",players=listOf(renamed)+newer.home.players.drop(1).dropLast(1)+newPlayer)
        newer.sharedRosterOrigin=newer.sharedRosterOrigin!!.copy(rosterRevision=2,
            rosterIDsBySourceID=(newer.sharedRosterOrigin!!.rosterIDsBySourceID-oldPlayer.id)+(newPlayer.id to id()))
        old.validate();newer.validate()
        val teams=LocalMatchSource.statisticsTeams(emptyList(),listOf(old,newer))
        assertEquals(1,teams.size)
        val team=teams.single()
        assertEquals("最新队名",team.name)
        assertEquals("最新姓名",team.players.single {it.id==renamed.id}.name)
        assertTrue(team.players.any {it.id==oldPlayer.id})
        assertTrue(team.players.any {it.id==newPlayer.id})
        assertEquals(team.players.size,team.players.map {it.id}.distinct().size)
        assertEquals("sharedSnapshot",team.source)
        assertTrue(LocalMatchSource.statisticsTeams(emptyList(),listOf(old.copy(practice=true))).isEmpty())
    }
}
