package cc.baseballmaster.core

import java.util.UUID

data class RosterAppendRow(val lineNumber: Int, val player: Player)
data class RosterAppendNote(val lineNumber: Int, val message: String)

data class RosterAppendPreview(
    val rows: List<RosterAppendRow>,
    val issues: List<RosterAppendNote>,
    val warnings: List<RosterAppendNote>,
) {
    fun confirmedPlayers(confirmedWarnings: Boolean): List<Player> {
        require(issues.isEmpty() && rows.isNotEmpty()) { issues.firstOrNull()?.message ?: "请先输入至少一名球员" }
        require(warnings.isEmpty() || confirmedWarnings) { "请确认相同姓名和背号的球员是不同人" }
        return rows.map { it.player }
    }
}

/** Local append is independent of new-team creation and of any shared-roster request. */
object RosterAppend {
    private data class Fields(val chinese: String, val english: String, val numbers: List<String>) {
        val displayName get() = chinese.ifBlank { english }
        val label get() = displayName to numbers.firstOrNull().orEmpty()
    }

    fun preview(input: String, existing: List<Player>): RosterAppendPreview {
        val rows = mutableListOf<RosterAppendRow>()
        val issues = mutableListOf<RosterAppendNote>()
        val warnings = mutableListOf<RosterAppendNote>()
        if (input.length > 64_000) {
            return RosterAppendPreview(emptyList(), listOf(RosterAppendNote(1, "名单过长，请分批输入")), emptyList())
        }
        val existingFields = existing.map { player ->
            Fields((player.chineseName ?: player.name).trim(), player.englishName.trim(), player.jerseyNumbers)
        }
        val exactExisting = existingFields.toSet()
        val existingLabels = existingFields.mapTo(mutableSetOf()) { it.label }
        val seen = mutableMapOf<Fields, Int>()
        val labels = mutableMapOf<Pair<String, String>, Int>()
        var firstContent = true
        var lines = 0
        for ((index, raw) in input.removePrefix("\uFEFF").lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val number = index + 1
            val columns = line.split(',', '，').map(String::trim)
            if (firstContent) {
                firstContent = false
                if (columns == listOf("姓名", "英文名", "背号")) continue
            }
            if (++lines > 200) {
                issues += RosterAppendNote(number, "每批最多追加 200 名球员，请分批输入")
                break
            }
            if (columns.size !in 2..3) {
                issues += RosterAppendNote(number, "请使用姓名,英文名,背号格式，或省略英文名一列")
                continue
            }
            val fields = Fields(columns.first(), if (columns.size == 3) columns[1] else "", columns.last().split('/').map(String::trim))
            val player = Player(UUID.randomUUID().toString(), fields.displayName, fields.numbers.first(),
                englishName = fields.english, jerseyNumbers = fields.numbers, chineseName = fields.chinese)
            val invalid = runCatching { Roster.validate(player) }.exceptionOrNull()
            if (invalid != null) {
                issues += RosterAppendNote(number, invalid.message ?: "请检查姓名和背号")
                continue
            }
            val previous = seen.putIfAbsent(fields, number)
            if (previous != null || fields in exactExisting) {
                val duplicate = if (previous != null) "与第 $previous 行" else "与已有名单"
                issues += RosterAppendNote(number, "${duplicate}完全重复，已有球员会保留")
                continue
            }
            val sameLabel = labels.putIfAbsent(fields.label, number)
            if (sameLabel != null || fields.label in existingLabels) {
                val duplicate = if (sameLabel != null) "与第 $sameLabel 行" else "与已有球员"
                warnings += RosterAppendNote(number, "${duplicate}姓名和背号相同，如为不同人请确认")
            }
            rows += RosterAppendRow(number, player)
        }
        if (rows.isEmpty() && issues.isEmpty()) issues += RosterAppendNote(1, "请先输入至少一名球员")
        return RosterAppendPreview(rows, issues, warnings)
    }
}
