package cc.baseballmaster.core

object MatchFactory {
    /** Same default batting/defensive participation as iOS configuredParticipation. */
    fun create(id:String,home:Team,away:Team,rules:Rules,ourTeamID:String?=null,scheduledAt:Long?=null,practice:Boolean=false):Game {
        require(home.players.size>=rules.fielders&&away.players.size>=rules.fielders) { "双方至少需要 ${rules.fielders} 名球员" }
        fun positions(team:Team)=team.players.take(rules.fielders).mapIndexed{i,p->i+1 to p.id}.toMap()
        fun order(team:Team):List<String> {
            val ids=team.players.take(rules.fielders).map{it.id}.toMutableList()
            if(rules.dhEnabled&&!rules.twoWayEnabled)team.players.getOrNull(rules.fielders)?.let{ids[0]=it.id}
            return ids
        }
        fun dh(team:Team):String? = if(!rules.dhEnabled)null else if(rules.twoWayEnabled)team.players.first().id else team.players.getOrNull(rules.fielders)?.id
        return Game(id,home,away,rules,homeOrder=order(home),awayOrder=order(away),homePositions=positions(home),awayPositions=positions(away),
            homePitcherID=home.players.first().id,awayPitcherID=away.players.first().id,homeDesignatedHitterID=dh(home),awayDesignatedHitterID=dh(away),
            homeRunsByInning=List(rules.innings){0},awayRunsByInning=List(rules.innings){0},ourTeamID=ourTeamID,scheduledAt=scheduledAt,isScheduled=scheduledAt!=null,practice=practice).also{it.validate()}
    }
}
