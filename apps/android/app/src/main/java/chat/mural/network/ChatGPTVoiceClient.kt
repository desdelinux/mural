package chat.mural.network

import java.util.UUID
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Opens gpt-realtime voice calls through the signed-in ChatGPT plan instead of an API key. */
class ChatGPTVoiceClient internal constructor(
    private val account: ChatGPTAccount,
    private val client: OkHttpClient,
    private val backend: HttpUrl,
) : LiveSessionProvider {
    constructor(account: ChatGPTAccount) : this(account, chatGPTHttpClient(), ChatGPTOAuth.BACKEND)

    override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
        val session = account.current()
        var response = create(session, request)
        if (response.status == 401) response = create(account.refreshAfterRejection(session), request)
        when (response.status) {
            201 -> Unit
            401 -> throw ChatGPTFailure.SignInRequired
            else -> throw ChatGPTFailure.Http(response.status)
        }
        val callID = response.location?.substringAfterLast('/')?.takeIf { CALL_ID.matches(it) }
            ?: throw ChatGPTFailure.InvalidResponse
        if (!response.body.startsWith("v=0")) throw ChatGPTFailure.InvalidResponse
        return LiveSessionConnection(response.body, callID, dialect = RealtimeDialect(request.instructions, request.history))
    }

    private suspend fun create(session: ChatGPTSession, request: LiveSessionRequest): BoundedResponse {
        val body = buildJsonObject {
            put("sdp", request.sdp)
            putJsonObject("session") {
                put("type", "realtime"); put("model", RealtimeDialect.MODEL); put("instructions", request.instructions)
                putJsonObject("audio") { putJsonObject("output") { put("voice", RealtimeDialect.VOICE) } }
            }
        }
        val sessionID = UUID.randomUUID().toString()
        return client.fetch(Request.Builder()
            .url(backend.newBuilder().addPathSegments("codex/realtime/calls").build())
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("chatgpt-account-id", session.accountID)
            .header("originator", ChatGPTOAuth.ORIGINATOR)
            .header("session-id", sessionID)
            .header("x-session-id", sessionID)
            .post(body.toString().toRequestBody(JSON))
            .build(), MAX_SDP_BYTES)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val CALL_ID = Regex("rtc_[A-Za-z0-9_-]{1,128}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        const val MAX_SDP_BYTES = 65_536L
    }
}
