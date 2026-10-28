package cc.baseballmaster.core

import java.time.ZoneId
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * Non-mutating validation of the invariants checked by iOS LocalBackup.validate.
 *
 * This is not a converter or permission check. A valid stored game may still contain history
 * commands the Android replay engine cannot execute. Callers must retain the original payload
 * and separately reject unsupported conversion/editing; this validator never grants authority.
 * Optional fields from older iOS formats remain optional. Unknown object fields are retained by
 * the caller, not decoded away. Inspected enum values and history versions are checked; associated command payloads are not
 * fully decoded here, and must not be treated as verified replay instructions.
 */
object AppleStoredGameValidation {
    private const val MAX_COUNT = 1_000_000
    private val battingFields = setOf("plateAppearances", "atBats", "runs", "hits", "doubles", "triples", "homeRuns", "runsBattedIn", "walks", "hitByPitch", "strikeouts", "stolenBases", "caughtStealing", "sacrifices")
    private val pitchingFields = setOf("outsRecorded", "battersFaced", "hits", "runs", "earnedRuns", "walks", "hitByPitch", "strikeouts", "wildPitches", "pitches", "strikes")
    private val fieldingFields = setOf("putouts", "assists", "errors", "doublePlays")
    private val commandKinds = setOf("swapOpeningSides", "pitch", "illegalPitch", "play", "runner", "hitByPitch", "intentionalWalk", "foulBunt", "droppedThirdStrike", "pitcher", "batter", "pinchRunner", "fielder", "position", "lineup", "violation", "adjudication", "situation", "endHalf", "decision", "extraInning", "enableTB", "placeTB", "skipTB", "finish", "reopen", "clock", "note", "additionalErrors", "review", "doubleSwitch", "unavailable")
    private val categories = setOf("game", "clock", "pitch", "battedBall", "runner", "out", "substitution", "violation", "tiebreak", "correction")
    private val endReasons = setOf("规定局数完成", "再见分结束", "时间限制结束", "提前结束规则", "弃权结束", "天气或场地原因", "比赛中断", "记录员结束记录")
    private val regions = setOf("infieldLeft", "infieldMiddle", "infieldRight", "left", "center", "right", "foulLeft", "foulRight")

    fun validate(stored: JsonObject) {
        stored.id("id")
        for (key in listOf("createdAt", "updatedAt")) stored.date(key)
        for (key in listOf("scheduledAt", "startedAt", "playedAt")) stored.optional(key)?.let { date(it, key) }
        stored.optional("playedTimeZoneID")?.let { zone(it, "比赛时区") }
        stored.array("dateRevisions", optional = true).forEach { raw ->
            val revision = raw.jsonObject
            for (key in listOf("changedAt", "previousDate", "playedAt")) revision.date(key)
            zone(revision.required("timeZoneID"), "比赛时间更正时区")
            revision.optional("previousTimeZoneID")?.let { zone(it, "原比赛时区") }
        }
        stored.text("seasonID")
        stored.bool("isHome")
        stored.optional("isSpectator")?.let { boolean(it, "isSpectator") }
        for (key in listOf("ourTeamID", "opponentTeamID", "sharedTeamID", "sharedGameID", "historySourceID")) stored.optional(key)?.let(::uuid)
        stored.optional("revision")?.let { number(it, "revision", 0..Int.MAX_VALUE) }
        val status = stored.number("status", 0..2)
        val rules = stored.obj("rules")
        val slowPitch = rules(rules, status == 2)
        val state = stored.obj("state")
        state(state, slowPitch, status == 2)
        lineup(stored.array("lineup"))
        stored.optional("secondaryLineup")?.let { lineup(it.jsonArray) }

        val revisions = state.array("correctionRevisions", optional = true).map { it.jsonObject }
        unique(revisions.map { it.id("id") }, "更正记录编号重复")
        val journals = listOfNotNull(state.optional("historyJournal")?.jsonObject) + revisions.map { revision ->
            revision.date("date"); revision.text("reason")
            unique(revision.ids("appliedOperationIDs"), "更正操作编号重复")
            revision.array("summary").forEach { text(it, "更正摘要") }
            revision.obj("previousJournal")
        }
        val hasOpeningSwap = journals.any { journal -> journal.array("operations").any { "swapOpeningSides" in it.jsonObject.obj("command") } }
        journals.forEach { journal -> journal(journal, state, slowPitch, hasOpeningSwap) }
    }

