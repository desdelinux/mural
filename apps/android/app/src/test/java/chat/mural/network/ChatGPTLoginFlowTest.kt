package chat.mural.network

import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ChatGPTLoginFlowTest {
    private val issuer = MockWebServer().apply { start() }
    private val browser = OkHttpClient.Builder().followRedirects(false).build()

    @After fun stop() = issuer.shutdown()

    private fun flow(ports: List<Int> = listOf(0)) = ChatGPTLoginFlow(OkHttpClient(), issuer.url("/"), ports) { 0L }

    private fun visit(pending: ChatGPTLoginFlow.Pending, query: String, path: String = "/auth/callback") =
        browser.newCall(Request.Builder().url("http://127.0.0.1:${pending.port}$path?$query").build()).execute().use { it.code }

    private fun tokens() = buildJsonObject {
        put("access_token", ChatGPTTestTokens.access(expiresAtSeconds = 7_200))
        put("refresh_token", "refresh-1")
    }.toString()

    @Test fun strayRequestsAreIgnoredUntilTheMatchingCallbackIsExchanged() = runBlocking {
        issuer.enqueue(MockResponse().setBody(tokens()))
        val login = flow()
        val pending = login.begin()
        assertEquals(pending.port.toString(), pending.authorizeUrl.queryParameter("redirect_uri")!!.substringAfterLast(':').substringBefore('/'))
        val session = async { login.await(pending, 10_000) }

        assertEquals(404, withContext(Dispatchers.IO) { visit(pending, "code=wrong&state=forged") })
        assertEquals(404, withContext(Dispatchers.IO) { visit(pending, "state=${pending.state}", path = "/favicon.ico") })
        assertEquals(200, withContext(Dispatchers.IO) { visit(pending, "code=real-code&state=${pending.state}") })

        val result = session.await()
        assertEquals("account-1", result.accountID)
        assertEquals("refresh-1", result.refreshToken)
        val exchange = issuer.takeRequest()
        assertEquals("/oauth/token", exchange.path)
        val form = exchange.body.readUtf8()
        assertTrue(form.contains("code=real-code"))
        assertTrue(form.contains("code_verifier=${pending.pkce.verifier}"))
        assertTrue(form.contains("redirect_uri=http%3A%2F%2Flocalhost%3A${pending.port}%2Fauth%2Fcallback"))
        assertTrue(pending.server.isClosed)
        assertEquals(1, issuer.requestCount)
    }

    @Test fun aDeniedLoginStopsWithoutExchangingACode() = runBlocking {
        val login = flow()
        val pending = login.begin()
        val session = async { runCatching { login.await(pending, 10_000) }.exceptionOrNull() }
        assertEquals(400, withContext(Dispatchers.IO) { visit(pending, "error=access_denied&state=${pending.state}") })
        assertEquals(ChatGPTFailure.Denied, session.await())
        assertEquals(0, issuer.requestCount)
        assertTrue(pending.server.isClosed)
    }

    @Test fun cancellationAndTimeoutCloseTheListener() = runBlocking {
        val login = flow()
        val cancelled = login.begin()
        val job = launch(Dispatchers.IO) { login.await(cancelled, 10_000) }
        delay(100); job.cancelAndJoin()
        assertTrue(cancelled.server.isClosed)

        val timedOut = login.begin()
        assertTrue(runCatching { login.await(timedOut, 300) }.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(timedOut.server.isClosed)
    }

    @Test fun rejectedExchangeAndBusyPortsFailClearly() = runBlocking {
        issuer.enqueue(MockResponse().setResponseCode(400))
        val login = flow()
        val pending = login.begin()
        val session = async { runCatching { login.await(pending, 10_000) }.exceptionOrNull() }
        withContext(Dispatchers.IO) { visit(pending, "code=c&state=${pending.state}") }
        assertEquals(400, (session.await() as ChatGPTFailure.Http).status)

        ServerSocket(0).use { busy ->
            assertEquals(ChatGPTFailure.PortUnavailable, runCatching { flow(listOf(busy.localPort)).begin() }.exceptionOrNull())
        }
    }
}
