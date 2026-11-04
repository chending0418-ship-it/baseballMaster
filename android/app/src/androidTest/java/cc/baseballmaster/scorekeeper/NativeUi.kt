package cc.baseballmaster.scorekeeper

import android.os.SystemClock
import androidx.test.uiautomator.*

/** Finds actionable controls in the phone's real scroll containers. */
internal object NativeUi {
    private fun refresh() { UiTreeCache.refresh() }
    fun find(device: UiDevice, selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 45_000
        var attempt = 0
        var down = true
        var previousViewport: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                refresh()
                device.findObject(selector)?.let {
                    val bounds = it.visibleBounds
                    if (!bounds.isEmpty && bounds.height() > 12 && bounds.centerY() in 24..device.displayHeight - 24) {
                        // A swipe can expose a node while the list is still moving. A tap then
                        // stops the fling instead of activating the control.
                        device.waitForIdle(500)
                        refresh()
                        val settled = device.findObject(selector)
                        if (settled != null && settled.visibleBounds == bounds) return settled
                    }
                }
                val pkg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
                val embeddedTask = device.findObject(By.res("embedded-product-task"))
                val scrollOwner = embeddedTask?.findObject(By.res("product-page-scroll"))
                    ?: device.findObject(By.res("product-page-scroll"))
                    ?: device.findObjects(By.pkg(pkg).scrollable(true))
                        .maxByOrNull { it.visibleBounds.height() }
                val viewport = scrollOwner?.visibleBounds
                if (viewport != null && viewport.height() > 100) {
                    val top = maxOf(viewport.top + 40, device.displayHeight / 6)
                    val bottom = minOf(viewport.bottom - 40, device.displayHeight * 8 / 10)
                    if (bottom > top) {
                        val visibleContent = scrollOwner?.findObjects(By.text(java.util.regex.Pattern.compile(".+"))).orEmpty()
                            .mapNotNull { node ->
                                val box = node.visibleBounds
                                if (box.height() > 10 && viewport.contains(box.centerX(), box.centerY())) "${node.text}@$box" else null
                            }.joinToString("|")
                        val marker = device.findObject(selector)?.visibleBounds
                        if (marker != null && !marker.isEmpty) down = marker.centerY() > device.displayHeight / 2
                        else if (visibleContent.isNotEmpty() && visibleContent == previousViewport) {
                            // Reverse at a real content endpoint instead of wasting the
                            // entire deadline swiping against it before looking above.
                            down = !down
                            attempt = 0
                        } else if (attempt++ % 8 == 7) down = !down
                        previousViewport = visibleContent
                        device.swipe(viewport.centerX(), if (down) bottom else top,
                            viewport.centerX(), if (down) top else bottom, 25)
                    }
                }
            } catch (_: StaleObjectException) { }
            device.waitForIdle(300)
        }
        error("Missing visible control: $selector")
    }
}
