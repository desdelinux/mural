package chat.mural.network

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** A ChatGPT sign-in. Tokens never appear in logs or string conversions. */
@Serializable
data class ChatGPTSession(val accountID: String, val accessToken: String, val refreshToken: String,
    val expiresAtMilliseconds: Long, val planType: String? = null) {
    fun needsRefresh(now: Long) = expiresAtMilliseconds - now < ChatGPTOAuth.REFRESH_WINDOW_MILLISECONDS
    override fun toString() = "ChatGPTSession([redacted])"
}

sealed class ChatGPTFailure(message: String) : IOException(message) {
    data object SignInRequired : ChatGPTFailure("Sign in with ChatGPT in Settings to continue.")
    data object InvalidResponse : ChatGPTFailure("ChatGPT returned an unexpected response.")
    data object PortUnavailable : ChatGPTFailure("ChatGPT sign-in could not start on this device.")
    data object Denied : ChatGPTFailure("ChatGPT sign-in was cancelled.")
    data object SecureStorage : ChatGPTFailure("The ChatGPT sign-in couldn't be stored securely on this device.")
    class Http(val status: Int) : ChatGPTFailure("ChatGPT could not complete the request (HTTP $status).")
}

/** OAuth for the ChatGPT sign-in Codex CLI uses: authorization code with PKCE and a loopback redirect. */
object ChatGPTOAuth {
    val ISSUER: HttpUrl = "https://auth.openai.com/".toHttpUrl()
    val BACKEND: HttpUrl = "https://chatgpt.com/backend-api/".toHttpUrl()
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val ORIGINATOR = "codex_cli_rs"
    val REDIRECT_PORTS = listOf(1455, 1457)
    const val CALLBACK_PATH = "/auth/callback"
    const val REFRESH_WINDOW_MILLISECONDS = 300_000L
    private const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private val FORM_JSON = "application/json; charset=utf-8".toMediaType()

    data class Pkce(val verifier: String, val challenge: String) {
        override fun toString() = "Pkce([redacted])"
    }

    sealed interface Callback {
        data class Code(val code: String) : Callback { override fun toString() = "Code([redacted])" }
        data class Denied(val error: String) : Callback
        data object Ignored : Callback
    }

    fun pkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifier = base64Url(ByteArray(48).also(random::nextBytes))
        return Pkce(verifier, challenge(verifier))
    }

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun state(random: SecureRandom = SecureRandom()): String = base64Url(ByteArray(32).also(random::nextBytes))

    fun redirectUri(port: Int) = "http://localhost:$port$CALLBACK_PATH"

    fun authorizeUrl(pkce: Pkce, state: String, port: Int, issuer: HttpUrl = ISSUER): HttpUrl =
        issuer.newBuilder().addPathSegments("oauth/authorize")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", redirectUri(port))
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("originator", ORIGINATOR)
            .build()

    /** Reads only the HTTP request line of a loopback request. Anything but this login's callback is ignored. */
    fun parseCallback(requestLine: String, expectedState: String): Callback {
        if (requestLine.length > 8_192) return Callback.Ignored
        val parts = requestLine.trim().split(' ')
        if (parts.size != 3 || parts[0] != "GET" || !parts[1].startsWith("/")) return Callback.Ignored
        val url = "http://localhost${parts[1]}".toHttpUrlOrNull() ?: return Callback.Ignored
        if (url.encodedPath != CALLBACK_PATH) return Callback.Ignored
        val state = url.queryParameter("state") ?: return Callback.Ignored
        if (!MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) return Callback.Ignored
        url.queryParameter("error")?.let { return Callback.Denied(it.take(64)) }
        val code = url.queryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 4_096 } ?: return Callback.Ignored
        return Callback.Code(code)
    }

    fun exchangeRequest(code: String, verifier: String, port: Int, issuer: HttpUrl = ISSUER): Request =
        Request.Builder().url(issuer.newBuilder().addPathSegments("oauth/token").build())
            .post(FormBody.Builder()
                .add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", redirectUri(port)).add("client_id", CLIENT_ID)
                .add("code_verifier", verifier).build())
            .build()

    fun refreshRequest(refreshToken: String, issuer: HttpUrl = ISSUER): Request =
        Request.Builder().url(issuer.newBuilder().addPathSegments("oauth/token").build())
            .post(buildJsonObject {
                put("client_id", CLIENT_ID); put("grant_type", "refresh_token"); put("refresh_token", refreshToken)
            }.toString().toRequestBody(FORM_JSON))
            .build()

    /** Builds a session from a token response. A refresh may omit the ID token or rotate nothing. */
    fun session(tokens: JsonObject, now: Long, previous: ChatGPTSession? = null): ChatGPTSession {
        val accessToken = tokens.string("access_token") ?: throw ChatGPTFailure.InvalidResponse
        val refreshToken = tokens.string("refresh_token") ?: previous?.refreshToken ?: throw ChatGPTFailure.InvalidResponse
        val access = claims(accessToken)
        val identity = tokens.string("id_token")?.let(::claims)
        val auth = (identity?.get(AUTH_CLAIM) ?: access?.get(AUTH_CLAIM)) as? JsonObject
        val accountID = auth?.string("chatgpt_account_id") ?: previous?.accountID ?: throw ChatGPTFailure.InvalidResponse
        if (accountID.isBlank() || accountID.length > 128) throw ChatGPTFailure.InvalidResponse
        val expiresAt = (access?.get("exp") as? JsonPrimitive)?.longOrNull?.times(1_000)
            ?: (tokens["expires_in"] as? JsonPrimitive)?.longOrNull?.let { now + it * 1_000 }
            ?: (now + 3_600_000)
        return ChatGPTSession(accountID, accessToken, refreshToken, expiresAt, auth?.string("chatgpt_plan_type") ?: previous?.planType)
    }

    /** Decodes a JWT payload without verifying it. The claims are only echoed back to the issuer's own backend. */
    internal fun claims(jwt: String): JsonObject? = try {
        val payload = jwt.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)) as? JsonObject
    } catch (_: Exception) { null }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun chatGPTHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .cache(null)
    .build()

internal class BoundedResponse(val status: Int, val body: String, val location: String?)

/** Executes a call whose body is read on OkHttp's worker and capped, cancelling the call with the coroutine. */
internal suspend fun OkHttpClient.fetch(request: Request, maxBytes: Long): BoundedResponse =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { BoundedResponse(it.code, it.readBounded(maxBytes), it.header("Location")) }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

private fun Response.readBounded(maxBytes: Long): String {
    val source = body?.source() ?: return ""
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, 8_192)
        if (count == -1L) break
        if (buffer.size > maxBytes) throw ChatGPTFailure.InvalidResponse
    }
    return buffer.readString(Charsets.UTF_8)
}

private fun String.toHttpUrlOrNull(): HttpUrl? = try { toHttpUrl() } catch (_: IllegalArgumentException) { null }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
