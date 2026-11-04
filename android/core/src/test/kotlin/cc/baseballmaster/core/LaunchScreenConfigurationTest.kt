package cc.baseballmaster.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LaunchScreenConfigurationTest {
    @Test fun currentIOSConfigurationRequiresWireFieldsAndCountsUnicodeScalars() {
        val valid=LaunchScreenConfiguration.bundled.copy(message="⚾".repeat(40));valid.validate()
        assertThrows(IllegalArgumentException::class.java) {valid.copy(message="⚾".repeat(41)).validate()}
        for(message in listOf("one\ntwo\nthree","text\rline","\u0000","\uD800")) assertThrows(IllegalArgumentException::class.java) {valid.copy(message=message).validate()}
        val raw=GameJson.encodeToJsonElement(LaunchScreenConfiguration.bundled).jsonObject
        for(key in listOf("schemaVersion","revision","enabled","message","displayDurationMs")) assertThrows(Exception::class.java) {
            LaunchScreenConfiguration.decode(JsonObject(raw-key).toString().toByteArray())
        }
        assertEquals(LaunchScreenConfiguration.bundled,LaunchScreenConfiguration.decode(JsonObject(raw+("futureField" to JsonPrimitive("preserved-by-server"))).toString().toByteArray()))
    }
    @Test fun scheduleAndImageDescriptorsFollowIOSBoundsAndOnlyAllowContentAddressedLocalPaths() {
        val config=LaunchScreenConfiguration.bundled.copy(startsAt=100,endsAt=200)
        config.validate();assertFalse(config.isInSchedule(99));assertTrue(config.isInSchedule(100));assertFalse(config.isInSchedule(200))
        for(value in listOf(config.copy(schemaVersion=2),config.copy(displayDurationMs=299),config.copy(displayDurationMs=2001),config.copy(endsAt=100),config.copy(updatedAt=4_102_444_800_001))) assertThrows(IllegalArgumentException::class.java) {value.validate()}
        val hash="a".repeat(64);val image=LaunchBackgroundImage("/api/v3/app/launch-assets/$hash.png",hash,"image/png",123,1206,2622);image.validate()
        for(value in listOf(image.copy(path="https://other.example/image.png"),image.copy(path="/../image.png"),image.copy(width=4097),image.copy(width=4096,height=8192),image.copy(byteCount=3*1024*1024+1),image.copy(mimeType="image/gif"),image.copy(sha256=hash.uppercase()))) assertThrows(IllegalArgumentException::class.java) {value.validate()}
    }
    @Test(timeout=10000) fun publicTransportSendsNoDeviceCredentialAndRejectsRedirects() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val authorization=AtomicReference<String>();val cookies=AtomicReference<String>();val redirectRequests=AtomicInteger()
        var redirect=false
        server.createContext("/api/v3/app/launch-screen") {exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"));cookies.set(exchange.requestHeaders.getFirst("Cookie"))
            val bytes=GameJson.encodeToString(LaunchScreenConfiguration.serializer(),LaunchScreenConfiguration.bundled).toByteArray()
            exchange.responseHeaders.set("Content-Type","application/json; charset=utf-8")
            if(redirect) exchange.responseHeaders.set("Location","/redirect-target")
            exchange.sendResponseHeaders(if(redirect) 302 else 200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        }
        server.createContext("/redirect-target") {exchange ->redirectRequests.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close()}
        server.start()
        try {
            val endpoint="http://127.0.0.1:${server.address.port}/api/v3/app/launch-screen"
            assertThrows(IllegalArgumentException::class.java) {LaunchScreenHttpTransport(endpoint)}
            val client=LaunchScreenHttpTransport(endpoint,true)
            assertEquals(LaunchScreenConfiguration.bundled,LaunchScreenConfiguration.decode(client.configuration()))
            assertNull(authorization.get());assertNull(cookies.get());redirect=true
            assertThrows(IllegalArgumentException::class.java) {client.configuration()};assertEquals(0,redirectRequests.get())
        } finally {server.stop(0)}
    }
    @Test(timeout=10000) fun publicTransportBoundsChunkedBodiesAndChecksMime() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);var mime="application/json"
        server.createContext("/api/v3/app/launch-screen") {exchange ->
            exchange.responseHeaders.set("Content-Type",mime);exchange.sendResponseHeaders(200,0)
            runCatching {exchange.responseBody.use {it.write(ByteArray(16_385))}};exchange.close()
        };server.start()
        try {
            val client=LaunchScreenHttpTransport("http://127.0.0.1:${server.address.port}/api/v3/app/launch-screen",true)
            assertThrows(IllegalArgumentException::class.java) {client.configuration()}
            mime="text/plain";assertThrows(IllegalArgumentException::class.java) {client.configuration()}
            assertThrows(IllegalArgumentException::class.java) {LaunchScreenHttpTransport("https://other.example/api/v3/app/launch-screen")}
        } finally {server.stop(0)}
    }
}
