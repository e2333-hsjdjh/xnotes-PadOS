package com.xnotes.ai

import com.xnotes.core.template.TNode
import com.xnotes.core.template.TemplateKind
import com.xnotes.format.TemplateFormatException
import com.xnotes.format.TemplateReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes an `.xtemplate` through the configured AI provider, in place of copying the guide into another
 * AI assistant. The bundled guide (templates/AI.md) is the system prompt, with a note that the reply
 * comes back through the API: one JSON block, shapes and text only, since nothing here can run code
 * or make pictures. Each reply is read as the library would read it and checked; one that cannot be
 * used goes back to the model with the reason, a few times, before the attempt fails.
 */
object AiTemplateGen {
    const val MAX_ATTEMPTS = 3
    /** Output room for a template and a reasoning model's thinking, whatever the chat's own limit. */
    const val MIN_OUTPUT_TOKENS = 16000
    const val MAX_REQUEST = 2000

    /** Where the one picture of a cover painted here sits in its package; its extension follows the image's format. */
    const val PICTURE_SRC = "images/cover.png"

    /** Overrides the guide's "What to hand back", which assumes a chat assistant that makes files. */
    private fun inApp(kind: TemplateKind, picture: Boolean) = if (picture) """

## In-app generation (this replaces "What to hand back")

You are being called by the xnotes app itself, through its API. You cannot run code or create files;
the app reads your reply directly, has an image model paint the picture you describe, and packages
the two.

- Make a cover template, with `"kind": "cover"`, built around one picture: fill the page with a
  `rect` in the cover's colour, then exactly one `image` node with `"src": "$PICTURE_SRC"`,
  `"fit": "cover"` and a `position` that keeps the subject in view on a wide page, then any frame and
  a label to write the title on. No other `image` node.
- Reply with exactly two code blocks: the complete template JSON in one ```json block, then the
  picture's description for the image model in one ```prompt block.
- The description is in English, at most 800 characters: a portrait picture about 2:3, the subject,
  style, colours and light, with calm space where the label goes, and no text, letters or frame in
  the picture itself.
- Outside the blocks write at most one short sentence saying what you chose, in the language of the
  request. Write the `name`, `description`, labels and text on the page in the language of the request.
""" else """

## In-app generation (this replaces "What to hand back")

You are being called by the xnotes app itself, through its API. You cannot run code, create files or
make pictures, and the app reads your reply directly.

- Make a ${if (kind == TemplateKind.COVER) "cover template, with `\"kind\": \"cover\"`" else "page template"}.
- Reply with the complete template JSON in exactly one ```json code block. Outside the block write at
  most one short sentence saying what you chose, in the language of the request.
- Draw it with shapes and text only: no `image` nodes and no package.
- Write the `name`, `description`, parameter labels and any text on the page in the language of the
  request.
"""

    /** The system prompt: [guide] and the in-app note. */
    fun instructions(guide: String, kind: TemplateKind, picture: Boolean = false): String = guide.trimEnd() + "\n" + inApp(kind, picture)

    /** The picture description in [reply]'s last ```prompt block, or null. */
    fun extractPrompt(reply: String): String? =
        Regex("```prompt[ \\t]*\\r?\\n(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(reply).lastOrNull()
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** The template in [reply]: its last ```json block, else its last fenced block, else its outermost braces. */
    fun extract(reply: String): String? {
        val blocks = Regex("```([A-Za-z]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(reply).toList()
        blocks.lastOrNull { it.groupValues[1].equals("json", ignoreCase = true) }?.let { return it.groupValues[2].trim() }
        blocks.lastOrNull { it.groupValues[2].trimStart().startsWith("{") }?.let { return it.groupValues[2].trim() }
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start in 0 until end) reply.substring(start, end + 1) else null
    }

    /**
     * [text] when it is a usable template of [kind]; otherwise an [IllegalArgumentException] whose
     * message tells the model what to fix. The reader drops what it cannot use without a word, so
     * the nodes it kept are counted against those written.
     */
    fun check(text: String, kind: TemplateKind, picture: Boolean = false): String {
        val template = try { TemplateReader.read(text) } catch (e: TemplateFormatException) {
            throw IllegalArgumentException("It is not a valid xtemplate: ${e.message}.")
        }
        val json = JSONObject(text)
        val written = mutableListOf<JSONObject>()
        collect(json.optJSONArray("items"), written)
        val images = written.filter { it.optString("type") == "image" }
        if (picture) require(images.size == 1 && images[0].opt("src") == PICTURE_SRC) {
            "It must have exactly one `image` node, with `\"src\": \"$PICTURE_SRC\"`, for the picture the app paints."
        } else require(images.isEmpty()) {
            "It uses an `image` node, but a template made here cannot carry a picture. Draw it with shapes and text only."
        }
        require(template.kind == kind) {
            if (kind == TemplateKind.COVER) "It must be a cover template: set `\"kind\": \"cover\"` at the top level."
            else "It must be a page template: leave out `\"kind\": \"cover\"`."
        }
        val kept = count(template.root.children)
        require(kept > 0) { "It draws nothing: `items` holds no node the format defines." }
        require(kept >= written.size) {
            "${written.size - kept} of its ${written.size} nodes cannot be used and would be dropped. Use only the node types, " +
                "properties, units, functions and names the specification defines, and check every expression."
        }
        return text
    }

