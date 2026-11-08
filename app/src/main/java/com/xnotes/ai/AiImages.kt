package com.xnotes.ai

import org.json.JSONObject

/** A picture an image model painted: its bytes and the file extension its format takes. */
class AiImage(val bytes: ByteArray, val extension: String)

/**
 * Pictures through an OpenAI-style Images API (`POST …/images/generations`), as OpenAI's gpt-image
 * and DALL·E models and compatible providers serve them. Only the base64 data in the reply is used;
 * a URL the provider hands back instead is never fetched.
 */
object AiImages {
    const val MAX_PROMPT = 4000
    /** Largest picture kept: a template package holds at most 16 MiB in all. */
    const val MAX_BYTES = 15 * 1024 * 1024
    /** Raw reply size read at most: the picture in base64 and its envelope. */
    const val MAX_RESPONSE = 24 * 1024 * 1024

    /** `…/images/generations` beside `…/chat/completions`, under the same validated base URL. */
    fun endpoint(url: String): String = AiProtocol.endpoint(url).removeSuffix("/chat/completions") + "/images/generations"

    /** A portrait size the model takes: gpt-image's 2:3, DALL·E 3's 4:7; DALL·E 2 paints squares only. */
    fun size(model: String): String = when {
        model.startsWith("dall-e-2") -> "1024x1024"
        model.startsWith("dall-e") -> "1024x1792"
        else -> "1024x1536"
    }

    fun request(model: String, prompt: String): JSONObject {
        val text = prompt.trim()
        require(model.isNotBlank()) { AiText.t(AiMsg.IMAGE_MODEL_REQUIRED) }
        require(text.isNotEmpty()) { AiText.t(AiMsg.IMAGE_INVALID_REPLY) }
        return JSONObject().put("model", model).put("prompt", text.take(MAX_PROMPT)).put("n", 1).put("size", size(model))
            // DALL·E answers with a link unless asked for the data; gpt-image always sends data and refuses the field.
            .also { if (model.startsWith("dall-e")) it.put("response_format", "b64_json") }
    }

    /** The picture in an Images API reply; [IllegalStateException] when there is none usable. */
    fun decode(body: String): AiImage {
        val data = runCatching { JSONObject(body).getJSONArray("data").getJSONObject(0) }.getOrNull()
        val encoded = data?.opt("b64_json") as? String ?: throw IllegalStateException(AiText.t(AiMsg.IMAGE_INVALID_REPLY))
        val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: throw IllegalStateException(AiText.t(AiMsg.IMAGE_INVALID_REPLY))
        check(bytes.size <= MAX_BYTES) { AiText.t(AiMsg.IMAGE_TOO_LARGE) }
        val ext = extension(bytes) ?: throw IllegalStateException(AiText.t(AiMsg.IMAGE_INVALID_REPLY))
        return AiImage(bytes, ext)
    }

    /** "png", "jpg" or "webp" from the file's signature; null for anything else. */
    fun extension(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }
}
