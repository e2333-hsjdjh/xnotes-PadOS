package com.xnotes.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.xnotes.R
import com.xnotes.ai.*
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.toComposeColor
import java.io.File
import kotlinx.coroutines.*

// --- shared pieces -------------------------------------------------------------------------

// Turn states are stored in the history files as these keys; only their display is translated.
private const val STATE_RUNNING = "生成中"
private const val STATE_DONE = "已完成"
private val RETRYABLE_STATES = listOf("已停止", "已中断", "生成失败")

@Composable
private fun stateLabel(state: String): String = when (state) {
    STATE_RUNNING -> stringResource(R.string.ai_state_running)
    STATE_DONE -> stringResource(R.string.ai_state_done)
    "已停止" -> stringResource(R.string.ai_state_stopped)
    "已中断" -> stringResource(R.string.ai_state_interrupted)
    "生成失败" -> stringResource(R.string.ai_state_failed)
    else -> state
}

/** The stored default title is the Chinese "新会话"; show it in the UI language. */
@Composable
internal fun AiChat.displayTitle(): String = if (title == AiChat().title) stringResource(R.string.ai_new_chat) else title

/** A 48 dp icon action with a tooltip; the AI chrome has no bare text buttons. */
@Composable
private fun AiIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(MinTouchTarget)) {
            Icon(icon, label, tint = if (enabled) palette.text.toComposeColor() else palette.disabled.toComposeColor(), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AiSpinner(color: Color = LocalPalette.current.textDim.toComposeColor()) {
    CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
}

/** A full-width notice on a grey fill: storage failures, source errors, the deleted-chat undo. */
@Composable
internal fun AiBanner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.fillMaxWidth().clip(shape).background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape).padding(start = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = Space.s12), color = palette.text.toComposeColor(), fontSize = TextSize.body)
        if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = TextSize.body) }
    }
}

/** The relative age of a chat ("5 minutes ago"), quieter than a full timestamp in a list. */
private fun relativeAge(time: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

// --- settings ------------------------------------------------------------------------------

/** The Preferences entry: what is configured now, and the dialog to change it. */
@Composable
internal fun AiSettingsRow() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val summary = remember(show) {
        runCatching { AiConfigStore(context).load() }.getOrNull()?.let { c ->
            if (c.providers.isEmpty()) context.getString(R.string.ai_settings_not_set)
            else listOfNotNull(c.providerOf(c.provider)?.label?.takeIf { c.providers.size > 1 }, c.model.ifBlank { context.getString(R.string.ai_settings_no_model) },
                c.effort.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.ai_thinking_summary, context.getString(effortRes(it))) },
                context.getString(if (c.searchKey.isBlank()) R.string.ai_settings_web_off else R.string.ai_settings_web_on)).joinToString(" · ")
        } ?: context.getString(R.string.ai_settings_unreadable_short)
    }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).border(1.dp, palette.border.toComposeColor(), shape)
            .clickable { show = true }.padding(horizontal = Space.s16, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Icon(XnotesIcons.ai, null, tint = palette.text.toComposeColor(), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_settings_row), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
            Text(summary, color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
        }
        Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
    }
    if (show) AiSettings { show = false }
}

/** The user-facing name of a thinking level; the stored keys are protocol words. */
private fun effortRes(level: String): Int = when (level) {
    AiThinking.OFF -> R.string.ai_effort_off
    "low" -> R.string.ai_effort_low
    "medium" -> R.string.ai_effort_medium
    "high" -> R.string.ai_effort_high
    else -> R.string.ai_effort_default
}

private fun formatRes(format: String): Int = when (format) {
    AiThinking.OPENAI -> R.string.ai_format_openai
    AiThinking.OPENROUTER -> R.string.ai_format_openrouter
    AiThinking.DEEPSEEK -> R.string.ai_format_deepseek
    AiThinking.QWEN -> R.string.ai_format_qwen
    else -> R.string.ai_format_auto
}

/** A titled group of settings rows on one outlined card, rows split by hairlines. */
@Composable
private fun SettingsGroup(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(title, Modifier.padding(start = Space.s4), color = palette.textDim.toComposeColor(), fontSize = TextSize.label, fontWeight = FontWeight.Medium)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, palette.border.toComposeColor(), shape).padding(Space.s12),
            verticalArrangement = Arrangement.spacedBy(Space.s12), content = content)
        if (note != null) Text(note, Modifier.padding(horizontal = Space.s4), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
    }
}

/** A row label with an optional quieter line under it. */
@Composable
private fun SettingsLabel(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        Text(title, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
        if (detail != null) Text(detail, color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
    }
}

/** One-of-several choice as a pill row; the chosen segment is inverted. Each segment is a 48 dp target. */
@Composable
private fun AiSegmented(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val palette = LocalPalette.current
    Row(modifier.height(MinTouchTarget).clip(CircleShape).border(1.dp, palette.border.toComposeColor(), CircleShape).padding(Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                .then(if (on) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier)
                .clickable(role = Role.RadioButton, onClickLabel = label) { onSelect(value) },
                contentAlignment = Alignment.Center) {
                Text(label, color = (if (on) palette.selectionForeground else palette.text).toComposeColor(), fontSize = TextSize.body, maxLines = 1)
            }
        }
    }
}

/** A setting that is on or off: label and note on the left, the switch on the right; the whole row toggles. */
@Composable
internal fun AiSwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(horizontal = Space.s4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
        SettingsLabel(title, detail, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun effortOptions(): List<Pair<String, String>> = AiThinking.LEVELS.map { it to stringResource(effortRes(it)) }

/**
 * The provider's model list, searchable and sorted by what each model is for ([AiModelKinds]): ticking
 * a chat model adds it to the chat list, an image model to the image list. Models that may not chat
 * (speech, embeddings, moderation) sit folded at the end and tick into the chat list if wanted.
 */
@Composable
private fun AiModelPicker(available: List<String>, chat: List<String>, images: List<String>, onToggle: (String, Boolean) -> Boolean, close: () -> Unit) {
    val palette = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    var others by remember { mutableStateOf(false) }
    val shown = available.filter { it.contains(query.trim(), ignoreCase = true) }
    val byKind = shown.groupBy(AiModelKinds::kind)
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.ai_models_fetched_title), fontSize = TextSize.heading) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(XnotesIcons.search, null, Modifier.size(18.dp)) }, placeholder = { Text(stringResource(R.string.ai_models_filter)) })
            Text(pluralStringResource(R.plurals.ai_models_count, shown.size, shown.size) + " · " +
                stringResource(R.string.ai_models_saved_count, chat.size, AiProtocol.MAX_MODELS) + " · " + stringResource(R.string.ai_provider_image_count, images.size),
                color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
            // Ticking past the limit is refused here, not trimmed on save.
            if (full) Text(AiText.t(AiMsg.CONFIG_TOO_MANY_MODELS, AiProtocol.MAX_MODELS), color = palette.text.toComposeColor(), fontSize = TextSize.label)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                fun section(kind: AiModelKinds.Kind, title: Int, image: Boolean, folded: Boolean = false) {
                    val list = byKind[kind].orEmpty()
                    if (list.isEmpty()) return
                    item(key = "h$kind") {
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).then(if (kind == AiModelKinds.Kind.OTHER) Modifier.clickable { others = !others } else Modifier)
                            .padding(horizontal = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(title) + " · ${list.size}", Modifier.weight(1f), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                            if (kind == AiModelKinds.Kind.OTHER) Icon(if (others) XnotesIcons.chevronUp else XnotesIcons.chevronDown, null, Modifier.size(16.dp), tint = palette.textDim.toComposeColor())
                        }
                    }
                    if (folded) return
                    items(list.size, key = { "$kind/${list[it]}" }) { index ->
                        val id = list[index]
                        val on = id in (if (image) images else chat)
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).toggleable(on, role = Role.Checkbox) { full = !onToggle(id, image) }.padding(horizontal = Space.s4),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                            Box(Modifier.size(20.dp).clip(MaterialTheme.shapes.extraSmall)
                                .then(if (on) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier.border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)),
                                contentAlignment = Alignment.Center) {
                                if (on) Icon(XnotesIcons.check, null, Modifier.size(14.dp), tint = palette.selectionForeground.toComposeColor())
                            }
                            Text(id, Modifier.weight(1f), color = palette.text.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                section(AiModelKinds.Kind.CHAT, R.string.ai_settings_models, image = false)
                section(AiModelKinds.Kind.IMAGE, R.string.ai_models_image, image = true)
                section(AiModelKinds.Kind.OTHER, R.string.ai_models_other, image = false, folded = !others)
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.ai_ok)) } })
}

