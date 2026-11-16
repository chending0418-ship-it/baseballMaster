package cc.baseballmaster.core

import kotlinx.serialization.json.*

fun sharedStatisticsID(teamID: String): String = "shared:${appleUUID(teamID)}"

private fun JsonObject.value(key: String) = get(key)?.takeUnless { it == JsonNull }
private fun JsonObject.text(key: String) = value(key)?.jsonPrimitive?.content
private fun JsonObject.flag(key: String) = value(key)?.jsonPrimitive?.booleanOrNull
private fun JsonObject.array(key: String) = value(key)?.jsonArray.orEmpty()
private fun JsonObject.id(key: String) = text(key)?.let(::appleUUID)

fun RemoteTeam.statisticsRoster(profile: SharedTeamProfile? = null): Team {
    val active = profile?.players.orEmpty().filter { it.archived != true }.map { player ->
        val number = player.numbers.firstOrNull() ?: "—"
        Player(appleUUID(player.sourceID), player.chineseName.ifBlank { player.englishName.ifBlank { "未填写姓名" } },
            number, englishName = player.englishName, chineseName = player.chineseName, jerseyNumbers = player.numbers)
    }
    return Team(sharedStatisticsID(id), profile?.name ?: name, active, city = profile?.city.orEmpty(),
        shortName = profile?.shortName ?: name, source = "shared")
}

val SharedMatchDocument.mayCountInStatistics: Boolean
    get() = !deleted && (raw.text("status") == "completed" || finalForWatching)

/**
 * Project saved totals and observations for statistics. Historical records do
 * not need a replayable journal or a lineup Android could open for editing.
 */
