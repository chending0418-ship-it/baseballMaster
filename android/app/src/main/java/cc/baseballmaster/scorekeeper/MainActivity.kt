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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.baseballmaster.core.*
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
    private fun receiveServiceIntent(source: Intent) {
        val value=source.dataString;source.data=null
        if(source.action==Intent.ACTION_VIEW && value!=null) screenModel.receiveTeamLink(value)
    }
}

class ScorekeeperViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val database = LocalDatabase.open(application)
    private val repository = GameRepository(database)
    internal val sharing by lazy { AndroidTeamSharing(repository,androidTeamDirectory(application,"https://baseballmaster.cc/api/v3")) }
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
    internal var appAppearance by mutableStateOf(settings.getString("appAppearance","system")?.takeIf {it in setOf("system","light","dark")} ?: "system")
        private set
    internal var showPracticeGuidance by mutableStateOf(settings.getBoolean("showPracticeGuidance",true))
        private set
    internal var showRuleExamples by mutableStateOf(settings.getBoolean("showRuleExamples",true))
        private set
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
        viewModelScope.launch {incomingLinkMutex.withLock {
            try {incomingTeamLink=sharing.state().incomingLinks.firstOrNull()}
            catch(_: Exception) {error="共享链接暂时无法读取，本机比赛仍可使用；请保留原链接与本机资料。"}
        }}
        viewModelScope.launch {
            combine(repository.teams, repository.games) { teams, games ->
                    teams.map { GameJson.decodeFromString<Team>(it.payload) } to
                        games.map { GameRepository.decode(it) }
                }
                .catch {
                    recoveryRequired = true
                    loading = false
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

    fun createTeam(name: String, roster: String, done: () -> Unit) = commit {
        repository.createTeam(name, roster)
        done()
    }

    internal suspend fun deviceBackupBytes(): ByteArray = withContext(Dispatchers.IO) { repository.exportDeviceBackup(currentTeamID) }

    internal suspend fun restoreDeviceBackup(bytes: ByteArray) {
        val selected = withContext(Dispatchers.IO) { repository.restoreDeviceBackup(bytes) }
        rememberCurrentTeam(selected)
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

    fun addRoster(name: String, short: String, city: String, opponent: Boolean, done: () -> Unit) =
        commit {
            repository.createRoster(name, short, city, opponent)
            done()
        }

    fun createPreparedGame(game: Game, done: () -> Unit) = commit {
        val created = repository.createPreparedGame(game)
        created.ourTeamID?.let(::selectCurrentTeam)
        accept(created)
        open(created.id)
        done()
    }

    override fun onCleared() {
        database.close()
    }
}

private val Brand = Color(0xFF092A2A)
internal val Accent = Color(0xFF217A57)

@Composable
internal fun BaseballTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = when(appearance) {"light"->false;"dark"->true;else->isSystemInDarkTheme()}
    val defaultTypography = Typography()
    MaterialTheme(
        typography = defaultTypography.copy(
            bodyLarge = defaultTypography.bodyLarge.copy(lineHeight = 21.sp),
            bodyMedium = defaultTypography.bodyMedium.copy(lineHeight = 18.sp),
            bodySmall = defaultTypography.bodySmall.copy(lineHeight = 16.sp),
        ),
        colorScheme =
            if (dark)
                darkColorScheme(
                    primary = Color(0xFF48B889),
                    background = Color(0xFF111820),
                    surface = Color(0xFF1B2632),
                    onSurface = Color(0xFFE8F1F7),
                    onBackground = Color(0xFFE8F1F7),
                    outlineVariant = Color(0xFF31404D),
                    outline = Color(0xFF31404D),
                    secondaryContainer = Color(0xFF173D30),
                )
            else
                lightColorScheme(
                    primary = Accent,
                    background = Color(0xFFF5F2EA),
                    surface = Color(0xFFFFFEFA),
                    onSurface = Color(0xFF102A43),
                    onBackground = Color(0xFF102A43),
                    outlineVariant = Color(0xFFDADFD9),
                    outline = Color(0xFFDADFD9),
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
    var newGame by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val game = model.openedGame
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
            if (game == null)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp)) {
                        val labels = listOf("比赛", "球队", "统计", "我的")
                        val icons =
                            listOf(
                                Icons.Outlined.PlayArrow,
                                Icons.Outlined.Person,
                                Icons.AutoMirrored.Outlined.List,
                                Icons.Outlined.AccountCircle,
                            )
                        labels.forEachIndexed { index, label ->
                            Column(
                                Modifier.weight(1f)
                                    .fillMaxHeight()
                                    .testTag("tab-$index")
                                    .clickable { tab = index },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val color =
                                    if (tab == index) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                Icon(icons[index], null, Modifier.size(23.dp), tint = color)
                                Text(label, fontSize = 10.sp, color = color)
                            }
                        }
                    }
                }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (model.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (model.recoveryRequired)
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("资料需要恢复", style = MaterialTheme.typography.headlineMedium)
                    Text(model.error ?: "原始数据已保留，请联系支持。")
                }
            else if (game != null)
                GameScreen(game, model.busy, { model.open(null) }, model::pitch) {
                    ScoringPanels(game, model)
                }
            else
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(key(tab) { rememberScrollState() })
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.section),
                ) {
                    if (tab != 2) Text(
                        listOf("比赛", "球队", "统计", "我的")[tab],
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    )
                    when (tab) {
                        0 -> HomeScreen(model, { createTeam = true }, { newGame = true })
                        1 -> RosterScreen(model) { createTeam = true }
                        2 -> StatisticsScreen(model)
                        else -> ProfileScreen(model) {tab=1}
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
        TeamDialog(model.busy, { createTeam = false }) { name, roster ->
            model.createTeam(name, roster) {
                createTeam = false
                tab = 0
            }
        }
    if (newGame && model.teams.isNotEmpty())
        NewGameDialog(
            model.teams.sortedBy { it.id != model.currentTeam?.id },
            model.rosters,
            model.busy,
            { newGame = false },
        ) { prepared ->
            model.createPreparedGame(prepared) { newGame = false }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(UiSpacing.cardInset),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.card),
            content = content,
        )
    }
}

