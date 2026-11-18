package cc.baseballmaster.scorekeeper

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.AnalysisAuthorizationRequired
import cc.baseballmaster.core.AnalysisInvalidReport
import cc.baseballmaster.core.AnalysisScopeLimit
import cc.baseballmaster.core.AnalysisSource
import cc.baseballmaster.core.TeamServiceFault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AnalysisEntry(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun analysisMessage(error: Exception): String = when (error) {
    is TeamServiceFault -> when (error.code) {
        "analysis_expired" -> "这份分析已过期，请重新生成。"
        "analysis_unavailable" -> "比赛分析服务暂未开放。"
        "analysis_invalid" -> "比赛分析资料无效或超过处理上限。"
        "analysis_conflict" -> "这次分析的比赛范围已改变，请重新生成。"
        "analysis_busy" -> "比赛分析正在处理，请稍后重试。"
        "analysis_limit" -> "今日比赛分析次数已用完。"
        else -> error.message ?: "暂时无法连接比赛分析服务，请重试。"
    }
    is AnalysisProviderFailure, is AnalysisTimeout, is AnalysisAuthorizationRequired, is AnalysisScopeLimit, is AnalysisInvalidReport ->
        error.message ?: "比赛分析暂时无法生成，请重试。"
    else -> "比赛分析暂时无法生成，请重试。"
}

@Composable
internal fun AnalysisScreen(source: AnalysisSource, sharing: AndroidTeamSharing, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = source.input.scope.title
    var requestID by rememberSaveable(source.id) { mutableStateOf(UUID.randomUUID().toString()) }
    var generating by remember(source.id) { mutableStateOf(false) }
    var issue by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var pdfPath by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var text by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportNotice by remember { mutableStateOf<String?>(null) }

    fun export(action: suspend () -> Unit) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                exportNotice = "报告导出未完成，请重试。"
            } finally {
                exporting = false
            }
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val path = pdfPath
        if (uri != null && path != null) export {
            withContext(Dispatchers.IO) {
                File(path).inputStream().use { input ->
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                    output.use { input.copyTo(it) }
                }
            }
            exportNotice = "已保存"
        }
    }
    val path = pdfPath
    val plainText = text
    if (path != null && plainText != null) {
        val file = File(path)
        ReportPdfPreview(file, title, exporting, onClose = close, onSave = { save.launch(file.name) },
            onShare = { export { ReportExport.share(context, file) } },
            onShareText = { export { shareAnalysisText(context, title, plainText) } })
    } else {
        ProductPage(title, onBack = close, backLabel = "关闭", backTag = "close-game-analysis", modal = true) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.title, Modifier.testTag("game-analysis-source"), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(source.subtitle, Modifier.testTag("game-analysis-scope"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (generating) {
                Row(Modifier.testTag("game-analysis-progress"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在分析比赛…")
                }
            } else {
                Text("点击生成后，比赛统计、关键局面和球员代号会经球队服务器发送给第三方 AI 服务 V-API。球员姓名留在本机。",
                    Modifier.testTag("game-analysis-privacy"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                issue?.let { Text(it, Modifier.testTag("game-analysis-error"), color = MaterialTheme.colorScheme.error) }
                PrimaryProductButton(if (issue == null) "生成比赛分析" else "重新生成", "generate-game-analysis") {
                    if (!generating) {
                        generating = true
                        issue = null
                        scope.launch {
                            try {
                                val report = sharing.session.generateAnalysis(source.input, requestID)
                                val document = AnalysisDocument(source, report)
                                val file = withContext(Dispatchers.IO) { document.write(context) }
                                text = document.plainText()
                                pdfPath = file.path
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Exception) {
                                if (failure is AnalysisProviderFailure ||
                                    failure is TeamServiceFault && failure.code in setOf("analysis_expired", "analysis_conflict")) {
                                    requestID = UUID.randomUUID().toString()
                                }
                                issue = analysisMessage(failure)
                            } finally {
                                generating = false
                            }
                        }
                    }
                }
            }
        }
    }
    exportNotice?.let { notice ->
        AlertDialog(onDismissRequest = { exportNotice = null }, title = { Text(notice) },
            confirmButton = { TextButton({ exportNotice = null }) { Text("完成") } })
    }
}

private fun shareAnalysisText(context: Context, title: String, contents: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, contents)
    context.startActivity(Intent.createChooser(send, "分享文字").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
