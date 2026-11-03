package cc.baseballmaster.scorekeeper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat

/** A recording draft keeps its owner when the workbench changes from one column to two. */
internal val LocalProductTaskEmbedded = compositionLocalOf { false }

internal val ProductGreen = Color(0xFF217A57)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductPage(
    title: String,
    busy: Boolean = false,
    onBack: () -> Unit,
    backLabel: String = "返回",
    actionLabel: String = "",
    actionEnabled: Boolean = true,
    actionTag: String = "",
    onAction: () -> Unit = {},
    modal: Boolean = false,
    secure: Boolean = false,
    backTag: String = "",
    actionAtTop: Boolean = false,
    headerTrailing: (@Composable () -> Unit)? = null,
    scrollKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val embedded = LocalProductTaskEmbedded.current
    BackHandler(enabled = embedded) { if (!busy) onBack() }
    val scroll = key(scrollKey) { rememberScrollState() }
    val saved = rememberSaveableStateHolder()
    val foreground = MaterialTheme.colorScheme.onBackground
    val page: @Composable (Modifier) -> Unit = { modifier ->
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Column(modifier.background(MaterialTheme.colorScheme.background)
                .semantics { testTagsAsResourceId = true }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (backLabel == "返回") {
                        IconButton(onBack, enabled = !busy, modifier = Modifier.size(44.dp).testTag(backTag).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    } else TextButton(onBack, enabled = !busy, modifier = Modifier.testTag(backTag)) { Text(backLabel, fontSize = 14.sp) }
                    Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), textAlign = TextAlign.Center,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (headerTrailing != null) Box(Modifier.widthIn(min = 44.dp), contentAlignment = Alignment.CenterEnd) {
                        headerTrailing()
                    } else if (actionAtTop && actionLabel.isNotEmpty()) TextButton(onAction,
                        enabled = actionEnabled && !busy, modifier = Modifier.testTag(actionTag)) {
                        Text(if (busy) "保存中…" else actionLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    } else Spacer(Modifier.width(44.dp))
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .testTag("product-page-scroll").padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    saved.SaveableStateProvider("page-content") { content() }
                }
                if (actionLabel.isNotEmpty() && !actionAtTop) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                            PrimaryProductButton(if (busy) "保存中…" else actionLabel, actionTag,
                                enabled = actionEnabled && !busy, onClick = onAction)
                        }
                    }
                }
            }
        }
    }
    when {
        embedded -> page(Modifier.fillMaxSize().testTag("embedded-product-task"))
        modal && !secure -> {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { next ->
                // Keep this veto pure. Material3's system-back path invokes
                // onDismissRequest after hide() even when Hidden is vetoed.
                // The single owner callback below decides whether to go back a
                // step, show an unsaved prompt or remove this sheet entirely.
                next != SheetValue.Hidden
            })
            ModalBottomSheet(onDismissRequest = { if (!busy) onBack() }, sheetState = sheet,
                // Scrolling a multi-step form must never be interpreted as leaving
                // its current step. Exit stays on the labelled back/cancel controls.
                sheetGesturesEnabled = false, dragHandle = null,
                containerColor = MaterialTheme.colorScheme.background, contentColor = foreground,
                sheetMaxWidth = 720.dp) {
                page(Modifier.fillMaxWidth().fillMaxHeight(.97f).imePadding())
            }
        }
        else -> Dialog(
            onDismissRequest = { if (!busy) onBack() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
        ) {
            val view = LocalView.current
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                page(Modifier.widthIn(max = 1280.dp).fillMaxSize())
            }
        }
    }
}

@Composable
internal fun PrimaryProductButton(label: String, tag: String = "", enabled: Boolean = true,
    onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag),
        enabled = enabled, shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ProductGreen, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ProductActionRow(title: String, subtitle: String = "", icon: ImageVector? = null,
    tag: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
        .testTag(tag), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp).heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon?.let { Icon(it, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProductToggleRow(title: String, subtitle: String = "", checked: Boolean,
    tag: String = "", onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
internal fun ProductMenuPicker(label: String, selected: String, values: Map<String, String>,
    tag: String = "", onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = true }.testTag(tag),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(values[selected] ?: "请选择", Modifier.widthIn(max = 240.dp), fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
            Text("⌃⌄", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            values.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) },
                modifier = Modifier.testTag("$tag-option-$key"),
                onClick = { expanded = false; onSelect(key) }) }
        }
    }
}

@Composable
internal fun ProductNumberStepper(label: String, value: Int, minimum: Int, maximum: Int,
    tag: String, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text("$value", Modifier.padding(horizontal = 12.dp).testTag(tag), fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)) {
            Row {
                TextButton({ change(value - 1) }, enabled = value > minimum,
                    modifier = Modifier.size(42.dp).testTag("$tag-minus"), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 22.sp) }
                TextButton({ change(value + 1) }, enabled = value < maximum,
                    modifier = Modifier.size(42.dp).testTag("$tag-plus"), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 22.sp) }
            }
        }
    }
}
