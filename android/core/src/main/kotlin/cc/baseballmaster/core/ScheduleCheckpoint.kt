package cc.baseballmaster.core

import kotlinx.serialization.json.*

/** The checkpoint endpoint exchanges StoredGame, separately from the small polling frame. */
object ScheduleCheckpoint {
    private fun originalGame(stored: JsonObject): Game {
        stored["androidScheduleGame"]?.let { return GameJson.decodeFromJsonElement<Game>(it) }
        val state = stored.getValue("state").jsonObject
        if (state["historyJournal"] == null && state["scoringEvents"]?.jsonArray.orEmpty().isEmpty() &&
            state.getValue("playLog").jsonArray.isEmpty()) {
            val opening = AppleStateProjection.read(stored).view()
            require(opening.inning == 1 && opening.isTop && opening.bases.all { it == null } &&
                opening.outs == 0 && opening.balls == opening.rules.initialBalls && opening.strikes == opening.rules.initialStrikes &&
                opening.homeRuns + opening.awayRuns == 0 && opening.batting.values.all { it == BattingLine() } &&
                opening.pitching.values.all { it == PitchingLine() } && opening.fielding.values.all { it == FieldingLine() }) { "进行中的比赛缺少逐球记录" }
            return opening
        }
        return AppleReplayPrototype.replay(stored,schedule=true)
    }

    fun receive(stored: JsonObject, notice: TeamScheduleNotice, field: ScheduleField): Game {
        require(stored.getValue("id").jsonPrimitive.content.equals(notice.id,true) && stored.getValue("status").jsonPrimitive.int == 0)
        AppleStoredGameValidation.validate(stored)
        val state = stored.getValue("state").jsonObject
        for (side in listOf("homeTeam","awayTeam")) require(state.getValue(side).jsonObject["id"] == field.state.getValue(side).jsonObject["id"])
        for (key in listOf("inning","isTop","balls","strikes","outs","homeRunsByInning","awayRunsByInning",
            "homeBatterIndex","awayBatterIndex","homeBattingOrderIDs","awayBattingOrderIDs","activeHomePitcherID","activeAwayPitcherID"))
            require(state[key] == field.state[key]) { "交接档案与场上局面不符（$key）" }
        fun bases(value: JsonObject) = value.getValue("baseRunners").jsonArray.chunked(2).associate { (base,player) -> base to player.jsonObject["id"] }
        require(bases(state) == bases(field.state)) { "交接档案的垒位不符" }
        val game = originalGame(stored)
        game.validate()
        val projection = AppleStateProjection.read(stored).view()
        require(game.id.equals(notice.id,true) && !game.practice && !game.isFinal && !game.isScheduled &&
            game.home.id == projection.home.id && game.away.id == projection.away.id &&
            game.inning == projection.inning && game.isTop == projection.isTop && game.bases == projection.bases &&
            game.balls == projection.balls && game.strikes == projection.strikes && game.outs == projection.outs &&
            game.homeRunsByInning == projection.homeRunsByInning && game.awayRunsByInning == projection.awayRunsByInning &&
            game.batting.filterValues { it != BattingLine() } == projection.batting.filterValues { it != BattingLine() } &&
            game.pitching.filterValues { it != PitchingLine() } == projection.pitching.filterValues { it != PitchingLine() } &&
            game.fielding.filterValues { it != FieldingLine() } == projection.fielding.filterValues { it != FieldingLine() } &&
            (state["runnerPitcherIDs"] == null || game.runnerPitcherIDs == projection.runnerPitcherIDs)) { "逐球历史与交接现场不符" }
        val ops = state["historyJournal"]?.jsonObject?.get("operations")?.jsonArray
        if (ops != null) require(ops.map { it.jsonObject.getValue("id").jsonPrimitive.content.lowercase() } ==
            game.journal.take(game.cursor).map { it.id.lowercase() }) { "交接档案的逐球操作不一致" }
        return game.copy(scheduledAt=projection.scheduledAt ?: notice.startsAt, publishedScheduleTeamID=notice.teamID,
            startedAt=projection.startedAt, playedAt=projection.playedAt, scheduleVenue=notice.venue)
    }

