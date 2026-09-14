package chat.mural.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

interface ChatGPTSessionStorage {
    suspend fun read(): ChatGPTSession?
    suspend fun save(session: ChatGPTSession)
    suspend fun clear()
}

/** Supplies a usable ChatGPT session, refreshing it near expiry or once after the backend rejects it. */
class ChatGPTAccount internal constructor(
    private val storage: ChatGPTSessionStorage,
    private val client: OkHttpClient,
    private val issuer: HttpUrl,
    private val now: () -> Long,
) {
    constructor(storage: ChatGPTSessionStorage) :
        this(storage, chatGPTHttpClient(), ChatGPTOAuth.ISSUER, System::currentTimeMillis)

    private val refreshing = Mutex()

    suspend fun current(): ChatGPTSession {
        val session = storage.read() ?: throw ChatGPTFailure.SignInRequired
        return if (session.needsRefresh(now())) refresh(session) else session
    }

    /** A concurrent caller may already have replaced the rejected token; that session is reused. */
    suspend fun refreshAfterRejection(rejected: ChatGPTSession): ChatGPTSession = refresh(rejected, force = true)

    private suspend fun refresh(stale: ChatGPTSession, force: Boolean = false): ChatGPTSession = refreshing.withLock {
        val stored = storage.read() ?: throw ChatGPTFailure.SignInRequired
        if (stored.accessToken != stale.accessToken && !stored.needsRefresh(now())) return@withLock stored
        if (!force && !stored.needsRefresh(now())) return@withLock stored
        val response = client.fetch(ChatGPTOAuth.refreshRequest(stored.refreshToken, issuer), MAX_TOKEN_BYTES)
        when (response.status) {
            200 -> Unit
            400, 401 -> throw ChatGPTFailure.SignInRequired
            else -> throw ChatGPTFailure.Http(response.status)
        }
        val tokens = try { Json.parseToJsonElement(response.body) as? JsonObject } catch (_: Exception) { null }
            ?: throw ChatGPTFailure.InvalidResponse
        ChatGPTOAuth.session(tokens, now(), stored).also { storage.save(it) }
    }

    private companion object { const val MAX_TOKEN_BYTES = 65_536L }
}