/** Well-known OpenAI-compatible endpoints a new provider can start from; the user still adds key and models. */
private val PROVIDER_PRESETS = listOf(
    R.string.ai_preset_deepseek to "https://api.deepseek.com/v1",
    R.string.ai_preset_openai to "https://api.openai.com/v1",
    R.string.ai_preset_openrouter to "https://openrouter.ai/api/v1",
    R.string.ai_preset_qwen to "https://dashscope.aliyuncs.com/compatible-mode/v1",
    R.string.ai_preset_moonshot to "https://api.moonshot.cn/v1",
    R.string.ai_preset_zhipu to "https://open.bigmodel.cn/api/paas/v4",
    R.string.ai_preset_siliconflow to "https://api.siliconflow.cn/v1",
    R.string.ai_preset_gemini to "https://generativelanguage.googleapis.com/v1beta/openai",
)

/**
 * The whole AI setup on one sheet, grouped as DSH groups it: model providers, new-chat defaults,
 * thinking, personalisation, web search. Each provider is edited on its own sheet. Nothing is sent
 * anywhere except an explicit model-list fetch, and nothing is stored until Save.
 */
@Composable
internal fun AiSettings(close: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val store = remember { AiConfigStore(context) }
    var error by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(runCatching { store.load() }.getOrElse { error = context.getString(R.string.ai_settings_unreadable); AiConfig() }) }
    var maxTokens by remember { mutableStateOf(config.maxTokens.toString()) }
    // The provider on the editor sheet, and whether it is new (not yet in the list).
    var editing by remember { mutableStateOf<Pair<AiProvider, Boolean>?>(null) }
    var defaultModelMenu by remember { mutableStateOf(false) }
    var imageProviderMenu by remember { mutableStateOf(false) }
    var promptMenu by remember { mutableStateOf(false) }
    val localDefaultPrompt = AiDefaults.instructions(context.resources.configuration.locales[0].toLanguageTag())
    val many = config.providers.size > 1
    fun save() {
        val tokens = maxTokens.trim().toIntOrNull()
        if (tokens == null || tokens !in AiProtocol.MIN_MAX_TOKENS..AiProtocol.MAX_MAX_TOKENS) {
            error = AiText.t(AiMsg.CONFIG_MAX_TOKENS_INVALID, AiProtocol.MIN_MAX_TOKENS, AiProtocol.MAX_MAX_TOKENS); return
        }
        val next = config.copy(maxTokens = tokens)
        runCatching { store.save(next) }.onSuccess { AiSessions.get(context).configRevision++; close() }.onFailure {
            error = if (it is IllegalArgumentException) it.message ?: context.getString(R.string.ai_settings_invalid) else context.getString(R.string.ai_settings_save_failed)
        }
    }
    editing?.let { (provider, isNew) ->
        AiProviderEditor(provider, isNew, close = { editing = null }, onDelete = {
            config = config.copy(providers = config.providers.filterNot { it.id == provider.id }).withValidDefault(); editing = null
        }) { edited ->
            val list = if (isNew) config.providers + edited else config.providers.map { if (it.id == edited.id) edited else it }
            config = config.copy(providers = list).withValidDefault(); editing = null
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = MaterialTheme.shapes.large
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(0.92f).fillMaxHeight(0.9f).clip(shape)
            .background(palette.menuBg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape).imePadding()) {
            Row(Modifier.fillMaxWidth().height(MinTouchTarget + Space.s8).padding(horizontal = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                AiIconButton(XnotesIcons.close, stringResource(R.string.cancel), onClick = close)
                Text(stringResource(R.string.ai_settings), Modifier.weight(1f).padding(start = Space.s4), color = palette.text.toComposeColor(),
                    fontSize = TextSize.heading, fontWeight = FontWeight.Bold)
                TextButton(onClick = ::save) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) }
            }
            HorizontalDivider(color = palette.border.toComposeColor())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s20)) {
                if (error.isNotEmpty()) AiBanner(error)
                SettingsGroup(stringResource(R.string.ai_settings_providers), stringResource(R.string.ai_settings_providers_note)) {
                    if (config.providers.isEmpty()) Text(stringResource(R.string.ai_providers_empty), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
                    else Column {
                        config.providers.forEachIndexed { index, p ->
                            if (index > 0) HorizontalDivider(color = palette.border.toComposeColor())
                            key(p.id) { AiProviderRow(p, isDefault = many && p.id == config.provider) { editing = p to false } }
                        }
                    }
                    TextButton(enabled = config.providers.size < AiConfigStore.MAX_PROVIDERS, onClick = { editing = AiProvider() to true },
                        contentPadding = PaddingValues(horizontal = Space.s4)) {
                        Icon(XnotesIcons.plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.s8)); Text(stringResource(R.string.ai_provider_add))
                    }
                    if (config.providers.size >= AiConfigStore.MAX_PROVIDERS)
                        Text(AiText.t(AiMsg.CONFIG_TOO_MANY_PROVIDERS, AiConfigStore.MAX_PROVIDERS), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                }
                SettingsGroup(stringResource(R.string.ai_settings_new_chat), stringResource(R.string.ai_settings_new_chat_note)) {
                    Box {
                        val defaultLabel = if (config.model.isBlank()) stringResource(R.string.ai_settings_no_model)
                            else listOfNotNull(config.providerOf(config.provider)?.label?.takeIf { many }, config.model).joinToString(" · ")
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
                            .clickable(enabled = config.providers.isNotEmpty()) { defaultModelMenu = true }.padding(horizontal = Space.s4),
                            verticalAlignment = Alignment.CenterVertically) {
                            SettingsLabel(stringResource(R.string.ai_default_model), modifier = Modifier.weight(1f))
                            Text(defaultLabel, Modifier.widthIn(max = 300.dp), color = palette.text.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(XnotesIcons.chevronDown, null, Modifier.padding(start = Space.s4).size(16.dp), tint = palette.textDim.toComposeColor())
                        }
                        DropdownMenu(expanded = defaultModelMenu, onDismissRequest = { defaultModelMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                            config.providers.filter { it.modelChoices.isNotEmpty() }.forEach { p ->
                                Text(p.label, Modifier.padding(horizontal = Space.s16, vertical = Space.s4), color = palette.textDim.toComposeColor(), fontSize = TextSize.label,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p.modelChoices.forEach { name ->
                                    DropdownMenuItem(text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { config = config.route(p.id, name); defaultModelMenu = false },
                                        trailingIcon = { if (p.id == config.provider && name == config.model) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) })
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = palette.border.toComposeColor())
                    SettingsLabel(stringResource(R.string.ai_thinking_default))
                    AiSegmented(effortOptions(), config.effort, Modifier.fillMaxWidth()) { config = config.copy(effort = it) }
                    HorizontalDivider(color = palette.border.toComposeColor())
                    AiSwitchRow(stringResource(R.string.ai_default_search), stringResource(R.string.ai_default_search_note), config.searchByDefault) { config = config.copy(searchByDefault = it) }
                    HorizontalDivider(color = palette.border.toComposeColor())
                    AiSwitchRow(stringResource(R.string.ai_default_attach_pdf), stringResource(R.string.ai_default_attach_pdf_note), config.attachPdfByDefault) { config = config.copy(attachPdfByDefault = it) }
                }
                SettingsGroup(stringResource(R.string.ai_settings_thinking), stringResource(R.string.ai_settings_thinking_note)) {
                    OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.ai_max_tokens)) },
                        supportingText = { Text(stringResource(R.string.ai_max_tokens_note, AiProtocol.MIN_MAX_TOKENS, AiProtocol.MAX_MAX_TOKENS)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
                SettingsGroup(stringResource(R.string.ai_settings_personal), stringResource(R.string.ai_instructions_note)) {
                    OutlinedTextField(config.instructions, { config = config.copy(instructions = it.take(AiProtocol.MAX_INSTRUCTIONS)) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_instructions)) }, placeholder = { Text(stringResource(R.string.ai_instructions_hint)) },
                        minLines = 4, maxLines = 10,
                        supportingText = { Text("${config.instructions.length} / ${AiProtocol.MAX_INSTRUCTIONS}") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { promptMenu = true }, contentPadding = PaddingValues(horizontal = Space.s4)) {
                                Icon(XnotesIcons.restore, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.s8)); Text(stringResource(R.string.ai_instructions_restore))
                            }
                            DropdownMenu(expanded = promptMenu, onDismissRequest = { promptMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ai_instructions_default_zh)) }, onClick = { config = config.copy(instructions = AiDefaults.INSTRUCTIONS_ZH); promptMenu = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.ai_instructions_default_en)) }, onClick = { config = config.copy(instructions = AiDefaults.INSTRUCTIONS_EN); promptMenu = false })
                            }
                        }
                        TextButton(onClick = { config = config.copy(instructions = "") }, enabled = config.instructions.isNotEmpty(), contentPadding = PaddingValues(horizontal = Space.s4)) {
                            Icon(XnotesIcons.close, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.s8)); Text(stringResource(R.string.ai_instructions_clear))
                        }
                    }
                }
                SettingsGroup(stringResource(R.string.ai_settings_image), stringResource(R.string.ai_settings_image_note)) {
                    // Chosen from the image models the providers list, as the default chat model is from their chat models.
                    val painters = config.providers.filter { it.imageChoices.isNotEmpty() }
                    Box {
                        val imageLabel = if (config.imageRoute() == null) stringResource(R.string.ai_image_none)
                            else listOfNotNull(config.providerOf(config.imageProvider)?.label?.takeIf { many }, config.imageModel).joinToString(" · ")
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
                            .clickable(enabled = painters.isNotEmpty()) { imageProviderMenu = true }.padding(horizontal = Space.s4),
                            verticalAlignment = Alignment.CenterVertically) {
                            SettingsLabel(stringResource(R.string.ai_settings_image), modifier = Modifier.weight(1f))
                            Text(imageLabel, Modifier.widthIn(max = 300.dp), color = palette.text.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(XnotesIcons.chevronDown, null, Modifier.padding(start = Space.s4).size(16.dp), tint = palette.textDim.toComposeColor())
                        }
                        DropdownMenu(expanded = imageProviderMenu, onDismissRequest = { imageProviderMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ai_image_none)) }, onClick = { config = config.copy(imageProvider = "", imageModel = ""); imageProviderMenu = false },
                                trailingIcon = { if (config.imageRoute() == null) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) })
                            painters.forEach { p ->
                                Text(p.label, Modifier.padding(horizontal = Space.s16, vertical = Space.s4), color = palette.textDim.toComposeColor(), fontSize = TextSize.label,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p.imageChoices.forEach { name ->
                                    DropdownMenuItem(text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { config = config.copy(imageProvider = p.id, imageModel = name); imageProviderMenu = false },
                                        trailingIcon = { if (p.id == config.imageProvider && name == config.imageModel) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) })
                                }
                            }
                        }
                    }
                    if (painters.isEmpty()) Text(stringResource(R.string.ai_tpl_gen_image_model_needed), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                }
                SettingsGroup(stringResource(R.string.ai_settings_search), stringResource(R.string.ai_settings_search_note)) {
                    OutlinedTextField(config.searchKey, { config = config.copy(searchKey = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai_settings_search_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                Text(stringResource(R.string.ai_settings_privacy), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                // Clearing wipes the saved keys at once, so it asks again in place instead of acting on one tap.
                if (confirmClear) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_settings_clear_confirm), Modifier.weight(1f), color = palette.text.toComposeColor(), fontSize = TextSize.body)
                    TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        store.clear(); config = AiConfig(instructions = localDefaultPrompt); maxTokens = config.maxTokens.toString(); confirmClear = false
                        AiSessions.get(context).configRevision++; error = context.getString(R.string.ai_settings_cleared)
                    }) { Text(stringResource(R.string.ai_settings_clear_do), fontWeight = FontWeight.Bold) }
                } else TextButton(onClick = { confirmClear = true }, contentPadding = PaddingValues(horizontal = Space.s4)) {
                    Icon(XnotesIcons.trash, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.s8)); Text(stringResource(R.string.ai_settings_clear))
                }
            }
        }
    }
}