internal fun SharedMatchDocument.statisticsGame(): Game? {
    if (deleted) return null
    val stored = requireNotNull(payload)
    if (stored.value("status")?.jsonPrimitive?.int != 1 || stored.flag("isSpectator") == true) return null
    val state = stored.getValue("state").jsonObject
    require(state.flag("isFinal") == true)
    val homeWire = state.getValue("homeTeam").jsonObject
    val awayWire = state.getValue("awayTeam").jsonObject
    val homeID = homeWire.id("id")
    val awayID = awayWire.id("id")
    val ourID = stored.id("ourTeamID")
    val isHome = when {
        ourID != null -> when (ourID) {
            homeID -> true
            awayID -> false
            else -> error("球队与比赛不一致")
        }
        else -> requireNotNull(stored.flag("isHome"))
    }
    val links = raw.array("playerLinks").map { it.jsonObject }.associate {
        requireNotNull(it.id("sourceID")) to requireNotNull(it.id("targetID"))
    }
    val source = AppleStateProjection.team(if (isHome) homeWire else awayWire, if (isHome) homeID else awayID)
    val opponent = AppleStateProjection.team(if (isHome) awayWire else homeWire, null)
    val sourceIDs = source.players.map { it.id }.toSet()
    fun identity(playerID: String) = links[playerID] ?: playerID
    val team = source.copy(id = sharedStatisticsID(teamID), players = source.players.map { it.copy(id = identity(it.id)) })
    require(team.players.map { it.id }.distinct().size == team.players.size)

    fun ids(container: JsonObject, key: String) = container.array(key).map { appleUUID(it.jsonPrimitive.content) }
    val side = if (isHome) "home" else "away"
    val lineup = stored.array("lineup").mapNotNull { it.jsonObject.id("playerID") }
    val participants = (lineup + ids(state, "${side}BattingOrderIDs") + ids(state, "${side}FieldingPlayerIDs") +
        ids(state, "${side}ExitedPlayerIDs")).filter { it in sourceIDs }.map(::identity).distinct()

    val batting = values<BattingLine>(state, "batting", sourceIDs, ::identity)
    val pitching = values<PitchingLine>(state, "pitching", sourceIDs, ::identity)
    val fielding = values<FieldingLine>(state, "fielding", sourceIDs, ::identity)
    val rules = state.value("rulesSnapshot")?.jsonObject ?: stored.getValue("rules").jsonObject
    val mode = GameMode.valueOf(rules.text("mode") ?: "standard")
    fun runs(key: String) = state.array(key).fold(0) { sum, value ->
        Math.addExact(sum, value.jsonPrimitive.int.also { require(it >= 0) })
    }
    val homeRuns = runs("homeRunsByInning")
    val awayRuns = runs("awayRunsByInning")
    val entries = state.array("scoringEvents").mapNotNull { element ->
        val event = element.jsonObject
        val isContact = event.text("category") == "battedBall"
        val needsReview = event.flag("needsReview") == true
        if (!isContact && !needsReview) return@mapNotNull null
        val batter = (event.id("plateAppearanceBatterID") ?: event.id("primaryPlayerID"))?.takeIf { it in sourceIDs }
        val observation = event.value("battedBall")?.jsonObject?.let { ball ->
            Observation(type = ball.text("type") ?: "unknown", precision = ball.text("precision") ?: "unknown",
                region = ball.text("region"), x = ball.value("x")?.jsonPrimitive?.doubleOrNull,
                y = ball.value("y")?.jsonPrimitive?.doubleOrNull)
        }
        PitchEntry(requireNotNull(event.id("id")), text = event.text("title").orEmpty(), kind = if (isContact) "play" else "review",
            notation = event.text("notation").orEmpty(), batterID = batter?.let(::identity), observation = observation,
            outcome = Outcome.entries.firstOrNull { it.title == event.text("resolvedOutcome") },
            inning = event.value("inning")?.jsonPrimitive?.int ?: 1, isTop = event.flag("isTop") ?: true,
            incomplete = needsReview)
    }
    return Game(
        id = id, home = if (isHome) team else opponent, away = if (isHome) opponent else team, rules = Rules(mode),
        homeRuns = homeRuns, awayRuns = awayRuns, revision = revision, entries = entries,
        homeHits = state.value("homeHits")?.jsonPrimitive?.int ?: 0,
        awayHits = state.value("awayHits")?.jsonPrimitive?.int ?: 0,
        homeErrors = state.value("homeErrors")?.jsonPrimitive?.int ?: 0,
        awayErrors = state.value("awayErrors")?.jsonPrimitive?.int ?: 0,
        homeOrder = if (isHome) participants else emptyList(), awayOrder = if (isHome) emptyList() else participants,
        homePositions = emptyMap(), awayPositions = emptyMap(), batting = batting, pitching = pitching, fielding = fielding,
        isFinal = true, ourTeamID = team.id, statisticsIncomplete = state.flag("statisticsIncomplete") == true ||
            state.flag("pitchingOutsReviewRequired") == true,
        scheduledAt = AppleStateProjection.date(stored.value("scheduledAt")),
        startedAt = AppleStateProjection.date(stored.value("startedAt")),
        playedAt = AppleStateProjection.date(stored.value("playedAt")), playedTimeZoneID = stored.text("playedTimeZoneID") ?: "UTC",
        createdAt = requireNotNull(AppleStateProjection.date(stored.value("createdAt"))),
        updatedAt = requireNotNull(AppleStateProjection.date(stored.value("updatedAt"))),
    )
}

private inline fun <reified T> values(state: JsonObject, key: String, source: Set<String>, identity: (String) -> String): MutableMap<String, T> {
    val rows = state.array(key)
    require(rows.size % 2 == 0)
    val result = mutableMapOf<String, T>()
    rows.chunked(2).forEach { (id, value) ->
        val player = appleUUID(id.jsonPrimitive.content)
        if (player in source) require(result.put(identity(player), GameJson.decodeFromJsonElement<T>(value)) == null)
    }
    return result
}

data class SharedStatisticsGames(val games: List<Game>, val unavailableCount: Int)

fun projectSharedStatistics(documents: Iterable<SharedMatchDocument>): SharedStatisticsGames {
    val games = mutableListOf<Game>()
    var unavailable = 0
    documents.groupBy { it.id }.values.map { versions -> versions.maxBy { it.revision } }
        .filter { it.mayCountInStatistics }.forEach { document ->
            try {
                document.statisticsGame()?.let(games::add)
            } catch (_: IllegalArgumentException) {
                unavailable++
            } catch (_: IllegalStateException) {
                unavailable++
            } catch (_: ArithmeticException) {
                unavailable++
            } catch (_: NoSuchElementException) {
                unavailable++
            }
        }
    return SharedStatisticsGames(games, unavailable)
}
