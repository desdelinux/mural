package chat.mural.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keeps the ChatGPT sign-in apart from the OpenAI key and Mural accounts; app backup excludes these preferences. */
class ChatGPTAuthStore internal constructor(context: Context, preferencesName: String, private val alias: String) :
    ChatGPTSessionStorage {
    constructor(context: Context) : this(context, "mural_chatgpt_session", "chat.mural.chatgpt.aes")

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val binding = "${context.packageName}|chatgpt|v1".toByteArray(Charsets.UTF_8)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey = (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        generateKey()
    }
    override suspend fun save(session: ChatGPTSession) = withContext(Dispatchers.IO) { access.withLock {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(binding) }
            val encrypted = cipher.doFinal(Json.encodeToString(session).toByteArray(Charsets.UTF_8))
            if (!preferences.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) throw ChatGPTFailure.SecureStorage
        } catch (_: Exception) { throw ChatGPTFailure.SecureStorage }
    } }
    override suspend fun read(): ChatGPTSession? = withContext(Dispatchers.IO) { access.withLock {
        try {
            val encoded = preferences.getString("ciphertext", null) ?: return@withLock null
            if (encoded.length > 32_768) throw ChatGPTFailure.SecureStorage
            val iv = preferences.getString("iv", null) ?: throw ChatGPTFailure.SecureStorage
            if (iv.length > 64) throw ChatGPTFailure.SecureStorage
            val storedKey = keyStore().getKey(alias, null) as? SecretKey ?: throw ChatGPTFailure.SecureStorage
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, storedKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                updateAAD(binding)
            }
            Json.decodeFromString<ChatGPTSession>(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            remove(); null
        }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { access.withLock { remove() } }
    private fun remove() {
        var failed = !preferences.edit().clear().commit()
        try { keyStore().deleteEntry(alias) } catch (_: Exception) { failed = true }
        if (failed) throw ChatGPTFailure.SecureStorage
    }
    private companion object {
        // Every instance shares one record; a reader must never pair one save's ciphertext with another's IV.
        val access = Mutex()
    }
}