/** One provider in the settings list: its name, endpoint host and model count; tap to edit. */
@Composable
private fun AiProviderRow(provider: AiProvider, isDefault: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    // An unnamed provider is already labelled by its host; don't repeat it underneath.
    val host = runCatching { java.net.URI(provider.url.trim()).host }.getOrNull().orEmpty().takeIf { it != provider.label }.orEmpty()
    val count = provider.modelChoices.size
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = Space.s8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        Column(Modifier.weight(1f)) {
            Text(provider.label, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val images = provider.imageChoices.size
            Text(listOf(host, pluralStringResource(R.plurals.ai_provider_models, count, count),
                if (images > 0) stringResource(R.string.ai_provider_image_count, images) else "").filter(String::isNotEmpty).joinToString(" · "),
                color = palette.textDim.toComposeColor(), fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isDefault) Text(stringResource(R.string.ai_model_default),
            Modifier.clip(CircleShape).background(palette.selectionBackground.toComposeColor()).padding(horizontal = Space.s8, vertical = Space.s2),
            color = palette.selectionForeground.toComposeColor(), fontSize = TextSize.caption)
        Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
    }
}

/** One saved model of a provider, with ✕ to drop it from the list. */
@Composable
private fun AiModelRow(name: String, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(start = Space.s8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(name, Modifier.weight(1f), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AiIconButton(XnotesIcons.close, stringResource(R.string.ai_model_remove, name), onClick = onRemove)
    }
}

/**
 * One provider's sheet, as DSH's provider card: preset, name, URL, key, models (typed or fetched from
 * the provider and ticked) and the thinking-field format. Done applies it to the settings draft only.
 */
@Composable
private fun AiProviderEditor(initial: AiProvider, isNew: Boolean, close: () -> Unit, onDelete: () -> Unit, onDone: (AiProvider) -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    var newModel by remember { mutableStateOf("") }
    var formatMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Fetching the provider's list is the one network call here, and only on tap.
    var fetching by remember { mutableStateOf<AiConnection?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf("") }
    var modelError by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { fetching?.cancel() } }
    fun choices() = provider.modelChoices
    /** False, with the reason shown, when the list would pass [AiProtocol.MAX_MODELS]; shrinking is always allowed. */
    fun setChoices(list: List<String>): Boolean {
        val clean = list.map(String::trim).filter(String::isNotEmpty).distinct()
        if (clean.size > AiProtocol.MAX_MODELS && clean.size > choices().size) {
            modelError = AiText.t(AiMsg.CONFIG_TOO_MANY_MODELS, AiProtocol.MAX_MODELS); return false
        }
        modelError = ""
        provider = provider.copy(models = clean)
        return true
    }
    fun addModel() { newModel.trim().takeIf { it.isNotEmpty() }?.let { if (setChoices(choices() + it)) newModel = "" } }
    var newImage by remember { mutableStateOf("") }
    fun images() = provider.imageChoices
    /** The image list likewise: refused past [AiProtocol.MAX_MODELS], shrinking always allowed. */
    fun setImages(list: List<String>): Boolean {
        val clean = list.map(String::trim).filter(String::isNotEmpty).distinct()
        if (clean.size > AiProtocol.MAX_MODELS && clean.size > images().size) {
            modelError = AiText.t(AiMsg.CONFIG_TOO_MANY_MODELS, AiProtocol.MAX_MODELS); return false
        }
        modelError = ""
        provider = provider.copy(imageModels = clean)
        return true
    }
    fun addImage() { newImage.trim().takeIf { it.isNotEmpty() }?.let { if (setImages(images() + it)) newImage = "" } }
    fun done() {
        val clean = provider.copy(name = provider.name.trim(), url = provider.url.trim(), key = provider.key.trim(), models = provider.modelChoices, imageModels = provider.imageChoices)
        runCatching { AiConfigStore.validateProvider(clean) }.onSuccess { onDone(clean) }
            .onFailure { error = it.message ?: context.getString(R.string.ai_settings_invalid) }
    }
    fetched?.let { list ->
        AiModelPicker(list, choices(), images(), onToggle = { id, image ->
            if (image) setImages(if (id in images()) images() - id else images() + id) else setChoices(if (id in choices()) choices() - id else choices() + id)
        }) { fetched = null }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = MaterialTheme.shapes.large
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.9f).fillMaxHeight(0.86f).clip(shape)
            .background(palette.menuBg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape).imePadding()) {
            Row(Modifier.fillMaxWidth().height(MinTouchTarget + Space.s8).padding(horizontal = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                AiIconButton(XnotesIcons.close, stringResource(R.string.cancel), onClick = close)
                Text(stringResource(if (isNew) R.string.ai_provider_add else R.string.ai_provider_edit), Modifier.weight(1f).padding(start = Space.s4),
                    color = palette.text.toComposeColor(), fontSize = TextSize.heading, fontWeight = FontWeight.Bold)
                TextButton(onClick = ::done) { Text(stringResource(R.string.ai_provider_done), fontWeight = FontWeight.Bold) }
            }
            HorizontalDivider(color = palette.border.toComposeColor())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s20)) {
                if (error.isNotEmpty()) AiBanner(error)
                SettingsGroup(stringResource(R.string.ai_settings_provider), stringResource(R.string.ai_settings_chat_note)) {
                    Box {
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small).clickable { presetMenu = true }.padding(horizontal = Space.s4),
                            verticalAlignment = Alignment.CenterVertically) {
                            SettingsLabel(stringResource(R.string.ai_provider_preset), stringResource(R.string.ai_provider_preset_note), Modifier.weight(1f))
                            val preset = PROVIDER_PRESETS.firstOrNull { it.second == provider.url.trim().trimEnd('/') }
                            Text(stringResource(preset?.first ?: R.string.ai_provider_preset_custom), color = palette.text.toComposeColor(), fontSize = TextSize.body)
                            Icon(XnotesIcons.chevronDown, null, Modifier.padding(start = Space.s4).size(16.dp), tint = palette.textDim.toComposeColor())
                        }
                        DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                            PROVIDER_PRESETS.forEach { (label, url) ->
                                val name = stringResource(label)
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    // A typed name is the user's; only an empty or preset name follows the preset.
                                    val keepName = provider.name.isNotBlank() && PROVIDER_PRESETS.none { context.getString(it.first) == provider.name.trim() }
                                    provider = provider.copy(url = url, name = if (keepName) provider.name else name); presetMenu = false
                                }, trailingIcon = { if (url == provider.url.trim().trimEnd('/')) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                    OutlinedTextField(provider.name, { provider = provider.copy(name = it.take(60)) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.ai_provider_name)) }, supportingText = { Text(stringResource(R.string.ai_provider_name_hint)) })
                    OutlinedTextField(provider.url, { provider = provider.copy(url = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai_settings_url)) }, singleLine = true)
                    OutlinedTextField(provider.key, { provider = provider.copy(key = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai_settings_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                SettingsGroup(stringResource(R.string.ai_settings_models),
                    stringResource(R.string.ai_provider_models_note) + " " + stringResource(R.string.ai_models_saved_count, choices().size, AiProtocol.MAX_MODELS)) {
                    if (choices().isEmpty()) Text(stringResource(R.string.ai_models_empty), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
                    Column {
                        choices().forEachIndexed { index, name ->
                            if (index > 0) HorizontalDivider(color = palette.border.toComposeColor())
                            key(name) { AiModelRow(name) { setChoices(choices() - name) } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        OutlinedTextField(newModel, { newModel = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ai_models_add)) }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addModel() }))
                        AiIconButton(XnotesIcons.plus, stringResource(R.string.ai_models_add), enabled = newModel.isNotBlank(), onClick = ::addModel)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = fetching == null, onClick = {
                            fetchError = ""
                            val connection = AiConnection(); fetching = connection
                            val (url, key) = provider.url to provider.key.trim()
                            scope.launch {
                                try { fetched = withContext(Dispatchers.IO) { connection.listModels(url, key) } }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { fetchError = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: AiText.t(AiMsg.NETWORK_ERROR) else AiText.t(AiMsg.NETWORK_ERROR) }
                                finally { if (fetching === connection) fetching = null }
                            }
                        }, contentPadding = PaddingValues(horizontal = Space.s4)) {
                            if (fetching != null) AiSpinner() else Icon(XnotesIcons.download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(Space.s8)); Text(stringResource(R.string.ai_models_fetch))
                        }
                    }
                    if (fetchError.isNotEmpty()) Text(fetchError, color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                    if (modelError.isNotEmpty()) Text(modelError, color = palette.text.toComposeColor(), fontSize = TextSize.label)
                }
                // Models that paint pictures, kept apart from the chat list; fetched ones are ticked in the same dialog.
                SettingsGroup(stringResource(R.string.ai_models_image),
                    stringResource(R.string.ai_models_image_note) + " " + stringResource(R.string.ai_models_saved_count, images().size, AiProtocol.MAX_MODELS)) {
                    if (images().isEmpty()) Text(stringResource(R.string.ai_models_image_empty), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
                    Column {
                        images().forEachIndexed { index, name ->
                            if (index > 0) HorizontalDivider(color = palette.border.toComposeColor())
                            key("image/$name") { AiModelRow(name) { setImages(images() - name) } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        OutlinedTextField(newImage, { newImage = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ai_models_image_add)) }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.ai_image_model_hint)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addImage() }))
                        AiIconButton(XnotesIcons.plus, stringResource(R.string.ai_models_image_add), enabled = newImage.isNotBlank(), onClick = ::addImage)
                    }
                }
                SettingsGroup(stringResource(R.string.ai_settings_thinking)) {
                    Box {
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small).clickable { formatMenu = true }.padding(horizontal = Space.s4),
                            verticalAlignment = Alignment.CenterVertically) {
                            SettingsLabel(stringResource(R.string.ai_thinking_format), stringResource(R.string.ai_thinking_format_note), Modifier.weight(1f))
                            Text(stringResource(formatRes(provider.thinkingFormat)), color = palette.text.toComposeColor(), fontSize = TextSize.body)
                            Icon(XnotesIcons.chevronDown, null, Modifier.padding(start = Space.s4).size(16.dp), tint = palette.textDim.toComposeColor())
                        }
                        DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                            AiThinking.FORMATS.forEach { f ->
                                DropdownMenuItem(text = { Text(stringResource(formatRes(f))) }, onClick = { provider = provider.copy(thinkingFormat = f); formatMenu = false },
                                    trailingIcon = { if (f == provider.thinkingFormat) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
                // Deleting asks again in place; chats that used this provider will ask for another model.
                if (!isNew) {
                    if (confirmDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ai_provider_delete_confirm), Modifier.weight(1f), color = palette.text.toComposeColor(), fontSize = TextSize.body)
                        TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.ai_provider_delete_do), fontWeight = FontWeight.Bold) }
                    } else TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(horizontal = Space.s4)) {
                        Icon(XnotesIcons.trash, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.s8)); Text(stringResource(R.string.ai_provider_delete))
                    }
                }
            }
        }
    }
}

