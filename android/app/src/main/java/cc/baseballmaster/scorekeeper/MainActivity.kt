package cc.baseballmaster.scorekeeper

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.baseballmaster.core.*
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val screenModel get() = ViewModelProvider(this)[ScorekeeperViewModel::class.java]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveServiceIntent(intent)
        setContent { BaseballTheme(screenModel.appAppearance) { PortraitViewport("portrait-app") { AndroidLaunchContainer(screenModel) {ScorekeeperApp(screenModel)} } } }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);receiveServiceIntent(intent)}
    override fun onStart() {super.onStart();screenModel.setLiveForeground(true)}
    override fun onStop() {screenModel.setLiveForeground(false);super.onStop()}
    private fun receiveServiceIntent(source: Intent) {
        val value=source.dataString;source.data=null
        if(source.action==Intent.ACTION_VIEW && value!=null) screenModel.receiveTeamLink(value)
    }
}

class ScorekeeperViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val database = LocalDatabase.open(application)
    private val repository = GameRepository(database)
    internal val live by lazy {AndroidLive(application,repository,viewModelScope) {recoveryRequired || loading}}
    /** Recording task snapshots contain the full journal and must never enter the Activity Bundle. */
    internal var scoringTaskSource by mutableStateOf<Game?>(null)
    private var sharedPresence: kotlinx.coroutines.Job?=null
    internal fun setLiveForeground(active: Boolean) {
        live.setForeground(active);sharedPresence?.cancel();sharedPresence=null
        if(active) sharedPresence=viewModelScope.launch {
            while(true) {
                if(!loading && !recoveryRequired) runCatching {sharing.maintainSharedLive()}.onFailure {if(it is kotlinx.coroutines.CancellationException) throw it}
                kotlinx.coroutines.delay(10_000)
            }
        }
    }
    internal val sharing by lazy {
        val testSession = if (BuildConfig.DEBUG) androidTeamDirectoryTestOverride?.invoke(application) else null
        AndroidTeamSharing(repository, testSession ?: androidTeamDirectory(application, "https://baseballmaster.cc/api/v3"))
    }
    internal var scheduleFeed by mutableStateOf(TeamScheduleFeed())
        private set
    internal var scheduleRefreshing by mutableStateOf(false)
        private set
    internal var scheduleSubmitting by mutableStateOf(false)
        private set
    private var scheduleProblem by mutableStateOf<Pair<String, String>?>(null)
    private var scheduleReadGeneration = 0L
    private var lastScheduleReadAt: Long? = null
    private var scheduleRefreshRequested = false
    private val scheduleReadMutex = Mutex()

    internal fun schedulePublication(game: Game) = scheduleFeed.publications[game.id.lowercase()]
    internal fun scheduleNotice(game: Game) = latestLocalScheduleNotice(game, scheduleFeed)
    internal fun scheduleIssue(key: String) = scheduleProblem?.takeIf { it.first == key }?.second
    internal fun canManageRemoteSchedule(notice: TeamScheduleNotice) =
        scheduleFeed.memberships.any { it.id.equals(notice.teamID, true) && it.role == TeamRole.admin }

    private suspend fun readLocalScheduleState(): Boolean {
        val cached = sharing.scheduleState()
        val memberships = cached.memberships.mapTo(hashSetOf()) { it.id.lowercase() }
        val old = scheduleFeed
        val next = cached.copy(
            notices = old.notices.filter { it.teamID.lowercase() in memberships },
            message = old.message?.takeIf { memberships.isNotEmpty() },
        )
        scheduleFeed = next
        val authorityChanged = old.memberships != next.memberships || old.bindings != next.bindings
        if (authorityChanged) lastScheduleReadAt = null
        return authorityChanged
    }

    internal fun refreshTeamSchedules(force: Boolean = false) {
        if (recoveryRequired) return
        if (scheduleRefreshing) {
            scheduleRefreshRequested = scheduleRefreshRequested || force
            return
        }
        scheduleRefreshing = true
        var generation = scheduleReadGeneration
        viewModelScope.launch {
            try {
                val shouldRead = scheduleReadMutex.withLock {
                    readLocalScheduleState()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (scheduleFeed.memberships.isEmpty() || !force && lastScheduleReadAt?.let { now - it < 60_000L } == true) return@withLock false
                    generation = scheduleReadGeneration
                    lastScheduleReadAt = now
                    true
                }
                if (shouldRead) {
                    val updated = sharing.teamSchedules()
                    if (generation == scheduleReadGeneration) scheduleFeed = updated
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (generation == scheduleReadGeneration) {
                    scheduleFeed = scheduleFeed.copy(notices = emptyList(),
                        message = "球队赛程暂时无法读取，请刷新重试。")
                }
            } finally {
                scheduleRefreshing = false
                if (scheduleRefreshRequested) {
                    scheduleRefreshRequested = false
                    refreshTeamSchedules(force = true)
                }
            }
        }
    }

    internal fun updateTeamSchedule(
        game: Game, startsAt: Long, venue: String, cancelled: Boolean = false, expectedRevision: Long? = null,
    ) = submitTeamSchedule(game.id) { sharing.publishSchedule(game.id, startsAt, venue, cancelled, expectedRevision) }

    internal fun retryTeamSchedule(game: Game) =
        submitTeamSchedule(game.id) { sharing.retrySchedulePublication(game.id) }

    internal fun updateRemoteSchedule(notice: TeamScheduleNotice, startsAt: Long, venue: String, cancelled: Boolean = false) =
        submitTeamSchedule(notice.key) { sharing.updateRemoteSchedule(notice, startsAt, venue, cancelled) }

    internal fun retryRemoteSchedule(notice: TeamScheduleNotice) =
        submitTeamSchedule(notice.key) { sharing.retrySchedulePublication(notice.id, notice.teamID) }

    private fun submitTeamSchedule(key: String, action: suspend () -> TeamSchedulePublication) {
        if (scheduleSubmitting || recoveryRequired) return
        scheduleSubmitting = true
        scheduleProblem = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (fault: TeamServiceFault) {
                val text = if (fault.code == "schedule_changed") "球队赛程已变化，请核对最新安排后再提交。"
                    else fault.message ?: "球队发布尚未确认，请重试。"
                scheduleProblem = key to text
            } catch (failure: Exception) {
                val text = if (failure is IllegalArgumentException || failure is IllegalStateException) failure.message else null
                scheduleProblem = key to (text ?: "球队发布尚未确认。请联网重试，原安排仍保留。")
            } finally {
                try {
                    scheduleReadMutex.withLock { readLocalScheduleState() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    scheduleProblem = key to "赛程状态暂时无法读取，请刷新核对。"
                } finally {
                    scheduleSubmitting = false
                }
                refreshTeamSchedules(force = true)
            }
        }
    }
    internal val launchRepository by lazy {AndroidLaunchScreenRepository.standard(application)}
    internal var launchDismissed by mutableStateOf(false)
        private set
    private var launchRefreshStarted=false
    private var launchEnd: Long?=null
    internal fun dismissLaunch() {launchDismissed=true}
    internal fun launchDeadline(duration: Int): Long = launchEnd ?: (android.os.SystemClock.elapsedRealtime()+duration).also {launchEnd=it}
    internal fun refreshLaunchOnce() {
        if(launchRefreshStarted) return
        launchRefreshStarted=true
        viewModelScope.launch(Dispatchers.IO) {launchRepository.refresh()}
    }
    private val incomingLinkMutex=Mutex()
    internal var incomingTeamLink by mutableStateOf<IncomingTeamLink?>(null)
        private set
    internal var incomingLinkVisible by mutableStateOf(false)
        private set
    internal fun resumeTeamLink() {incomingLinkVisible=true}
    internal fun receiveTeamLink(value: String) {
        val parsed=runCatching {IncomingTeamLink.parse(value)}.getOrElse {error="邀请或配对链接无效，请重新复制完整链接。";return}
        incomingLinkVisible=true
        viewModelScope.launch {incomingLinkMutex.withLock {
            try {sharing.retainIncomingLink(parsed);incomingTeamLink=sharing.state().incomingLinks.firstOrNull();incomingLinkVisible=true}
            catch(_: Exception) {error="链接尚未保存，请保留原链接，处理已有待核对链接后重试。"}
        }}
    }
    internal fun closeTeamLink(id: String) {
        viewModelScope.launch {incomingLinkMutex.withLock {
            try {sharing.consumeIncomingLink(id);incomingTeamLink=sharing.state().incomingLinks.firstOrNull();incomingLinkVisible=false}
            catch(_: Exception) {error="链接状态尚未保存，原链接仍保留，请重试。"}
        }}
    }
    private val settings = application.getSharedPreferences("baseballmaster-settings", 0)
    internal var websiteRelease by mutableStateOf<WebsiteAndroidRelease?>(null)
        private set
    internal var checkingUpdate by mutableStateOf(false)
        private set
    internal var updateCheckFailed by mutableStateOf(false)
        private set
    private var lastAutomaticUpdateCheck: Long? = null
    private var dismissedUpdateBuild by mutableIntStateOf(settings.getInt("dismissedUpdateBuild", 0))
    internal val advertisedUpdate get() = websiteRelease?.takeIf {
        it.build > maxOf(BuildConfig.VERSION_CODE, dismissedUpdateBuild)
    }

    internal fun dismissUpdate(build: Int) {
        dismissedUpdateBuild = build
        settings.edit().putInt("dismissedUpdateBuild", build).apply()
    }

    internal fun checkForUpdate(manual: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (checkingUpdate || !manual && lastAutomaticUpdateCheck?.let { now - it < 60 * 60 * 1000L } == true) return
        lastAutomaticUpdateCheck = now
        checkingUpdate = true
        updateCheckFailed = false
        viewModelScope.launch {
            try {
                websiteRelease = withContext(Dispatchers.IO) { WebsiteAndroidRelease.read() }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                websiteRelease = null
                updateCheckFailed = true
            } finally {
                checkingUpdate = false
            }
        }
    }

    internal var appAppearance by mutableStateOf(settings.getString("appAppearance","system")?.takeIf {it in setOf("system","light","dark")} ?: "system")
        private set
    internal var showPracticeGuidance by mutableStateOf(settings.getBoolean("showPracticeGuidance",true))
        private set
    internal var showRuleExamples by mutableStateOf(settings.getBoolean("showRuleExamples",true))
        private set
    internal var defaultRecordingMethod by mutableStateOf(
        settings.getString("defaultRecordingMethod", null)?.takeIf { it in RecordingMethodLabels } ?: "traditional",
    )
        private set

    internal fun setDefaultRecordingMethod(method: String) {
        require(method in RecordingMethodLabels)
        if (defaultRecordingMethod == method) return
        settings.edit().putString("defaultRecordingMethod", method).apply()
        defaultRecordingMethod = method
    }

    internal fun setAppearance(value: String) {require(value in setOf("system","light","dark"));settings.edit().putString("appAppearance",value).apply();appAppearance=value}
    internal fun setPracticeGuidance(value: Boolean) {settings.edit().putBoolean("showPracticeGuidance",value).apply();showPracticeGuidance=value}
    internal fun setRuleExamples(value: Boolean) {settings.edit().putBoolean("showRuleExamples",value).apply();showRuleExamples=value}
    var currentTeamID by mutableStateOf(settings.getString("current_team_id", null))
        private set

    val currentTeam: Team?
        get() = teams.firstOrNull { it.id == currentTeamID } ?: teams.firstOrNull()

    private fun rememberCurrentTeam(id: String?) {
        currentTeamID = id
        settings
            .edit()
            .apply {
                if (id == null) remove("current_team_id") else putString("current_team_id", id)
            }
            .apply()
    }

    fun selectCurrentTeam(id: String) {
        if (teams.any { it.id == id }) rememberCurrentTeam(id)
    }

    var teams by mutableStateOf<List<Team>>(emptyList())
        private set

    var rosters by mutableStateOf<List<Team>>(emptyList())
        private set

    var games by mutableStateOf<List<Game>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var loading by mutableStateOf(true)
        private set

    var recoveryRequired by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var openedGameID by mutableStateOf<String?>(saved["openedGameID"])
        private set

    var practiceGame by mutableStateOf<Game?>(null)
        private set

    private val historyMutex = Mutex()
    var historyDraft by mutableStateOf<CorrectionDraft?>(null)
        private set

    var historyDraftLoadedFor by mutableStateOf<String?>(null)
        private set

    var historyDraftSaved by mutableStateOf(false)
        private set

    private var historyLoadingID: String? = null
    val openedGame
        get() =
            practiceGame?.takeIf { it.id == openedGameID }
                ?: games.firstOrNull { it.id == openedGameID }

    fun practice(method: String = "traditional", mode: GameMode = GameMode.standard) {
        fun team(id: String, name: String) =
            Team(id, name, (1..12).map { Player("$id-$it", "球员$it", it.toString()) })
        val id = "practice-${java.util.UUID.randomUUID()}"
        practiceGame =
            MatchFactory.create(
                id,
                team("practice-home", "主队"),
                team("practice-away", "客队"),
                Rules(mode, recordingMethod = method),
                practice = true,
            )
        open(id)
    }

    init {
        viewModelScope.launch {
            sharing.scheduleChanges.collect {
                try {
                    val authorityChanged = scheduleReadMutex.withLock { readLocalScheduleState() }
                    if (authorityChanged) {
                        scheduleReadGeneration++
                        refreshTeamSchedules(force = true)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    scheduleFeed = TeamScheduleFeed(message = "球队赛程暂时无法读取，请刷新重试。")
                }
            }
        }
        viewModelScope.launch {incomingLinkMutex.withLock {
            try {incomingTeamLink=sharing.state().incomingLinks.firstOrNull()}
            catch(_: Exception) {error="共享链接暂时无法读取，本机比赛仍可使用；请保留原链接与本机资料。"}
        }}
        viewModelScope.launch {
            val snapshots = GameSnapshotDecoder()
            combine(repository.teams, repository.games) { teams, games ->
                    teams.map { GameJson.decodeFromString<Team>(it.payload) } to snapshots.decode(games)
                }
                .flowOn(Dispatchers.Default)
                .catch {
                    recoveryRequired = true
                    loading = false
                    live.requestSync()
                    error = "本机资料暂时无法读取，原始数据已保留。请保留此设备上的资料并联系支持。"
                }
                .collect { (newTeams, newGames) ->
                    rosters = newTeams
                    teams = newTeams.filter {
                        !it.isOpponent && !it.archived && it.source == "local"
                    }
                    if (teams.none { it.id == currentTeamID })
                        rememberCurrentTeam(teams.firstOrNull()?.id)
                    games = newGames.map { next ->
                        games.firstOrNull { it.id == next.id && it.revision > next.revision }
                            ?: next
                    }
                    loading = false
                    live.requestSync()
                }
        }
        // Credential creation is isolated from scoring and never contacts a service.
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { CredentialVault(application).secret() } }
        }
    }

    fun open(id: String?) {
        openedGameID = id
        saved["openedGameID"] = id
    }

    fun dismissError() {
        error = null
    }

    private fun commit(block: suspend () -> Unit) {
        if (busy || recoveryRequired) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (failure: Exception) {
                error =
                    if (failure is IllegalArgumentException || failure is IllegalStateException)
                        failure.message
                    else "保存未完成，原有资料已保留。请检查设备空间后重试。"
            } finally {
                busy = false
            }
        }
    }

    fun createTeam(name: String, roster: String, opponent: Boolean = false, done: () -> Unit) = commit {
        repository.createTeam(name, roster, opponent)
        done()
    }

    internal suspend fun deviceBackupBytes(): ByteArray = withContext(Dispatchers.IO) {
        repository.migrateLegacyTeamArchives(getApplication())
        repository.exportDeviceBackup(currentTeamID)
    }

    internal suspend fun restoreDeviceBackup(bytes: ByteArray) {
        live.prepareForRestore()
        val selected = withContext(Dispatchers.IO) {
            repository.migrateLegacyTeamArchives(getApplication())
            repository.restoreDeviceBackup(bytes)
        }
        rememberCurrentTeam(selected)
        live.requestSync()
    }

    internal suspend fun appleBackupSummaries(): List<AppleBackupSummary> = withContext(Dispatchers.IO) { repository.appleBackupSummaries() }
    internal suspend fun appleBackupBytes(id: String, original: Boolean): ByteArray = withContext(Dispatchers.IO) { repository.exportAppleBackup(id, original) }
    internal fun importAppleBackup(bytes: ByteArray, done: (AppleImportReceipt) -> Unit) = commit {
        val receipt = withContext(Dispatchers.IO) { repository.importAppleBackup(bytes) }
        if (currentTeamID == null) rememberCurrentTeam(receipt.currentTeamID)
        done(receipt)
    }

    fun importRosters(rosters: List<Team>, done: () -> Unit) = commit {
        repository.importRosters(rosters)
        done()
    }

    fun createGame(
        team: Team,
        opponent: String,
        roster: String,
        rules: Rules,
        isHome: Boolean,
        scheduledAt: Long?,
        done: () -> Unit,
    ) = commit {
        val game =
            repository.createGame(team, opponent, roster, rules.mode, isHome, rules, scheduledAt)
        accept(game)
        open(game.id)
        done()
    }

    private fun accept(game: Game) {
        if (game.practice) practiceGame = game
        else games = listOf(game) + games.filter { it.id != game.id }
        if(!game.practice) live.requestSync()
    }

    fun deleteGame(game: Game) = commit {
        if (game.isScheduled) {
            val state = sharing.scheduleState()
            val publication = state.publications[game.id.lowercase()]
            check(state.teamFor(game.ourTeamID) == null ||
                publication?.let { it.pending == null && !it.needsReview && (it.notice == null || it.cancelled) } != false) {
                "请先打开比赛安排，取消球队赛程并确认结果，再删除本机安排。"
            }
        }
        repository.deleteGame(game.id, game.revision)
        if (live.bindings.any { it.gameID == game.id }) live.close(game.id)
        if (openedGameID == game.id) open(null)
        games = games.filterNot { it.id == game.id }
    }

    fun pitch(action: PitchAction) {
        val game = openedGame ?: return
        commit {
            accept(
                if (game.practice) MatchEngine.apply(game, Command("pitch", pitch = action))
                else repository.pitch(game.id, action, game.revision)
            )
        }
    }

    fun command(command: Command, baseRevision: Long? = null, done: () -> Unit = {}) {
        val game = openedGame ?: return
        commit {
            if (game.practice) {
                check(baseRevision == null || baseRevision == game.revision) { "练习局面已变化，请重新核对" }
                accept(MatchEngine.apply(game, command))
            } else accept(repository.command(game.id, command, baseRevision ?: game.revision))
            done()
        }
    }

    fun changeRecordingMethod(method: String, baseRevision: Long) {
        val game = openedGame ?: return
        if (game.rules.recordingMethod == method) return
        commit {
            check(game.revision == baseRevision) { "比赛已更新，请重新核对记录方式" }
            accept(
                if (game.practice) MatchEngine.changeRecordingMethod(game, method)
                else repository.changeRecordingMethod(game.id, method, baseRevision),
            )
        }
    }

    fun undo() {
        val game = openedGame ?: return
        commit {
            accept(
                if (game.practice) MatchEngine.undo(game)
                else repository.undo(game.id, game.revision)
            )
        }
    }

    fun redo() {
        val game = openedGame ?: return
        commit {
            accept(
                if (game.practice) MatchEngine.redo(game)
                else repository.redo(game.id, game.revision)
            )
        }
    }

    fun correct(
        index: Int,
        replacement: Command?,
        reason: String,
        acceptContext: Boolean = false,
        baseRevision: Long? = null,
        done: () -> Unit = {},
    ) {
        val game = openedGame ?: return
        commit {
            if (game.practice) {
                check(baseRevision == null || baseRevision == game.revision) { "练习局面已变化，请重新核对" }
                accept(MatchEngine.correct(game, index, replacement, reason, acceptContext))
            } else
                accept(
                    repository.correct(
                        game.id,
                        index,
                        replacement,
                        reason,
                        baseRevision ?: game.revision,
                        acceptContext,
                    )
                )
            done()
        }
    }

    fun loadHistoryDraft(game: Game) {
        if (historyDraftLoadedFor == game.id || historyLoadingID == game.id) return
        historyLoadingID = game.id
        viewModelScope.launch {
            try {
                val draft = historyMutex.withLock {
                    if (game.practice) null else repository.loadCorrectionDraft(game.id)
                }
                if (historyLoadingID == game.id) {
                    historyDraft = draft
                    historyDraftLoadedFor = game.id
                    historyDraftSaved = draft != null
                }
            } catch (failure: Exception) {
                error = "纠错草稿暂时无法读取，原资料已保留。"
            } finally {
                if (historyLoadingID == game.id) historyLoadingID = null
            }
        }
    }

    fun updateHistoryDraft(draft: CorrectionDraft) {
        val game = openedGame ?: return
        check(draft.gameID == game.id)
        historyDraft = draft
        historyDraftLoadedFor = game.id
        historyDraftSaved = false
        viewModelScope.launch {
            try {
                historyMutex.withLock { if (!game.practice) repository.saveCorrectionDraft(draft) }
                if (historyDraft == draft) historyDraftSaved = true
            } catch (failure: Exception) {
                error = "草稿未保存到本机，请保留当前页面后重试。"
            }
        }
    }

    fun discardHistoryDraft(done: () -> Unit) = commit {
        val game = openedGame ?: return@commit
        historyMutex.withLock { if (!game.practice) repository.discardCorrectionDraft(game.id) }
        historyDraft = null
        historyDraftSaved = false
        done()
    }

    fun saveHistoryCorrection(draft: CorrectionDraft, done: () -> Unit) = commit {
        val game = openedGame ?: return@commit
        historyMutex.withLock {
            accept(
                if (game.practice) History.commit(game, draft) else repository.saveCorrection(draft)
            )
        }
        historyDraft = null
        historyDraftSaved = false
        done()
    }

    fun updateRoster(team: Team, done: () -> Unit = {}) = commit {
        repository.updateRoster(team)
        done()
    }

    fun appendRoster(team: Team, input: String, confirmedWarnings: Boolean, done: () -> Unit) = commit {
        require(!team.archived && team.source == "local") { "只有本机现役球队可以追加队员" }
        val players = RosterAppend.preview(input, team.players).confirmedPlayers(confirmedWarnings)
        repository.updateRoster(team.copy(players = team.players + players), expected = team)
        done()
    }

    fun addRoster(name: String, short: String, city: String, opponent: Boolean, done: () -> Unit) =
        commit {
            repository.createRoster(name, short, city, opponent)
            done()
        }

    fun configureScheduledGame(game: Game, baseRevision: Long, done: () -> Unit) = commit {
        val configured = repository.configureScheduledGame(game, baseRevision)
        accept(configured)
        done()
    }

    fun createPreparedGame(game: Game, publishSchedule: Boolean = false, done: () -> Unit) = commit {
        val created = when {
            game.sharedRosterOrigin != null -> repository.createSharedRosterGame(game)
            game.ourTeamID == null -> repository.createObservationGame(game)
            else -> repository.createPreparedGame(game)
        }
        created.ourTeamID?.let(::selectCurrentTeam)
        accept(created)
        open(created.id)
        done()
        if (publishSchedule) updateTeamSchedule(created, requireNotNull(created.scheduledAt), created.scheduleVenue.orEmpty())
    }

    override fun onCleared() {
        database.close()
    }
}