    private fun collect(nodes: JSONArray?, into: MutableList<JSONObject>) {
        if (nodes == null) return
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            into += node
            collect(node.optJSONArray("children"), into)
        }
    }

    private fun count(nodes: List<TNode>): Int = nodes.sumOf { node ->
        1 + when (node) {
            is TNode.Group -> count(node.children)
            is TNode.Repeat -> count(node.children)
            else -> 0
        }
    }

    /** What the user sees while a template is made. */
    sealed interface Stage {
        /** Text arriving: [chars] so far, in [attempt] of [MAX_ATTEMPTS]. */
        data class Writing(val attempt: Int, val chars: Int) : Stage
        data class Checking(val attempt: Int) : Stage
        /** The image model painting the cover's picture. */
        data object Painting : Stage
    }

    /**
     * The routes a template is made through: the text model [text] (provider ID and model) picked on the
     * form, else [config]'s default; the image model's provider [imageProvider] and [imageModel] picked
     * there, each else the image model set in AI settings. A provider that no longer exists is never
     * swapped for another; an image route without a model name is none.
     */
    fun routes(config: AiConfig, text: Pair<String, String>?, imageProvider: String?, imageModel: String?): Pair<AiConfig, AiConfig?> {
        val writer = text?.takeIf { (p, m) -> config.providerOf(p) != null && m.isNotBlank() }?.let { (p, m) -> config.route(p, m.trim()) } ?: config
        val provider = imageProvider ?: config.imageProvider
        val model = (imageModel ?: config.imageModel).trim()
        val painter = if (config.providerOf(provider) != null && model.isNotEmpty()) config.route(provider, model) else null
        return writer to painter
    }

    /** A made template: plain [json], or [json] and the [picture] it names at [src] in its package. */
    class Generated(val json: String, val picture: AiImage? = null, val src: String = PICTURE_SRC)

    /**
     * A checked template of [kind] for [request], from [config]'s default provider and model with
     * [guide] as the system prompt. With [painter] (an image model's route, covers only) the
     * template is built around one picture that model then paints from the description the text
     * model wrote. Throws [IllegalStateException] with a user-facing reason when no usable template
     * came back.
     */
    suspend fun generate(
        config: AiConfig, client: AiTransport, guide: String, request: String, kind: TemplateKind,
        painter: AiConfig? = null, stage: suspend (Stage) -> Unit = {},
    ): Generated {
        val ask = request.trim()
        require(ask.isNotEmpty()) { AiText.t(AiMsg.TEMPLATE_GEN_EMPTY) }
        require(ask.length <= MAX_REQUEST) { AiText.t(AiMsg.TEMPLATE_GEN_TOO_LONG, MAX_REQUEST) }
        require(config.url.isNotBlank() && config.key.isNotBlank() && config.model.isNotBlank()) { AiText.t(AiMsg.PROVIDER_NOT_CONFIGURED) }
        val picture = painter != null && kind == TemplateKind.COVER
        if (picture) require(painter!!.url.isNotBlank() && painter.key.isNotBlank() && painter.model.isNotBlank()) { AiText.t(AiMsg.IMAGE_MODEL_REQUIRED) }
        val settings = config.copy(instructions = instructions(guide, kind, picture), maxTokens = maxOf(config.maxTokens, MIN_OUTPUT_TOKENS))
        val messages = mutableListOf(AiMessage("user", ask))
        var reason = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            val reply = StringBuilder()
            stage(Stage.Writing(attempt, 0))
            client.stream(settings, messages) { reply.append(it); stage(Stage.Writing(attempt, reply.length)) }
            stage(Stage.Checking(attempt))
            val text = reply.toString()
            var checked: Pair<String, String?>? = null
            reason = try {
                val json = extract(text) ?: throw IllegalArgumentException("No template JSON was found in the reply.")
                val description = if (picture) extractPrompt(text) ?: throw IllegalArgumentException("No picture description was found: give it in a ```prompt block.") else null
                checked = check(json, kind, picture) to description
                ""
            } catch (e: IllegalArgumentException) { e.message.orEmpty() }
            catch (e: org.json.JSONException) { "It is not valid JSON: ${e.message}." }
            checked?.let { (json, description) ->
                if (description == null) return Generated(json)
                // Painted once, after the template holds; a failure here is the image service's, not the model's to fix.
                stage(Stage.Painting)
                val image = client.image(painter!!, description)
                val src = PICTURE_SRC.substringBeforeLast('.') + "." + image.extension
                return Generated(if (src == PICTURE_SRC) json else json.replace("\"$PICTURE_SRC\"", "\"$src\""), image, src)
            }
            messages += AiMessage("assistant", text.ifBlank { "(empty reply)" })
            messages += AiMessage("user", "That template cannot be used. $reason Reply with the complete corrected template" +
                (if (picture) " in one ```json block and the picture's description in one ```prompt block." else " in one ```json block."))
        }
        throw IllegalStateException(AiText.t(AiMsg.TEMPLATE_GEN_FAILED, reason))
    }
}
