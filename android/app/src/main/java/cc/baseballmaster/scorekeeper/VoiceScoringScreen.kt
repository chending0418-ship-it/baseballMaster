package cc.baseballmaster.scorekeeper

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.baseballmaster.core.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

private fun Throwable.voiceMessage() =
    (this as? VoiceIssue)?.message ?: "语音操作未完成，请重试或继续手动记分。"

@Composable
internal fun VoiceSettings(access: VoiceAccess) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(access) {
        try { access.refresh() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { issue = e.voiceMessage() }
    }
    Text("语音记分", style = MaterialTheme.typography.titleSmall)
    CardBlock {
        Text(if (access.token == null) "未开通 · 使用一次性开启码开通" else "已开通 · 在现场记分时可使用语音")
        TextButton({ editing = true; issue = null }, Modifier.testTag("open-voice-settings"), enabled = !busy) {
            Text(if (access.token == null) "输入开启码" else "更换开启码")
        }
        if (!editing) issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (editing) AlertDialog(
        onDismissRequest = { if (!busy) { editing = false; code = ""; issue = null } },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("开通语音记分") },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.take(80) }, Modifier.fillMaxWidth().testTag("voice-code"),
                    label = { Text("一次性开启码") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation())
                issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank() && !busy, modifier = Modifier.testTag("redeem-voice-code"), onClick = {
                busy = true
                issue = null
                scope.launch {
                    try { access.redeem(code); code = ""; editing = false }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { issue = e.voiceMessage() }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在开通…" else "开通") }
        },
        dismissButton = { TextButton({ editing = false; code = ""; issue = null }, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun VoiceScoringScreen(
    game: Game,
    access: VoiceAccess,
    matches: () -> Boolean,
    busy: Boolean,
    dismiss: () -> Unit,
    confirm: (Command) -> Unit,
    recording: VoiceRecording = remember(access) { TencentVoiceRecording(access.service) },
) {
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val currentMatches by rememberUpdatedState(matches)
    var task by remember { mutableStateOf<Job?>(null) }
    var phase by remember { mutableStateOf<VoicePhase?>(null) }
    var stop by remember { mutableStateOf(AtomicBoolean(false)) }
    var text by remember { mutableStateOf("") }
    var finalized by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<VoiceAnalysis?>(null) }
    var proposal by remember { mutableStateOf<Command?>(null) }
    var consent by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    val payload = remember(game.id, game.revision) { game.voiceContext() }

    fun cancel(interrupted: Boolean = false) {
        val wasWorking = task != null || permissionPending
        task?.cancel()
        task = null
        permissionPending = false
        if (phase != null) { finalized = false; text = "" }
        phase = null
        analyzing = false
        consent = false
        if (wasWorking && interrupted) issue = "语音操作已中断，可重试或返回手动记分。"
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancel(interrupted = true)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancel() }
    }

    fun launch(credential: String, onFinish: () -> Unit, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                runCatching { access.report(e, credential) }
                issue = e.voiceMessage()
            } finally {
                if (task === coroutineContext.job) {
                    onFinish()
                    task = null
                }
            }
        }
        task = job
        job.start()
    }

    fun start() {
        val credential = access.token ?: return
        if (!currentMatches() || task != null) { issue = "比赛局面已变化，请返回记分。"; return }
        issue = null
        finalized = false
        text = ""
        analysis = null
        proposal = null
        val thisStop = AtomicBoolean(false)
        stop = thisStop
        phase = VoicePhase.connecting
        launch(credential, onFinish = { phase = null }) {
            val result = recording.run(payload, credential, thisStop, { phase = it }, { text = it.take(4000) })
            if (!currentMatches() || access.token != credential) throw VoiceIssue("比赛局面或语音权限已变化，请返回记分。")
            text = result.take(4000)
            finalized = text.isNotBlank()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (permissionPending) {
            permissionPending = false
            if (allowed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
            else issue = "未取得麦克风权限，可在系统设置中开启，或返回手动记分。"
        }
    }

    fun analyze() {
        val credential = access.token ?: return
        if (!currentMatches() || task != null || !finalized) return
        val sent = text.trim()
        if (sent.isEmpty()) return
        analyzing = true
        issue = null
        analysis = null
        proposal = null
        launch(credential, onFinish = { analyzing = false }) {
            val response = access.service.analyze(sent, payload, credential)
            analysis = response
            if (!currentMatches() || access.token != credential) throw VoiceIssue("比赛局面或语音权限已变化，建议仅供人工核对。")
            proposal = voiceProposal(response, game, sent)
        }
    }

    ProductPage("语音记分", busy = busy, onBack = dismiss, backLabel = "返回比赛", modal = true, secure = true) {
        Text("${game.inning}局${if (game.isTop) "上" else "下"} · ${game.outs} 出局 · 打者 ${game.batter.name}")
        when (phase) {
            VoicePhase.connecting -> Text("正在连接语音识别…")
            VoicePhase.recording -> Button({ stop.set(true) }, Modifier.testTag("stop-voice-recording")) { Text("结束录音") }
            VoicePhase.finishing -> Text("正在接收最终转写…")
            null -> Button({ consent = true }, Modifier.testTag("start-voice-recording"),
                enabled = !busy && !analyzing && !permissionPending && access.token != null && currentMatches()) {
                Text(if (text.isEmpty()) "开始录音" else "重新录音")
            }
        }
        if (phase != null || analyzing) TextButton({ cancel() }) { Text(if (analyzing) "取消解析" else "取消录音") }
        OutlinedTextField(text, { text = it.take(4000); analysis = null; proposal = null },
            Modifier.fillMaxWidth().testTag("voice-transcript"), label = { Text("语音转写") },
            readOnly = !finalized || analyzing || phase != null, minLines = 3, maxLines = 8)
        TextButton(::analyze, Modifier.testTag("analyze-voice-play"),
            enabled = finalized && text.isNotBlank() && task == null && access.token != null && currentMatches()) {
            Text(if (analyzing) "正在解析本球…" else "解析局面")
        }
        issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        analysis?.let { result -> CardBlock {
            Text("核对本球", style = MaterialTheme.typography.titleMedium)
            Text(result.summary)
            result.observations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            val candidate = proposal
            if (candidate == null || !currentMatches() || access.token == null) {
                Text("请人工核对这个局面，然后返回手动记分。")
            } else {
                Text(candidate.pitch?.title ?: candidate.outcome?.title.orEmpty())
                candidate.decisions.orEmpty().forEach {
                    val player = game.battingTeam.players.firstOrNull { p -> p.id == it.playerID }
                    val destination = listOf("出局", "留在原垒", "一垒", "二垒", "三垒", "得分")[it.destination + 1]
                    Text("${player?.name.orEmpty()} · $destination")
                }
                val pro = candidate.kind == "play" && game.rules.recordingMethod == "situation"
                val missing = candidate.observation?.precision == "unknown"
                Button({ if (currentMatches() && !busy && access.token != null) confirm(candidate) },
                    Modifier.testTag("confirm-voice-play"), enabled = !busy) {
                    Text(if (!pro) "确认记录" else if (missing) "到球场补落点" else "到球场核对")
                }
            }
        } }
    }
    if (consent) AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = { consent = false }, title = { Text("使用语音记分") },
        text = { Text("录音将直接发送给腾讯云语音识别，当前局面会发给我们的服务。点「解析局面」后，转写和当前局面（可能含球员姓名、背号）会发给腾讯云混元 AI 生成记分建议。你也可以取消并手动记分。") },
        confirmButton = { TextButton({
            consent = false
            if (currentMatches() && access.token != null) {
                permissionPending = true
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }, Modifier.testTag("accept-voice-consent")) { Text("同意并开始录音") } },
        dismissButton = { TextButton({ consent = false }, Modifier.testTag("cancel-voice-consent")) { Text("取消") } },
    )
}
