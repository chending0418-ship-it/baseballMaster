package cc.baseballmaster.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/** Append lifecycle commands to an independently replayed source without rebuilding its history.
 * Unknown commands and lifecycle transitions that would also change the playing situation fail closed.
 */
internal object AppleLifecycleAppend {
    private val reasons = mapOf("regulation" to "规定局数完成", "walkOff" to "再见分结束",
        "timeLimit" to "时间限制结束", "mercyRule" to "提前结束规则", "forfeit" to "弃权结束",
        "weather" to "天气或场地原因", "scorerDecision" to "记录员结束记录")
    internal fun date(ms: Long) = JsonPrimitive(BigDecimal.valueOf(ms).movePointLeft(3).subtract(BigDecimal("978307200")))
    internal fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    internal fun stable(id: String, component: String): String {
        val hex = hash((id.uppercase() + component).toByteArray()).take(32)
        return "${hex.take(8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}".uppercase()
    }
    private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it == JsonNull }
    internal fun situation(state: JsonObject) = buildJsonObject {
        for (key in listOf("inning", "isTop", "balls", "strikes", "outs", "homeRunsByInning", "awayRunsByInning",
            "homeBatterIndex", "awayBatterIndex", "activeHomePitcherID", "activeAwayPitcherID",
            "runnerPitcherIDs", "unearnedRunnerIDs", "extraFoulUsed")) state.optional(key)?.let { put(key,it) }
        put("baseRunners", buildJsonArray { state.getValue("baseRunners").jsonArray.chunked(2)
            .sortedBy { it[0].jsonPrimitive.int }.forEach { (base,player) ->
                add(buildJsonObject { put("base",base);put("playerID",player.jsonObject.getValue("id")) })
            } })
        if (state.optional("recordsLineupSnapshots")?.jsonPrimitive?.boolean == true)
            for (key in listOf("homeBattingOrderIDs","awayBattingOrderIDs")) put(key,state.getValue(key))
    }
    internal fun context(state: JsonObject) = buildJsonObject {
        put("inning",state.getValue("inning")); put("isTop",state.getValue("isTop"))
        put("batterIndex",state.getValue(if(state.getValue("isTop").jsonPrimitive.boolean) "awayBatterIndex" else "homeBatterIndex"))
        for(side in listOf("home","away")) {
            put("${side}Order",state.getValue("${side}BattingOrderIDs"))
            put("${side}Fielders",state.getValue("${side}FieldingPlayerIDs"))
        }
        put("positions",buildJsonArray { for(side in listOf("homeTeam","awayTeam"))
            state.getValue(side).jsonObject.getValue("players").jsonArray.forEach {
                add(it.jsonObject.getValue("id"));add(it.jsonObject.getValue("primaryPosition"))
            } })
        put("runners",buildJsonArray { state.getValue("baseRunners").jsonArray.chunked(2).forEach { (base,player) ->
            add(base);add(player.jsonObject.getValue("id"))
        } })
    }
    fun append(bytes: ByteArray, gameID: String, command: Command, operationID: String): ByteArray {
        val id = appleUUID(operationID)
        require(command.at > 0 && command == when(command.kind) {
            "finish" -> Command("finish",text=command.text,at=command.at)
            "suspend" -> { require(command.text in setOf("", "suspended")); Command("suspend",text=command.text,at=command.at) }
            "note" -> Command("note",text=command.text,at=command.at)
            "reopen" -> Command("reopen",flag=command.flag,at=command.at)
            "clock" -> Command("clock",text=command.text,at=command.at)
            else -> throw IllegalArgumentException("此来源操作尚未支持无损写回")
        }) { "来源操作含尚未支持的附加资料" }
        val source = AppleSourceBackup.prepare(bytes)
        val game = source.games().single { it.id == appleUUID(gameID) }
        require(!game.incompletePrefix && game.historySourceID == null && game.journal.isNotEmpty()) { "来源历史不完整，原文件未修改" }
        val inspection = AppleBackupEnvelope.inspect(bytes)
        val stored = inspection.snapshot.getValue("games").jsonArray.map {it.jsonObject}
            .single {appleUUID(it.getValue("id").jsonPrimitive.content)==game.id}
        val state = stored.getValue("state").jsonObject
        val journal = state.getValue("historyJournal").jsonObject
        require(journal.getValue("operations").jsonArray.size < 10_000) { "来源历史过长" }
        val occupied = mutableSetOf<String>()
        val dates = mutableListOf<BigDecimal>()
        fun scan(value: JsonElement) {
            when(value) {
                is JsonObject -> {
                    for((key,item) in value) {
                        if(key in setOf("timestamp","correctedAt","reviewedAt","updatedAt","endedAt","clockRunningSince","date") && item!=JsonNull)
                            dates += BigDecimal(item.jsonPrimitive.content)
                        scan(item)
                    }
                    if(value["operations"] is JsonArray && value.optional("checkpoint")!=null)
                        scan(AppleWireJson.parse(Base64.getDecoder().decode(value.getValue("checkpoint").jsonPrimitive.content)))
                }
                is JsonArray -> value.forEach(::scan)
                is JsonPrimitive -> if(value.isString && Regex("[a-fA-F0-9-]{36}").matches(value.content)) occupied += value.content.lowercase()
            }
        }
        scan(stored)
        // Other matches only reserve identities; their later dates do not
        // prevent appending an earlier action to the selected match.
        val selectedDates = dates.toList()
        scan(inspection.snapshot)
        dates.clear(); dates.addAll(selectedDates)
        require(dates.all {date(command.at).content.toBigDecimal()>=it}) { "操作时间早于来源记录，原文件未修改" }
        val eventID=stable(id,"event0");val logID=stable(id,"log0")
        require(listOf(id,eventID.lowercase(),logID.lowercase()).distinct().size==3 &&
            listOf(id,eventID.lowercase(),logID.lowercase()).none {it in occupied}) { "来源操作编号重复，原文件未修改" }
        require(command.kind!="reopen" || state.optional("canResumePrecisely")?.jsonPrimitive?.boolean==true) { "旧比赛恢复阶段需独立核对" }
        require(!game.halfEndedAwaitingDecision || command.kind!="reopen") { "此恢复会换边，须使用完整局面写入" }
        if(command.kind=="clock") {
            require(command.text in setOf("start","resume","pause")) { "时钟显示切换尚未支持来源写入" }
            require(command.text!="pause" || game.clockRunningSince!=null) { "比赛计时已经暂停" }
            require(game.clockRunningSince!=null || (state.optional("clockElapsedSeconds")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO)>BigDecimal.ZERO) { "首次开赛须使用完整开赛写入" }
        }
        val next=MatchEngine.apply(game,command,id)
        var updated=state
        fun setState(key:String,value:JsonElement) {updated=JsonObject(updated+(key to value))}
        fun remove(vararg keys:String) {updated=JsonObject(updated-keys.toSet())}
        val (title,notation,category)=when(command.kind) {
            "finish" -> {
                val reason=reasons[command.text] ?: throw IllegalArgumentException("结束原因尚未支持")
                setState("isFinal",JsonPrimitive(true));setState("endedAt",date(command.at));setState("endReason",JsonPrimitive(reason))
                setState("canResumePrecisely",JsonPrimitive(true));remove("pendingDecision")
                Triple("比赛结束：$reason","END","game")
            }
            "suspend" -> Triple("比赛中断：已保存当前局面，可稍后继续","SUSP","game")
            "reopen" -> {
                setState("isFinal",JsonPrimitive(false));remove("endedAt","endReason","pendingDecision")
                setState("dismissedWalkOffHalf",JsonPrimitive(game.halfKey))
                Triple("比赛恢复为进行中，计时暂停","REOPEN","game")
            }
            "note" -> Triple(command.text,null,"substitution")
            else -> if(command.text=="pause") Triple("比赛计时暂停","PAUSE","clock")
                else Triple("比赛计时继续","RESUME","clock")
        }
        if(command.kind!="note") {
            // Source Date/TimeInterval values can carry fractions of a millisecond.
            // Freeze using their decimal wire values, not the millisecond UI projection.
            val freezes=command.kind in setOf("finish","suspend") || command.kind=="clock" && command.text=="pause"
            if(freezes) state.optional("clockRunningSince")?.let {running ->
                val accumulated=(state.optional("clockElapsedSeconds")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
                val elapsed=(date(command.at).content.toBigDecimal()-running.jsonPrimitive.content.toBigDecimal()).max(BigDecimal.ZERO)
                setState("clockElapsedSeconds",JsonPrimitive(accumulated+elapsed))
            }
            if(next.clockRunningSince==null) remove("clockRunningSince") else setState("clockRunningSince",date(next.clockRunningSince!!))
        }
        val log=buildJsonObject {
            put("id",logID);put("inning",game.inning);put("isTop",game.isTop);put("timestamp",date(command.at));put("text",title);put("isIncomplete",false)
        }
        val event=buildJsonObject {
            put("id",eventID);put("logEntryID",logID);put("inning",game.inning);put("isTop",game.isTop);put("timestamp",date(command.at))
            put("category",category);put("title",title);notation?.let {put("notation",it)};put("runnerMovements",JsonArray(emptyList()));put("needsReview",false)
            put("beforeSituation",situation(state));put("afterSituation",situation(updated))
        }
        val operation=buildJsonObject {
            put("id",id.uppercase());put("command",AppleHistoryCommandCodec.encodeForJournal(command,game,next,emptyList()))
            put("context",context(state));put("timestamp",date(command.at));put("title",title)
            put("eventIDs",JsonArray(listOf(JsonPrimitive(eventID))));put("logIDs",JsonArray(listOf(JsonPrimitive(logID))))
            state.optional("currentPlateAppearanceID")?.let {put("nextAppearanceID",it)}
            put("inserted",false);put("edited",false);put("acceptsNewContext",false);put("errors",JsonArray(emptyList()))
        }
        setState("playLog",JsonArray(state.getValue("playLog").jsonArray+log))
        setState("scoringEvents",JsonArray(state.getValue("scoringEvents").jsonArray+event))
        setState("historyJournal",JsonObject(journal+("operations" to JsonArray(journal.getValue("operations").jsonArray+operation))))
        val updatedStored=JsonObject(stored+mapOf("state" to updated,"revision" to JsonPrimitive(next.revision),"updatedAt" to date(command.at),"status" to JsonPrimitive(if(next.isFinal) 1 else 0)))
        AppleStoredGameValidation.validate(updatedStored)
        val replay=AppleReplayPrototype.replay(updatedStored)
        fun score(value: Game) = GameJson.encodeToJsonElement(value).jsonObject.filterKeys { it in setOf(
            "inning","isTop","balls","strikes","outs","homeRunsByInning","awayRunsByInning","homeHits","awayHits",
            "homeErrors","awayErrors","homeBatterIndex","awayBatterIndex","bases","homeOrder","awayOrder",
            "homePositions","awayPositions","batting","pitching","fielding","runnerPitcherIDs","unearnedRunnerIDs","appearancePitches") }
        val expectedElapsed=(updated.optional("clockElapsedSeconds")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO)
            .movePointRight(3).setScale(0,java.math.RoundingMode.FLOOR).longValueExact()
        val sourceClock=AppleStateProjection.read(updatedStored,updated).view()
        require(score(replay)==score(next) && replay.isFinal==next.isFinal &&
            replay.endedAt==next.endedAt && sourceClock.clockElapsedMs==expectedElapsed && sourceClock.clockRunningSince==next.clockRunningSince &&
            replay.journal.map {it.id}==next.journal.map {it.id}) { "来源生命周期写入重放不一致，原文件未修改" }
        val payload=JsonObject(inspection.snapshot+("games" to JsonArray(inspection.snapshot.getValue("games").jsonArray.map {
            if(appleUUID(it.jsonObject.getValue("id").jsonPrimitive.content)==game.id) updatedStored else it
        }))).toString().toByteArray()
        val result=JsonObject(AppleWireJson.parse(bytes).jsonObject+mapOf("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload)),"checksum" to JsonPrimitive(hash(payload)))).toString().toByteArray()
        AppleSourceBackup.prepare(result)
        return result
    }
}