    /** Also used for pre-match-format historical player records. */
    fun validateBattingLine(line: JsonObject) = counts(line, battingFields)

    private fun rules(value: JsonObject, scheduled: Boolean): Boolean {
        value.number("scheduledInnings", 1..10_000)
        val fielders = value.number("fieldersCount", 1..99)
        val mode = value.enum("mode", setOf("standard", "coachPitch", "slowPitch"), optional = true) ?: "standard"
        val slow = mode == "slowPitch"
        number(value.optional("initialBalls") ?: JsonPrimitive(if (slow) 1 else 0), "初始坏球", 0..3)
        number(value.optional("initialStrikes") ?: JsonPrimitive(if (slow) 1 else 0), "初始好球", 0..2)
        val foul = value.enum("twoStrikeFoulPolicy", setOf("outImmediately", "oneExtraFoul"), optional = true)
        val timed = (value.enum("competitionFormat", setOf("innings", "timed"), optional = true) ?: if (slow) "timed" else "innings") == "timed"
        value.enum("recordingMethod", setOf("traditional", "situation"), optional = true)
        for (key in listOf("usesDesignatedHitter", "allowsTwoWayPlayer")) value.optional(key)?.let { boolean(it, key) }
        for (key in listOf("timeLimitMinutes", "timeWarningMinutes", "pitchLimit", "pitchWarningRemaining", "pitcherInningsLimit")) value.optional(key)?.let { number(it, key, 0..MAX_COUNT) }
        value.optional("halfInningRunLimit")?.let { number(it, "半局得分上限", 1..99) }
        value.optional("coachPitchLimit")?.let { number(it, "教练投手球数上限", 1..20) }
        value.optional("inningsLimit")?.let { number(it, "局数上限", 1..10_000) }
        if (slow) {
            require(fielders == 10) { "慢垒守备人数无效" }
            if (!scheduled) {
                require(foul != null) { "慢垒界外规则尚未补齐" }
                require(!timed || value.optional("timeLimitMinutes")?.let { number(it, "比赛时长", 1..MAX_COUNT) } != null) { "慢垒时间赛时长缺失" }
            }
        }
        return slow
    }