// --- entry and host ------------------------------------------------------------------------

/**
 * The screenshot menu's AI action for one document. While the floating chat is open — over this
 * document or, in split screen, the other one — [addsToOpenChat] is true and a capture joins that
 * chat's next question; otherwise it starts a new chat from this document.
 */
internal class AskAiAction(val addsToOpenChat: Boolean, val take: (File) -> Unit)

/**
 * [onCapture] arms this pane's screenshot tool and [onRelease] restores the tool it had, for the
 * floating chat's capture mode, which drives every pane at once.
 */
@Composable
internal fun rememberAskAi(source: String, sourceUri: String?, currentPdf: File? = null, documentId: String = "",
                           onCapture: () -> Unit = {}, onRelease: () -> Unit = {}): AskAiAction {
    val context = LocalContext.current
    val sessions = AiSessions.get(context)
    val owner = documentId.ifBlank { sourceUri.orEmpty().ifBlank { source } }
    val capture by rememberUpdatedState(onCapture)
    val release by rememberUpdatedState(onRelease)
    val pdfUri = currentPdf?.let { android.net.Uri.fromFile(it).toString() }
    DisposableEffect(sessions, owner, source, sourceUri, pdfUri) {
        val document = AiDocument(source, sourceUri.orEmpty(), documentId, pdfUri?.let { it to "$source.pdf" })
        sessions.documents[owner] = document
        onDispose { if (sessions.documents[owner] === document) sessions.documents.remove(owner) }
    }
    // Per pane, not per document: the same document in both panes is two places to capture from.
    DisposableEffect(sessions) {
        val token = Any()
        sessions.registerCapture(token, AiCaptureTarget({ capture() }, { release() }))
        onDispose { sessions.unregisterCapture(token) }
    }
    var failure by remember { mutableStateOf("") }
    if (failure.isNotEmpty()) AlertDialog(onDismissRequest = { failure = "" }, text = { Text(failure) },
        confirmButton = { TextButton(onClick = { failure = "" }) { Text(stringResource(R.string.ai_ok)) } })
    val openChat = sessions.activeId?.takeIf { sessions.floatingOwner != null && sessions.chats.containsKey(it) }
    return AskAiAction(openChat != null) { file ->
        runCatching {
            check(sessions.ready) { context.getString(R.string.ai_history_loading) }
            require(file.length() in 1..(6 * 1024 * 1024)) { context.getString(R.string.ai_screenshot_too_large) }
            val bytes = file.readBytes()
            if (openChat != null) {
                check(sessions.addScreenshot(openChat, bytes, fromDocument = owner)) { context.getString(R.string.ai_screenshot_limit, AiProtocol.MAX_IMAGES) }
                // In capture mode the card stays folded for the next capture; it shows the count.
                if (!sessions.capturing) sessions.floatingCollapsed = false
            } else {
                val id = sessions.newChat(source, sourceUri.orEmpty(), owner, documentId)
                sessions.addScreenshot(id, bytes)
                sessions.floatingCollapsed = false
                pdfUri?.let { sessions.offerCurrentPdf(id, it, "$source.pdf") }
            }
        }.onFailure { failure = it.message ?: context.getString(R.string.ai_screenshot_failed) }
    }
}

/** Exactly one host at the application root; survives editor and backstage navigation. */
@Composable
fun AiChatHost(visibleDocuments: Set<String>) {
    val sessions = AiSessions.get(LocalContext.current)
    val chat = sessions.activeId?.let { sessions.chats[it] }
    LaunchedEffect(visibleDocuments, sessions.floatingOwner) { sessions.reconcileFloating(visibleDocuments) }
    if (chat != null && sessions.floatingOwner in visibleDocuments) key(chat.id) { AskAiWindow(sessions, chat) }
}

// --- the chat ------------------------------------------------------------------------------

/**
 * One conversation. Floating, it is a draggable card over the document whose header collapses it to
 * a strip; in the AI page it fills its column and [onHistory] / [onShowSidebar] add the page's
 * navigation to the same header.
 */
