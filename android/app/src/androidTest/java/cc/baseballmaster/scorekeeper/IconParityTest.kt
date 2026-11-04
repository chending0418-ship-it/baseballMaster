package cc.baseballmaster.scorekeeper

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconParityTest {
    @Test fun installedLauncherIconMatchesIOSPixelForPixel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ios = instrumentation.context.assets.open("AppIcon-1024.png").use { BitmapFactory.decodeStream(it) }
        val android = BitmapFactory.decodeResource(context.resources, R.drawable.app_icon)
        try {
            assertEquals(1024, ios.width); assertEquals(1024, ios.height)
            assertEquals(ios.width, android.width); assertEquals(ios.height, android.height)
            assertTrue("APK launcher icon must match the iOS icon pixels", ios.sameAs(android))
            assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
            assertTrue(context.packageManager.getApplicationIcon(context.applicationInfo) is android.graphics.drawable.AdaptiveIconDrawable)
        } finally { ios.recycle(); android.recycle() }
    }
}