    private fun state(value: JsonObject, slow: Boolean, scheduled: Boolean) {
        val home = value.obj("homeTeam"); val away = value.obj("awayTeam")
        val homePlayers = team(home); val awayPlayers = team(away)
        require(home.id("id") != away.id("id")) { "比赛双方相同" }
        unique(homePlayers.keys.toList() + awayPlayers.keys.toList(), "比赛球员编号重复")
        situation(value)
        value.number("scheduledInnings", 1..10_000)
        for (key in listOf("homeHits", "awayHits", "homeErrors", "awayErrors")) value.number(key, 0..MAX_COUNT)
        value.bool("isFinal")
        value.optional("rulesSnapshot")?.jsonObject?.let { rules(it, scheduled) }
        value.enum("clockDisplayMode", setOf("elapsed", "remaining"), optional = true)
        value.enum("endReason", endReasons, optional = true)
        value.enum("pendingDecision", setOf("runLimit", "regulationHalf", "walkOff"), optional = true)
        for (key in listOf("clockRunningSince", "endedAt")) value.optional(key)?.let { date(it, key) }
        value.optional("clockElapsedSeconds")?.let { require(decimal(it, "计时") in 0.0..315_360_000.0) { "计时无效" } }
        value.optional("plateAppearancePitchCount")?.let { number(it, "打席投球数", 0..MAX_COUNT) }
        for ((side, players) in listOf("home" to homePlayers, "away" to awayPlayers)) {
            val order = value.ids("${side}BattingOrderIDs")
            unique(order, "打序球员重复")
            require(players.keys.containsAll(order)) { "打序球员不存在" }
            require(scheduled || players.isNotEmpty() && order.isNotEmpty()) { "已开始比赛缺少名单" }
            val defense = value.optional("${side}FieldingPlayerIDs")?.jsonArray?.map(::uuid)
            if (defense != null) {
                unique(defense, "守备球员重复")
                require(players.keys.containsAll(defense)) { "守备球员不存在" }
            }
            if (slow && !scheduled) {
                require(defense?.size == 10) { "慢垒 10 人守备不完整" }
                require(requireNotNull(defense).map { players.getValue(it) }.toSet() == (1..10).toSet()) { "慢垒守位不完整或重复" }
            }
            value.optional("active${side.replaceFirstChar { it.uppercase() }}PitcherID")?.let { require(uuid(it) in players) { "投手不存在" } }
        }
        val bases = pairs(value.array("baseRunners"), "垒况") { number(it, "垒位", 1..3).toString() }
        val runners = bases.values.map { raw -> player(raw.jsonObject).first }
        unique(runners, "跑者重复")
        require((if (value.bool("isTop")) awayPlayers else homePlayers).keys.containsAll(runners)) { "垒上球员不存在" }
        for ((key, fields) in listOf("batting" to battingFields, "pitching" to pitchingFields, "fielding" to fieldingFields)) {
            pairs(value.array(key), key, ::uuid).values.forEach { counts(it.jsonObject, fields) }
        }
        val logs = value.array("playLog").map { it.jsonObject }
        unique(logs.map { it.id("id") }, "日志编号重复")
        logs.forEach { log -> log.number("inning", 1..10_000); log.bool("isTop"); log.date("timestamp"); log.text("text"); log.bool("isIncomplete") }
        val events = value.array("scoringEvents", optional = true).map { it.jsonObject }
        unique(events.map { it.id("id") }, "事件编号重复")
        events.forEach { event ->
            event.number("inning", 1..10_000); event.bool("isTop"); event.date("timestamp"); event.text("title"); event.bool("needsReview")
            val category = event.enum("category", categories)
            event.enum("resolvedOutcome", Outcome.entries.map { it.title }.toSet(), optional = true)
            event.enum("ballStatus", setOf("活球", "死球", "延迟死球", "按裁判宣判"), optional = true)
            for (key in listOf("logEntryID", "primaryPlayerID", "secondaryPlayerID", "plateAppearanceBatterID", "plateAppearanceID")) event.optional(key)?.let(::uuid)
            event.array("runnerMovements").forEach { raw -> raw.jsonObject.let { it.id("playerID"); it.text("origin"); it.text("destination") } }
            event.optional("automaticRunnerRuns")?.let { number(it, "自动跑者得分", 0..MAX_COUNT) }
            event.optional("battedBall")?.jsonObject?.let { observation ->
                require(category == "battedBall") { "击球观察事件类型无效" }
                observation(observation, slow)
                observation.optional("fielding")?.jsonObject?.let { defense ->
                    require(observation.text("precision") != "unknown" && defense.optional("status") != null) { "击球守备观察无效" }
                    require(defense.text("status") != "noFielding" || event.optional("resolvedOutcome")?.let { text(it, "击球结果") } == "本垒打") { "无守备处理只适用于全垒打" }
                    require(event.optional("resolvedOutcome")?.let { text(it, "击球结果") } != "对方失误" || defense.text("status") == "pending" || defense.optional("errorPosition") != null) { "失误责任缺失" }
                }
            }
            for (key in listOf("beforeSituation", "afterSituation")) event.optional(key)?.jsonObject?.let(::situation)
        }
        value.optional("plateAppearances")?.jsonArray?.let { raw ->
            val appearances = raw.map { it.jsonObject }; val ids = appearances.map { it.id("id") }
            unique(ids, "打席标识重复")
            appearances.forEach { appearance ->
                appearance.number("inning", 1..10_000); appearance.bool("isTop"); appearance.ids("batterIDs")
                appearance.bool("completed"); appearance.bool("interrupted")
                appearance.optional("previousSegmentID")?.let { require(uuid(it) in ids) { "前段打席标识不存在" } }
            }
            value.optional("currentPlateAppearanceID")?.let { require(uuid(it) in ids) { "当前打席标识不存在" } }
            events.forEach { event -> event.optional("plateAppearanceID")?.let { require(uuid(it) in ids) { "事件关联打席不存在" } } }
        }
    }

