package com.xnotes.ai

import com.xnotes.core.template.TemplateKind
import com.xnotes.format.TemplateReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiTemplateGenTest {
    private val config = AiConfig("https://api.example.com/v1", "model", "key", maxTokens = 4096, instructions = "用户自己的聊天提示词")
    private val page = """{"xtemplate":1,"name":"Lines","items":[{"type":"repeat","axis":"y","step":"8mm","children":[{"type":"line","x1":"0","y1":"0","x2":"W","y2":"0"}]}]}"""
    private val cover = """{"xtemplate":1,"kind":"cover","name":"Frame","items":[{"type":"rect","x":"0","y":"0","w":"W","h":"H"},{"type":"rect","x":"12mm","y":"12mm","w":"W - 24mm","h":"H - 24mm"}]}"""

    /** A provider scripted reply by reply, recording what each request carried. */
    private class Model(val replies: List<String>) : AiTransport {
        val requests = mutableListOf<Pair<AiConfig, List<AiMessage>>>()
        override fun cancel() {}
        override fun search(key: String, query: String, question: String): List<AiWebSource> = error("not expected")
        override fun fetch(key: String, url: String) = error("not expected")
        override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) {
            requests += config to messages.toList()
            replies[requests.size - 1].chunked(7).forEach { delta(it) }
        }
    }

    private fun run(model: Model, kind: TemplateKind = TemplateKind.PAGE, stages: MutableList<AiTemplateGen.Stage> = mutableListOf()) =
        runBlocking { AiTemplateGen.generate(config, model, "GUIDE TEXT", "  一页横线  ", kind) { stages += it } }

    @Test fun guideIsTheSystemPromptAndAJsonBlockIsTaken() {
        val model = Model(listOf("我选了 8 毫米行距。\n```json\n$page\n```\n"))
        val stages = mutableListOf<AiTemplateGen.Stage>()
        assertEquals(page, run(model, stages = stages).json)
        val (sent, messages) = model.requests.single()
        assertTrue(sent.instructions.startsWith("GUIDE TEXT"))
        assertTrue(sent.instructions.contains("In-app generation")); assertTrue(sent.instructions.contains("page template"))
        assertFalse(sent.instructions.contains("用户自己的聊天提示词"))
        assertTrue(sent.maxTokens >= AiTemplateGen.MIN_OUTPUT_TOKENS)
        assertEquals(listOf("一页横线"), messages.map { it.text })
        assertTrue(stages.any { it is AiTemplateGen.Stage.Writing && it.chars > 0 }); assertTrue(stages.last() is AiTemplateGen.Stage.Checking)
    }

    @Test fun unusableReplyGoesBackWithTheReasonThenTheFixIsTaken() {
        val bad = """{"xtemplate":1,"name":"Pic","items":[{"type":"image","src":"images/a.jpg"}]}"""
        val model = Model(listOf("```json\n$bad\n```", "```json\n$page\n```"))
        assertEquals(page, run(model).json)
        val second = model.requests[1].second
        assertEquals(listOf("user", "assistant", "user"), second.map { it.role })
        assertTrue(second.last().text.contains("`image` node"))
    }

    @Test fun droppedNodesKindAndJsonErrorsAreReported() {
        val dropped = """{"xtemplate":1,"name":"X","items":[{"type":"line","x1":"0","y1":"0","x2":"W","y2":"0"},{"type":"spiral","turns":3}]}"""
        val reasons = mutableListOf<String>()
        for ((reply, kind) in listOf(dropped to TemplateKind.PAGE, page to TemplateKind.COVER, cover to TemplateKind.PAGE, "{\"xtemplate\":1, items:[]}" to TemplateKind.PAGE, "没有模板" to TemplateKind.PAGE)) {
            val model = Model(listOf(reply, reply, reply))
            val e = assertThrows(IllegalStateException::class.java) { run(model, kind) }
            assertEquals(AiTemplateGen.MAX_ATTEMPTS, model.requests.size)
            reasons += e.message!!
        }
        assertTrue(reasons[0], reasons[0].contains("1 of its 2 nodes"))
        assertTrue(reasons[1].contains("must be a cover template")); assertTrue(reasons[2].contains("must be a page template"))
        assertTrue(reasons[3].contains("not a valid xtemplate")); assertTrue(reasons[4].contains("No template JSON"))
    }

    @Test fun coverKindIsAskedForAndAccepted() {
        val model = Model(listOf("```JSON\n$cover\n```"))
        assertEquals(cover, run(model, TemplateKind.COVER).json)
        assertTrue(model.requests.single().first.instructions.contains("\"kind\": \"cover\""))
    }

    @Test fun nothingIsSentWithoutARouteOrARequest() {
        val model = Model(emptyList())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AiTemplateGen.generate(config.copy(key = ""), model, "G", "q", TemplateKind.PAGE) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AiTemplateGen.generate(config, model, "G", "   ", TemplateKind.PAGE) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AiTemplateGen.generate(config, model, "G", "x".repeat(AiTemplateGen.MAX_REQUEST + 1), TemplateKind.PAGE) } }
        assertTrue(model.requests.isEmpty())
    }

    @Test fun extractPrefersTheLastJsonBlock() {
        assertEquals("{\"b\":2}", AiTemplateGen.extract("```json\n{\"a\":1}\n```\n修正后：\n```json\n{\"b\":2}\n```"))
        assertEquals("{\"c\":3}", AiTemplateGen.extract("```\n{\"c\":3}\n```"))
        assertEquals("{\"d\":4}", AiTemplateGen.extract("这里是 {\"d\":4} 完"))
        assertNull(AiTemplateGen.extract("没有 JSON"))
    }

    /** The checks must not refuse the templates the format's own examples are. */
    @Test fun everyBundledPlainTemplatePassesTheCheck() {
        val files = listOf("src/main/assets/templates", "src/main/assets/templates/examples")
            .flatMap { File(it).listFiles()?.toList() ?: emptyList() }
            .filter { f -> f.name.endsWith(".xtemplate") && f.readBytes().let { b -> b.isNotEmpty() && b[0] == '{'.code.toByte() } }
        assertTrue(files.size >= 20)
        for (f in files) {
            val text = f.readText()
            val kind = TemplateReader.read(text).kind
            assertEquals(f.name, text, AiTemplateGen.check(text, kind, picture = false))
        }
    }
}
