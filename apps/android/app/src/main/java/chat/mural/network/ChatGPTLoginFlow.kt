package chat.mural.network

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Runs one ChatGPT sign-in: a loopback listener exists only while the browser completes the login. */
class ChatGPTLoginFlow internal constructor(
    private val client: OkHttpClient,
    private val issuer: HttpUrl,
    private val ports: List<Int>,
    private val now: () -> Long,
) {
    constructor() : this(chatGPTHttpClient(), ChatGPTOAuth.ISSUER, ChatGPTOAuth.REDIRECT_PORTS, System::currentTimeMillis)

    class Pending internal constructor(val authorizeUrl: HttpUrl, internal val server: ServerSocket,
        internal val pkce: ChatGPTOAuth.Pkce, internal val state: String) {
        internal val port get() = server.localPort
        override fun toString() = "Pending([redacted])"
    }

    fun begin(): Pending {
        val server = ports.firstNotNullOfOrNull { port ->
            try { ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")) } catch (_: Exception) { null }
        } ?: throw ChatGPTFailure.PortUnavailable
        val pkce = ChatGPTOAuth.pkce()
        val state = ChatGPTOAuth.state()
        return Pending(ChatGPTOAuth.authorizeUrl(pkce, state, server.localPort, issuer), server, pkce, state)
    }

    suspend fun await(pending: Pending, timeoutMilliseconds: Long = 600_000): ChatGPTSession = withContext(Dispatchers.IO) {
        try {
            val code = withTimeout(timeoutMilliseconds) { receiveCode(pending) }
            val response = client.fetch(ChatGPTOAuth.exchangeRequest(code, pending.pkce.verifier, pending.port, issuer), 65_536)
            if (response.status != 200) throw ChatGPTFailure.Http(response.status)
            val tokens = try { Json.parseToJsonElement(response.body) as? JsonObject } catch (_: Exception) { null }
                ?: throw ChatGPTFailure.InvalidResponse
            ChatGPTOAuth.session(tokens, now())
        } finally { cancel(pending) }
    }

    fun cancel(pending: Pending) { try { pending.server.close() } catch (_: Exception) { } }

    private suspend fun receiveCode(pending: Pending): String {
        pending.server.soTimeout = 500
        while (true) {
            coroutineContextActive()
            val socket = try { pending.server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 5_000
                val line = try { it.getInputStream().readRequestLine() } catch (_: Exception) { null }
                when (val callback = line?.let { request -> ChatGPTOAuth.parseCallback(request, pending.state) } ?: ChatGPTOAuth.Callback.Ignored) {
                    is ChatGPTOAuth.Callback.Code -> { it.respond(200, DONE_PAGE); return callback.code }
                    is ChatGPTOAuth.Callback.Denied -> { it.respond(400, DENIED_PAGE); throw ChatGPTFailure.Denied }
                    ChatGPTOAuth.Callback.Ignored -> it.respond(404, "")
                }
            }
        }
    }

    private suspend fun coroutineContextActive() = kotlin.coroutines.coroutineContext.ensureActive()

    private companion object {
        const val DONE_PAGE = "<!doctype html><meta name=viewport content='width=device-width'><title>Mural</title>" +
            "<p style='font:18px sans-serif;margin:32px'>Signed in. You can return to Mural.</p>"
        const val DENIED_PAGE = "<!doctype html><meta name=viewport content='width=device-width'><title>Mural</title>" +
            "<p style='font:18px sans-serif;margin:32px'>Sign-in was cancelled. You can return to Mural.</p>"
    }
}

private fun InputStream.readRequestLine(): String? {
    val bytes = java.io.ByteArrayOutputStream()
    while (bytes.size() < 8_193) {
        val next = read()
        if (next == -1) return null
        if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
        bytes.write(next)
    }
    return null
}

private fun Socket.respond(status: Int, html: String) {
    val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
    val body = html.toByteArray(Charsets.UTF_8)
    try {
        getOutputStream().apply {
            write(("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(body); flush()
        }
    } catch (_: Exception) { }
}
