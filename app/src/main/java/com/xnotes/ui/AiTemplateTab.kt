package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Stable
import com.xnotes.ai.AiSessions
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.xnotes.ai.AiConfig
import com.xnotes.ai.AiConfigStore
import com.xnotes.ai.AiConnection
import com.xnotes.ai.AiMsg
import com.xnotes.ai.AiTemplateGen
import com.xnotes.ai.AiText
import com.xnotes.core.template.TemplateKind
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

/** The xtemplate repo's AI.md, bundled byte for byte: the brief, the spec and examples in one text. */
private const val GUIDE = "templates/AI.md"

private val PAGE_PROMPTS = listOf(R.string.ai_prompt_page_dots, R.string.ai_prompt_page_habits, R.string.ai_prompt_page_lab, R.string.ai_prompt_page_reading)
private val COVER_PROMPTS = listOf(R.string.ai_prompt_cover_photo, R.string.ai_prompt_cover_painting, R.string.ai_prompt_cover_pattern, R.string.ai_prompt_cover_leather)

private val PAGE_GEN_PROMPTS = PAGE_PROMPTS
private val COVER_GEN_PROMPTS = listOf(R.string.ai_prompt_cover_frame, R.string.ai_prompt_cover_stripes)
private val PICTURE_GEN_PROMPTS = listOf(R.string.ai_prompt_cover_paint_pines, R.string.ai_prompt_cover_paint_leather, R.string.ai_prompt_cover_paint_circles)

/**
 * New note's Generate using AI tab. First, generating here through the configured AI provider
 * ([AiTemplateGen]), with a picture painted by the image model for covers when one is set up; [onGenerated] gets the checked template to add to the library. Below,
 * for covers with pictures, the three steps for another AI assistant: copy the guide, ask it for a
 * template (with prompts to try), and import the `.xtemplate` file it made. [coversOnly] keeps to
 * covers, for the cover picker of an open note; [wide] sets the example prompts in two columns.
 */
/**
 * What the Generate here form holds and the request it runs. Kept by the new-note screen rather than
 * the tab, so looking at the pages or covers while a template (and its picture) is made neither
 * stops it nor loses the request; only closing the screen does ([rememberTemplateGen]).
 */
@Stable
internal class TemplateGenState(val scope: CoroutineScope, coversOnly: Boolean) {
    var kind by mutableStateOf(if (coversOnly) TemplateKind.COVER else TemplateKind.PAGE)
    var request by mutableStateOf("")
    /** Whether a cover gets a painted picture; null follows whether AI settings set up an image model. */
    var paint by mutableStateOf<Boolean?>(null)
    /**
     * The models picked on the form for this screen, apart from the defaults in AI settings: the text
     * model as provider ID and model, the image model's provider and name. Null follows the default.
     */
    var textModel by mutableStateOf<Pair<String, String>?>(null)
    var imageProvider by mutableStateOf<String?>(null)
    var imageModel by mutableStateOf<String?>(null)
    var stage by mutableStateOf<AiTemplateGen.Stage?>(null)
    var error by mutableStateOf<String?>(null)
    var job by mutableStateOf<Job?>(null)
    var client by mutableStateOf<AiConnection?>(null)
    val running: Boolean get() = job != null
    fun stop() { client?.cancel(); job?.cancel() }
}

/** The new-note screen's [TemplateGenState]; a request still running when the screen closes is stopped. */
@Composable
internal fun rememberTemplateGen(coversOnly: Boolean): TemplateGenState {
    val scope = rememberCoroutineScope()
    val state = remember { TemplateGenState(scope, coversOnly) }
    DisposableEffect(state) { onDispose { state.stop() } }
    return state
}

