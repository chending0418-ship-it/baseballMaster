package cc.baseballmaster.scorekeeper

import android.content.Context
import cc.baseballmaster.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal var androidTeamBroadcastOverlayBaseTestOverride: String? = null

/** Keeps only the explicitly selected match IDs on this device. Fixed codes stay in transient UI state. */
internal class TeamBroadcastCoordinator(
    context: Context,
    private val sharing: AndroidTeamSharing,
    private val scope: CoroutineScope,
    private val canSync: () -> Boolean,
    private val localGame: (String) -> Game?,
    private val activeLocalGame: () -> Game? = { null },
) {
    private data class Selection(val teamID: String, val gameID: String, val shared: Boolean)

    private val preferences = context.getSharedPreferences("team-fixed-broadcast", Context.MODE_PRIVATE)
    private var selection = preferences.getString("team", null)?.let { team ->
        preferences.getString("game", null)?.let { game -> Selection(team, game, preferences.getBoolean("shared", false)) }
    }
    private var restoredGames = preferences.getStringSet("restored-games", emptySet()).orEmpty().toSet()
    private var restoreReview = preferences.getBoolean("restore-review", false)
    private var restoring = false
    private val lock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var foregroundTicker: Job? = null
    private var foreground = false

    init {
        scope.launch {
            for (request in requests) {
                if (foreground && canSync()) refreshSelection()
            }
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        foregroundTicker?.cancel()
        foregroundTicker = if (active) scope.launch {
            while (true) {
                requests.trySend(Unit)
                delay(15_000)
            }
        } else null
    }

    fun requestSync(id: String) {
        if (foreground && (activeLocalGame()?.id == id || selection?.let { !it.shared && it.gameID == id } == true)) {
            requests.trySend(Unit)
        }
    }

    suspend fun clearBinding() = lock.withLock {
        restoring = true
        restoreReview = true
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("restore-review", true).remove("team").remove("game").remove("shared").commit()) {
                "转播绑定暂时无法清除。"
            }
        }
        selection = null
    }

    suspend fun finishRestore() = lock.withLock {
        try { rememberRestoredGames() } finally { restoring = false }
    }

    private suspend fun rememberRestoredGames() = withContext(Dispatchers.IO) {
        val ids = sharing.local.games.first().mapTo(hashSetOf()) { it.id }
        check(preferences.edit().putStringSet("restored-games", ids).remove("restore-review").commit()) {
            "恢复的比赛仍需重新选择转播。"
        }
        restoredGames = ids
        restoreReview = false
    }
    suspend fun target(game: Game) = withContext(Dispatchers.IO) { sharing.session.broadcastTarget(game) }
    suspend fun sources(teamID: String) = withContext(Dispatchers.IO) { sharing.session.broadcastSources(teamID) }
    suspend fun rotate(teamID: String, code: String) = withContext(Dispatchers.IO) { sharing.session.rotateBroadcast(teamID, code) }

    fun url(source: BroadcastSource, code: String): String {
        val fixture = if (BuildConfig.DEBUG) androidTeamBroadcastOverlayBaseTestOverride else null
        return if (fixture == null) source.url(code) else source.url(code, fixture)
    }

    suspend fun selectLocal(gameID: String, teamID: String, expected: BroadcastSelection): TeamBroadcastStatus? = lock.withLock {
        check(canSync() && !restoring) { "本机比赛暂时无法读取。" }
        val game = localGame(gameID) ?: return@withLock null
        val target = target(game) ?: return@withLock null
        check(target.teamID == teamID) { "球队关联已变化，请刷新后重新核对。" }
        withContext(Dispatchers.IO) { sharing.session.publishLocalBroadcast(game, expected) }.also { result ->
            if (result?.selected == true) rememberSelection(Selection(teamID, gameID, false))
        }
    }

    suspend fun selectShared(teamID: String, documentID: String, expected: BroadcastSelection): TeamBroadcastStatus? = lock.withLock {
        check(canSync()) { "球队比赛暂时无法读取。" }
        withContext(Dispatchers.IO) { sharing.session.publishSharedBroadcast(teamID, documentID, expected) }.also { result ->
            if (result?.selected == true) rememberSelection(Selection(teamID, documentID, true))
        }
    }

    suspend fun continueAfterHandoff(receipt: CompletedMatchControl) {
        if (receipt.request.kind !in setOf(MatchControl.accept, MatchControl.force)) return
        val team = receipt.request.teamID
        val document = receipt.request.gameID
        lock.withLock {
            try {
                val result = withContext(Dispatchers.IO) {
                    val session = sharing.session
                    val row = session.match(team, document)
                    val sourceID = row.payload?.get("id")?.jsonPrimitive?.contentOrNull ?: return@withContext null
                    if (!session.broadcastSources(team).activeGameID.equals(sourceID, true)) return@withContext null
                    session.publishSharedBroadcast(team, document)
                }
                if (result?.selected == true) rememberSelection(Selection(team, document, true))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The handoff receipt is already saved. The writer can retry the selected source.
            }
        }
    }

    private fun rememberSelection(value: Selection?) {
        selection = value
        val edit = preferences.edit()
        if (value == null) edit.remove("team").remove("game").remove("shared")
        else edit.putString("team", value.teamID).putString("game", value.gameID).putBoolean("shared", value.shared)
        edit.apply()
    }

    private suspend fun refreshSelection() = lock.withLock {
        if (!foreground || !canSync() || restoring) return@withLock
        if (restoreReview) {
            try { rememberRestoredGames() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return@withLock }
        }
        val active = activeLocalGame()
        if (active != null && active.id != selection?.gameID && active.id !in restoredGames &&
            !active.isPregame && !active.isFinal && !active.isScheduled && !active.practice) {
            val started = try {
                withContext(Dispatchers.IO) {
                    val session = sharing.session
                    val target = session.broadcastTarget(active) ?: return@withContext null
                    val reply = session.startLocalBroadcast(active)
                    target.takeIf { reply?.selected == true }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (started != null) {
                rememberSelection(Selection(started.teamID, active.id, false))
                return@withLock
            }
        }
        val current = selection ?: return@withLock
        val game = if (current.shared) null else localGame(current.gameID)
        if (!current.shared && game == null) {
            rememberSelection(null)
            return@withLock
        }
        try {
            val result = withContext(Dispatchers.IO) {
                val session = sharing.session
                if (current.shared) session.publishSharedBroadcast(current.teamID, current.gameID)
                else if (game != null && session.broadcastTarget(game)?.teamID == current.teamID) session.resumeLocalBroadcast(game)
                else null
            }
            if (result?.selected != true) rememberSelection(null)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (fault: TeamServiceFault) {
            if (fault.status in setOf(401, 403, 404, 409)) rememberSelection(null)
        } catch (_: Exception) {
            // A connection failure does not change either the local match or the current selection.
        }
    }
}