    private fun journal(value: JsonObject, current: JsonObject, slow: Boolean, hasOpeningSwap: Boolean) {
        require(value.number("version", 1..Int.MAX_VALUE) == 1) { "纠错记录版本不受支持" }
        value.bool("legacyPrefix")
        val operations = value.array("operations").map { it.jsonObject }
        unique(operations.map { it.id("id") }, "纠错操作编号重复")
        operations.forEach { operation ->
            val command = operation.obj("command")
            require(command.size == 1 && command.keys.single() in commandKinds && command.values.single() is JsonObject) { "历史操作类型不受支持" }
            operation.date("timestamp"); operation.text("title")
            for (key in listOf("inserted", "edited", "acceptsNewContext")) operation.bool(key)
            for (key in listOf("eventIDs", "logIDs")) unique(operation.ids(key), "历史引用编号重复")
            operation.optional("nextAppearanceID")?.let(::uuid)
            operation.optional("correctedAt")?.let { date(it, "历史更正时间") }
            operation.array("errors").forEach { raw -> raw.jsonObject.let { it.id("id"); it.id("playerID"); it.number("count", 0..MAX_COUNT); it.text("note") } }
            operation.optional("battedBall")?.jsonObject?.let { observation(it, slow) }
            operation.optional("statisticsRuling")?.jsonObject?.let { ruling ->
                ruling.number("runsBattedIn", 0..MAX_COUNT)
                pairs(ruling.array("earnedRuns"), "自责分", ::uuid).values.forEach { number(it, "自责分", 0..MAX_COUNT) }
                unique(ruling.ids("unearnedRunnerIDs"), "非自责跑者编号重复")
            }
            val context = operation.obj("context")
            context.number("inning", 1..10_000); context.bool("isTop"); context.number("batterIndex", 0..MAX_COUNT)
            for (key in listOf("homeOrder", "awayOrder", "homeFielders", "awayFielders")) unique(context.ids(key), "历史阵容编号重复")
            pairs(context.array("positions"), "历史守位", ::uuid).values.forEach { number(it, "历史守位", 1..10) }
            val runners = pairs(context.array("runners"), "历史跑者") { number(it, "垒位", 1..3).toString() }.values.map(::uuid)
            unique(runners, "历史跑者重复")
        }
        val checkpointBytes = Base64.getDecoder().decode(value.text("checkpoint"))
        val checkpoint = AppleWireJson.parse(checkpointBytes).jsonObject
        require(checkpoint.optional("historyJournal") == null && checkpoint.optional("correctionRevisions") == null) { "检查点包含递归历史" }
        val currentHome = current.obj("homeTeam").id("id"); val currentAway = current.obj("awayTeam").id("id")
        val checkpointHome = checkpoint.obj("homeTeam").id("id"); val checkpointAway = checkpoint.obj("awayTeam").id("id")
        require(checkpointHome == currentHome && checkpointAway == currentAway || hasOpeningSwap && checkpointHome == currentAway && checkpointAway == currentHome) { "检查点比赛双方不符" }
        state(checkpoint, slow, scheduled = false)
    }

    private fun observation(value: JsonObject, slow: Boolean) {
        val type = value.enum("type", setOf("ground", "hardGround", "line", "fly", "popup", "bunt", "unknown"))
        require(!slow || type != "bunt") { "慢垒不支持触击观察" }
        val precision = value.enum("precision", setOf("unknown", "region", "point"))
        val region = value.enum("region", regions, optional = true)
        val x = value.optional("x")?.let { decimal(it, "落点 x") }; val y = value.optional("y")?.let { decimal(it, "落点 y") }
        require(when (precision) {
            "unknown" -> region == null && x == null && y == null
            "region" -> region != null && x == null && y == null
            "point" -> region == null && x != null && y != null && x in 0.0..1.0 && y in 0.0..1.0
            else -> false
        }) { "击球落点无效" }
        value.optional("fielding")?.jsonObject?.let(::defense)
        val details = value.array("runnerDetails", optional = true).map { it.jsonObject }
        unique(details.map { it.id("playerID") }, "跑者详情编号重复")
        details.forEach { detail -> detail.enum("cause", setOf("hit", "relay", "error", "award", "tag", "appeal", "pending")); detail.optional("fielding")?.jsonObject?.let(::defense) }
    }

    private fun defense(value: JsonObject) {
        val status = value.enum("status", setOf("recorded", "pending", "noFielding"), optional = true)
        val route = value.array("route").map { number(it, "守备路线", 1..10) }
        val error = value.optional("errorPosition")?.let { number(it, "失误守位", 1..10) }
        require(route.size <= 50 && (status != "recorded" || route.isNotEmpty()) && (status != "noFielding" || route.isEmpty() && error == null)) { "守备路线无效" }
        value.array("participants", optional = true).forEach { raw -> raw.jsonObject.let { it.number("position", 1..10); it.id("playerID"); it.text("name"); it.text("number") } }
    }

    private fun situation(value: JsonObject) {
        val inning = value.number("inning", 1..10_000)
        value.bool("isTop"); value.number("balls", 0..4); value.number("strikes", 0..3); value.number("outs", 0..3)
        for (key in listOf("homeBatterIndex", "awayBatterIndex")) value.number(key, 0..MAX_COUNT)
        for (key in listOf("homeRunsByInning", "awayRunsByInning")) {
            val scores = value.array(key)
            require(scores.size >= inning) { "逐局比分缺失" }
            scores.forEach { number(it, "逐局比分", 0..MAX_COUNT) }
        }
    }

