package cc.baseballmaster.core

import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Swift HistoryCommand Codable adapter. This does not produce a StoredGame or
 * its multi-event history; formal export must validate that separate projection.
 */
internal object AppleHistoryCommandCodec {
    private fun uid(value: String)=appleUUID(value).uppercase()
    private fun date(value: Long)=JsonPrimitive(BigDecimal.valueOf(value).movePointLeft(3).subtract(BigDecimal("978307200")))
    private fun JsonObject.optional(key: String)=get(key)?.takeUnless {it==JsonNull}
    private fun idList(values: Iterable<String>)=buildJsonArray {values.forEach {add(uid(it))}}
    private fun tagged(kind: String,values: JsonObject=buildJsonObject {})=buildJsonObject {put(kind,values)}
    private fun player(p: Player,position: Int=p.position)=buildJsonObject {
        put("id",uid(p.id));put("chineseName",p.chineseName ?: p.name);put("englishName",p.englishName)
        put("numbers",buildJsonArray {p.jerseyNumbers.forEach {add(it.toInt())}});put("jerseyNumbers",buildJsonArray {p.jerseyNumbers.forEach {add(it)}})
        require(position in 1..10) {"iOS 球员守位缺失"};put("primaryPosition",position)
    }
    private fun decision(d: Decision,people: Map<String,Player>)=buildJsonObject {
        put("id",uid(d.playerID));put("player",player(people.getValue(d.playerID)))
        put("origin",if(d.origin==0) tagged("batter") else tagged("base",buildJsonObject {put("_0",d.origin)}))
        put("destination",when(d.destination) {-1->tagged("out");0->tagged("hold");4->tagged("score");in 1..3->tagged("base",buildJsonObject {put("_0",d.destination)});else->error("跑者去向无效")})
    }
    private fun moves(values: List<Decision>,people: Map<String,Player>)=buildJsonArray {values.forEach {add(decision(it,people))}}
    private fun defensive(c: Command,moves: List<Decision>): JsonObject? {
        val f=c.fielding ?: c.observation?.fielding ?: return null
        val outs=moves.count {it.destination==-1}
        val caught=c.outcome in setOf(Outcome.flyOut,Outcome.lineOut,Outcome.foulFlyOut,Outcome.sacrificeFly,Outcome.infieldFly)
        val putouts=f.putouts.ifEmpty {if(f.status=="recorded" && outs>0) if(caught) f.route.take(1) else f.route.takeLast(outs) else emptyList()}
        val assists=f.assists.ifEmpty {if(f.status=="recorded" && outs>0 && !caught) f.route.dropLast(1) else emptyList()}
        return buildJsonObject {
            put("id","ANDROID");put("title","本球守备");put("notation",f.route.joinToString("-").ifEmpty {f.errorPosition?.let {"E$it"} ?: "待补"})
            put("routePositions",GameJson.encodeToJsonElement(f.route));put("assistPositions",GameJson.encodeToJsonElement(assists));put("putoutPositions",GameJson.encodeToJsonElement(putouts));f.errorPosition?.let {put("errorPosition",it)}
        }
    }
    private val violations=mapOf("balk" to "投手犯规","quickPitch" to "快速投球","illegalPitch" to "违规投球","foreignSubstance" to "球体处理违规","batterOutOfBox" to "踏出打击区击球","batterInterference" to "打者干扰","illegalBat" to "违规球棒","battingOutOfTurn" to "打击次序错误","catcherInterference" to "捕手干扰打击","plateObstruction" to "本垒阻挡或冲撞","obstruction" to "阻挡跑垒","fakeTag" to "假触杀","detachedEquipment" to "脱离装备触球","runnerInterference" to "跑者干扰","runningLaneInterference" to "跑垒限制道干扰","coachInterference" to "教练员干扰","passingRunner" to "超越前位跑者")
    private val runners=mapOf("stolenBase" to "偷垒成功","doubleSteal" to "双重盗垒","delayedSteal" to "延迟盗垒","relayAdvance" to "守备转传时额外进垒","defensiveErrorAdvance" to "守备失误造成额外进垒","caughtStealing" to "跑垒出局","pickoff" to "牵制出局","tagOut" to "触杀出局","forceOut" to "封杀出局","appealOut" to "申诉出局","leftEarly" to "离垒过早","missedBase" to "漏踩垒位","outOfBasePath" to "跑出限制道","passedRunner" to "超越前位跑者","runnerInterference" to "跑者妨碍守备","runnerHitByBall" to "跑垒员被击球击中","coachAssistance" to "教练员帮助跑者","wildPitch" to "球太偏，跑者进垒","passedBall" to "捕手漏球，跑者进垒","uncertainLooseBall" to "球漏过去，分不清原因","balk" to "投手犯规，跑者进垒")
    private val endReasons=mapOf("regulation" to "规定局数完成","walkOff" to "再见分结束","timeLimit" to "时间限制结束","mercyRule" to "提前结束规则","forfeit" to "弃权结束","weather" to "天气或场地原因","suspended" to "比赛中断","scorerDecision" to "记录员结束记录")
    private val statuses=mapOf("live" to "活球","dead" to "死球","delayedDead" to "延迟死球","umpireDecision" to "按裁判宣判")
    private val previous=mapOf("notApplicable" to "不涉及上一比赛结果","keep" to "保留上一比赛结果","cancel" to "取消上一比赛结果")
    private val appearances=mapOf("continueAtBat" to "继续当前打席","batterOut" to "打者出局","batterFirst" to "打者上一垒")
    private val credits=mapOf("none" to "不另记打击结果","single" to "一垒安打","double" to "二垒安打","triple" to "三垒安打","homeRun" to "本垒打","reachedOnError" to "守备失误上垒","fieldersChoice" to "野手选择","sacrifice" to "牺牲打")
    fun observation(event: PitchEntry): JsonObject? = event.observation?.let {o -> buildJsonObject {
        put("type",o.type);put("precision",o.precision);o.region?.let {put("region",it)};o.x?.let {put("x",it)};o.y?.let {put("y",it)}
        fun route(f: Fielding)=buildJsonObject {
            put("status",f.status);put("route",GameJson.encodeToJsonElement(f.route));f.errorPosition?.let {put("errorPosition",it)}
            put("participants",buildJsonArray {event.participants.forEach {p -> add(buildJsonObject {put("position",p.position);put("playerID",uid(p.playerID));put("name",p.name);put("number",p.number)})}})
        }
        o.fielding?.let {put("fielding",route(it))}
        val detailed=event.decisions.filter {it.cause!="hit" || it.fielding!=null || it.route.isNotEmpty() || it.errorPosition!=null}
        if(detailed.isNotEmpty()) put("runnerDetails",buildJsonArray {detailed.forEach {d -> add(buildJsonObject {
            put("playerID",uid(d.playerID));put("cause",d.cause)
            val f=d.fielding ?: if(d.route.isNotEmpty() || d.errorPosition!=null) Fielding(route=d.route,errorPosition=d.errorPosition,status=if(d.route.isEmpty()) "pending" else "recorded") else null
            f?.let {put("fielding",route(it))}
        })}})
    }}

