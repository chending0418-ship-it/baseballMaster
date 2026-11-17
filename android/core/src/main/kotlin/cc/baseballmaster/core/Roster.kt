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

    fun parse(text: String): List<Player> {
        val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(rows.isNotEmpty() && rows.size <= 200) { "请填写 1–200 名球员，每行一位" }
        return rows.map { row ->
            val parts = row.split(',', '，').map { it.trim() }
            require(parts.size in 2..3) { "名单格式：中文名,英文名,背号；英文名可省略，多个背号用 / 分隔" }
            val chinese = parts[0]
            val english = if (parts.size == 3) parts[1] else ""
            val numbers = parts.last().split('/').map { it.trim() }
            Player(
                    UUID.randomUUID().toString(),
                    chinese.ifBlank { english },
                    numbers.first(),
                    englishName = english,
                    jerseyNumbers = numbers,
                    chineseName = chinese,
                )
                .also(::validate)
        }
    }
}
