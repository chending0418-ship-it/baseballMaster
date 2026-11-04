package cc.baseballmaster.scorekeeper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

/** Current usable window, rather than a phone/tablet or manufacturer lookup. */
internal val LocalWorkbenchWide = compositionLocalOf { false }
internal val LocalSeparatingFold = compositionLocalOf<SeparatingFold?> { null }
internal data class SeparatingFold(val bounds: Rect, val vertical: Boolean)

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Retains one content owner through cover-screen, inner-screen and window changes. */
@Composable
internal fun PortraitViewport(tag: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val fold by produceState<SeparatingFold?>(null, activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
                .catch { value = null }
                .collect { info ->
                    val feature = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.isSeparating }
                    value = feature?.let {
                        val b = it.bounds
                        SeparatingFold(Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
                            it.orientation == FoldingFeature.Orientation.VERTICAL)
                    }
                }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag(tag).semantics { testTagsAsResourceId = true }) {
        CompositionLocalProvider(LocalWorkbenchWide provides (maxWidth >= 700.dp && maxHeight >= 480.dp),
            LocalSeparatingFold provides fold) { content() }
    }
}