internal val Game.isObservation: Boolean get() = ourTeamID == null && home.isOpponent && away.isOpponent && !practice

private val Brand = Color(0xFF092A2A)
internal val Accent = Color(0xFF217A57)

@Composable
internal fun BaseballTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = when(appearance) {"light"->false;"dark"->true;else->isSystemInDarkTheme()}
    val defaultTypography = Typography()
    val themedView = LocalView.current
    SideEffect {
        (themedView.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, themedView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(15.dp), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(18.dp)),
        typography = defaultTypography.copy(
            // Let each Text's actual font size choose its line box. A fixed
            // inherited 21sp line height made even 11sp metadata occupy a full
            // body line. Natural metrics also grow with accessibility font size.
            bodyLarge = defaultTypography.bodyLarge.copy(lineHeight = TextUnit.Unspecified,platformStyle=PlatformTextStyle(includeFontPadding=false)),
            bodyMedium = defaultTypography.bodyMedium.copy(lineHeight = TextUnit.Unspecified,platformStyle=PlatformTextStyle(includeFontPadding=false)),
            bodySmall = defaultTypography.bodySmall.copy(lineHeight = TextUnit.Unspecified,platformStyle=PlatformTextStyle(includeFontPadding=false)),
        ),
        colorScheme =
            if (dark)
                darkColorScheme(
                    primary = Color(0xFF48B889),
                    onPrimary = Color(0xFF111820),
                    primaryContainer = Color(0xFF173D30),
                    onPrimaryContainer = Color(0xFFC6EEDB),
                    secondary = Color(0xFF48B889),
                    onSecondary = Color(0xFF111820),
                    onSecondaryContainer = Color(0xFFC6EEDB),
                    error = Color(0xFFFF8B82),
                    onError = Color(0xFF4A1513),
                    errorContainer = Color(0xFF502522),
                    onErrorContainer = Color(0xFFFFDAD6),
                    background = Color(0xFF111820),
                    surface = Color(0xFF1B2632),
                    surfaceTint = Color.Transparent,
                    surfaceContainer = Color(0xFF1B2632),
                    surfaceContainerHigh = Color(0xFF1B2632),
                    surfaceContainerHighest = Color(0xFF25313E),
                    onSurfaceVariant = Color(0xFFAAB8C5),
                    onSurface = Color(0xFFE8F1F7),
                    onBackground = Color(0xFFE8F1F7),
                    outlineVariant = Color(0xFF31404D),
                    outline = Color(0xFF31404D),
                    secondaryContainer = Color(0xFF173D30),
                )
            else
                lightColorScheme(
                    primary = Accent,
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFE4F2EA),
                    onPrimaryContainer = Color(0xFF174D38),
                    secondary = Accent,
                    onSecondary = Color.White,
                    onSecondaryContainer = Color(0xFF174D38),
                    error = Color(0xFFBA3933),
                    onError = Color.White,
                    errorContainer = Color(0xFFFFE9E5),
                    onErrorContainer = Color(0xFF6C211C),
                    background = Color(0xFFF5F2EA),
                    surface = Color(0xFFFFFEFA),
                    surfaceTint = Color.Transparent,
                    surfaceContainer = Color(0xFFFFFEFA),
                    surfaceContainerHigh = Color(0xFFFFFEFA),
                    surfaceContainerHighest = Color(0xFFF5F2EA),
                    onSurface = Color(0xFF102A43),
                    onBackground = Color(0xFF102A43),
                    outlineVariant = Color(0xFFDADFD9),
                    outline = Color(0xFFB4C0BA),
                    onSurfaceVariant = Color(0xFF607284),
                    secondaryContainer = Color(0xFFE4F2EA),
                ),
        content = content,
    )
}

