package cc.baseballmaster.scorekeeper

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.assertTrue

/** Bounded deterministic search for statistics pages, whose long forms span several screens. */
internal object StatisticsUi {
    private fun settle(device: UiDevice) {
        device.waitForIdle(500)
        UiTreeCache.refresh()
    }

    private fun viewport(device: UiDevice): UiObject2? {
        val pkg=InstrumentationRegistry.getInstrumentation().targetContext.packageName
        return device.findObject(By.pkg(pkg).scrollable(true))
    }
    private fun stableNode(device: UiDevice, selector: BySelector, inContent: Boolean): UiObject2? {
        settle(device)
        val before=device.findObject(selector) ?: return null
        val bounds=before.visibleBounds
        val content=if(inContent) viewport(device)?.visibleBounds else null
        if(bounds.height()<=10 || bounds.width()<=10 || content!=null &&
            (!content.contains(bounds.centerX(), bounds.centerY()))) return null
        // Wait for physics/layout to stop as well as accessibility events. A fling-stopping tap
        // must not be treated as a successful checkbox interaction.
        SystemClock.sleep(120)
        settle(device)
        val after=device.findObject(selector) ?: return null
        return after.takeIf {it.visibleBounds==bounds}
    }
    private fun scrollStep(device: UiDevice, direction: Direction): Boolean {
        val container=viewport(device) ?: return false
        // Compose 1.x on API 31 does not always emit the scroll event expected
        // by UiObject2.scroll; its false result is not proof of an endpoint.
        // Use a slow bounded gesture and inspect the target again after settling.
        val bounds = container.visibleBounds
        fun visibleText(): List<Pair<String, android.graphics.Rect>> = container.findObjects(By.text(java.util.regex.Pattern.compile(".+")))
            .mapNotNull { node -> val box = node.visibleBounds; if(box.height() > 10 && bounds.contains(box.centerX(), box.centerY())) node.text to box else null }
        val before = visibleText()
        val top = bounds.top + bounds.height() / 4
        val bottom = bounds.bottom - bounds.height() / 4
        if (bottom <= top) return false
        device.swipe(bounds.centerX(), if (direction == Direction.DOWN) bottom else top,
            bounds.centerX(), if (direction == Direction.DOWN) top else bottom, 40)
        SystemClock.sleep(250)
        settle(device)
        // The actual visible content is a reliable endpoint signal even when
        // API 31 omits the framework scroll event.
        val after = visibleText()
        return before.isEmpty() || before != after
    }

    fun <T:Any> use(device: UiDevice, selector: BySelector, scroll: Boolean=true, block:(UiObject2)->T): T {
        val end=SystemClock.uptimeMillis()+45_000
        var reset=false;var downSteps=0;var stale:StaleObjectException?=null
        while(SystemClock.uptimeMillis()<end) {
            try {
                stableNode(device,selector,scroll)?.let {return block(it)}
                if(!scroll) {SystemClock.sleep(150);continue}
                if(!reset) {
                    // Search top-to-bottom once. Alternating blind swipes can repeatedly skip a
                    // small metric above the current viewport without ever observing it.
                    var steps=0
                    while(steps++<10 && SystemClock.uptimeMillis()<end && scrollStep(device,Direction.UP)) {
                        stableNode(device,selector,true)?.let { return block(it) }
                    }
                    reset=true
                    continue
                }
                if(downSteps++>=24 || !scrollStep(device,Direction.DOWN)) {
                    // At the end, keep waiting for an expected value rather than oscillating.
                    stableNode(device,selector,true)?.let {return block(it)}
                    break
                }
            } catch(failure:StaleObjectException) { stale=failure }
        }
        throw AssertionError("Missing or unstable $selector after bounded statistics-page search",stale)
    }
    fun setChecked(device:UiDevice, tag:String, checked:Boolean) {
        repeat(4) {
            use(device,By.res(tag)) {control -> if(control.isChecked!=checked) control.click(); Unit}
            // Re-read before every retry: a delayed successful click must never be toggled back.
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (SystemClock.uptimeMillis() < deadline) {
                settle(device)
                if (device.hasObject(By.res(tag).checked(checked))) return
                SystemClock.sleep(100)
            }
        }
        assertTrue("Control $tag did not become $checked",device.hasObject(By.res(tag).checked(checked)))
    }
}
