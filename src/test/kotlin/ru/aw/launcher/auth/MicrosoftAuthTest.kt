package ru.aw.launcher.auth

import kotlinx.coroutines.CancellationException
import com.sun.net.httpserver.HttpServer
import net.lenni0451.commons.httpclient.HttpResponse
import net.lenni0451.commons.httpclient.content.HttpContent
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException
import net.lenni0451.commons.httpclient.requests.impl.PostRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MicrosoftAuthTest {
    private val auth = MicrosoftAuth()

    @Test
    fun `device failures never disclose library response bodies or tokens`() {
        val sensitiveFailure = IllegalStateException("response contained secret-refresh-token")
        val failure = assertThrows(AuthException::class.java) {
            auth.deviceRequest { throw sensitiveFailure }
        }
        assertFalse(failure.stackTraceToString().contains("secret-refresh-token"))
        assertNull(failure.cause)
    }

    @Test
    fun `cancelling device sign in stays cancellation rather than a login error`() {
        val cancellation = CancellationException("Cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            auth.deviceRequest { throw cancellation }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `device polling interruption is preserved for coroutine cancellation`() {
        val interruption = InterruptedException("Interrupted")
        val thrown = assertThrows(InterruptedException::class.java) {
            auth.deviceRequest { throw interruption }
        }
        assertSame(interruption, thrown)
    }

    @Test
    fun `shared network preserves Xbox signatures payload bytes and error responses`() {
        val payload = "{\"RelyingParty\":\"rp://api.minecraftservices.com/\"}"
        val responseBody = "{\"error\":\"authorization_pending\"}"
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            val outgoing = chain.request()
            assertEquals("POST", outgoing.method)
            assertEquals("test-xbox-signature", outgoing.header("Signature"))
            assertEquals(payload, Buffer().also { outgoing.body!!.writeTo(it) }.readUtf8())
            Response.Builder().request(outgoing).protocol(Protocol.HTTP_1_1)
                .code(400).message("Bad Request").body(responseBody.toResponseBody())
                .header("X-Test-Response", "preserved").build()
        }.build()
        val request = PostRequest("https://sisu.xboxlive.com/authorize")
        request.setHeader("Signature", "test-xbox-signature")
        request.setContent(HttpContent.string(payload))
        val response = auth.deviceHttpClient(network).execute(request)
        assertEquals(400, response.statusCode)
        assertEquals(responseBody, response.content.asString)
        assertEquals("preserved", response.getFirstHeader("X-Test-Response").orElseThrow())
    }

    @Test
    fun `auth requests do not follow redirects with sensitive post bodies`() {
        val redirects = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { exchange ->
            exchange.responseHeaders.add("Location", "/redirected")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/redirected") { exchange ->
            redirects.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val request = PostRequest("http://127.0.0.1:${server.address.port}/login")
            request.setContent(HttpContent.string("test-only-refresh-token"))
            assertEquals(307, auth.deviceHttpClient().execute(request).statusCode)
            assertEquals(0, redirects.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP errors identify the failing service without disclosing response tokens`() {
        val response = HttpResponse(URI.create("https://api.minecraftservices.com/launcher/login?secret=hidden").toURL(),
            403, "secret-access-token".toByteArray(), emptyMap())
        val failure = assertThrows(AuthException::class.java) {
            auth.deviceRequest { throw HttpRequestException(response, "secret-access-token") }
        }
        assertEquals("Сервис api.minecraftservices.com отклонил вход (HTTP 403)", failure.message)
        assertFalse(failure.stackTraceToString().contains("secret-access-token"))
        assertNull(failure.cause)
    }
}
