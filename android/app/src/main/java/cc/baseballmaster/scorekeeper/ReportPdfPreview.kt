package cc.baseballmaster.scorekeeper

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun openReport(file: File): PdfRenderer {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        PdfRenderer(descriptor)
    } catch (failure: Exception) {
        descriptor.close()
        throw failure
    }
}

private data class ReportPageBounds(val width: Int, val height: Int) {
    val aspectRatio get() = width.toFloat() / height
}

private fun readReportBounds(file: File): List<ReportPageBounds> = openReport(file).use { renderer ->
    require(renderer.pageCount > 0) { "报告没有可预览的页面" }
    List(renderer.pageCount) { index ->
        renderer.openPage(index).use { ReportPageBounds(it.width, it.height) }
    }
}

private fun renderReportPage(file: File, pageIndex: Int): Bitmap = openReport(file).use { renderer ->
    renderer.openPage(pageIndex).use { page ->
        val ratio = minOf(1_280f / page.width, 1_640f / page.height)
        val output = Bitmap.createBitmap((page.width * ratio).roundToInt().coerceAtLeast(1),
            (page.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        output.eraseColor(AndroidColor.WHITE)
        try {
            page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            output
        } catch (failure: Exception) {
            output.recycle()
            throw failure
        }
    }
}

@Composable
internal fun ReportPdfPreview(
    file: File,
    title: String,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onShareText: (() -> Unit)? = null,
) {
    var zoom by rememberSaveable(file.path) { mutableFloatStateOf(1f) }
    var retry by remember { mutableIntStateOf(0) }
    var pageBounds by remember(file.path, retry) { mutableStateOf<List<ReportPageBounds>?>(null) }
    var issue by remember(file.path, retry) { mutableStateOf(false) }
    var shareChoices by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    var selectedPage by rememberSaveable(file.path) { mutableStateOf<Int?>(null) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(dragged) {
        if (dragged) selectedPage = null
    }
    LaunchedEffect(file.path, retry) {
        try {
            pageBounds = withContext(Dispatchers.IO) { readReportBounds(file) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            issue = true
        }
    }
    ProductPage(title, busy = busy, onBack = onClose, backTag = "report-preview-close", scrollableContent = false) {
        val bounds = pageBounds
        if (bounds != null) {
            if (onShareText != null) {
                Text("A4 竖版 · ${bounds.size} 页 · 可缩放阅读、保存或打印", Modifier.testTag("game-analysis-pdf-summary"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val current by remember(bounds, scroll) {
                derivedStateOf {
                    // At the bottom, multiple short landscape pages can share one scroll position.
                    // Keep the arrow selection as long as that page is still in the viewport.
                    selectedPage?.takeIf { page -> scroll.layoutInfo.visibleItemsInfo.any { it.index == page } }
                        ?: if (!scroll.canScrollForward && scroll.canScrollBackward) bounds.lastIndex
                        else scroll.firstVisibleItemIndex
                }
            }
            fun moveTo(page: Int) {
                scope.launch {
                    scroll.scrollToItem(page)
                    selectedPage = page
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ moveTo(current - 1) }, Modifier.size(48.dp).testTag("report-preview-previous"),
                    enabled = current > 0 && !busy) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一页")
                }
                Text("${current + 1} / ${bounds.size} 页", Modifier.weight(1f).testTag("report-preview-page-count"), style = MaterialTheme.typography.bodySmall)
                IconButton({ moveTo(current + 1) }, Modifier.size(48.dp).testTag("report-preview-next"),
                    enabled = current + 1 < bounds.size && !busy) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一页")
                }
                IconButton({ zoom = (zoom - .25f).coerceAtLeast(1f) },
                    Modifier.size(48.dp).testTag("report-preview-zoom-out").semantics { contentDescription = "缩小" }, enabled = zoom > 1f) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                IconButton({ zoom = 1f }, Modifier.size(48.dp).testTag("report-preview-fit").semantics { contentDescription = "适合宽度" }, enabled = zoom > 1f) {
                    Text("适宽", style = MaterialTheme.typography.labelSmall)
                }
                IconButton({ zoom = (zoom + .25f).coerceAtMost(2.5f) },
                    Modifier.size(48.dp).testTag("report-preview-zoom-in").semantics { contentDescription = "放大" }, enabled = zoom < 2.5f) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("report-preview-body"), contentAlignment = Alignment.Center) {
            when {
                bounds != null -> {
                    val reportWidth = maxWidth * zoom
                    LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).width(reportWidth),
                        state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bounds.size, key = { it }) { index ->
                            ReportPage(file, index, bounds[index])
                        }
                    }
                }
                issue -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂时无法预览这份报告，请重试或返回。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    TextButton({ retry++ }, Modifier.testTag("report-preview-retry")) { Text("重试") }
                }
                else -> CircularProgressIndicator(Modifier.testTag("report-preview-loading"))
            }
        }
        if (bounds != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onSave, Modifier.weight(1f).testTag("report-preview-save"), enabled = !busy) { Text("保存到文件") }
                Box(Modifier.weight(1f)) {
                    PrimaryProductButton("系统分享", "report-preview-share", enabled = !busy, onClick = {
                        if (onShareText == null) onShare() else shareChoices = true
                    })
                    if (onShareText != null) DropdownMenu(shareChoices, { shareChoices = false }) {
                        DropdownMenuItem(text = { Text("分享 PDF") }, onClick = { shareChoices = false; onShare() },
                            modifier = Modifier.testTag("game-analysis-share-pdf"))
                        DropdownMenuItem(text = { Text("分享文字") }, onClick = { shareChoices = false; onShareText() },
                            modifier = Modifier.testTag("game-analysis-share-text"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportPage(file: File, index: Int, bounds: ReportPageBounds) {
    var retry by remember { mutableIntStateOf(0) }
    var rendered by remember(file.path, index, retry) { mutableStateOf<Bitmap?>(null) }
    var issue by remember(file.path, index, retry) { mutableStateOf(false) }
    LaunchedEffect(file.path, index, retry) {
        try {
            rendered = withContext(Dispatchers.IO) { renderReportPage(file, index) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            issue = true
        }
    }

    Surface(Modifier.fillMaxWidth().aspectRatio(bounds.aspectRatio),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            val bitmap = rendered
            when {
                bitmap != null -> {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(image, "报告第 ${index + 1} 页", Modifier.fillMaxSize().testTag(
                        if (index == 0) "report-preview-page" else "report-preview-page-${index + 1}"),
                        contentScale = ContentScale.Fit)
                }
                issue -> TextButton({ retry++ }, Modifier.testTag("report-preview-retry-${index + 1}")) { Text("重新加载第 ${index + 1} 页") }
                else -> CircularProgressIndicator(Modifier.testTag("report-preview-loading-${index + 1}"))
            }
        }
    }
}
