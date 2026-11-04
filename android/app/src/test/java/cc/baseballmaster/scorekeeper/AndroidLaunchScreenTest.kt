package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cache/native image decoding under Android JVM only; no Activity or UI/AN07. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidLaunchScreenTest {
    private class Transport : LaunchScreenTransport {
        var config=LaunchScreenConfiguration.bundled;var image=byteArrayOf();var requests=0
        override fun configuration(): ByteArray {requests++;return GameJson.encodeToString(LaunchScreenConfiguration.serializer(),config).toByteArray()}
        override fun image(descriptor: LaunchBackgroundImage)=image
    }
    private fun folder()=Files.createTempDirectory("android-launch-cache-").toFile()
    private fun image(): Pair<LaunchBackgroundImage,ByteArray> {
        val root=requireNotNull(File(requireNotNull(System.getProperty("parity.fixtures"))).parentFile?.parentFile)
        val bytes=File(root,"BaseballMaster/Assets.xcassets/LaunchAppIcon.imageset/LaunchAppIcon@1x.png").readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        return LaunchBackgroundImage("/api/v3/app/launch-assets/$hash.png",hash,"image/png",bytes.size,44,44) to bytes
    }
    @Test fun offlineCacheReadNeverWaitsForNetworkAndDisabledOrScheduledConfigMatchesIOS() {
        val dir=folder();val transport=Transport();val repo=AndroidLaunchScreenRepository(dir,transport)
        try {
            assertEquals(LaunchScreenConfiguration.bundled,repo.presentation()!!.configuration);assertEquals(0,transport.requests)
            transport.config=LaunchScreenConfiguration.bundled.copy(message="缓存文案",startsAt=100,endsAt=200)
            assertTrue(repo.refresh());assertEquals("缓存文案",repo.presentation(100)!!.configuration.message)
            assertEquals(LaunchScreenConfiguration.bundled,repo.presentation(200)!!.configuration)
            transport.config=transport.config.copy(enabled=false);assertTrue(repo.refresh());assertNull(repo.presentation(150))
        } finally {dir.deleteRecursively()}
    }
    @Test fun validImageIsDecodedAndAChangedHashOrDimensionCannotReplaceThePreviousManifest() {
        val dir=folder();val transport=Transport();val repo=AndroidLaunchScreenRepository(dir,transport)
        try {
            val (descriptor,bytes)=image();transport.config=LaunchScreenConfiguration.bundled.copy(message="已校验",backgroundImage=descriptor);transport.image=bytes
            assertTrue(repo.refresh());val shown=repo.presentation()!!;assertEquals("已校验",shown.configuration.message);assertEquals(44,shown.image!!.width)
            transport.config=transport.config.copy(message="不应替换",backgroundImage=descriptor.copy(width=45))
            assertFalse(repo.refresh());assertEquals("已校验",repo.presentation()!!.configuration.message)
            assertThrows(IllegalArgumentException::class.java) {AndroidLaunchScreenRepository.decodeImage(bytes+0,descriptor)}
            assertThrows(IllegalArgumentException::class.java) {AndroidLaunchScreenRepository.decodeImage(bytes,descriptor.copy(sha256="a".repeat(64),path="/api/v3/app/launch-assets/${"a".repeat(64)}.png"))}
        } finally {dir.deleteRecursively()}
    }
    @Test fun failedManifestCommitKeepsThePriorConfigAndAnInvalidDownloadNeverBecomesVisible() {
        val dir=folder();val transport=Transport()
        try {
            transport.config=LaunchScreenConfiguration.bundled.copy(message="原配置");val original=AndroidLaunchScreenRepository(dir,transport);assertTrue(original.refresh())
            val (descriptor,bytes)=image();transport.config=transport.config.copy(message="新配置",backgroundImage=descriptor);transport.image=bytes
            assertFalse(AndroidLaunchScreenRepository(dir,transport) {throw IOException("injected manifest failure")}.refresh())
            assertEquals("原配置",original.presentation()!!.configuration.message)
            File(dir,"${descriptor.sha256}.image").delete();transport.image=byteArrayOf(1,2,3)
            assertFalse(original.refresh());assertEquals("原配置",original.presentation()!!.configuration.message)
        } finally {dir.deleteRecursively()}
    }
    @Test fun anInFlightRefreshDoesNotHoldThePresentationLock() {
        val dir=folder();val entered=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        val repo=AndroidLaunchScreenRepository(dir,object : LaunchScreenTransport {
            override fun configuration(): ByteArray {entered.countDown();check(release.await(5,TimeUnit.SECONDS));return GameJson.encodeToString(LaunchScreenConfiguration.serializer(),LaunchScreenConfiguration.bundled).toByteArray()}
            override fun image(descriptor: LaunchBackgroundImage)=error("Unexpected image")
        })
        try {
            val refresh=executor.submit<Boolean> {repo.refresh()};assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertEquals(LaunchScreenConfiguration.bundled,executor.submit<AndroidLaunchPresentation?> {repo.presentation()}.get(1,TimeUnit.SECONDS)!!.configuration)
            release.countDown();assertTrue(refresh.get(3,TimeUnit.SECONDS))
        } finally {release.countDown();executor.shutdownNow();dir.deleteRecursively()}
    }
}
