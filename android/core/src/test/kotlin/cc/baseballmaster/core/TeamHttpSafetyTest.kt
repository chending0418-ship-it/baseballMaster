package cc.baseballmaster.core

import com.sun.net.httpserver.HttpServer
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TeamHttpSafetyTest {
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply { createContext("/", handler); start() }
    private fun endpoint(server: HttpServer) = "http://127.0.0.1:${server.address.port}/api/v3"

    @Test fun rejectsNoncanonicalSecretsAndUnsafeEndpointsWithoutAnyRequest() {
        val credential = TeamCredentialProvider { TeamSecrets.random() }
        for (address in listOf("http://baseballmaster.cc/api/v3", "https://user:pass@example.com/api/v3", "https://example.com/api/v3?token=x", "https://example.com/api/v3#secret", "https://example.com/other", "http://127.0.0.1.example.com/api/v3"))
            assertThrows(IllegalArgumentException::class.java) { TeamHttpTransport(address, credential, allowLoopbackHttp = true) }
        assertThrows(IllegalArgumentException::class.java) { TeamHttpTransport("http://127.0.0.1/api/v3", credential) }
        val canonical = TeamSecrets.random()
        assertEquals(canonical, TeamSecrets.validate(canonical))
        for (token in listOf(canonical + "=", canonical.dropLast(1) + "_", "../secret", "a".repeat(42)))
            assertThrows(IllegalArgumentException::class.java) { TeamSecrets.validate(token) }
    }

    @Test fun credentialFailurePreventsOpeningAConnection() {
        val count = AtomicInteger()
        val server = server { count.incrementAndGet(); it.sendResponseHeaders(500,-1); it.close() }
        try {
            val client = TeamHttpTransport(endpoint(server), TeamCredentialProvider { throw java.io.IOException("credential not saved") }, true)
            assertThrows(java.io.IOException::class.java) { client.request("GET", "teams", null) }
            assertEquals(0, count.get())
        } finally { server.stop(0) }
    }

    @Test fun redirectsNeverForwardAuthorizationToTheTarget() {
        val count = AtomicInteger()
        val target = server { count.incrementAndGet(); it.sendResponseHeaders(200,-1); it.close() }
        val origin = server { it.responseHeaders.add("Location", endpoint(target) + "/teams"); it.sendResponseHeaders(307,-1); it.close() }
        try {
            val transport = TeamHttpTransport(endpoint(origin), TeamCredentialProvider { TeamSecrets.random() }, true)
            assertEquals("redirect_refused", assertThrows(TeamServiceFault::class.java) { transport.request("GET", "teams", null) }.code)
            assertEquals(0, count.get())
        } finally { origin.stop(0); target.stop(0) }
    }

    @Test fun sendsProtocolAndBearerButNeverUsesTheGlobalCookieStoreOrResponseCookies() {
        val old = CookieHandler.getDefault(); val token = TeamSecrets.random(); val count = AtomicInteger()
        val server = server { exchange ->
            assertEquals("1", exchange.requestHeaders.getFirst("X-BM-Protocol"))
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestHeaders.getFirst("Cookie")); count.incrementAndGet()
            exchange.responseHeaders.add("Set-Cookie", "secretCookie=value; Path=/")
            val bytes = "{}".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val manager = CookieManager(); manager.cookieStore.add(URI(endpoint(server)), HttpCookie("globalCookie","must-not-send"))
            CookieHandler.setDefault(manager)
            val transport = TeamHttpTransport(endpoint(server), TeamCredentialProvider { token }, true)
            repeat(2) { transport.request("GET", "teams", null) }
            assertEquals(2, count.get()); assertFalse(manager.cookieStore.cookies.any { it.name == "secretCookie" })
        } finally { CookieHandler.setDefault(old); server.stop(0) }
    }

    @Test fun malformedDuplicateJSONAndServerErrorsDoNotExposeRawBodies() {
        val secret = TeamSecrets.random()
        var status = 200; var body = "{\"role\":\"admin\",\"role\":\"member\"}"
        val server = server { exchange -> val bytes=body.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        try {
            val transport = TeamHttpTransport(endpoint(server), TeamCredentialProvider { secret }, true)
            assertEquals("invalid_response", assertThrows(TeamServiceFault::class.java) { transport.request("GET", "teams", null) }.code)
            status = 403; body = "{\"error\":{\"code\":\"forbidden\",\"message\":\"$secret\"}}"
            val denied = assertThrows(TeamServiceFault::class.java) { transport.request("GET", "teams", null) }
            assertEquals("forbidden", denied.code); assertFalse(denied.toString().contains(secret)); assertNull(denied.cause)
        } finally { server.stop(0) }
    }
    @Test fun auditQueryIsPreciselyScopedToReadOnlyEncodedFiltersBeforeOpeningAConnection() {
        val requests=AtomicInteger();val credentials=AtomicInteger()
        val server=server {exchange ->
            requests.incrementAndGet();assertEquals("from=0&action=handoff%3Aforce",exchange.requestURI.rawQuery)
            val bytes="{}".toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        }
        try {
            val client=TeamHttpTransport(endpoint(server),TeamCredentialProvider {credentials.incrementAndGet();TeamSecrets.random()},true)
            val base="teams/${java.util.UUID.randomUUID()}/audit"
            client.request("GET","$base?from=0&action=handoff%3Aforce",null)
            for(path in listOf("$base?from=0&action=handoff:force","$base?from=0&action=handoff%253Aforce","$base?from=0&action=teamCreated&extra=1","$base?from=-1&action=","$base?from=0&action=%2F..%2F","$base?from=0&action=&before=1&before=2"))
                assertThrows(IllegalArgumentException::class.java) {client.request("GET",path,null)}
            assertThrows(IllegalArgumentException::class.java) {client.request("POST","$base?from=0&action=handoff%3Aforce",null)}
            assertEquals(1,requests.get());assertEquals(1,credentials.get())
        } finally {server.stop(0)}
    }
}
