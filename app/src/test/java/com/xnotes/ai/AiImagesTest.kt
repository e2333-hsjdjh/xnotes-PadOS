package com.xnotes.ai

import com.xnotes.core.template.TemplateKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The image model: its settings, the Images API call, and covers built around the picture it paints. */
class AiImagesTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
    private fun reply(bytes: ByteArray) = """{"created":1,"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""

    private val openai = AiProvider("p1", "OpenAI", "https://api.openai.com/v1", "sk-test-0123456789", listOf("gpt-text"), imageModels = listOf("gpt-image-1"))
    private val other = AiProvider("p2", "Other", "https://api.deepseek.com", "ds-key", listOf("deepseek-chat"))
    private val config = AiConfig(providers = listOf(openai, other), provider = "p2", imageProvider = "p1", imageModel = "gpt-image-1").route("p2", "deepseek-chat")

    @Test fun requestAsksForOnePortraitPictureAsData() {
        val body = AiImages.request("gpt-image-1", "  pines  ")
        assertEquals("gpt-image-1", body.getString("model")); assertEquals("pines", body.getString("prompt"))
        assertEquals(1, body.getInt("n")); assertEquals("1024x1536", body.getString("size")); assertFalse(body.has("response_format"))
        val dalle = AiImages.request("dall-e-3", "x")
        assertEquals("1024x1792", dalle.getString("size")); assertEquals("b64_json", dalle.getString("response_format"))
        assertEquals(AiImages.MAX_PROMPT, AiImages.request("gpt-image-1", "y".repeat(9000)).getString("prompt").length)
        assertEquals("https://api.openai.com/v1/images/generations", AiImages.endpoint("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/images/generations", AiImages.endpoint("https://api.openai.com/v1/chat/completions"))
    }

    @Test fun decodeKeepsOnlyRealPictureDataAndNeverFollowsLinks() {
        assertEquals("png", AiImages.decode(reply(png)).extension)
        assertEquals("jpg", AiImages.decode(reply(jpg)).extension)
        for (bad in listOf("""{"data":[{"url":"https://example.com/a.png"}]}""", reply("not a picture".toByteArray()), "{}", "<html>")) {
            assertThrows(bad, IllegalStateException::class.java) { AiImages.decode(bad) }
        }
    }

    private class Fake(val status: Int, val body: String) : HttpURLConnection(URL("https://api.openai.com/v1/images/generations")) {
        val sent = ByteArrayOutputStream()
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun transportPostsToTheImagesEndpointWithTheImageProvidersKey() {
        var opened = ""
        val fake = Fake(200, reply(png))
        val route = config.imageRoute()!!
        val image = AiConnection { opened = it; fake }.image(route, "a pine forest")
        assertEquals("https://api.openai.com/v1/images/generations", opened)
        assertEquals("Bearer sk-test-0123456789", fake.getRequestProperty("Authorization"))
        assertEquals("gpt-image-1", JSONObject(fake.sent.toString()).getString("model"))
        assertEquals("png", image.extension)
        val refused = assertThrows(IllegalStateException::class.java) {
            AiConnection { Fake(400, """{"error":{"message":"Your request was rejected by the safety system."}}""") }.image(route, "x")
        }
        assertTrue(refused.message!!.contains("safety system"))
    }

    @Test fun imageSettingsRoundTripAndGoAwayWithTheirProvider() {
        val stored = AiConfigStore.decode(AiConfigStore.encode(config), "")
        assertEquals("p1", stored.imageProvider); assertEquals("gpt-image-1", stored.imageModel)
        assertEquals("https://api.openai.com/v1", stored.imageRoute()!!.url); assertEquals("deepseek-chat", stored.model)
        val orphan = AiConfigStore.decode(AiConfigStore.encode(config).put("imageProvider", "gone"), "")
        assertEquals("", orphan.imageProvider); assertEquals("", orphan.imageModel); assertNull(orphan.imageRoute())
        val removed = config.copy(providers = listOf(other)).withValidDefault()
        assertEquals("", removed.imageProvider); assertNull(removed.imageRoute())
        assertThrows(IllegalArgumentException::class.java) { AiConfigStore.validate(config.copy(imageProvider = "")) }
        AiConfigStore.validate(config.copy(imageProvider = "", imageModel = ""))
        AiConfigStore.validate(config)
        assertNull(config.copy(imageModel = " ").imageRoute())
    }

    /** A text model scripted reply by reply, and an image model returning [picture]. */
    private class Models(val replies: List<String>, val picture: ByteArray) : AiTransport {
        val texts = mutableListOf<Pair<AiConfig, List<AiMessage>>>()
        val paints = mutableListOf<Pair<AiConfig, String>>()
        override fun cancel() {}
        override fun search(key: String, query: String, question: String): List<AiWebSource> = error("not expected")
        override fun fetch(key: String, url: String) = error("not expected")
        override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) {
            texts += config to messages.toList(); delta(replies[texts.size - 1])
        }
        override fun image(config: AiConfig, prompt: String): AiImage { paints += config to prompt; return AiImages.decode("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(picture)}"}]}""") }
    }

    private val coverJson = """{"xtemplate":1,"kind":"cover","name":"Pines","items":[{"type":"rect","x":"0","y":"0","w":"W","h":"H"},""" +
        """{"type":"image","src":"images/cover.png","fit":"cover"},{"type":"rect","x":"20mm","y":"20mm","w":"60mm","h":"14mm"}]}"""
    private val answer = "```json\n$coverJson\n```\n```prompt\nA misty pine forest at dawn, soft watercolour, no text.\n```"

    @Test fun coverWithPictureIsWrittenThenPaintedOnce() {
        val models = Models(listOf(answer), png)
        val stages = mutableListOf<AiTemplateGen.Stage>()
        val made = runBlocking { AiTemplateGen.generate(config, models, "GUIDE", "松林封面", TemplateKind.COVER, config.imageRoute()) { stages += it } }
        assertEquals(coverJson, made.json); assertEquals("images/cover.png", made.src); assertArrayEquals(png, made.picture!!.bytes)
        val (route, prompt) = models.paints.single()
        assertEquals("gpt-image-1", route.model); assertEquals("ds-key", models.texts.single().first.key); assertEquals("sk-test-0123456789", route.key)
        assertEquals("A misty pine forest at dawn, soft watercolour, no text.", prompt)
        assertTrue(models.texts.single().first.instructions.contains("```prompt"))
        assertEquals(AiTemplateGen.Stage.Painting, stages.last())
    }

    @Test fun aJpegPictureRenamesItsSource() {
        val made = runBlocking { AiTemplateGen.generate(config, Models(listOf(answer), jpg), "G", "q", TemplateKind.COVER, config.imageRoute()) }
        assertEquals("images/cover.jpg", made.src)
        assertTrue(made.json.contains("\"images/cover.jpg\"")); assertFalse(made.json.contains("cover.png"))
        com.xnotes.format.TemplateReader.read(made.json)
    }

    @Test fun missingDescriptionOrPictureSlotIsFixedBeforeAnythingIsPainted() {
        val noPrompt = "```json\n$coverJson\n```"
        val noSlot = "```json\n{\"xtemplate\":1,\"kind\":\"cover\",\"name\":\"X\",\"items\":[{\"type\":\"rect\",\"x\":\"0\",\"y\":\"0\",\"w\":\"W\",\"h\":\"H\"}]}\n```\n```prompt\nsky\n```"
        val models = Models(listOf(noPrompt, noSlot, answer), png)
        runBlocking { AiTemplateGen.generate(config, models, "G", "q", TemplateKind.COVER, config.imageRoute()) }
        assertEquals(3, models.texts.size); assertEquals(1, models.paints.size)
        assertTrue(models.texts[1].second.last().text.contains("No picture description"))
        assertTrue(models.texts[2].second.last().text.contains("exactly one `image` node"))
    }

    @Test fun pagesAndUnpaintedCoversNeverCallTheImageModel() {
        val page = """{"xtemplate":1,"name":"Lines","items":[{"type":"line","x1":"0","y1":"0","x2":"W","y2":"0"}]}"""
        val models = Models(listOf("```json\n$page\n```"), png)
        val made = runBlocking { AiTemplateGen.generate(config, models, "G", "q", TemplateKind.PAGE, config.imageRoute()) }
        assertNull(made.picture); assertTrue(models.paints.isEmpty())
        assertFalse(models.texts.single().first.instructions.contains("```prompt"))
    }
}
