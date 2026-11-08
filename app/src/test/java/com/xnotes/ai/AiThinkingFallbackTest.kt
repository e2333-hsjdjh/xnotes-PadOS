package com.xnotes.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Models without reasoning refuse a thinking level; the answer goes on without it, and the turn says so. */
class AiThinkingFallbackTest {
    private val user = listOf(AiMessage("user", "问题"))
    private val gpt4o = """{"error":{"message":"Unrecognized request argument supplied: reasoning_effort","type":"invalid_request_error","param":null}}"""
    private val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]\n"

    private class Fake(val status: Int, val body: String) : HttpURLConnection(URL("https://api.openai.com/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }

    /** A connection handing out [fakes] in order, keeping them to look at what each request carried. */
    private class Script(vararg fakes: Fake) {
        val queue = ArrayDeque(fakes.toList())
        val used = mutableListOf<Fake>()
        val client = AiConnection { queue.removeFirst().also { used += it } }
        fun body(i: Int) = JSONObject(used[i].sent.toString())
    }

    private fun config(model: String, effort: String = "high", url: String = "https://api.openai.com/v1") = AiConfig(url, model, "sk-test-0123456789", effort = effort)

    @Test fun refusedThinkingIsSentAgainWithoutItAndRemembered() = runBlocking {
        val script = Script(Fake(400, gpt4o), Fake(200, stream), Fake(200, stream))
        val c = config("gpt-4o-fallback-1")
        val first = script.client.complete(c, user, emptyList(), null, "auto") {}
        assertEquals("好", first.content); assertTrue(first.thinkingDropped)
        assertEquals("high", script.body(0).getString("reasoning_effort")); assertFalse(script.body(1).has("reasoning_effort"))
        // The model and level are known now: the next request goes straight without the field.
        val next = AiConnection { script.queue.removeFirst().also { script.used += it } }.complete(c, user, emptyList(), null, "auto") {}
        assertTrue(next.thinkingDropped); assertFalse(script.body(2).has("reasoning_effort")); assertEquals(3, script.used.size)
        // Another level of the same model, or the same level of another model, is still tried as asked.
        assertFalse(AiThinking.refuses(c.copy(effort = "low"))); assertFalse(AiThinking.refuses(c.copy(model = "gpt-5")))
    }

    @Test fun withToolsTheRefusalIsAboutThinkingNotTools() = runBlocking {
        val script = Script(Fake(400, gpt4o), Fake(200, stream))
        val reply = script.client.complete(config("gpt-4o-fallback-2", AiThinking.OFF), user, emptyList(), AiWebTools.definitions(), "auto") {}
        assertTrue(reply.thinkingDropped)
        assertEquals("none", script.body(0).getString("reasoning_effort"))
        assertTrue(script.body(1).has("tools")); assertFalse(script.body(1).has("reasoning_effort"))
    }

    @Test fun otherRefusalsAndRequestsWithoutThinkingAreNotRetried() {
        val other = Script(Fake(400, """{"error":{"message":"Invalid value for 'temperature'"}}"""))
        assertThrows(IllegalStateException::class.java) { runBlocking { other.client.complete(config("gpt-4o-fallback-3"), user, emptyList(), null, "auto") {} } }
        assertEquals(1, other.used.size)
        val plain = Script(Fake(400, gpt4o))
        assertThrows(IllegalStateException::class.java) { runBlocking { plain.client.complete(config("gpt-4o-fallback-4", effort = ""), user, emptyList(), null, "auto") {} } }
        assertEquals(1, plain.used.size)
    }

    @Test fun eachFormatNamesItsOwnField() {
        assertEquals("thinking", AiThinking.field(AiThinking.AUTO, "https://api.deepseek.com"))
        assertEquals("enable_thinking", AiThinking.field(AiThinking.AUTO, "https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertEquals("reasoning", AiThinking.field(AiThinking.AUTO, "https://openrouter.ai/api/v1"))
        assertEquals("reasoning_effort", AiThinking.field(AiThinking.AUTO, "https://api.openai.com/v1"))
        assertTrue(AiThinking.refused("Unsupported parameter: 'reasoning_effort' is not supported with this model.", "high", AiThinking.AUTO, "https://api.openai.com/v1"))
        assertFalse(AiThinking.refused("Unsupported parameter: 'reasoning_effort'", "", AiThinking.AUTO, "https://api.openai.com/v1"))
    }
}
