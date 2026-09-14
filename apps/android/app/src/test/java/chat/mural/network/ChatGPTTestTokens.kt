package chat.mural.network

import java.util.Base64
import kotlinx.serialization.json.*

/** Builds unsigned JWT-shaped tokens at runtime so no token-like literal lives in the repository. */
internal object ChatGPTTestTokens {
    fun jwt(claims: JsonObject): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        return "$header.${encoder.encodeToString(claims.toString().toByteArray())}.signature"
    }

    fun access(expiresAtSeconds: Long, account: String = "account-1", plan: String = "plus") = jwt(buildJsonObject {
        put("exp", expiresAtSeconds)
        putJsonObject("https://api.openai.com/auth") { put("chatgpt_account_id", account); put("chatgpt_plan_type", plan) }
    })

    fun session(access: String = "access-1", refresh: String = "refresh-1", expiresAt: Long = Long.MAX_VALUE) =
        ChatGPTSession("account-1", access, refresh, expiresAt, "plus")

    class Storage(var value: ChatGPTSession?) : ChatGPTSessionStorage {
        var saves = 0
        override suspend fun read() = value
        override suspend fun save(session: ChatGPTSession) { saves++; value = session }
        override suspend fun clear() { value = null }
    }
}