@Composable
internal fun AiTemplateTab(
    coversOnly: Boolean, wide: Boolean, onImport: () -> Unit, onGenerated: (AiTemplateGen.Generated) -> Unit,
    gen: TemplateGenState, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read again whenever AI settings are saved, so setting a provider up from here shows at once.
    val revision = AiSessions.get(context).configRevision
    val config by produceState<AiConfig?>(null, revision) {
        value = withContext(Dispatchers.IO) { runCatching { AiConfigStore(context).load() }.getOrNull() ?: AiConfig() }
    }
    val ready = config?.let { it.url.isNotBlank() && it.key.isNotBlank() && it.model.isNotBlank() } == true
    // Without a provider the other assistant is the way to go, so it starts open.
    var external by remember(ready) { mutableStateOf(!ready) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s32)) {
        GenerateHere(config, ready, coversOnly, wide, onGenerated, gen)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            val palette = LocalPalette.current
            Row(
                Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button) { external = !external }.padding(vertical = Space.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s12),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(stringResource(R.string.ai_tpl_external), color = palette.text.toComposeColor(), fontSize = TextSize.title, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.ai_tpl_external_about), color = palette.textDim.toComposeColor(), fontSize = TextSize.label, lineHeight = 17.sp)
                }
                Icon(if (external) XnotesIcons.chevronUp else XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
            }
            if (external) ExternalSteps(coversOnly, wide, onImport)
        }
    }
}

