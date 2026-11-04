package cc.baseballmaster.core

import kotlinx.serialization.json.*

/** Whitelisted public view, corresponding to iOS LiveSnapshot schema 1.
 * Never serialize Game/Team wholesale: roster libraries, original backups,
 * correction checkpoints, credentials and private metadata stay on device.
 */
object LiveProjection {
    private fun person(p: Player) = buildJsonObject {
        put("id",p.id);put("name",p.name.take(200));put("number",p.number.take(20))
    }
    private fun situation(s: Situation, inning: Int, top: Boolean) = buildJsonObject {
        val ended=s.outs>=3 || s.inning!=inning || s.isTop!=top
        put("balls",s.balls);put("strikes",s.strikes);put("outs",s.outs)
        put("halfEnded",ended);put("bases",buildJsonArray {if(!ended) s.bases.forEachIndexed {i,id -> if(id!=null) add(i+1)}})
    }
    fun create(g: Game): JsonObject {
        require(!g.practice && !g.isScheduled && g.revision in 0..100_000) {"本场暂不能生成文字直播资料"}
        require(!g.isFinal || g.endedAt!=null)
        val people=(g.home.players+g.away.players).associateBy {it.id}
        val groups=AppearanceProjection.build(g)
        val current=groups.lastOrNull { !it.complete && !it.interrupted }?.takeUnless {g.isFinal || g.halfEndedAwaitingDecision}
        val started=g.startedAt!=null || g.clockHasStarted || g.openingPitchRecorded || g.inning>1 || !g.isTop ||
            g.outs>0 || g.bases.any {it!=null} || g.homeRuns+g.awayRuns+g.homeHits+g.awayHits>0 ||
            g.balls!=g.rules.initialBalls || g.strikes!=g.rules.initialStrikes || g.batting.values.any {it.plateAppearances>0}
        fun side(home: Boolean): JsonObject {
            val t=if(home) g.home else g.away
            val order=if(home) g.homeOrder ?: t.players.map {it.id} else g.awayOrder ?: t.players.map {it.id}
            val positions=if(home) g.homePositions else g.awayPositions
            val fielders=positions?.toSortedMap()?.values?.toList() ?: order.take(g.rules.fielders)
            val dh=if(home) g.homeDesignatedHitterID else g.awayDesignatedHitterID
            val allInnings=if(home) g.homeRunsByInning else g.awayRunsByInning
            val visible=if(g.rules.timed) maxOf(g.inning,allInnings.indexOfLast {it>0}+1) else allInnings.size
            val innings=allInnings.take(visible)
            return buildJsonObject {
                put("name",t.name.take(200));put("shortName",t.shortName.take(200))
                put("runs",if(home) g.homeRuns else g.awayRuns);put("hits",if(home) g.homeHits else g.awayHits);put("errors",if(home) g.homeErrors else g.awayErrors)
                put("innings",buildJsonArray {innings.forEach {add(it)}})
                put("playedInnings",buildJsonArray {innings.forEachIndexed {i,runs ->
                    val half=i*2+if(home) 1 else 0;val active=(g.inning-1)*2+if(g.isTop) 0 else 1
                    add(started && (half<active || half==active && (!g.isFinal || g.entries.any {it.inning==i+1 && it.isTop!=home && it.kind in setOf("pitch","play","runner","hitByPitch","foulBunt","violation","adjudication")} || i==0 && !home && g.startedAt!=null) || runs>0))
                }})
                put("lineup",buildJsonArray {(order+fielders.filter {it !in order}).distinct().forEach {id ->
                    val p=people[id] ?: error("阵容球员资料不完整")
                    add(buildJsonObject {
                        put("player",person(p));order.indexOf(id).takeIf {it>=0}?.let {put("order",it+1)}
                        val position=positions?.entries?.singleOrNull {it.value==id}?.key ?: p.position
                        put("position",if(id==dh) "DH" else if(id !in fielders) "打击" else listOf("","投手","捕手","一垒手","二垒手","三垒手","游击手","左外野手","中外野手","右外野手","自由人").getOrElse(position) {"待确认"})
                    })
                }})
                (if(home) g.homePitcherID else g.awayPitcherID)?.let {put("pitcherID",it)}
            }
        }
        return buildJsonObject {
            put("schema",1);put("gameID",g.id);put("revision",g.revision);put("mode",g.rules.mode.name);put("isFinal",g.isFinal)
            put("endedAt",g.endedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("inning",g.inning);put("isTop",g.isTop);put("balls",g.balls);put("strikes",g.strikes);put("outs",g.outs)
            put("home",side(true));put("away",side(false));put("batter",person(g.batter));put("pitcher",person(g.pitcher))
            put("batterOrder",if(g.isFinal) JsonNull else JsonPrimitive(g.batterIndex+1))
            put("pitchCount",if(g.rules.mode==GameMode.coachPitch) JsonNull else JsonPrimitive(g.pitching[g.pitcher.id]?.pitches ?: 0))
            put("appearancePitchCount",g.appearancePitches);put("pitchLimit",if(g.rules.mode==GameMode.coachPitch) JsonPrimitive(g.rules.coachPitchLimit) else JsonNull)
            put("bases",buildJsonArray {g.bases.forEachIndexed {i,id -> if(id!=null) add(buildJsonObject {put("base",i+1);put("player",person(people.getValue(id)))})}})
            put("currentAppearanceID",current?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("notice",((if(g.isFinal) endReason(g.endReason) else when(g.pendingDecision) {"walkOff"->"再见分确认";"runLimit"->"半局得分上限确认";"regulationHalf"->"半局／终场确认";else->""})+
                if(g.statisticsIncomplete) " · 过程或责任待确认，统计可能不完整" else "").take(500))
            put("hasStarted",started)
            put("rules",buildJsonObject {
                put("scheduledInnings",g.rules.innings);g.rules.halfRunLimit?.let {put("halfInningRunLimit",it)};g.rules.timeLimitMinutes?.let {put("timeLimitMinutes",it)}
                if(g.rules.mode==GameMode.slowPitch) {
                    put("competitionFormat",if(g.rules.timed) "timed" else "innings");g.rules.inningsLimit?.let {put("inningsLimit",it)}
                    put("initialBalls",g.rules.initialBalls);put("initialStrikes",g.rules.initialStrikes)
                    put("twoStrikeFoulPolicy",if(g.rules.oneExtraFoul) "oneExtraFoul" else "outImmediately");put("fieldersCount",10);put("rulesVersion",2);put("extraFoulUsed",g.extraFoulUsed)
                }
            })
            put("clock",buildJsonObject {g.startedAt?.let {put("startedAt",it)};g.clockRunningSince?.let {put("runningSince",it)};put("elapsedSeconds",g.clockElapsedMs.coerceAtLeast(0)/1000.0)})
            put("batterStats",if(g.isFinal) JsonNull else buildJsonObject {val b=g.batting[g.batter.id] ?: BattingLine();put("atBats",b.atBats);put("hits",b.hits);put("isComplete",!g.statisticsIncomplete)})
            put("nextBatters",buildJsonArray {if(!g.isFinal) (1..2).forEach {offset -> val i=(g.batterIndex+offset)%g.battingOrder.size;add(buildJsonObject {put("player",person(people.getValue(g.battingOrder[i])));put("order",i+1)})}})
            put("entries",buildJsonArray {groups.filterNot {g.isFinal && it.events.isEmpty()}.forEach {a ->
                require(a.id.length<=50 && a.events.size<=2000) {"比赛过程超过当前直播容量，本机记录已保留"}
                val p=people[a.batterID];val last=a.events.lastOrNull();val active=a.id==current?.id
                val interrupted=a.interrupted || g.isFinal && !a.complete
                add(buildJsonObject {
                    put("id",a.id);put("appearanceID",a.id);put("inning",a.inning);put("isTop",a.isTop);put("kind","appearance")
                    put("label",(last?.notation?.ifBlank {"打席"} ?: "打席").take(100))
                    put("summary",(if(interrupted) "${p?.name ?: "打者"}：打席未完成，因换边或终场中断" else last?.text ?: "${p?.name ?: "打者"}准备打击").take(1000))
                    put("player",p?.let(::person) ?: JsonNull);put("status",if(a.events.any {it.incomplete}) "review" else if(interrupted) "interrupted" else if(a.complete) "completed" else "current")
                    if(active) put("order",g.batterIndex+1)
                    (if(active) g.situation() else last?.after)?.let {put("situation",situation(it,a.inning,a.isTop))}
                    put("details",buildJsonArray {a.events.forEach {e -> add(buildJsonObject {
                        put("id",e.id);put("text",(e.text+if(e.reviewedAt!=null) "（已复核修订）" else "").take(1000));e.after?.let {put("situation",situation(it,e.inning,e.isTop))}
                    })}})
                })
            }})
        }
    }
    private fun endReason(reason: String?)=mapOf("regulation" to "规定局数完成","walkOff" to "再见分结束","timeLimit" to "时间限制结束","mercyRule" to "提前结束规则","forfeit" to "弃权结束","weather" to "天气或场地原因","suspended" to "比赛中断","scorerDecision" to "记录员结束记录")[reason] ?: "比赛结束"
}
