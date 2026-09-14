package chat.mural.network

import java.util.UUID
import kotlinx.serialization.json.*

/** Speaks OpenAI Realtime events to the provider while the conversation keeps its session events.
 * Transcript timings are milliseconds since the provider confirmed the session. */
internal class RealtimeDialect(
    private val instructions: String,
    private val history: JsonArray,
    private val now: () -> Long = System::currentTimeMillis,
) : LiveEventDialect {
    private var startedAt: Long? = null
    private var responseActive = false
    private var responsePending = false
    private val speech = mutableMapOf<String, LongArray>()
    private val openDelegations = mutableSetOf<String>()
    private var heardLearner = false
    private var lastAssistantItem: String? = null
    private var assistantFragments = 0L

    override fun inbound(event: JsonObject): DialectStep = when (event.string("type")) {
        "session.created" -> if (startedAt != null) DialectStep() else {
            startedAt = now()
            val id = (event["session"] as? JsonObject)?.string("id")
            DialectStep(send = listOf(sessionUpdate()) + history.map { item("conversation.item.create", it) },
                deliver = listOf(buildJsonObject {
                    put("type", "session.started"); putJsonObject("session") { id?.let { put("id", it) } }
                }))
        }
        "input_audio_buffer.speech_started" -> {
            event.string("item_id")?.let { speech[it] = longArrayOf(elapsed(), elapsed()) }; DialectStep()
        }
        "input_audio_buffer.speech_stopped" -> {
            event.string("item_id")?.let { speech.getOrPut(it) { longArrayOf(elapsed(), 0) }[1] = elapsed() }; DialectStep()
        }
        "conversation.item.input_audio_transcription.completed" -> {
            val id = event.string("item_id") ?: UUID.randomUUID().toString()
            val text = event.string("transcript")?.trim().orEmpty()
            val times = speech.remove(id) ?: longArrayOf(elapsed(), elapsed())
            if (text.isEmpty()) DialectStep() else {
                val spaced = if (heardLearner) " $text" else text
                heardLearner = true
                DialectStep(deliver = listOf(transcript("session.input_transcript.delta", "learner:$id", spaced,
                    times[0], maxOf(times[0], times[1]))))
            }
        }
        "response.output_audio_transcript.delta" -> {
            val delta = event.string("delta").orEmpty()
            val itemID = event.string("item_id") ?: "assistant"
            if (delta.isEmpty()) DialectStep() else {
                val spaced = if (lastAssistantItem != null && lastAssistantItem != itemID && !delta.first().isWhitespace()) " $delta" else delta
                lastAssistantItem = itemID
                val at = elapsed()
                DialectStep(deliver = listOf(transcript("session.output_transcript.delta",
                    "assistant:$itemID:${assistantFragments++}", spaced, at, at)))
            }
        }
        "response.function_call_arguments.done" -> {
            val callID = event.string("call_id")
            if (event.string("name") != DELEGATE_TOOL || callID == null) DialectStep() else {
                openDelegations += callID
                DialectStep(deliver = listOf(buildJsonObject {
                    put("type", "session.delegation.created")
                    putJsonObject("delegation") { put("id", callID); put("target", "client") }
                }))
            }
        }
        "response.created" -> { responseActive = true; responsePending = false; DialectStep() }
        "response.done" -> {
            responseActive = false
            DialectStep(send = if (responsePending) requestResponse() else emptyList(), deliver = listOf(usage("session.usage.updated")))
        }
        "error" -> DialectStep(deliver = listOf(buildJsonObject { put("type", "error") }))
        else -> DialectStep()
    }

    override fun outbound(event: JsonObject): DialectStep {
        val content = event.string("content").orEmpty()
        return when (event.string("type")) {
            "session.instructions.append" -> DialectStep(send = listOf(systemMessage(content)) + requestResponse())
            "session.thinking.append" -> DialectStep(send = listOf(systemMessage(content)))
            "session.commentary.append" -> {
                val delegationID = event.string("delegation_id")
                if (delegationID != null && openDelegations.remove(delegationID)) {
                    DialectStep(send = listOf(item("conversation.item.create", buildJsonObject {
                        put("type", "function_call_output"); put("call_id", delegationID); put("output", content)
                    })) + requestResponse())
                } else DialectStep(send = listOf(systemMessage("Say this to the learner now, keeping its meaning: $content")) + requestResponse())
            }
            "session.close" -> DialectStep(deliver = listOf(usage("session.usage.updated"), usage("session.closed")))
            else -> DialectStep()
        }
    }

    private fun requestResponse(): List<JsonObject> {
        if (responseActive) { responsePending = true; return emptyList() }
        responseActive = true; responsePending = false
        return listOf(buildJsonObject { put("type", "response.create") })
    }

    private fun elapsed() = (now() - (startedAt ?: now())).coerceAtLeast(0)

    private fun sessionUpdate() = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime"); put("instructions", instructions)
            putJsonArray("tools") { add(DELEGATE_TOOL_DEFINITION) }; put("tool_choice", "auto")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("transcription") { put("model", TRANSCRIPTION_MODEL) }
                    putJsonObject("turn_detection") { put("type", "server_vad") }
                }
                putJsonObject("output") { put("voice", VOICE) }
            }
        }
    }

    private fun systemMessage(text: String) = item("conversation.item.create", buildJsonObject {
        put("type", "message"); put("role", "system")
        putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", text) }) }
    })

    private fun item(type: String, value: JsonElement) = buildJsonObject { put("type", type); put("item", value) }

    private fun transcript(type: String, id: String, text: String, start: Long, end: Long) = buildJsonObject {
        put("type", type); put("event_id", id); put("delta", text)
        put("start_ms", start.coerceAtMost(Int.MAX_VALUE.toLong())); put("end_ms", end.coerceAtMost(Int.MAX_VALUE.toLong()))
    }

    private fun usage(type: String) = buildJsonObject {
        put("type", type); putJsonObject("usage") { put("seconds", elapsed() / 1000.0) }
    }

    companion object {
        const val MODEL = "gpt-realtime"
        const val VOICE = "marin"
        const val TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
        const val DELEGATE_TOOL = "delegate_to_app"
        private val DELEGATE_TOOL_DEFINITION = buildJsonObject {
            put("type", "function"); put("name", DELEGATE_TOOL)
            put("description", "Ask the app to handle current events, facts that need verification or detailed explanations. " +
                "Wait for its result, then share it with the learner.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("request") { put("type", "string") } }
                putJsonArray("required") { add("request") }
                put("additionalProperties", false)
            }
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