/** Generating through the configured provider: what to make, the request, and progress or the error. */
@Composable
private fun GenerateHere(config: AiConfig?, ready: Boolean, coversOnly: Boolean, wide: Boolean, onGenerated: (AiTemplateGen.Generated) -> Unit, gen: TemplateGenState) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    var settings by remember { mutableStateOf(false) }
    if (settings) AiSettings { settings = false }
    // The models this form sends to: picked here, else the defaults in AI settings.
    val routes = config?.let { AiTemplateGen.routes(it, gen.textModel, gen.imageProvider, gen.imageModel) }
    val writer = routes?.first
    val painter = routes?.second
    val running = gen.running
    var kind by gen::kind
    var request by gen::request
    // A cover is painted when asked to, or by default when AI settings set up an image model.
    val paint = gen.paint ?: (config?.imageRoute() != null)
    val picture = kind == TemplateKind.COVER && paint

    fun generate() {
        val c = writer ?: return
        val made = kind
        val ask = request
        val picture = painter.takeIf { made == TemplateKind.COVER && paint }
        val connection = AiConnection()
        gen.client = connection; gen.error = null
        gen.job = gen.scope.launch {
            try {
                val guide = withContext(Dispatchers.IO) { context.assets.open(GUIDE).use { String(it.readBytes(), Charsets.UTF_8) } }
                val template = withContext(Dispatchers.IO) {
                    AiTemplateGen.generate(c, connection, guide, ask, made, picture) { s -> withContext(Dispatchers.Main) { gen.stage = s } }
                }
                onGenerated(template)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A stop the person asked for is not an error.
                if (gen.job?.isCancelled != true) gen.error = if (e is IllegalArgumentException || e is IllegalStateException) e.message else AiText.t(AiMsg.NETWORK_ERROR)
            } finally {
                gen.stage = null; gen.job = null; gen.client = null
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(palette.menuBg.toComposeColor()).padding(Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Text(stringResource(R.string.ai_tpl_gen_title), color = text, fontSize = TextSize.title, fontWeight = FontWeight.Medium)
        if (config != null && !ready) {
            AiBanner(stringResource(R.string.ai_tpl_gen_no_provider), stringResource(R.string.ai_tpl_gen_open_settings)) { settings = true }
            return@Column
        }
        val c = config ?: return@Column
        val w = writer ?: return@Column
        Text(
            stringResource(R.string.ai_tpl_gen_about, routeLabel(w), w.model),
            color = dim, fontSize = TextSize.label, lineHeight = 17.sp,
        )
        // The text model for this screen; the defaults in AI settings stay as they are.
        ModelPicker(
            stringResource(R.string.ai_tpl_gen_text_model),
            listOfNotNull(routeLabel(w).takeIf { c.providers.size > 1 }, w.model).joinToString(" · "),
            enabled = !running,
        ) { close ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_tpl_gen_default, listOfNotNull(c.providerOf(c.provider)?.label?.takeIf { c.providers.size > 1 }, c.model).joinToString(" · "))) },
                onClick = { gen.textModel = null; close() },
                trailingIcon = { if (gen.textModel == null) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) },
            )
            c.providers.filter { it.modelChoices.isNotEmpty() }.forEach { p ->
                Text(p.label, Modifier.padding(horizontal = Space.s16, vertical = Space.s4), color = dim, fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.modelChoices.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { gen.textModel = p.id to name; close() },
                        trailingIcon = { if (gen.textModel == (p.id to name)) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) },
                    )
                }
            }
        }
        if (!coversOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                for ((k, label) in listOf(TemplateKind.PAGE to R.string.ai_page_templates, TemplateKind.COVER to R.string.ai_cover_templates)) {
                    val on = kind == k
                    Box(
                        Modifier.heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
                            .background(if (on) palette.selectionBackground.toComposeColor() else palette.bg.toComposeColor())
                            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                            .clickable(enabled = !running, role = Role.RadioButton) { kind = k }
                            .padding(horizontal = Space.s16),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(label), color = if (on) palette.selectionForeground.toComposeColor() else text, fontSize = TextSize.body, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        OutlinedTextField(
            request, { request = it.take(AiTemplateGen.MAX_REQUEST); gen.error = null }, Modifier.fillMaxWidth(),
            enabled = !running, minLines = 3, maxLines = 8,
            placeholder = { Text(stringResource(R.string.ai_tpl_gen_hint), fontSize = TextSize.body) },
        )
        if (kind == TemplateKind.COVER) {
            AiSwitchRow(
                stringResource(R.string.ai_tpl_gen_picture_title),
                if (painter != null) stringResource(R.string.ai_tpl_gen_picture_note, routeLabel(painter), painter.model) else stringResource(R.string.ai_tpl_gen_cover_note),
                paint, enabled = !running,
            ) { gen.paint = it }
            if (paint) {
                // The image model for this screen, from the image models the providers list.
                val painters = c.providers.filter { it.imageChoices.isNotEmpty() }
                val picked = gen.imageProvider != null && gen.imageModel != null
                if (painters.isNotEmpty()) ModelPicker(
                    stringResource(R.string.ai_tpl_gen_image_model),
                    painter?.let { listOfNotNull(routeLabel(it).takeIf { c.providers.size > 1 }, it.model).joinToString(" · ") }.orEmpty(),
                    enabled = !running,
                ) { close ->
                    c.imageRoute()?.let { default ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_tpl_gen_default, listOfNotNull(routeLabel(default).takeIf { c.providers.size > 1 }, default.model).joinToString(" · "))) },
                            onClick = { gen.imageProvider = null; gen.imageModel = null; close() },
                            trailingIcon = { if (!picked) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) },
                        )
                    }
                    painters.forEach { p ->
                        Text(p.label, Modifier.padding(horizontal = Space.s16, vertical = Space.s4), color = dim, fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        p.imageChoices.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { gen.imageProvider = p.id; gen.imageModel = name; close() },
                                trailingIcon = { if (picked && gen.imageProvider == p.id && gen.imageModel == name) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                if (painter == null) {
                    Text(stringResource(R.string.ai_tpl_gen_image_model_needed), color = dim, fontSize = TextSize.label, lineHeight = 17.sp)
                    TextButton(onClick = { settings = true }, enabled = !running, contentPadding = PaddingValues(horizontal = Space.s4)) {
                        Text(stringResource(R.string.ai_tpl_gen_open_settings), fontSize = TextSize.body)
                    }
                }
            }
        }
        Text(stringResource(R.string.ai_tpl_gen_examples), color = dim, fontSize = TextSize.label)
        val examples = when {
            picture -> PICTURE_GEN_PROMPTS
            kind == TemplateKind.COVER -> COVER_GEN_PROMPTS
            else -> PAGE_GEN_PROMPTS
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            for (row in examples.chunked(if (wide) 2 else 1)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    for (id in row) {
                        val prompt = stringResource(id)
                        Text(
                            prompt, color = text, fontSize = TextSize.body, lineHeight = 19.sp,
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
                                .background(palette.bg.toComposeColor())
                                .clickable(enabled = !running, role = Role.Button) { request = prompt }
                                .padding(horizontal = Space.s12, vertical = Space.s8),
                        )
                    }
                    if (row.size < (if (wide) 2 else 1)) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            FilledTonalButton(
                onClick = { if (running) gen.stop() else generate() },
                enabled = running || (request.isNotBlank() && !(picture && painter == null)),
                modifier = Modifier.heightIn(min = MinTouchTarget),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = palette.selectionBackground.toComposeColor(),
                    contentColor = palette.selectionForeground.toComposeColor(),
                ),
            ) { Text(stringResource(if (running) R.string.ai_stop else R.string.ai_tpl_gen_button), fontWeight = FontWeight.Medium) }
            val status = when (val s = gen.stage) {
                is AiTemplateGen.Stage.Writing -> if (s.attempt == 1) stringResource(R.string.ai_tpl_gen_writing, s.chars)
                    else stringResource(R.string.ai_tpl_gen_fixing, s.attempt, AiTemplateGen.MAX_ATTEMPTS, s.chars)
                is AiTemplateGen.Stage.Checking -> stringResource(R.string.ai_tpl_gen_checking)
                AiTemplateGen.Stage.Painting -> stringResource(R.string.ai_tpl_gen_painting)
                null -> null
            }
            if (status != null) Text(status, color = dim, fontSize = TextSize.label, modifier = Modifier.weight(1f))
        }
        gen.error?.let { AiBanner(it) }
    }
}