@Composable
private fun AskAiWindow(
    sessions: AiSessions,
    chat: AiChat,
    floating: Boolean = true,
    onHistory: (() -> Unit)? = null,
    onShowSidebar: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val uiScope = rememberCoroutineScope()
    val scroll = remember(chat.id) { ScrollState(sessions.scrollPositions[chat.id] ?: 0) }
    var follow by remember(chat.id) { mutableStateOf(sessions.followLatest[chat.id] ?: true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (value, scrolling) ->
            sessions.scrollPositions[chat.id] = value
            if (scrolling) { follow = scroll.maxValue - value < 80; sessions.followLatest[chat.id] = follow }
        }
    }
    LaunchedEffect(scroll.maxValue) { if (follow && !scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue) }
    fun jumpToLatest() { follow = true; sessions.followLatest[chat.id] = true; uiScope.launch { scroll.animateScrollTo(scroll.maxValue) } }
    var settings by remember { mutableStateOf(false) }
    // The floating card's own list of this document's chats, shown in place of the conversation.
    var history by remember { mutableStateOf(false) }
    val collapsed = floating && sessions.floatingCollapsed
    val owner = sessions.floatingOwner

    // Unplaced until first moved: the card then opens at the right edge below the document toolbar,
    // not over the toolbar's first tools. Once moved, every chat (a new one too) opens where it was left.
    val x = sessions.windowOffset?.first ?: Float.NaN
    val y = sessions.windowOffset?.second ?: Float.NaN
    val busy = sessions.isRunning(chat.id)
    val prompt = sessions.drafts[chat.id] ?: ""
    val screenshots = sessions.attachments[chat.id].orEmpty()
    val importing = sessions.importStates[chat.id]?.loading == true
    var retryQuestion by remember { mutableStateOf<AiMessage?>(null) }
    retryQuestion?.let { question ->
        AlertDialog(onDismissRequest = { retryQuestion = null }, title = { Text(stringResource(R.string.ai_replace_draft_title)) },
            text = { Text(stringResource(R.string.ai_replace_draft_text)) },
            confirmButton = { TextButton(onClick = { sessions.restoreQuestion(chat.id, question); retryQuestion = null }) { Text(stringResource(R.string.ai_replace)) } },
            dismissButton = { TextButton(onClick = { retryQuestion = null }) { Text(stringResource(R.string.ai_keep_draft)) } })
    }
    if (settings) AiSettings { settings = false }
    fun retry(question: AiMessage) {
        if (prompt.isNotBlank() || screenshots.isNotEmpty() || sessions.pdfAttachments[chat.id] != null || importing) retryQuestion = question
        else sessions.restoreQuestion(chat.id, question)
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Collapsed keeps the open width, so folding never shifts the card sideways.
        val width = if (!floating) maxWidth else minOf(maxWidth, 480.dp)
        val height = if (!floating) maxHeight else if (collapsed) MinTouchTarget + Space.s8 else minOf(maxHeight, 680.dp)
        val maxX = with(density) { (maxWidth - width).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - height).toPx() }.coerceAtLeast(0f)
        val startX = (maxX - with(density) { Space.s12.toPx() }).coerceAtLeast(0f)
        val startY = with(density) { (MinTouchTarget + Space.s20).toPx() }.coerceAtMost(maxY)
        val cardX = if (x.isNaN()) startX else x
        val cardY = if (y.isNaN()) startY else y
        // The corner every panel floating over a document wears (selection bar, slash menu, canvas menus).
        val shape = MaterialTheme.shapes.medium
        Column(
            Modifier.offset { IntOffset(cardX.coerceIn(0f, maxX).toInt(), cardY.coerceIn(0f, maxY).toInt()) }
                .width(width).height(height)
                .then(if (floating) Modifier.clip(shape).background(palette.menuBg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape) else Modifier)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Header: the whole strip drags the floating card; collapsed, a tap on it reopens the chat.
            Row(
                Modifier.fillMaxWidth().height(MinTouchTarget + Space.s8)
                    .pointerInput(maxX, maxY) {
                        if (floating) detectDragGestures { change, drag ->
                            change.consume()
                            val (lastX, lastY) = sessions.windowOffset ?: (startX to startY)
                            sessions.windowOffset = (lastX.coerceIn(0f, maxX) + drag.x).coerceIn(0f, maxX) to (lastY.coerceIn(0f, maxY) + drag.y).coerceIn(0f, maxY)
                        }
                    }
                    .then(if (collapsed) Modifier.clickable { sessions.expand() } else Modifier)
                    .padding(horizontal = Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onShowSidebar?.let { AiIconButton(XnotesIcons.menu, stringResource(R.string.show_sidebar), onClick = it) }
                onHistory?.let { AiIconButton(XnotesIcons.list, stringResource(R.string.ai_history), onClick = it) }
                if (floating) Icon(XnotesIcons.ai, null, Modifier.padding(horizontal = Space.s12).size(18.dp), tint = palette.text.toComposeColor())
                else Spacer(Modifier.width(Space.s8))
                // Folded for capturing, the strip says what to do and how many captures have joined.
                if (collapsed && sessions.capturing) Text(stringResource(R.string.ai_capturing, screenshots.size), Modifier.weight(1f),
                    color = palette.text.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text(chat.displayTitle(), Modifier.weight(1f), color = palette.text.toComposeColor(), fontSize = if (floating) TextSize.bodyLarge else TextSize.title,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (busy) Box(Modifier.padding(horizontal = Space.s8)) { AiSpinner() }
                if (!collapsed) {
                    if (floating && owner != null) {
                        AiIconButton(XnotesIcons.list, stringResource(R.string.ai_history)) { history = !history }
                        // Folds the card and arms the screenshot tool in every pane; unfolding the card ends it.
                        if (sessions.canCapture) AiIconButton(XnotesIcons.scissors, stringResource(R.string.ai_take_screenshot)) {
                            history = false; sessions.startCapture()
                        }
                    }
                    AiIconButton(XnotesIcons.edit, stringResource(R.string.ai_new_chat), enabled = sessions.ready) {
                        history = false
                        sessions.newChat(floatingOwner = if (floating) sessions.floatingOwner else null)
                    }
                    AiIconButton(XnotesIcons.sliders, stringResource(R.string.ai_settings)) { settings = true }
                }
                if (floating) {
                    AiIconButton(if (collapsed) XnotesIcons.chevronUp else XnotesIcons.chevronDown,
                        stringResource(if (collapsed) R.string.ai_expand else R.string.ai_collapse)) { if (collapsed) sessions.expand() else sessions.floatingCollapsed = true }
                    AiIconButton(XnotesIcons.close, stringResource(R.string.ai_close)) { sessions.close() }
                }
            }
            if (!collapsed && history && owner != null) {
                HorizontalDivider(color = palette.border.toComposeColor())
                AiFloatingHistory(sessions, owner, Modifier.weight(1f).fillMaxWidth()) { history = false }
            } else if (!collapsed) {
                HorizontalDivider(color = palette.border.toComposeColor())
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Space.s16, vertical = Space.s12),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s20)) {
                            if (sessions.storageError.isNotEmpty()) AiBanner(sessions.storageError, stringResource(R.string.ai_retry_save), sessions::retrySave)
                            if (chat.turns.isEmpty()) AiEmptyChat(floating)
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s20)) {
                                    chat.turns.forEachIndexed { index, turn ->
                                        key(index) { AiTurnView(chat.id, index, turn, canRetry = !busy) { retry(turn.user) } }
                                    }
                                }
                            }
                        }
                    }
                    // Scrolled away from a reply that is still growing: one round button brings it back.
                    if (!follow && scroll.maxValue > scroll.value) {
                        val label = stringResource(R.string.ai_jump_latest)
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(Space.s12).size(MinTouchTarget).clip(CircleShape)
                                .background(palette.menuBg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), CircleShape)
                                .clickable(onClickLabel = label) { jumpToLatest() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(XnotesIcons.arrowDown, label, Modifier.size(20.dp), tint = palette.text.toComposeColor()) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = Space.s12, end = Space.s12, bottom = Space.s12), contentAlignment = Alignment.Center) {
                    AiComposer(sessions, chat, Modifier.widthIn(max = 760.dp), onSent = ::jumpToLatest, onSettings = { settings = true })
                }
            }
        }
    }
}

@Composable
private fun AiEmptyChat(floating: Boolean) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = if (floating) Space.s24 else Space.s48), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        Box(Modifier.size(MinTouchTarget).clip(CircleShape).background(palette.surface.toComposeColor()), contentAlignment = Alignment.Center) {
            Icon(XnotesIcons.ai, null, Modifier.size(24.dp), tint = palette.text.toComposeColor())
        }
        Text(stringResource(R.string.ai_empty_title), color = palette.text.toComposeColor(), fontSize = TextSize.title, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.ai_empty_hint), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
    }
}