@Composable
fun ScorekeeperApp(model: ScorekeeperViewModel = viewModel()) {
    var tab by rememberSaveableState(0)
    var createTeam by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var createOpponent by rememberSaveable { mutableStateOf(false) }
    var manageOpponents by rememberSaveable { mutableStateOf(false) }
    var newGame by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val game = model.openedGame
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var rulesHelp by rememberSaveable { mutableStateOf(false) }
    if (rulesHelp) RulesScreen(showExamples = model.showRuleExamples) { rulesHelp = false }
    model.incomingTeamLink?.let {link -> key(link.id) {
        if(model.incomingLinkVisible) TeamSharingScreen(model.sharing,model.teams,incoming=link) {model.closeTeamLink(link.id)}
        else AlertDialog(onDismissRequest={},title={Text("有链接待核对")},text={Text(if(link.kind==IncomingTeamLinkKind.join) "之前的球队邀请已保留。核对球队与角色后再加入。" else "之前的设备配对链接已保留。选择球队并核对两台设备的短语后再授权。")},
            confirmButton={TextButton(onClick=model::resumeTeamLink) {Text("继续核对")}},dismissButton={TextButton(onClick={model.closeTeamLink(link.id)}) {Text("关闭此链接")}})
    } }
    BackHandler(game != null) { model.open(null) }
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (game == null) ProductBottomNavigation(tab) { tab = it }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            if (model.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (model.recoveryRequired)
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("资料需要恢复", style = MaterialTheme.typography.headlineMedium)
                    Text(model.error ?: "原始数据已保留，请联系支持。")
                }
            else if (game != null) {
                if (game.scheduledSetupPending || game.isScheduled && (
                        !game.scheduleVenue.isNullOrBlank() || model.schedulePublication(game) != null || model.scheduleNotice(game) != null ||
                            model.scheduleFeed.teamFor(game.ourTeamID) != null
                    )) PendingScheduleScreen(game, model) { opponent -> createOpponent = opponent; createTeam = true }
                else GameScreen(game, model)
            }
            else tabStates.SaveableStateProvider(tab) {
                if (tab == 0) {
                    HomeScreen(model, { createOpponent = false; createTeam = true }, { newGame = true }) { rulesHelp = true }
                } else {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = UiSpacing.page, vertical = UiSpacing.rowGap),
                        verticalArrangement = Arrangement.spacedBy(UiSpacing.section),
                    ) {
                        when (tab) {
                            1 -> {
                                RosterScreen(model, showTabHeader = true) { opponent -> createOpponent = opponent; createTeam = true }
                            }
                            2 -> StatisticsScreen(model)
                            else -> {
                                ProductTabHeader("我的")
                                ProfileScreen(model) { tab = 1 }
                            }
                        }
                    }
                }
            }
            if (model.error != null && !model.recoveryRequired)
                AlertDialog(
                    onDismissRequest = model::dismissError,
                    title = { Text("操作未完成") },
                    text = { Text(model.error!!) },
                    confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } },
                )
        }
    }
    if (createTeam)
        TeamDialog(model.busy, createOpponent, { createTeam = false }) { name, roster ->
            model.createTeam(name, roster, createOpponent) {
                createTeam = false
                if (!createOpponent && !manageOpponents) tab = 0
            }
        }
    if (newGame)
        NewGameDialog(
            model.teams.sortedBy { it.id != model.currentTeam?.id },
            model.rosters,
            model.sharing,
            model.busy,
            { newGame = false },
            games = model.games,
            defaultRecordingMethod = model.defaultRecordingMethod,
            manageOpponents = { manageOpponents = true },
            scheduleFeed = model.scheduleFeed,
            publish = { prepared -> model.createPreparedGame(prepared, publishSchedule = true) { newGame = false } },
        ) { prepared ->
            model.createPreparedGame(prepared) { newGame = false }
        }
    if (manageOpponents) FormPage("对手球队与名单", model.busy, true, "close-setup-opponents", "完成", { manageOpponents = false }, { manageOpponents = false }) {
        RosterScreen(model, initialFilter = "opponent") { opponent -> createOpponent = opponent; createTeam = true }
    }
}

