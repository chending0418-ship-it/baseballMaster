package cc.baseballmaster.scorekeeper

import android.content.Context
import androidx.compose.runtime.*
import cc.baseballmaster.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidLive(context: Context,private val local: GameRepository,private val scope: CoroutineScope,private val recoveryRequired: ()->Boolean) {
    private val vault=CredentialVault(context.applicationContext)
    private val coordinator=StandaloneLive(LiveCredentialFile(File(context.noBackupFilesDir,"standalone-live"),vault::stableEncryptionSecret),LiveHttpTransport())
    private val mutex=Mutex();private val requests=Channel<Unit>(Channel.CONFLATED)
    private var ticker: Job?=null
    var bindings by mutableStateOf<List<LiveBinding>>(emptyList());private set
    var messages by mutableStateOf<Map<String,String>>(emptyMap());private set
    var urls by mutableStateOf<Map<String,String>>(emptyMap());private set
    var error by mutableStateOf<String?>(null);private set
    var busy by mutableStateOf(false);private set
    init {scope.launch {for(unit in requests) mutex.withLock {execute {
        val games=local.games.first().map(GameRepository::decode).associateBy {it.id}
        coordinator.synchronize(games,recoveryRequired())
    }}}}
    private suspend fun execute(action: suspend ()->Unit) {
        busy=true
        try {
            val result=withContext(Dispatchers.IO) {
                action();val rows=coordinator.bindings()
                Triple(rows,coordinator.messages.toMap(),rows.mapNotNull {b -> coordinator.url(b.gameID)?.let {b.gameID to it}}.toMap())
            }
            bindings=result.first;messages=result.second;urls=result.third;error=coordinator.credentialError
        } catch(cancel: CancellationException) {throw cancel}
        catch(_: Exception) {error="直播状态未能确认，原本机比赛和凭证已保留；请重试或重启 App 核对。";urls=emptyMap()}
        finally {busy=false}
    }
    fun requestSync() {requests.trySend(Unit)}
    fun setForeground(active: Boolean) {
        ticker?.cancel();ticker=null
        requestSync()
        if(active) ticker=scope.launch {while(isActive) {delay(10_000);requestSync()}}
    }
    fun start(game: Game) {scope.launch {mutex.withLock {execute {coordinator.start(game)}};requestSync()}}
    fun close(id: String) {scope.launch {mutex.withLock {execute {coordinator.close(id)}};requestSync()}}
    fun retry(id: String) {scope.launch {mutex.withLock {execute {coordinator.retry(id)}};requestSync()}}
    suspend fun prepareForRestore() = withContext(Dispatchers.IO) {coordinator.prepareForRestore()}
}