    /** Effective moves come from the reducer, including automatic awards. A
     * review must supply the real wire event, never guess an operation/event ID.
     */
    fun encodeForJournal(c: Command,before: Game,after: Game,effectiveMoves: List<Decision>,reviewEvent: JsonObject?=null): JsonObject {
        require(!(c.kind=="lineup" && before.isPregame)) {"赛前阵容须更新 iOS 检查点，不能写成普通历史操作"}
        return encodeAction(c,before,after,effectiveMoves,reviewEvent)
    }
    /** Action input for the original Swift executor. Pregame lineup actions are
     * deliberately distinct: iOS commits them into its next checkpoint.
     */
    fun encodeAction(c: Command,before: Game,after: Game,effectiveMoves: List<Decision>,reviewEvent: JsonObject?=null): JsonObject {
        val people=(before.home.players+before.away.players).associateBy {it.id}
        fun args(block: JsonObjectBuilder.()->Unit)=buildJsonObject(block)
        val f=when(c.kind) {
            "swap"->return tagged("swapOpeningSides")
            "pitch"->args {put("_0",requireNotNull(c.pitch).title)}
            "play"->args {put("_0",requireNotNull(c.outcome).title);defensive(c,effectiveMoves)?.let {put("_1",it)};put("_2",moves(effectiveMoves,people));c.timingRunCounts?.let {put("_3",it)}}
            "runner"->args {put("_0",runners.getValue(requireNotNull(c.runnerKind)));put("_1",moves(effectiveMoves,people));c.timingRunCounts?.let {put("_2",it)}}
            "illegal"->args {c.pitch?.let {put("_0",it.title)};put("_1",c.wasThrown);c.outcome?.let {put("_2",it.title)};defensive(c,effectiveMoves)?.let {put("_3",it)};if(c.outcome!=null) put("_4",moves(effectiveMoves,people));c.timingRunCounts?.let {put("_5",it)};put("_6",c.penaltyApplied)}
            "hitByPitch","intentionalWalk","foulBunt","endHalf","skipTB"->args {}
            "droppedThirdStrike"->args {put("_0",moves(effectiveMoves,people))}
            "pitcher","batter"->args {put("_0",uid(requireNotNull(c.playerID)))}
            "pinchRunner"->args {put("_0",requireNotNull(c.position));put("_1",uid(requireNotNull(c.playerID)))}
            "fielder"->args {put("_0",uid(requireNotNull(c.playerID)));put("_1",uid(requireNotNull(c.replacementID)))}
            "position"->args {put("_0",uid(requireNotNull(c.playerID)));put("_1",requireNotNull(c.position))}
            "decision","reopen"->args {put("_0",c.flag)}
            "extraInning"->args {put("_0",c.flag);put("_1",GameJson.encodeToJsonElement(c.runnerBases))}
            "enableTB"->args {put("_0",GameJson.encodeToJsonElement(c.runnerBases))}
            "placeTB"->args {put("_0",uid(requireNotNull(c.playerID)));c.position?.let {put("_1",it)}}
            "finish","suspend"->args {put("_0",endReasons.getValue(if(c.kind=="suspend") "suspended" else c.text));put("_1",date(c.at))}
            "clock"->args {put("_0",if(c.text=="resume") "start" else c.text);put("_1",date(c.at))}
            "note"->args {put("_0",c.text)}
            "violation"->args {put("_0",violations.getValue(requireNotNull(c.runnerKind)));put("_1",moves(effectiveMoves,people))}
            "adjudication"->args {
                val r=requireNotNull(c.adjudication);put("_0",violations.getValue(requireNotNull(c.runnerKind)))
                put("_1",buildJsonObject {
                    put("ballStatus",statuses.getValue(r.ballStatus));put("previousPlayDisposition",previous.getValue(r.previousPlay));put("plateAppearanceDisposition",appearances.getValue(r.plateAppearance))
                    put("runnerDecisions",moves(effectiveMoves,people));put("countsAsAtBat",r.countsAsAtBat);put("isFinalRuling",r.isFinal);put("battingCredit",credits.getValue(r.battingCredit));put("runsBattedIn",r.runsBattedIn);put("earnedRuns",r.earnedRuns);r.errorPlayerID?.let {put("fieldingErrorPlayerID",uid(it))}
                })
            }
            "situation"->args {
                val s=requireNotNull(c.situation)
                put("_0",buildJsonObject {
                    put("inning",s.inning);put("isTop",s.isTop);put("balls",s.balls);put("strikes",s.strikes);put("outs",s.outs);put("awayScore",s.awayScore);put("homeScore",s.homeScore);put("batterIndex",s.batterIndex);s.pitcherID?.let {put("pitcherID",uid(it))}
                    put("runners",buildJsonArray {s.bases.forEachIndexed {i,id -> if(id!=null) {add(i+1);add(player(people.getValue(id)))}}})
                })
            }
            "lineup"->args {
                val home=requireNotNull(c.homeSide);val t=if(home) after.home else after.away
                val order=requireNotNull(if(home) after.homeOrder else after.awayOrder);val positions=requireNotNull(if(home) after.homePositions else after.awayPositions)
                put("_0",buildJsonObject {
                    put("isHome",home);put("players",buildJsonArray {t.players.forEach {p -> add(player(p,positions.entries.firstOrNull {it.value==p.id}?.key ?: p.position))}})
                    put("battingIDs",idList(order));put("fieldingIDs",idList(positions.toSortedMap().values));(if(home) after.homeDesignatedHitterID else after.awayDesignatedHitterID)?.let {put("dhID",uid(it))}
                    (if(home) after.homePitcherID else after.awayPitcherID)?.let {put("pitcherID",uid(it))}
                    put("anchorID",uid(order[(if(home) after.homeBatterIndex else after.awayBatterIndex)%order.size]))
                    put("runners",buildJsonArray {after.bases.forEachIndexed {i,id -> if(id!=null) {add(i+1);add(player(people.getValue(id)))}}})
                    put("automaticIDs",idList(after.automaticRunnerIDs.sorted()));put("changes",buildJsonArray {add("阵容调整")})
                })
            }
            "doubleSwitch"->args {put("_0",uid(requireNotNull(c.playerID)));put("_1",uid(requireNotNull(c.replacementID)));put("_2",requireNotNull(c.position));put("_3",uid(requireNotNull(c.secondPlayerID)));put("_4",uid(requireNotNull(c.secondReplacementID)));put("_5",requireNotNull(c.secondPosition))}
            "review"->args {
                val source=requireNotNull(reviewEvent) {"复核缺少原始 iOS 事件对应，不能猜测编号"}
                put("_0",buildJsonObject {
                    put("id",source.getValue("id"));put("title",c.text);put("category",source.getValue("category"));put("note",c.reviewNote ?: c.text)
                    c.outcome?.let {put("resolvedOutcome",it.title);put("notation",it.notation)} ?: source.optional("notation")?.let {put("notation",it)}
                    source.optional("primaryPlayerID")?.let {put("primaryPlayerID",it)}
                    (c.playerID?.let {JsonPrimitive(uid(it))} ?: source.optional("secondaryPlayerID"))?.let {put("secondaryPlayerID",it)}
                    source.optional("ballStatus")?.let {put("ballStatus",it)}
                })
            }
            else->error("此操作没有直接的 Swift HistoryCommand：${c.kind}")
        }
        return tagged(when(c.kind) {"illegal"->"illegalPitch";"suspend"->"finish";else->c.kind},f)
    }
}
