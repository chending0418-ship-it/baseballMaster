package cc.baseballmaster.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The service exchanges a field and roster view. This type never becomes a stored game. */
@Serializable
data class ScheduleField(val isHome: Boolean, val rules: JsonObject, val pregame: Boolean,
    val state: JsonObject, val lastPlay: String? = null) {
    private fun ids(key: String) = state.getValue(key).jsonArray.map { appleUUID(it.jsonPrimitive.content) }
    private fun id(key: String) = state[key]?.jsonPrimitive?.contentOrNull?.let(::appleUUID)
    private fun number(key: String) = state.getValue(key).jsonPrimitive.int

    fun editingView(gameID: String): Game {
        val config = AppleStateProjection.rules(rules)
        val homeWire = state.getValue("homeTeam").jsonObject
        val awayWire = state.getValue("awayTeam").jsonObject
        val ourID = appleUUID((if (isHome) homeWire else awayWire).getValue("id").jsonPrimitive.content)
        val home = AppleStateProjection.team(homeWire, ourID)
        val away = AppleStateProjection.team(awayWire, ourID)
        fun defense(side: String, team: Team): Map<Int, String> {
            val fielders = ids(if (state["${side}FieldingPlayerIDs"] is JsonArray) "${side}FieldingPlayerIDs" else "${side}BattingOrderIDs")
            return fielders.associateBy { id -> team.players.single { it.id == id }.position }
        }
        val bases = MutableList<String?>(3) { null }
        for ((base, player) in state.getValue("baseRunners").jsonArray.chunked(2)) {
            bases[base.jsonPrimitive.int - 1] = appleUUID(player.jsonObject.getValue("id").jsonPrimitive.content)
        }
        fun runs(side: String) = state.getValue("${side}RunsByInning").jsonArray.map { it.jsonPrimitive.int }
        return Game(gameID, home, away, config, inning = number("inning"), isTop = state.getValue("isTop").jsonPrimitive.boolean,
            balls = number("balls"), strikes = number("strikes"), outs = number("outs"),
            homeBatterIndex = number("homeBatterIndex"), awayBatterIndex = number("awayBatterIndex"), bases = bases,
            homeRuns = runs("home").sum(), awayRuns = runs("away").sum(), homeRunsByInning = runs("home"), awayRunsByInning = runs("away"),
            homeOrder = ids("homeBattingOrderIDs"), awayOrder = ids("awayBattingOrderIDs"),
            homePositions = defense("home",home), awayPositions = defense("away",away),
            homePitcherID = id("activeHomePitcherID"), awayPitcherID = id("activeAwayPitcherID"),
            homeDesignatedHitterID = id("homeDesignatedHitterID"), awayDesignatedHitterID = id("awayDesignatedHitterID"),
            automaticRunnerIDs = state["automaticRunnerIDs"]?.takeIf { it is JsonArray }?.let { ids("automaticRunnerIDs").toSet() }.orEmpty(),
            ourTeamID = ourID, clockHasStarted = !pregame).also { it.validate() }
    }

    fun proposal(gameID: String, command: Command, version: Long): JsonObject {
        require(command.kind == "lineup")
        val before = editingView(gameID)
        val after = MatchEngine.apply(before, command)
        val home = requireNotNull(command.homeSide)
        val side = if (home) "home" else "away"
        val players = state.getValue("${side}Team").jsonObject.getValue("players").jsonArray
        val fielders = state["${side}FieldingPlayerIDs"] as? JsonArray ?: state.getValue("${side}BattingOrderIDs")
        val baseline = buildJsonObject {
            put("isHome", home); put("players", players); put("battingIDs", state.getValue("${side}BattingOrderIDs")); put("fieldingIDs", fielders)
            state["${side}DesignatedHitterID"]?.let { put("dhID", it) }
            state["active${if (home) "Home" else "Away"}PitcherID"]?.let { put("pitcherID", it) }
            val order = state.getValue("${side}BattingOrderIDs").jsonArray
            put("anchorID", order[number("${side}BatterIndex") % order.size]); put("runners", state.getValue("baseRunners"))
            state["automaticRunnerIDs"]?.let { put("automaticIDs", it) }; put("changes", JsonArray(emptyList()))
        }
        val team = if (home) after.home else after.away
        val positions = requireNotNull(if (home) after.homePositions else after.awayPositions)
        val oldPositions = if (home) before.homePositions else before.awayPositions
        val order = requireNotNull(if (home) after.homeOrder else after.awayOrder)
        val original = players.associate { it.jsonObject.getValue("id").jsonPrimitive.content.lowercase() to it.jsonObject }
        val updates = command.playerUpdates.orEmpty().associateBy { it.id }
        val nextPlayers = team.players.associate { p ->
            val pos = positions.entries.firstOrNull { it.value == p.id }?.key ?: p.position.takeIf { it in 1..10 } ?: 1
            val old = original[p.id]
            val wire = if (old == null || updates.containsKey(p.id)) AppleExportPrototype.player(p.copy(position = pos))
                else JsonObject(old + ("primaryPosition" to JsonPrimitive(pos)))
            p.id to wire
        }
        val changes = buildList {
            command.benchAdditions.forEach { add("补入替补 #${it.number} ${it.name}") }
            command.playerUpdates.orEmpty().forEach { add("更正 #${it.number} ${it.name}") }
            command.lineupReplacements.orEmpty().forEach { (outgoing,incoming) ->
                add("${team.players.single { it.id == incoming }.name} 替换 ${team.players.single { it.id == outgoing }.name}")
            }
            if (positions != oldPositions) add("调整守备阵容")
            if (command.order != (if (home) before.homeOrder else before.awayOrder)) add("调整打序")
            if (command.designatedHitterID != (if (home) before.homeDesignatedHitterID else before.awayDesignatedHitterID)) add("调整指定打击")
        }
        require(changes.isNotEmpty()) { "请先调整阵容" }
        val lineup = buildJsonObject {
            put("isHome", home); put("players", JsonArray(nextPlayers.values.toList())); put("battingIDs", wireIDs(order))
            put("fieldingIDs", if (positions == oldPositions) fielders else wireIDs(positions.toSortedMap().values))
            command.designatedHitterID?.let { put("dhID", it.uppercase()) }
            put("pitcherID", positions.getValue(1).uppercase())
            put("anchorID", order[(if (home) after.homeBatterIndex else after.awayBatterIndex) % order.size].uppercase())
            put("runners", buildJsonArray {
                for ((base, prev) in state.getValue("baseRunners").jsonArray.chunked(2)) {
                    val runner = requireNotNull(after.bases[base.jsonPrimitive.int - 1])
                    add(base); add(nextPlayers[runner] ?: prev)
                }
            })
            if (after.automaticRunnerIDs != before.automaticRunnerIDs) put("automaticIDs", wireIDs(after.automaticRunnerIDs))
            else baseline["automaticIDs"]?.let { put("automaticIDs",it) }
            put("changes", JsonArray(changes.map(::JsonPrimitive)))
        }
        return buildJsonObject {
            put("id", UUID.randomUUID().toString().uppercase()); put("version", version); lastPlay?.let { put("lastPlay", it) }
            put("baseline",baseline); put("lineup",lineup)
        }
    }

    companion object {
        private fun wireIDs(values: Collection<String>) = JsonArray(values.map { JsonPrimitive(appleUUID(it).uppercase()) })

        fun opening(source: Game): ScheduleField {
            require(source.isScheduled && !source.isFinal && !source.practice) { "只能提供赛前阵容" }
            val g = if (source.scheduledSetupPending)
                MatchFactory.create(source.id, source.home, source.away, source.rules, source.ourTeamID) else source
            val rules = AppleExportPrototype.rules(g.rules)
            val state = buildJsonObject {
                put("rulesSnapshot",rules); put("scheduledInnings",g.rules.innings); put("extraFoulUsed",false)
                put("inning",1); put("isTop",true); put("balls",g.rules.initialBalls); put("strikes",g.rules.initialStrikes); put("outs",0)
                put("baseRunners", JsonArray(emptyList())); put("isFinal",false)
                for (key in listOf("batting","pitching","fielding","playLog")) put(key, JsonArray(emptyList()))
                for (home in listOf(false,true)) {
                    val side = if (home) "home" else "away"
                    val team = if (home) g.home else g.away
                    val positions = requireNotNull(if (home) g.homePositions else g.awayPositions)
                    put("${side}Team",buildJsonObject {
                        put("id",appleUUID(team.id).uppercase()); put("name",team.name); put("shortName",team.shortName); put("city",team.city)
                        put("players",JsonArray(team.players.map { p -> AppleExportPrototype.player(p.copy(position =
                            positions.entries.firstOrNull { it.value == p.id }?.key ?: p.position.takeIf { it in 1..10 } ?: 1)) }))
                    })
                    put("${side}BattingOrderIDs", wireIDs(requireNotNull(if (home) g.homeOrder else g.awayOrder)))
                    put("${side}FieldingPlayerIDs", wireIDs(positions.toSortedMap().values))
                    put("active${if (home) "Home" else "Away"}PitcherID", positions.getValue(1).uppercase())
                    (if (home) g.homeDesignatedHitterID else g.awayDesignatedHitterID)?.let { put("${side}DesignatedHitterID", it.uppercase()) }
                    put("${side}RunsByInning",JsonArray(List(g.rules.innings) { JsonPrimitive(0) }))
                    put("${side}BatterIndex",0); put("${side}Hits",0); put("${side}Errors",0)
                }
            }
            return ScheduleField(g.home.id == g.ourTeamID, rules, true, state)
        }
    }
}
