package cc.baseballmaster.core

import java.util.UUID

object Roster {
    fun validate(player: Player) {
        require(player.name.isNotBlank() || player.englishName.isNotBlank()) { "请填写中文或英文姓名" }
        require(
            player.jerseyNumbers.isNotEmpty() &&
                player.jerseyNumbers.distinct().size == player.jerseyNumbers.size &&
                player.jerseyNumbers.all {
                    it == "00" ||
                        it.toIntOrNull()?.let { n -> n in 0..99 && n.toString() == it } == true
                }
        ) {
            "背号为 0–99 或 00，多个背号不能重复"
        }
        require(player.number == player.jerseyNumbers.first())
    }

    data class Row(val line: Int, val player: Player)

    fun parse(text: String): List<Player> = parseRows(text).map(Row::player)

    fun parseRows(text: String): List<Row> {
        require(text.length <= 64_000) { "名单过长，请分批输入" }
        val rows = text.lineSequence().mapIndexedNotNull { index, value ->
            value.trim().takeIf(String::isNotEmpty)?.let { index + 1 to it }
        }.toList()
        require(rows.isNotEmpty() && rows.size <= 200) { "请填写 1–200 名球员，每行一位" }
        return rows.map { (line, row) ->
            try {
                val parts = row.split(',', '，').map { it.trim() }
                require(parts.size in 2..3) { "名单格式：中文名,英文名,背号；英文名可省略，多个背号用 / 分隔" }
                val chinese = parts[0]
                val english = if (parts.size == 3) parts[1] else ""
                val numbers = parts.last().split('/').map { it.trim() }
                require(chinese.length <= 80 && english.length <= 80 && numbers.size <= 10) { "姓名过长或背号超过 10 个" }
                Row(line, Player(
                        UUID.randomUUID().toString(),
                        chinese.ifBlank { english },
                        numbers.first(),
                        englishName = english,
                        jerseyNumbers = numbers,
                        chineseName = chinese,
                    )
                    .also(::validate))
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 $line 行：${error.message}")
            }
        }
    }

    fun previewAppend(text: String, existingNames: List<String>): List<Row> {
        val used = existingNames.mapTo(mutableSetOf(), ::identityName)
        return parseRows(text).onEach { row ->
            val names = setOf(row.player.name, row.player.englishName).filter(String::isNotBlank).map(::identityName).distinct()
            require(names.none(used::contains)) { "第 ${row.line} 行：姓名与本批或已有档案重复，请核对原球员身份" }
            used.addAll(names)
        }
    }

    internal fun identityName(value: String) =
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
}
