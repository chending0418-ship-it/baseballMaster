package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import kotlinx.serialization.Serializable

@Serializable
internal data class RuleDraft(
    val innings: String = "6",
    val balls: String = "0",
    val strikes: String = "0",
    val coachLimit: String = "6",
    val timeMinutes: String = "",
    val inningLimit: String = "",
    val runLimit: String = "",
    val pitchLimit: String = "",
    val pitcherInnings: String = "",
    val timed: Boolean = false,
    val extraFoul: Boolean = true,
    val dh: Boolean = false,
    val twoWay: Boolean = false,
    val method: String = "traditional",
    val timeWarning: String = "",
    val pitchWarning: String = "",
) {
    fun build(mode: GameMode): Rules {
        fun n(label: String, value: String, range: IntRange, optional: Boolean = false): Int? {
            if (optional && value.isBlank()) return null
            return value.toIntOrNull()?.takeIf { it in range }
                ?: error("$label 请填写 ${range.first}–${range.last}")
        }
        require(!timed || timeMinutes.isNotBlank()) { "时间赛请填写比赛时限" }
        return Rules(
            mode = mode,
            initialBalls = n("初始坏球", balls, 0..3)!!,
            initialStrikes = n("初始好球", strikes, 0..2)!!,
            coachPitchLimit = n("规定投球数", coachLimit, 1..20)!!,
            oneExtraFoul = extraFoul,
            innings = n("局数", innings, 1..99)!!,
            timed = timed,
            timeLimitMinutes = n("比赛时限", timeMinutes, 1..600, true),
            inningsLimit = n("时间赛局数上限", inningLimit, 1..99, true),
            halfRunLimit = n("半局得分上限", runLimit, 1..99, true),
            pitchLimit = n("投手球数上限", pitchLimit, 1..1000, true),
            pitcherInningsLimit = n("投手局数上限", pitcherInnings, 1..99, true),
            timeWarningMinutes = n("时间提醒", timeWarning, 1..600, true),
            pitchWarningRemaining = n("球数提醒", pitchWarning, 1..1000, true),
            recordingMethod = method,
            designatedHitter = mode == GameMode.standard && dh,
            twoWayPlayer = mode == GameMode.standard && dh && twoWay,
        )
    }

    companion object {
        fun from(r: Rules) =
            RuleDraft(
                r.innings.toString(),
                r.initialBalls.toString(),
                r.initialStrikes.toString(),
                r.coachPitchLimit.toString(),
                r.timeLimitMinutes?.toString().orEmpty(),
                r.inningsLimit?.toString().orEmpty(),
                r.halfRunLimit?.toString().orEmpty(),
                r.pitchLimit?.toString().orEmpty(),
                r.pitcherInningsLimit?.toString().orEmpty(),
                r.timed,
                r.oneExtraFoul,
                r.designatedHitter,
                r.twoWayPlayer,
                r.recordingMethod,
                r.timeWarningMinutes?.toString().orEmpty(),
                r.pitchWarningRemaining?.toString().orEmpty(),
            )
    }
}

