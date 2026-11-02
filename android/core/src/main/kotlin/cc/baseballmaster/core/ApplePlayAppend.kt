package cc.baseballmaster.core

import java.math.BigDecimal
import java.util.Base64
import kotlinx.serialization.json.*

/** Append a basic batted-ball transaction, including its half-end event, to the original graph. */
internal object ApplePlayAppend {
    private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it == JsonNull }
    fun append(bytes: ByteArray, gameID: String, command: Command, operationID: String): ByteArray {
        require(command.kind=="play" && command==Command("play",outcome=command.outcome,decisions=command.decisions,
            timingRunCounts=command.timingRunCounts,at=command.at)) { "此击球的观察、守备或附加审计尚未支持来源写回" }
        require(command.at>0)
        val id=appleUUID(operationID)
        val source=AppleSourceBackup.prepare(bytes)
        val game=source.games().single {it.id==appleUUID(gameID)}
        require(!game.incompletePrefix && !game.isFinal && game.pendingDecision==null &&
            !game.halfEndedAwaitingDecision && game.clockRunningSince!=null && game.journal.isNotEmpty()) { "此来源比赛暂不能写入击球" }
        val inspection=AppleBackupEnvelope.inspect(bytes)
        val stored=inspection.snapshot.getValue("games").jsonArray.map {it.jsonObject}.single {appleUUID(it.getValue("id").jsonPrimitive.content)==game.id}
        val state=stored.getValue("state").jsonObject
        val journal=state.getValue("historyJournal").jsonObject
        val date=AppleLifecycleAppend.date(command.at)
        val reserved=(listOf(id)+ (0..1).flatMap {listOf(AppleLifecycleAppend.stable(id,"event$it").lowercase(),AppleLifecycleAppend.stable(id,"log$it").lowercase())}+
            AppleLifecycleAppend.stable(id,"nextAppearance").lowercase()).toSet()
        require(reserved.size==6)
        fun check(value:JsonElement,dates:Boolean) {
            when(value) {
                is JsonObject -> {
                    value.forEach { (key,item) ->
                        if(dates && key in setOf("timestamp","correctedAt","reviewedAt","updatedAt","endedAt","clockRunningSince","date") && item!=JsonNull)
                            require(BigDecimal(date.content)>=BigDecimal(item.jsonPrimitive.content)) { "击球时间早于来源记录" }
                        check(item,dates)
                    }
                    if(value["operations"] is JsonArray && value.optional("checkpoint")!=null)
                        check(AppleWireJson.parse(Base64.getDecoder().decode(value.getValue("checkpoint").jsonPrimitive.content)),dates)
                }
                is JsonArray -> value.forEach {check(it,dates)}
                is JsonPrimitive -> if(value.isString) require(value.content.lowercase() !in reserved) { "击球编号与来源历史重复" }
            }
        }
        check(inspection.snapshot,false);check(stored,true)
        val next=MatchEngine.apply(game,command,id)
        require(!next.isFinal && next.pendingDecision==null && !next.halfEndedAwaitingDecision) { "此击球需要规则或终场确认，尚未支持来源写回" }
        val halfChanged=game.inning!=next.inning || game.isTop!=next.isTop
        val moves=next.entries.last().decisions
        val outcome=requireNotNull(command.outcome)
        val scoredCount=next.homeRuns+next.awayRuns-game.homeRuns-game.awayRuns
        val scorers=if(scoredCount==0) emptyList() else moves.filter {it.destination==4}.take(scoredCount).map {it.playerID}
        require(scorers.size==scoredCount)
        val players=(state.getValue("homeTeam").jsonObject.getValue("players").jsonArray+
            state.getValue("awayTeam").jsonObject.getValue("players").jsonArray).associate {appleUUID(it.jsonObject.getValue("id").jsonPrimitive.content) to it.jsonObject}.toMutableMap()
        state.getValue("baseRunners").jsonArray.chunked(2).forEach {players[appleUUID(it[1].jsonObject.getValue("id").jsonPrimitive.content)]=it[1].jsonObject}
        var updated=state
        fun setState(key:String,value:JsonElement) {updated=JsonObject(updated+(key to value))}
        fun remove(vararg keys:String) {updated=JsonObject(updated-keys.toSet())}
        fun ids(values:Iterable<String>)=JsonArray(values.map {JsonPrimitive(it.uppercase())})
        fun ownerPairs(values:Map<String,String>)=buildJsonArray {values.toSortedMap().forEach {(runner,pitcher)->add(runner.uppercase());add(pitcher.uppercase())}}
        for((key,value) in listOf("inning" to next.inning,"balls" to next.balls,"strikes" to next.strikes,"outs" to next.outs,
            "homeBatterIndex" to next.homeBatterIndex,"awayBatterIndex" to next.awayBatterIndex,"homeHits" to next.homeHits,"awayHits" to next.awayHits,
            "homeErrors" to next.homeErrors,"awayErrors" to next.awayErrors,"plateAppearancePitchCount" to next.appearancePitches)) setState(key,JsonPrimitive(value))
        setState("isTop",JsonPrimitive(next.isTop))
        for((key,value) in listOf("homeRunsByInning" to next.homeRunsByInning,"awayRunsByInning" to next.awayRunsByInning)) setState(key,GameJson.encodeToJsonElement(value))
        for((key,value) in listOf("batting" to next.batting.mapValues {GameJson.encodeToJsonElement(it.value).jsonObject},
            "pitching" to next.pitching.mapValues {GameJson.encodeToJsonElement(it.value).jsonObject},"fielding" to next.fielding.mapValues {GameJson.encodeToJsonElement(it.value).jsonObject})) {
            val original=state.getValue(key).jsonArray.chunked(2).associate {appleUUID(it[0].jsonPrimitive.content) to it[1].jsonObject}
            setState(key,buildJsonArray {value.toSortedMap().forEach {(player,line)->add(player.uppercase());add(JsonObject(original[player].orEmpty()+line))}})
        }
        setState("baseRunners",buildJsonArray {next.bases.forEachIndexed {base,player->if(player!=null) {add(base+1);add(players.getValue(player))}}})
        setState("automaticRunnerIDs",ids(next.automaticRunnerIDs))
        remove("walkResponsiblePitcherID","strikeoutResponsibleBatterID")
        if(next.rules.mode==GameMode.slowPitch) setState("extraFoulUsed",JsonPrimitive(false)) else remove("extraFoulUsed")
        if(halfChanged) remove("runnerPitcherIDs","unearnedRunnerIDs")
        else {setState("runnerPitcherIDs",ownerPairs(next.runnerPitcherIDs));setState("unearnedRunnerIDs",ids(next.unearnedRunnerIDs.sorted()))}
        val oldAppearance=state.getValue("currentPlateAppearanceID")
        val newAppearance=AppleLifecycleAppend.stable(id,"nextAppearance")
        setState("plateAppearances",JsonArray(state.getValue("plateAppearances").jsonArray.map {
            if(it.jsonObject.getValue("id").jsonPrimitive.content.equals(oldAppearance.jsonPrimitive.content,true))
                JsonObject(it.jsonObject+mapOf("completed" to JsonPrimitive(true),"interrupted" to JsonPrimitive(false))) else it
        }+buildJsonObject {put("id",newAppearance);put("inning",next.inning);put("isTop",next.isTop);put("batterIDs",ids(listOf(next.batter.id)));put("completed",false);put("interrupted",false)}))
        setState("currentPlateAppearanceID",JsonPrimitive(newAppearance))
        // The batted-ball event is logged after resolving runners but before
        // advancing the batter/registering outs. A half-end owns the final snapshot.
        var intermediate=JsonObject(state+mapOf("homeRunsByInning" to updated.getValue("homeRunsByInning"),"awayRunsByInning" to updated.getValue("awayRunsByInning")))
        // Before a half change the reducer has already cleared bases/ownership.
        // Reconstruct these from the actual decisions rather than from that cleared state.
        val owners=game.runnerPitcherIDs.toMutableMap()
        game.bases.filterNotNull().forEach {owners.putIfAbsent(it,game.pitcher.id)}
        owners[game.batter.id]=game.pitcher.id
        if(outcome==Outcome.fieldersChoice) moves.firstOrNull {it.origin>0 && it.destination==-1}?.let {owners[it.playerID]?.let {owner->owners[game.batter.id]=owner}}
        val unearned=game.unearnedRunnerIDs.toMutableSet().apply {addAll(game.automaticRunnerIDs);remove(game.batter.id);if(outcome==Outcome.error) add(game.batter.id)}
        if(outcome==Outcome.fieldersChoice) moves.firstOrNull {it.origin>0 && it.destination==-1 && it.playerID in unearned}?.let {unearned+=game.batter.id}
        val bases=MutableList<String?>(3) {null}
        moves.forEach {move -> val base=if(move.destination==0) move.origin else move.destination;if(base in 1..3) bases[base-1]=move.playerID}
        intermediate=JsonObject(intermediate+mapOf("baseRunners" to buildJsonArray {bases.forEachIndexed {base,player->if(player!=null) {add(base+1);add(players.getValue(player))}}},
            "runnerPitcherIDs" to ownerPairs(owners),"unearnedRunnerIDs" to ids(unearned.sorted())))
        val destinations=mapOf(-1 to "出局",0 to "停留",1 to "到一垒",2 to "到二垒",3 to "到三垒",4 to "得分")
        fun destination(move:Decision)=if(move.destination==4 && move.playerID !in scorers) "回本垒（不计分）" else destinations.getValue(move.destination)
        val summary=moves.filter {it.destination!=0}.joinToString("，") {move->
            val p=players.getValue(move.playerID);p.getValue("chineseName").jsonPrimitive.content.ifBlank {p.getValue("englishName").jsonPrimitive.content}+destination(move)}
        val title="${game.batter.name}：${outcome.title}"+if(summary.isEmpty()) "" else "；$summary"
        val halfTitle="三出局，攻守交换，本半局结束（实际 3 出局）"
        val incomplete=outcome in setOf(Outcome.other,Outcome.pendingOut,Outcome.pending)
        fun log(index:Int,text:String)=buildJsonObject {put("id",AppleLifecycleAppend.stable(id,"log$index"));put("inning",game.inning);put("isTop",game.isTop);put("timestamp",date);put("text",text);put("isIncomplete",if(index==0) incomplete else false)}
        val event=buildJsonObject {
            put("id",AppleLifecycleAppend.stable(id,"event0"));put("logEntryID",AppleLifecycleAppend.stable(id,"log0"));put("inning",game.inning);put("isTop",game.isTop);put("timestamp",date)
            put("category","battedBall");put("title",title);put("notation",outcome.notation);put("primaryPlayerID",game.batter.id.uppercase())
            if(game.rules.mode!=GameMode.coachPitch) put("secondaryPlayerID",game.pitcher.id.uppercase())
            put("plateAppearanceBatterID",game.batter.id.uppercase());put("plateAppearanceID",oldAppearance);put("effectiveScorerIDs",ids(scorers))
            put("runnerMovements",buildJsonArray {moves.forEach {move->add(buildJsonObject {put("playerID",move.playerID.uppercase());put("origin",if(move.origin==0) "打者" else listOf("","一垒跑者","二垒跑者","三垒跑者")[move.origin]);put("destination",destination(move))})}})
            put("needsReview",incomplete);put("resolvedOutcome",outcome.title);put("automaticRunnerRuns",moves.filter {it.destination==4}.take(game.remainingRuns).count {it.playerID in game.automaticRunnerIDs})
            put("beforeSituation",AppleLifecycleAppend.situation(state));put("afterSituation",AppleLifecycleAppend.situation(if(halfChanged) intermediate else updated))
        }
        val events=listOf(event)+if(halfChanged) listOf(buildJsonObject {
            put("id",AppleLifecycleAppend.stable(id,"event1"));put("logEntryID",AppleLifecycleAppend.stable(id,"log1"));put("inning",game.inning);put("isTop",game.isTop);put("timestamp",date)
            put("category","game");put("title",halfTitle);put("notation","HALF-END");put("runnerMovements",JsonArray(emptyList()));put("needsReview",false)
            put("beforeSituation",AppleLifecycleAppend.situation(intermediate));put("afterSituation",AppleLifecycleAppend.situation(updated))
        }) else emptyList()
        val logs=listOf(log(0,title))+if(halfChanged) listOf(log(1,halfTitle)) else emptyList()
        val operation=buildJsonObject {
            put("id",id.uppercase());put("command",AppleHistoryCommandCodec.encodeForJournal(command,game,next,moves));put("context",AppleLifecycleAppend.context(state));put("timestamp",date)
            put("title",if(halfChanged) halfTitle else title);put("eventIDs",JsonArray(events.map {it.getValue("id")}));put("logIDs",JsonArray(logs.map {it.getValue("id")}));put("nextAppearanceID",newAppearance)
            put("inserted",false);put("edited",false);put("acceptsNewContext",false);put("errors",JsonArray(emptyList()))
        }
        setState("playLog",JsonArray(state.getValue("playLog").jsonArray+logs));setState("scoringEvents",JsonArray(state.getValue("scoringEvents").jsonArray+events))
        setState("historyJournal",JsonObject(journal+("operations" to JsonArray(journal.getValue("operations").jsonArray+operation))))
        val record=JsonObject(stored+mapOf("state" to updated,"updatedAt" to date,"revision" to JsonPrimitive(next.revision),"openingPitchRecorded" to JsonPrimitive(true)))
        AppleStoredGameValidation.validate(record)
        val replay=AppleReplayPrototype.replay(record)
        val scoreKeys=setOf("inning","isTop","balls","strikes","outs","bases","homeBatterIndex","awayBatterIndex","homeRunsByInning","awayRunsByInning","homeHits","awayHits","homeErrors","awayErrors","batting","pitching","fielding","runnerPitcherIDs","unearnedRunnerIDs","automaticRunnerIDs","appearancePitches")
        require(GameJson.encodeToJsonElement(replay).jsonObject.filterKeys {it in scoreKeys}==GameJson.encodeToJsonElement(next).jsonObject.filterKeys {it in scoreKeys}) { "击球图与记分重放不一致，未生成不完整来源文件" }
        val payload=JsonObject(inspection.snapshot+("games" to JsonArray(inspection.snapshot.getValue("games").jsonArray.map {if(appleUUID(it.jsonObject.getValue("id").jsonPrimitive.content)==game.id) record else it}))).toString().toByteArray()
        val result=JsonObject(AppleWireJson.parse(bytes).jsonObject+mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)),"checksum" to JsonPrimitive(AppleLifecycleAppend.hash(payload)))).toString().toByteArray()
        AppleSourceBackup.prepare(result)
        return result
    }
}