/** One exchange: the question as a grey bubble on the right, the answer as plain reading text. */
@Composable
private fun AiTurnView(chatId: String, index: Int, turn: AiTurn, canRetry: Boolean, onRetry: () -> Unit) {
    val palette = LocalPalette.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var details by remember(chatId, index) { mutableStateOf(false) }
    val running = turn.state == STATE_RUNNING
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        Column(Modifier.align(Alignment.End).widthIn(max = 560.dp).fillMaxWidth(0.85f), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Space.s6)) {
            // Several screenshots sit in one row that scrolls sideways, smaller than a lone one.
            if (turn.user.images.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                turn.user.images.forEach { encoded ->
                    val bitmap = remember(encoded) { runCatching { val bytes = Base64.decode(encoded, Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
                    bitmap?.let {
                        Image(it.asImageBitmap(), stringResource(R.string.ai_chat_screenshot), Modifier.heightIn(max = if (turn.user.images.size == 1) 160.dp else 120.dp)
                            .clip(MaterialTheme.shapes.medium).border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
                    }
                }
            }
            turn.user.pdf?.let { AiFileTag(XnotesIcons.pdfFile, it.name, pluralStringResource(R.plurals.ai_pages, it.pageCount, it.pageCount)) }
            if (turn.user.text.isNotBlank()) Text(turn.user.text,
                Modifier.clip(MaterialTheme.shapes.large).background(palette.surface.toComposeColor()).padding(horizontal = Space.s16, vertical = Space.s12),
                color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
        }
        if (turn.answer.isNotBlank()) AiMarkdownText(turn.answer)
        if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            AiSpinner()
            Text(turn.progress.ifBlank { stringResource(R.string.ai_generating) }, color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
        } else if (turn.state != STATE_DONE || turn.error.isNotBlank()) {
            Text(stateLabel(turn.state) + if (turn.error.isNotBlank()) " · ${turn.error}" else "", color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
        }
        val sources = (turn.steps + turn.user.searchContext).filter { it.isNotBlank() }
        if (!running) Row(verticalAlignment = Alignment.CenterVertically) {
            if (turn.answer.isNotBlank()) AiIconButton(XnotesIcons.copy, stringResource(R.string.ai_copy_answer)) {
                scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("AI", turn.answer))) }
            }
            if (turn.state in RETRYABLE_STATES && canRetry) AiIconButton(XnotesIcons.restore, stringResource(R.string.ai_ask_again), onClick = onRetry)
            if (sources.isNotEmpty()) TextButton(onClick = { details = !details }) {
                Text(stringResource(if (details) R.string.ai_details_hide else R.string.ai_details_show), fontSize = TextSize.label, color = palette.textDim.toComposeColor())
                Icon(if (details) XnotesIcons.chevronUp else XnotesIcons.chevronDown, null, Modifier.padding(start = Space.s4).size(14.dp), tint = palette.textDim.toComposeColor())
            }
        }
        if (details && sources.isNotEmpty()) Text(sources.joinToString("\n"),
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(palette.surface.toComposeColor()).padding(Space.s12),
            color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
    }
}

/** A file shown by name: an icon, the name and a quiet detail such as the page count. */
@Composable
private fun AiFileTag(icon: ImageVector, name: String, detail: String) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Row(Modifier.clip(shape).border(1.dp, palette.border.toComposeColor(), shape).padding(horizontal = Space.s12, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        Icon(icon, null, Modifier.size(16.dp), tint = palette.text.toComposeColor())
        Text(name, color = palette.text.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(detail, color = palette.textDim.toComposeColor(), fontSize = TextSize.label, maxLines = 1)
    }
}

/** A pending attachment above the input; [onRemove] is its own 48 dp target. */
@Composable
private fun AiAttachmentChip(label: String, detail: String, leading: @Composable () -> Unit, removeLabel: String, onRemove: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Row(Modifier.height(MinTouchTarget).clip(shape).background(palette.surface.toComposeColor()).padding(start = Space.s8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        leading()
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(label, color = palette.text.toComposeColor(), fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = palette.textDim.toComposeColor(), fontSize = TextSize.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        extra()
        AiIconButton(XnotesIcons.close, removeLabel, onClick = onRemove)
    }
}

/**
 * The input card: pending attachments, the text, then one row with ＋ (attach), the search toggle
 * and the round send/stop button. Everything a question needs sits in this one place.
 */
@Composable
private fun AiComposer(sessions: AiSessions, chat: AiChat, modifier: Modifier, onSent: () -> Unit, onSettings: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val importState = sessions.importStates[chat.id]
    val importing = importState?.loading == true
    val webSearch = sessions.searchEnabled[chat.id] ?: false
    val prompt = sessions.drafts[chat.id] ?: ""
    val screenshots = sessions.attachments[chat.id].orEmpty()
    val pdf = sessions.pdfAttachments[chat.id]
    val busy = sessions.isRunning(chat.id)
    var clipboardError by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    fun attachPdf(uri: android.net.Uri, name: String? = null) { clipboardError = false; sessions.importPdf(chat.id, uri.toString(), name) }
    // Capture the requesting chat: picker results must never attach to a newly selected conversation.
    var pickerChat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var pickerVersion by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0L) }
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val target = pickerChat; pickerChat = null
        if (uri != null && target != null && sessions.composerVersion(target) == pickerVersion)
            sessions.importPdf(target, uri.toString())
    }
    val config = remember(sessions.configRevision) { runCatching { AiConfigStore(context).load() }.getOrNull() ?: AiConfig() }
    // A chat whose provider was deleted must pick another model first, as DSH blocks it.
    val routable = chat.routable(config)
    val canSend = prompt.isNotBlank() && !importing && importState?.error.isNullOrEmpty() && routable
    // No rounder than the floating card it sits in.
    val shape = MaterialTheme.shapes.medium
    Column(modifier.fillMaxWidth()) {
        if (clipboardError) Text(stringResource(R.string.ai_paste_no_pdf), Modifier.padding(bottom = Space.s8), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
        Column(Modifier.fillMaxWidth().clip(shape).background(palette.menuBg.toComposeColor()).border(1.dp, palette.materialColors.outline.toComposeColor(), shape)) {
            if (screenshots.isNotEmpty() || importState != null || pdf != null) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Space.s8, end = Space.s8, top = Space.s8),
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                screenshots.forEachIndexed { index, bytes ->
                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    val description = if (screenshots.size == 1) stringResource(R.string.ai_screenshot) else stringResource(R.string.ai_screenshot_numbered, index + 1)
                    AiAttachmentChip(description, "", leading = {
                        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.size(32.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop) }
                    }, removeLabel = stringResource(R.string.ai_remove_screenshot), onRemove = { sessions.removeScreenshot(chat.id, index) })
                }
                importState?.let { state ->
                    AiAttachmentChip(state.name ?: "PDF", if (state.loading) stringResource(R.string.ai_preparing) else state.error, leading = {
                        if (state.loading) AiSpinner() else Icon(XnotesIcons.pdfFile, null, Modifier.size(18.dp), tint = palette.text.toComposeColor())
                    }, removeLabel = stringResource(if (state.loading) R.string.ai_stop_preparing else R.string.ai_remove_attachment), onRemove = { sessions.removePdf(chat.id) }) {
                        if (!state.loading) AiIconButton(XnotesIcons.restore, stringResource(R.string.ai_retry_attachment)) { sessions.retryImport(chat.id) }
                    }
                }
                pdf?.let {
                    AiAttachmentChip(it.name, pluralStringResource(R.plurals.ai_pages, it.pageCount, it.pageCount), leading = {
                        Icon(XnotesIcons.pdfFile, null, Modifier.size(18.dp), tint = palette.text.toComposeColor())
                    }, removeLabel = stringResource(R.string.ai_remove_attachment), onRemove = { if (!importing) sessions.removePdf(chat.id) })
                }
            }
            val text = palette.text.toComposeColor()
            val hint = stringResource(if (busy) R.string.ai_input_hint_busy else R.string.ai_input_hint)
            BasicTextField(
                prompt, { sessions.editDraft(chat.id, text = it) },
                Modifier.fillMaxWidth().heightIn(min = MinTouchTarget, max = 160.dp).padding(horizontal = Space.s16, vertical = Space.s12),
                textStyle = TextStyle(color = text, fontSize = TextSize.bodyLarge),
                cursorBrush = SolidColor(text),
                decorationBox = { inner ->
                    if (prompt.isEmpty()) Text(hint, color = palette.textDim.toComposeColor(), fontSize = TextSize.bodyLarge)
                    inner()
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AiIconButton(XnotesIcons.plus, stringResource(R.string.ai_attach), enabled = !importing) { attachMenu = true }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        sessions.currentPdfs[chat.id]?.let { (uri, name) ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.ai_attach_current_pdf)) }, leadingIcon = { Icon(XnotesIcons.pdf, null, Modifier.size(18.dp)) },
                                onClick = { attachMenu = false; attachPdf(android.net.Uri.parse(uri), name) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.ai_attach_pick_pdf)) }, leadingIcon = { Icon(XnotesIcons.folder, null, Modifier.size(18.dp)) },
                            onClick = { attachMenu = false; pickerChat = chat.id; pickerVersion = sessions.composerVersion(chat.id); pdfPicker.launch(arrayOf("application/pdf")) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ai_attach_paste_pdf)) }, leadingIcon = { Icon(XnotesIcons.paste, null, Modifier.size(18.dp)) }, onClick = {
                            attachMenu = false
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = clipboard.primaryClip
                            val uri = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).uri else null
                            if (uri == null) clipboardError = true else attachPdf(uri)
                        })
                    }
                }
                AiSearchToggle(webSearch) { sessions.editDraft(chat.id, search = it) }
                // The chip takes what room is left and shortens its label; send stays at the far end.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { AiModelChip(sessions, chat, config, Modifier, onSettings) }
                AiSendButton(busy = busy, enabled = busy || canSend) {
                    if (busy) sessions.stop(chat.id)
                    else {
                        sessions.send(chat.id, AiMessage("user", prompt.trim(), screenshots.map { Base64.encodeToString(it, Base64.NO_WRAP) }, pdf = pdf), webSearch)
                        onSent()
                    }
                }
            }
        }
    }
}

