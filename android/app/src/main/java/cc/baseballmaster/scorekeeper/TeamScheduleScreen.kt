package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.TeamScheduleFeed
import cc.baseballmaster.core.TeamScheduleNotice
import cc.baseballmaster.core.TeamSchedulePublication
import cc.baseballmaster.core.normalizeScheduleVenue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

internal sealed interface UpcomingSchedule {
    val key: String
    val startsAt: Long

    data class Local(val game: Game, val publication: TeamSchedulePublication?, val notice: TeamScheduleNotice?) : UpcomingSchedule {
        override val key = "match-${game.id}"
        override val startsAt = notice?.startsAt ?: game.scheduledAt ?: Long.MAX_VALUE
    }

    data class Member(val notice: TeamScheduleNotice) : UpcomingSchedule {
        override val key = "team-schedule-${notice.key}"
        override val startsAt = notice.startsAt
    }
}

internal fun upcomingSchedules(games: List<Game>, feed: TeamScheduleFeed, now: Long): List<UpcomingSchedule> {
    val localIDs = games.mapTo(hashSetOf()) { it.id.lowercase() }
    val linkedKeys = feed.publications.values.filter { it.sourceID.lowercase() in localIDs }
        .mapTo(hashSetOf()) { "${it.teamID.lowercase()}/${it.sourceID.lowercase()}" }
    val bindings = feed.bindings.associate { it.localTeamID.lowercase() to it.cloudTeamID.lowercase() }
    val localSourceKeys = games.mapNotNullTo(hashSetOf()) { game ->
        game.ourTeamID?.lowercase()?.let { own -> "${bindings[own] ?: own}/${game.id.lowercase()}" }
    }
    val allowed = feed.memberships.mapTo(hashSetOf()) { it.id.lowercase() }
    val notices = feed.notices.associateBy(TeamScheduleNotice::key)
    val local = games.filter { it.isScheduled && !it.isFinal }
        .map { game ->
            val publication = feed.publications[game.id.lowercase()]
            UpcomingSchedule.Local(game, publication, latestLocalScheduleNotice(game, feed, publication, notices))
        }
    val reviewNotices = feed.remotePublications.values.mapNotNull {
        it.notice?.takeIf { _ -> it.pending != null || it.needsReview }
    }
    val remote = (feed.notices + reviewNotices).filter {
        it.startsAt >= now && it.teamID.lowercase() in allowed && it.key !in linkedKeys && it.key !in localSourceKeys
    }.distinctBy { it.key.lowercase() }.map { UpcomingSchedule.Member(it) }
    return (local + remote).sortedWith(compareBy<UpcomingSchedule> { it.startsAt }.thenBy { it.key })
}

internal fun latestLocalScheduleNotice(
    game: Game,
    feed: TeamScheduleFeed,
    publication: TeamSchedulePublication? = feed.publications[game.id.lowercase()],
    notices: Map<String, TeamScheduleNotice> = feed.notices.associateBy(TeamScheduleNotice::key),
): TeamScheduleNotice? {
    val own = game.ourTeamID ?: return null
    val teamID = publication?.teamID ?: feed.bindings.firstOrNull { it.localTeamID.equals(own, true) }?.cloudTeamID ?: own
    val current = notices["${teamID.lowercase()}/${game.id.lowercase()}"]
    val previous = publication?.notice
    return if (current != null && current.revision >= (previous?.revision ?: 0)) current else previous
}

private val memberScheduleDateFormat = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.CHINA)

