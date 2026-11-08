package com.xnotes.ai

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

/** App-owned jobs and a serialized disk queue; UI disposal never cancels generation. */
class AiSessions(
    private val store: AiHistoryStore,
    private val scope: CoroutineScope,
    private val config: () -> AiConfig,
    private val transport: () -> AiTransport = { AiConnection() },
    private val pdfAccess: AiPdfAccess? = null,
    private val holdPdfs: (Set<String>) -> AutoCloseable = { AutoCloseable {} },
    private val preparePdf: suspend (String, String?) -> AiPdf = { _, _ -> error(AiText.t(AiMsg.PDF_SERVICE_UNAVAILABLE)) },
) {
    /** Pending screenshots per chat, in capture order. */
    val attachments = mutableStateMapOf<String, List<ByteArray>>()
    val pdfAttachments = mutableStateMapOf<String, AiPdf>()
    val currentPdfs = mutableStateMapOf<String, Pair<String, String>>()
    val drafts = mutableStateMapOf<String, String>()
    var historyQuery by mutableStateOf("")
    val chats = mutableStateMapOf<String, AiChat>()
    var floatingOwner by mutableStateOf<String?>(null); private set
    var activeId by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false); private set
    var storageError by mutableStateOf(""); private set
    /** Chats moved to Trash, kept on disk until restored or deleted permanently; never in [chats]. */
    val trash = mutableStateMapOf<String, AiChat>()
    /** The notes Trash retention in days, set by the app: 0 means Trash is off and [delete] is permanent. */
    var trashDays by mutableStateOf(30)
    val trashOff: Boolean get() = trashDays == 0
    val searchEnabled = mutableStateMapOf<String, Boolean>()
    val importStates = mutableStateMapOf<String, AiImportState>()
    val scrollPositions = mutableMapOf<String, Int>()
    /** Where the floating card was last left, in px of its host; a new chat opens there too. Null until moved. */
    var windowOffset by mutableStateOf<Pair<Float, Float>?>(null)
    /** The floating card folded to its title strip; kept here so a new capture can unfold it. */
    var floatingCollapsed by mutableStateOf(false)
    /** Documents open in panes, by document key, registered by their editors while composed. */
    val documents = mutableStateMapOf<String, AiDocument>()
    /** Every composed pane's screenshot tool, by a per-pane token: two panes may show one document. */
    private val captureTargets = mutableStateMapOf<Any, AiCaptureTarget>()
    val canCapture: Boolean get() = captureTargets.isNotEmpty()
    /** The floating card's capture mode: every pane has its screenshot tool armed, the card is folded. */
    var capturing by mutableStateOf(false); private set
    /** Bumped when AI settings are saved, so open chats re-read their model choices. */
    var configRevision by mutableStateOf(0)
    val followLatest = mutableMapOf<String, Boolean>()
    private val composerVersions = mutableMapOf<String, Long>()
    private val imports = mutableMapOf<String, Job>()
    private val draftSaves = mutableMapOf<String, Job>()
    private val requests = mutableStateMapOf<String, Pair<AiTransport, Job>>()
    private data class Disk(val write: AiChat? = null, val delete: String? = null, val done: CompletableDeferred<Boolean> = CompletableDeferred())
    private val disk = Channel<Disk>(Channel.UNLIMITED)
    init {
        scope.launch {
            try {
                val (loaded, damaged) = withContext(Dispatchers.IO) { store.readAll() }
                loaded.forEach { chat -> if (chat.trashedAt > 0) trash[chat.id] = chat.recover() else { chats[chat.id] = chat.recover(); restoreDraft(chat) } }
                if (damaged > 0) storageError = AiText.t(AiMsg.HISTORY_DAMAGED, damaged)
                loaded.filter { it != it.recover() }.forEach { persist(it.recover()) }
            } catch (_: Exception) { storageError = AiText.t(AiMsg.HISTORY_READ_FAILED) }
            ready = true
            for (operation in disk) {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        operation.write?.let(store::write)
                        operation.delete?.let(store::delete)
                    }
                    true
                } catch (_: Exception) { storageError = AiText.t(AiMsg.HISTORY_SAVE_FAILED); false }
                operation.done.complete(ok)
            }
        }
    }
    private fun persist(chat: AiChat): Deferred<Boolean> = Disk(write = chat).also { disk.trySend(it) }.done
    suspend fun flush(): Boolean {
        draftSaves.values.toList().forEach { it.join() }
        return Disk().also { disk.send(it) }.done.await()
    }
    private fun restoreDraft(chat: AiChat) {
        drafts[chat.id] = chat.draft.text
        searchEnabled[chat.id] = chat.draft.search
        if (chat.draft.images.isNotEmpty())
            runCatching { chat.draft.images.map { java.util.Base64.getDecoder().decode(it) } }.onSuccess { attachments[chat.id] = it }.onFailure { storageError = AiText.t(AiMsg.DRAFT_SCREENSHOT_UNREADABLE) }
        chat.draft.pdf?.let { pdfAttachments[chat.id] = it }
    }
    fun editDraft(id: String, text: String? = null, search: Boolean? = null) {
        if (!chats.containsKey(id)) return
        text?.let { drafts[id] = it.take(16000) }; search?.let { searchEnabled[id] = it }
        saveDraft(id)
    }
    /**
     * Adds a screenshot to the chat's next question; false when it already holds [AiProtocol.MAX_IMAGES].
     * A capture from another document ([fromDocument], its key) records that document on the chat, so
     * the chat also appears in that document's floating history; it still belongs to its source.
     */
    fun addScreenshot(id: String, bytes: ByteArray, fromDocument: String = ""): Boolean {
        val chat = chats[id] ?: return false
        val current = attachments[id].orEmpty()
        if (current.size >= AiProtocol.MAX_IMAGES) return false
        attachments[id] = current + bytes
        if (fromDocument.isNotBlank() && fromDocument != chat.documentKey && fromDocument !in chat.relatedDocuments)
            chats[id] = chat.copy(relatedDocuments = chat.relatedDocuments + fromDocument)
        saveDraft(id)
        return true
    }
    fun removeScreenshot(id: String, index: Int) {
        val current = attachments[id] ?: return
        if (index !in current.indices) return
        val next = current.filterIndexed { i, _ -> i != index }
        if (next.isEmpty()) attachments.remove(id) else attachments[id] = next
        saveDraft(id)
    }
    fun removePdf(id: String) {
        imports.remove(id)?.cancel(); importStates.remove(id); pdfAttachments.remove(id); saveDraft(id)
    }
    fun restoreQuestion(id: String, user: AiMessage) {
        if (!chats.containsKey(id) || isRunning(id)) return
        removePdf(id)
        drafts[id] = user.text
        if (user.pdf != null) pdfAttachments[id] = user.pdf
        attachments.remove(id)
        if (user.images.isNotEmpty()) attachments[id] = user.images.map { java.util.Base64.getDecoder().decode(it) }
        saveDraft(id)
    }
    private fun encoded(id: String): List<String> = attachments[id].orEmpty().map { java.util.Base64.getEncoder().encodeToString(it) }
    private fun saveDraft(id: String) {
        val chat = chats[id] ?: return
        val draft = AiDraft(drafts[id].orEmpty(), encoded(id), pdfAttachments[id], searchEnabled[id] ?: false)
        chats[id] = chat.copy(draft = draft)
        draftSaves.remove(id)?.cancel()
        draftSaves[id] = scope.launch {
            delay(350)
            chats[id]?.let { persist(it) }
            draftSaves.remove(id)
        }
    }
    fun importPdf(id: String, uri: String, name: String? = null) {
        if (!chats.containsKey(id)) return
        imports.remove(id)?.cancel()
        val token = Any()
        importStates[id] = AiImportState(uri, name, loading = true, token = token)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val pdf = withContext(Dispatchers.IO) { preparePdf(uri, name) }
                if (chats.containsKey(id) && importStates[id]?.token === token) {
                    pdfAttachments[id] = pdf; importStates.remove(id); saveDraft(id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (chats.containsKey(id) && importStates[id]?.token === token)
                    importStates[id] = AiImportState(uri, name, error = if (e is IllegalArgumentException) e.message ?: AiText.t(AiMsg.PDF_UNREADABLE) else AiText.t(AiMsg.PDF_UNREADABLE_CHECK), token = token)
            } finally { if (importStates[id]?.token === token || importStates[id] == null) imports.remove(id) }
        }
        imports[id] = job; job.start()
    }
    fun retryImport(id: String) { importStates[id]?.let { importPdf(id, it.uri, it.name) } }
    fun composerVersion(id: String): Long = composerVersions[id] ?: 0L
    /** Also invalidates picker/import results belonging to the discarded composer. */
    private fun discardUnstartedChat(id: String, keepOpen: Boolean = false) {
        val chat = chats[id] ?: return
        if (chat.turns.isNotEmpty() || isRunning(id)) return
        composerVersions[id] = composerVersion(id) + 1L
        imports.remove(id)?.cancel()
        importStates.remove(id)
        attachments.remove(id)
        pdfAttachments.remove(id)
        drafts.remove(id)
        draftSaves.remove(id)?.cancel()
        val cleared = chat.copy(draft = AiDraft(search = searchEnabled[id] ?: false), relatedDocuments = emptyList())
        if (keepOpen) chats[id] = cleared else {
            chats.remove(id)
            searchEnabled.remove(id); currentPdfs.remove(id)
        }
        scrollPositions.remove(id); followLatest.remove(id)
        // Unlike deleting a conversation, abandoning an empty input creates no Trash entry.
        val op = Disk(delete = id)
        disk.trySend(op)
        scope.launch {
            if (op.done.await()) withContext(Dispatchers.IO) { store.forgetDeleted(id) }
            else if (!keepOpen) { chats[id] = cleared; restoreDraft(cleared) }
        }
    }
    private suspend fun progress(id: String, label: String) = withContext(scope.coroutineContext.minusKey(Job)) {
        update(id) { c -> c.copy(turns = c.turns.dropLast(1) + c.turns.last().let { it.copy(progress = label, steps = (it.steps + label).takeLast(20)) }) }?.let { persist(it) }
    }
    fun retrySave() { storageError = ""; chats.values.forEach { persist(it) } }
    fun newChat(source: String = "", sourceUri: String = "", floatingOwner: String? = null, sourceDocumentId: String = ""): String {
        check(ready)
        endCapture()
        // The floating card's own "new chat" names only its document: take that document's details from
        // its open editor, else from the card's chat when that chat was started there (not merely joined).
        val bare = source.isBlank() && sourceUri.isBlank() && sourceDocumentId.isBlank()
        val open = if (bare) floatingOwner?.let { documents[it] } else null
        val previous = if (floatingOwner != null && this.floatingOwner == floatingOwner) activeId?.let { chats[it] }?.takeIf { it.documentKey.isBlank() || it.documentKey == floatingOwner } else null
        val chat = if (open != null) AiChat(source = open.source, sourceUri = open.uri, sourceDocumentId = open.documentId)
            else AiChat(source = source.ifBlank { previous?.source.orEmpty() }, sourceUri = sourceUri.ifBlank { previous?.sourceUri.orEmpty() }, sourceDocumentId = sourceDocumentId.ifBlank { previous?.sourceDocumentId.orEmpty() })
        val previousPdf = open?.pdf ?: previous?.let { currentPdfs[it.id] }
        val current = activeId?.let { chats[it] }
        if (current != null && current.turns.isEmpty() && !isRunning(current.id) &&
            this.floatingOwner == floatingOwner && current.source == chat.source &&
            current.sourceUri == chat.sourceUri && current.sourceDocumentId == chat.sourceDocumentId) {
            discardUnstartedChat(current.id, keepOpen = true)
            return current.id
        }
        current?.let { discardUnstartedChat(it.id) }
        this.floatingOwner = floatingOwner
        // Unreadable settings only lose the defaults; the chat itself still opens.
        val defaults = runCatching { config() }.getOrNull()
        val started = if (defaults?.searchByDefault == true) chat.copy(draft = chat.draft.copy(search = true)) else chat
        if (started.draft.search) searchEnabled[chat.id] = true
        chats[chat.id] = started; activeId = chat.id
        previousPdf?.let { (uri, name) -> offerCurrentPdf(chat.id, uri, name, defaults) }
        return chat.id
    }
    /** The document's own PDF, offered in the ＋ menu; attached at once when the settings ask for it. */
    fun offerCurrentPdf(id: String, uri: String, name: String, defaults: AiConfig? = runCatching { config() }.getOrNull()) {
        if (!chats.containsKey(id)) return
        currentPdfs[id] = uri to name
        if (defaults?.attachPdfByDefault == true && pdfAttachments[id] == null && importStates[id] == null) importPdf(id, uri, name)
    }
    fun close() {
        endCapture()
        activeId?.let { discardUnstartedChat(it) }
        activeId = null; floatingOwner = null
    }
    fun registerCapture(token: Any, target: AiCaptureTarget) { captureTargets[token] = target }
    fun unregisterCapture(token: Any) { captureTargets.remove(token) }
    /** Fold the card and arm the screenshot tool in every pane, so either document can be captured. */
    fun startCapture() {
        if (floatingOwner == null || activeId == null) return
        capturing = true; floatingCollapsed = true
        captureTargets.values.toList().forEach { it.arm() }
    }
    /** Leave capture mode: each pane gets back the tool it had, unless the user has picked another since. */
    fun endCapture() {
        if (!capturing) return
        capturing = false
        captureTargets.values.toList().forEach { it.release() }
    }
    /** Unfold the card; ends capture mode, since the chat is about to be read or typed into. */
    fun expand() { floatingCollapsed = false; endCapture() }
    /**
     * Hides the card when its document leaves the screen. A chat that also holds screenshots from a
     * still-visible document moves over to that one instead of closing.
     */
    fun reconcileFloating(visibleDocuments: Set<String>) {
        val owner = floatingOwner ?: return
        if (owner in visibleDocuments) return
        val chat = activeId?.let { chats[it] }
        val next = chat?.let { c -> (listOf(c.documentKey) + c.relatedDocuments).firstOrNull { it in visibleDocuments } }
        if (next != null) floatingOwner = next else close()
    }
    fun open(id: String) { if (chats.containsKey(id)) { activeId = id; floatingOwner = null } }
    /** Switch the floating card to another chat of the same document; the document's PDF stays on offer. */
    fun openFloating(id: String) {
        if (!chats.containsKey(id) || floatingOwner == null) return
        val offer = floatingOwner?.let { documents[it]?.pdf } ?: activeId?.let { currentPdfs[it] }
        activeId = id
        if (offer != null && currentPdfs[id] == null) currentPdfs[id] = offer
    }
    /** Chats started from [owner], the floating card's document key, newest first. */
    fun chatsOf(owner: String): List<AiChat> = chats.values.filter { it.belongsTo(owner) }.sortedByDescending { it.updated }
    fun rename(id: String, title: String) {
        chats[id]?.let { val next = it.copy(title = title.trim().take(100).ifBlank { "新会话" }); chats[id] = next; persist(next) }
    }
    /**
     * Pick this chat's model (through [provider], when providers are set up) and thinking level for its
     * next questions; a running answer keeps its own.
     */
    fun choose(id: String, model: String? = null, effort: String? = null, provider: String? = null) {
        val chat = chats[id] ?: return
        val next = chat.copy(model = model?.trim() ?: chat.model, provider = provider ?: chat.provider, effort = effort?.takeIf { it in AiThinking.LEVELS } ?: chat.effort)
        if (next != chat) { chats[id] = next; persist(next) }
    }
    fun isRunning(id: String) = requests.containsKey(id)
    private fun update(id: String, change: (AiChat) -> AiChat): AiChat? = chats[id]?.let { change(it).also { next -> chats[id] = next } }
    fun send(id: String, user: AiMessage, useSearch: Boolean) {
        if (!ready || isRunning(id) || importStates[id]?.loading == true || importStates[id]?.error?.isNotEmpty() == true) return
        val original = chats[id] ?: return
        val client = transport()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var started = false
            var pdfLease: AutoCloseable? = null
            try {
                val defaults = config()
                require(original.routable(defaults)) { AiText.t(AiMsg.PROVIDER_REMOVED) }
                val settings = original.settings(defaults)
                val tools = settings.forTools()
                require(settings.key.isNotBlank()) { AiText.t(AiMsg.PROVIDER_NOT_CONFIGURED) }
                AiProtocol.endpoint(settings.url)
                AiProtocol.request(settings, original.context() + user)
                if (useSearch) { require(settings.searchKey.isNotBlank()) { AiText.t(AiMsg.SEARCH_KEY_NOT_CONFIGURED) } }
                val next = original.copy(draft = chats[id]?.draft ?: original.draft, title = if (original.turns.isEmpty() && original.title == "新会话") user.text.take(40) else original.title,
                    updated = System.currentTimeMillis(), turns = original.turns + AiTurn(user))
                chats[id] = next; started = true
                check(persist(next).await()) { AiText.t(AiMsg.QUESTION_SAVE_FAILED) }
                // Clear only the submitted composer; edits made while saving belong to the next turn.
                if (drafts[id].orEmpty().trim() == user.text && pdfAttachments[id] == user.pdf &&
                    encoded(id) == user.images) {
                    drafts.remove(id); attachments.remove(id); pdfAttachments.remove(id); saveDraft(id)
                }
                val pdfIds=(original.context()+user).mapNotNull { it.pdf?.id?.takeIf(String::isNotBlank) }.toSet()
                pdfLease=withContext(Dispatchers.IO) { holdPdfs(pdfIds) }
                progress(id, AiText.t(AiMsg.PROGRESS_PREPARING))
                val history = original.context()
                // Web search and PDF reading are tools the answering model calls when it needs them, as in
                // DeepSeek Harness. A model or API without tool calling answers without web search, and the turn
                // says so; its PDFs are then read through the earlier JSON loop before it answers.
                val pdfFiles = AiPdfTools.files(history + user)
                val access = if (pdfFiles.isEmpty()) null else checkNotNull(pdfAccess) { AiText.t(AiMsg.PDF_SERVICE_UNAVAILABLE) }
                var pdfReader = access?.let { AiPdfTools.Reader(pdfFiles, it) }
                var prepared = user
                progress(id, AiText.t(AiMsg.PROGRESS_GENERATING))
                val tail = mutableListOf<org.json.JSONObject>()
                val evidence = mutableListOf<String>()
                val failures = mutableListOf<String>()
                var offerWeb = useSearch
                var unsupported = ""
                var thinkingDropped = false
                var rounds = 0
                var lastSave = System.nanoTime()
                val append: suspend (String) -> Unit = { chunk ->
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        val chat = update(id) { c -> c.copy(turns = c.turns.dropLast(1) + c.turns.last().copy(answer = c.turns.last().answer + chunk)) }
                        if (chat != null && System.nanoTime() - lastSave > 1_000_000_000L) { persist(chat); lastSave = System.nanoTime() }
                    }
                }
                while (true) {
                    val reader = pdfReader
                    val offered = org.json.JSONArray().apply {
                        if (offerWeb) AiWebTools.definitions().let { d -> for (i in 0 until d.length()) put(d.get(i)) }
                        if (reader != null) AiPdfTools.definitions().let { d -> for (i in 0 until d.length()) put(d.get(i)) }
                    }.takeIf { it.length() > 0 }
                    val maxRounds = if (reader != null) AiPdfTools.MAX_ROUNDS else AiWebTools.MAX_ROUNDS
                    val choice = when { offered == null -> null; rounds >= maxRounds -> "none"; else -> "auto" }
                    val reply = try {
                        withContext(Dispatchers.IO) { client.complete(settings, history + prepared, tail, offered, choice ?: "auto", append) }
                    } catch (e: AiToolsUnsupported) {
                        // A model that takes no pictures refuses the request carrying page images: drop them,
                        // tell it so, and go on reading text. Only once, so another cause still ends the turn.
                        val textOnly = if (reader != null && !reader.imagesOff) AiPdfTools.withoutImages(tail) else null
                        if (textOnly != null) {
                            reader!!.imagesOff = true
                            tail.clear(); tail += textOnly
                            continue
                        }
                        // Only the first request can be refused for offering tools; later ones already carry tool rounds.
                        if (choice == null || tail.isNotEmpty()) throw IllegalStateException(e.message)
                        if (offerWeb) { offerWeb = false; unsupported = AiText.t(AiMsg.TOOLS_UNSUPPORTED); progress(id, unsupported) }
                        if (reader != null && access != null) {
                            pdfReader = null
                            prepared = withContext(Dispatchers.IO) { AiPdfTools.prepare(tools, client, history, user, access) { label -> progress(id, label) } }
                            progress(id, AiText.t(AiMsg.PROGRESS_GENERATING))
                        }
                        continue
                    }
                    if (reply.thinkingDropped) thinkingDropped = true
                    if (reply.calls.isEmpty() || choice != "auto") break
                    rounds++
                    // Each call goes to the tool set that was offered; anything else is answered, not run.
                    val results = arrayOfNulls<org.json.JSONObject>(reply.calls.size)
                    val webCalls = reply.calls.withIndex().filter { offerWeb && (it.value.name == AiWebTools.SEARCH || it.value.name == AiWebTools.FETCH) }
                    val pdfCalls = reply.calls.withIndex().filter { reader != null && it.value.name in AiPdfTools.NAMES }
                    if (webCalls.isNotEmpty()) {
                        val round = AiWebTools.run(webCalls.map { it.value }, client, settings.searchKey, user.text) { label -> progress(id, label) }
                        webCalls.forEachIndexed { n, call -> results[call.index] = round.messages[n] }
                        evidence += round.evidence; failures += round.failures
                    }
                    val pdfRound = if (pdfCalls.isNotEmpty() && reader != null) reader.run(pdfCalls.map { it.value }) { label -> progress(id, label) } else null
                    pdfRound?.let { r -> pdfCalls.forEachIndexed { n, call -> results[call.index] = r.messages[n] } }
                    reply.calls.forEachIndexed { n, call ->
                        if (results[n] == null) results[n] = org.json.JSONObject().put("role", "tool").put("tool_call_id", call.id)
                            .put("content", "工具调用失败：" + AiText.t(AiMsg.WEB_TOOL_UNKNOWN))
                    }
                    tail += AiWebTools.assistantMessage(reply)
                    // The next request withholds tools; the model is told why in the results it reads.
                    val messages = results.filterNotNull()
                    tail += if (rounds >= maxRounds) AiWebTools.withLimitNotice(messages, if (reader != null) AiPdfTools.LIMIT_NOTICE else AiWebTools.LIMIT_NOTICE) else messages
                    pdfRound?.images?.let { tail += it }
                }
                // What was found stays with the question, for follow-ups and the details view.
                if (evidence.isNotEmpty()) update(id) { c ->
                    c.copy(turns = c.turns.dropLast(1) + c.turns.last().let { it.copy(user = it.user.copy(searchContext = evidence.joinToString("\n\n").take(AiWebTools.EVIDENCE_MAX))) })
                }?.let { persist(it) }
                check(chats[id]?.turns?.lastOrNull()?.answer?.isNotBlank() == true) { AiText.t(AiMsg.NO_TEXT_RETURNED) }
                finish(id, "已完成", listOfNotNull(unsupported.ifEmpty { null }, AiText.t(AiMsg.THINKING_UNSUPPORTED).takeIf { thinkingDropped }, failures.firstOrNull()?.let { AiText.t(AiMsg.WEB_TOOL_FAILED, it) }).joinToString(" · "))
            } catch (e: CancellationException) {
                if (started) finish(id, "已停止", AiText.t(AiMsg.STOPPED_BY_USER))
                throw e
            } catch (e: Exception) {
                val message = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: AiText.t(AiMsg.REQUEST_FAILED) else AiText.t(AiMsg.NETWORK_ERROR)
                if (started) finish(id, "生成失败", message)
                else update(id) { c -> c.copy(turns = c.turns + AiTurn(user, state = "生成失败", error = message)) }?.let { persist(it) }
            } finally {
                try { withContext(NonCancellable + Dispatchers.IO) { pdfLease?.close() } }
                finally { requests.remove(id) }
            }
        }
        requests[id] = client to job; job.start()
    }
    private fun finish(id: String, state: String, error: String) {
        update(id) { c -> c.copy(updated = System.currentTimeMillis(), turns = c.turns.dropLast(1) + c.turns.last().copy(state = state, error = error)) }?.let { persist(it) }
    }
    fun stop(id: String) { requests[id]?.let { (client, job) -> client.cancel(); job.cancel() } }
    /**
     * Deletes a chat as notes are deleted: generation and imports stop and it leaves every history list.
     * It moves to Trash, its record (draft and PDF references included) kept on disk until restored or
     * deleted permanently — or, with Trash off, it is deleted permanently at once. A floating card on
     * it moves to the document's newest other chat, or a fresh one.
     */
    fun delete(id: String) {
        val chat = chats[id] ?: return
        val permanent = trashOff
        stop(id)
        imports.remove(id)?.cancel(); importStates.remove(id); draftSaves.remove(id)?.cancel()
        searchEnabled.remove(id); scrollPositions.remove(id); followLatest.remove(id)
        composerVersions.remove(id)
        attachments.remove(id); drafts.remove(id); pdfAttachments.remove(id); currentPdfs.remove(id)
        chats.remove(id) // Late callbacks cannot recreate a trashed record.
        val trashed = chat.recover().copy(trashedAt = System.currentTimeMillis())
        if (!permanent) trash[id] = trashed
        if (activeId == id) {
            val owner = floatingOwner
            val next = owner?.let { chatsOf(it).firstOrNull() }
            when {
                next != null -> activeId = next.id
                owner != null && documents.containsKey(owner) -> newChat(floatingOwner = owner)
                else -> close()
            }
        }
        // Queued now, after every earlier write of this chat; if it fails the chat goes back where it was, with the storage error shown.
        if (permanent) {
            val op = Disk(delete = id).also { disk.trySend(it) }
            scope.launch {
                if (op.done.await()) withContext(Dispatchers.IO) { runCatching { store.forgetDeleted(id) } }
                else { chats[id] = chat; restoreDraft(chat) }
            }
        } else {
            val saved = persist(trashed)
            scope.launch { if (!saved.await() && trash[id] === trashed) { trash.remove(id); chats[id] = chat; restoreDraft(chat) } }
        }
    }
    /** Back from Trash into history, draft and attachments as they were. */
    fun restore(id: String) {
        val chat = trash.remove(id) ?: return
        val back = chat.copy(trashedAt = 0L)
        chats[id] = back; restoreDraft(back); persist(back)
    }
    /** Removes a trashed chat's record for good; its PDF snapshots are released once nothing else holds them. */
    fun deleteForever(id: String) {
        val chat = trash.remove(id) ?: return
        // Queued now, in order with the writes before it; only the outcome is awaited.
        val op = Disk(delete = id).also { disk.trySend(it) }
        scope.launch {
            if (op.done.await()) withContext(Dispatchers.IO) { runCatching { store.forgetDeleted(id) } }
            else trash[id] = chat
        }
    }
    fun emptyTrash() = trash.keys.toList().forEach(::deleteForever)
    /** Permanently deletes chats trashed more than [days] days ago; 0 or less keeps them until emptied by hand. */
    fun purgeTrash(days: Int, now: Long = System.currentTimeMillis()) {
        if (days <= 0) return
        val cutoff = now - days * 86_400_000L
        trash.values.filter { it.trashedAt in 1 until cutoff }.forEach { deleteForever(it.id) }
    }
    companion object {
        @Volatile private var instance: AiSessions? = null
        fun get(context: Context): AiSessions {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: AiSessions(AiHistoryStore(File(app.noBackupFilesDir, "ai-history"), com.xnotes.storage.PdfResources.get(app)),
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), { AiConfigStore(app).load() }, pdfAccess = LocalAiPdfAccess(app), holdPdfs = { com.xnotes.storage.PdfResources.get(app).hold(it) }, preparePdf = { uri, name -> AiPdfReader.read(app, android.net.Uri.parse(uri), name) }).also { instance = it }
            }
        }
    }
}

/**
 * A document open in a pane, as its editor registers it under its key: what a chat started from it
 * records and its PDF to offer in ＋ (URI and name).
 */
class AiDocument(val source: String, val uri: String, val documentId: String, val pdf: Pair<String, String>?)

/** One pane's screenshot tool as the floating card's capture mode drives it: [arm] it, then [release] it back. */
class AiCaptureTarget(val arm: () -> Unit, val release: () -> Unit)

data class AiImportState(val uri: String, val name: String?, val loading: Boolean = false, val error: String = "", val token: Any = Any())
