package chat.mural

import android.Manifest
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.ConversationProvider
import chat.mural.core.Preferences
import chat.mural.core.Speaker
import chat.mural.network.ChatGPTAuthStore
import chat.mural.network.ChatGPTSession
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Opt-in check of a real subscription voice call. It is skipped unless `files/chatgpt-device-check.json`
 * exists in the test app, holding `account_id`, `access_token` and `expires_at_ms` of a ChatGPT sign-in.
 * The file is deleted as soon as it is read and the stored session is cleared afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ChatGPTVoiceDeviceCheck {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var seeded = false

    private val seed = object : ExternalResource() {
        override fun before() {
            val file = File(context.filesDir, "chatgpt-device-check.json")
            if (!file.exists()) return
            val values = try { Json.parseToJsonElement(file.readText()).jsonObject } finally { file.delete() }
            runBlocking {
                ChatGPTAuthStore(context).save(ChatGPTSession(values.getValue("account_id").jsonPrimitive.content,
                    values.getValue("access_token").jsonPrimitive.content, "not-used-by-this-check",
                    values.getValue("expires_at_ms").jsonPrimitive.long, "device-check"))
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            seeded = true
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    private var original: Preferences? = null

    @After fun cleanUp() {
        if (seeded) runBlocking { ChatGPTAuthStore(context).clear() }
    }

    @Test fun subscriptionVoiceCallSpeaksTheGreetingAndClosesWithUsage() {
        assumeTrue("No seeded ChatGPT session; skipping the device voice check.", seeded)
        val vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            assertTrue(vm.chatGPTSignedIn)
            original = vm.archive.preferences.copy()
            vm.updatePreferences(original!!.copy(hasOnboarded = true, aiConsentVersion = 1, learningLanguageID = "es", meaningVisible = false))
            vm.selectConversationProvider(ConversationProvider.CHATGPT_SUBSCRIPTION)
            vm.start()
        }
        compose.waitUntil(45_000) { compose.runOnUiThread { vm.state == "active" || vm.error != null } }
        compose.runOnIdle { assertNull("Voice failed: ${vm.error}", vm.error); assertEquals("active", vm.state) }
        var loudest = 0.0
        compose.waitUntil(45_000) {
            compose.runOnUiThread {
                loudest = maxOf(loudest, vm.outputLevel)
                loudest > 0.0 && vm.session?.passages?.any { it.speaker == Speaker.assistant && it.text.length > 10 } == true
            }
        }
        Thread.sleep(6_000)
        val greeting = compose.runOnUiThread { vm.session!!.passages.filter { it.speaker == Speaker.assistant }.joinToString(" | ") { it.text } }
        Log.i("ChatGPTVoiceDeviceCheck", "assistant: $greeting")
        compose.runOnIdle { vm.end() }
        compose.waitUntil(15_000) { compose.runOnUiThread { vm.state == "ended" } }
        compose.runOnIdle {
            val record = vm.archive.sessions.last()
            Log.i("ChatGPTVoiceDeviceCheck", "voiceSeconds=${record.voiceSeconds} usageFinal=${record.usageFinal} provider=${record.providerID} loudest=$loudest")
            assertTrue(record.usageFinal)
            assertTrue(record.voiceSeconds in 1.0..14.9)
            assertTrue(record.providerID.orEmpty().startsWith("sess_") || record.providerID.orEmpty().startsWith("rtc_"))
            vm.updatePreferences(original!!)
        }
    }
}
