package cc.baseballmaster.scorekeeper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private data class RuleItem(val category: String, val term: String, val abbreviation: String, val explanation: String, val example: String)
private data class RuleLine(val text: String, val x: Float, val y: Float, val width: Float, val height: Float)
private data class RulePage(val width: Float, val height: Float, val lines: List<RuleLine>)
private data class RuleIndex(val sha256: String, val pages: List<RulePage>)
private data class RuleMatch(val page: Int, val line: RuleLine)

private fun readGlossary(context: Context): List<RuleItem> {
    val items = JSONArray(context.resources.openRawResource(R.raw.baseball_rule_glossary).bufferedReader().use { it.readText() })
    return (0 until items.length()).map { i -> items.getJSONObject(i).let {
        RuleItem(it.getString("category"), it.getString("term"), it.getString("abbreviation"), it.getString("explanation"), it.getString("example"))
    } }
}

private fun readRuleIndex(context: Context): RuleIndex {
    val root = JSONObject(context.resources.openRawResource(R.raw.baseball_rules_index).bufferedReader().use { it.readText() })
    val pages = root.getJSONArray("pages")
    return RuleIndex(root.getString("pdfSHA256"), (0 until pages.length()).map { pageIndex ->
        val page = pages.getJSONObject(pageIndex)
        val lines = page.getJSONArray("lines")
        RulePage(page.getDouble("width").toFloat(), page.getDouble("height").toFloat(), (0 until lines.length()).map { lineIndex ->
            val line = lines.getJSONObject(lineIndex)
            RuleLine(line.getString("text"), line.getDouble("x").toFloat(), line.getDouble("y").toFloat(), line.getDouble("width").toFloat(), line.getDouble("height").toFloat())
        })
    })
}

private fun findRuleMatches(index: RuleIndex, query: String): List<RuleMatch> = buildList {
    if (query.isNotEmpty()) index.pages.forEachIndexed { page, p -> p.lines.forEach { line ->
        var start = 0
        while (true) {
            val found = line.text.indexOf(query, start, ignoreCase = true)
            if (found < 0) break
            add(RuleMatch(page, line))
            start = found + query.length
        }
    } }
}

