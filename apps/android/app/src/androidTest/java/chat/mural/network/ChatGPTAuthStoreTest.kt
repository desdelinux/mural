package chat.mural.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatGPTAuthStoreTest {
    @Test fun signInSurvivesRecreationEncryptedAndClearsIndependentlyOfOtherCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", context.packageName)
        val store = ChatGPTAuthStore(context, "mural_chatgpt_session_test", "chat.mural.chatgpt.test.aes")
        val apiKey = CredentialStore(context, "mural_chatgpt_test_openai", "chat.mural.chatgpt.test.openai")
        val session = ChatGPTSession("account-offline-test", "a".repeat(900), "r".repeat(120), System.currentTimeMillis() + 86_400_000, "plus")
        try {
            apiKey.save("sk-offline-chatgpt-test-credential")
            store.save(session)
            val raw = context.getSharedPreferences("mural_chatgpt_session_test", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains(session.accessToken)); assertFalse(raw.contains(session.refreshToken)); assertFalse(raw.contains(session.accountID))
            assertEquals(session, ChatGPTAuthStore(context, "mural_chatgpt_session_test", "chat.mural.chatgpt.test.aes").read())
            apiKey.delete()
            assertEquals(session, store.read())
            apiKey.save("sk-offline-chatgpt-test-credential")
            store.clear()
            assertNull(store.read()); assertTrue(apiKey.hasKey)
        } finally { store.clear(); apiKey.delete() }
    }

    @Test fun tamperedRecordsAreDiscarded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ChatGPTAuthStore(context, "mural_chatgpt_session_test", "chat.mural.chatgpt.test.aes")
        try {
            store.save(ChatGPTSession("account-offline-test", "access", "refresh", 1L))
            val preferences = context.getSharedPreferences("mural_chatgpt_session_test", Context.MODE_PRIVATE)
            val ciphertext = preferences.getString("ciphertext", null)!!
            val flipped = (if (ciphertext.first() == 'A') 'B' else 'A') + ciphertext.drop(1)
            assertTrue(preferences.edit().putString("ciphertext", flipped).commit())
            assertNull(store.read())
            assertNull(preferences.getString("ciphertext", null))
        } finally { store.clear() }
    }
}
