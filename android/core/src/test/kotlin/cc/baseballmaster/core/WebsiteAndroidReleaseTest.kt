package cc.baseballmaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebsiteAndroidReleaseTest {
    private val current = """<a href="/assets/android/0.8.3-build14/BaseballMaster-Android-0.8.3-build14.apk">下载</a>"""
    private val old = """<a href="/assets/android/0.8.2-build10/BaseballMaster-Android-0.8.2-build10.apk">下载</a>"""

    @Test fun publishedDownloadButtonsAgreeOnTheImmutablePackage() {
        assertEquals(WebsiteAndroidRelease("0.8.3", 14), WebsiteAndroidRelease.parse(current + current))
        assertEquals(WebsiteAndroidRelease("0.8.2", 10), WebsiteAndroidRelease.parse(old))
    }

    @Test fun conflictingOrUnrecognizedLinksDoNotAdvertiseAnUpdate() {
        assertThrows(IllegalArgumentException::class.java) { WebsiteAndroidRelease.parse(old + current) }
        assertThrows(NoSuchElementException::class.java) { WebsiteAndroidRelease.parse(current.replace("href=\"", "href=\"https://other.example")) }
        assertThrows(NoSuchElementException::class.java) { WebsiteAndroidRelease.parse(current.replace("build14.apk", "build99.apk")) }
    }
}