@Composable
internal fun RulesForm(initial: Rules, change: (Rules?) -> Unit) {
    var json by
        rememberSaveable(initial.mode) {
            mutableStateOf(GameJson.encodeToString(RuleDraft.from(initial)))
        }
    val draft = remember(json) { GameJson.decodeFromString<RuleDraft>(json) }
    val result = remember(json, initial.mode) { runCatching { draft.build(initial.mode) } }
    SideEffect { change(result.getOrNull()) }
    fun update(f: (RuleDraft) -> RuleDraft) {
        json = GameJson.encodeToString(f(GameJson.decodeFromString(json)))
    }
    fun value(v: String) = v.filter { it.isDigit() }.take(4)
    Choices("记分方式", mapOf("traditional" to "基础版", "situation" to "Pro 版"), draft.method) { v ->
        update { it.copy(method = v) }
    }
    Choices(
        "赛制",
        mapOf("innings" to "局数赛", "timed" to "时间赛"),
        if (draft.timed) "timed" else "innings",
    ) { v ->
        update { it.copy(timed = v == "timed") }
    }
    OutlinedTextField(
        draft.innings,
        { v -> update { it.copy(innings = value(v)) } },
        label = { Text("规定局数") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.timed) {
        OutlinedTextField(
            draft.timeMinutes,
            { v -> update { it.copy(timeMinutes = value(v)) } },
            label = { Text("比赛时限（分钟）") },
            singleLine = true,
        )
        OutlinedTextField(
            draft.inningLimit,
            { v -> update { it.copy(inningLimit = value(v)) } },
            label = { Text("局数上限（留空不限制）") },
            singleLine = true,
        )
    }
    if (draft.timed)
        OutlinedTextField(
            draft.timeWarning,
            { v -> update { it.copy(timeWarning = value(v)) } },
            label = { Text("剩余时间提醒（分钟，留空关闭）") },
            singleLine = true,
        )
    if (initial.mode == GameMode.slowPitch) {
        Text("10 人守备（含 Free），可在阵容中增加打者。", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft.balls,
                { v -> update { it.copy(balls = value(v)) } },
                label = { Text("初始坏球") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                draft.strikes,
                { v -> update { it.copy(strikes = value(v)) } },
                label = { Text("初始好球") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Choices(
            "两好球后界外",
            mapOf("extra" to "额外一次界外", "out" to "直接出局"),
            if (draft.extraFoul) "extra" else "out",
        ) { v ->
            update { it.copy(extraFoul = v == "extra") }
        }
    }
    if (initial.mode == GameMode.coachPitch)
        OutlinedTextField(
            draft.coachLimit,
            { v -> update { it.copy(coachLimit = value(v)) } },
            label = { Text("每打席规定投球数") },
            singleLine = true,
        )
    OutlinedTextField(
        draft.runLimit,
        { v -> update { it.copy(runLimit = value(v)) } },
        label = { Text("半局得分上限（留空不限制）") },
        singleLine = true,
    )
    if (initial.mode != GameMode.coachPitch) {
        OutlinedTextField(
            draft.pitchLimit,
            { v -> update { it.copy(pitchLimit = value(v)) } },
            label = { Text("投手球数上限（留空不限制）") },
            singleLine = true,
        )
        OutlinedTextField(
            draft.pitchWarning,
            { v -> update { it.copy(pitchWarning = value(v)) } },
            label = { Text("剩余球数提醒（留空关闭）") },
            singleLine = true,
        )
        OutlinedTextField(
            draft.pitcherInnings,
            { v -> update { it.copy(pitcherInnings = value(v)) } },
            label = { Text("投手局数上限（留空不限制）") },
            singleLine = true,
        )
    }
    if (initial.mode == GameMode.standard)
        Row {
            Checkbox(draft.dh, { v -> update { it.copy(dh = v) } })
            Text("启用 DH", Modifier.padding(top = 13.dp))
        }
    if (initial.mode == GameMode.standard && draft.dh)
        Row {
            Checkbox(draft.twoWay, { v -> update { it.copy(twoWay = v) } })
            Text("允许二刀流投打身份", Modifier.padding(top = 13.dp))
        }
    if (result.isFailure)
        Text(
            result.exceptionOrNull()?.message.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
}

internal fun parseMatchDate(text: String, zone: String): Long =
    java.time.LocalDateTime.parse(
            text.trim(),
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT),
        )
        .atZone(java.time.ZoneId.of(zone))
        .toInstant()
        .toEpochMilli()

internal fun formatMatchDate(date: Long, zone: String): String =
    java.time.Instant.ofEpochMilli(date)
        .atZone(java.time.ZoneId.of(zone))
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
internal fun MatchTimeEditor(
    game: Game,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (Command) -> Unit,
) {
    var text by rememberSaveable {
        mutableStateOf(
            formatMatchDate(
                game.playedAt ?: game.startedAt ?: game.scheduledAt ?: System.currentTimeMillis(),
                game.playedTimeZoneID,
            )
        )
    }
    var zone by rememberSaveable { mutableStateOf(game.playedTimeZoneID) }
    var reason by rememberSaveable { mutableStateOf("") }
    var scheduled by rememberSaveable { mutableStateOf(game.isScheduled) }
    val date = runCatching { parseMatchDate(text, zone) }.getOrNull()
    FormPage(
        "比赛时间",
        busy,
        date != null && (scheduled || reason.isNotBlank()),
        "save-match-time",
        "保存",
        dismiss,
        {
            save(
                Command(
                    if (scheduled) "schedule" else "date",
                    dateMillis = date,
                    timeZoneID = zone,
                    text = reason,
                )
            )
        },
    ) {
        Text("比赛时间与记分计时分别保留；更正时间不改变比赛统计。", fontSize = 12.sp)
        if (!game.openingPitchRecorded)
            Choices(
                "时间用途",
                mapOf("played" to "比赛实际时间", "schedule" to "预告赛程"),
                if (scheduled) "schedule" else "played",
            ) {
                scheduled = it == "schedule"
            }
        OutlinedTextField(
            text,
            { text = it },
            label = { Text("日期时间 yyyy-MM-dd HH:mm") },modifier=Modifier.testTag("match-time"),
            singleLine = true,
        )
        OutlinedTextField(zone, { zone = it }, label = { Text("时区") },modifier=Modifier.testTag("match-zone"), singleLine = true)
        if (!scheduled) OutlinedTextField(reason, { reason = it }, label = { Text("更正原因") },modifier=Modifier.testTag("match-time-reason"))
        if (date == null) Text("请核对日期格式与时区。", color = MaterialTheme.colorScheme.error)
    }
}
