package cc.baseballmaster.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.*

/** Inspection/checkpoint projection only. Original wire JSON remains required:
 * the Native Game does not replace event/appearance graphs or correction Data.
 * This class grants neither edit permission nor a lossless-export capability.
 */
internal class AppleStateProjection private constructor(
    private val value: Game,val stored: JsonObject,val state: JsonObject,
    val preservedOnlyFields: Set<String>,val warnings: Set<String>,
) {
    fun view(): Game=GameJson.decodeFromString(GameJson.encodeToString(value))
    companion object {
        internal fun date(value: JsonElement?): Long? = value?.takeUnless {it==JsonNull}?.jsonPrimitive?.let {
            require(!it.isString)
            BigDecimal(it.content).add(BigDecimal("978307200")).movePointRight(3).setScale(0,RoundingMode.FLOOR).longValueExact()
        }
        private fun JsonObject.optional(key: String)=get(key)?.takeUnless {it==JsonNull}
        private fun JsonObject.text(key: String)=getValue(key).jsonPrimitive.content
        private fun JsonObject.number(key: String,default: Int=0)=optional(key)?.jsonPrimitive?.int ?: default
        private fun JsonObject.flag(key: String,default: Boolean=false)=optional(key)?.jsonPrimitive?.boolean ?: default
        private fun JsonObject.array(key: String)=optional(key)?.jsonArray ?: JsonArray(emptyList())
        private fun uuid(value: JsonElement)=appleUUID(value.jsonPrimitive.content)
        private fun ids(value: JsonArray)=value.map(::uuid)
        private fun pairs(value: JsonArray,key: (JsonElement)->String): Map<String,JsonElement> {
            require(value.size%2==0) {"iOS 字典资料不完整"}
            val rows=value.chunked(2).map {key(it[0]) to it[1]}
            require(rows.map {it.first}.distinct().size==rows.size) {"iOS 字典编号重复"}
            return rows.toMap()
        }
        private fun player(p: JsonObject): Player {
            val numbers=p.array("numbers").map {it.jsonPrimitive.int}
            val labels=p.optional("jerseyNumbers")?.jsonArray?.map {it.jsonPrimitive.content} ?: numbers.map(Int::toString)
            require(labels.map {it.toIntOrNull()}==numbers) {"背号标签与数字不一致"}
            val cn=p.text("chineseName");val en=p.text("englishName");val number=labels.firstOrNull() ?: "—"
            return Player(uuid(p.getValue("id")),cn.ifEmpty {en.ifEmpty {"#$number"}},number,en,p.number("primaryPosition"),labels,cn)
        }
        private fun team(raw: JsonObject,our: String?): Team = Team(uuid(raw.getValue("id")),raw.text("name"),raw.array("players").map {player(it.jsonObject)},
            isOpponent=uuid(raw.getValue("id"))!=our,city=raw.optional("city")?.jsonPrimitive?.content.orEmpty(),shortName=raw.optional("shortName")?.jsonPrimitive?.content ?: raw.text("name"))
        internal fun rules(raw: JsonObject): Rules {
            val mode=GameMode.valueOf(raw.optional("mode")?.jsonPrimitive?.content ?: "standard")
            require(raw.number("fieldersCount")==if(mode==GameMode.slowPitch) 10 else 9) {"此守备人数暂不能映射为安卓规则"}
            return Rules(mode=mode,initialBalls=raw.number("initialBalls",if(mode==GameMode.slowPitch) 1 else 0),initialStrikes=raw.number("initialStrikes",if(mode==GameMode.slowPitch) 1 else 0),
                coachPitchLimit=raw.number("coachPitchLimit",6),oneExtraFoul=raw.optional("twoStrikeFoulPolicy")?.jsonPrimitive?.content!="outImmediately",
                innings=raw.number("scheduledInnings"),timed=(raw.optional("competitionFormat")?.jsonPrimitive?.content ?: if(mode==GameMode.slowPitch) "timed" else "innings")=="timed",
                timeLimitMinutes=raw.optional("timeLimitMinutes")?.jsonPrimitive?.int,inningsLimit=raw.optional("inningsLimit")?.jsonPrimitive?.int,
                halfRunLimit=raw.optional("halfInningRunLimit")?.jsonPrimitive?.int,pitchLimit=raw.optional("pitchLimit")?.jsonPrimitive?.int,pitcherInningsLimit=raw.optional("pitcherInningsLimit")?.jsonPrimitive?.int,
                recordingMethod=raw.optional("recordingMethod")?.jsonPrimitive?.content ?: "traditional",designatedHitter=raw.flag("usesDesignatedHitter"),twoWayPlayer=raw.flag("allowsTwoWayPlayer"),
                timeWarningMinutes=raw.optional("timeWarningMinutes")?.jsonPrimitive?.int,pitchWarningRemaining=raw.optional("pitchWarningRemaining")?.jsonPrimitive?.int)
        }
        fun read(stored: JsonObject,state: JsonObject=stored.getValue("state").jsonObject): AppleStateProjection {
            // Validate the full record before projecting a selected checkpoint.
            AppleStoredGameValidation.validate(stored)
            val our=stored.optional("ourTeamID")?.let(::uuid)
            val home=team(state.getValue("homeTeam").jsonObject,our);val away=team(state.getValue("awayTeam").jsonObject,our)
            val rules=rules(state.optional("rulesSnapshot")?.jsonObject ?: stored.getValue("rules").jsonObject)
            val homeOrder=ids(state.array("homeBattingOrderIDs"));val awayOrder=ids(state.array("awayBattingOrderIDs"))
            fun positions(t: Team,side: String,order: List<String>): Map<Int,String> {
                val fielders=state.optional("${side}FieldingPlayerIDs")?.jsonArray?.let(::ids) ?: order
                val people=t.players.associateBy {it.id}
                return fielders.associateBy {people.getValue(it).position}.also {require(it.size==fielders.size && it.keys==(1..rules.fielders).toSet()) {"守位无法完整映射"}}
            }
            val bases=MutableList<String?>(3) {null}
            pairs(state.array("baseRunners")) {it.jsonPrimitive.int.toString()}.forEach {(key,p) -> val index=key.toInt();require(index in 1..3);bases[index-1]=uuid(p.jsonObject.getValue("id"))}
            val batting=pairs(state.array("batting"),::uuid).mapValues {GameJson.decodeFromJsonElement<BattingLine>(it.value)}.toMutableMap()
            val pitching=pairs(state.array("pitching"),::uuid).mapValues {GameJson.decodeFromJsonElement<PitchingLine>(it.value)}.toMutableMap()
            val fielding=pairs(state.array("fielding"),::uuid).mapValues {GameJson.decodeFromJsonElement<FieldingLine>(it.value)}.toMutableMap()
            val homeRuns=state.array("homeRunsByInning").map {it.jsonPrimitive.int};val awayRuns=state.array("awayRunsByInning").map {it.jsonPrimitive.int}
            fun id(key: String)=state.optional(key)?.let(::uuid)
            val events=state.array("scoringEvents").map {it.jsonObject}
            val history=state.optional("historyJournal")?.jsonObject
            val elapsed=state.optional("clockElapsedSeconds")?.jsonPrimitive?.content?.let {BigDecimal(it).movePointRight(3).setScale(0,RoundingMode.FLOOR).longValueExact()} ?: 0L
            require(elapsed>=0) {"比赛计时无效"}
            val started=date(stored.optional("startedAt"));val running=date(state.optional("clockRunningSince"))
            val endReasons=mapOf("规定局数完成" to "regulation","再见分结束" to "walkOff","时间限制结束" to "timeLimit","提前结束规则" to "mercyRule","弃权结束" to "forfeit","天气或场地原因" to "weather","比赛中断" to "suspended","记录员结束记录" to "scorerDecision")
            val keys=state.array("tiebreakPlacementKeys").map {it.jsonPrimitive.content}.toMutableSet()
            val tbBases=state.optional("tiebreakRunnerBases")?.jsonArray?.map {it.jsonPrimitive.int}?.ifEmpty {listOf(2)} ?: listOf(2)
            // iOS older snapshots may mark an entire half as already arranged.
            keys.toList().filter {Regex("[1-9][0-9]*-(?:top|bottom)").matches(it)}.forEach {half ->tbBases.forEach {keys+="$half-base$it"}}
            val g=Game(uuid(stored.getValue("id")),home,away,rules,
                inning=state.number("inning",1),isTop=state.flag("isTop",true),balls=state.number("balls"),strikes=state.number("strikes"),outs=state.number("outs"),
                homeBatterIndex=state.number("homeBatterIndex"),awayBatterIndex=state.number("awayBatterIndex"),appearancePitches=state.number("plateAppearancePitchCount"),
                totalPitches=pitching.values.fold(0) {sum,p ->Math.addExact(sum,p.pitches)},extraFoulUsed=state.flag("extraFoulUsed"),bases=bases,
                homeRuns=homeRuns.fold(0,Math::addExact),awayRuns=awayRuns.fold(0,Math::addExact),revision=stored.optional("revision")?.jsonPrimitive?.long ?: history?.array("operations")?.size?.toLong() ?: 0,
                homeOrder=homeOrder,awayOrder=awayOrder,homePositions=positions(home,"home",homeOrder),awayPositions=positions(away,"away",awayOrder),
                homeDesignatedHitterID=id("homeDesignatedHitterID"),awayDesignatedHitterID=id("awayDesignatedHitterID"),homePitcherID=id("activeHomePitcherID"),awayPitcherID=id("activeAwayPitcherID"),
                exitedPlayers=(ids(state.array("homeExitedPlayerIDs"))+ids(state.array("awayExitedPlayerIDs"))).toSet(),
                batting=batting,pitching=pitching,fielding=fielding,homeRunsByInning=homeRuns,awayRunsByInning=awayRuns,
                homeHits=state.number("homeHits"),awayHits=state.number("awayHits"),homeErrors=state.number("homeErrors"),awayErrors=state.number("awayErrors"),
                runnerPitcherIDs=pairs(state.array("runnerPitcherIDs"),::uuid).mapValues {uuid(it.value)}.toMutableMap(),unearnedRunnerIDs=ids(state.array("unearnedRunnerIDs")).toSet(),automaticRunnerIDs=ids(state.array("automaticRunnerIDs")).toSet(),
                walkResponsiblePitcherID=id("walkResponsiblePitcherID"),strikeoutResponsibleBatterID=id("strikeoutResponsibleBatterID"),isFinal=state.flag("isFinal"),
                endReason=state.optional("endReason")?.jsonPrimitive?.content?.let {endReasons.getValue(it)},endedAt=date(state.optional("endedAt")),
                scheduledAt=date(stored.optional("scheduledAt")),playedAt=date(stored.optional("playedAt")),playedTimeZoneID=stored.optional("playedTimeZoneID")?.jsonPrimitive?.content ?: "Asia/Shanghai",
                dateRevisions=stored.array("dateRevisions").map {it.toString()},pendingDecision=state.optional("pendingDecision")?.jsonPrimitive?.content,
                halfEndedAwaitingDecision=state.flag("halfEndedAwaitingDecision"),dismissedRunLimitHalf=state.optional("dismissedRunLimitHalf")?.jsonPrimitive?.content,dismissedWalkOffHalf=state.optional("dismissedWalkOffHalf")?.jsonPrimitive?.content,
                clockElapsedMs=elapsed,clockRunningSince=running,clockHasStarted=running!=null || elapsed>0,
                openingPitchRecorded=stored.optional("openingPitchRecorded")?.jsonPrimitive?.boolean ?: true,
                isScheduled=stored.number("status")==2,startedAt=started,ourTeamID=our,incompletePrefix=history?.flag("legacyPrefix") ?: events.isNotEmpty(),
                statisticsIncomplete=state.flag("statisticsIncomplete"),extraInningConfirmed=state.flag("extraInningConfirmed"),tiebreakStartInning=state.optional("tiebreakStartInning")?.jsonPrimitive?.int,
                tiebreakRunnerBases=tbBases,tiebreakPlacementKeys=keys,clockDisplayMode=state.optional("clockDisplayMode")?.jsonPrimitive?.content ?: if(rules.timed) "remaining" else "elapsed",
                historySourceID=stored.optional("historySourceID")?.let(::uuid),createdAt=requireNotNull(date(stored.getValue("createdAt"))),updatedAt=requireNotNull(date(stored.getValue("updatedAt"))))
            val usage=pitcherUsage(state,rules.mode)
            g.pitcherInnings=usage.first.toMutableMap()
            g.validate()
            val warnings=buildSet {
                if(usage.second) add("pitcherInningHistoryIncomplete")
                if(rules.mode==GameMode.coachPitch) add("totalPitchesRequiresJournalReplay")
                if(stored.flag("isSpectator")) add("spectator")
                if(stored.optional("sharedTeamID")!=null || stored.optional("sharedGameID")!=null) add("sharedAuthorizationNotGranted")
                if(state.flag("pitchingOutsReviewRequired")) add("pitchingOutsReviewRequired")
                if(state.number("pitchingOutsAccountingVersion")!=2) add("legacyPitchingOutsAccounting")
                if((home.players+away.players).any {it.jerseyNumbers.isEmpty()}) add("emptyJerseyNumber")
                if(stored.optional("playedTimeZoneID")==null) add("timeZoneAbsentInSource")
            }
            val preserved=state.keys-setOf("homeTeam","awayTeam","rulesSnapshot","scheduledInnings","inning","isTop","balls","strikes","outs","homeBatterIndex","awayBatterIndex","plateAppearancePitchCount","extraFoulUsed","baseRunners","homeRunsByInning","awayRunsByInning","homeHits","awayHits","homeErrors","awayErrors","homeBattingOrderIDs","awayBattingOrderIDs","homeFieldingPlayerIDs","awayFieldingPlayerIDs","activeHomePitcherID","activeAwayPitcherID","homeDesignatedHitterID","awayDesignatedHitterID","homeExitedPlayerIDs","awayExitedPlayerIDs","batting","pitching","fielding","runnerPitcherIDs","unearnedRunnerIDs","automaticRunnerIDs","walkResponsiblePitcherID","strikeoutResponsibleBatterID","isFinal","endReason","endedAt","pendingDecision","halfEndedAwaitingDecision","dismissedRunLimitHalf","dismissedWalkOffHalf","clockElapsedSeconds","clockRunningSince","clockDisplayMode","statisticsIncomplete","extraInningConfirmed","tiebreakStartInning","tiebreakRunnerBases","tiebreakPlacementKeys")
            return AppleStateProjection(g,stored,state,preserved+setOf("baseRunners.playerSnapshots","homeTeam.isDemo","awayTeam.isDemo"),warnings)
        }
        /** Same before/after inclusion rule as GameState.pitcherInningUsage;
         * statistical IP remains the saved outsRecorded value.
         */
        private fun pitcherUsage(state: JsonObject,mode: GameMode): Pair<Map<String,Set<Int>>,Boolean> {
            if(mode==GameMode.coachPitch) return emptyMap<String,Set<Int>>() to false
            val result=mutableMapOf<String,Set<Int>>();var started=false;var opening=false;var previous: Int?=null;var incomplete=false
            fun include(s: JsonObject?) {
                val pitcher=s?.optional(if(s.flag("isTop",true)) "activeHomePitcherID" else "activeAwayPitcherID")?.let(::uuid)
                if(s==null || pitcher==null) {incomplete=true;return}
                val inning=s.number("inning",1);val half=(inning-1)*2+if(s.flag("isTop",true)) 0 else 1
                if(previous==null && half!=0 || previous?.let {half<it || half>it+1}==true) incomplete=true
                previous=half;result[pitcher]=(result[pitcher] ?: emptySet())+inning
            }
            for(raw in state.array("scoringEvents")) {
                val e=raw.jsonObject;val notation=e.optional("notation")?.jsonPrimitive?.content
                if(notation=="PREGAME-LINEUP") continue
                if(notation=="PLAY") {started=true;opening=true}
                if(e.text("category") in setOf("pitch","battedBall","runner","out","violation","tiebreak") || notation in setOf("HALF-END","RESUME")) started=true
                if(started) {include(e.optional("beforeSituation")?.jsonObject);include(e.optional("afterSituation")?.jsonObject)}
            }
            val hasState=state.optional("clockRunningSince")!=null || state.optional("clockElapsedSeconds")!=null || state.number("inning",1)>1 || !state.flag("isTop",true) || state.number("outs")>0 || state.array("baseRunners").isNotEmpty() ||
                state.array("homeRunsByInning").any {it.jsonPrimitive.int>0} || state.array("awayRunsByInning").any {it.jsonPrimitive.int>0} ||
                listOf("batting","pitching").any {key ->state.array(key).chunked(2).any {it[1].jsonObject.values.any {v ->v.jsonPrimitive.int!=0}}}
            if(started || hasState) {include(state);if(!opening) incomplete=true}
            return result to incomplete
        }
    }
}
