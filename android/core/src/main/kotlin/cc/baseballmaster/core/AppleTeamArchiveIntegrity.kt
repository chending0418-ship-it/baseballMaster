package cc.baseballmaster.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/** Integrity and structural validation only; this never grants shared-team authority. */
data class AppleTeamArchiveIntegrityResult(val version: Int, val teamName: String, val gameCount: Int, val activeGameCount: Int)

/** Read-only wire values, returned only after every row and the final seal validate.
 * Keep the original file bytes for storage/export: JSON re-encoding changes the seal.
 */
class AppleTeamArchive internal constructor(
    val manifest: JsonObject,
    val records: List<JsonObject>,
    val summary: AppleTeamArchiveIntegrityResult,
)

object AppleTeamArchiveIntegrity {
    private const val MAX_BYTES = 100_000_000L
    private const val MAX_LINE = 32 * 1024 * 1024
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun uuid(value: JsonElement?) = appleUUID(value?.jsonPrimitive?.content ?: error("缺少编号"))
    private fun JsonObject.requiredArray(key: String) = this[key]?.jsonArray ?: error("缺少 $key")
    private fun JsonObject.requiredBoolean(key: String) = this[key]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull ?: error("缺少 $key")
    private fun JsonObject.requiredInt(key: String) = this[key]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("缺少 $key")
    private fun JsonObject.requiredText(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("缺少 $key")

    fun inspect(input: InputStream): AppleTeamArchiveIntegrityResult = parse(input, retainRecords = false).summary

    /** Offline archive reader, not a conversion to editable Android matches. */
    fun read(input: InputStream): AppleTeamArchive = parse(input, retainRecords = true)

    private fun parse(input: InputStream, retainRecords: Boolean): AppleTeamArchive {
        val records = mutableListOf<JsonObject>()
        var manifest: JsonObject? = null
        var entries: List<JsonObject> = emptyList()
        var profileSources = emptySet<String>()
        var teamName = ""
        var version = 0
        var count = 0
        var active = 0
        var sealed = false
        val chain = MessageDigest.getInstance("SHA-256")
        val line = ByteArrayOutputStream()
        fun process(raw: ByteArray) {
            require(!sealed) { "封印后有额外数据" }
            val envelope = AppleWireJson.parse(raw).jsonObject
            val bytes = Base64.getDecoder().decode(envelope.requiredText("payload"))
            require(digest(bytes) == envelope.requiredText("checksum")) { "档案行校验失败" }
            val value = AppleWireJson.parse(bytes)
            when (envelope.requiredText("kind")) {
                "manifest" -> {
                    require(manifest == null && count == 0) { "档案清单位置无效" }
                    val m = value.jsonObject
                    require(m.requiredText("format") == "BaseballMaster Team Archive") { "档案格式不受支持" }
                    version = m.requiredInt("version"); require(version in 1..2) { "档案版本不受支持" }
                    uuid(m["recoveryEpoch"]); require(m.requiredText("snapshot").isNotEmpty()) { "档案快照标识缺失" }
                    entries = m.requiredArray("games").map { it.jsonObject }
                    require(entries.size <= 5000 && entries.map { uuid(it["id"]) }.distinct().size == entries.size) { "比赛清单无效" }
                    entries.forEach {
                        require(it.requiredInt("serverRevision") >= 0) { "比赛修订无效" }
                        require(!it.requiredBoolean("purged") || it.requiredBoolean("deleted")) { "已清除比赛必须保留删除标记" }
                    }
                    val profile = m["profile"]?.jsonObject ?: error("缺少球队资料")
                    uuid(profile["id"]); teamName = profile.requiredText("name")
                    require(teamName.isNotBlank() && profile.requiredInt("revision") >= 0) { "球队资料无效" }
                    profile.requiredText("shortName"); profile.requiredText("city")
                    val players = profile.requiredArray("players").map { it.jsonObject }
                    require(players.map { uuid(it["id"]) }.distinct().size == players.size) { "球队球员编号重复" }
                    val sources = players.map { uuid(it["sourceID"]) }
                    require(sources.distinct().size == sources.size) { "球员来源编号重复" }
                    players.forEach { player ->
                        player.requiredText("chineseName"); player.requiredText("englishName")
                        val numbers = player.requiredArray("numbers").map { it.jsonPrimitive.also { n -> require(n.isString) { "球员背号须保留原文" } }.content }
                        require(numbers.distinct().size == numbers.size && numbers.all { it == "00" || it.toIntOrNull()?.let { n -> n in 0..99 && n.toString() == it } == true }) { "球员背号无效" }
                        player["archived"]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.booleanOrNull != null) { "球员归档状态无效" } }
                    }
                    profileSources = sources.toSet(); manifest = m
                }
                "game" -> {
                    require(manifest != null && count < entries.size) { "比赛条目数量无效" }
                    val game = value.jsonObject; val expected = entries[count]
                    require(uuid(game["id"]) == uuid(expected["id"]) && game.requiredInt("serverRevision") == expected.requiredInt("serverRevision") && game.requiredBoolean("deleted") == expected.requiredBoolean("deleted") && game.requiredBoolean("purged") == expected.requiredBoolean("purged")) { "比赛条目与清单不一致" }
                    val payload = game["payload"]?.takeUnless { it == JsonNull }
                    val projection = game["projection"]?.takeUnless { it == JsonNull }
                    require(if (game.requiredBoolean("purged")) payload == null && projection == null else payload != null && projection != null) { "比赛内容不完整" }
                    if (!game.requiredBoolean("deleted")) active++
                    val links = game.requiredArray("playerLinks").map { it.jsonObject }
                    require(links.map { uuid(it["sourceID"]) }.distinct().size == links.size) { "球员映射重复" }
                    require(links.all { uuid(it["targetID"]) in profileSources }) { "球员映射目标无效" }
                    game["historySourceID"]?.takeUnless { it == JsonNull }?.let(::uuid)
                    if (payload != null) {
                        val stored = payload.jsonObject
                        AppleStoredGameValidation.validate(stored)
                        val state = stored["state"]?.jsonObject ?: error("比赛状态缺失")
                        val team = state[if (stored.requiredBoolean("isHome")) "homeTeam" else "awayTeam"]?.jsonObject ?: error("比赛球队缺失")
                        val sources = team.requiredArray("players").map { uuid(it.jsonObject["id"]) }.toSet()
                        require(links.all { uuid(it["sourceID"]) in sources && uuid(it["targetID"]) in profileSources }) { "球员映射无效" }
                    }
                    if (retainRecords) records += game
                    count++
                }
                "seal" -> {
                    require(manifest != null && count == entries.size) { "档案未完整" }
                    require(value.jsonPrimitive.isString && value.jsonPrimitive.content == chain.digest().hex()) { "档案整体校验失败" }
                    sealed = true; return
                }
                else -> error("档案条目类型不受支持")
            }
            chain.update(raw); chain.update(10.toByte())
        }
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            total += n
            require(total <= MAX_BYTES) { "档案超过 100 MB，请选择较小的档案" }
            for (i in 0 until n) {
                if (buffer[i] == 10.toByte()) { process(line.toByteArray()); line.reset() }
                else { require(line.size() < MAX_LINE - 1) { "档案行过大" }; line.write(buffer[i].toInt()) }
            }
        }
        require(sealed && line.size() == 0) { "档案不完整" }
        return AppleTeamArchive(manifest ?: error("档案清单缺失"), records.toList(),
            AppleTeamArchiveIntegrityResult(version, teamName, count, active))
    }
}
