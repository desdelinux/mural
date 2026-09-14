package chat.mural.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeDialectTest {
    private var clock = 10_000L
    private val history = buildJsonArray {
        add(buildJsonObject {
            put("type", "message"); put("role", "user")
            putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", "Hola") }) }
        })
    }
    private val dialect = RealtimeDialect("Speak only Spanish.", history) { clock }

    private fun event(type: String, vararg fields: Pair<String, String>) = buildJsonObject {
        put("type", type); fields.forEach { (key, value) -> put(key, value) }
    }
    private fun command(type: String, content: String, delegationID: String? = null) = buildJsonObject {
        put("type", type); put("event_id", "e"); put("content", content)
        put("delegation_id", delegationID?.let(::JsonPrimitive) ?: JsonNull)
    }
    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
    private fun started() = dialect.inbound(buildJsonObject {
        put("type", "session.created"); putJsonObject("session") { put("id", "sess_1") }
    })

    @Test fun sessionCreatedConfiguresTheCallThenStartsTheConversationOnce() {
        val step = started()
        assertEquals(listOf("session.update", "conversation.item.create"), step.send.map { it.type })
        val session = step.send[0]["session"]!!.jsonObject
        assertEquals("Speak only Spanish.", session["instructions"]!!.jsonPrimitive.content)
        assertEquals(RealtimeDialect.DELEGATE_TOOL, session["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(RealtimeDialect.TRANSCRIPTION_MODEL,
            session["audio"]!!.jsonObject["input"]!!.jsonObject["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(history[0], step.send[1]["item"])
        assertEquals("session.started", step.deliver.single().type)
        assertEquals("sess_1", step.deliver.single()["session"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(DialectStep(), started())
    }

    @Test fun learnerTranscriptsUseSpeechTimingAndStaySeparatedBySpaces() {
        started()
        clock = 11_000; dialect.inbound(event("input_audio_buffer.speech_started", "item_id" to "a"))
        clock = 12_500; dialect.inbound(event("input_audio_buffer.speech_stopped", "item_id" to "a"))
        clock = 13_000
        val first = dialect.inbound(event("conversation.item.input_audio_transcription.completed", "item_id" to "a", "transcript" to " Hola. ")).deliver.single()
        assertEquals("session.input_transcript.delta", first.type)
        assertEquals("Hola.", first["delta"]!!.jsonPrimitive.content)
        assertEquals(1_000, first["start_ms"]!!.jsonPrimitive.int)
        assertEquals(2_500, first["end_ms"]!!.jsonPrimitive.int)
        val second = dialect.inbound(event("conversation.item.input_audio_transcription.completed", "item_id" to "b", "transcript" to "Me llamo Ana.")).deliver.single()
        assertEquals(" Me llamo Ana.", second["delta"]!!.jsonPrimitive.content)
        assertNotEquals(first["event_id"], second["event_id"])
        assertEquals(DialectStep(), dialect.inbound(event("conversation.item.input_audio_transcription.completed", "item_id" to "c", "transcript" to "  ")))
    }

    @Test fun assistantDeltasBecomeUniqueFragmentsAndNewRepliesStartWithASpace() {
        started()
        clock = 14_000
        val first = dialect.inbound(event("response.output_audio_transcript.delta", "item_id" to "r1", "delta" to "¡Hola")).deliver.single()
        val second = dialect.inbound(event("response.output_audio_transcript.delta", "item_id" to "r1", "delta" to "!")).deliver.single()
        val next = dialect.inbound(event("response.output_audio_transcript.delta", "item_id" to "r2", "delta" to "¿Qué tal?")).deliver.single()
        assertEquals("session.output_transcript.delta", first.type)
        assertEquals(4_000, first["start_ms"]!!.jsonPrimitive.int)
        assertEquals("!", second["delta"]!!.jsonPrimitive.content)
        assertEquals(" ¿Qué tal?", next["delta"]!!.jsonPrimitive.content)
        assertEquals(3, setOf(first, second, next).map { it["event_id"] }.toSet().size)
    }

    @Test fun delegationBecomesAFunctionResultAndOtherToolsAreIgnored() {
        started()
        assertEquals(DialectStep(), dialect.inbound(event("response.function_call_arguments.done", "name" to "other", "call_id" to "x")))
        val created = dialect.inbound(event("response.function_call_arguments.done", "name" to RealtimeDialect.DELEGATE_TOOL, "call_id" to "call_1")).deliver.single()
        assertEquals("session.delegation.created", created.type)
        assertEquals("call_1", created["delegation"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("client", created["delegation"]!!.jsonObject["target"]!!.jsonPrimitive.content)

        val answer = dialect.outbound(command("session.commentary.append", "Hoy llueve en Madrid.", "call_1"))
        assertEquals(listOf("conversation.item.create", "response.create"), answer.send.map { it.type })
        val output = answer.send[0]["item"]!!.jsonObject
        assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
        assertEquals("Hoy llueve en Madrid.", output["output"]!!.jsonPrimitive.content)
    }

    @Test fun commentaryWithoutAnOpenDelegationIsSpokenAsAnInstruction() {
        started()
        val step = dialect.outbound(command("session.commentary.append", "Muy bien.", "unknown"))
        assertEquals(listOf("conversation.item.create", "response.create"), step.send.map { it.type })
        val message = step.send[0]["item"]!!.jsonObject
        assertEquals("system", message["role"]!!.jsonPrimitive.content)
        assertTrue(message["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.endsWith("Muy bien."))
    }

    @Test fun instructionsAskForAReplyButThinkingOnlyAddsContext() {
        started()
        assertEquals(listOf("conversation.item.create", "response.create"),
            dialect.outbound(command("session.instructions.append", "Greet the learner.")).send.map { it.type })
        dialect.inbound(event("response.done"))
        assertEquals(listOf("conversation.item.create"),
            dialect.outbound(command("session.thinking.append", "Challenge 2/5.")).send.map { it.type })
    }

    @Test fun aReplyRequestedDuringAnActiveResponseWaitsForItToFinish() {
        started()
        dialect.inbound(event("response.created"))
        assertEquals(listOf("conversation.item.create"), dialect.outbound(command("session.instructions.append", "Help.")).send.map { it.type })
        val done = dialect.inbound(event("response.done"))
        assertEquals(listOf("response.create"), done.send.map { it.type })
        assertEquals("session.usage.updated", done.deliver.single().type)

        dialect.inbound(event("response.created"))
        dialect.outbound(command("session.instructions.append", "Redirect."))
        dialect.inbound(event("response.created"))
        assertTrue(dialect.inbound(event("response.done")).send.isEmpty())
    }

    @Test fun muteStaysLocalAndCloseReportsElapsedSeconds() {
        started()
        assertEquals(DialectStep(), dialect.outbound(buildJsonObject { put("type", "session.input_audio.mute") }))
        clock = 25_500
        val closed = dialect.outbound(buildJsonObject { put("type", "session.close") })
        assertTrue(closed.send.isEmpty())
        assertEquals(listOf("session.usage.updated", "session.closed"), closed.deliver.map { it.type })
        assertEquals(15.5, closed.deliver[1]["usage"]!!.jsonObject["seconds"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun providerErrorsReachTheConversationWithoutProviderDetails() {
        val error = buildJsonObject {
            put("type", "error"); putJsonObject("error") { put("message", "raw provider text") }
        }
        assertEquals(buildJsonObject { put("type", "error") }, dialect.inbound(error).deliver.single())
        assertEquals(DialectStep(), dialect.inbound(event("rate_limits.updated")))
    }
}
