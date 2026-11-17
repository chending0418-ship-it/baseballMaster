package cc.baseballmaster.scorekeeper

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val matchMinuteFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
private val matchClockFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun parseMatchLocalTime(text: String): LocalDateTime = LocalDateTime.parse(text.trim(), matchMinuteFormat).also {
    require(it.year in 1..9999) { "比赛年份超出可用范围。" }
}

internal fun parseMatchDate(text: String, zone: String, offset: String? = null): Long {
    val local = parseMatchLocalTime(text)
    val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
    val selected = offsets.singleOrNull() ?: offsets.firstOrNull { it.id == offset }
    requireNotNull(selected) {
        if (offsets.isEmpty()) "这个时间在所选时区不存在，请选择别的时间。" else "此时段出现两次，请选择对应的时差。"
    }
    return local.toInstant(selected).toEpochMilli()
}

internal fun formatMatchDate(date: Long, zone: String): String =
    Instant.ofEpochMilli(date).atZone(ZoneId.of(zone)).format(matchMinuteFormat)

internal fun matchDateIssue(value: Long, scheduled: Boolean, now: Long): String? = when {
    scheduled && value <= now -> "请选择晚于现在的开赛时间。"
    !scheduled && value > now -> "请选择已经发生的比赛时间。"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchDateTimeControls(
    text: String,
    zone: String,
    scheduled: Boolean,
    selectedOffset: String?,
    fieldTag: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onOffsetChange: (String) -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val timezone = runCatching { ZoneId.of(zone) }.getOrNull()
    val local = runCatching { parseMatchLocalTime(text) }.getOrNull()
    val fallback = LocalDateTime.now(timezone ?: ZoneId.systemDefault())
    val initial = local ?: fallback
    val offsets = if (timezone != null && local != null) timezone.rules.getValidOffsets(local) else null
    val issue = when {
        timezone == null -> "请填写有效的时区。"
        local == null -> "请选择日期与时间，或按 yyyy-MM-dd HH:mm 输入。"
        offsets.isNullOrEmpty() -> "这个时间在所选时区不存在，请选择别的时间。"
        offsets.size > 1 && offsets.none { it.id == selectedOffset } -> "此时段出现两次，请选择对应的时差。"
        else -> runCatching { parseMatchDate(text, zone, selectedOffset) }
            .fold({ matchDateIssue(it, scheduled, System.currentTimeMillis()) }, { "比赛时间超出可用范围。" })
    }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.rowGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpacing.rowGap)) {
            MatchDateChoice("日期", local?.toLocalDate()?.toString() ?: "选择日期", "$fieldTag-date", enabled, Modifier.weight(1f)) { picker = "date" }
            MatchDateChoice("时间", local?.format(matchClockFormat) ?: "选择时间", "$fieldTag-time", enabled, Modifier.weight(1f)) { picker = "time" }
        }
        if (offsets.orEmpty().size > 1) {
            Text("选择比赛时间对应的时差", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.rowGap)) {
                offsets.orEmpty().forEachIndexed { index, offset ->
                    FilterChip(selectedOffset == offset.id, { onOffsetChange(offset.id) },
                        label = { Text("UTC${offset.id}") }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("$fieldTag-offset-$index"))
                }
            }
        }
        if (manual) OutlinedTextField(text, onTextChange, enabled = enabled,
            label = { Text("yyyy-MM-dd HH:mm") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(fieldTag))
        TextButton({ manual = !manual }, enabled = enabled,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("$fieldTag-manual")) {
            Text(if (manual) "收起手动输入" else "手动输入")
        }
        if (issue != null) Text(issue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("$fieldTag-issue"))
    }

    if (picker == "date") {
        val today = fallback.toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = minOf(1900, initial.year, today.year)..maxOf(2100, initial.year, today.year),
            selectableDates = remember(today, scheduled) {
                object : SelectableDates {
                    override fun isSelectableYear(year: Int): Boolean =
                        if (scheduled) year >= today.year else year <= today.year

                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val day = Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate()
                        return if (scheduled) !day.isBefore(today) else !day.isAfter(today)
                    }
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picker = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("$fieldTag-date-dialog"),
            confirmButton = {
                TextButton(onClick = {
                    val day = Instant.ofEpochMilli(requireNotNull(state.selectedDateMillis)).atOffset(ZoneOffset.UTC).toLocalDate()
                    onTextChange(LocalDateTime.of(day, initial.toLocalTime()).format(matchMinuteFormat))
                    picker = null
                }, enabled = state.selectedDateMillis != null, modifier = Modifier.testTag("$fieldTag-date-confirm")) { Text("确定日期") }
            },
            dismissButton = { TextButton({ picker = null }) { Text("取消") } },
        ) {
            Box(Modifier.verticalScroll(rememberScrollState())) {
                DatePicker(state, modifier = Modifier.heightIn(max = 560.dp), showModeToggle = false)
            }
        }
    } else if (picker == "time") {
        val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { picker = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("$fieldTag-time-dialog"),
            title = { Text("选择时间") },
            text = { TimePicker(state, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    onTextChange(initial.withHour(state.hour).withMinute(state.minute).format(matchMinuteFormat))
                    picker = null
                }, modifier = Modifier.testTag("$fieldTag-time-confirm")) { Text("确定时间") }
            },
            dismissButton = { TextButton({ picker = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MatchDateChoice(label: String, value: String, tag: String, enabled: Boolean, modifier: Modifier, click: () -> Unit) {
    OutlinedButton(click, modifier.heightIn(min = 60.dp).testTag(tag), enabled = enabled,
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
