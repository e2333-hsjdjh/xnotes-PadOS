package com.xnotes.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiPdfToolsTest {
    private val pdf = AiPdf("paper.pdf", id = "a".repeat(64), pageCount = 100)
    private val config = AiConfig("https://example.com/v1", "model", "key")
    private class Access : AiPdfAccess {
        val pages = mutableListOf<Int>()
        override suspend fun read(pdf: AiPdf, start: Int, end: Int): String { pages.addAll(start..end); return "第 $start 页：Exact Paper Title" }
        override suspend fun image(pdf: AiPdf, page: Int) = "YWJj"
    }
    private class Client(val replies: List<String>) : AiTransport {
        var count = 0
        override fun cancel() {}
        override fun search(key: String, query: String, question: String): List<AiWebSource> = error("not expected")
        override fun fetch(key: String, url: String) = ""
        override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) { delta(replies[count++]) }
    }
    @Test fun readsOnlyRequestedPagesAndSuppliesSearchGrounding() = runBlocking {
        val access = Access()
        val user = AiMessage("user", "这篇论文的相关研究", pdf = pdf)
        val result = AiPdfTools.prepare(config, Client(listOf("""{"tool":"read","id":"${pdf.id}","start":1,"end":1}""", """{"tool":"done"}""")), emptyList(), user, access)
        assertEquals(listOf(1), access.pages)
        assertTrue(result.text.contains("Exact Paper Title"))
        assertTrue(result.text.endsWith(user.text))
        assertTrue(AiProtocol.request("model", listOf(user)).toString().contains(pdf.id))
        assertFalse(AiProtocol.request("model", listOf(user)).toString().contains("image_url"))
    }
    @Test fun historyAttachmentCanBeReadOnFollowup() = runBlocking {
        val access = Access()
        AiPdfTools.prepare(config, Client(listOf("""{"tool":"read","id":"${pdf.id}","start":50,"end":51}""", """{"tool":"done"}""")), listOf(AiMessage("user", "paper", pdf = pdf)), AiMessage("user", "继续"), access)
        assertEquals(listOf(50, 51), access.pages)
    }
    @Test fun rejectsForeignAttachmentAndOutOfRangeBeforeReading() = runBlocking {
        for (call in listOf("""{"tool":"read","id":"../private","start":1,"end":1}""", """{"tool":"read","id":"${pdf.id}","start":99,"end":101}""")) {
            val access = Access()
            try { AiPdfTools.prepare(config, Client(listOf(call)), emptyList(), AiMessage("user", "q", pdf = pdf), access); fail("must reject") } catch (_: IllegalArgumentException) {}
            assertTrue(access.pages.isEmpty())
        }
    }
    @Test fun referenceRoundTripsWithoutEmbeddingPdfBytes() {
        val dir = java.nio.file.Files.createTempDirectory("pdf-ref").toFile()
        try {
            val store = AiHistoryStore(dir); val chat = AiChat(turns = listOf(AiTurn(AiMessage("user", "q", pdf = pdf))))
            store.write(chat); assertEquals(pdf, store.readAll().first.single().turns.single().user.pdf)
            assertTrue(dir.listFiles()!!.single().length() < 2000)
        } finally { dir.deleteRecursively() }
    }

    // --- native tools ---

    private fun call(name: String, args: String, id: String = "c1") = AiToolCall(id, name, args)
    private fun Round(calls: List<AiToolCall>, access: Access = Access(), reader: AiPdfTools.Reader = AiPdfTools.Reader(mapOf(pdf.id to pdf), access)) =
        runBlocking { reader.run(calls) {} }

    @Test fun nativeReadReturnsPagesWithFooterSayingHowToContinue() {
        val access = Access()
        val round = Round(listOf(call(AiPdfTools.READ, """{"attachment_id":"${pdf.id}","start_page":3,"end_page":"4"}""")), access)
        assertEquals(listOf(3, 4), access.pages)
        val text = round.messages.single().getString("content")
        assertEquals("c1", round.messages.single().getString("tool_call_id"))
        assertTrue(text.startsWith(AiPdfTools.NOTICE)); assertTrue(text.contains("Exact Paper Title"))
        assertTrue(text.endsWith("继续请用 start_page=5。）"))
        assertNull(round.images)
        val end = Round(listOf(call(AiPdfTools.READ, """{"attachment_id":"${pdf.id}","start_page":100}""")))
        assertTrue(end.messages.single().getString("content").contains("已到文件末尾"))
    }
    @Test fun badCallsGoBackToTheModelWithoutReading() {
        val access = Access()
        val round = Round(listOf(
            call(AiPdfTools.READ, """{"attachment_id":"../private","start_page":1}""", "a"),
            call(AiPdfTools.READ, """{"attachment_id":"${pdf.id}","start_page":98,"end_page":101}""", "b"),
            call(AiPdfTools.READ, """{"attachment_id":"${pdf.id}","start_page":1,"end_page":6}""", "c"),
            call(AiPdfTools.SEARCH, "{not json", "d"),
        ), access)
        assertTrue(access.pages.isEmpty())
        assertEquals(listOf("a", "b", "c", "d"), round.messages.map { it.getString("tool_call_id") })
        assertTrue(round.messages.all { it.getString("content").startsWith("工具调用失败") })
        // The model is told which attachments exist, so it can correct the ID.
        assertTrue(round.messages[0].getString("content").contains(pdf.id))
    }
    @Test fun pageImagesFollowTheToolResultsAndAreCappedPerAnswer() {
        val access = Access()
        val reader = AiPdfTools.Reader(mapOf(pdf.id to pdf), access)
        val first = Round(List(AiPdfTools.MAX_IMAGES) { call(AiPdfTools.VIEW, """{"attachment_id":"${pdf.id}","page":${it + 1}}""", "v$it") }, access, reader)
        val parts = first.images!!.getJSONArray("content")
        assertEquals("user", first.images!!.getString("role"))
        assertEquals(AiPdfTools.MAX_IMAGES, (0 until parts.length()).count { parts.getJSONObject(it).getString("type") == "image_url" })
        val more = Round(listOf(call(AiPdfTools.VIEW, """{"attachment_id":"${pdf.id}","page":9}""")), access, reader)
        assertTrue(more.messages.single().getString("content").startsWith("工具调用失败"))
        assertNull(more.images)
    }
    @Test fun searchReportsCheckedRangeAndTurnBudgetIsEnforced() {
        val access = Access()
        val round = Round(listOf(call(AiPdfTools.SEARCH, """{"attachment_id":"${pdf.id}","query":"exact paper"}""")), access)
        assertEquals((1..10).toList(), access.pages)
        assertTrue(round.messages.single().getString("content").contains("继续请用 start_page=11"))
        val big = object : AiPdfAccess {
            override suspend fun read(pdf: AiPdf, start: Int, end: Int) = "x".repeat(AiPdfTools.RESULT_MAX + 10)
            override suspend fun image(pdf: AiPdf, page: Int) = ""
        }
        val budget = AiPdfTools.Reader(mapOf(pdf.id to pdf), big)
        val reads = runBlocking { (1..3).map { budget.run(listOf(call(AiPdfTools.READ, """{"attachment_id":"${pdf.id}","start_page":$it}"""))) {} } }
        assertTrue(reads[0].messages.single().getString("content").contains("已截断"))
        assertTrue(reads[2].messages.single().getString("content").startsWith("工具调用失败"))
    }
    @Test fun definitionsOfferThePdfToolsWithTheirGuidance() {
        val tools = AiPdfTools.definitions()
        assertEquals(listOf(AiPdfTools.READ, AiPdfTools.SEARCH, AiPdfTools.VIEW), (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") })
        val request = AiProtocol.request(config, listOf(AiMessage("user", "q", pdf = pdf)), tools = tools).toString()
        assertTrue(request.contains("pdf_read、pdf_search"))
        assertFalse(request.contains("web_search 和 web_fetch"))
    }
}