    private fun lineup(value: JsonArray) {
        val entries = value.map { it.jsonObject }
        unique(entries.map { it.id("playerID") }, "初始阵容球员重复")
        entries.forEach { it.number("battingOrder", 0..MAX_COUNT); it.optional("position")?.let { position -> number(position, "初始守位", 1..10) } }
    }

    private fun team(value: JsonObject): Map<String, Int> {
        value.id("id"); require(value.text("name").isNotBlank()) { "球队名称为空" }; value.text("shortName"); value.text("city")
        val players = value.array("players").map { player(it.jsonObject) }
        unique(players.map { it.first }, "球队球员编号重复")
        return players.toMap()
    }

    private fun player(value: JsonObject): Pair<String, Int> {
        val id = value.id("id"); value.text("chineseName"); value.text("englishName")
        val numbers = value.array("numbers").map { number(it, "球员背号", 0..99) }
        value.optional("jerseyNumbers")?.jsonArray?.let { array ->
            val labels = array.map { text(it, "球员背号原文") }
            require(labels.isNotEmpty() && labels.distinct().size == labels.size && labels.all { it == "00" || it.toIntOrNull()?.let { n -> n in 0..99 && n.toString() == it } == true } && labels.map(String::toInt) == numbers) { "球员背号原文无效" }
        }
        return id to value.number("primaryPosition", 1..10)
    }

    private fun counts(value: JsonObject, fields: Set<String>) { fields.forEach { value.number(it, 0..MAX_COUNT) } }
    private fun unique(values: Collection<String>, reason: String) = require(values.distinct().size == values.size) { reason }
    private fun pairs(values: JsonArray, name: String, key: (JsonElement) -> String): Map<String, JsonElement> {
        require(values.size % 2 == 0) { "$name 键值不完整" }
        val pairs = values.chunked(2).map { key(it[0]) to it[1] }
        unique(pairs.map { it.first }, "$name 键重复")
        return pairs.toMap()
    }
    private fun JsonObject.optional(key: String) = this[key]?.takeUnless { it == JsonNull }
    private fun JsonObject.required(key: String) = optional(key) ?: error("缺少 $key")
    private fun JsonObject.obj(key: String) = required(key).jsonObject
    private fun JsonObject.array(key: String, optional: Boolean = false): JsonArray = this.optional(key)?.jsonArray ?: if (optional) JsonArray(emptyList()) else error("缺少 $key")
    private fun JsonObject.text(key: String) = text(required(key), key)
    private fun JsonObject.id(key: String) = uuid(required(key))
    private fun JsonObject.ids(key: String) = array(key).map(::uuid)
    private fun JsonObject.bool(key: String) = boolean(required(key), key)
    private fun JsonObject.number(key: String, range: IntRange) = number(required(key), key, range)
    private fun JsonObject.date(key: String) = date(required(key), key)
    private fun JsonObject.enum(key: String, values: Set<String>, optional: Boolean = false): String? {
        val element = this.optional(key) ?: if (optional) return null else error("缺少 $key")
        return text(element, key).also { require(it in values) { "$key 不受支持" } }
    }
    private fun text(value: JsonElement, name: String): String { val primitive = value.jsonPrimitive; require(primitive.isString) { "$name 不是文字" }; return primitive.content }
    private fun uuid(value: JsonElement) = appleUUID(text(value, "编号"))
    private fun boolean(value: JsonElement, name: String): Boolean { val primitive = value.jsonPrimitive; require(!primitive.isString) { "$name 不是布尔值" }; return primitive.booleanOrNull ?: error("$name 不是布尔值") }
    private fun number(value: JsonElement, name: String, range: IntRange): Int { val primitive = value.jsonPrimitive; val number = primitive.intOrNull; require(!primitive.isString && number != null && number in range) { "$name 超出支持范围" }; return number }
    private fun decimal(value: JsonElement, name: String): Double { val primitive = value.jsonPrimitive; val number = primitive.doubleOrNull; require(!primitive.isString && number != null && number.isFinite()) { "$name 无效" }; return number }
    private fun date(value: JsonElement, name: String) { decimal(value, name) }
    private fun zone(value: JsonElement, name: String) { require(runCatching { ZoneId.of(text(value, name)) }.isSuccess) { "$name 无效" } }
}
