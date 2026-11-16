package cc.baseballmaster.core

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class WebsiteAndroidRelease(val version: String, val build: Int) {
    companion object {
        const val downloadPage = "https://baseballmaster.cc/scorekeeper#download"
        private const val productPage = "https://baseballmaster.cc/scorekeeper"
        private const val pageLimit = 256 * 1024
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS).build()
        // The website deploy script publishes this immutable APK path in both download buttons.
        private val link = Regex("""\shref\s*=\s*["']/assets/android/(\d+\.\d+\.\d+)-build([1-9]\d{0,9})/BaseballMaster-Android-\1-build\2\.apk["']""")

        internal fun parse(html: String): WebsiteAndroidRelease {
            val releases = link.findAll(html).map {
                WebsiteAndroidRelease(it.groupValues[1], it.groupValues[2].toInt())
            }.toSet()
            return releases.single()
        }

        fun read(): WebsiteAndroidRelease {
            val request = Request.Builder().url(productPage)
                .header("Accept", "text/html").header("Cache-Control", "no-cache, no-store").build()
            return client.newCall(request).execute().use { response ->
                val body = requireNotNull(response.body)
                val type = body.contentType()
                check(response.code == 200 && type?.type == "text" && type.subtype == "html")
                check(body.contentLength() <= pageLimit)
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(bytes.size() + count <= pageLimit)
                        bytes.write(buffer, 0, count)
                    }
                }
                parse(bytes.toString(Charsets.UTF_8.name()))
            }
        }
    }
}
