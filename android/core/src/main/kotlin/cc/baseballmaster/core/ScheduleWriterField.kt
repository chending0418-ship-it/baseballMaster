package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.json.*

private fun JsonObject.uuid(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(::appleUUID)
private fun JsonObject.ids(key: String) = getValue(key).jsonArray.map { appleUUID(it.jsonPrimitive.content) }
private fun JsonObject.runners() = getValue("runners").jsonArray.chunked(2).associate { (base, player) ->
    base.jsonPrimitive.int to player.jsonObject.uuid("id")
}

internal fun lineupCommand(value: JsonObject, old: Team, oldOrder: List<String>, at: Long = 0): Command {
    val players = value.getValue("players").jsonArray.map { AppleStateProjection.player(it.jsonObject) }
    val byID = players.associateBy(Player::id)
    val prior = old.players.associateBy(Player::id)
    require(byID.size == players.size && byID.keys.containsAll(prior.keys))
    val positions = value.ids("fieldingIDs").associateBy { byID.getValue(it).position }
    val order = value.ids("battingIDs")
    val replacements = oldOrder.filter { it !in order }.associateWith { order[oldOrder.indexOf(it)] }
    return Command("lineup", homeSide = value.getValue("isHome").jsonPrimitive.boolean,
        order = order, positions = positions, playerID = value.uuid("anchorID"), designatedHitterID = value.uuid("dhID"),
        lineupReplacements = replacements, benchAdditions = players.filter { it.id !in prior },
        playerUpdates = players.mapNotNull { p -> prior[p.id]?.let { oldPlayer ->
            oldPlayer.copy(name=p.name, englishName=p.englishName, chineseName=p.chineseName,
                number=p.number, jerseyNumbers=p.jerseyNumbers).takeUnless { it == oldPlayer }
        } }, matchOnlyProfiles = true, at = at)
}

/** Verify against the local unsent plays as well as the server's last published field. */
fun ScheduleField.applyProposal(game: Game, proposal: JsonObject): Game {
    val before = proposal.getValue("baseline").jsonObject
    val next = proposal.getValue("lineup").jsonObject
    val home = next.getValue("isHome").jsonPrimitive.boolean
    val side = if (home) "home" else "away"
    val team = if (home) game.home else game.away
    val order = requireNotNull(if (home) game.homeOrder else game.awayOrder)
    require(before["isHome"] == next["isHome"] && before["players"] == state.getValue("${side}Team").jsonObject["players"]) { "球员资料已变化" }
    val participation = listOf("battingIDs","fieldingIDs","anchorID","pitcherID","dhID","automaticIDs").any { before[it] != next[it] } ||
        before.runners() != next.runners()
    if (participation) {
        val positions = requireNotNull(if (home) game.homePositions else game.awayPositions)
        require(before.ids("battingIDs") == order && before.ids("fieldingIDs").toSet() == positions.values.toSet() &&
            before.uuid("pitcherID") == positions[1] && before.uuid("dhID") == (if (home) game.homeDesignatedHitterID else game.awayDesignatedHitterID) &&
            before.uuid("anchorID") == order[(if (home) game.homeBatterIndex else game.awayBatterIndex) % order.size] &&
            (1..3).all { before.runners()[it] == game.bases[it-1] } &&
            before["automaticIDs"]?.jsonArray.orEmpty().map { appleUUID(it.jsonPrimitive.content) }.toSet() == game.automaticRunnerIDs) { "场上人员或垒位已变化" }
    }
    val command = lineupCommand(next,team,order).let { if (participation) it else it.copy(playerID=null,scheduleRosterOnly=true) }
    val applied = MatchEngine.apply(game, command, requireNotNull(proposal.uuid("id")))
    if (participation) require((1..3).all { next.runners()[it] == applied.bases[it-1] }) { "代跑与垒位不符" }
    return applied
}

fun scheduleLogID(gameID: String, operationID: String): String =
    UUID.nameUUIDFromBytes("${gameID}/log/${operationID}".toByteArray()).toString().uppercase()

fun ScheduleField.withGame(game: Game, lineup: JsonObject? = null, lastLog: String? = null): ScheduleField {
    val state = state.toMutableMap()
    val native = AppleExportPrototype.state(game)
    for (key in listOf("inning","isTop","balls","strikes","outs","isFinal","automaticRunnerIDs","homeBatterIndex",
        "awayBatterIndex","homeRunsByInning","awayRunsByInning","homeHits","awayHits","homeErrors","awayErrors"))
        state[key] = native.getValue(key)
    for (home in listOf(false,true)) {
        val side = if (home) "home" else "away"
        val teamKey = "${side}Team"
        val team = state.getValue(teamKey).jsonObject
        val proposed = lineup?.takeIf { it.getValue("isHome").jsonPrimitive.boolean == home }
        val keys = mapOf("${side}BattingOrderIDs" to "battingIDs", "${side}FieldingPlayerIDs" to "fieldingIDs",
            "${side}DesignatedHitterID" to "dhID", "active${if (home) "Home" else "Away"}PitcherID" to "pitcherID")
        if (proposed != null) {
            state[teamKey] = JsonObject(team + ("players" to proposed.getValue("players")))
            for ((key,wire) in keys) proposed[wire]?.let { state[key] = it } ?: state.remove(key)
        } else {
            val nativeTeam = native.getValue(teamKey).jsonObject
            val oldPlayers = team.getValue("players").jsonArray
            val newPlayers = nativeTeam.getValue("players").jsonArray
            if (oldPlayers.map { AppleStateProjection.player(it.jsonObject) } != newPlayers.map { AppleStateProjection.player(it.jsonObject) })
                state[teamKey] = JsonObject(team + ("players" to newPlayers))
            for ((key,wire) in keys) {
                val old = state[key]
                val fresh = native[key]
                if (wire == "fieldingIDs" && (old ?: state["${side}BattingOrderIDs"])?.jsonArray?.toSet() == fresh?.jsonArray?.toSet()) continue
                if (old != fresh) fresh?.let { state[key] = it } ?: state.remove(key)
            }
        }
    }
    val people = listOf("homeTeam","awayTeam").flatMap { state.getValue(it).jsonObject.getValue("players").jsonArray }
        .associateBy { it.jsonObject.uuid("id") }
    state["baseRunners"] = buildJsonArray {
        game.bases.forEachIndexed { index,id -> if (id != null) { add(index + 1); add(people.getValue(id)) } }
    }
    val wireRules = if (game.rules == AppleStateProjection.rules(rules)) rules else AppleExportPrototype.rules(game.rules)
    state["rulesSnapshot"] = wireRules
    state["scheduledInnings"] = JsonPrimitive(game.rules.innings)
    return copy(rules=wireRules, isHome=game.home.id == game.ourTeamID, state=JsonObject(state), pregame=game.isPregame, lastPlay=lastLog ?: game.entries.lastOrNull()?.let { scheduleLogID(game.id,it.id) })
}

/** A scorer retracts a play while the lineup recorder's later profile edits remain effective. */
fun undoSchedulePlay(game: Game): Game {
    val active = game.journal.take(game.cursor)
    val trailing = active.takeLastWhile { it.command.scheduleRosterOnly }
    if (trailing.isEmpty()) return MatchEngine.undo(game)
    val prefix = active.dropLast(trailing.size)
    require(prefix.isNotEmpty() && prefix.size > game.correctionBoundary) { "没有可以撤销的局面记录" }
    val scoring = game.copy(journal=prefix,cursor=prefix.size)
    var rewound = MatchEngine.undo(scoring)
    val retained = rewound.cursor
    for (record in trailing) {
        val old = record.command
        val home = requireNotNull(old.homeSide)
        val adjusted = old.copy(order=if (home) rewound.homeOrder else rewound.awayOrder,
            positions=if (home) rewound.homePositions else rewound.awayPositions, playerID=null,
            designatedHitterID=if (home) rewound.homeDesignatedHitterID else rewound.awayDesignatedHitterID)
        rewound = MatchEngine.apply(rewound,adjusted,record.id)
    }
    return rewound.copy(journal=rewound.journal + prefix.drop(retained) + game.journal.drop(game.cursor),
        revision=game.revision+1,updatedAt=System.currentTimeMillis()).also { it.validate() }
}