@Composable
private fun HomeScreen(model: ScorekeeperViewModel, addTeam: () -> Unit, newGame: () -> Unit) {
    val team = model.currentTeam
    val ongoing = model.games.filter { !it.isFinal && !it.isScheduled }
    val scheduled = model.games.filter { it.isScheduled && !it.isFinal }.sortedBy { it.scheduledAt }
    val completed =
        model.games
            .filter { it.isFinal }
            .sortedWith(
                compareByDescending<Game> {
                        it.playedAt ?: it.startedAt ?: it.scheduledAt ?: it.createdAt
                    }
                    .thenBy { it.id }
            )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (team != null) TeamMark(team.name, 56)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                team?.name ?: "尚未创建球队",
                Modifier.testTag("home-team-name"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                "${java.time.Year.now()} 年 · ${team?.players?.count { !it.archived } ?: 0} 名球员",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                HomeSummary.record(model.games, team?.id).label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.testTag("home-team-record")
                        .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
    if (team == null)
        OutlinedButton(
            onClick = addTeam,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("create-team"),
        ) {
            Text("创建球队", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    Button(
        onClick = if (team == null) addTeam else newGame,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("new-game"),
    ) {
        Icon(Icons.Outlined.AddCircle, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (model.games.isEmpty()) "开始新比赛" else "再开始一场比赛",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    SectionTitle("正在进行", if (ongoing.isEmpty()) null else "${ongoing.size} 场")
    TextButton({ model.practice() }, modifier = Modifier.testTag("start-practice")) { Text("练习记分") }
    if (ongoing.isEmpty())
        EmptyCard("没有进行中的比赛", "新比赛创建后会自动保存在这里，可在 App 重启后继续。", Icons.Outlined.PlayArrow)
    ongoing.forEach { game ->
        CardBlock(Modifier.testTag("game-${game.id}").clickable { model.open(game.id) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${game.away.name}  vs  ${game.home.name}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${game.inning}局${if (game.isTop) "上" else "下"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "▶ 继续比赛",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("continue-game"),
                    )
                }
                Text(
                    "${game.awayRuns} : ${game.homeRuns}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
    if (scheduled.isNotEmpty()) {
        SectionTitle("比赛预告", "${scheduled.size} 场")
        scheduled.forEach { game ->
            CardBlock(Modifier.testTag("game-${game.id}").clickable { model.open(game.id) }) {
                Text("${game.away.name}  vs  ${game.home.name}", fontWeight = FontWeight.Bold)
                Text("预告比赛 · 查看详情", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    SectionTitle("最近比赛", "按比赛日期排序")
    if (completed.isEmpty()) EmptyCard("还没有已完成的比赛", "比赛结束并保存后，会按时间显示在这里。", Icons.Outlined.DateRange)
    completed.forEach { game ->
        CardBlock(Modifier.testTag("game-${game.id}").clickable { model.open(game.id) }) {
            Text("${game.away.name}  vs  ${game.home.name}", fontWeight = FontWeight.Bold)
            Text(
                "${game.awayRuns} : ${game.homeRuns}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
            Text("已结束 · 查看完整记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TeamMark(name: String, size: Int) {
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
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (detail != null)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(
    title: String,
    detail: String,
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
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        FilledTonalButton(onClick = add, modifier = Modifier.testTag("add-team")) { Text("创建球队") }
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
private fun ProfileScreen(model: ScorekeeperViewModel, manageTeams: ()->Unit) {
    var showRules by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable {mutableStateOf<String?>(null)}
    if (showRules) RulesScreen(showExamples=model.showRuleExamples) { showRules = false }
    Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    CardBlock {
        Text("棒球比赛记录大师", fontWeight = FontWeight.Bold)
        Text("Android ${BuildConfig.VERSION_NAME}")
        Text("开发预览版", color = MaterialTheme.colorScheme.primary)
        Text("默认仅本机记分；进入球队共享后连接球队服务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    CardBlock {
        Text("棒球规则查询", fontWeight = FontWeight.Bold)
        Text("常用规则解释与完整规则 PDF", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { showRules = true }, modifier = Modifier.testTag("open-rules")) { Text("查看规则") }
        TextButton(onClick={model.practice()},modifier=Modifier.testTag("open-practice-inning")) {Text("练习一局")}
    }
    CardBlock {
        Text("本机数据与设置",fontWeight=FontWeight.Bold)
        TextButton(onClick={page="data"},modifier=Modifier.testTag("open-local-data")) {Text("本地数据与备份")}
        TextButton(onClick={page="settings"},modifier=Modifier.testTag("open-app-settings")) {Text("App 设置")}
        TextButton(onClick={page="about"},modifier=Modifier.testTag("open-app-about")) {Text("关于 BaseballMaster")}
    }
    when(page) {
        "data"->LocalDataScreen(model,{page=null;manageTeams()}) {page=null}
        "settings"->AppSettingsScreen(model) {page=null}
        "about"->AppAboutScreen {page=null}
    }
}

private val SampleRoster =
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
    val dark = MaterialTheme.colorScheme.background.luminance()<.5f
    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,securePolicy=if(secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        PortraitViewport("portrait-form") {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding()
                    .imePadding()
                    .semantics { testTagsAsResourceId = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = dismiss, enabled = !busy) { Text(dismissLabel, fontSize = 16.sp) }
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    if (saveLabel.isNotBlank())
                        TextButton(
                            onClick = save,
                            enabled = enabled && !busy,
                            modifier = Modifier.testTag(saveTag),
                        ) {
                            Text(
                                if (busy) "保存中…" else saveLabel,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    else Spacer(Modifier.width(60.dp))
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = UiSpacing.formVerticalInset),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.form),
                    content = content,
                )
            }
            }
        }
    }
}

@Composable
private fun TeamDialog(busy: Boolean, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var roster by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    FormPage(
        "创建球队",
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
private fun GameScreen(
    game: Game,
    busy: Boolean,
    back: () -> Unit,
    pitch: (PitchAction) -> Unit,
    controls: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            IconButton(
                onClick = back,
                modifier = Modifier.align(Alignment.CenterStart).testTag("back-home"),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    "返回比赛",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "现场记分",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${game.inning}局${if (game.isTop) "上" else "下"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            game.away.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, false),
                        )
                        Text(
                            "${game.awayRuns} : ${game.homeRuns}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            game.home.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, false),
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        game.rules.mode.title,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MiniCount("B", game.balls, 3, Accent, "坏球")
                        MiniCount("S", game.strikes, 2, Color(0xFFA35513), "好球")
                        MiniCount("O", game.outs, 2, Color(0xFFC7463D), "出局")
                    }
                }
            }
        }
        var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(game.id) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                clockNow = System.currentTimeMillis()
            }
        }
        if (game.clockHasStarted || game.rules.timed)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val seconds =
                    (if (game.clockDisplayMode == "remaining")
                        game.remainingTime(clockNow) ?: game.elapsed(clockNow)
                    else game.elapsed(clockNow)) / 1000
                Text(
                    "${if(game.clockDisplayMode=="remaining")"剩余"else"已用"} ${seconds/60}:${"%02d".format(kotlin.math.abs(seconds%60))} · ${if(game.clockRunningSince!=null)"计时中"else"暂停"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        game.ruleNotices(clockNow).forEach {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                Field(game, Modifier.height(330.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "投手 · 已投 ${game.pitching[game.pitcher.id]?.pitches?:0} 球",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "#${game.pitcher.number} ${game.pitcher.name}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "第 ${game.batterIndex + 1} 棒",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "#${game.batter.number} ${game.batter.name}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PitchAction.entries.forEach { action ->
                val color =
                    when (action) {
                        PitchAction.ball -> Accent
                        PitchAction.calledStrike,
                        PitchAction.swingingStrike -> Color(0xFF9F4C0D)
                        else -> Color(0xFF102A43)
                    }
                val title =
                    when (action) {
                        PitchAction.calledStrike -> "好球 · 看"
                        PitchAction.swingingStrike -> "好球 · 挥空"
                        else -> action.title
                    }
                Button(
                    onClick = { pitch(action) },
                    enabled =
                        !busy && !game.isFinal && !game.isScheduled && game.pendingDecision == null,
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("pitch-${action.name}"),
                ) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            "已保存 ${game.totalPitches} 球",
            modifier = Modifier.fillMaxWidth().testTag("save-status").padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        controls()
    }
}

@Composable
private fun MiniCount(label: String, value: Int, maximum: Int, color: Color, tag: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Black,
        )
        // Numeric semantics preserve the same accessible count while the display follows iOS dots.
        Box(
            Modifier.testTag("count-$tag").semantics {
                text = androidx.compose.ui.text.AnnotatedString("$value")
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(maximum) { index ->
                    Box(
                        Modifier.size(8.dp)
                            .background(
                                if (index < value) color else Color(0xFFDADFD9),
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
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(field.copy(alpha = .86f))
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val home = Offset(w / 2, h - 48)
        val radius =
            min((w / 2 - min(24f, max(14f, w * .04f))) * sqrt(2f) * 1.30f, (home.y - 40) / .62f)
                .coerceAtLeast(1f)
        val d =
            min(
                w * .18f,
                min(max(132f, home.y - max(34f, h * .40f)) / 2, max(1f, (home.y - 74) / 2)),
            )
        val center = Offset(w / 2, home.y - d)
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / w
            fun point(p: Offset) = p * scale
            val homePx = point(home)
            val r = radius * scale
            val sector =
                Path().apply {
                    moveTo(homePx.x, homePx.y)
                    arcTo(
                        Rect(homePx.x - r, homePx.y - r, homePx.x + r, homePx.y + r),
                        225f,
                        90f,
                        false,
                    )
                    close()
                }
            drawPath(sector, field)
            drawPath(sector, dirt.copy(alpha = .9f), style = Stroke(8.dp.toPx()))
            val offset = r / sqrt(2f)
            drawLine(
                Color.White.copy(alpha = .82f),
                homePx,
                homePx + Offset(-offset, -offset),
                1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            drawLine(
                Color.White.copy(alpha = .82f),
                homePx,
                homePx + Offset(offset, -offset),
                1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            fun diamond(at: Offset, depth: Float) =
                Path().apply {
                    moveTo(at.x, at.y + depth)
                    lineTo(at.x + depth, at.y)
                    lineTo(at.x, at.y - depth)
                    lineTo(at.x - depth, at.y)
                    close()
                }
            drawPath(diamond(point(center), d * 1.25f * scale), dirt)
            drawPath(diamond(point(center), d * .58f * scale), field)
            drawPath(
                diamond(point(center), d * scale),
                Color.White.copy(alpha = .72f),
                style = Stroke(2.dp.toPx()),
            )
            val bases =
                listOf(
                    Offset(center.x + d, center.y),
                    Offset(center.x, center.y - d),
                    Offset(center.x - d, center.y),
                )
            bases.forEachIndexed { i, at ->
                drawPath(
                    diamond(point(at), (if (game.bases[i] == null) 7f else 13f) * scale),
                    if (game.bases[i] == null) Color.White else Color(0xFFFABD29),
                )
            }
            drawPath(diamond(homePx, 8 * scale), Color.White)
        }
        val cfY = max(29f, home.y - radius * .65f)
        val spots =
            listOf(
                center,
                Offset(home.x, min(h - 18, home.y + 30)),
                Offset(min(w - 38, center.x + d + 66), center.y + 35),
                Offset(center.x + max(66f, d * 1.1f), center.y - d * .72f),
                Offset(max(38f, center.x - d - 66), center.y + 35),
                Offset(center.x - max(66f, d * 1.1f), center.y - d * .72f),
                Offset(w * .19f, max(51f, home.y - radius * .58f)),
                Offset(w * .5f, cfY),
                Offset(w * .81f, max(51f, home.y - radius * .58f)),
                Offset(w * .5f, (cfY + center.y - d) / 2),
            )
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
