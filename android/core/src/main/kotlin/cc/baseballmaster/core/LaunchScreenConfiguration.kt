package cc.baseballmaster.core

import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class LaunchBackgroundImage(val path: String, val sha256: String, val mimeType: String, val byteCount: Int, val width: Int, val height: Int) {
    fun validate() {
        require(mimeType in setOf("image/png","image/jpeg") && Regex("[a-f0-9]{64}").matches(sha256))
        require(path=="/api/v3/app/launch-assets/$sha256.${if(mimeType=="image/png") "png" else "jpg"}")
        require(byteCount in 1..3*1024*1024 && width in 1..4096 && height in 1..8192 && width.toLong()*height<=16_000_000)
    }
}
/** Required wire fields deliberately have no defaults: Swift Codable also requires them. */
@Serializable data class LaunchScreenConfiguration(
    val schemaVersion: Int, val revision: Long, val enabled: Boolean, val message: String, val displayDurationMs: Int,
    val updatedAt: Long? = null, val startsAt: Long? = null, val endsAt: Long? = null, val backgroundImage: LaunchBackgroundImage? = null,
) {
    fun validate() {
        val points=message.codePoints().toArray()
        require(schemaVersion==1 && revision>=0 && points.size<=40 && points.none {(it<32 && it!=10) || it==127 || it in 0xd800..0xdfff} && message.count {it=='\n'}<=1)
        require(displayDurationMs in 300..2000 && listOfNotNull(updatedAt,startsAt,endsAt).all {it in 0..4_102_444_800_000L})
        require(startsAt==null || endsAt==null || endsAt>startsAt)
        backgroundImage?.validate()
    }
    fun isInSchedule(at: Long)=(startsAt==null || at>=startsAt) && (endsAt==null || at<endsAt)
    companion object {
        val bundled=LaunchScreenConfiguration(1,0,true,"每一球 都有迹可循",800)
        fun decode(bytes: ByteArray): LaunchScreenConfiguration {
            require(bytes.size<=16_384)
            return TeamDirectoryJson.format.decodeFromJsonElement<LaunchScreenConfiguration>(AppleWireJson.parse(bytes)).also {it.validate()}
        }
    }
}

interface LaunchScreenTransport {
    fun configuration(): ByteArray
    fun image(descriptor: LaunchBackgroundImage): ByteArray
}

/** Public configuration only; no device bearer, cookies, redirects or response cache. */
class LaunchScreenHttpTransport(
    endpoint: String = "https://baseballmaster.cc/api/v3/app/launch-screen", allowLoopbackHttp: Boolean = false,
) : LaunchScreenTransport {
    private val endpoint=endpoint.toHttpUrl()
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(if(allowLoopbackHttp && this.endpoint.scheme=="http") java.net.Proxy.NO_PROXY else null)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build()
    init {
        require(this.endpoint.encodedPath=="/api/v3/app/launch-screen" && this.endpoint.query==null && this.endpoint.fragment==null && this.endpoint.username.isEmpty() && this.endpoint.password.isEmpty())
        require(this.endpoint.scheme=="https" && this.endpoint.host=="baseballmaster.cc" || allowLoopbackHttp && this.endpoint.scheme=="http" && this.endpoint.host in setOf("127.0.0.1","localhost","::1"))
    }
    override fun configuration()=read(endpoint,16_384,"application/json")
    override fun image(descriptor: LaunchBackgroundImage): ByteArray {
        descriptor.validate();val url=requireNotNull(endpoint.resolve(descriptor.path))
        require(url.scheme==endpoint.scheme && url.host==endpoint.host && url.port==endpoint.port)
        return read(url,3*1024*1024,descriptor.mimeType)
    }
    private fun read(url: okhttp3.HttpUrl, limit: Int, mime: String): ByteArray {
        val request=Request.Builder().url(url).header("Accept",mime).header("Cache-Control","no-cache, no-store").build()
        client.newCall(request).execute().use {response ->
            val body=requireNotNull(response.body);val type=body.contentType()
            require(response.code==200 && response.request.url==url && type!=null && "${type.type}/${type.subtype}".lowercase()==mime && body.contentLength()<=limit)
            return body.byteStream().use {stream ->
                val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val count=stream.read(buffer);if(count<0) break;require(result.size()+count<=limit);result.write(buffer,0,count)}
                result.toByteArray()
            }
        }
    }
}
