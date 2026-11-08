package com.xnotes.ai

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Providers list chat and image models apart; the new-note form picks from them without touching the defaults. */
class AiProviderModelsTest {
    private val openai = AiProvider("p1", "OpenAI", "https://api.openai.com/v1", "sk-open", listOf("astra", "gpt-4o"), imageModels = listOf("gpt-image-1", "dall-e-3"))
    private val deepseek = AiProvider("p2", "DeepSeek", "https://api.deepseek.com", "ds-key", listOf("deepseek-chat"))
    private val config = AiConfig(providers = listOf(openai, deepseek), provider = "p2", imageProvider = "p1", imageModel = "gpt-image-1").route("p2", "deepseek-chat")

    @Test fun fetchedNamesSortByWhatTheyAreFor() {
        for (chat in listOf("gpt-4o", "astra", "deepseek-chat", "o3-mini", "qwen-vl-max", "gpt-4o-search-preview", "claude-sonnet")) assertEquals(chat, AiModelKinds.Kind.CHAT, AiModelKinds.kind(chat))
        for (image in listOf("gpt-image-1", "dall-e-3", "imagen-4", "black-forest-labs/FLUX.1-dev", "stabilityai/stable-diffusion-3", "Kwai-Kolors/Kolors", "cogview-4")) assertEquals(image, AiModelKinds.Kind.IMAGE, AiModelKinds.kind(image))
        for (other in listOf("text-embedding-3-small", "whisper-1", "tts-1-hd", "gpt-4o-transcribe", "omni-moderation-latest", "gpt-4o-realtime-preview", "babbage-002")) assertEquals(other, AiModelKinds.Kind.OTHER, AiModelKinds.kind(other))
    }

    @Test fun imageModelsAreStoredWithTheirProvider() {
        val stored = AiConfigStore.decode(AiConfigStore.encode(config), "")
        assertEquals(listOf("gpt-image-1", "dall-e-3"), stored.providerOf("p1")!!.imageChoices)
        assertEquals(listOf("astra", "gpt-4o"), stored.providerOf("p1")!!.modelChoices)
        assertEquals("gpt-image-1", stored.imageRoute()!!.model)
        AiConfigStore.validate(stored)
    }

    @Test fun anImageModelSavedBeforeProvidersListedThemJoinsItsProvider() {
        val old = AiConfigStore.encode(config)
        old.getJSONArray("providers").getJSONObject(0).remove("imageModels")
        old.put("imageModel", "gpt-image-1.5")
        val stored = AiConfigStore.decode(old, "")
        assertEquals(listOf("gpt-image-1.5"), stored.providerOf("p1")!!.imageChoices)
        assertEquals("gpt-image-1.5", stored.imageRoute()!!.model)
        AiConfigStore.validate(stored)
    }

    @Test fun theDefaultImageModelMustBeOneTheProviderLists() {
        assertThrows(IllegalArgumentException::class.java) { AiConfigStore.validate(config.copy(imageModel = "gpt-image-9")) }
        // Dropping the model from the provider drops the default with it, never to another provider.
        val edited = config.copy(providers = listOf(openai.copy(imageModels = listOf("dall-e-3")), deepseek)).withValidDefault()
        assertEquals("", edited.imageProvider); assertNull(edited.imageRoute())
    }

    @Test fun aProviderMayServePicturesOnly() {
        val painter = AiProvider("p3", "Images", "https://images.example.com/v1", "img-key", imageModels = listOf("flux-dev"))
        AiConfigStore.validateProvider(painter)
        assertThrows(IllegalArgumentException::class.java) { AiConfigStore.validateProvider(painter.copy(imageModels = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { AiConfigStore.validateProvider(painter.copy(imageModels = List(AiProtocol.MAX_MODELS + 1) { "m$it" })) }
        // It never becomes the default chat provider by being first.
        val setup = AiConfig(providers = listOf(painter, deepseek)).withValidDefault()
        assertEquals("p2", setup.provider); assertEquals("deepseek-chat", setup.model)
        AiConfigStore.validate(setup.copy(imageProvider = "p3", imageModel = "flux-dev"))
        assertTrue(JSONArray(AiConfigStore.encode(setup).getJSONArray("providers").getJSONObject(0).getJSONArray("imageModels").toString()).length() == 1)
    }

    @Test fun theFormPicksModelsWithoutChangingTheDefaults() {
        val (writer, painter) = AiTemplateGen.routes(config, null, null, null)
        assertEquals("deepseek-chat", writer.model); assertEquals("ds-key", writer.key)
        assertEquals("gpt-image-1", painter!!.model); assertEquals("sk-open", painter.key)
        val (picked, paints) = AiTemplateGen.routes(config, "p1" to "astra", "p1", "dall-e-3")
        assertEquals("astra", picked.model); assertEquals("https://api.openai.com/v1", picked.url); assertEquals("sk-open", picked.key)
        assertEquals("dall-e-3", paints!!.model)
        // A pick whose provider is gone falls back to the default, never to another provider.
        assertEquals("deepseek-chat", AiTemplateGen.routes(config, "gone" to "x", null, null).first.model)
        assertNull(AiTemplateGen.routes(config.copy(imageProvider = "", imageModel = ""), null, null, null).second)
        assertEquals("deepseek-chat", config.model); assertEquals("gpt-image-1", config.imageModel)
    }
}
