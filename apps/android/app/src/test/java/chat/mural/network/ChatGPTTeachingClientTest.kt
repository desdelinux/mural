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

class ChatGPTTeachingClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val responses = ArrayDeque<MockResponse>()
    private var refreshResponse = MockResponse().setResponseCode(500)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/backend-api/codex/responses" -> { requests += request; responses.removeFirst() }
                "/oauth/token" -> refreshResponse
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun client(storage: ChatGPTSessionStorage = ChatGPTTestTokens.Storage(ChatGPTTestTokens.session())): ChatGPTTeachingClient {
        val http = OkHttpClient.Builder().followRedirects(false).build()
        return ChatGPTTeachingClient(ChatGPTAccount(storage, http, server.url("/"), { 0L }), http, server.url("/backend-api/"))
    }

    private fun sse(vararg events: JsonObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "event: ${it["type"]!!.jsonPrimitive.content}\ndata: $it\n\n" })

    private fun message(text: String, url: String? = null) = buildJsonObject {
        put("type", "response.output_item.done")
        putJsonObject("item") {
            put("type", "message"); put("role", "assistant")
            putJsonArray("content") { add(buildJsonObject {
                put("type", "output_text"); put("text", text)
                putJsonArray("annotations") { url?.let { add(buildJsonObject { put("type", "url_citation"); put("url", it); put("title", "EFE") }) } }
            }) }
        }
    }
    private fun completed(status: String = "completed") = buildJsonObject {
        put("type", "response.completed")
        putJsonObject("response") {
            put("status", status); putJsonArray("output") { }
            putJsonObject("usage") { put("input_tokens", 29); put("output_tokens", 10) }
        }
    }

    @Test fun streamedItemsBecomeTheHelperResultWithSourcesAndUsage() = runBlocking {
        responses += sse(buildJsonObject { put("type", "response.created") },
            buildJsonObject { put("type", "response.output_text.delta"); put("delta", "Hoy") },
            buildJsonObject { put("type", "response.output_item.done"); putJsonObject("item") { put("type", "web_search_call") } },
            message("Hoy llueve en Madrid.", "https://efe.com/noticia"), completed())

        val result = client().respond("Answer briefly.", "¿Qué pasa hoy?", search = true)

        assertEquals("Hoy llueve en Madrid.", result.text)
        assertEquals(listOf("https://efe.com/noticia"), result.sources.map { it.url })
        assertEquals(APIUsage(29, 10, 1), result.usage)
        val sent = requests.single()
        assertEquals("text/event-stream", sent.getHeader("Accept"))
        assertEquals("Bearer access-1", sent.getHeader("Authorization"))
        assertEquals("account-1", sent.getHeader("chatgpt-account-id"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("gpt-5.6-luna", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertEquals("web_search", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("¿Qué pasa hoy?", body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull("The plan backend rejects this field", body["max_output_tokens"])
        assertNull(body["text"])
    }

    @Test fun schemasAreSentStrictAndSearchIsOmittedWhenNotRequested() = runBlocking {
        responses += sse(message("""{"outcome":"progress"}"""), completed())
        val schema = buildJsonObject { put("type", "object") }
        client().respond("Assess.", "context", schema = schema)
        val body = Json.parseToJsonElement(requests.single().body.readUtf8()).jsonObject
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals(true, format["strict"]!!.jsonPrimitive.boolean)
        assertEquals(schema, format["schema"])
        assertTrue(body["tools"]!!.jsonArray.isEmpty())
    }

    @Test fun failedIncompleteRefusedAndTruncatedStreamsAreErrors() = runBlocking {
        responses += sse(buildJsonObject { put("type", "response.failed"); putJsonObject("response") { put("status", "failed") } })
        assertEquals(ChatGPTFailure.InvalidResponse, runCatching { client().respond("i", "x") }.exceptionOrNull())
        responses += sse(message("partial"), completed(status = "incomplete"))
        assertEquals(APIClient.APIException.Incomplete, runCatching { client().respond("i", "x") }.exceptionOrNull())
        responses += sse(message("cut off"))
        assertEquals(APIClient.APIException.Incomplete, runCatching { client().respond("i", "x") }.exceptionOrNull())
        responses += sse(buildJsonObject {
            put("type", "response.output_item.done")
            putJsonObject("item") { put("type", "message"); putJsonArray("content") { add(buildJsonObject { put("type", "refusal") }) } }
        }, completed())
        assertEquals(APIClient.APIException.Refused, runCatching { client().respond("i", "x") }.exceptionOrNull())
    }

    @Test fun planLimitsAndRejectedSessionsSurfaceAsChatGPTFailures() = runBlocking {
        responses += MockResponse().setResponseCode(429)
        assertEquals(429, (runCatching { client().respond("i", "x") }.exceptionOrNull() as ChatGPTFailure.Http).status)
        refreshResponse = MockResponse().setBody(buildJsonObject { put("access_token", "access-2"); put("expires_in", 3_600) }.toString())
        responses += MockResponse().setResponseCode(401)
        responses += sse(message("ok"), completed())
        assertEquals("ok", client().respond("i", "x").text)
        assertEquals(listOf("Bearer access-1", "Bearer access-1", "Bearer access-2"), requests.map { it.getHeader("Authorization") })
    }

    @Test fun completedOutputIsKeptWhenTheBackendIncludesIt() {
        val stream = "data: " + buildJsonObject {
            put("type", "response.completed")
            putJsonObject("response") {
                put("status", "completed")
                putJsonArray("output") { add(message("from final")["item"]!!) }
            }
        } + "\r\n\r\n"
        val response = ChatGPTTeachingClient.completedResponse(stream)
        assertEquals("from final", decodeTeachingResponse(response).text)
    }
}
