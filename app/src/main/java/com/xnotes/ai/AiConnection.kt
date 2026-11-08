package com.xnotes.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * One model provider, configured as DeepSeek Harness configures a route: its own endpoint, key, model
 * list and thinking-field format. [id] is permanent — chats and the default name a provider by it — so
 * it is generated once and never shown; [name] is the editable label. Only OpenAI Chat Completions is spoken.
 */
data class AiProvider(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "", val url: String = "", val key: String = "",
    val models: List<String> = emptyList(),
    val thinkingFormat: String = AiThinking.AUTO,
    /** Models this provider paints pictures with (its Images API), kept apart from the chat [models]. */
    val imageModels: List<String> = emptyList(),
) {
    /** The saved chat models in the user's order, trimmed and without repeats. */
    val modelChoices: List<String> get() = models.map(String::trim).filter(String::isNotEmpty).distinct()
    /** The saved image models, likewise. */
    val imageChoices: List<String> get() = imageModels.map(String::trim).filter(String::isNotEmpty).distinct()
    /** The label, else the endpoint host, so an unnamed provider still reads as something. */
    val label: String get() = name.trim().ifEmpty { runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty().ifEmpty { url.trim() } }
}

/**
 * The AI setup, resolved to one route. [providers] are the configured providers and [provider] the
 * default one's ID; [url], [key], [models] and [thinkingFormat] belong to the route in use and are
 * filled by [route]. A config without providers (tests, the request builder) is a single bare route.
 * [model] is the default for new chats, [effort] the default thinking level ("" leaves it to the
 * provider), [instructions] the user's own system prompt for answers. [searchByDefault] and
 * [attachPdfByDefault] are how a new chat starts out.
 */
data class AiConfig(
    val url: String = "", val model: String = "", val key: String = "", val searchKey: String = "",
    val models: List<String> = emptyList(),
    val effort: String = "",
    val thinkingFormat: String = AiThinking.AUTO,
    val maxTokens: Int = AiProtocol.DEFAULT_MAX_TOKENS,
    val instructions: String = "",
    val searchByDefault: Boolean = false,
    val attachPdfByDefault: Boolean = false,
    val providers: List<AiProvider> = emptyList(),
    val provider: String = "",
    /** The provider and model that paint pictures (an OpenAI-style Images API); none when [imageModel] is blank. */
    val imageProvider: String = "",
    val imageModel: String = "",
) {
    /** This setup sending picture requests to [imageModel] through [imageProvider]; null when none is set up. */
    fun imageRoute(): AiConfig? {
        val model = imageModel.trim().takeIf { it.isNotEmpty() } ?: return null
        val p = providerOf(imageProvider) ?: return null
        return copy(provider = p.id, url = p.url, key = p.key, models = p.modelChoices, thinkingFormat = p.thinkingFormat, model = model)
    }
    /** The saved pick list in the user's order, always holding the default model. */
    val modelChoices: List<String> get() = (if (model.trim() in models.map(String::trim)) models else listOf(model) + models).map(String::trim).filter(String::isNotEmpty).distinct()
    /** PDF tool steps must return bare JSON: no thinking, no user prompt. */
    fun forTools(): AiConfig = copy(effort = "", instructions = "")
    fun providerOf(id: String): AiProvider? = providers.firstOrNull { it.id == id }
    /**
     * This setup sending [model] through provider [id]. A provider that no longer exists leaves the
     * route without endpoint or key, so nothing is sent until another model is picked.
     */
    fun route(id: String, model: String): AiConfig {
        if (providers.isEmpty()) return copy(model = model)
        val p = providerOf(id) ?: return copy(provider = id, url = "", key = "", models = emptyList(), thinkingFormat = AiThinking.AUTO, model = model)
        return copy(provider = p.id, url = p.url, key = p.key, models = p.modelChoices, thinkingFormat = p.thinkingFormat, model = model)
    }
    /**
     * After providers were edited or deleted: the default stays where it was if it still exists, else
     * moves to the first provider and its first model, so settings never save a dangling default.
     */
    fun withValidDefault(): AiConfig {
        // A picture model whose provider is gone, or no longer lists it, is dropped, never moved to another provider.
        val image = if (providerOf(imageProvider)?.imageChoices?.contains(imageModel.trim()) != true) copy(imageProvider = "", imageModel = "") else this
        val p = providerOf(provider) ?: providers.firstOrNull { it.modelChoices.isNotEmpty() } ?: providers.firstOrNull()
            ?: return image.copy(provider = "", model = "", url = "", key = "", models = emptyList())
        return image.route(p.id, model.trim().takeIf { it in p.modelChoices } ?: p.modelChoices.firstOrNull().orEmpty())
    }
    /** The provider a model picked before providers existed belongs to: the default's if it lists it. */
    fun ownerOf(model: String): String? =
        (listOfNotNull(providerOf(provider)) + providers).firstOrNull { model.trim() in it.modelChoices }?.id
}

