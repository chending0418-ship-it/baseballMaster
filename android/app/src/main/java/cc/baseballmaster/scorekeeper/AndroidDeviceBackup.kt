package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.AppleWireJson
import cc.baseballmaster.core.CorrectionDraft
import cc.baseballmaster.core.GameJson
import cc.baseballmaster.core.Roster
import cc.baseballmaster.core.Team
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Android-local recovery snapshot; this is explicitly not an iOS .bmbackup. */
@Serializable internal data class DeviceTeam(val id: String, val name: String, val payload: String, val isOpponent: Boolean)
@Serializable internal data class DeviceGame(val id: String, val payload: String, val revision: Long, val updatedAt: Long, val payloadVersion: Int)
@Serializable internal data class DeviceOperation(val id: String, val gameID: String, val action: String, val baseRevision: Long, val createdAt: Long)
@Serializable internal data class DeviceDraft(val gameID: String, val payload: String, val updatedAt: Long)
@Serializable internal data class DeviceSnapshot(val currentTeamID: String?, val teams: List<DeviceTeam>, val games: List<DeviceGame>, val operations: List<DeviceOperation>, val drafts: List<DeviceDraft>)
@Serializable private data class DeviceEnvelope(val format: String, val version: Int, val payload: String, val checksum: String)

internal object AndroidDeviceBackup {
    private const val LIMIT = 100_000_000
    private const val FORMAT = "BaseballMaster Android Device Backup"
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    fun encode(snapshot: DeviceSnapshot): ByteArray {
        validate(snapshot)
        val payload = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val data = json.encodeToString(DeviceEnvelope(FORMAT, 1, Base64.getEncoder().encodeToString(payload), hash(payload))).toByteArray(Charsets.UTF_8)
        require(data.size <= LIMIT) { "备份超过 100 MB" }
        return data
    }
    fun decode(bytes: ByteArray): DeviceSnapshot {
        require(bytes.size <= LIMIT) { "备份超过 100 MB" }
        val outer = json.decodeFromJsonElement<DeviceEnvelope>(AppleWireJson.parse(bytes))
        require(outer.format == FORMAT && outer.version == 1) { "备份格式或版本不受支持" }
        val payload = Base64.getDecoder().decode(outer.payload)
        require(hash(payload) == outer.checksum) { "备份校验失败" }
        return json.decodeFromJsonElement<DeviceSnapshot>(AppleWireJson.parse(payload)).also(::validate)
    }
    private fun validate(snapshot: DeviceSnapshot) {
        require(snapshot.teams.map { it.id }.distinct().size == snapshot.teams.size && snapshot.games.map { it.id }.distinct().size == snapshot.games.size && snapshot.operations.map { it.id }.distinct().size == snapshot.operations.size && snapshot.drafts.map { it.gameID }.distinct().size == snapshot.drafts.size) { "备份编号重复" }
        val teams = snapshot.teams.map { entity ->
            val team = GameJson.decodeFromString<Team>(entity.payload)
            require(entity.id == team.id && entity.name == team.name && entity.isOpponent == team.isOpponent && team.name.isNotBlank()) { "球队资料不一致" }
            team.players.forEach(Roster::validate)
            team
        }
        require(snapshot.currentTeamID == null || teams.any { it.id == snapshot.currentTeamID && !it.isOpponent }) { "当前球队不存在" }
        val games = snapshot.games.associate { entity ->
            val game = GameRepository.decode(GameEntity(entity.id, entity.payload, entity.revision, entity.updatedAt, entity.payloadVersion))
            require(entity.id == game.id) { "比赛编号不一致" }
            game.id to game
        }
        snapshot.operations.forEach { require(it.gameID in games && it.baseRevision >= 0 && it.id.isNotBlank() && it.action.isNotEmpty()) { "操作记录无效" } }
        snapshot.drafts.forEach { draft ->
            val game = games[draft.gameID] ?: error("纠错草稿比赛不存在")
            val parsed = GameJson.decodeFromString<CorrectionDraft>(draft.payload)
            require(parsed.gameID == draft.gameID && parsed.baseRevision <= game.revision) { "纠错草稿无效" }
        }
    }
}