    fun document(game: Game, original: JsonObject?, frame: ScheduleField): JsonObject {
        val active = game.copy(journal=game.journal.take(game.cursor), checkpoint=game.checkpoint ?: MatchEngine.checkpoint(game))
        val encoded = AppleExportPrototype.stored(active, scheduledWriter=true)
        val state = encoded.getValue("state").jsonObject.toMutableMap()
        val old = original?.get("state")?.jsonObject
        if (old != null) {
            val originalRecords = originalGame(original).journal
            preserveHistory(old,state,game,originalRecords)
        }
        for (key in listOf("homeTeam","awayTeam","homeFieldingPlayerIDs","awayFieldingPlayerIDs","baseRunners"))
            frame.state[key]?.let { state[key] = it }
        val localKeys = setOf("sharedTeamID","sharedGameID","sharedPlayerLinks","historySourceID","publicationState",
            "pendingPublicationRevisions","postgameUploadTeamID","postgameTargetConfigured","schedulePublication","lastScheduleProposal","scheduleReceivedEpoch")
        val forRecipient = game.copy(publishedScheduleTeamID=null,postgameSharingChoice=null,historySourceID=null)
        val payload = JsonObject((original.orEmpty() + encoded - localKeys) + mapOf("state" to JsonObject(state),
            "androidScheduleGame" to GameJson.encodeToJsonElement(forRecipient)))
        AppleStoredGameValidation.validate(payload)
        return buildJsonObject { put("formatVersion",2); put("payload",payload); put("projection",LiveProjection.create(game)) }
    }

    private fun preserveHistory(old: JsonObject, state: MutableMap<String,JsonElement>, game: Game, originalRecords: List<RecordedCommand>) {
        val journal = state.getValue("historyJournal").jsonObject
        fun JsonElement.id() = jsonObject.getValue("id").jsonPrimitive.content.uppercase()
        val source = old["historyJournal"]?.jsonObject ?: run {
            // A pre-journal game can have multiple events for one pitch (e.g. the fourth ball and its walk).
            // Reuse their original identities and logs when adding the first editable journal.
            if (originalRecords.isEmpty()) return
            val recorded = originalRecords.map { it.id.uppercase() }.toSet()
            val eventsByOperation = mutableMapOf<String,MutableList<JsonObject>>()
            var owner = originalRecords.first().id.uppercase()
            for (event in old["scoringEvents"]?.jsonArray.orEmpty()) {
                if (event.id() in recorded) owner = event.id()
                eventsByOperation.getOrPut(owner) { mutableListOf() }.add(event.jsonObject)
            }
            val current = journal.getValue("operations").jsonArray.associateBy { it.id() }
            val operations = originalRecords.map { record ->
                val id = record.id.uppercase()
                val item = current[id]?.jsonObject ?: buildJsonObject { put("id",id) }
                val events = eventsByOperation[id].orEmpty()
                JsonObject(item + mapOf("eventIDs" to JsonArray(events.map { it.getValue("id") }),
                    "logIDs" to JsonArray(events.mapNotNull { it["logEntryID"] }.distinct())))
            }
            JsonObject(journal + ("operations" to JsonArray(operations)))
        }
        val prior = source.getValue("operations").jsonArray
        val fresh = journal.getValue("operations").jsonArray
        val keep = (0 until minOf(prior.size,fresh.size,originalRecords.size)).takeWhile { i ->
            prior[i].id() == fresh[i].id() && originalRecords[i] == game.journal[i]
        }.size
        val aliases = mutableMapOf<String,JsonElement>()
        val oldEvents = old["scoringEvents"]?.jsonArray.orEmpty().associateBy { it.id() }
        val newEvents = state.getValue("scoringEvents").jsonArray.associateBy { it.id() }
        for (i in 0 until keep) {
            val a = prior[i].jsonObject
            val b = fresh[i].jsonObject
            val latest = a.getValue("eventIDs").jsonArray.lastOrNull()?.jsonPrimitive?.content?.uppercase()?.let(oldEvents::get)
            val simulated = newEvents[b.id()]
            val oldAppearance = latest?.jsonObject?.get("plateAppearanceID")
            simulated?.jsonObject?.get("plateAppearanceID")?.let { if (oldAppearance != null) aliases[it.jsonPrimitive.content] = oldAppearance }
            b["nextAppearanceID"]?.let { key -> a["nextAppearanceID"]?.let { aliases[key.jsonPrimitive.content] = it } }
        }
        fun mapped(value: JsonElement): JsonElement = when(value) {
            is JsonArray -> JsonArray(value.map(::mapped))
            is JsonObject -> JsonObject(value.mapValues { mapped(it.value) })
            is JsonPrimitive -> aliases[value.content] ?: value
        }
        for ((list,refs) in mapOf("playLog" to "logIDs","scoringEvents" to "eventIDs")) {
            val dropped = prior.drop(keep).flatMap { it.jsonObject.getValue(refs).jsonArray }.map { it.jsonPrimitive.content.uppercase() }.toSet()
            val generated = fresh.take(keep).flatMap { it.jsonObject.getValue(refs).jsonArray }.map { it.jsonPrimitive.content.uppercase() }.toSet()
            state[list] = JsonArray(old[list]?.jsonArray.orEmpty().filter { it.id() !in dropped } +
                state.getValue(list).jsonArray.filter { it.id() !in generated }.map(::mapped))
        }
        for (key in listOf("currentPlateAppearanceID","plateAppearances")) state[key]?.let { state[key] = mapped(it) }
        val appearances = state["plateAppearances"]?.jsonArray.orEmpty()
        val originals = old["plateAppearances"]?.jsonArray.orEmpty()
        val referenced = state.getValue("scoringEvents").jsonArray.mapNotNull { it.jsonObject["plateAppearanceID"] }.toSet()
        if (originals.isNotEmpty()) {
            val projected = appearances.associateBy { it.id() }
            val missing = originals.filter { it.id() !in projected && it.jsonObject["id"] in referenced }
            state["plateAppearances"] = JsonArray(missing + appearances)
        }
        state["historyJournal"] = JsonObject(source + ("operations" to JsonArray(prior.take(keep) + fresh.drop(keep).map(::mapped))))
        val existing = old["correctionRevisions"]?.jsonArray.orEmpty().associateBy { it.id() }
        state["correctionRevisions"]?.let { revisions -> state["correctionRevisions"] = JsonArray(revisions.jsonArray.map { existing[it.id()] ?: it }) }
    }
}

