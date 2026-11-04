package cc.baseballmaster.core

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Strict roster-only wire prototype; deliberately rejects matches and legacy records. */
object AppleRosterBackup {
    private val json = Json { encodeDefaults = true }
    /** Converts roster records only; it does not import games, seasons, or grant sharing authority. */
    fun decode(inspection: AppleBackupInspection): List<Team> {
        fun parseTeam(value: JsonElement, opponent: Boolean, archived: Boolean): Team {
            val obj = value.jsonObject
            fun text(name: String): String = obj[name]?.jsonPrimitive?.content ?: error("球队缺少 $name")
            val id = appleUUID(text("id"))
            val players = (obj["players"]?.jsonArray ?: error("球队名单缺失")).map { raw ->
                val p = raw.jsonObject
                fun field(name: String): String = p[name]?.jsonPrimitive?.content ?: error("球员缺少 $name")
                val numbers = p["numbers"]?.jsonArray?.map { it.jsonPrimitive.int } ?: error("球员背号缺失")
                val labels = p["jerseyNumbers"]?.takeUnless { it == JsonNull }?.jsonArray?.map { it.jsonPrimitive.content } ?: numbers.map { it.toString() }
                require(labels.isNotEmpty() && labels.map { it.toIntOrNull() } == numbers) { "球员背号不受支持" }
                val chinese = field("chineseName"); val english = field("englishName")
                val position = p["primaryPosition"]?.jsonPrimitive?.int ?: error("球员守位缺失")
                require(position in 1..10) { "球员守位无效" }
                Player(appleUUID(field("id")), chinese.ifBlank { english }, labels.first(),
                    englishName = english, position = position, jerseyNumbers = labels, chineseName = chinese, archived = archived).also(Roster::validate)
            }
            return Team(id, text("name"), players, isOpponent = opponent, city = text("city"), shortName = text("shortName"), archived = archived)
        }
        fun array(name: String) = inspection.snapshot[name]?.takeUnless { it == JsonNull }?.jsonArray ?: JsonArray(emptyList())
        val active = array("teams").map { parseTeam(it, false, false) }.toMutableList()
        val opponents = array("opponentTeams").map { parseTeam(it, true, false) }
        array("archivedTeams").map { parseTeam(it, false, true) }.forEach { old ->
            val index = active.indexOfFirst { it.id == old.id }
            if (index < 0) active += old
            else {
                val current = active[index]
                active[index] = current.copy(players = current.players + old.players.filter { previous -> current.players.none { it.id == previous.id } })
            }
        }
        val all = active + opponents
        require(all.map { it.id }.distinct().size == all.size) { "球队编号冲突" }
        val ids = all.flatMap { it.players }.map { it.id }
        require(ids.distinct().size == ids.size) { "球员编号冲突" }
        return all
    }
    fun encode(teams: List<Team>, games: List<Game>, currentTeamID: String?, createdAtMillis: Long): ByteArray {
        require(games.isEmpty()) { "完整比赛互导尚未完成，不能生成不完整备份" }
        require(teams.all { it.source == "local" && !it.archived && it.players.none { p -> p.archived } }) { "此备份暂不支持共享或归档资料" }
        val own = teams.filter { !it.isOpponent }; val opponents = teams.filter { it.isOpponent }
        require(if (own.isEmpty()) currentTeamID == null else own.any { it.id == currentTeamID }) { "当前球队无效" }
        val ids = teams.map { appleUUID(it.id) }; require(ids.distinct().size == ids.size)
        val playerIDs = teams.flatMap { it.players }.map { appleUUID(it.id) }; require(playerIDs.distinct().size == playerIDs.size)
        fun player(p: Player): JsonObject {
            Roster.validate(p)
            require(p.position in 0..10) { "球员守位无效" }
            return buildJsonObject {
                put("id", p.id.uppercase()); put("chineseName", p.chineseName ?: p.name); put("englishName", p.englishName)
                put("numbers", buildJsonArray { p.jerseyNumbers.forEach { add(it.toInt()) } })
                put("jerseyNumbers", buildJsonArray { p.jerseyNumbers.forEach { add(it) } })
                put("primaryPosition", if (p.position == 0) 1 else p.position)
            }
        }
        fun team(t: Team) = buildJsonObject {
            put("id", t.id.uppercase()); put("name", t.name); put("shortName", t.shortName); put("city", t.city)
            put("players", buildJsonArray { t.players.forEach { add(player(it)) } })
        }
        val snapshot = buildJsonObject {
            put("teams", buildJsonArray { own.forEach { add(team(it)) } })
            put("opponentTeams", buildJsonArray { opponents.forEach { add(team(it)) } })
            if (currentTeamID != null) put("currentTeamID", currentTeamID.uppercase())
            put("seasons", JsonArray(emptyList())); put("playerGameRecords", JsonArray(emptyList()))
            put("games", JsonArray(emptyList())); put("archivedTeams", JsonArray(emptyList()))
        }
        val payload = json.encodeToString(JsonObject.serializer(), snapshot).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val envelope = buildJsonObject {
            put("format", "BaseballMaster Backup"); put("version", 7)
            put("createdAt", createdAtMillis / 1000.0 - 978307200.0)
            put("payload", Base64.getEncoder().encodeToString(payload)); put("checksum", digest)
        }
        return json.encodeToString(JsonObject.serializer(), envelope).toByteArray()
    }
}