/**
 * This chat's model and thinking level as one quiet pill ("model · High ⌄"), as DSH shows it; the
 * menu groups the saved models under their providers and offers the thinking levels. With more than
 * one provider the pill names the provider too. Changes apply to the next question.
 */
@Composable
private fun AiModelChip(sessions: AiSessions, chat: AiChat, config: AiConfig, modifier: Modifier, onSettings: () -> Unit) {
    val palette = LocalPalette.current
    val current = chat.settings(config)
    val routable = chat.routable(config)
    var menu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val title = stringResource(R.string.ai_model_chip)
    val provider = config.providerOf(current.provider)
    val label = if (!routable) stringResource(R.string.ai_model_select)
        else listOfNotNull(provider?.label?.takeIf { config.providers.size > 1 }, current.model.ifBlank { stringResource(R.string.ai_settings_no_model) },
            current.effort.takeIf { it.isNotEmpty() }?.let { stringResource(effortRes(it)) }).joinToString(" · ")
    // Each group is a provider and its models; a setup from before providers is one unnamed group.
    val groups = config.providers.filter { it.modelChoices.isNotEmpty() }.map { it.id to it.label to it.modelChoices }.ifEmpty { listOf("" to "" to config.modelChoices).filter { it.second.isNotEmpty() } }
    val total = groups.sumOf { it.second.size }
    val needle = query.trim()
    Box(modifier) {
        Tooltip(title) {
            Box(Modifier.height(MinTouchTarget).clickable(role = Role.Button, onClickLabel = title) { query = ""; menu = true }.padding(horizontal = Space.s4), contentAlignment = Alignment.Center) {
                Row(Modifier.height(32.dp).clip(CircleShape).border(1.dp, palette.border.toComposeColor(), CircleShape).padding(start = Space.s12, end = Space.s8),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Text(label, Modifier.weight(1f, fill = false), color = (if (routable) palette.textDim else palette.text).toComposeColor(), fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(XnotesIcons.chevronDown, null, Modifier.size(14.dp), tint = palette.textDim.toComposeColor())
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.widthIn(min = 260.dp, max = 360.dp).heightIn(max = 520.dp)) {
            // Search only pays off on a long list, as in DSH's model menu.
            if (total > SEARCH_MODELS_AFTER) OutlinedTextField(query, { query = it }, Modifier.padding(horizontal = Space.s12, vertical = Space.s4).fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(XnotesIcons.search, null, Modifier.size(18.dp)) }, placeholder = { Text(stringResource(R.string.ai_models_filter)) })
            var shown = 0
            groups.forEach { (head, models) ->
                val (id, name) = head
                val matches = models.filter { it.contains(needle, ignoreCase = true) || name.contains(needle, ignoreCase = true) }
                if (matches.isEmpty()) return@forEach
                shown += matches.size
                Text(name.ifEmpty { stringResource(R.string.ai_settings_models) }, Modifier.padding(horizontal = Space.s16, vertical = Space.s4),
                    color = palette.textDim.toComposeColor(), fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                matches.forEach { model ->
                    val on = routable && model == current.model && (id.isEmpty() || id == current.provider)
                    DropdownMenuItem(text = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { sessions.choose(chat.id, model = model, provider = id); menu = false },
                        trailingIcon = { if (on) Icon(XnotesIcons.check, null, Modifier.size(16.dp)) })
                }
            }
            if (total > 0 && shown == 0) Text(stringResource(R.string.ai_models_no_match), Modifier.padding(horizontal = Space.s16, vertical = Space.s8), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
            DropdownMenuItem(text = { Text(stringResource(if (total == 0) R.string.ai_models_set_up else R.string.ai_models_manage)) },
                leadingIcon = { Icon(XnotesIcons.sliders, null, Modifier.size(18.dp)) }, onClick = { menu = false; onSettings() })
            HorizontalDivider(Modifier.padding(vertical = Space.s4), color = palette.border.toComposeColor())
            Text(stringResource(R.string.ai_settings_thinking), Modifier.padding(horizontal = Space.s16, vertical = Space.s4), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
            AiSegmented(effortOptions(), current.effort, Modifier.padding(horizontal = Space.s12).fillMaxWidth()) { sessions.choose(chat.id, effort = it) }
            Text(stringResource(R.string.ai_thinking_menu_note), Modifier.padding(horizontal = Space.s16, vertical = Space.s8), color = palette.textDim.toComposeColor(), fontSize = TextSize.caption)
        }
    }
}

/** More saved models than this, across providers, and the model menu gets a search field. */
private const val SEARCH_MODELS_AFTER = 8

/** Web search as a pill: outlined and quiet when off, inverted when on. */
@Composable
private fun AiSearchToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    val fg = (if (on) palette.selectionForeground else palette.textDim).toComposeColor()
    val label = stringResource(R.string.ai_web_search)
    Tooltip(label) {
        Box(Modifier.height(MinTouchTarget).toggleable(on, role = Role.Switch, onValueChange = onChange), contentAlignment = Alignment.Center) {
            Row(
                Modifier.height(32.dp).clip(CircleShape)
                    .then(if (on) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier.border(1.dp, palette.border.toComposeColor(), CircleShape))
                    .padding(horizontal = Space.s12),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s6),
            ) {
                Icon(XnotesIcons.globe, label, Modifier.size(16.dp), tint = fg)
                Text(stringResource(R.string.ai_web), color = fg, fontSize = TextSize.label)
            }
        }
    }
}

/** Round send button, inverted when it can act; while answering it becomes Stop. */
@Composable
private fun AiSendButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val label = stringResource(if (busy) R.string.ai_stop else R.string.ai_send)
    Tooltip(label) {
        Box(Modifier.size(MinTouchTarget).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).clip(CircleShape).background((if (enabled) palette.selectionBackground else palette.surface).toComposeColor()), contentAlignment = Alignment.Center) {
                Icon(if (busy) XnotesIcons.stop else XnotesIcons.arrowUp, label, Modifier.size(18.dp),
                    tint = (if (enabled) palette.selectionForeground else palette.textDim).toComposeColor())
            }
        }
    }
}

/**
 * The floating card's history: only this document's chats, newest first. Picking one keeps it
 * floating over the document; the trash button moves a chat to Trash. Renaming and opening other
 * documents stay on the AI page.
 */
