package cc.baseballmaster.core

import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable data class LiveBinding(
    val gameID: String, val requestID: String=UUID.randomUUID().toString(), val createdAt: Long,
    val token: String, val code: String?=null, val ackRevision: Long?=null, val ackDigest: String?=null,
    val lastSync: Long?=null, val expiresAt: Long?=null, val closing: Boolean=false, val blocked: Boolean=false,
) {
    init {
        appleUUID(gameID);appleUUID(requestID);require(createdAt>0 && Regex("[a-f0-9]{64}").matches(token))
        code?.let {require(Regex("[A-F0-9]{24}").matches(it))}
        require((ackRevision==null)==(ackDigest==null) && (ackRevision==null || ackRevision>=0 && code!=null))
        ackDigest?.let {require(Regex("[a-f0-9]{64}").matches(it))};lastSync?.let {require(it>0)};expiresAt?.let {require(it>0)}
    }
    override fun toString()="LiveBinding(gameID=$gameID, closing=$closing, blocked=$blocked)"
}
@Serializable data class LiveReply(val code: String,val revision: Long,val serverTime: Long,val lastSeen: Long,val expiresAt: Long) {
    init {require(Regex("[A-F0-9]{24}").matches(code) && revision>=0 && serverTime>0 && lastSeen>0 && expiresAt>0)}
}
interface LiveCredentialStorage {
    val monitor: Any
    fun load(): List<LiveBinding>
    fun save(bindings: List<LiveBinding>)
}
class LiveFault(val status: Int): IOException(when(status) {401,403->"直播发布授权已失效";409->"本机记录与已发布修订不一致";413->"本场直播资料过大";404,410->"直播已关闭或到期";else->"直播服务暂时不可用"})
fun interface LiveTransport { fun send(method: String, code: String?, token: String, body: JsonObject?): LiveReply? }

/** Separate service and per-broadcast token; never sends the team Bearer. */
class LiveHttpTransport(baseURL: String="https://baseballmaster.cc/livestreaming/novideo", allowLoopbackHttp: Boolean=false): LiveTransport {
    private val base=URI(baseURL)
    private val http: OkHttpClient
    init {
        val loopback=allowLoopbackHttp && base.scheme=="http" && base.host in setOf("127.0.0.1","localhost","[::1]")
        require(base.rawPath in setOf("/livestreaming/novideo","/livestreaming/novideo/") && base.rawQuery==null && base.rawFragment==null && base.rawUserInfo==null &&
            (loopback || base.scheme=="https" && base.host=="baseballmaster.cc" && base.port==-1))
        http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).cache(null).connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
            .apply {if(loopback) proxy(Proxy.NO_PROXY)}.build()
    }
    override fun send(method: String,code: String?,token: String,body: JsonObject?): LiveReply? {
        require(method in setOf("POST","PUT","DELETE") && Regex("[a-f0-9]{64}").matches(token) && (code==null || Regex("[A-F0-9]{24}").matches(code)))
        require(code!=null || method in setOf("POST","DELETE"))
        val bytes=body?.toString()?.toByteArray(Charsets.UTF_8)
        require(bytes==null || bytes.size<=2*1_048_576) {"本场直播资料过大，本机记录已保留"}
        val request=Request.Builder().url(base.toASCIIString().trimEnd('/')+"/api/sessions"+(code?.let {"/$it"} ?: ""))
            .header("Authorization","Bearer $token").header("Cache-Control","no-store").header("Accept-Encoding","identity")
            .method(method,bytes?.toRequestBody("application/json".toMediaType()) ?: if(method=="POST") byteArrayOf().toRequestBody(null) else null).build()
        try {return http.newCall(request).execute().use {reply ->
            if(reply.code !in 200..299) throw LiveFault(reply.code)
            if(method=="DELETE") null else {
                val data=reply.body?.byteStream()?.use {stream ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val n=stream.read(buffer);if(n<0) break;check(output.size()+n<=3*1_048_576);output.write(buffer,0,n)}
                    output.toByteArray()
                } ?: throw LiveFault(502)
                TeamDirectoryJson.format.decodeFromJsonElement<LiveReply>(AppleWireJson.parse(data))
            }
        }} catch(e: LiveFault) {throw e} catch(_: Exception) {throw LiveFault(503)}
    }
}

/** Serial durable state machine following LiveBroadcastManager. No creation or
 * publishing occurs without an existing explicitly started binding.
 */
