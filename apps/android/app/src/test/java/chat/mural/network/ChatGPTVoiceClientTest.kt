package chat.mural.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ChatGPTVoiceClientTest {
    private val server = MockWebServer()
    private val calls = mutableListOf<RecordedRequest>()
    private val refreshes = mutableListOf<RecordedRequest>()
    private var callResponses = ArrayDeque<MockResponse>()
    private var refreshResponse = MockResponse().setResponseCode(200)
    private val now = 1_000_000_000L
    private val request = LiveSessionRequest("v=0\r\noffer", "Speak Spanish.",
        buildJsonArray { add(buildJsonObject { put("type", "message"); put("role", "user") }) })

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/backend-api/codex/realtime/calls" -> { calls += request; callResponses.removeFirst() }
                "/oauth/token" -> { refreshes += request; refreshResponse }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun answer(location: String? = "/v1/realtime/calls/rtc_u2_abc") = MockResponse().setResponseCode(201)
        .setHeader("Content-Type", "text/plain").setBody("v=0\r\nanswer").apply { location?.let { setHeader("Location", it) } }

    private fun client(storage: ChatGPTSessionStorage): ChatGPTVoiceClient {
        val http = OkHttpClient.Builder().followRedirects(false).build()
        val account = ChatGPTAccount(storage, http, server.url("/"), { now })
        return ChatGPTVoiceClient(account, http, server.url("/backend-api/"))
    }

    @Test fun createsARealtimeCallWithThePlanSessionAndATranslatingDialect() = runBlocking {
        callResponses += answer()
        val connection = client(ChatGPTTestTokens.Storage(ChatGPTTestTokens.session())).createLiveSession(request)

        assertEquals("v=0\r\nanswer", connection.sdp)
        assertEquals("rtc_u2_abc", connection.providerSessionID)
        assertTrue(connection.dialect is RealtimeDialect)
        val sent = calls.single()
        assertEquals("POST", sent.method)
        assertEquals("Bearer access-1", sent.getHeader("Authorization"))
        assertEquals("account-1", sent.getHeader("chatgpt-account-id"))
        assertEquals("codex_cli_rs", sent.getHeader("originator"))
        assertNull(sent.getHeader("Cookie"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("v=0\r\noffer", body["sdp"]!!.jsonPrimitive.content)
        val session = body["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        assertEquals("gpt-realtime", session["model"]!!.jsonPrimitive.content)
        assertEquals("Speak Spanish.", session["instructions"]!!.jsonPrimitive.content)
        assertEquals("marin", session["audio"]!!.jsonObject["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertTrue(refreshes.isEmpty())
    }

    @Test fun anExpiringSessionIsRefreshedAndSavedBeforeTheCall() = runBlocking {
        val storage = ChatGPTTestTokens.Storage(ChatGPTTestTokens.session(expiresAt = now + 60_000))
        val fresh = ChatGPTTestTokens.access(expiresAtSeconds = now / 1000 + 3_600)
        refreshResponse = MockResponse().setBody(buildJsonObject { put("access_token", fresh); put("refresh_token", "refresh-2") }.toString())
        callResponses += answer()
        client(storage).createLiveSession(request)

        assertEquals(1, refreshes.size)
        assertEquals("Bearer $fresh", calls.single().getHeader("Authorization"))
        assertEquals("refresh-2", storage.value!!.refreshToken)
        assertEquals(1, storage.saves)
    }

    @Test fun aRejectedTokenIsRefreshedOnceAndTheCallRetried() = runBlocking {
        val storage = ChatGPTTestTokens.Storage(ChatGPTTestTokens.session())
        refreshResponse = MockResponse().setBody(buildJsonObject { put("access_token", "access-2"); put("expires_in", 3_600) }.toString())
        callResponses += MockResponse().setResponseCode(401)
        callResponses += answer()
        client(storage).createLiveSession(request)

        assertEquals(listOf("Bearer access-1", "Bearer access-2"), calls.map { it.getHeader("Authorization") })
        assertEquals("refresh-1", storage.value!!.refreshToken)
    }

    @Test fun aSecondRejectionAsksToSignInAgain() = runBlocking {
        refreshResponse = MockResponse().setBody(buildJsonObject { put("access_token", "access-2"); put("expires_in", 3_600) }.toString())
        callResponses += MockResponse().setResponseCode(401)
        callResponses += MockResponse().setResponseCode(401)
        val failure = runCatching { client(ChatGPTTestTokens.Storage(ChatGPTTestTokens.session())).createLiveSession(request) }.exceptionOrNull()
        assertEquals(ChatGPTFailure.SignInRequired, failure)
        assertEquals(2, calls.size)
    }

    @Test fun missingOrRevokedSignInNeverReachesTheBackend() = runBlocking {
        assertEquals(ChatGPTFailure.SignInRequired,
            runCatching { client(ChatGPTTestTokens.Storage(null)).createLiveSession(request) }.exceptionOrNull())
        refreshResponse = MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        val expiring = ChatGPTTestTokens.Storage(ChatGPTTestTokens.session(expiresAt = now))
        assertEquals(ChatGPTFailure.SignInRequired, runCatching { client(expiring).createLiveSession(request) }.exceptionOrNull())
        assertTrue(calls.isEmpty())
        assertNotNull(expiring.value)
    }

    @Test fun deniedPlansAndMalformedAnswersFailClearly() = runBlocking {
        val storage = ChatGPTTestTokens.Storage(ChatGPTTestTokens.session())
        callResponses += MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Voice session access denied."}}""")
        assertEquals(403, (runCatching { client(storage).createLiveSession(request) }.exceptionOrNull() as ChatGPTFailure.Http).status)
        callResponses += answer(location = null)
        assertEquals(ChatGPTFailure.InvalidResponse, runCatching { client(storage).createLiveSession(request) }.exceptionOrNull())
        callResponses += answer(location = "/v1/realtime/calls/../../other")
        assertEquals(ChatGPTFailure.InvalidResponse, runCatching { client(storage).createLiveSession(request) }.exceptionOrNull())
        callResponses += MockResponse().setResponseCode(201).setHeader("Location", "/v1/realtime/calls/rtc_x").setBody("<html>")
        assertEquals(ChatGPTFailure.InvalidResponse, runCatching { client(storage).createLiveSession(request) }.exceptionOrNull())
        callResponses += MockResponse().setResponseCode(302).setHeader("Location", "https://evil.test/")
        assertEquals(302, (runCatching { client(storage).createLiveSession(request) }.exceptionOrNull() as ChatGPTFailure.Http).status)
    }

    @Test fun concurrentRejectionsReuseTheSessionAnotherCallerRefreshed() = runBlocking {
        val storage = ChatGPTTestTokens.Storage(ChatGPTTestTokens.session(access = "access-2"))
        val http = OkHttpClient()
        val account = ChatGPTAccount(storage, http, server.url("/"), { now })
        val reused = account.refreshAfterRejection(ChatGPTTestTokens.session(access = "access-1"))
        assertEquals("access-2", reused.accessToken)
        assertTrue(refreshes.isEmpty())
    }
}