@Composable
private fun AiFloatingHistory(sessions: AiSessions, owner: String, modifier: Modifier, onPicked: () -> Unit) {
    val palette = LocalPalette.current
    val chats = sessions.chatsOf(owner)
    var deleting by remember { mutableStateOf<AiChat?>(null) }
    deleting?.let { chat -> AiTrashDialog(chat, onConfirm = { sessions.delete(chat.id); deleting = null }) { deleting = null } }
    Column(modifier) {
        Text(stringResource(R.string.ai_document_history), Modifier.padding(horizontal = Space.s16, vertical = Space.s8),
            color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
        if (chats.isEmpty()) Text(stringResource(R.string.ai_no_chats), Modifier.padding(horizontal = Space.s16, vertical = Space.s8),
            color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(horizontal = Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            items(chats.size, key = { chats[it].id }) { index ->
                val chat = chats[index]
                val selected = sessions.activeId == chat.id
                val running = sessions.isRunning(chat.id)
                val fg = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
                val dim = if (selected) fg.copy(alpha = 0.7f) else palette.textDim.toComposeColor()
                val turns = pluralStringResource(R.plurals.ai_turns, chat.turns.size, chat.turns.size)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.medium)
                        .then(if (selected) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier)
                        .clickable { sessions.openFloating(chat.id); onPicked() }.padding(start = Space.s12, top = Space.s2, bottom = Space.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(chat.displayTitle(), color = fg, fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$turns · ${relativeAge(chat.updated)}", color = dim, fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (running) AiSpinner(dim)
                    val label = stringResource(R.string.ai_delete_chat, chat.displayTitle())
                    Tooltip(label) {
                        IconButton(onClick = { deleting = chat }, modifier = Modifier.size(MinTouchTarget)) {
                            Icon(XnotesIcons.trash, label, Modifier.size(18.dp), tint = dim)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Confirms deleting [chat], worded as notes are: Move to Trash, or with Trash off a permanent delete.
 * Shared by the AI page and the floating card's history.
 */
@Composable
private fun AiTrashDialog(chat: AiChat, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val permanent = AiSessions.get(LocalContext.current).trashOff
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (permanent) R.string.delete_permanently_confirm else R.string.ai_delete_title)) },
        text = { Text(stringResource(if (permanent) R.string.ai_delete_forever_text else R.string.ai_delete_text, chat.displayTitle())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(if (permanent) R.string.delete else R.string.ai_move_to_trash)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

// --- the AI page ---------------------------------------------------------------------------

@Composable
private fun AiHistoryList(onShowSidebar: (() -> Unit)?, onOpenSource: (String) -> Unit, onSelected: () -> Unit) {
    val sessions = AiSessions.get(LocalContext.current)
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceError by remember { mutableStateOf("") }
    var sourceErrorChat by remember { mutableStateOf<String?>(null) }
    var relocating by remember { mutableStateOf<AiChat?>(null) }
    val relocatePicker=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val chat=relocating; relocating=null
        if(uri!=null && chat!=null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val storage=com.xnotes.storage.NotebookStorage.get(context)
                        val id=storage.identity(uri.toString())?.documentId
                        require(chat.sourceDocumentId.isBlank() || id==chat.sourceDocumentId) { context.getString(R.string.ai_source_wrong) }
                        if(!chat.sourceDocumentId.isBlank()) storage.ensure(uri.toString(),chat.source,"xnote")
                    }
                    sourceError=""; sourceErrorChat=null; onOpenSource(uri.toString())
                } catch(e:Exception) { sourceError=e.message ?: context.getString(R.string.ai_source_locate_failed); sourceErrorChat=chat.id }
            }
        }
    }
    fun openSource(chat: AiChat) = scope.launch {
        try {
            val uri = withContext(Dispatchers.IO) {
                val storage=com.xnotes.storage.NotebookStorage.get(context)
                val path=if(chat.sourceDocumentId.isBlank()) chat.sourceUri else storage.registry.resolve(chat.sourceDocumentId) ?: chat.sourceUri
                check(path.isNotBlank()) { context.getString(R.string.ai_source_unsaved) }
                check(chat.sourceDocumentId.isBlank() || storage.identity(path)?.documentId == chat.sourceDocumentId) { context.getString(R.string.ai_source_lost) }
                path
            }
            onOpenSource(uri)
        } catch(e: Exception) { sourceError = e.message ?: context.getString(R.string.ai_source_lost); sourceErrorChat=chat.id }
    }
    val query = sessions.historyQuery
    var deleting by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.s12)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val searchRoom = maxWidth - (if (onShowSidebar != null) 96.dp else 48.dp)
            // The open search takes the title's place rather than squeezing it into a wrapped column.
            var searching by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                onShowSidebar?.let { AiIconButton(XnotesIcons.menu, stringResource(R.string.show_sidebar), onClick = it) }
                if (searching) Spacer(Modifier.weight(1f))
                else Text(stringResource(R.string.ai_label), Modifier.weight(1f).padding(start = Space.s4), color = palette.text.toComposeColor(),
                    fontSize = TextSize.headline, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                ExplorerSearchField(query, { sessions.historyQuery = it }, expandedWidth = searchRoom.coerceIn(40.dp, 300.dp), onExpandedChange = { searching = it })
                AiIconButton(XnotesIcons.edit, stringResource(R.string.ai_new_chat), enabled = sessions.ready) { sessions.newChat(); onSelected() }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            if (sessions.storageError.isNotEmpty()) AiBanner(sessions.storageError, stringResource(R.string.ai_retry_save), sessions::retrySave)
            if (sourceError.isNotEmpty()) AiBanner(sourceError, if (sourceErrorChat != null) stringResource(R.string.ai_locate) else null) {
                sessions.chats[sourceErrorChat]?.let { relocating = it; relocatePicker.launch(arrayOf("*/*")) }
            }
        }
        val chats = sessions.chats.values.filter { it.turns.isNotEmpty() && it.matches(query) }.sortedByDescending { it.updated }
        if (!sessions.ready || chats.isEmpty()) Text(stringResource(if (!sessions.ready) R.string.ai_loading else if (query.isNotBlank()) R.string.ai_no_matches else R.string.ai_no_chats),
            Modifier.padding(Space.s12), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(top = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            items(chats.size, key = { chats[it].id }) { index ->
                val chat = chats[index]
                AiHistoryRow(chat, sessions, onOpen = { sessions.open(chat.id); onSelected() },
                    onSource = if (chat.sourceUri.isNotBlank() || chat.sourceDocumentId.isNotBlank()) ({ openSource(chat) }) else null,
                    onRename = { renaming = chat.id; title = chat.title }, onDelete = { deleting = chat.id })
            }
        }
    }
    deleting?.let { id -> sessions.chats[id]?.let { chat -> AiTrashDialog(chat, onConfirm = { sessions.delete(id); deleting = null }) { deleting = null } } }
    renaming?.let { id -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename)) }, text = { OutlinedTextField(title, { title = it.take(100) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { sessions.rename(id, title); renaming = null }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } }) }
}

/** Two quiet lines per chat; the active one is inverted and the rarer actions live in ⋮. */
@Composable
private fun AiHistoryRow(chat: AiChat, sessions: AiSessions, onOpen: () -> Unit, onSource: (() -> Unit)?, onRename: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    val selected = sessions.activeId == chat.id
    val running = sessions.isRunning(chat.id)
    val fg = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
    val dim = if (selected) fg.copy(alpha = 0.7f) else palette.textDim.toComposeColor()
    val last = chat.turns.lastOrNull()
    val status = when {
        running -> last?.progress?.ifBlank { null } ?: stringResource(R.string.ai_state_running)
        sessions.importStates[chat.id]?.loading == true -> stringResource(R.string.ai_attachment_preparing)
        chat.draft.text.isNotBlank() || chat.draft.images.isNotEmpty() || chat.draft.pdf != null -> stringResource(R.string.ai_draft)
        last != null && last.state != STATE_DONE -> stateLabel(last.state)
        else -> null
    }
    val meta = listOfNotNull(status, relativeAge(chat.updated), chat.source.ifBlank { null }).joinToString(" · ")
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier)
            .clickable(onClick = onOpen).padding(start = Space.s12, top = Space.s6, bottom = Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(chat.displayTitle(), color = fg, fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = dim, fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (running) AiSpinner(dim)
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(MinTouchTarget)) { Icon(XnotesIcons.more, stringResource(R.string.more), Modifier.size(18.dp), tint = dim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onSource?.let { DropdownMenuItem(text = { Text(stringResource(R.string.ai_open_source)) }, leadingIcon = { Icon(XnotesIcons.file, null, Modifier.size(18.dp)) }, onClick = { menu = false; it() }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(XnotesIcons.edit, null, Modifier.size(18.dp)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(XnotesIcons.trash, null, Modifier.size(18.dp)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun AiHistoryPane(sidebarOpen: Boolean, onShowSidebar: () -> Unit, onOpenSource: (String) -> Unit) {
    val sessions = AiSessions.get(LocalContext.current)
    val palette = LocalPalette.current
    var history by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sessions.ready) {
        if (sessions.ready && sessions.activeId == null) {
            val latest = sessions.chats.values.maxByOrNull { it.updated }
            if (latest == null) sessions.newChat() else sessions.open(latest.id)
        }
    }
    val showSidebar = if (sidebarOpen) null else onShowSidebar
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        androidx.activity.compose.BackHandler(enabled = !wide && history) { history = false }
        Row(Modifier.fillMaxSize()) {
            if (wide || history) Box(if (wide) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                AiHistoryList(showSidebar, onOpenSource, onSelected = { history = false })
            }
            // The chat is a card on the grey backdrop, so list and conversation read as two levels.
            if (wide || !history) Box(
                Modifier.weight(1f).fillMaxHeight().padding(if (wide) PaddingValues(top = Space.s8, end = Space.s8, bottom = Space.s8) else PaddingValues(0.dp))
                    .clip(if (wide) MaterialTheme.shapes.large else MaterialTheme.shapes.extraSmall)
                    .background(palette.menuBg.toComposeColor())
                    .then(if (wide) Modifier.border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large) else Modifier),
            ) {
                val chat = sessions.activeId?.let { sessions.chats[it] }
                if (!sessions.ready) Text(stringResource(R.string.ai_loading), Modifier.padding(Space.s16), color = palette.textDim.toComposeColor())
                else if (chat == null) EmptyPane(stringResource(R.string.ai_pick_chat)) {
                    TextButton(onClick = { sessions.newChat() }) { Text(stringResource(R.string.ai_new_chat)) }
                    if (!wide) TextButton(onClick = { history = true }) { Text(stringResource(R.string.ai_history)) }
                } else key(chat.id) {
                    AskAiWindow(sessions, chat, floating = false,
                        onHistory = if (wide) null else ({ history = true }),
                        onShowSidebar = if (wide) null else showSidebar)
                }
            }
        }
    }
}
