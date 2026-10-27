package cc.baseballmaster.core

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Read and verify the iOS .bmbackup envelope without changing or importing any local data. */
data class AppleBackupInspection(
    val version: Int,
    val payloadBytes: ByteArray,
    val snapshot: JsonObject,
    val teamCount: Int,
    val opponentCount: Int,
    val gameCount: Int,
)

object AppleBackupEnvelope {
    private const val MAX_BYTES = 100_000_000
    private val json = Json { ignoreUnknownKeys = false }
    fun inspect(bytes: ByteArray): AppleBackupInspection {
        require(bytes.size <= MAX_BYTES) { "备份超过 100 MB" }
        val envelope = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(envelope["format"]?.jsonPrimitive?.content == "BaseballMaster Backup") { "备份格式不受支持" }
        val version = envelope["version"]?.jsonPrimitive?.intOrNull ?: error("备份版本缺失")
        require(version in 1..7) { "备份版本不受支持" }
        require(envelope["createdAt"]?.jsonPrimitive?.doubleOrNull?.isFinite() == true) { "备份时间无效" }
        val payload = Base64.getDecoder().decode(envelope["payload"]?.jsonPrimitive?.content ?: error("备份内容缺失"))
        val checksum = envelope["checksum"]?.jsonPrimitive?.content ?: error("备份校验值缺失")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        require(checksum == digest) { "备份校验失败，原有数据未修改" }
        val snapshot = json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        fun list(key: String, required: Boolean = false): JsonArray = when (val value = snapshot[key]) {
            null, JsonNull -> if (required) error("备份缺少 $key") else JsonArray(emptyList())
            else -> value.jsonArray
        }
        fun id(obj: JsonObject) = obj["id"]?.jsonPrimitive?.content?.let { appleUUID(it) } ?: error("编号缺失")
        val teams = list("teams", true); val opponents = list("opponentTeams")
        // iOS may keep historical snapshots with the same team and player IDs as the live roster.
        list("archivedTeams").forEach { it.jsonObject }
        val allTeams = (teams + opponents).map { it.jsonObject }
        val teamIDs = allTeams.map(::id)
        require(teamIDs.distinct().size == teamIDs.size) { "球队编号重复" }
        val current = snapshot["currentTeamID"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { appleUUID(it) }
        require(if (teams.isEmpty()) current == null else current != null && teams.any { id(it.jsonObject) == current }) { "当前球队无效" }
        val players = allTeams.flatMap { team ->
            require(team["name"]?.jsonPrimitive?.content?.isNotBlank() == true) { "球队名称为空" }
            (team["players"]?.jsonArray ?: error("球员名单缺失")).map { it.jsonObject }
        }
        val ids = players.map(::id)
        require(ids.distinct().size == ids.size) { "球员编号重复" }
        players.forEach { player ->
            val numbers = (player["numbers"]?.jsonArray ?: error("球员背号缺失")).map { it.jsonPrimitive.int }
            require(numbers.all { it in 0..99 }) { "球员背号无效" }
            player["jerseyNumbers"]?.takeUnless { it == JsonNull }?.jsonArray?.let { labels ->
                val texts = labels.map { it.jsonPrimitive.content }
                require(texts.isNotEmpty() && texts.distinct().size == texts.size && texts.all { it == "00" || it.toIntOrNull()?.let { n -> n in 0..99 && n.toString() == it } == true } && texts.map { it.toInt() } == numbers) { "球员背号原文无效" }
            }
        }
        val seasons = list("seasons", true).map { it.jsonObject }
        val seasonIDs = seasons.map { season ->
            val seasonID = season["id"]?.jsonPrimitive?.content ?: error("赛季编号缺失")
            require(seasonID.isNotEmpty() && season["name"]?.jsonPrimitive?.content?.isNotBlank() == true) { "赛季资料无效" }
            seasonID
        }
        require(seasonIDs.distinct().size == seasonIDs.size) { "赛季编号重复" }
        val recordIDs = list("playerGameRecords", true).map { id(it.jsonObject) }
        require(recordIDs.distinct().size == recordIDs.size) { "个人记录编号重复" }
        val games = list("games")
        val gameIDs = games.map { id(it.jsonObject) }
        require(gameIDs.distinct().size == gameIDs.size) { "比赛编号重复" }
        return AppleBackupInspection(version, payload, snapshot, teams.size, opponents.size, games.size)
    }
}