class StandaloneLive(
    private val storage: LiveCredentialStorage,private val transport: LiveTransport,
    private val now: ()->Long=System::currentTimeMillis,
) {
    private val generation=AtomicInteger()
    private val failures=mutableMapOf<String,Int>();private val retryAt=mutableMapOf<String,Long>()
    val messages=mutableMapOf<String,String>()
    var credentialError: String?=null;private set
    fun bindings(): List<LiveBinding> = synchronized(storage.monitor) {storage.load()}
    fun url(id: String): String? = synchronized(storage.monitor) {
        if(credentialError!=null) return@synchronized null
        storage.load().singleOrNull {it.gameID==id && !it.closing && (it.expiresAt ?: Long.MAX_VALUE)>now()}?.code?.let {"https://baseballmaster.cc/livestreaming/novideo/$it"}
    }
    private fun save(next: List<LiveBinding>) {
        try {storage.save(next)} catch(e: Exception) {credentialError="直播凭证保存结果未确认，请重启 App 核对；当前不会继续上传";throw e}
    }
    private fun update(binding: LiveBinding) = save(storage.load().filterNot {it.gameID==binding.gameID}+binding)
    fun start(game: Game) = synchronized(storage.monitor) {
        check(credentialError==null)
        require(!game.practice && !game.isScheduled && !game.isFinal && game.home.source!="shared" && game.away.source!="shared") {"仅进行中的独立正式比赛可以开启文字直播"}
        check(storage.load().none {it.gameID==game.id}) {"本场已有直播状态，请先核对"}
        // Validate the public projection before retaining any publishing intent.
        LiveProjection.create(game)
        val token=ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") {"%02x".format(it)}
        update(LiveBinding(gameID=game.id,createdAt=now(),token=token));messages[game.id]="正在开启直播…"
    }
    fun close(id: String) = synchronized(storage.monitor) {
        check(credentialError==null)
        storage.load().singleOrNull {it.gameID==id}?.let {update(it.copy(closing=true,blocked=false));retryAt.remove(id)}
    }
    fun retry(id: String) = synchronized(storage.monitor) {retryAt.remove(id)}
    fun prepareForRestore() {
        generation.incrementAndGet()
        synchronized(storage.monitor) {save(storage.load().map {it.copy(closing=true,blocked=false)});retryAt.clear();failures.clear()}
    }
    fun synchronize(games: Map<String,Game>, recoveryRequired: Boolean=false) = synchronized(storage.monitor) {
        if(credentialError!=null || recoveryRequired) return@synchronized
        for(initial in storage.load()) {
            if(credentialError!=null) break
            var b=storage.load().singleOrNull {it.gameID==initial.gameID} ?: continue
            if(b.blocked || (retryAt[b.gameID] ?: 0)>now()) continue
            val currentGeneration=generation.get()
            fun closing()=b.closing || generation.get()!=currentGeneration || storage.load().singleOrNull {it.gameID==b.gameID}?.closing==true
            try {
                if((b.expiresAt ?: Long.MAX_VALUE)<=now()) {save(storage.load().filterNot {it.gameID==b.gameID});messages[b.gameID]="直播已过期，旧链接不再可用";continue}
                val game=games[b.gameID]
                if(game==null && !b.closing) {b=b.copy(closing=true);update(b)}
                if(b.closing) {
                    transport.send("DELETE",b.code,b.token,if(b.code==null) buildJsonObject {put("requestID",b.requestID)} else null)
                    save(storage.load().filterNot {it.gameID==b.gameID});messages[b.gameID]="直播已关闭，云端直播资料已删除";continue
                }
                requireNotNull(game)
                val projection=LiveProjection.create(game);val digest=MessageDigest.getInstance("SHA-256").digest(projection.toString().toByteArray()).joinToString("") {"%02x".format(it)}
                if(b.code==null) {
                    val reply=requireNotNull(transport.send("POST",null,b.token,buildJsonObject {put("requestID",b.requestID);put("createdAt",b.createdAt);put("snapshot",projection)}))
                    b=b.copy(code=reply.code,closing=closing());update(b)
                    if(b.closing) continue
                }
                val ack=b.ackRevision
                if(ack!=null && (game.revision<ack || game.revision==ack && b.ackDigest!=digest)) {
                    b=b.copy(blocked=true);update(b);messages[b.gameID]="本机资料与已发布版本不一致，请关闭直播后重新开启";continue
                }
                val unchanged=ack==game.revision && b.ackDigest==digest
                val reply=requireNotNull(transport.send(if(unchanged) "POST" else "PUT",b.code,b.token,if(unchanged) null else projection))
                check(reply.code==b.code && reply.revision==game.revision && reply.expiresAt>=reply.serverTime) {"直播响应不一致"}
                b=b.copy(ackRevision=game.revision,ackDigest=digest,lastSync=now(),expiresAt=now()+(reply.expiresAt-reply.serverTime),closing=closing())
                update(b);failures.remove(b.gameID);retryAt.remove(b.gameID)
                messages[b.gameID]=if(game.isFinal) "终场已同步 · 一小时后删除" else "已同步 · 网页每 10 秒刷新"
            } catch(error: Exception) {
                if(credentialError!=null) break
                if(closing() && !b.closing) {update(b.copy(closing=true,blocked=false));retryAt.remove(b.gameID);continue}
                if(error is LiveFault && error.status in setOf(404,410)) {
                    save(storage.load().filterNot {it.gameID==b.gameID});messages[b.gameID]="直播已关闭或过期，旧链接不再可用"
                } else {
                    val count=(failures[b.gameID] ?: 0)+1;failures[b.gameID]=count;retryAt[b.gameID]=now()+(1L shl count.coerceAtMost(6)).coerceAtMost(60)*1000
                    val blocked=error is LiveFault && error.status in setOf(400,401,403,409,413,415) || error is IllegalArgumentException
                    if(blocked) {b=b.copy(blocked=true);update(b)}
                    messages[b.gameID]=if(blocked) "直播同步未确认，请关闭后重新开启；本机比赛已保留" else "待同步，请联网后重试；本机比赛已保留"
                }
            }
        }
    }
}
