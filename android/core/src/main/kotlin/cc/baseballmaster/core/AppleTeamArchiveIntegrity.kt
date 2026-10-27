package cc.baseballmaster.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Framing and identity checks only. Full StoredGame validation is required before any import. */
data class AppleTeamArchiveIntegrityResult(val version: Int, val teamName: String, val gameCount: Int, val activeGameCount: Int)

object AppleTeamArchiveIntegrity {
    private const val MAX_LINE = 32 * 1024 * 1024
    private val json = Json
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun uuid(value: JsonElement?) = appleUUID(value?.jsonPrimitive?.content ?: error("缺少编号"))
    private fun JsonObject.requiredArray(key: String) = this[key]?.jsonArray ?: error("缺少 $key")
    private fun JsonObject.requiredBoolean(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: error("缺少 $key")
    private fun JsonObject.requiredInt(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: error("缺少 $key")
    private fun JsonObject.requiredText(key: String) = this[key]?.jsonPrimitive?.content ?: error("缺少 $key")

    fun inspect(input: InputStream): AppleTeamArchiveIntegrityResult {
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
            val envelope = json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
            val bytes = Base64.getDecoder().decode(envelope.requiredText("payload"))
            require(digest(bytes) == envelope.requiredText("checksum")) { "档案行校验失败" }
            val value = json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            when (envelope.requiredText("kind")) {
                "manifest" -> {
                    require(manifest == null && count == 0) { "档案清单位置无效" }
                    val m = value.jsonObject
                    require(m.requiredText("format") == "BaseballMaster Team Archive") { "档案格式不受支持" }
                    version = m.requiredInt("version"); require(version in 1..2) { "档案版本不受支持" }
                    uuid(m["recoveryEpoch"]); m.requiredText("snapshot")
                    entries = m.requiredArray("games").map { it.jsonObject }
                    require(entries.size <= 5000 && entries.map { uuid(it["id"]) }.distinct().size == entries.size) { "比赛清单无效" }
                    val profile = m["profile"]?.jsonObject ?: error("缺少球队资料")
                    uuid(profile["id"]); teamName = profile.requiredText("name"); profile.requiredInt("revision"); profile.requiredText("shortName"); profile.requiredText("city")
                    val players = profile.requiredArray("players").map { it.jsonObject }
                    require(players.map { uuid(it["id"]) }.distinct().size == players.size) { "球队球员编号重复" }
                    val sources = players.map { uuid(it["sourceID"]) }
                    require(sources.distinct().size == sources.size) { "球员来源编号重复" }
                    players.forEach { it.requiredText("chineseName"); it.requiredText("englishName"); it.requiredArray("numbers") }
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
                    if (payload != null) {
                        val stored = payload.jsonObject; val state = stored["state"]?.jsonObject ?: error("比赛状态缺失")
                        val team = state[if (stored.requiredBoolean("isHome")) "homeTeam" else "awayTeam"]?.jsonObject ?: error("比赛球队缺失")
                        val sources = team.requiredArray("players").map { uuid(it.jsonObject["id"]) }.toSet()
                        require(links.all { uuid(it["sourceID"]) in sources && uuid(it["targetID"]) in profileSources }) { "球员映射无效" }
                    }
                    count++
                }
                "seal" -> {
                    require(manifest != null && count == entries.size) { "档案未完整" }
                    require(value.jsonPrimitive.content == chain.digest().hex()) { "档案整体校验失败" }
                    sealed = true; return
                }
                else -> error("档案条目类型不受支持")
            }
            chain.update(raw); chain.update(10.toByte())
        }
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            for (i in 0 until n) {
                if (buffer[i] == 10.toByte()) { process(line.toByteArray()); line.reset() }
                else { require(line.size() < MAX_LINE - 1) { "档案行过大" }; line.write(buffer[i].toInt()) }
            }
        }
        require(sealed && line.size() == 0) { "档案不完整" }
        return AppleTeamArchiveIntegrityResult(version, teamName, count, active)
    }
}