/** The three steps for another AI assistant: copy the guide, ask it (with prompts to try), import its file. */
@Composable
private fun ExternalSteps(coversOnly: Boolean, wide: Boolean, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val accent = palette.accent.toComposeColor()
    val card = palette.menuBg.toComposeColor()
    val guide by produceState<ByteArray?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { context.assets.open(GUIDE).use { it.readBytes() } }.getOrNull() }
    }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    // What went to the clipboard last, so its button says so for a moment.
    var copied by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2000)
            copied = null
        }
    }
    fun copy(what: Any, s: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("xtemplate", s))
        copied = what
    }

    @Composable
    fun Step(n: Int, title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                Box(Modifier.size(26.dp).background(palette.selectionBackground.toComposeColor(), CircleShape), contentAlignment = Alignment.Center) {
                    Text(n.toString(), color = palette.selectionForeground.toComposeColor(), fontSize = TextSize.body, fontWeight = FontWeight.Medium)
                }
                Text(title, color = text, fontSize = TextSize.title, fontWeight = FontWeight.Medium)
            }
            // The step's body lines up under its title, past the number.
            Column(Modifier.padding(start = Space.s40), verticalArrangement = Arrangement.spacedBy(Space.s12)) { content() }
        }
    }

    @Composable
    fun Prompts(ids: List<Int>) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            for (row in ids.chunked(if (wide) 2 else 1)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    for (id in row) Prompt(id, copied == id, Modifier.weight(1f).fillMaxHeight()) { copy(id, it) }
                    if (row.size < (if (wide) 2 else 1)) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s32)) {
        Text(stringResource(R.string.ai_intro), color = dim, fontSize = TextSize.bodyLarge, lineHeight = 20.sp)

        Step(1, stringResource(R.string.ai_step_copy)) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(card).padding(start = Space.s16, top = Space.s16, end = Space.s12, bottom = Space.s16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s16),
            ) {
                Icon(XnotesIcons.file, null, tint = accent, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        Text(stringResource(R.string.ai_guide_name), color = text, fontSize = TextSize.bodyLarge, fontWeight = FontWeight.Medium)
                        guide?.let { Text(stringResource(R.string.ai_guide_size, (it.size + 512) / 1024), color = dim, fontSize = TextSize.label) }
                    }
                    Text(stringResource(R.string.ai_guide_about), color = dim, fontSize = TextSize.label, lineHeight = 17.sp)
                }
                val shareLabel = stringResource(R.string.share)
                IconButton(
                    onClick = {
                        val g = guide ?: return@IconButton
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, String(g, Charsets.UTF_8))
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    },
                    enabled = guide != null,
                ) { Icon(XnotesIcons.share, shareLabel, tint = dim) }
                val done = copied == GUIDE
                FilledTonalButton(
                    onClick = { guide?.let { copy(GUIDE, String(it, Charsets.UTF_8)) } },
                    enabled = guide != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = palette.selectionBackground.toComposeColor(),
                        contentColor = palette.selectionForeground.toComposeColor(),
                    ),
                ) {
                    Icon(if (done) XnotesIcons.check else XnotesIcons.copy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Space.s8))
                    Text(stringResource(if (done) R.string.copied else R.string.copy), fontWeight = FontWeight.Medium)
                }
            }
        }

        Step(2, stringResource(R.string.ai_step_ask)) {
            Text(stringResource(R.string.ai_step_ask_about), color = dim, fontSize = TextSize.body, lineHeight = 19.sp)
            // A heading of their own, so the prompts read as requests to borrow, not text every request needs.
            Column(Modifier.padding(top = Space.s12), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(stringResource(R.string.ai_examples), color = text, fontSize = TextSize.bodyLarge, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.ai_examples_about), color = dim, fontSize = TextSize.label, lineHeight = 17.sp)
            }
            if (!coversOnly) {
                Text(stringResource(R.string.ai_page_templates), color = dim, fontSize = TextSize.body, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = Space.s4))
                Prompts(PAGE_PROMPTS)
            }
            Text(stringResource(R.string.ai_cover_templates), color = dim, fontSize = TextSize.body, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = Space.s4))
            Prompts(COVER_PROMPTS)
        }

        Step(3, stringResource(R.string.ai_step_import)) {
            Action(XnotesIcons.importDoc, stringResource(R.string.import_template_file), stringResource(R.string.import_template_about), onImport)
        }
    }
}