/** Keystore-encrypted, atomic, excluded from Android backup and note exports. */
class AiConfigStore(context: Context) {
    /** The default system prompt in the UI language, for a setup that has never chosen one. */
    private val defaultInstructions = AiDefaults.instructions(context.resources.configuration.locales[0].toLanguageTag())
    private val file = AtomicFile(File(context.noBackupFilesDir, "ask-ai.config"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ask-ai", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ask-ai", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): AiConfig {
        if (!file.baseFile.exists()) return AiConfig(instructions = defaultInstructions)
        val bytes = file.readFully()
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return decode(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)), defaultInstructions)
    }
    fun save(config: AiConfig) {
        validate(config)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(encode(config).toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun clear() = file.delete()


    companion object {
        /** The ID a single-provider setup from before providers existed migrates to; fixed so chats keep it. */
        const val LEGACY_PROVIDER = "default"
        const val MAX_PROVIDERS = 20

        /** The providers [config] holds, or its bare route as one provider when it has none. */
        private fun providersOf(config: AiConfig): List<AiProvider> = config.providers.ifEmpty {
            if (config.url.isBlank() && config.key.isBlank() && config.modelChoices.isEmpty()) emptyList()
            else listOf(AiProvider(LEGACY_PROVIDER, "", config.url, config.key, config.modelChoices, config.thinkingFormat))
        }

        /**
         * The stored form. The default provider's endpoint, key and models are also written at the top
         * level, where builds from before providers read them, so going back keeps the default working.
         */
        fun encode(config: AiConfig): JSONObject {
            val providers = providersOf(config)
            val current = providers.firstOrNull { it.id == config.provider } ?: providers.firstOrNull()
            val list = org.json.JSONArray()
            providers.forEach { p ->
                list.put(JSONObject().put("id", p.id).put("name", p.name.trim()).put("url", p.url.trim()).put("key", p.key.trim())
                    .put("models", org.json.JSONArray(p.modelChoices)).put("thinkingFormat", p.thinkingFormat)
                    .put("imageModels", org.json.JSONArray(p.imageChoices)))
            }
            val model = config.model.trim()
            val defaultModels = current?.modelChoices.orEmpty()
            return JSONObject().put("url", current?.url?.trim().orEmpty()).put("model", model).put("key", current?.key?.trim().orEmpty())
                .put("searchKey", config.searchKey.trim())
                .put("models", org.json.JSONArray(if (model.isEmpty() || model in defaultModels) defaultModels else listOf(model) + defaultModels))
                .put("effort", config.effort).put("thinkingFormat", current?.thinkingFormat ?: AiThinking.AUTO)
                .put("maxTokens", config.maxTokens).put("instructions", config.instructions.trim())
                .put("searchByDefault", config.searchByDefault).put("attachPdfByDefault", config.attachPdfByDefault)
                .put("providers", list).put("provider", current?.id.orEmpty())
                .put("imageProvider", config.imageProvider).put("imageModel", config.imageModel.trim())
        }

        /** A stored setup, resolved to its default route; one from before providers becomes [LEGACY_PROVIDER]. */
        fun decode(json: JSONObject, defaultInstructions: String): AiConfig {
            fun strings(from: JSONObject) = from.optJSONArray("models")?.let { list -> (0 until list.length()).mapNotNull { list.opt(it) as? String } } ?: emptyList()
            fun format(from: JSONObject) = from.optString("thinkingFormat", AiThinking.AUTO).takeIf { it in AiThinking.FORMATS } ?: AiThinking.AUTO
            val model = json.optString("model", "")
            var providers = json.optJSONArray("providers")?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { p ->
                    val id = (p.opt("id") as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val images = p.optJSONArray("imageModels")?.let { list -> (0 until list.length()).mapNotNull { list.opt(it) as? String } } ?: emptyList()
                    AiProvider(id, p.optString("name", ""), p.optString("url", ""), p.optString("key", ""), strings(p), format(p), images)
                }.distinctBy { it.id }
            } ?: run {
                val url = json.optString("url", ""); val key = json.optString("key", "")
                val legacyModels = strings(json).let { if (model.isBlank() || model in it) it else listOf(model) + it }
                if (url.isBlank() && key.isBlank() && legacyModels.isEmpty()) emptyList()
                else listOf(AiProvider(LEGACY_PROVIDER, "", url, key, legacyModels, format(json)))
            }
            // An image model saved before providers listed their own joins its provider's image list.
            val imageProviderId = json.optString("imageProvider", "")
            val savedImageModel = json.optString("imageModel", "").trim()
            if (savedImageModel.isNotEmpty()) providers = providers.map { p ->
                if (p.id == imageProviderId && savedImageModel !in p.imageChoices) p.copy(imageModels = p.imageChoices + savedImageModel) else p
            }
            val provider = json.optString("provider", "").takeIf { id -> providers.any { it.id == id } } ?: providers.firstOrNull()?.id.orEmpty()
            val config = AiConfig(searchKey = json.optString("searchKey", ""),
                effort = json.optString("effort", "").takeIf { it in AiThinking.LEVELS } ?: "",
                maxTokens = json.optInt("maxTokens", AiProtocol.DEFAULT_MAX_TOKENS).coerceIn(AiProtocol.MIN_MAX_TOKENS, AiProtocol.MAX_MAX_TOKENS),
                // A config saved before prompts existed gets the default; an emptied prompt stays empty.
                instructions = if (json.has("instructions")) json.optString("instructions", "") else defaultInstructions,
                searchByDefault = json.optBoolean("searchByDefault", false), attachPdfByDefault = json.optBoolean("attachPdfByDefault", false),
                providers = providers, provider = provider,
                imageProvider = json.optString("imageProvider", "").takeIf { id -> providers.any { it.id == id } }.orEmpty(),
                imageModel = json.optString("imageModel", "").trim())
                .let { if (it.imageProvider.isEmpty()) it.copy(imageModel = "") else it }
            return if (providers.isEmpty()) config.copy(model = model) else config.route(provider, model)
        }

        /** What one provider needs before it can be kept: an HTTPS endpoint, a key and at least one model. */
        fun validateProvider(p: AiProvider) {
            AiProtocol.endpoint(p.url)
            // A provider may serve chat, pictures or both; it needs at least one model of either kind.
            require(p.key.isNotBlank() && (p.modelChoices.isNotEmpty() || p.imageChoices.isNotEmpty())) { AiText.t(AiMsg.CONFIG_MODEL_KEY_REQUIRED) }
            require(!p.key.contains('\n') && !p.key.contains('\r')) { AiText.t(AiMsg.CONFIG_KEY_INVALID) }
            require(p.modelChoices.size <= AiProtocol.MAX_MODELS && p.imageChoices.size <= AiProtocol.MAX_MODELS) { AiText.t(AiMsg.CONFIG_TOO_MANY_MODELS, AiProtocol.MAX_MODELS) }
            require((p.modelChoices + p.imageChoices).all { it.length <= 200 && it.none { c -> c == '\n' || c == '\r' } }) { AiText.t(AiMsg.CONFIG_IMAGE_MODEL_INVALID) }
            require(p.thinkingFormat in AiThinking.FORMATS) { AiText.t(AiMsg.CONFIG_THINKING_INVALID) }
        }

        /** Everything [save] refuses, checked before anything is written; the previous file stays. */
        fun validate(config: AiConfig) {
            val providers = providersOf(config)
            require(providers.isNotEmpty()) { AiText.t(AiMsg.CONFIG_MODEL_KEY_REQUIRED) }
            require(providers.size <= MAX_PROVIDERS) { AiText.t(AiMsg.CONFIG_TOO_MANY_PROVIDERS, MAX_PROVIDERS) }
            require(providers.all { it.id.isNotBlank() } && providers.distinctBy { it.id }.size == providers.size) { AiText.t(AiMsg.CONFIG_PROVIDERS_INVALID) }
            providers.forEach { p ->
                try { validateProvider(p) } catch (e: IllegalArgumentException) { throw IllegalArgumentException(AiText.t(AiMsg.CONFIG_PROVIDER_PROBLEM, p.label.ifEmpty { "?" }, e.message.orEmpty())) }
            }
            val default = if (config.providers.isEmpty()) providers.first() else config.providerOf(config.provider)
            require(default != null && config.model.trim() in default.modelChoices + listOfNotNull(config.model.trim().takeIf { config.providers.isEmpty() && it.isNotEmpty() })) {
                AiText.t(AiMsg.CONFIG_DEFAULT_MODEL_REQUIRED)
            }
            require(!config.searchKey.contains('\n') && !config.searchKey.contains('\r')) { AiText.t(AiMsg.CONFIG_SEARCH_KEY_INVALID) }
            require(config.maxTokens in AiProtocol.MIN_MAX_TOKENS..AiProtocol.MAX_MAX_TOKENS) { AiText.t(AiMsg.CONFIG_MAX_TOKENS_INVALID, AiProtocol.MIN_MAX_TOKENS, AiProtocol.MAX_MAX_TOKENS) }
            require(config.instructions.length <= AiProtocol.MAX_INSTRUCTIONS) { AiText.t(AiMsg.CONFIG_INSTRUCTIONS_TOO_LONG, AiProtocol.MAX_INSTRUCTIONS) }
            require(config.effort in AiThinking.LEVELS) { AiText.t(AiMsg.CONFIG_THINKING_INVALID) }
            val imageModel = config.imageModel.trim()
            if (imageModel.isNotEmpty()) {
                val p = config.providerOf(config.imageProvider)
                require(p != null) { AiText.t(AiMsg.CONFIG_IMAGE_PROVIDER_REQUIRED) }
                require(imageModel in p.imageChoices) { AiText.t(AiMsg.CONFIG_IMAGE_MODEL_INVALID) }
            }
        }
    }
}

interface AiTransport {
    fun cancel()
    /** DeepSeek Search API sources for [query]; [question] goes along as `ori_question`. */
    fun search(key: String, query: String, question: String = ""): List<AiWebSource>
    /** A page's Markdown through the DeepSeek Fetch API. */
    fun fetch(key: String, url: String): String
    suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit)
    /**
     * One streamed completion that may end in tool calls. Without [tools] it is a plain [stream];
     * with them, a provider that refuses tool calling throws [AiToolsUnsupported].
     */
    suspend fun complete(config: AiConfig, messages: List<AiMessage>, tail: List<JSONObject>, tools: org.json.JSONArray?, toolChoice: String, delta: suspend (String) -> Unit): AiReply {
        check(tools == null && tail.isEmpty()) { AiText.t(AiMsg.REQUEST_FAILED) }
        val text = StringBuilder()
        stream(config, messages) { text.append(it); delta(it) }
        return AiReply(text.toString())
    }
    /** A picture for [prompt] from [config]'s image model (see [AiImages]). */
    fun image(config: AiConfig, prompt: String): AiImage = throw IllegalStateException(AiText.t(AiMsg.IMAGE_MODEL_REQUIRED))
}

