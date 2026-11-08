package com.xnotes.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** OpenAI's newer token field, and the provider's reason carried into a failed request's message. */
class AiHttpErrorTest {
    private val user = listOf(AiMessage("user", "问题"))
    private val key = "sk-test-0123456789abcdef"

    private class Fake(val status: Int, val error: String) : HttpURLConnection(URL("https://api.openai.com/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getErrorStream() = ByteArrayInputStream(error.toByteArray())
        override fun getInputStream() = throw java.io.IOException("HTTP $status")
    }

    @Test fun openAiGetsMaxCompletionTokensAndOthersKeepMaxTokens() {
        val openai = AiProtocol.request(AiConfig("https://api.openai.com/v1", "gpt", "k", maxTokens = 8000), user)
        assertEquals(8000, openai.getInt("max_completion_tokens")); assertFalse(openai.has("max_tokens"))
        for (url in listOf("https://api.deepseek.com", "https://openrouter.ai/api/v1", "https://example.com/v1", "https://api.openai.com.example.org/v1")) {
            val body = AiProtocol.request(AiConfig(url, "m", "k", maxTokens = 8000), user)
            assertEquals(url, 8000, body.getInt("max_tokens")); assertFalse(url, body.has("max_completion_tokens"))
        }
    }

    @Test fun providerErrorReadsOpenAiStyleBodiesOnlyAndMasksTheKey() {
        val openai = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens"}}"""
        assertTrue(AiProtocol.providerError(openai, key).startsWith("Unsupported parameter: 'max_tokens'"))
        assertEquals("bad model", AiProtocol.providerError("""{"error":"bad\n  model"}""", key))
        assertEquals("nope", AiProtocol.providerError("""{"message":"nope"}""", key))
        assertEquals("", AiProtocol.providerError("<html><body>Bad Request</body></html>", key))
        assertEquals("", AiProtocol.providerError("", key))
        assertEquals("Incorrect API key provided: ***.", AiProtocol.providerError("""{"error":{"message":"Incorrect API key provided: $key."}}""", key))
        assertEquals(AiProtocol.ERROR_DETAIL_MAX, AiProtocol.providerError("""{"error":{"message":"${"x".repeat(1000)}"}}""", key).length)
    }

    @Test fun failedRequestSaysWhatTheProviderRejected() {
        val fake = Fake(400, """{"error":{"message":"Unsupported value: 'reasoning_effort' does not support 'none' with this model."}}""")
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { AiConnection { fake }.complete(AiConfig("https://api.openai.com/v1", "gpt", key), user, emptyList(), null, "auto") {} }
        }
        assertTrue(error.message!!.contains("HTTP 400")); assertTrue(error.message!!.contains("reasoning_effort"))
        assertTrue(JSONObject(fake.sent.toString()).has("max_completion_tokens"))
        val silent = assertThrows(IllegalStateException::class.java) {
            runBlocking { AiConnection { Fake(500, "oops") }.complete(AiConfig("https://api.openai.com/v1", "gpt", key), user, emptyList(), null, "auto") {} }
        }
        assertEquals(AiText.t(AiMsg.REQUEST_HTTP_FAILED, 500), silent.message)
    }

    @Test fun refusedToolsCarryTheReasonToo() {
        val fake = Fake(400, """{"error":{"message":"tools are not supported for this model"}}""")
        val error = assertThrows(AiToolsUnsupported::class.java) {
            runBlocking { AiConnection { fake }.complete(AiConfig("https://api.openai.com/v1", "gpt", key), user, emptyList(), AiWebTools.definitions(), "auto") {} }
        }
        assertEquals(400, error.status); assertTrue(error.message!!.contains("tools are not supported"))
    }
}