@Composable
internal fun RulesScreen(showExamples: Boolean = true, dismiss: () -> Unit) {
    val context = LocalContext.current
    val items = remember { readGlossary(context) }
    val categories = listOf("basics" to "基础", "offense" to "进攻", "defense" to "守备", "pitching" to "投手", "violations" to "违规", "scoring" to "记录")
    var category by rememberSaveable { mutableStateOf("basics") }
    var reading by rememberSaveable { mutableStateOf(false) }
    FormPage("棒球规则查询", false, true, "", "", dismiss, {}) {
        CardBlock {
            TextButton(onClick = { reading = true }, modifier = Modifier.fillMaxWidth().testTag("open-rule-pdf")) {
                Column(Modifier.fillMaxWidth()) {
                    Text("查看完整《棒球规则 2022 版》", fontWeight = FontWeight.Bold)
                    Text("中国棒球协会审定 · 229 页 · 离线阅读", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("以下为便于现场理解的简化说明；正式比赛以现场裁判、赛事规程和最新规则为准。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("常用规则与术语 · ${items.count { it.category == category }} 项", fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { (key, label) ->
                FilterChip(selected = category == key, onClick = { category = key }, label = { Text(label) }, modifier = Modifier.testTag("rule-category-$label"))
            }
        }
        items.filter { it.category == category }.forEach { item ->
            CardBlock {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.term, fontWeight = FontWeight.Bold)
                    Text(item.abbreviation, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
                Text(item.explanation, fontSize = 14.sp)
                if(showExamples) Text("例如：${item.example}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
    if (reading) RulePDFReader { reading = false }
}

private fun rulePDFFile(context: Context, expectedHash: String): File {
    val file = File(context.cacheDir, "baseball-rules-2022.pdf")
    fun hash(candidate: File): String = candidate.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    if (!file.exists() || hash(file) != expectedHash) {
        val temporary = File.createTempFile("rules-", ".pdf", context.cacheDir)
        try {
            context.resources.openRawResource(R.raw.baseball_rules_2022).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(hash(temporary) == expectedHash) { "规则文件校验失败" }
            check(temporary.renameTo(file)) { "无法准备规则文件" }
        } finally { temporary.delete() }
    }
    return file
}

@Composable
private fun RulePDFReader(dismiss: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var matchIndex by rememberSaveable { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val index by produceState<RuleIndex?>(null) { value = withContext(Dispatchers.IO) { readRuleIndex(context) } }
    val matches = remember(index, submitted) { index?.let { findRuleMatches(it, submitted) } ?: emptyList() }
    var failure by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, index, page) {
        value = null
        val current = index ?: return@produceState
        try {
            value = withContext(Dispatchers.IO) {
                val file = rulePDFFile(context, current.sha256)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        check(renderer.pageCount == current.pages.size)
                        renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdfPage ->
                            val width = 1200
                            val result = Bitmap.createBitmap(width, (width.toFloat() * pdfPage.height / pdfPage.width).toInt(), Bitmap.Config.ARGB_8888)
                            result.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result
                        }
                    }
                }
            }
            failure = null
        } catch (e: Exception) { failure = e.localizedMessage ?: "无法打开规则文件" }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PortraitViewport("portrait-rule-pdf") {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = dismiss) { Text("返回") }
                    Text("棒球规则 2022", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${index?.pages?.size ?: 229} 页", fontSize = 12.sp, modifier = Modifier.testTag("rule-pdf-page-count"))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(search, { search = it }, modifier = Modifier.weight(1f).testTag("rule-pdf-search-field"), singleLine = true, placeholder = { Text("输入单字或关键词") })
                    TextButton(onClick = { submitted = search.trim(); matchIndex = 0; matchesFor(index, search.trim()).firstOrNull()?.let { page = it.page } }, enabled = search.isNotBlank(), modifier = Modifier.testTag("rule-pdf-search-button")) { Text("查找") }
                }
                if (submitted.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (matches.isEmpty()) "未找到“$submitted”" else "第 ${matchIndex + 1} / ${matches.size} 处", modifier = Modifier.weight(1f).testTag("rule-pdf-search-status"), fontSize = 12.sp)
                    TextButton(onClick = { matchIndex = (matchIndex - 1 + matches.size) % matches.size; page = matches[matchIndex].page }, enabled = matches.isNotEmpty(), modifier = Modifier.testTag("rule-pdf-previous-match")) { Text("上一处") }
                    TextButton(onClick = { matchIndex = (matchIndex + 1) % matches.size; page = matches[matchIndex].page }, enabled = matches.isNotEmpty(), modifier = Modifier.testTag("rule-pdf-next-match")) { Text("下一处") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { page-- }, enabled = page > 0, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("rule-pdf-previous-page")) { Text("‹ 上页") }
                    Text("${page + 1} / ${index?.pages?.size ?: 229}", modifier = Modifier.testTag("rule-pdf-current-page"), fontSize = 12.sp)
                    TextButton(onClick = { page++ }, enabled = page < (index?.pages?.lastIndex ?: 0), contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("rule-pdf-next-page")) { Text("下页 ›") }
                    TextButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f) }, enabled = zoom > 1f, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("rule-pdf-zoom-out")) { Text("−") }
                    Text("${(zoom * 100).toInt()}%", fontSize = 12.sp)
                    TextButton(onClick = { zoom = (zoom + .5f).coerceAtMost(3f) }, enabled = zoom < 3f, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("rule-pdf-zoom-in")) { Text("＋") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    val vertical = rememberScrollState()
                    val horizontal = rememberScrollState()
                    val density = LocalDensity.current
                    val image = bitmap
                    val metadata = index?.pages?.getOrNull(page)
                    val selected = matches.getOrNull(matchIndex)?.takeIf { it.page == page }
                    val pageWidth = maxWidth * zoom
                    LaunchedEffect(page, matchIndex, image, zoom, selected) {
                        if (image != null && selected != null && metadata != null) {
                            val imageHeightPx = with(density) { pageWidth.toPx() } * image.height / image.width
                            val top = imageHeightPx * (metadata.height - selected.line.y - selected.line.height) / metadata.height
                            vertical.scrollTo((top - 80).toInt().coerceIn(0, vertical.maxValue))
                        }
                    }
                    Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal), contentAlignment = Alignment.TopStart) {
                        if (image != null) Box(Modifier.width(pageWidth).aspectRatio(image.width.toFloat() / image.height).testTag("rule-pdf-reader")) {
                            Image(image.asImageBitmap(), null, Modifier.fillMaxSize())
                            if (selected != null && metadata != null) Canvas(Modifier.fillMaxSize()) {
                                val line = selected.line
                                drawRect(Color.Yellow.copy(alpha = .36f), Offset(size.width * line.x / metadata.width, size.height * (metadata.height - line.y - line.height) / metadata.height), Size(size.width * line.width / metadata.width, size.height * line.height / metadata.height))
                            }
                        } else Text(failure ?: "正在加载…", modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}

private fun matchesFor(index: RuleIndex?, query: String) = index?.let { findRuleMatches(it, query) } ?: emptyList()