/** An example request: its text, and a copy mark that turns into a tick once it is on the clipboard. */
@Composable
private fun Prompt(@StringRes id: Int, copied: Boolean, modifier: Modifier, onCopy: (String) -> Unit) {
    val palette = LocalPalette.current
    val prompt = stringResource(id)
    Row(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .clickable(onClickLabel = stringResource(R.string.copy), role = Role.Button) { onCopy(prompt) }
            .padding(start = Space.s16, top = Space.s12, end = Space.s12, bottom = Space.s12),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Text(prompt, color = palette.text.toComposeColor(), fontSize = TextSize.body, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        Icon(
            if (copied) XnotesIcons.check else XnotesIcons.copy, null,
            tint = (if (copied) palette.accent else palette.textDim).toComposeColor(), modifier = Modifier.size(18.dp),
        )
    }
}

/** A step's action: its icon, what it does, and a line about it. */
@Composable
private fun Action(icon: ImageVector, title: String, about: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.s16, vertical = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s16),
    ) {
        Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, fontWeight = FontWeight.Medium)
            Text(about, color = palette.textDim.toComposeColor(), fontSize = TextSize.label, lineHeight = 17.sp)
        }
    }
}

/** A route's provider as people know it: its name, else its endpoint's host. */
private fun routeLabel(route: AiConfig): String =
    route.providerOf(route.provider)?.label ?: runCatching { java.net.URI(route.url).host }.getOrNull().orEmpty()

/** A labelled row showing the current [choice] that opens a menu of [items]; picking one calls the given close. */
@Composable
private fun ModelPicker(title: String, choice: String, enabled: Boolean, items: @Composable (close: () -> Unit) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }.padding(horizontal = Space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            Text(title, Modifier.weight(1f), color = palette.text.toComposeColor(), fontSize = TextSize.body)
            Text(choice, Modifier.weight(1f, fill = false), color = palette.text.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(XnotesIcons.chevronDown, null, Modifier.size(16.dp), tint = palette.textDim.toComposeColor())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) { items { open = false } }
    }
}