/** One request, no redirects/retries/logging; cancellation closes the active socket. */
class AiConnection(private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) : AiTransport {
    companion object {
        /** A failed request's message, with the provider's reason when it gave one. */
        fun httpFailure(status: Int, detail: String): String =
            if (detail.isEmpty()) AiText.t(AiMsg.REQUEST_HTTP_FAILED, status) else AiText.t(AiMsg.REQUEST_HTTP_FAILED_DETAIL, status, detail)

        /** At most 64 KiB of an error response, or "" when there is none to read. */
        private fun errorBody(conn: HttpURLConnection): String = runCatching {
            conn.errorStream?.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (bytes.size() < 65536) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 65536 - bytes.size()))
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }.getOrNull().orEmpty()
    }
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    override fun cancel() { cancelled = true; connection?.disconnect() }
    override fun search(key: String, query: String, question: String): List<AiWebSource> =
        DeepSeekSearch.sources(post(DeepSeekSearch.ENDPOINT, key, DeepSeekSearch.request(query, question), 30000, AiMsg.SEARCH_HTTP_FAILED, AiMsg.SEARCH_RESPONSE_TOO_LARGE))
    override fun fetch(key: String, url: String): String =
        DeepSeekSearch.content(post(DeepSeekSearch.FETCH_ENDPOINT, key, DeepSeekSearch.fetchRequest(url), 60000, AiMsg.FETCH_HTTP_FAILED, AiMsg.FETCH_RESPONSE_TOO_LARGE))
    /** A JSON POST to an official DeepSeek tools endpoint with the separate search key. */
    private fun post(endpoint: String, key: String, body: JSONObject, readTimeout: Int, httpFailed: AiMsg, tooLarge: AiMsg): String {
        require(key.isNotBlank() && !key.contains('\n') && !key.contains('\r')) { AiText.t(AiMsg.SEARCH_KEY_REQUIRED) }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
        val conn = openConnection(endpoint)
        connection = conn
        try {
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = readTimeout
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            check(conn.responseCode == 200) { AiText.t(httpFailed, conn.responseCode) }
            val result = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (!cancelled) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 1024 * 1024) { AiText.t(tooLarge) }
                    result.append(buffer, 0, count)
                }
            }
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            return result.toString()
        } finally { conn.disconnect(); connection = null }
    }
    override fun image(config: AiConfig, prompt: String): AiImage {
        require(config.key.isNotBlank() && !config.key.contains('\n') && !config.key.contains('\r')) { AiText.t(AiMsg.PROVIDER_NOT_CONFIGURED) }
        val bytes = AiImages.request(config.model, prompt).toString().toByteArray(Charsets.UTF_8)
        check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
        val conn = openConnection(AiImages.endpoint(config.url))
        connection = conn
        try {
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            // Painting takes a while; a picture is one long response, not a stream.
            conn.readTimeout = 240000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${config.key}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            check(status in 200..299) { httpFailure(status, AiProtocol.providerError(errorBody(conn), config.key)) }
            val result = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(64 * 1024)
                while (!cancelled) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= AiImages.MAX_RESPONSE) { AiText.t(AiMsg.IMAGE_TOO_LARGE) }
                    result.append(buffer, 0, count)
                }
            }
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            return AiImages.decode(result.toString())
        } finally { conn.disconnect(); connection = null }
    }
    /** The provider's OpenAI-style model list (GET /models); sent only when the user asks for it in settings. */
    fun listModels(url: String, key: String): List<String> {
        require(key.isNotBlank() && !key.contains('\n') && !key.contains('\r')) { AiText.t(AiMsg.CONFIG_MODEL_KEY_REQUIRED) }
        val conn = openConnection(AiProtocol.modelsEndpoint(url))
        connection = conn
        try {
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            check(conn.responseCode == 200) { AiText.t(AiMsg.MODELS_HTTP_FAILED, conn.responseCode) }
            val result = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (!cancelled) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 4 * 1024 * 1024) { AiText.t(AiMsg.MODELS_RESPONSE_TOO_LARGE) }
                    result.append(buffer, 0, count)
                }
            }
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            return AiProtocol.models(result.toString())
        } finally { conn.disconnect(); connection = null }
    }
    override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) {
        complete(config, messages, emptyList(), null, "auto", delta)
    }
    /** The request refused the thinking field it carried; nothing of the answer has arrived yet. */
    private class ThinkingRefused : Exception()

    /**
     * Models without reasoning refuse a thinking level (OpenAI's GPT-4o answers `reasoning_effort` with
     * HTTP 400), so a refusal that names the field is sent again once without it, and the model and level
     * are then sent without it for the rest of the session. The reply says so.
     */
    override suspend fun complete(config: AiConfig, messages: List<AiMessage>, tail: List<JSONObject>, tools: org.json.JSONArray?, toolChoice: String, delta: suspend (String) -> Unit): AiReply {
        if (AiThinking.refuses(config)) return send(config.copy(effort = ""), messages, tail, tools, toolChoice, delta).copy(thinkingDropped = true)
        return try {
            send(config, messages, tail, tools, toolChoice, delta)
        } catch (e: ThinkingRefused) {
            AiThinking.markRefused(config)
            send(config.copy(effort = ""), messages, tail, tools, toolChoice, delta).copy(thinkingDropped = true)
        }
    }

    private suspend fun send(config: AiConfig, messages: List<AiMessage>, tail: List<JSONObject>, tools: org.json.JSONArray?, toolChoice: String, delta: suspend (String) -> Unit): AiReply {
        val conn = openConnection(AiProtocol.endpoint(config.url))
        connection = conn
        try {
            check(!cancelled) { AiText.t(AiMsg.CONNECTION_STOPPED) }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${config.key}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            val bytes = AiProtocol.request(config, messages, tail, tools, toolChoice).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 12 * 1024 * 1024) { AiText.t(AiMsg.REQUEST_TOO_LARGE) }
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            // The provider's own reason, so a rejected request says what it was rejected for.
            val detail = if (status in 200..299) "" else AiProtocol.providerError(errorBody(conn), config.key)
            if ((status == 400 || status == 422) && AiThinking.refused(detail, config.effort, config.thinkingFormat, config.url)) throw ThinkingRefused()
            // A request rejected only because it offered tools: the caller answers without them and says so.
            if (tools != null && (status == 400 || status == 422)) throw AiToolsUnsupported(status, detail)
            check(status in 200..299) { httpFailure(status, detail) }
            val calls = AiToolCallAssembler(AiProtocol.replyBudget(config.maxTokens))
            val streamLimit = AiProtocol.streamBudget(config.maxTokens)
            val content = StringBuilder()
            var received = 0L
            var finished = false
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val line = StringBuilder()
                while (!cancelled) {
                    val ch = reader.read()
                    if (ch < 0) break
                    received++
                    check(received <= streamLimit && line.length < 65536) { AiText.t(AiMsg.ANSWER_TOO_LONG) }
                    if (ch == 10) {
                        val value = line.toString().trimEnd('\r'); line.setLength(0)
                        if (value.startsWith("data:")) {
                            val data = value.substring(5).trim()
                            if (data == "[DONE]") { finished = true; break }
                            if (data.isNotEmpty()) {
                                val json = JSONObject(data)
                                check(!json.has("error")) { AiText.t(AiMsg.PROVIDER_ERROR) }
                                calls.accept(json)
                                val text = AiProtocol.delta(data)
                                if (text.isNotEmpty()) { calls.grow(text.length); content.append(text); delta(text) }
                            }
                        }
                    } else line.append(ch.toChar())
                }
            }
            check(!cancelled && finished) { if (cancelled) AiText.t(AiMsg.CONNECTION_STOPPED) else AiText.t(AiMsg.CONNECTION_ENDED_EARLY) }
            return calls.reply(content.toString())
        } finally { conn.disconnect(); connection = null }
    }
}