/** Swift saves before/after GameState Data for historical actions without a typed scoring command. */
@kotlinx.serialization.Serializable
data class ScheduleSnapshot(val before: String, val after: String, val fields: JsonObject) {
    internal fun replay(game: Game, command: Command, id: String): Game {
        val expected = GameJson.decodeFromString<Game>(before)
        val target = GameJson.decodeFromString<Game>(after)
        require(game.id == expected.id && game.id == target.id && game.situation() == expected.situation() &&
            game.home == expected.home && game.away == expected.away && game.homeOrder == expected.homeOrder &&
            game.awayOrder == expected.awayOrder && game.homePositions == expected.homePositions && game.awayPositions == expected.awayPositions &&
            game.runnerPitcherIDs == expected.runnerPitcherIDs &&
            game.batting.filterValues { it != BattingLine() } == expected.batting.filterValues { it != BattingLine() } &&
            game.pitching.filterValues { it != PitchingLine() } == expected.pitching.filterValues { it != PitchingLine() } &&
            game.fielding.filterValues { it != FieldingLine() } == expected.fielding.filterValues { it != FieldingLine() }) { "之前的更正已改变这条历史操作的局面" }
        val noted = Session(game,Command("note",text=command.text,at=command.at),id).run()
        val event = noted.entries.last().copy(kind="scheduleSnapshot",after=target.situation())
        return target.copy(entries=noted.entries.dropLast(1)+event, playedAt=game.playedAt,
            scheduledAt=game.scheduledAt, publishedScheduleTeamID=game.publishedScheduleTeamID,
            playedTimeZoneID=game.playedTimeZoneID, scheduleVenue=game.scheduleVenue).also { it.validate() }
    }
}
