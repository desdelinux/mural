package chat.mural.network

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Runs Mural's text helpers on the signed-in ChatGPT plan with the same model the API key uses. */
class ChatGPTTeachingClient internal constructor(
    private val account: ChatGPTAccount,
    private val client: OkHttpClient,
    private val backend: HttpUrl,
) : TeachingClient {
    constructor(account: ChatGPTAccount) : this(account, chatGPTHttpClient(), ChatGPTOAuth.BACKEND)

    override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
        purpose: HelperPurpose?): APIResult {
        val body = buildJsonObject {
            put("model", MODEL); put("instructions", instructions)
            putJsonArray("input") { add(buildJsonObject {
                put("type", "message"); put("role", "user")
                putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", input) }) }
            }) }
            putJsonArray("tools") { if (search) add(buildJsonObject { put("type", "web_search") }) }
            put("tool_choice", "auto"); put("parallel_tool_calls", false)
            putJsonObject("reasoning") { put("effort", "low") }
            put("store", false); put("stream", true); putJsonArray("include") { }
            if (schema != null) putJsonObject("text") { putJsonObject("format") {
                put("type", "json_schema"); put("name", "mural_result"); put("strict", true); put("schema", schema)
            } }
        }
        val session = account.current()
        var response = send(session, body)
        if (response.status == 401) response = send(account.refreshAfterRejection(session), body)
        when (response.status) {
            200 -> Unit
            401 -> throw ChatGPTFailure.SignInRequired
            else -> throw ChatGPTFailure.Http(response.status)
        }
        return decodeTeachingResponse(completedResponse(response.body))
    }

    private suspend fun send(session: ChatGPTSession, body: JsonObject) = client.fetch(Request.Builder()
        .url(backend.newBuilder().addPathSegments("codex/responses").build())
        .chatGPTSession(session)
        .header("Accept", "text/event-stream")
        .post(body.toString().toRequestBody(JSON))
        .build(), MAX_STREAM_BYTES)

    internal companion object {
        const val MODEL = "gpt-5.6-luna"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_STREAM_BYTES = 2_097_152L

        /** The plan backend streams output items but sends the final response without them. */
        fun completedResponse(stream: String): JsonObject {
            val items = mutableListOf<JsonElement>()
            var final: JsonObject? = null
            for (block in stream.replace("\r\n", "\n").split("\n\n")) {
                val data = block.lineSequence().filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:").trim() }
                if (data.isEmpty() || data == "[DONE]") continue
                val event = try { Json.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null } ?: continue
                when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
                    "response.output_item.done" -> (event["item"] as? JsonObject)?.let(items::add)
                    "response.completed", "response.incomplete" -> final = event["response"] as? JsonObject
                    "response.failed", "error" -> throw ChatGPTFailure.InvalidResponse
                }
            }
            val response = final ?: throw APIClient.APIException.Incomplete
            val output = (response["output"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: JsonArray(items)
            return JsonObject(response + ("output" to output))
        }
    }
}