internal fun memberScheduleDate(startsAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(startsAt).atZone(zone).format(memberScheduleDateFormat)

@Composable
internal fun TeamScheduleNoticeRow(notice: TeamScheduleNotice, onManage: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val interaction = if (onManage != null) Modifier.clickable(role = Role.Button, onClick = onManage) else Modifier
    Surface(
        Modifier.fillMaxWidth().then(interaction).semantics(mergeDescendants = true) {}
            .testTag("team-schedule-${notice.id.lowercase()}"),
        color = colors.surface, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(UiSpacing.cardInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.DateRange, null, Modifier.size(22.dp), tint = colors.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${notice.away} 对 ${notice.home}", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Text(memberScheduleDate(notice.startsAt), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                if (notice.venue.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.LocationOn, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                        Text(notice.venue, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Text(if (notice.cancelled) "已取消" else "球队赛程",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (notice.cancelled) colors.error else colors.primary)
            if (onManage != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

internal fun validScheduleVenue(value: String): Boolean =
    runCatching { normalizeScheduleVenue(value) }.isSuccess

@Composable
internal fun ScheduleVenueInput(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    tag: String,
) {
    val valid = validScheduleVenue(value)
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().testTag(tag),
        label = { Text("场地（选填）") },
        enabled = enabled, singleLine = true, isError = !valid,
        supportingText = if (valid) null else { { Text("请输入单行场地，最多 120 个字符。") } },
    )
}

@Composable
internal fun TeamScheduleControls(game: Game, model: ScorekeeperViewModel) {
    val publication = model.schedulePublication(game)
    val notice = model.scheduleNotice(game)
    val fromAnotherDevice = publication == null && notice != null
    val remote = notice?.takeIf { fromAnotherDevice }
    val managedRemote = remote?.let { model.scheduleFeed.remotePublications[it.key] }
    val target = model.scheduleFeed.teamFor(game.ourTeamID)
    val authorized = if (remote != null) model.canManageRemoteSchedule(remote)
        else target != null && (publication == null || target.id.equals(publication.teamID, true))
    val identity = remote?.key ?: game.id
    val issue = model.scheduleIssue(identity)
    if (!authorized && publication == null && notice == null && issue == null) return
    SchedulePublicationActions(
        identity = identity,
        startsAt = notice?.startsAt ?: game.scheduledAt ?: System.currentTimeMillis(),
        zone = game.playedTimeZoneID,
        venue = notice?.venue ?: game.scheduleVenue.orEmpty(),
        revision = notice?.revision ?: 0L,
        requestRevision = if (remote != null) remote.revision else publication?.revision ?: 0L,
        published = notice != null,
        cancelled = notice?.cancelled == true,
        pending = (publication ?: managedRemote)?.pending != null,
        needsReview = (publication ?: managedRemote)?.needsReview == true,
        authorized = authorized,
        busy = model.busy || model.scheduleSubmitting,
        submitting = model.scheduleSubmitting,
        issue = issue,
        save = { time, location, cancel, expected ->
            if (remote != null) model.updateRemoteSchedule(remote, time, location, cancel)
            else model.updateTeamSchedule(game, time, location, cancel, expected)
        },
        retry = { if (remote != null) model.retryRemoteSchedule(remote) else model.retryTeamSchedule(game) },
    )
}

@Composable
internal fun RemoteTeamScheduleScreen(notice: TeamScheduleNotice, model: ScorekeeperViewModel, close: () -> Unit) {
    val publication = model.scheduleFeed.remotePublications[notice.key]
    val latest = publication?.notice?.takeIf { it.revision >= notice.revision } ?: notice
    val authorized = model.canManageRemoteSchedule(notice)
    val zone = TimeZone.getDefault().id
    ProductPage(title = "球队赛程", onBack = close, busy = model.scheduleSubmitting, modal = true) {
        CardBlock {
            Text("${latest.away} 对 ${latest.home}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(formatMatchDate(latest.startsAt, zone), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("team-schedule-managed-date"))
            if (latest.venue.isNotBlank()) Text(latest.venue, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("team-schedule-managed-venue"))
        }
        SchedulePublicationActions(
            identity = notice.key,
            startsAt = latest.startsAt,
            zone = zone,
            venue = latest.venue,
            revision = latest.revision,
            published = true,
            cancelled = latest.cancelled,
            pending = publication?.pending != null,
            needsReview = publication?.needsReview == true,
            authorized = authorized,
            busy = model.busy || model.scheduleSubmitting,
            submitting = model.scheduleSubmitting,
            issue = model.scheduleIssue(notice.key),
            save = { time, location, cancel, _ -> model.updateRemoteSchedule(latest, time, location, cancel) },
            retry = { model.retryRemoteSchedule(notice) },
        )
    }
}

@Composable
private fun SchedulePublicationActions(
    identity: String,
    startsAt: Long,
    zone: String,
    venue: String,
    revision: Long,
    requestRevision: Long = revision,
    published: Boolean,
    cancelled: Boolean,
    pending: Boolean,
    needsReview: Boolean,
    authorized: Boolean,
    busy: Boolean,
    submitting: Boolean,
    issue: String?,
    save: (Long, String, Boolean, Long) -> Unit,
    retry: () -> Unit,
) {
    var editRevision by rememberSaveable(identity) { mutableStateOf<Long?>(null) }
    var editRequestRevision by rememberSaveable(identity) { mutableLongStateOf(requestRevision) }
    var editStartsAt by rememberSaveable(identity) { mutableLongStateOf(startsAt) }
    var editVenue by rememberSaveable(identity) { mutableStateOf(venue) }
    var cancelRevision by rememberSaveable(identity) { mutableStateOf<Long?>(null) }
    var cancelRequestRevision by rememberSaveable(identity) { mutableLongStateOf(requestRevision) }
    val status = when {
        pending -> "球队发布结果待确认"
        needsReview -> "需要核对最新球队赛程"
        cancelled -> "球队赛程已取消"
        published -> "赛程已发布给球队成员"
        else -> "赛程仅自己可见"
    }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.rowGap)) {
        CardBlock {
            Text(status, Modifier.testTag("team-schedule-status"), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            if (submitting) Text("正在核对球队赛程…", style = MaterialTheme.typography.bodySmall)
            if (issue != null) Text(issue, Modifier.testTag("team-schedule-issue"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!authorized) Text("当前设备无权更新球队赛程。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (authorized && pending) {
            ProductActionRow("重试发布", icon = Icons.Outlined.DateRange, tag = "team-schedule-retry", enabled = !busy) {
                retry()
            }
        } else if (authorized) {
            val label = when {
                needsReview -> "核对最新安排"
                published -> "更新球队赛程"
                else -> "发布到球队赛程"
            }
            ProductActionRow(label, icon = Icons.Outlined.DateRange, tag = "team-schedule-edit", enabled = !busy) {
                if (needsReview) {
                    save(startsAt, venue, false, requestRevision)
                } else {
                    editStartsAt = startsAt
                    editVenue = venue
                    editRevision = revision
                    editRequestRevision = requestRevision
                }
            }
            if (published && !cancelled && !needsReview) {
                TextButton(onClick = { cancelRevision = revision; cancelRequestRevision = requestRevision }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("team-schedule-cancel")) {
                    Text("取消球队赛程", color = if (busy) LocalContentColor.current else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    val openedRevision = editRevision
    if (openedRevision != null && authorized && !pending && !needsReview) {
        TeamScheduleEditor(
            startsAt = editStartsAt,
            zone = zone,
            initialVenue = editVenue,
            busy = busy,
            changed = openedRevision != revision,
            close = { editRevision = null },
        ) { startsAt, venue ->
            editRevision = null
            save(startsAt, venue, false, editRequestRevision)
        }
    }
    val proposedCancellation = cancelRevision
    if (proposedCancellation != null && authorized && published && !pending && !needsReview) {
        val changed = proposedCancellation != revision
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { if (!busy) cancelRevision = null },
            title = { Text("取消球队赛程？") },
            text = { Text(if (changed) "球队赛程已变化，请返回核对最新安排。"
                else "成员将在比赛首页看到这场比赛已取消。") },
            confirmButton = {
                TextButton(
                    {
                        cancelRevision = null
                        save(startsAt, venue, true, cancelRequestRevision)
                    },
                    enabled = !busy && !changed && !cancelled,
                    modifier = Modifier.testTag("team-schedule-confirm-cancel"),
                ) { Text("确认取消", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ cancelRevision = null }, enabled = !busy) { Text("返回") } },
        )
    }
}

@Composable
private fun TeamScheduleEditor(
    startsAt: Long,
    zone: String,
    initialVenue: String,
    busy: Boolean,
    changed: Boolean,
    close: () -> Unit,
    save: (Long, String) -> Unit,
) {
    var date by rememberSaveable(startsAt, zone) { mutableStateOf(formatMatchDate(startsAt, zone)) }
    var offset by rememberSaveable(startsAt, zone) {
        mutableStateOf<String?>(Instant.ofEpochMilli(startsAt).atZone(ZoneId.of(zone)).offset.id)
    }
    var venue by rememberSaveable(startsAt) { mutableStateOf(initialVenue) }
    val proposed = runCatching { parseMatchDate(date, zone, offset) }.getOrNull()
        ?.takeIf { it > System.currentTimeMillis() }
    ProductPage(
        "球队赛程", busy = busy, onBack = close, modal = true,
        actionLabel = "发布", actionTag = "team-schedule-commit",
        actionEnabled = !changed && proposed != null && validScheduleVenue(venue),
        onAction = { proposed?.let { save(it, venue.trim()) } },
    ) {
        if (changed) Text("球队赛程已变化，请返回核对最新安排。",
            Modifier.testTag("team-schedule-edit-conflict"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("开赛时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        MatchDateTimeControls(date, zone, true, offset, "team-schedule-edit-date", !busy,
            onTextChange = { value -> if (date != value) { date = value; offset = null } },
            onOffsetChange = { offset = it },
        )
        ScheduleVenueInput(venue, { venue = it }, !busy, "team-schedule-edit-venue")
    }
}
