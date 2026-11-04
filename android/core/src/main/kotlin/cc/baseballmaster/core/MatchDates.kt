package cc.baseballmaster.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Match dates are interpreted in their saved zone, never the viewing device's zone. */
object MatchDates {
    private val labelFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    // Matches iOS StoredGame.effectivePlayedAt, including old games with only scheduledAt.
    fun effectivePlayedAt(game: Game): Long? =
        game.playedAt ?: game.startedAt ?: game.scheduledAt ?: game.createdAt.takeIf { it > 0 }

    fun localPlayedAt(game: Game): ZonedDateTime? = effectivePlayedAt(game)?.let { timestamp ->
        runCatching { Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(game.playedTimeZoneID)) }.getOrNull()
    }

    fun year(game: Game): Int? = localPlayedAt(game)?.year

    fun label(game: Game): String {
        if (effectivePlayedAt(game) == null) return "比赛时间未记录"
        val local = localPlayedAt(game) ?: return "比赛时区未识别 · ${game.playedTimeZoneID}"
        return "${local.format(labelFormat)} · ${local.zone.id}"
    }
}
