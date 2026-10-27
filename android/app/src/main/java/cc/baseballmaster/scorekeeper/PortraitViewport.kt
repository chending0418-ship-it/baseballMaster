package cc.baseballmaster.scorekeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/** One portrait column, including system-forced desktop/tablet windows. No landscape layout. */
@Composable
internal fun PortraitViewport(tag: String, content: @Composable () -> Unit) {
    val saved = rememberSaveableStateHolder()
    val landscape=LocalConfiguration.current.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true },
        contentAlignment = Alignment.Center,
    ) {
        val height = maxHeight
        val forcedWide=landscape&&maxWidth>height
        val width = if(forcedWide)minOf(maxWidth,480.dp,height*.72f)else minOf(maxWidth,480.dp)
        Box(
            Modifier.width(width).fillMaxHeight().testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            if (forcedWide&&height < 500.dp) Text("请竖屏使用", Modifier.testTag("portrait-required"))
            else saved.SaveableStateProvider("portrait-content") { content() }
        }
    }
}
