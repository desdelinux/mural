package chat.mural.network

import chat.mural.network.ChatGPTOAuth.Callback
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ChatGPTOAuthTest {
    @Test fun pkceMatchesTheRfcExampleAndGeneratesUrlSafeVerifiers() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGPTOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val pkce = ChatGPTOAuth.pkce()
        assertTrue(pkce.verifier.length in 43..128)
        assertTrue(pkce.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(ChatGPTOAuth.challenge(pkce.verifier), pkce.challenge)
        assertNotEquals(ChatGPTOAuth.state(), ChatGPTOAuth.state())
        assertFalse(pkce.toString().contains(pkce.verifier))
    }

    @Test fun authorizeUrlCarriesTheCodexLoopbackRequest() {
        val url = ChatGPTOAuth.authorizeUrl(ChatGPTOAuth.Pkce("v", "c"), "s", 1455)
        assertEquals("https", url.scheme); assertEquals("auth.openai.com", url.host); assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals(mapOf("response_type" to "code", "client_id" to ChatGPTOAuth.CLIENT_ID,
            "redirect_uri" to "http://localhost:1455/auth/callback",
            "scope" to "openid profile email offline_access api.connectors.read api.connectors.invoke",
            "code_challenge" to "c", "code_challenge_method" to "S256", "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true", "state" to "s", "originator" to "codex_cli_rs"),
            url.queryParameterNames.associateWith { url.queryParameter(it) })
    }

    @Test fun onlyThisLoginsCallbackIsAccepted() {
        assertEquals(Callback.Code("abc"), ChatGPTOAuth.parseCallback("GET /auth/callback?code=abc&state=s1 HTTP/1.1", "s1"))
        assertEquals(Callback.Denied("access_denied"), ChatGPTOAuth.parseCallback("GET /auth/callback?error=access_denied&state=s1 HTTP/1.1", "s1"))
        listOf("GET /auth/callback?code=abc&state=other HTTP/1.1", "GET /auth/callback?code=abc HTTP/1.1",
            "POST /auth/callback?code=abc&state=s1 HTTP/1.1", "GET /favicon.ico HTTP/1.1",
            "GET /auth/callback?code=&state=s1 HTTP/1.1", "GET http://evil.test/auth/callback?code=a&state=s1 HTTP/1.1",
            "GET /auth/callback?code=${"a".repeat(9_000)}&state=s1 HTTP/1.1", "garbage").forEach {
            assertEquals(it.take(40), Callback.Ignored, ChatGPTOAuth.parseCallback(it, "s1"))
        }
        assertFalse(Callback.Code("secret").toString().contains("secret"))
    }

    @Test fun codeExchangeIsAFormAndRefreshIsJson() {
        val exchange = ChatGPTOAuth.exchangeRequest("code-1", "verifier-1", 1457)
        assertEquals("https://auth.openai.com/oauth/token", exchange.url.toString())
        val form = exchange.body as FormBody
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "code-1",
            "redirect_uri" to "http://localhost:1457/auth/callback", "client_id" to ChatGPTOAuth.CLIENT_ID, "code_verifier" to "verifier-1"),
            (0 until form.size).associate { form.name(it) to form.value(it) })

        val refresh = ChatGPTOAuth.refreshRequest("refresh-1")
        val body = Json.parseToJsonElement(Buffer().also { refresh.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("refresh_token", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals("refresh-1", body["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(ChatGPTOAuth.CLIENT_ID, body["client_id"]!!.jsonPrimitive.content)
    }

    @Test fun sessionReadsAccountPlanAndExpiryFromTheTokens() {
        val access = ChatGPTTestTokens.access(expiresAtSeconds = 2_000_000)
        val identity = ChatGPTTestTokens.jwt(buildJsonObject {
            putJsonObject("https://api.openai.com/auth") { put("chatgpt_account_id", "account-9"); put("chatgpt_plan_type", "pro") }
        })
        val session = ChatGPTOAuth.session(buildJsonObject {
            put("access_token", access); put("refresh_token", "refresh-1"); put("id_token", identity)
        }, now = 0)
        assertEquals(ChatGPTSession("account-9", access, "refresh-1", 2_000_000_000, "pro"), session)
        assertFalse(session.toString().contains(access))
    }

    @Test fun refreshWithoutRotationKeepsThePreviousRefreshTokenAndAccount() {
        val previous = ChatGPTTestTokens.session()
        val next = ChatGPTOAuth.session(buildJsonObject { put("access_token", "opaque"); put("expires_in", 60) }, now = 1_000, previous)
        assertEquals(ChatGPTSession("account-1", "opaque", "refresh-1", 61_000, "plus"), next)
        assertThrows(ChatGPTFailure.InvalidResponse::class.java) {
            ChatGPTOAuth.session(buildJsonObject { put("access_token", "opaque") }, now = 0)
        }
    }

    @Test fun sessionsRefreshFiveMinutesBeforeExpiry() {
        val session = ChatGPTTestTokens.session(expiresAt = 1_000_000)
        assertFalse(session.needsRefresh(700_000))
        assertTrue(session.needsRefresh(700_001))
    }
}
