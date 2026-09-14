package chat.mural.network

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in check that a browser on the device delivers the OAuth redirect to the app's loopback listener.
 * Run with `-e chatgptLoopbackCheck true` on a device with Chrome past its first-run screens.
 */
@RunWith(AndroidJUnit4::class)
class ChatGPTLoopbackDeviceCheck {
    @Test fun browserRedirectReachesTheLoopbackListener() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("chatgptLoopbackCheck") == "true")
        // The code exchange targets a closed local port, so a received callback fails fast instead of timing out.
        val flow = ChatGPTLoginFlow(OkHttpClient(), "https://127.0.0.1:9/".toHttpUrl(), ChatGPTOAuth.REDIRECT_PORTS, System::currentTimeMillis)
        val pending = flow.begin()
        assertTrue(pending.port in ChatGPTOAuth.REDIRECT_PORTS)
        val outcome = async(Dispatchers.IO) { runCatching { flow.await(pending, 45_000) }.exceptionOrNull() }
        val callback = "http://localhost:${pending.port}/auth/callback?code=device-check&state=${pending.state}"
        instrumentation.targetContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(callback))
            .setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val failure = outcome.await()
        assertFalse("The browser never reached the loopback listener", failure is TimeoutCancellationException)
        assertNotNull("The fake code must not produce a session", failure)
        assertTrue(pending.server.isClosed)
    }
}
