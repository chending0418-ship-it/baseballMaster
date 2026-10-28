package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.AppleSourceBackup
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
import kotlinx.serialization.json.*

/** Android-local recovery snapshot; this is explicitly not an iOS .bmbackup. */
@Serializable internal data class DeviceTeam(val id: String, val name: String, val payload: String, val isOpponent: Boolean)
@Serializable internal data class DeviceGame(val id: String, val payload: String, val revision: Long, val updatedAt: Long, val payloadVersion: Int)
@Serializable internal data class DeviceOperation(val id: String, val gameID: String, val action: String, val baseRevision: Long, val createdAt: Long)
@Serializable internal data class DeviceDraft(val gameID: String, val payload: String, val updatedAt: Long)
@Serializable internal data class DeviceAppleBackup(val id: String, val name: String, val gameCount: Int, val original: String, val current: String)
@Serializable internal data class DeviceAppleSource(val gameID: String, val backupID: String)
@Serializable internal data class DeviceSnapshot(val currentTeamID: String?, val teams: List<DeviceTeam>, val games: List<DeviceGame>, val operations: List<DeviceOperation>, val drafts: List<DeviceDraft>, val appleBackups: List<DeviceAppleBackup> = emptyList(), val appleSources: List<DeviceAppleSource> = emptyList())
@Serializable private data class DeviceEnvelope(val format: String, val version: Int, val payload: String, val checksum: String)

internal object AndroidDeviceBackup {
    private const val LIMIT = 100_000_000
    private const val FORMAT = "BaseballMaster Android Device Backup"
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    fun encode(snapshot: DeviceSnapshot): ByteArray {
        validate(snapshot)
        val version = if (snapshot.appleBackups.isEmpty() && snapshot.appleSources.isEmpty()) 1 else 2
        val value = json.encodeToJsonElement(snapshot).jsonObject
        val payload = (if (version == 1) JsonObject(value - "appleBackups" - "appleSources") else value).toString().toByteArray(Charsets.UTF_8)
        val data = json.encodeToString(DeviceEnvelope(FORMAT, version, Base64.getEncoder().encodeToString(payload), hash(payload))).toByteArray(Charsets.UTF_8)
        require(data.size <= LIMIT) { "备份超过 100 MB" }
        return data
    }
    fun decode(bytes: ByteArray): DeviceSnapshot {
        require(bytes.size <= LIMIT) { "备份超过 100 MB" }
        val outer = json.decodeFromJsonElement<DeviceEnvelope>(AppleWireJson.parse(bytes))
        require(outer.format == FORMAT && outer.version in 1..2) { "备份格式或版本不受支持" }
        val payload = Base64.getDecoder().decode(outer.payload)
        require(hash(payload) == outer.checksum) { "备份校验失败" }
        return json.decodeFromJsonElement<DeviceSnapshot>(AppleWireJson.parse(payload)).also {
            require(outer.version == 2 || it.appleBackups.isEmpty() && it.appleSources.isEmpty()) { "来源备份需要格式 2" }
            validate(it)
        }
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
        require(snapshot.appleBackups.map { it.id }.distinct().size == snapshot.appleBackups.size && snapshot.appleSources.map { it.gameID }.distinct().size == snapshot.appleSources.size) { "来源备份编号重复" }
        val links = snapshot.appleSources.associate { it.gameID to it.backupID }
        val sourceIDs = snapshot.appleBackups.map { it.id }.toSet()
        require(games.filterValues { it.appleSourceID != null }.keys == links.keys && links.all { (gameID, sourceID) -> games[gameID]?.appleSourceID == sourceID }) { "比赛的来源标记或映射缺失" }
        require(links.values.all { it in sourceIDs } && links.keys.all { it in games }) { "比赛来源缺失" }
        val originalOperationOwners = mutableMapOf<String, String>()
        snapshot.appleBackups.forEach { source ->
            val original = Base64.getDecoder().decode(source.original); val current = Base64.getDecoder().decode(source.current)
            val old = AppleSourceBackup.prepare(original)
            require(old.digest == source.id) { "原始来源校验失败" }
            old.games(source.id).forEach { game ->
                (game.journal + game.historyRevisions.flatMap { it.previousRecords }).map { it.id.lowercase(java.util.Locale.ROOT) }.distinct().forEach { id ->
                    require(originalOperationOwners.put(id, game.id) == null) { "不同来源比赛的原始操作编号重复" }
                }
            }
            val next = AppleSourceBackup.validateLineage(original, current)
            require(source.name == old.name && source.gameCount == next.games().size) { "来源摘要不一致" }
            val imported = next.games(source.id)
            require(links.filterValues { it == source.id }.keys == imported.map { it.id }.toSet()) { "来源比赛映射不完整" }
            imported.forEach { game -> require(GameJson.encodeToJsonElement(game) == GameJson.encodeToJsonElement(games.getValue(game.id))) { "比赛与原始来源不一致" } }
            next.teams().forEach { team -> require(teams.any { GameJson.encodeToJsonElement(it) == GameJson.encodeToJsonElement(team) }) { "来源球队名单不一致" } }
        }
        require(snapshot.drafts.none { it.gameID in links }) { "来源比赛包含尚不支持的纠错草稿" }
        require(snapshot.operations.none { it.id.lowercase(java.util.Locale.ROOT) in originalOperationOwners }) { "本机操作编号与原始来源历史冲突" }
        snapshot.operations.forEach { require(it.gameID in games && it.baseRevision >= 0 && it.id.isNotBlank() && it.action.isNotEmpty()) { "操作记录无效" } }
        snapshot.drafts.forEach { draft ->
            val game = games[draft.gameID] ?: error("纠错草稿比赛不存在")
            val parsed = GameJson.decodeFromString<CorrectionDraft>(draft.payload)
            require(parsed.gameID == draft.gameID && parsed.baseRevision <= game.revision) { "纠错草稿无效" }
        }
    }
}
