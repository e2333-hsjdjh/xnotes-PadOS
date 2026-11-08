package com.xnotes.ai

/**
 * What a model in a provider's `/models` list is for, read from its name alone, since OpenAI-style
 * lists say nothing else. A guess for sorting the fetched list: people can still type any name into
 * either list of a provider.
 */
object AiModelKinds {
    enum class Kind { CHAT, IMAGE, OTHER }

    private val IMAGE = listOf("gpt-image", "dall-e", "image", "imagen", "flux", "stable-diffusion", "sdxl", "sd3", "kolors", "cogview", "midjourney", "seedream")
    private val OTHER = listOf("embedding", "whisper", "tts", "transcribe", "moderation", "rerank", "audio", "realtime", "speech", "babbage", "davinci")

    fun kind(model: String): Kind {
        val name = model.trim().lowercase()
        return when {
            IMAGE.any { it in name } -> Kind.IMAGE
            OTHER.any { it in name } -> Kind.OTHER
            else -> Kind.CHAT
        }
    }
}
