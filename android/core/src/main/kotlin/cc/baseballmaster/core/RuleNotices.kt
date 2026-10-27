package cc.baseballmaster.core

/** Advisories only. A limit never fabricates an out or automatically ends a match. */
fun Game.ruleNotices(at: Long): List<String> {
    if (isFinal || isScheduled || practice) return emptyList()
    val result = mutableListOf<String>()
    rules.regulationInnings?.let {
        if (inning == it) result += "当前为规定的最后一局" else if (inning > it) result += "比赛已进入延长局"
    }
    if (clockHasStarted)
        rules.timeLimitMinutes?.let { limit ->
            rules.timeWarningMinutes?.let { warning ->
                val remaining = limit - (elapsed(at) / 60000).toInt()
                if (remaining <= warning)
                    result += if (remaining > 0) "比赛时间剩余约 $remaining 分钟" else "已到 $limit 分钟时间限制"
            }
        }
    if (rules.mode != GameMode.coachPitch)
        for (player in home.players + away.players) {
            val pitches = pitching[player.id]?.pitches ?: 0
            rules.pitchLimit?.let { limit ->
                val remaining = limit - pitches
                if (remaining <= 0)
                    result += "#${player.number} ${player.name} 已达到 $limit 球限制（已投 $pitches 球）"
                else if (
                    player.id == pitcher.id &&
                        rules.pitchWarningRemaining?.let { remaining <= it } == true
                )
                    result += "#${player.number} ${player.name} 距投球限制还剩 $remaining 球"
            }
            rules.pitcherInningsLimit?.let { limit ->
                val used = pitcherInnings[player.id]?.size ?: 0
                if (used >= limit && used > 0)
                    result +=
                        "#${player.number} ${player.name} 已登板 $used / $limit 局；登板即计入，统计 IP 另按实际出局数计算"
            }
        }
    if (statisticsIncomplete || incompletePrefix) result += "部分过程或统计责任不完整，请核对记录"
    return result.distinct()
}
