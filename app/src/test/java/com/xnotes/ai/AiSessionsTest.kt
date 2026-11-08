package com.xnotes.ai

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiSessionsTest {
    private class HeldReply : AiTransport {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        override fun cancel() { cancelled = true; release.cancel() }
        override fun search(key: String, query: String, question: String): List<AiWebSource> = emptyList()
        override fun fetch(key: String, url: String) = ""
        override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) {
            delta("部分"); entered.complete(Unit); release.await(); delta("完成")
        }
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }
    @Test fun closingAndOpeningAnotherChatDoesNotCancelAndPersistsOriginal() = runBlocking {
        val dir = Files.createTempDirectory("sessions").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            val reply = HeldReply(); val store = AiHistoryStore(dir)
            val sessions = AiSessions(store, scope, { AiConfig("https://example.com/v1", "vision", "key") }, { reply })
            until { sessions.ready }
            val first = sessions.newChat("source.pdf", floatingOwner = "source.pdf")
            sessions.send(first, AiMessage("user", "问题", listOf("YWJj")), false)
            reply.entered.await()
            sessions.reconcileFloating(setOf("source.pdf"))
            assertEquals(first, sessions.activeId)
            sessions.reconcileFloating(emptySet())
            assertNull(sessions.activeId)
            assertNull(sessions.floatingOwner)
            val second = sessions.newChat()
            assertFalse(reply.cancelled)
            reply.release.complete(Unit)
            until { !sessions.isRunning(first) }; sessions.flush()
            assertEquals(second, sessions.activeId)
            assertEquals("部分完成", sessions.chats[first]!!.turns.single().answer)
            assertEquals("已完成", store.readAll().first.first { it.id == first }.turns.single().state)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
    @Test fun deleteCancelsMovesToTrashAndRestoreBringsBackPartial() = runBlocking {
        val dir = Files.createTempDirectory("sessions").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            val reply = HeldReply(); val store = AiHistoryStore(dir)
            val sessions = AiSessions(store, scope, { AiConfig("https://example.com/v1", "vision", "key") }, { reply })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.send(id, AiMessage("user", "问题"), false)
            reply.entered.await(); sessions.delete(id)
            sessions.flush()
            assertTrue(reply.cancelled); assertFalse(sessions.chats.containsKey(id)); assertTrue(sessions.trash.containsKey(id))
            assertTrue(store.readAll().first.single().trashedAt > 0)
            sessions.restore(id); sessions.flush()
            assertTrue(sessions.chats.containsKey(id)); assertFalse(sessions.trash.containsKey(id))
            assertEquals(0L, store.readAll().first.single().trashedAt)
            assertEquals("部分", store.readAll().first.single().turns.single().answer)
            assertNotEquals("生成中", store.readAll().first.single().turns.single().state)
            sessions.delete(id); sessions.deleteForever(id); sessions.flush()
            assertTrue(sessions.trash.isEmpty()); assertTrue(store.readAll().first.isEmpty())
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
    @Test fun failedInitialSaveDoesNotSendNetworkRequest() = runBlocking {
        val dir = Files.createTempDirectory("sessions").toFile()
        val blocked = java.io.File(dir, "not-directory").apply { writeText("existing") }
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            val reply = HeldReply()
            val sessions = AiSessions(AiHistoryStore(blocked), scope, { AiConfig("https://example.com/v1", "vision", "key") }, { reply })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.send(id, AiMessage("user", "question"), false)
            until { !sessions.isRunning(id) }; sessions.flush()
            assertFalse(reply.entered.isCompleted); assertTrue(sessions.storageError.isNotEmpty())
            assertEquals("existing", blocked.readText())
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
    /** A scripted provider: each [complete] call gets the next reply, or answers [final] once tools are withheld. */
    private class ToolModel(val script: List<(List<org.json.JSONObject>, org.json.JSONArray?, String) -> AiReply>, val sources: (String) -> List<AiWebSource> = { emptyList() }) : AiTransport {
        val searches = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<Triple<List<org.json.JSONObject>, org.json.JSONArray?, String>>()
        override fun cancel() {}
        override fun search(key: String, query: String, question: String): List<AiWebSource> { searches += query to question; return sources(query) }
        override fun fetch(key: String, url: String) = "# Page\nfull text of $url"
        override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) = error("tool rounds use complete")
        override suspend fun complete(config: AiConfig, messages: List<AiMessage>, tail: List<org.json.JSONObject>, tools: org.json.JSONArray?, toolChoice: String, delta: suspend (String) -> Unit): AiReply {
            requests += Triple(tail.toList(), tools, toolChoice)
            val reply = script[requests.size - 1](tail, tools, toolChoice)
            if (reply.content.isNotEmpty()) delta(reply.content)
            return reply
        }
    }
    private fun call(name: String, args: String, id: String = "c1") = AiReply(calls = listOf(AiToolCall(id, name, args)))
    private suspend fun ask(model: ToolModel, search: Boolean, block: (AiSessions, String) -> Unit) = coroutineScope {
        val dir = Files.createTempDirectory("sessions").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            val sessions = AiSessions(AiHistoryStore(dir), scope, { AiConfig("https://example.com/v1", "vision", "key", "search-key") }, { model })
            until { sessions.ready }; val id = sessions.newChat()
            sessions.send(id, AiMessage("user", "这篇论文的有关情况", listOf("YWJj")), search)
            until { !sessions.isRunning(id) }; sessions.flush()
            block(sessions, id)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
    @Test fun modelSearchesThroughToolThenAnswersWithSources() = runBlocking {
        val model = ToolModel(listOf(
            { tail, tools, choice ->
                assertTrue(tail.isEmpty()); assertEquals("auto", choice)
                assertEquals(listOf("web_search", "web_fetch"), (0 until tools!!.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") })
                call("web_search", """{"queries":["Attention Is All You Need","Attention Is All You Need"]}""").copy(reasoning = "想一想")
            },
            { tail, _, _ ->
                assertEquals("assistant", tail[0].getString("role")); assertEquals("想一想", tail[0].getString("reasoning_content"))
                val result = tail[1].getString("content")
                assertEquals("c1", tail[1].getString("tool_call_id"))
                assertTrue(result.startsWith(AiWebTools.NOTICE)); assertTrue(result.contains("[Paper](https://arxiv.org/abs/1706.03762)"))
                AiReply("论文介绍")
            },
        ), sources = { listOf(AiWebSource("https://arxiv.org/abs/1706.03762", "Paper", summary = "Transformer")) })
        ask(model, true) { sessions, id ->
            assertEquals(listOf("Attention Is All You Need" to "这篇论文的有关情况"), model.searches)
            val turn = sessions.chats[id]!!.turns.single()
            assertEquals("论文介绍", turn.answer); assertEquals("已完成", turn.state); assertEquals("", turn.error)
            assertTrue(turn.user.searchContext.contains("https://arxiv.org/abs/1706.03762"))
            assertTrue(turn.steps.any { it.contains("Attention Is All You Need") })
        }
    }
    @Test fun modelMayAnswerWithoutSearchingAndFetchIsAvailable() = runBlocking {
        val model = ToolModel(listOf(
            { _, _, _ -> call("web_fetch", """{"url":"https://example.com/a"}""") },
            { tail, _, _ -> assertTrue(tail[1].getString("content").contains("full text of https://example.com/a")); AiReply("读完了") },
        ))
        ask(model, true) { sessions, id ->
            assertTrue(model.searches.isEmpty())
            assertEquals("读完了", sessions.chats[id]!!.turns.single().answer)
        }
    }
    @Test fun withoutTheSwitchNoToolsAreOffered() = runBlocking {
        val model = ToolModel(listOf({ _, tools, _ -> assertNull(tools); AiReply("直接回答") }))
        ask(model, false) { sessions, id -> assertEquals("直接回答", sessions.chats[id]!!.turns.single().answer) }
    }
    @Test fun anAnswerSentWithoutTheChosenThinkingLevelSaysSo() = runBlocking {
        val model = ToolModel(listOf({ _, _, _ -> AiReply("没有思考的回答", thinkingDropped = true) }))
        ask(model, false) { sessions, id ->
            val turn = sessions.chats[id]!!.turns.single()
            assertEquals("已完成", turn.state); assertTrue(turn.error.contains("不支持所选的思考设置"))
        }
    }
    @Test fun modelWithoutToolCallingAnswersWithoutSearchAndSaysSo() = runBlocking {
        val model = ToolModel(listOf(
            { _, tools, _ -> assertNotNull(tools); throw AiToolsUnsupported(400) },
            { _, tools, _ -> assertNull(tools); AiReply("没有联网的回答") },
        ))
        ask(model, true) { sessions, id ->
            val turn = sessions.chats[id]!!.turns.single()
            assertTrue(model.searches.isEmpty())
            assertEquals("没有联网的回答", turn.answer); assertEquals("已完成", turn.state)
            assertTrue(turn.error.contains("不支持工具调用"))
        }
    }
    @Test fun searchFailureGoesBackToModelAndIsReported() = runBlocking {
        val model = ToolModel(listOf(
            { _, _, _ -> call("web_search", """{"queries":["x"]}""") },
            { tail, _, _ -> assertTrue(tail[1].getString("content").contains("工具调用失败")); AiReply("搜索失败了，无法查证") },
        ), sources = { throw IllegalStateException("DeepSeek 搜索失败（HTTP 401）") })
        ask(model, true) { sessions, id ->
            val turn = sessions.chats[id]!!.turns.single()
            assertEquals("已完成", turn.state); assertTrue(turn.error.contains("HTTP 401"))
            assertEquals("", turn.user.searchContext)
        }
    }
    @Test fun toolRoundsAreBoundedThenTheModelMustAnswer() = runBlocking {
        val model = ToolModel(List(AiWebTools.MAX_ROUNDS) { n -> { _: List<org.json.JSONObject>, _: org.json.JSONArray?, _: String -> call("web_search", """{"queries":["q$n"]}""", "c$n") } } +
            listOf({ tail, _, choice ->
                assertEquals("none", choice)
                assertTrue(tail.last().getString("content").endsWith("（${AiWebTools.LIMIT_NOTICE}）"))
                assertEquals(1, tail.count { it.optString("content").contains(AiWebTools.LIMIT_NOTICE) })
                AiReply("够了")
            }))
        ask(model, true) { sessions, id ->
            assertEquals(AiWebTools.MAX_ROUNDS, model.searches.size)
            assertEquals("够了", sessions.chats[id]!!.turns.single().answer)
        }
    }
    @Test fun badArgumentsAndUnknownToolsAreAnsweredNotRun() = runBlocking {
        val model = ToolModel(listOf(
            { _, _, _ -> AiReply(calls = listOf(AiToolCall("a", "web_search", "{not json"), AiToolCall("b", "shell", "{}"))) },
            { tail, _, _ -> assertEquals(listOf("a", "b"), tail.drop(1).map { it.getString("tool_call_id") }); AiReply("好") },
        ))
        ask(model, true) { _, _ -> assertTrue(model.searches.isEmpty()) }
    }

    private val paper = AiPdf("paper.pdf", id = "b".repeat(64), pageCount = 12)
    private class Pages : AiPdfAccess {
        val read = mutableListOf<Int>()
        override suspend fun read(pdf: AiPdf, start: Int, end: Int): String { read.addAll(start..end); return "Exact Paper Title p$start" }
        override suspend fun image(pdf: AiPdf, page: Int) = "cGFnZQ=="
    }
    private suspend fun askPdf(model: AiTransport, search: Boolean, pages: Pages, block: (AiSessions, String) -> Unit) = coroutineScope {
        val dir = Files.createTempDirectory("sessions").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            val sessions = AiSessions(AiHistoryStore(dir), scope, { AiConfig("https://example.com/v1", "vision", "key", "search-key") }, { model }, pdfAccess = pages)
            until { sessions.ready }; val id = sessions.newChat()
            sessions.send(id, AiMessage("user", "这篇论文讲什么", pdf = paper), search)
            until { !sessions.isRunning(id) }; sessions.flush()
            block(sessions, id)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
    private fun names(tools: org.json.JSONArray?) = tools?.let { t -> (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") } }
    @Test fun answeringModelReadsPdfThroughNativeToolsAndSeesPageImages() = runBlocking {
        val pages = Pages()
        val model = ToolModel(listOf(
            { tail, tools, choice ->
                assertTrue(tail.isEmpty()); assertEquals("auto", choice)
                assertEquals(listOf("pdf_read", "pdf_search", "pdf_view_page"), names(tools))
                AiReply(calls = listOf(AiToolCall("r", "pdf_read", """{"attachment_id":"${"b".repeat(64)}","start_page":1}"""),
                    AiToolCall("v", "pdf_view_page", """{"attachment_id":"${"b".repeat(64)}","page":2}""")))
            },
            { tail, _, _ ->
                assertEquals(listOf("assistant", "tool", "tool", "user"), tail.map { it.getString("role") })
                assertTrue(tail[1].getString("content").contains("Exact Paper Title p1"))
                assertTrue(tail[3].toString().contains("data:image/jpeg;base64,cGFnZQ=="))
                AiReply("论文讲注意力")
            },
        ))
        askPdf(model, false, pages) { sessions, id ->
            assertEquals(listOf(1), pages.read)
            val turn = sessions.chats[id]!!.turns.single()
            assertEquals("论文讲注意力", turn.answer); assertEquals("已完成", turn.state); assertEquals("", turn.error)
        }
    }
    @Test fun aModelWithoutPictureInputGoesOnInTextWhenPageImagesAreRefused() = runBlocking {
        val pages = Pages()
        val id = "b".repeat(64)
        val model = ToolModel(listOf(
            { _, _, _ -> AiReply(calls = listOf(AiToolCall("v", "pdf_view_page", """{"attachment_id":"$id","page":2}"""))) },
            { tail, _, _ -> assertTrue(tail.any(AiPdfTools::isImageMessage)); throw AiToolsUnsupported(400, "image input is not supported") },
            { tail, _, _ ->
                assertFalse(tail.any(AiPdfTools::isImageMessage))
                assertTrue(tail.last().getString("content").contains(AiPdfTools.IMAGES_REFUSED))
                AiReply(calls = listOf(AiToolCall("v2", "pdf_view_page", """{"attachment_id":"$id","page":3}"""), AiToolCall("r", "pdf_read", """{"attachment_id":"$id","start_page":2}""")))
            },
            { tail, _, _ ->
                assertTrue(tail.first { it.optString("tool_call_id") == "v2" }.getString("content").contains(AiPdfTools.IMAGES_REFUSED))
                assertFalse(tail.any(AiPdfTools::isImageMessage))
                AiReply("按文字作答")
            },
        ))
        askPdf(model, false, pages) { sessions, id2 ->
            val turn = sessions.chats[id2]!!.turns.single()
            assertEquals("按文字作答", turn.answer); assertEquals("已完成", turn.state)
            assertEquals(listOf(2), pages.read)
        }
    }
    @Test fun pdfAndWebToolsAreOfferedTogetherAndRoutedByName() = runBlocking {
        val pages = Pages()
        val model = ToolModel(listOf(
            { _, tools, _ ->
                assertEquals(listOf("web_search", "web_fetch", "pdf_read", "pdf_search", "pdf_view_page"), names(tools))
                AiReply(calls = listOf(AiToolCall("w", "web_search", """{"queries":["Exact Paper Title"]}"""),
                    AiToolCall("p", "pdf_read", """{"attachment_id":"${"b".repeat(64)}","start_page":2}"""), AiToolCall("x", "shell", "{}")))
            },
            { tail, _, _ ->
                assertEquals(listOf("w", "p", "x"), tail.drop(1).map { it.getString("tool_call_id") })
                assertTrue(tail[3].getString("content").startsWith("工具调用失败"))
                AiReply("好")
            },
        ))
        askPdf(model, true, pages) { _, _ ->
            assertEquals(listOf("Exact Paper Title" to "这篇论文讲什么"), model.searches)
            assertEquals(listOf(2), pages.read)
        }
    }
    @Test fun withoutToolCallingPdfFallsBackToTheJsonLoop() = runBlocking {
        val pages = Pages()
        val streamed = mutableListOf<String>()
        val model = object : AiTransport {
            var completes = 0
            override fun cancel() {}
            override fun search(key: String, query: String, question: String): List<AiWebSource> = error("not expected")
            override fun fetch(key: String, url: String) = ""
            override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) {
                val reply = if (streamed.isEmpty()) """{"tool":"read","id":"${"b".repeat(64)}","start":3,"end":3}""" else """{"tool":"done"}"""
                streamed += reply; delta(reply)
            }
            override suspend fun complete(config: AiConfig, messages: List<AiMessage>, tail: List<org.json.JSONObject>, tools: org.json.JSONArray?, toolChoice: String, delta: suspend (String) -> Unit): AiReply {
                completes++
                if (tools != null) throw AiToolsUnsupported(400)
                assertTrue(messages.last().text.contains("Exact Paper Title p3"))
                delta("按第 3 页回答"); return AiReply("按第 3 页回答")
            }
        }
        askPdf(model, false, pages) { sessions, id ->
            assertEquals(listOf(3), pages.read); assertEquals(2, model.completes)
            val turn = sessions.chats[id]!!.turns.single()
            assertEquals("按第 3 页回答", turn.answer); assertEquals("已完成", turn.state); assertEquals("", turn.error)
        }
    }
}
