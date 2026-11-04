package cc.baseballmaster.scorekeeper

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry

/** Test-only cache invalidation. Android 12 has no public UiAutomation.clearCache;
 * changing serviceInfo also reconfigures SystemUI navigation during display changes.
 * A normal subtree event invalidates the cached app tree without reconfiguring a service.
 */
internal object UiTreeCache {
    fun refresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) { automation.clearCache(); return }
        val packageName = instrumentation.targetContext.packageName
        var hasWindow = false
        instrumentation.runOnMainSync {
            hasWindow = WindowInspector.getGlobalWindowViews().any { it.isAttachedToWindow && it.isShown }
        }
        if (!hasWindow) return
        val event = automation.executeAndWaitForEvent({
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }.forEach { root ->
                    val changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    changed.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                    changed.packageName = packageName
                    changed.className = root.javaClass.name
                    changed.setSource(root)
                    root.sendAccessibilityEventUnchecked(changed)
                }
            }
        }, { changed -> changed.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            changed.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0 &&
            changed.packageName?.toString() == packageName }, 5000)
        event.recycle()
    }
}