@Composable
private fun rememberSaveableState(initial: Int) =
    androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initial) }

@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painterResource(R.drawable.app_icon),
            "棒球比赛记录大师图标",
            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)),
        )
        Column {
            Text("棒球比赛记录大师", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("每一球 都有迹可循", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .75f)),
    ) {
        Column(
            Modifier.padding(UiSpacing.cardInset),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.card),
            content = content,
        )
    }
}

@Composable
internal fun ProductTabHeader(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier
                .padding(horizontal = if (leading != null || trailing != null) 68.dp else 8.dp)
                .semantics { heading() })
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
private fun ProductBottomNavigation(selected: Int, onSelected: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = UiSpacing.page, vertical = UiSpacing.rowGap),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 540.dp).fillMaxWidth(), color = colors.surface,
            shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val icons = listOf(NavigationSymbols.diamond, NavigationSymbols.roster, NavigationSymbols.statistics, NavigationSymbols.profile)
                listOf("比赛", "球队", "统计", "我的").forEachIndexed { index, label ->
                    val active = selected == index
                    val tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                            .background(if (active) colors.primaryContainer else Color.Transparent)
                            .selectable(active, role = Role.Tab, onClick = { onSelected(index) })
                            .testTag("tab-$index").heightIn(min = 52.dp).padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(icons[index], null, Modifier.size(23.dp), tint = tint)
                        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = tint)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(model: ScorekeeperViewModel, addTeam: () -> Unit, newGame: () -> Unit, rules: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.checkForUpdate()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                model.refreshTeamSchedules()
                delay(60_000)
            }
        }
    }
    val team = model.currentTeam
    val games = model.games
    val (ongoing, completed) = remember(games) {
        Pair(
            games.filter { !it.isFinal && !it.isScheduled },
            games.filter { it.isFinal }.sortedWith(compareByDescending<Game> {
                it.playedAt ?: it.startedAt ?: it.scheduledAt ?: it.createdAt
            }.thenBy { it.id }),
        )
    }
    val feed = model.scheduleFeed
    var scheduleExpiry by remember { mutableLongStateOf(0L) }
    val scheduled = remember(games, feed, scheduleExpiry) { upcomingSchedules(games, feed, System.currentTimeMillis()) }
    val nextExpiry = scheduled.firstOrNull { it is UpcomingSchedule.Member }?.startsAt
    LaunchedEffect(nextExpiry) {
        if (nextExpiry != null) {
            var wait = nextExpiry - System.currentTimeMillis()
            while (wait >= 0) {
                delay(wait + 1)
                wait = nextExpiry - System.currentTimeMillis()
            }
            scheduleExpiry++
        }
    }
    var managedScheduleKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSchedule = scheduled.filterIsInstance<UpcomingSchedule.Member>()
        .firstOrNull { it.notice.key == managedScheduleKey }?.notice
    val adminSchedule = selectedSchedule?.takeIf(model::canManageRemoteSchedule)
    LaunchedEffect(managedScheduleKey, adminSchedule, model.scheduleRefreshing) {
        if (managedScheduleKey != null && adminSchedule == null && !model.scheduleRefreshing) managedScheduleKey = null
    }
    val teamRecord = remember(games, team?.id) { HomeSummary.record(games, team?.id).label }
    val update = model.advertisedUpdate
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("home-games-list"),
        contentPadding = PaddingValues(horizontal = UiSpacing.page, vertical = UiSpacing.rowGap),
        verticalArrangement = Arrangement.spacedBy(UiSpacing.rowGap),
    ) {
        item(key = "title") {
            ProductTabHeader("比赛", trailing = {
                IconButton(rules, Modifier.size(48.dp).testTag("home-rules-help")) {
                    Icon(Icons.Outlined.Info, "规则帮助", tint = MaterialTheme.colorScheme.primary)
                }
            })
        }
        item(key = "identity") { HomeTeamHeader(team, teamRecord) }
        item(key = "new-match") {
            Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.rowGap)) {
                if (team == null) OutlinedButton(addTeam, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("create-team"),
                    shape = MaterialTheme.shapes.medium) { Text("创建球队") }
                Button(newGame, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("new-game"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = ProductGreen, contentColor = Color.White)) {
                    Icon(Icons.Outlined.AddCircle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(UiSpacing.rowGap))
                    Text(if (ongoing.isEmpty()) "开始新比赛" else "再开始一场比赛",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (update != null) item(key = "website-update") { WebsiteUpdateNotice(update, onDismiss = { model.dismissUpdate(update.build) }) }
        if (scheduled.isNotEmpty() || feed.memberships.isNotEmpty() || feed.message != null) {
            item(key = "scheduled-header") {
                Row(Modifier.padding(top = UiSpacing.rowGap), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("即将进行", "${scheduled.size} 场") }
                    if (feed.memberships.isNotEmpty() || feed.message != null) {
                        TextButton(onClick = { model.refreshTeamSchedules(force = true) }, enabled = !model.scheduleRefreshing,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("team-schedule-refresh")) {
                            Text(if (model.scheduleRefreshing) "刷新中…" else if (feed.message != null) "重试" else "刷新")
                        }
                    }
                }
            }
            feed.message?.let { message ->
                item(key = "schedule-feed-error") {
                    Text(message, Modifier.testTag("team-schedule-feed-error"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            items(scheduled, key = { it.key }) { entry ->
                when (entry) {
                    is UpcomingSchedule.Local -> HomeGameRow(entry.game, model, entry.publication, entry.notice)
                    is UpcomingSchedule.Member -> TeamScheduleNoticeRow(
                        entry.notice,
                        onManage = if (model.canManageRemoteSchedule(entry.notice)) {
                            { managedScheduleKey = entry.notice.key }
                        } else null,
                    )
                }
            }
        }
        item(key = "ongoing-header") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(UiSpacing.rowGap),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("正在进行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    if (ongoing.isNotEmpty()) Text("${ongoing.size} 场", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ model.practice() }, modifier = Modifier.heightIn(min = 48.dp).testTag("start-practice"),
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("练习记分") }
            }
        }
        if (ongoing.isEmpty()) item(key = "ongoing-empty") {
            EmptyCard("没有进行中的比赛", Icons.Outlined.PlayArrow)
        }
        else items(ongoing, key = { "match-${it.id}" }) { HomeGameRow(it, model) }
        item(key = "recent-header") {
            Box(Modifier.padding(top = UiSpacing.rowGap)) { SectionTitle("最近比赛", "按比赛日期排序") }
        }
        if (completed.isEmpty()) item(key = "completed-empty") {
            EmptyCard("还没有已完成的比赛", Icons.Outlined.DateRange)
        }
        else items(completed, key = { "match-${it.id}" }) { HomeGameRow(it, model) }
    }
    if (adminSchedule != null) RemoteTeamScheduleScreen(adminSchedule, model) { managedScheduleKey = null }
}

@Composable
private fun HomeTeamHeader(team: Team?, record: String) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp)) {
        val wrapRecord = maxWidth < 300.dp * fontScale.coerceAtLeast(1f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (team != null) TeamMark(team.name, 48)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(team?.name ?: "尚未创建球队", Modifier.testTag("home-team-name"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (team != null) {
                    Text("${java.time.Year.now()} 年 · ${team.players.count { !it.archived }} 名球员",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (wrapRecord) HomeRecordPill(record, Modifier.padding(top = 4.dp))
                } else Text("创建球队后可管理名单和查看统计",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (team != null && !wrapRecord) HomeRecordPill(record)
        }
    }
}

@Composable
private fun HomeRecordPill(record: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = CircleShape) {
        Text(record, Modifier.testTag("home-team-record").padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomeGameRow(
    game: Game, model: ScorekeeperViewModel, publication: TeamSchedulePublication? = null, notice: TeamScheduleNotice? = null,
) {
    val colors = MaterialTheme.colorScheme
    val result = when {
        !game.isFinal -> null
        game.isObservation -> "观"
        game.ourTeamID == game.home.id -> when { game.homeRuns > game.awayRuns -> "胜"; game.homeRuns < game.awayRuns -> "负"; else -> "平" }
        game.ourTeamID == game.away.id -> when { game.awayRuns > game.homeRuns -> "胜"; game.awayRuns < game.homeRuns -> "负"; else -> "平" }
        else -> "完"
    }
    val date = if (game.isScheduled) notice?.startsAt ?: game.scheduledAt
        else game.playedAt ?: game.startedAt ?: game.createdAt.takeIf { it > 0 }
    val detail = when {
        game.isScheduled -> date?.let { formatMatchDate(it, game.playedTimeZoneID) } ?: "时间待定"
        game.isFinal -> date?.let { formatMatchDate(it, game.playedTimeZoneID) }.orEmpty()
        else -> buildString {
            append("${game.inning}局${if (game.isTop) "上" else "下"}")
            val runnerCount = game.bases.count { it != null }
            if (runnerCount > 0) append(" · $runnerCount 名跑者在垒")
        }
    }
    Surface(Modifier.fillMaxWidth(), color = colors.surface, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 62.dp)
                    .clickable(role = Role.Button) { model.open(game.id) }.testTag("game-${game.id}")
                    .padding(start = UiSpacing.cardInset, top = UiSpacing.rowGap, bottom = UiSpacing.rowGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.rowGap),
            ) {
                if (game.isScheduled || result != null) {
                    val background = if (result == "负") colors.errorContainer else colors.primaryContainer
                    val tint = if (result == "负") colors.onErrorContainer else colors.onPrimaryContainer
                    Box(Modifier.size(30.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
                        if (game.isScheduled) Icon(Icons.Outlined.DateRange, null, Modifier.size(17.dp), tint = tint)
                        else Text(result.orEmpty(), style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${game.away.shortName} 对 ${game.home.shortName}", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (game.isScheduled) (notice?.venue ?: game.scheduleVenue)?.takeIf { it.isNotBlank() }?.let { venue ->
                        Text(venue, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (!game.isScheduled && !game.isFinal) {
                        Text(if (game.isObservation) "继续观赛记录" else "继续比赛", Modifier.testTag("continue-game"),
                            style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (game.isScheduled) {
                    val status = when {
                        publication?.pending != null || publication?.needsReview == true -> "待确认"
                        notice?.cancelled == true -> "已取消"
                        notice != null -> "球队赛程"
                        game.isObservation -> "观赛"
                        else -> "本队"
                    }
                    Text(status, style = MaterialTheme.typography.labelMedium,
                        color = if (notice?.cancelled == true) colors.error else colors.primary)
                }
                else Text("${game.awayRuns} : ${game.homeRuns}", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            GameDeleteControl(game, model)
        }
    }
}

@Composable
private fun GameDeleteControl(game: Game, model: ScorekeeperViewModel) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, Modifier.size(48.dp).testTag("game-menu-${game.id}")) {
            Icon(Icons.Outlined.MoreVert, "${game.away.shortName} 对 ${game.home.shortName}的比赛操作", Modifier.size(20.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if(game.isScheduled) "删除安排" else "删除比赛") }, onClick = { menu = false; confirm = true })
        }
    }
    if (confirm) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { confirm = false }, title = { Text("删除比赛记录？") },
        text = { Text("这场本机比赛及现场记录会永久删除，此操作无法撤销。") },
        confirmButton = { TextButton({ confirm = false; model.deleteGame(game) }, enabled = !model.busy, modifier = Modifier.testTag("confirm-delete-game")) { Text("删除这场比赛") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}

@Composable
internal fun TeamMark(name: String, size: Int) {
    Box(
        Modifier.size(size.dp).background(Color(0xFF102A43), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1),
            color = Color.White,
            fontSize = (size * .42).sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun SectionTitle(title: String, detail: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (detail != null)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    CardBlock {
        Row(
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                null,
                Modifier.width(38.dp).size(23.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (title == "没有进行中的比赛") Text("新比赛创建后会自动保存在这里，可在 App 重启后继续记录。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TeamsScreen(teams: List<Team>, add: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("我的球队", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        FilledTonalButton(onClick = add, modifier = Modifier.testTag("add-team"), shape = RoundedCornerShape(15.dp)) { Text("创建球队") }
    }
    if (teams.isEmpty()) Text("创建球队后，在这里管理你的球员名单。")
    teams.forEach { team ->
        CardBlock {
            Text(
                team.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text("${team.players.size} 名球员", color = MaterialTheme.colorScheme.onSurfaceVariant)
            team.players.forEach { player ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        player.number,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(player.name)
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(model: ScorekeeperViewModel, manageTeams: () -> Unit) {
    var showRules by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    if (showRules) RulesScreen(showExamples = model.showRuleExamples) { showRules = false }

    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    CardBlock(Modifier.testTag("profile-identity")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = colors.primaryContainer, shape = CircleShape) {
                Icon(NavigationSymbols.profile, null, Modifier.padding(6.dp).size(43.dp), tint = colors.primary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("本机记分员", style = type.titleLarge, fontWeight = FontWeight.Bold)
                Text("当前球队 · ${model.currentTeam?.name ?: "尚未选择球队"}", style = type.bodyMedium,
                    color = colors.onSurfaceVariant)
                Text("保存在此设备", style = type.labelMedium, color = colors.primary)
            }
        }
    }
    ProfileSection("学习与帮助") {
        ProfileMenuRow("棒球规则查询", "常用规则解释与完整规则 PDF", Icons.AutoMirrored.Outlined.List, "open-rules") { showRules = true }
        ProfileMenuRow("练习一局", "独立练习，不影响正式比赛数据", NavigationSymbols.diamond, "open-practice-inning") { model.practice() }
    }
    ProfileSection("本机数据与设置") {
        ProfileMenuRow("本地数据", "备份、恢复和已导入的资料", Icons.Outlined.AccountBox, "open-local-data") { page = "data" }
        ProfileMenuRow("App 设置", "默认记录方式 · ${RecordingMethodLabels.getValue(model.defaultRecordingMethod)}",
            Icons.Outlined.Settings, "open-app-settings") { page = "settings" }
        ProfileMenuRow("关于 BaseballMaster", "版本信息与数据说明", Icons.Outlined.Info, "open-app-about") { page = "about" }
    }
    when (page) {
        "data" -> LocalDataScreen(model, { page = null; manageTeams() }) { page = null }
        "settings" -> AppSettingsScreen(model) { page = null }
        "about" -> AppAboutScreen(model) { page = null }
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = UiSpacing.rowGap), verticalArrangement = Arrangement.spacedBy(UiSpacing.rowGap)) {
        SectionTitle(title, null)
        content()
    }
}

@Composable
private fun ProfileMenuRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag(tag),
        shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = UiSpacing.cardInset, vertical = UiSpacing.rowGap),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.small) {
                Icon(icon, null, Modifier.padding(8.dp).size(21.dp), tint = colors.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        }
    }
}

internal val SampleRoster =
    (1..10).joinToString("\n") { "示例球员$it,${if (it == 1) "00" else it.toString()}" }

@Composable
internal fun FormPage(
    title: String,
    busy: Boolean,
    enabled: Boolean,
    saveTag: String,
    saveLabel: String,
    dismiss: () -> Unit,
    save: () -> Unit,
    secure: Boolean = false,
    dismissLabel: String = "取消",
    content: @Composable ColumnScope.() -> Unit,
) {
    ProductPage(
        title = title,
        busy = busy,
        onBack = dismiss,
        backLabel = if (saveLabel.isBlank() && dismissLabel == "取消") "返回" else dismissLabel,
        actionLabel = saveLabel,
        actionAtTop = saveLabel in setOf("保存", "应用", "完成"),
        actionEnabled = enabled,
        actionTag = saveTag,
        onAction = save,
        secure = secure,
        content = content,
    )
}

@Composable
private fun TeamDialog(busy: Boolean, opponent: Boolean, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var roster by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    FormPage(
        if (opponent) "批量创建对手" else "创建球队",
        busy,
        name.isNotBlank() && roster.isNotBlank(),
        "save-team",
        "保存",
        dismiss,
        { save(name, roster) },
    ) {
        CardBlock {
            Text("球队名称", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                name,
                { name = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("team-name"),
            )
        }
        CardBlock {
            Text("球员名单", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                "每行填写：姓名,背号。背号支持 00。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                roster,
                { roster = it },
                minLines = 8,
                maxLines = 12,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("team-roster"),
            )
            TextButton(onClick = { roster = SampleRoster }) { Text("填入示例名单") }
        }
    }
}

@Composable
private fun GameScreen(game: Game, model: ScorekeeperViewModel) {
    val wide = LocalWorkbenchWide.current
    var openedOnResults by rememberSaveable(game.id) { mutableStateOf(game.isFinal && !game.practice) }
    LaunchedEffect(game.isFinal) { if (!game.isFinal) openedOnResults = false }
    var requestedPanel by rememberSaveable(game.id) { mutableStateOf("") }
    var openingPitch by rememberSaveable(game.id) { mutableStateOf<String?>(null) }
    var recordingMethodRevision by rememberSaveable(game.id) { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(game.id) {
        while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() }
    }
    fun pitch(action: PitchAction) {
        if (!game.openingPitchRecorded && !game.practice) openingPitch = action.name else model.pitch(action)
    }
    if (openingPitch != null) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = { openingPitch = null }, title = { Text("确认双方先发阵容") },
        text = { Text("${game.away.name} 与 ${game.home.name}的先发阵容确认后开始计时。开赛后仍可调整阵容。") },
        confirmButton = { TextButton({ val action = PitchAction.valueOf(openingPitch!!); openingPitch = null; model.pitch(action) },
            modifier = Modifier.testTag("confirm-opening-pitch")) { Text("确认开赛") } },
        dismissButton = { TextButton({ openingPitch = null; requestedPanel = "lineup" }) { Text("继续设置阵容") } })
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        ScoringPanels(game, model, requestedPanel, { requestedPanel = "" },
            initialPanel = if (openedOnResults) "results" else "",
            closeInitialResults = if (openedOnResults) ({ model.open(null) }) else null,
            host = { panelControls, finishActions, task, inlineTask, locked ->
                val enabled = !model.busy && !locked && !game.isFinal && !game.isScheduled && game.pendingDecision == null
                val canChangeRecordingMethod = enabled && game.appleSourceID == null
                val currentMethod = RecordingMethodLabels.getValue(game.rules.recordingMethod)
                recordingMethodRevision?.let { revision ->
                    val nextMethod = if (game.rules.recordingMethod == "situation") "traditional" else "situation"
                    val nextLabel = RecordingMethodLabels.getValue(nextMethod)
                    AlertDialog(
                        modifier = Modifier.semantics { testTagsAsResourceId = true },
                        onDismissRequest = { recordingMethodRevision = null },
                        title = { Text("记录方式") },
                        text = { Text("当前为$currentMethod。切换后会接着记录当前打席，已保存的球数和明细会保留。") },
                        confirmButton = {
                            TextButton(
                                onClick = { recordingMethodRevision = null; model.changeRecordingMethod(nextMethod, revision) },
                                enabled = canChangeRecordingMethod,
                                modifier = Modifier.testTag("confirm-recording-method"),
                            ) { Text("切换至$nextLabel") }
                        },
                        dismissButton = { TextButton({ recordingMethodRevision = null }) { Text("取消") } },
                    )
                }
                AdaptiveScoreWorkbench(game, inlineTask, locked, game.ruleNotices(now),
                    toolbar = {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val largerText = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
                            val showBroadcast = !game.practice && maxWidth >= 324.dp + 148.dp * largerText
                            val showPoster = showBroadcast && maxWidth >= 372.dp + 148.dp * largerText
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ model.open(null) }, enabled = !locked, modifier = Modifier.size(48.dp).testTag("back-home")) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回比赛", tint = MaterialTheme.colorScheme.primary)
                                }
                                Text(if (game.isObservation) "观赛记分" else "现场记分", Modifier.weight(1f),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (showBroadcast) {
                                    IconButton({ requestedPanel = "live" }, enabled = !locked, modifier = Modifier.size(48.dp).testTag("open-live-broadcast")) {
                                        Icon(Icons.Outlined.Share, "文字直播与分享", Modifier.size(21.dp))
                                    }
                                }
                                if (showPoster) {
                                    IconButton({ requestedPanel = "poster" }, enabled = !locked, modifier = Modifier.size(48.dp).testTag("open-game-poster")) {
                                        Icon(Icons.Outlined.AddCircle, "比赛宣传海报", Modifier.size(21.dp))
                                    }
                                }
                                IconButton({ requestedPanel = "history" }, enabled = !locked, modifier = Modifier.size(48.dp).testTag("game-history")) {
                                    Icon(Icons.Outlined.Refresh, "纠正记录", Modifier.size(21.dp))
                                }
                                TextButton({ requestedPanel = "results" }, enabled = !locked,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("game-results"), contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("比赛结果", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Box {
                                    IconButton({ menu = true }, enabled = !locked, modifier = Modifier.size(48.dp).testTag("scoring-more")) {
                                        Icon(Icons.Outlined.MoreVert, "比赛菜单", Modifier.size(21.dp))
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        if (!game.practice && !showBroadcast) DropdownMenuItem(
                                            text = { Text("文字直播与分享") }, modifier = Modifier.testTag("open-live-broadcast"),
                                            onClick = { menu = false; requestedPanel = "live" },
                                        )
                                        if (!game.practice && !showPoster) DropdownMenuItem(
                                            text = { Text("比赛宣传海报") }, modifier = Modifier.testTag("open-game-poster"),
                                            onClick = { menu = false; requestedPanel = "poster" },
                                        )
                                        if (!game.isFinal && !game.isScheduled && game.appleSourceID == null) {
                                            DropdownMenuItem(
                                                text = { Text("记录方式：$currentMethod") },
                                                enabled = canChangeRecordingMethod,
                                                modifier = Modifier.testTag("scoring-recording-method"),
                                                onClick = { menu = false; recordingMethodRevision = game.revision },
                                            )
                                        }
                                        listOf("比赛结果" to "results", "双方阵容与打序" to "lineup", "比赛计时" to "clock",
                                            "比赛时间／预告" to "time", "本场比赛规则" to "rules", "完整记录与纠错" to "history", "报告与更多操作" to "more")
                                            .forEach { (label, target) -> DropdownMenuItem(text = { Text(label) }, enabled = !locked,
                                                onClick = { menu = false; requestedPanel = target }) }
                                    }
                                }
                            }
                        }
                    },
                    scoreboard = {
                        val timer: @Composable () -> Unit = {
                            if (game.clockHasStarted || game.rules.timed) {
                                val seconds = (if (game.clockDisplayMode == "remaining") game.remainingTime(now) ?: game.elapsed(now) else game.elapsed(now)) / 1000
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ requestedPanel = "clock" }, enabled = !locked, contentPadding = PaddingValues(horizontal = 2.dp),
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("game-clock")) {
                                        Text("${if (game.clockDisplayMode == "remaining") "倒" else "正"} ${seconds / 60}:${"%02d".format(kotlin.math.abs(seconds % 60))}",
                                            fontSize = 10.sp, maxLines = 1)
                                    }
                                    if (!game.isFinal) TextButton({ model.command(Command("clock", text = if (game.clockRunningSince == null) "resume" else "pause")) },
                                        enabled = !locked && !model.busy, modifier = Modifier.size(48.dp).testTag("toggle-game-clock")
                                            .semantics { contentDescription = if (game.clockRunningSince == null) "继续计时" else "暂停计时" },
                                        contentPadding = PaddingValues(0.dp)) {
                                        Text(if (game.clockRunningSince == null) "▶" else "Ⅱ", Modifier.clearAndSetSemantics {}, fontSize = 10.sp)
                                    }
                                }
                            } else Text(game.rules.mode.title, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        val counts: @Composable () -> Unit = {
                            val colors = MaterialTheme.colorScheme
                            val dark = colors.surface.luminance() < .4f
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                MiniCount("B", game.balls, 3, colors.primary, "坏球")
                                MiniCount("S", game.strikes, 2, if (dark) Color(0xFFF5AC68) else Color(0xFFA35513), "好球")
                                MiniCount("O", game.outs, 2, if (dark) colors.error else Color(0xFFC7463D), "出局")
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).heightIn(min = 32.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("${game.inning}局${if (game.isTop) "上" else "下"}", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(game.away.shortName, Modifier.weight(1f, false), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${game.awayRuns} : ${game.homeRuns}", fontSize = 24.sp, fontWeight = FontWeight.Black)
                                    Text(game.home.shortName, Modifier.weight(1f, false), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                timer()
                                counts()
                            }
                        } else Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${game.inning}局${if (game.isTop) "上" else "下"}", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(game.away.shortName, Modifier.weight(1f, false), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${game.awayRuns} : ${game.homeRuns}", fontSize = 22.sp, fontWeight = FontWeight.Black)
                                        Text(game.home.shortName, Modifier.weight(1f, false), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    timer()
                                    counts()
                                }
                            }
                        }
                    },
                    controls = {
                        if (game.canSwapOpeningSides && !game.isFinal && !game.isScheduled)
                            TextButton({ model.command(Command("swap")) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("swap-opening-sides")) { Text("互换先攻／后攻", fontSize = 11.sp) }
                        if (game.rules.mode == GameMode.coachPitch) Text(
                            "教练投手 · 本打席 ${game.appearancePitches} / ${game.rules.coachPitchLimit} 球",
                            Modifier.testTag("coach-pitch-progress"), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!game.isFinal && !game.isScheduled && game.pendingDecision == null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                PitchAction.entries.forEach { action ->
                                    val color = when (action) {
                                        PitchAction.ball -> ProductGreen
                                        PitchAction.calledStrike, PitchAction.swingingStrike -> Color(0xFF9F4C0D)
                                        else -> Color(0xFF102A43)
                                    }
                                    val title = when (action) {
                                        PitchAction.calledStrike -> "好球 · 看"
                                        PitchAction.swingingStrike -> "好球 · 挥空"
                                        else -> action.title
                                    }
                                    Button({ pitch(action) }, enabled = enabled,
                                        contentPadding = PaddingValues(horizontal = 2.dp), shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pitch-${action.name}")) {
                                        Text(title, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                        panelControls()
                    },
                    finishActions = finishActions,
                    task = task,
                    openPanel = { if (!locked) requestedPanel = it },
                )
            },
        )
    }
}

@Composable
private fun MiniCount(label: String, value: Int, maximum: Int, color: Color, tag: String) {
    val empty = if (MaterialTheme.colorScheme.surface.luminance() < .4f) Color(0xFF536574) else Color(0xFFDADFD9)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.clearAndSetSemantics {},
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Black,
        )
        // Numeric semantics preserve the same accessible count while the display follows iOS dots.
        Box(
            Modifier.testTag("count-$tag").semantics {
                text = androidx.compose.ui.text.AnnotatedString("$value")
                contentDescription = "$tag $value"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(maximum) { index ->
                    Box(
                        Modifier.size(8.dp)
                            .background(
                                if (index < value) color else empty,
                                RoundedCornerShape(50),
                            )
                    )
                }
            }
        }
    }
}

@Composable
internal fun Field(game: Game, modifier: Modifier) {
    val field = if (MaterialTheme.colorScheme.background.luminance()<.5f) Color(0xFF245F49) else Color(0xFF2E765A)
    val dirt = if (MaterialTheme.colorScheme.background.luminance()<.5f) Color(0xFFA78356) else Color(0xFFD7B785)
    val runners = game.bases.map { id -> game.battingTeam.players.firstOrNull { it.id == id } }
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(field.copy(alpha = .86f))
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        if (h <= 100f || w <= 0f) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                runners.forEachIndexed { index, runner ->
                    val occupant = when {
                        runner != null -> "#${runner.number}"
                        game.bases[index] != null -> "有人"
                        else -> "无人"
                    }
                    Text("${index + 1}垒 · $occupant", fontSize = 11.sp, color = Color.White)
                }
            }
            return@BoxWithConstraints
        }
        val geometry=BaseballFieldGeometry(w.toDouble(),h.toDouble())
        Canvas(Modifier.fillMaxSize()) {drawBaseballField(geometry,field,dirt,game.bases.map {it!=null})}
        val spots=(1..10).map {position -> geometry.fielder(position).let {Offset(it.x.toFloat(),it.y.toFloat())}}
        game.fieldingPositions.toSortedMap().forEach { (position, id) ->
            val index = position - 1
            val player = game.fieldingTeam.players.first { it.id == id }
            val at = spots[index]
            Column(
                Modifier.offset((at.x - 32).dp, (at.y - 16).dp)
                    .size(64.dp, 32.dp)
                    .background(
                        if (index == 0) Color(0xFF102A43) else Color.White.copy(alpha = .96f),
                        RoundedCornerShape(9.dp),
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    player.name,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (index == 0) Color.White else Color(0xFF102A43),
                    maxLines = 1,
                )
                Text(
                    "#${player.number}",
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = if (index == 0) Color.White.copy(alpha = .75f) else Color(0xFF607284),
                )
            }
        }
        listOf(geometry.first, geometry.second, geometry.third).forEachIndexed { index, base ->
            val runner = runners[index] ?: return@forEachIndexed
            Box(
                Modifier.offset((base.x - 18).dp, (base.y - 13).dp).size(36.dp, 26.dp)
                    .testTag("field-runner-${index + 1}")
                    .clearAndSetSemantics {
                        contentDescription = "${index + 1}垒：${runner.name}，${runner.number}号"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("#${runner.number}", color = Color(0xFF102A43), fontSize = 9.sp, lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(
                    "${game.fieldingTeam.name}守备",
                    if (game.bases.all { it == null }) "垒上无人"
                    else "垒上 ${game.bases.count{it!=null}} 人",
                )
                .forEach {
                    Text(
                        it,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier =
                            Modifier.background(
                                    Color.Black.copy(alpha = .22f),
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
        }
    }
}
