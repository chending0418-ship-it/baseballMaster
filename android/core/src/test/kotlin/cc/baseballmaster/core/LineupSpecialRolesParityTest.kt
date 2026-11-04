package cc.baseballmaster.core

import org.junit.Assert.*
import org.junit.Test

class LineupSpecialRolesParityTest {
    private fun seed(rules:Rules):Game {
        fun team(id:String)=Team(id,id,(1..13).map {Player("$id-$it","球员$it",it.toString())})
        return MatchFactory.create("special-lineup",team("home"),team("away"),rules)
    }
    @Test fun pitchingOnlyKeepsOriginalTwoWayPlayerAsDhAndPreservesOriginalPitchingCredit() {
        var g=seed(Rules(designatedHitter=true,twoWayPlayer=true))
        repeat(3){g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.ball))}
        val original=g.pitcher.id;val incoming="home-10"
        val revised=g.homePositions!!.toMutableMap().apply {put(1,incoming)}
        val result=MatchEngine.apply(g,Command("lineup",homeSide=true,order=g.homeOrder,positions=revised,designatedHitterID=g.homeDesignatedHitterID))
        assertEquals(incoming,result.pitcher.id)
        assertEquals(g.homeOrder,result.homeOrder);assertEquals(original,result.homeDesignatedHitterID)
        assertTrue(original in result.homeOrder!!);assertFalse(original in result.homePositions!!.values)
        assertFalse(incoming in result.homeOrder!!);assertFalse(original in result.exitedPlayers)
        assertEquals(g.pitching[original],result.pitching[original]);assertEquals(g.balls,result.balls)
        assertEquals(original,result.walkResponsiblePitcherID)
        Participation.validate(result.rules,result.homeOrder!!,result.homePositions!!,result.homeDesignatedHitterID)
    }
    @Test fun normalTwoWayReplacementTakesBothPitchingAndDhUnlikePitchingOnly() {
        var g=seed(Rules(designatedHitter=true,twoWayPlayer=true))
        g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.ball))
        val original=g.pitcher.id;val incoming="home-10"
        val result=MatchEngine.apply(g,Command("lineup",homeSide=true,
            order=g.homeOrder!!.map {if(it==original)incoming else it},
            positions=g.homePositions!!.mapValues {if(it.value==original)incoming else it.value},
            designatedHitterID=incoming,lineupReplacements=mapOf(original to incoming)))
        assertEquals(incoming,result.pitcher.id);assertEquals(incoming,result.homeDesignatedHitterID)
        assertTrue(incoming in result.homeOrder!!);assertFalse(original in result.homeOrder!!)
        assertTrue(original in result.exitedPlayers);assertEquals(g.totalPitches,result.totalPitches)
    }
    @Test fun slowPitchExtraHitterRotatesIntoDefenseWithoutChangingOrderOrFieldingCount() {
        for(position in listOf(1,2,8)) {
            var g=seed(Rules(GameMode.slowPitch))
            g=MatchEngine.apply(g,Command("lineup",homeSide=true,order=g.home.players.take(12).map {it.id},positions=g.homePositions))
            g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.ball))
            val outgoing=g.homePositions!!.getValue(position);val incoming="home-11"
            val result=MatchEngine.apply(g,Command("lineup",homeSide=true,order=g.homeOrder,
                positions=g.homePositions!!.toMutableMap().apply {put(position,incoming)}))
            assertEquals(g.homeOrder,result.homeOrder)
            assertEquals(10,result.homePositions!!.size);assertEquals(10,result.homePositions!!.values.distinct().size)
            assertEquals(incoming,result.homePositions!![position]);assertFalse(outgoing in result.homePositions!!.values)
            assertTrue(outgoing in result.homeOrder!!);assertFalse(outgoing in result.exitedPlayers)
            assertEquals(g.totalPitches,result.totalPitches);assertEquals(g.balls,result.balls)
            assertEquals(g.batter.id,result.batter.id)
            assertEquals(g.pitching,result.pitching)
        }
    }
    @Test fun slowPitchPitcherRotationNeverErasesPriorExitHistory() {
        var g=seed(Rules(GameMode.slowPitch))
        g=MatchEngine.apply(g,Command("lineup",homeSide=true,order=g.home.players.take(12).map {it.id},positions=g.homePositions))
        g=MatchEngine.apply(g,Command("pitch",pitch=PitchAction.ball))
        val original=g.pitcher.id;g.exitedPlayers=setOf(original,"home-13")
        val result=MatchEngine.apply(g,Command("lineup",homeSide=true,order=g.homeOrder,positions=g.homePositions!!.toMutableMap().apply {put(1,"home-11")}))
        assertTrue(original in result.exitedPlayers);assertTrue("home-13" in result.exitedPlayers)
    }
    @Test fun numberOnlyDisplayNameKeepsEmptyLanguageFieldsAndSameIdentityWithoutWeakeningNumbers() {
        val g=seed(Rules());val player=g.away.players.first()
        val corrected=player.copy(name="00号球员",chineseName="",englishName="",number="00",jerseyNumbers=listOf("00","0"))
        val result=MatchEngine.apply(g,Command("lineup",homeSide=false,order=g.awayOrder,positions=g.awayPositions,playerUpdates=listOf(corrected)))
        assertEquals(player.id,result.batter.id);assertEquals("00号球员",result.batter.name)
        assertEquals("",result.batter.chineseName);assertEquals("",result.batter.englishName)
        assertEquals(listOf("00","0"),result.batter.jerseyNumbers)
        try {Roster.validate(corrected.copy(name="100号球员",number="100",jerseyNumbers=listOf("100")));fail("Invalid jersey accepted")}catch(_:IllegalArgumentException){}
    }
}
