package com.xnotes

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.xnotes.core.tools.forDocumentControls
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xnotes.R
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.ui.Editor
import com.xnotes.ui.Toolbar
import com.xnotes.ui.ToolbarAround
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

/** Minimum time the launch loader stays up, so its animation is briefly seen even
 *  when the session restores instantly. */
private const val MIN_LOADER_MS = 600L

/** How long the maths waits for the font bytes it measures with before going without. */
private const val MATH_FONT_WAIT_MS = 1500L

/** Whether the display has a camera cutout (notch/hole-punch). False below API 29, which has no
 *  cutout API; such devices fall back to fullscreen by default. Also false for a context with no
 *  display of its own, which cannot answer the question. */
internal fun deviceHasDisplayCutout(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val display = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display
        else @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }.getOrNull()
    return display?.cutout != null
}

/** The whole screen's shorter side in dp, the same in any orientation, window or split; 0 if unknown. */
internal fun deviceShortSideDp(context: Context): Double {
    val metrics = runCatching {
        DisplayMetrics().also {
            @Suppress("DEPRECATION")
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
    }.getOrNull() ?: return 0.0
    return minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density.toDouble()
}

/** The standard touch action an old One UI S-Pen-button code stands in for, or -1 for anything else. */
private fun standardPenAction(action: Int): Int = when (action) {
    211 -> MotionEvent.ACTION_DOWN
    212 -> MotionEvent.ACTION_UP
    213 -> MotionEvent.ACTION_MOVE
    else -> -1
}

class MainActivity : ComponentActivity() {

    // The editor owns the fullscreen state (persisted preference, default depends on the display
    // cutout); this activity just applies it to the window. Fullscreen draws edge to edge and lets
    // the swipe-in transient bars overlay (no resize), non-fullscreen insets under the bars.
    private var editor: Editor? = null
    // A PDF or markdown file handed to us by another app ("Open with" / Share); consumed once the editor is ready.
    private var pendingImport by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.xnotes.platform.FontCatalog.init(this)
        com.xnotes.platform.TemplateLibrary.init(this)
        com.xnotes.ui.AudioPlayback.init(this)
        com.xnotes.ui.AudioRecording.init(this)
        com.xnotes.ui.installAiText(this) // before any AiSessions/AI backend message is produced
        com.xnotes.ui.installStorageText(this) // before any storage error message is produced
        setTheme(com.xnotes.R.style.Theme_Xnotes) // leave the dark launch/splash theme behind
        applyFullscreen(!deviceHasDisplayCutout(this)) // provisional; reconciled once the editor loads prefs
        // The intent that started the task comes back whenever Android rebuilds it, from a saved state
        // or from Recents after the process died or the app was updated; it was handled the first time.
        val rebuilt = savedInstanceState != null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!rebuilt) pendingImport = importUri(intent)
        setContent {
            val context = LocalContext.current
            val ed = remember { Editor(context).also { editor = it } }
            LaunchedEffect(ed.fullscreen) { applyFullscreen(ed.fullscreen) }
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val start = android.os.SystemClock.uptimeMillis()
                ed.restoreSession() // heavy load off-thread; loader animates meanwhile
                ed.restoreSplitWorkspace()
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                if (elapsed < MIN_LOADER_MS) kotlinx.coroutines.delay(MIN_LOADER_MS - elapsed)
                ready = true
                ed.prewarmBackstage() // warm recents/explorer caches so the first backstage open is instant
            }
            XnotesTheme(ed.palette, ed.cornerStyle) {
                CompositionLocalProvider(com.xnotes.ui.LocalToolbarLook provides ed.toolbarLook) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (ready) EditorScreen(
                            ed,
                            fullscreen = ed.fullscreen,
                            onToggleFullscreen = ed::toggleFullscreen,
                            importUri = pendingImport,
                            onImportConsumed = { pendingImport = null },
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !ready,
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(280)),
                        ) {
                            com.xnotes.ui.XnotesLoader()
                        }
                    }
                }
            }
        }
    }

    // Stylus side-button keys (Bluetooth/USI pens report the button only this way) are caught here,
    // before Compose focus routing, so the controller sees both press and release regardless of
    // which view holds focus. Other keys fall through to the normal dispatch.
    var penKeyRecorder: ((KeyEvent) -> Boolean)? = null
    var cancelPenRecording: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (penKeyRecorder?.invoke(event) == true) return true
        val workspace = editor
        // Release every pane: focus can have moved since the original press.
        if (event.action == KeyEvent.ACTION_UP) {
            var handled = false
            workspace?.openPanes?.forEach { if (it.onRecordedPenKey(event)) handled = true }
            if (handled) return true
        } else if (workspace?.openPanes?.contains(workspace.active) == true &&
            workspace.active.onRecordedPenKey(event)) return true
        // The pen writes in whichever pane has the focus, so its button keys go to that pane too.
        if (editor?.active?.onStylusButtonKey(event) == true) return true
        if (editor?.active?.onVolumeKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    // Older Samsung builds (seen on a Tab S6 Lite, Android 13; gone by Android 15) tag a stylus
    // stroke made with the S-Pen button held with proprietary action codes instead of DOWN/MOVE/UP,
    // so the view tree never opens a touch target and the whole stroke is dropped. Rewrite them
    // here, the last point they are intact, and dispatch normally. A no-op on every other device.
    private val workspaceFourFingerTap by lazy { com.xnotes.pen.FourFingerTap(16f * resources.displayMetrics.density) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val workspace = editor
        when (workspaceFourFingerTap.track(ev, workspace != null && workspace.openPanes.isNotEmpty() && workspace.openPanes.none { it.stylusInContact })) {
            com.xnotes.pen.FourFingerTap.Result.CAPTURE -> {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                return true
            }
            com.xnotes.pen.FourFingerTap.Result.TRIGGER -> {
                workspace?.active?.let { if (it.canvasOpen) it.infinite.toggleDebug() else it.view.toggleDebug() }
                return true
            }
            com.xnotes.pen.FourFingerTap.Result.CONSUME -> return true
            com.xnotes.pen.FourFingerTap.Result.PASS -> Unit
        }
        StylusButtonLatch.clicks.onTouch(ev) // a side-button press with the pen tip down is a hold, never a click
        val standard = standardPenAction(ev.actionMasked)
        if (standard < 0 || ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.dispatchTouchEvent(ev)
        }
        val rewritten = MotionEvent.obtain(ev)
        rewritten.action = standard
        try {
            return super.dispatchTouchEvent(rewritten)
        } finally {
            rewritten.recycle()
        }
    }

    // A file arriving while we're already running: singleTask reuses this instance via onNewIntent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importUri(intent)?.let { pendingImport = it }
    }

    // The PDF or markdown uri carried by an inbound VIEW ("Open with") or SEND ("Share") intent, else null.
    private fun importUri(intent: Intent?): Uri? = when (intent?.action) {
        // Only the import filters take VIEW, but a task started by another build's filters keeps its intent.
        Intent.ACTION_VIEW -> intent.data?.takeIf { (intent.type ?: runCatching { contentResolver.getType(it) }.getOrNull()) in IMPORT_TYPES }
        Intent.ACTION_SEND ->
            if (intent.type in IMPORT_TYPES)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else null
        else -> null
    }

    // uiMode is in configChanges, so a system dark/light flip lands here instead of recreating us.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        editor?.onSystemDarkModeChanged(night == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            cancelPenRecording?.invoke()
            editor?.openPanes?.forEach { it.resetPenButtons() }
            StylusButtonLatch.releaseKey()
        } // a held pen key's key-up may not come back to this window
        if (hasFocus && editor?.fullscreen == true) applyFullscreen(true) // re-hide transient bars after they swipe in
    }

    override fun onPause() {
        super.onPause()
        editor?.persist()
    }

    // Playback never goes on with xnotes off screen; a split window that only lost focus is still on it.
    override fun onStop() {
        super.onStop()
        com.xnotes.ui.AudioPlayback.pause()
    }

    @Suppress("DEPRECATION") // RUNNING_* still arrive below Android 14
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The canvas keeps its rendered pages, so PDFium's pages and glyph caches can go while the
        // UI is hidden; they load again when something new is drawn.
        if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            com.xnotes.platform.PdfiumDocument.trimAll()
        }
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun EditorScreen(
    editor: Editor,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    importUri: Uri? = null,
    onImportConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // The LaTeX renderer resolves its fonts through the composition, so this is the
    // only place it can be built; the flow reaches it through MathRendering after.
    val mathDensity = LocalDensity.current
    // A formula is set one glyph run at a time, and runs like "x" and "=" recur across a note, so
    // the text measurer keeps far more layouts than its default eight.
    val mathText = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 256)
    val mathFonts = com.xnotes.platform.LatexInternals.defaultFonts()
    LaunchedEffect(mathDensity, mathText, mathFonts) {
        // The fonts come a moment before the bytes that measure them exactly; setting formulas between would set them twice.
        if (mathFonts.mainBytes == null) kotlinx.coroutines.delay(MATH_FONT_WAIT_MS)
        com.xnotes.platform.MathRendering.install(mathDensity, mathText, mathFonts)
        editor.refreshFlowMath()
    }
    // Backstage is the root of the stack; the editor is pushed on top only when a note is open
    // (editor.noteOpen). Every launch starts on backstage.
    var backstageView by remember { mutableStateOf(com.xnotes.ui.BackstageView.HOME) }
    var showShareChooser by remember { mutableStateOf(false) }
    var guardAction by remember { mutableStateOf<GuardRequest?>(null) }
    var pendingAfterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    // The pane an image was picked for, and where a long-press menu asked it to land. Held together
    // so the picker's result lands in the pane that opened it, whatever the focus does meanwhile.
    var pendingInsert by remember { mutableStateOf<PendingInsert?>(null) }
    // The same, for the infinite canvas.
    var pendingCanvasInsert by remember { mutableStateOf<PendingInsert?>(null) }
    // The pane and page a side-panel "Insert PDF" picked a file for.
    var pendingPdfInsert by remember { mutableStateOf<PendingPdfInsert?>(null) }
    var pendingShareUri by remember { mutableStateOf<String?>(null) }
    var pendingSaveCopyUri by remember { mutableStateOf<String?>(null) }
    var paneHomes by remember { mutableStateOf<Map<com.xnotes.ui.Pane, String?>>(emptyMap()) }
    var homeFocus by remember { mutableStateOf<com.xnotes.ui.Pane?>(null) }
    var mainMenu by remember { mutableStateOf(false) }
    var parkedDocuments by remember { mutableStateOf<Pair<Any?, Any?>?>(null) }
    val documentsNow = (if (editor.canvasOpen) editor.infinite.document else editor.state.document) to
        editor.secondary?.let { if (it.canvasOpen) it.infinite.document else it.state.document }
    LaunchedEffect(documentsNow) { if (mainMenu && parkedDocuments != documentsNow) mainMenu = false }

    // A finished PDF render awaiting a SAF "Save as" destination (open-note / file / pages export).
    var pendingExportTemp by remember { mutableStateOf<java.io.File?>(null) }
    // In-flight PDF render; drives the progress dialog, null hides it.
    var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
    // The running export's coroutine and its own cancel flag. Each export gets a fresh flag so a new
    // export can abort the previous one (set its flag, then join it) without un-cancelling itself.
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exportCancel by remember { mutableStateOf<java.util.concurrent.atomic.AtomicBoolean?>(null) }
    // The pane whose page indices await a SAF "Save as" destination (side-panel page export).
    var pendingExportPages by remember { mutableStateOf<PendingPages?>(null) }
    val scope = rememberCoroutineScope()
    val storageProblemPane = listOfNotNull(editor, editor.secondary).firstOrNull { it.storageProblemUri != null }
    var relinkPane by remember { mutableStateOf<com.xnotes.ui.Editor?>(null) }
    var relinking by remember { mutableStateOf(false) }
    val relinkPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pane = relinkPane; relinkPane = null
        if (uri != null && pane != null) { relinking = true; scope.launch { try { pane.relinkMissingPdf(uri.toString()) } finally { relinking = false } } }
    }
    storageProblemPane?.let { pane ->
        var working by remember(pane) { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!working && !relinking) pane.storageProblemUri = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.recovery_title)) },
            text = { androidx.compose.material3.Text(pane.storageProblemText) },
            confirmButton = {
                androidx.compose.foundation.layout.Column {
                    if (pane.storageMissingPdf != null) androidx.compose.material3.TextButton(enabled = !working && !relinking, onClick = {
                        relinkPane = pane; relinkPdfLauncher.launch(arrayOf("application/pdf", "application/octet-stream", "application/zip"))
                    }) { androidx.compose.material3.Text(stringResource(R.string.recovery_relink_pdf)) }
                    if (pane.storageMissingPdf != null) androidx.compose.material3.TextButton(enabled = !working && !relinking, onClick = {
                        working = true; scope.launch { pane.openInkWithoutPdf(); working = false }
                    }) { androidx.compose.material3.Text(stringResource(R.string.recovery_open_ink_only)) }
                    if (pane.storageCanRecover) androidx.compose.material3.TextButton(enabled = !working && !relinking, onClick = {
                        working = true; scope.launch { pane.recoverStorageProblem(restore = true); working = false }
                    }) { androidx.compose.material3.Text(stringResource(R.string.recovery_restore_backup)) }
                    if (pane.storageDuplicate) androidx.compose.material3.TextButton(enabled = !working && !relinking, onClick = {
                        working = true; scope.launch { pane.recoverStorageProblem(copy = true); working = false }
                    }) { androidx.compose.material3.Text(stringResource(R.string.recovery_make_copy_independent)) }
                    if (pane.storageCanRecover) androidx.compose.material3.TextButton(enabled = !working && !relinking, onClick = {
                        working = true; scope.launch { pane.recoverStorageProblem(); working = false }
                    }) { androidx.compose.material3.Text(stringResource(R.string.recovery_keep_current)) }
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(enabled = !working && !relinking, onClick = { pane.storageProblemUri = null }) { androidx.compose.material3.Text(stringResource(R.string.recovery_later)) } },
        )
    }
    val resolver = context.contentResolver
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // Which pane a "Save as" writes: the focused one at the moment the picker opened.
    var savePane by remember { mutableStateOf(editor) }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            val name = displayNameOf(resolver, it)
            runCatching { resolver.openOutputStream(it, "wt")?.use { o -> savePane.save(o, it.toString(), name) } }
                .onSuccess { val p = pendingAfterSave; pendingAfterSave = null; p?.invoke() }
                .onFailure { editor.message = context.getString(R.string.err_save_note); pendingAfterSave = null }
        }
    }

    // "Import PDF" takes one file or many. One keeps the old flow: remember the pick and show the name
    // dialog at once, with the (possibly large) copy happening at Save under the "Importing PDF…" loader.
    // Many skips naming altogether (each note takes its source file's name) and the explorer imports
    // them one at a time, so the picked list never becomes a list of open PDFs.
    var importPdfPane by remember { mutableStateOf<Editor?>(null) }
    val importPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val destination = importPdfPane ?: editor
        val embedded = importPdfPane != null
        importPdfPane = null
        fun stemOf(u: android.net.Uri) = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, u) ?: "Document")
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> destination.requestImport(stemOf(uris[0]), uris[0].toString())
            else -> destination.requestImports(uris.map { com.xnotes.ui.PendingImport(stemOf(it), it.toString()) })
        }
        if (uris.isNotEmpty() && !embedded) {
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHomeAll() // land on backstage to name the single pick, or to run the batch
        }
    }
    // A PDF "Save as" destination. The note is already rendered into [pendingExportTemp]
    // (off-thread, behind the progress dialog), so the picker just chooses where to copy it —
    // shared by the open-note export, the explorer-file export, and side-panel page saves.
    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val temp = pendingExportTemp; pendingExportTemp = null
        if (temp != null) {
            if (uri != null) {
                val ok = runCatching { resolver.openOutputStream(uri)?.use { o -> temp.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false)
                editor.message = if (ok) context.getString(R.string.exported_pdf) else context.getString(R.string.err_export_pdf)
            }
            temp.delete() // discard the temp whether saved or the picker was dismissed
        }
    }

    // Explorer picks waiting on their destination, chosen first so a big zip streams straight to it rather than through the cache.
    var pendingPdfs by remember { mutableStateOf<PendingPdfs?>(null) }
    fun exportPicked(uri: Uri?) {
        val pending = pendingPdfs; pendingPdfs = null
        val root = editor.browseRoot
        if (uri == null || pending == null || root == null) return
        pending.onStarted()
        scope.launch {
            val r = editor.exportPdfsAsync(root, pending.picked, uri.toString())
            when {
                r.cancelled -> null
                r.found == 0 -> context.getString(R.string.no_notes_to_export)
                !r.kept -> context.getString(R.string.err_export_pdf)
                com.xnotes.ui.lonePdf(pending.picked) != null -> context.getString(R.string.exported_pdf)
                r.written < r.found -> context.getString(R.string.exported_some_pdfs, r.written, r.found)
                else -> context.resources.getQuantityString(R.plurals.exported_pdfs, r.written, r.written)
            }?.let { editor.message = it }
        }
    }
    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { exportPicked(it) }
    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { exportPicked(it) }

    // Save a copy of an explorer file (a note or a canvas) elsewhere.
    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val src = pendingSaveCopyUri; pendingSaveCopyUri = null
        if (uri != null && src != null) {
            scope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { checkNotNull(resolver.openOutputStream(uri)).use { o -> editor.copyFileTo(src, o, newIdentity = true) } }.isSuccess }
                if(!ok) editor.message = context.getString(R.string.err_save_copy)
            }
        }
    }

    // Save a single selected page as a PNG.
    val savePageImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val pending = pendingExportPages; pendingExportPages = null
        val index = pending?.pages?.firstOrNull()
        if (uri != null && pending != null && index != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = runCatching {
                    val png = pending.editor.pageImagePng(index) ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { it.write(png) } != null
                }.getOrDefault(false)
                if (!ok) editor.message = context.getString(R.string.err_save_image)
            }
        }
    }

    // Save several selected pages as individual PNGs into a folder the user picks.
    val savePagesImagesTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val pending = pendingExportPages; pendingExportPages = null
        if (treeUri != null && pending != null && pending.pages.isNotEmpty()) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val stem = pending.editor.title
                val saved = runCatching {
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    var n = 0
                    for (index in pending.pages) {
                        val png = pending.editor.pageImagePng(index) ?: continue
                        val name = "%s-p%02d.png".format(stem, index + 1)
                        val file = android.provider.DocumentsContract.createDocument(resolver, parent, "image/png", name) ?: continue
                        resolver.openOutputStream(file)?.use { it.write(png) }
                        n++
                    }
                    n
                }.getOrDefault(0)
                editor.message = if (saved > 0) context.resources.getQuantityString(R.plurals.saved_images, saved, saved) else context.getString(R.string.err_save_images)
            }
        }
    }

    val insertImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingInsert; pendingInsert = null
        if (uri != null && pending != null) {
            runCatching {
                resolver.openInputStream(uri)?.use { s -> pending.editor.insertImageAt(s.readBytes(), pending.at) }
            }.onFailure { editor.message = context.getString(R.string.err_read_image) }
        }
    }

    // Recording asks for the microphone the first time, and on Android 13 and later for notifications
    // too, since the recording shows one while xnotes is off screen. Refusing notifications still records.
    var pendingRecordPane by remember { mutableStateOf<Editor?>(null) }
    // Notifications are asked for once a run when only they are missing; the system stops asking after two refusals anyway.
    var askedNotifications by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    val recordPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val pane = pendingRecordPane
        pendingRecordPane = null
        if (pane == null) return@rememberLauncherForActivityResult
        if (grants[android.Manifest.permission.RECORD_AUDIO] == true) {
            pane.startRecording()
        } else {
            // Once Android stops asking, only Settings can grant it, so the message offers the way there.
            val asks = activity?.let { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, android.Manifest.permission.RECORD_AUDIO) } == true
            val settings = context.getString(R.string.mic_settings) to {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Unit
            }
            pane.say(context.getString(R.string.err_mic_needed), if (asks) null else settings)
        }
    }

    // A recording saved out of the note: its file copied as it is, off the main thread.
    var pendingSaveAudio by remember { mutableStateOf<Pair<Editor, com.xnotes.core.model.AudioItem>?>(null) }
    val saveAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingSaveAudio
        pendingSaveAudio = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        val (pane, item) = pending
        val file = item.audio.file
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
            }
            pane.say(context.getString(if (ok) R.string.audio_saved else R.string.err_save_audio))
        }
    }

    // An audio file to attach, for the pane that asked; the editor copies it off the main thread.
    var pendingAudioPane by remember { mutableStateOf<Editor?>(null) }
    val attachAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pane = pendingAudioPane
        pendingAudioPane = null
        if (uri != null && pane != null) pane.attachAudio(uri)
    }

    // A PDF picked from a page's menu in the side panel; its pages go in right after that page.
    val insertPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingPdfInsert; pendingPdfInsert = null
        if (uri != null && pending != null) scope.launch { pending.editor.insertPdfAfter(pending.after, uri.toString()) }
    }

    // Images picked for the sticker library (multi-select), stored on disk by the editor.
    val addStickersLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { resolver.openInputStream(uri)?.use { s -> editor.addSticker(s.readBytes()) } }
                .onFailure { editor.message = context.getString(R.string.err_read_image) }
        }
    }

    // A user Helix code theme (.toml), parsed + stored by the editor.
    val importCodeThemeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importCodeTheme(it, displayNameOf(resolver, uri)) }
                ?: run { editor.message = context.getString(R.string.err_read_file) }
        }
    }

    // A user page template (.xtemplate, plain or packaged), checked and stored in the template library.
    val importTemplateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) editor.importTemplate(uri)
    }

    // A picture made into a one-image cover or page template, whichever the New note screen asked for.
    var pendingTemplateKind by remember { mutableStateOf(com.xnotes.core.template.TemplateKind.PAGE) }
    val templateFromImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) editor.templateFromImage(uri, pendingTemplateKind)
    }

    // A user font (.ttf/.otf), stored + registered by the editor.
    val importFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importFont(it, displayNameOf(resolver, uri)) }
                ?: run { editor.message = context.getString(R.string.err_read_file) }
        }
    }

    // Grant a folder for the in-app explorer (a one-time system folder picker).
    var browsePickerPane by remember { mutableStateOf<Editor?>(null) }
    val pickRootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val destination = browsePickerPane ?: editor
        browsePickerPane = null
        uri?.let {
            if (runCatching { resolver.takePersistableUriPermission(it, rwFlags) }.isSuccess) {
                scope.launch { destination.switchWorkspace(it.toString()) }
            } else {
                editor.message = context.getString(R.string.workspace_unavailable)
            }
        }
    }

    /** Open the "Save as" picker for [target], remembering which pane its result belongs to. */
    fun launchSaveAs(target: Editor) {
        savePane = target.documentOwner
        createLauncher.launch("${target.title}.xnote")
    }

    fun saveOrPrompt() {
        val target = editor.active
        val uri = target.currentUri
        if (uri == null) { launchSaveAs(target); return }
        // The write is off the main thread now, so the Save-As fallback fires from its callback.
        target.saveToThen(uri) { ok -> if (!ok) launchSaveAs(target) }
    }

    // The prompt is about one pane's note, so it asks about — and saves — the pane being acted on.
    fun guarded(target: Editor = editor.active, onDiscard: () -> Unit = {}, action: () -> Unit) {
        if (!target.noteOpen) { action(); return }
        if (!target.canvasOpen) { target.commitText(); target.flowText.endSession() }
        if (target.documentOwner !== target) { guarded(target.documentOwner, onDiscard, action); return }
        when {
            // A canvas keeps its own autosave, and dirty/autosaveUri describe the paged buffer
            // underneath it, so asking about those here would prompt over a note nobody is looking at.
            target.canvasOpen -> action()
            target.autosaveUri != null -> action() // autosaved notes are flushed on doc-swap; no prompt
            target.dirty -> guardAction = GuardRequest(target, action, onDiscard)
            else -> action()
        }
    }

    /** Run [action] once every open pane has settled its unsaved changes, prompting one at a time. */
    fun guardedAll(action: (Set<Editor>) -> Unit) {
        editor.openPanes.filter { !it.canvasOpen }.forEach { it.commitText(); it.flowText.endSession() }
        val panes = editor.openPanes.map { it.documentOwner }.distinct()
        // This set is local to one guard sequence. Only an actual Discard button click adds an
        // editor; Save, Cancel and unprompted paths never authorize dropping any dirty document.
        val explicitlyDiscarded = mutableSetOf<Editor>()
        fun step(i: Int) {
            if (i >= panes.size) action(explicitlyDiscarded.toSet())
            else guarded(panes[i], onDiscard = { explicitlyDiscarded.add(panes[i]) }) { step(i + 1) }
        }
        step(0)
    }

    // A settings backup written where the user picks, or read and held for the go-ahead to apply it.
    val exportSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri, "wt")?.use { editor.exportSettings(it) } != null }.getOrDefault(false)
            }
            editor.message = context.getString(if (ok) R.string.settings_exported else R.string.err_export_settings)
        }
    }
    var pendingBackup by remember { mutableStateOf<Editor.SettingsBackup?>(null) }
    val importSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val backup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { editor.readSettingsBackup(it) } }.getOrNull()
            }
            if (backup == null) editor.message = context.getString(R.string.err_import_settings) else pendingBackup = backup
        }
    }

    // A photo the camera app is taking, into a cache file handed to it, for the pane that asked.
    var pendingPhoto by remember { mutableStateOf<Pair<Editor, java.io.File>?>(null) }
    val takePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val (pane, file) = pendingPhoto ?: return@rememberLauncherForActivityResult
        pendingPhoto = null
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (if (taken) runCatching { file.readBytes() }.getOrNull() else null).also { file.delete() }
            }
            if (bytes == null || bytes.isEmpty()) return@launch
            if (pane.canvasOpen) pane.infinite.insertImage(bytes) else pane.insertImageAt(bytes, null)
        }
    }

    fun takePhoto(pane: Editor) {
        val file = runCatching {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        }.getOrNull() ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingPhoto = pane to file
        try {
            takePhotoLauncher.launch(uri)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingPhoto = null
            pane.message = context.getString(R.string.err_no_camera)
        }
    }

    // An image picked for the infinite canvas: read the bytes and hand them straight over.
    val insertCanvasImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pending = pendingCanvasInsert; pendingCanvasInsert = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { input -> input.readBytes() } }.getOrNull()
            }
            if (bytes != null) pending.editor.infinite.insertImage(bytes, pending.at)
        }
    }

    /**
     * The kind of the stored document at [uriStr], read from its file name, falling back to a note
     * for anything unrecognizable. Every place that has to name a copy of that file goes through
     * here: spelling an extension at the call site is how an `.xcanvas` ended up shared as `.xnote`.
     */
    fun kindOf(uriStr: String): com.xnotes.core.util.DocumentKind =
        com.xnotes.core.util.DocumentKind.ofName(displayNameOf(resolver, Uri.parse(uriStr)).orEmpty())
            ?: com.xnotes.core.util.DocumentKind.NOTE

    /** Read the file at [uriStr] into [target]. The extension picks the surface: the two document
     *  types share the explorer but not much else. Reads off-thread behind the "Opening note…"
     *  spinner so a big embedded PDF doesn't freeze the UI. */
    suspend fun openInto(requested: Editor, uriStr: String): Editor? {
        if (requested.noteOpen && requested.visibleDocumentUri?.let { requested.isSameDocument(it, uriStr) } == true) return requested
        // Keep a shared document's save owner alive. Replace its borrowed view instead, then
        // exchange visual slots so the requested pane still opens the new file in place.
        val target = if (requested.isSharedView && requested.documentOwner === requested)
            requested.sibling ?: requested else requested
        // A canvas never shares the screen: refuse it while the other pane holds a document or its home.
        val paired = target.sibling?.takeIf { it.noteOpen || it.pane in paneHomes }
        if (paired != null && (paired.canvasOpen || kindOf(uriStr) == com.xnotes.core.util.DocumentKind.CANVAS)) {
            editor.message = context.getString(R.string.split_canvas_unsupported)
            return null
        }
        if (target.noteOpen && target.visibleDocumentUri != null && !target.flushSplitDocument()) return null
        if (!target.saveCanvasBeforeReplacement()) return null
        val retainedPosition = if (target !== requested) target.readingPosition() else null
        val other = target.sibling
        if (other?.noteOpen == true && !other.canvasOpen &&
            other.currentUri?.let { target.isSameDocument(it, uriStr) } == true) {
            target.openSharedView(other)
        } else {
            val name = displayNameOf(resolver, Uri.parse(uriStr))
            if (com.xnotes.core.util.DocumentKind.ofName(name ?: "") == com.xnotes.core.util.DocumentKind.CANVAS) {
                target.openCanvasAsync(uriStr, name)
            } else {
                target.openAsync(uriStr, name)
            }
        }
        if (retainedPosition != null && target.noteOpen &&
            target.visibleDocumentUri?.let { target.isSameDocument(it, uriStr) } == true) {
            requested.restoreReadingPosition(retainedPosition)
            editor.splitReversed = !editor.splitReversed
            editor.focusPane(target.pane)
        }
        return target.takeIf { it.noteOpen && it.visibleDocumentUri?.let { uri -> it.isSameDocument(uri, uriStr) } == true }
    }

    fun closePaneHome(pane: Editor) {
        if (editor.openPanes.any { it.stylusInContact }) return
        val close = {
            // Closing a shared owner retains that editor and closes the borrowed view instead.
            val retained = if (pane.isSharedView && pane.documentOwner === pane) pane.sibling else null
            paneHomes = com.xnotes.ui.closePaneHomeState(paneHomes, pane.pane, retained?.pane)
            if (retained != null) editor.splitReversed = !editor.splitReversed
            homeFocus = paneHomes.keys.firstOrNull()
            if (pane.noteOpen) editor.closePane(pane)
            else if (pane.pane == com.xnotes.ui.Pane.SECONDARY) editor.abandonSecondary()
        }
        if (pane.isSharedView) close() else guarded(pane) { close() }
    }

    fun closeEditorPane(pane: Editor) {
        if (editor.openPanes.any { it.stylusInContact }) return
        val other = if (pane === editor) editor.secondary else editor
        if (other != null && (other.noteOpen || other.pane in paneHomes)) {
            // Park the document behind its file browser. Reuse the existing guarded replacement
            // path when a file is picked; only closing this browser removes the pane itself.
            paneHomes = paneHomes + (pane.pane to pane.visibleDocumentUri)
            homeFocus = pane.pane
        } else {
            val close = { homeFocus = paneHomes.keys.firstOrNull(); editor.closePane(pane) }
            if (pane.isSharedView) close() else guarded(pane) { close() }
        }
    }

    fun openTreeFile(uriStr: String, explicitlyDiscarded: Set<Editor> = emptySet()) {
        scope.launch {
            val kind = com.xnotes.core.util.DocumentKind.ofName(displayNameOf(resolver, Uri.parse(uriStr)) ?: "")
            if (kind == com.xnotes.core.util.DocumentKind.SPLIT) {
                if (editor.openSplitFile(uriStr, explicitlyDiscarded)) { paneHomes = emptyMap(); mainMenu = false }
            } else if (editor.openSingleFromHome(uriStr, displayNameOf(resolver, Uri.parse(uriStr)), explicitlyDiscarded)) { paneHomes = emptyMap(); homeFocus = null; mainMenu = false }
        }
    }

    /** Open two picked files together, one per pane, and start the split focused on the first. */
    fun openSplit(firstUri: String, secondUri: String) {
        if (kindOf(firstUri) == com.xnotes.core.util.DocumentKind.CANVAS || kindOf(secondUri) == com.xnotes.core.util.DocumentKind.CANVAS) {
            editor.message = context.getString(R.string.split_canvas_unsupported)
            return
        }
        editor.secondaryPane()
        scope.launch {
            // Either picked file may already be open in the other pane: settle both before reading.
            for (current in editor.openPanes.map { it.documentOwner }.distinct()) {
                if (current.visibleDocumentUri != null && !current.flushSplitDocument()) return@launch
            }
            val first = openInto(editor, firstUri) ?: return@launch
            val second = first.sibling ?: return@launch
            val opened = openInto(second, secondUri)
            if (opened == null) editor.message = context.getString(R.string.err_open_second)
            editor.splitRatio = 0.5f
            editor.splitReversed = first !== editor
            editor.focusPane(first.pane)
            paneHomes = emptyMap()
            mainMenu = false
        }
    }

    fun stemOf(uriStr: String): String =
        com.xnotes.core.util.Paths.stem(displayNameOf(resolver, Uri.parse(uriStr)) ?: "Note")

    /** What the export dialog counts for the stored document at [uriStr]: a note's pages, a canvas's items. */
    fun countingOf(uriStr: String): String = ExportProgress.countingFor(kindOf(uriStr))

    // Render a PDF off the main thread into a temp file behind a cancellable progress dialog;
    // only once it finishes does [onReady] run — opening the SAF picker or a share sheet.
    // Dismissing the dialog flips this export's cancel flag, which aborts the page loop AND the
    // write stream, so the half-written temp is discarded. A fresh export first aborts and joins any
    // previous one, so they never overlap. [shareDir] picks the cache subdir: FileProvider only
    // exposes cache/share, so shares render there; plain "save" exports use cache/export.
    fun runPdfExport(
        stem: String,
        shareDir: Boolean,
        counting: String = "page",
        render: (java.io.OutputStream, (Int, Int) -> Unit, () -> Boolean) -> Unit,
        onReady: (java.io.File) -> Unit,
    ) {
        val prevJob = exportJob
        val prevCancel = exportCancel
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        exportCancel = cancel // the dialog's Cancel flips THIS export's flag
        exportProgress = ExportProgress(0, 0, counting) // show the dialog at once; the render fills in the real total
        exportJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Abort any still-running previous export (its write stream throws on the next buffer) and
            // wait for it to fully unwind before we touch the shared temp dir — no overlapping saves.
            prevCancel?.set(true)
            prevJob?.join()
            val dir = java.io.File(context.cacheDir, if (shareDir) "share" else "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the file this export produces
            val temp = java.io.File(dir, "$stem.pdf")
            val ok = runCatching {
                java.io.FileOutputStream(temp).use { fo ->
                    // Abort the (otherwise uninterruptible) PdfBox save the moment Cancel is tapped.
                    val o = com.xnotes.platform.CancellableOutputStream(fo) { cancel.get() }
                    render(o, { done, total -> if (!cancel.get()) exportProgress = ExportProgress(done, total, counting) }, { cancel.get() })
                }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (exportCancel === cancel) exportProgress = null // only the latest export owns the dialog
                when {
                    cancel.get() -> temp.delete()
                    ok -> onReady(temp)
                    else -> { temp.delete(); if (exportCancel === cancel) editor.message = context.getString(R.string.err_export_pdf) }
                }
            }
        }
    }

    fun launchShare(file: java.io.File, stem: String, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_named, stem)))
    }

    fun shareFile(uriStr: String, asPdf: Boolean) {
        val stem = stemOf(uriStr)
        if (asPdf) {
            // Render with progress, then share the finished PDF (writes into cache/share for FileProvider).
            runPdfExport(stem, shareDir = true, counting = countingOf(uriStr),
                render = { o, prog, cancel -> editor.exportFileToPdf(uriStr, o, prog, cancel) },
                onReady = { temp -> runCatching { launchShare(temp, stem, "application/pdf") }.onFailure { editor.message = context.getString(R.string.err_share_note) } })
        } else {
            // A portable bundle can embed a large shared PDF. Keep packaging off the UI thread.
            scope.launch {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        val target = java.io.File(dir, "$stem${kindOf(uriStr).suffix}")
                        try {
                            java.io.FileOutputStream(target).use { editor.copyFileTo(uriStr, it) }
                            target
                        } catch (e: Exception) { target.delete(); throw e }
                    }.getOrNull()
                }
                if (file == null) editor.message = context.getString(R.string.err_share_note)
                else runCatching { launchShare(file, stem, "application/octet-stream") }
                    .onFailure { editor.message = context.getString(R.string.err_share_note) }
            }
        }
    }

    // Share several stored files at once, each as the bundle it is (no PDF render for a batch).
    fun shareFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val intent = runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val auth = "${context.packageName}.fileprovider"
                val taken = HashSet<String>()
                val out = ArrayList<Uri>()
                for (u in uris) {
                    val stem = stemOf(u)
                    val suffix = kindOf(u).suffix
                    var name = "$stem$suffix"
                    var n = 2
                    while (!taken.add(name.lowercase())) name = "$stem (${n++})$suffix"
                    val file = java.io.File(dir, name)
                    java.io.FileOutputStream(file).use { o -> editor.copyFileTo(u, o) }
                    out.add(androidx.core.content.FileProvider.getUriForFile(context, auth, file))
                }
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, out)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (intent != null) context.startActivity(Intent.createChooser(intent, context.resources.getQuantityString(R.plurals.share_n_notes, uris.size, uris.size))) else editor.message = context.getString(R.string.err_share_notes)
            }
        }
    }

    // Share the selected side-panel pages: as one PDF, or as one/many PNGs (ACTION_SEND_MULTIPLE).
    fun sharePages(from: Editor, pages: List<Int>, asPdf: Boolean) {
        if (pages.isEmpty()) return
        val stem = from.title
        if (asPdf) {
            runPdfExport(stem, shareDir = true,
                render = { o, prog, cancel -> from.exportPagesToPdf(pages, o, prog, cancel) },
                onReady = { temp -> runCatching { launchShare(temp, stem, "application/pdf") }.onFailure { editor.message = context.getString(R.string.err_share_pages) } })
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val auth = "${context.packageName}.fileprovider"
            val intent = runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val uris = ArrayList<Uri>()
                for (index in pages) {
                    val png = from.pageImagePng(index) ?: continue
                    val file = java.io.File(dir, "%s-p%02d.png".format(stem, index + 1))
                    java.io.FileOutputStream(file).use { it.write(png) }
                    uris.add(androidx.core.content.FileProvider.getUriForFile(context, auth, file))
                }
                when {
                    uris.isEmpty() -> null
                    uris.size == 1 -> Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uris[0]); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/png"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (intent != null) context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_named, stem))) else editor.message = context.getString(R.string.err_share_pages)
            }
        }
    }

    fun savePagesAsPdf(from: Editor, pages: List<Int>) {
        if (pages.isEmpty()) return
        runPdfExport(from.title, shareDir = false,
            render = { o, prog, cancel -> from.exportPagesToPdf(pages, o, prog, cancel) },
            onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${from.title}.pdf") })
    }

    // One page -> a single PNG (CreateDocument); several -> a folder the user picks (one PNG per page).
    fun savePagesAsImages(from: Editor, pages: List<Int>) {
        if (pages.isEmpty()) return
        pendingExportPages = PendingPages(from, pages)
        if (pages.size == 1) savePageImageLauncher.launch("%s-p%02d.png".format(from.title, pages[0] + 1))
        else savePagesImagesTreeLauncher.launch(null)
    }

    // Every shortcut acts on the focused pane, so the same KeyActions serve both of them.
    editor.keyActions = remember {
        Editor.KeyActions(
            newNote = { guarded { editor.active.newNote() } },
            open = { backstageView = com.xnotes.ui.BackstageView.HOME; guardedAll { editor.goHomeAll() } },
            save = { saveOrPrompt() },
            saveAs = { launchSaveAs(editor.active) },
            exportPdf = {
                val from = editor.active
                runPdfExport(from.title, shareDir = false,
                    render = { o, prog, cancel -> from.exportPdf(o, prog, cancel) },
                    onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${from.title}.pdf") })
            },
            preferences = { backstageView = com.xnotes.ui.BackstageView.PREFERENCES; guardedAll { editor.goHomeAll() } },
            fullscreen = onToggleFullscreen,
        )
    }

    // A window over this screen that shows messages itself takes them while it is open.
    LaunchedEffect(editor.message, editor.messageHosts) {
        if (editor.messageHosts == 0) editor.showMessage(snackbar)
    }
    // The second pane raises its own messages; surface them through the same snackbar.
    val secondaryMessage = editor.secondary?.message
    val secondaryHosts = editor.secondary?.messageHosts ?: 0
    LaunchedEffect(secondaryMessage, secondaryHosts) {
        if (secondaryMessage != null && secondaryHosts == 0) {
            snackbar.showSnackbar(secondaryMessage)
            editor.secondary?.message = null
        }
    }

    // A PDF or markdown file opened from another app ("Open with"/Share): set up a pending import
    // and land on the backstage so its name dialog appears, exactly like the in-app "Import PDF"
    // button. With no folder chosen yet, fall back to App storage so the explorer renders and the
    // note autosaves. A markdown file becomes a new note whose text it is.
    LaunchedEffect(importUri) {
        val src = importUri ?: return@LaunchedEffect
        onImportConsumed() // consume once; the body has no suspend point so it runs to completion
        val name = displayNameOf(resolver, src)
        val markdown = runCatching { resolver.getType(src) }.getOrNull() in MARKDOWN_TYPES ||
            name?.substringAfterLast('.', "")?.lowercase() in MARKDOWN_EXTENSIONS
        val stem = com.xnotes.core.util.Paths.stem(name ?: "Document")
        guardedAll {
            if (editor.browseRoot == null) editor.useInternalStorage()
            editor.requestImport(stem, src.toString(), markdown)
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHomeAll()
        }
    }

    // In fullscreen the window runs edge to edge and the swipe-in system bars are transient, so
    // zero the content insets: otherwise their inset animates 0 -> N -> 0 and resizes the canvas,
    // forcing a re-render every time the bars hide. Non-fullscreen keeps the normal bar insets.
    // A DOCKED keyboard lifts only the open panes (the bottom format bar rides right above it) and
    // the snackbar; Home stays put and the keyboard slides over it. A floating keyboard reports no
    // inset. Insets are consumed below so inner imePadding only adds what the bars don't cover.
    val contentInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBars
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.ime.exclude(contentInsets))) },
        contentWindowInsets = contentInsets,
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(contentInsets)) {
            // BASE LAYER: backstage is the root of the stack — always present underneath.
            val home: @Composable (Editor?) -> Unit = { target ->
                androidx.compose.runtime.key(target?.pane) {
                var paneView by remember { mutableStateOf(com.xnotes.ui.BackstageView.HOME) }
                com.xnotes.ui.Backstage(
                editor = target ?: editor,
                view = if (target == null) backstageView else paneView,
                onSelectView = { if (target == null) backstageView = it else paneView = it },
                allowSplits = target == null,
                initialDocument = target?.let { paneHomes[it.pane] },
                backEnabled = target == null || homeFocus == target.pane,
                onExitApp = { if (target == null) (context as? android.app.Activity)?.finish() else closePaneHome(target) },
                onImportCodeTheme = { importCodeThemeLauncher.launch(arrayOf("*/*")) },
                onImportFont = { importFontLauncher.launch(arrayOf("*/*")) },
                onBackup = { export ->
                    if (export) exportSettingsLauncher.launch("xnotes-settings-${java.time.LocalDate.now()}.zip")
                    else importSettingsLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                onImportPdf = { importPdfPane = target; importPdfLauncher.launch(arrayOf("application/pdf")) },
                onOpenFile = { uri ->
                    if (target == null) guardedAll { discarded -> openTreeFile(uri, discarded) }
                    else guarded(target) {
                        val toolsFrom = editor.active
                        scope.launch {
                            val opened = openInto(target, uri)
                            if (opened != null) {
                                paneHomes = com.xnotes.ui.finishPaneHomeOpen(paneHomes, target.pane, opened.pane)
                                homeFocus = null
                                opened.copyToolSettingsFrom(toolsFrom)
                                editor.focusPane(opened.pane)
                            }
                        }
                    }
                },
                onChooseSplitPair = {
                    if (editor.browseRoot == null) editor.useInternalStorage()
                    editor.secondaryPane()
                    paneHomes = mapOf(com.xnotes.ui.Pane.PRIMARY to null, com.xnotes.ui.Pane.SECONDARY to null)
                    homeFocus = com.xnotes.ui.Pane.PRIMARY
                    editor.splitRatio = 0.5f
                    mainMenu = false
                },
                onOpenSplitFile = { uri -> guardedAll { discarded -> scope.launch { if (editor.openSplitFile(uri, discarded)) { paneHomes = emptyMap(); mainMenu = false } } } },
                onRestoreSplit = { if (editor.inSplit) { paneHomes = emptyMap(); mainMenu = false } else guardedAll { discarded -> scope.launch { if (editor.openSplitFile(explicitlyDiscarded = discarded)) { paneHomes = emptyMap(); mainMenu = false } } } },
                onPickRoot = { browsePickerPane = target; pickRootLauncher.launch(null) },
                onShareFile = { uri -> pendingShareUri = uri; showShareChooser = true },
                onSaveCopyFile = { uri -> pendingSaveCopyUri = uri; saveCopyLauncher.launch("${stemOf(uri)}${kindOf(uri).suffix}") },
                onExportFilePdf = { uri ->
                    runPdfExport(stemOf(uri), shareDir = false, counting = countingOf(uri),
                        render = { o, prog, cancel -> editor.exportFileToPdf(uri, o, prog, cancel) },
                        onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${stemOf(uri)}.pdf") })
                },
                onOpenSplit = { first, second -> guardedAll { openSplit(first, second) } },
                onShareFiles = { uris -> shareFiles(uris) },
                onOpenBeside = { uri ->
                    editor.currentUri?.takeIf {
                        target == null && !editor.canvasOpen && !editor.isSameDocument(it, uri) &&
                            kindOf(uri) != com.xnotes.core.util.DocumentKind.CANVAS
                    }?.let { first -> { guardedAll { openSplit(first, uri) } } }
                },
                onExportPdfs = { picked, name, onStarted ->
                    pendingPdfs = PendingPdfs(picked, onStarted)
                    val lone = com.xnotes.ui.lonePdf(picked)
                    if (lone != null) exportPdfLauncher.launch("${com.xnotes.core.util.DocumentKind.stripSuffix(lone.name)}.pdf")
                    else exportZipLauncher.launch("$name.zip")
                },
                onImportTemplate = { importTemplateLauncher.launch(arrayOf("*/*")) },
                onTemplateFromImage = { kind ->
                    pendingTemplateKind = kind
                    templateFromImageLauncher.launch(arrayOf("image/*"))
                },
            )
                }
            }
            if (paneHomes.isEmpty() || mainMenu) home(null)

            // TOP LAYER: the open panes (toolbar + canvas each), pushed over backstage. Back acts on
            // the focused pane; its handlers live here so — composed after backstage — they win while
            // a note is open.
            val focused = editor.active
            if (focused.noteOpen && !mainMenu && (paneHomes.isEmpty() || homeFocus == null)) {
                // While a text box is open, Back commits-or-dismisses it (and hides the keyboard).
                BackHandler(enabled = focused.editingField != null) { focused.commitText() }
                // A live flow caret session ends first (flushing its typing burst).
                BackHandler(enabled = focused.flowEditingActive) { focused.flowText.endSession() }
                // Otherwise Back closes that pane (guarded for unsaved edits); the other one stays.
                BackHandler(enabled = focused.editingField == null && !focused.flowEditingActive) {
                    closeEditorPane(focused)
                }
            }

            val actions = PaneActions(
                onToggleFullscreen = onToggleFullscreen,
                onMainMenu = {
                    if (editor.openPanes.none { it.stylusInContact }) {
                        editor.persist()
                        parkedDocuments = documentsNow
                        mainMenu = true
                        backstageView = com.xnotes.ui.BackstageView.HOME
                    }
                },
                onSaveSplit = {
                    if (paneHomes.isNotEmpty() || editor.openPanes.any { it.visibleDocumentUri == null }) editor.message = context.getString(R.string.split_save_files_first)
                    else scope.launch { editor.saveCurrentSplit() }
                },
                onOpenBackstage = { pane ->
                    closeEditorPane(pane)
                },
                onClosePane = { pane ->
                    closeEditorPane(pane)
                },
                onPickDocument = { pane ->
                    if (!editor.active.stylusInContact) {
                        if (editor.browseRoot == null) editor.useInternalStorage()
                        paneHomes = paneHomes + (pane.pane to pane.visibleDocumentUri)
                        homeFocus = pane.pane
                    }
                },
                onSplitDocument = { pane ->
                    if (!editor.inSplit && !pane.canvasOpen && !pane.stylusInContact) {
                        if (editor.browseRoot == null) editor.useInternalStorage()
                        val slot = if (pane === editor) com.xnotes.ui.Pane.SECONDARY else com.xnotes.ui.Pane.PRIMARY
                        editor.secondaryPane()
                        paneHomes = paneHomes + (slot to pane.visibleDocumentUri)
                        homeFocus = slot
                        editor.startFreshSplit(pane.pane)
                    }
                },
                onInsertImage = { pane, at ->
                    pendingInsert = PendingInsert(pane, at)
                    insertImageLauncher.launch(arrayOf("image/*"))
                },
                onInsertCanvasImage = { pane, at ->
                    pendingCanvasInsert = PendingInsert(pane, at)
                    insertCanvasImageLauncher.launch(arrayOf("image/*"))
                },
                onTakePhoto = { pane -> takePhoto(pane) },
                onShareImage = { file ->
                    runCatching { launchShare(file, file.nameWithoutExtension, "image/png") }
                        .onFailure { editor.message = context.getString(R.string.err_share_image) }
                },
                onOpenFile = { pane, uri -> scope.launch { openInto(pane, uri) } },
                onAddStickers = { addStickersLauncher.launch(arrayOf("image/*")) },
                onInsertAudio = { pane ->
                    pendingAudioPane = pane
                    attachAudioLauncher.launch(arrayOf("audio/*", "application/ogg"))
                },
                onSaveAudio = { pane, item ->
                    pendingSaveAudio = pane to item
                    saveAudioLauncher.launch(audioFileName(item))
                },
                onShareAudio = { pane, item ->
                    scope.launch {
                        val shared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                java.io.File(dir, audioFileName(item)).also { item.audio.file.copyTo(it, overwrite = true) }
                            }.getOrNull()
                        }
                        if (shared == null) pane.say(context.getString(R.string.err_share_audio))
                        else runCatching { launchShare(shared, item.name, audioMime(item.audio.ext)) }.onFailure { pane.say(context.getString(R.string.err_share_audio)) }
                    }
                },
                onRecordAudio = { pane ->
                    val mic = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    val wanted = buildList {
                        if (!mic) add(android.Manifest.permission.RECORD_AUDIO)
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (mic && wanted.isEmpty()) {
                        pane.startRecording()
                    } else if (mic && !askedNotifications) {
                        askedNotifications = true
                        pendingRecordPane = pane
                        recordPermissions.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO) + wanted)
                    } else if (mic) {
                        pane.startRecording()
                    } else {
                        pendingRecordPane = pane
                        recordPermissions.launch(wanted.toTypedArray())
                    }
                },
                onImportTemplate = { importTemplateLauncher.launch(arrayOf("*/*")) },
                onTemplateFromImage = { kind ->
                    pendingTemplateKind = kind
                    templateFromImageLauncher.launch(arrayOf("image/*"))
                },
                onSharePages = { pane, pages, asPdf -> sharePages(pane, pages, asPdf) },
                onSavePagesAsPdf = { pane, pages -> savePagesAsPdf(pane, pages) },
                onSavePagesAsImages = { pane, pages -> savePagesAsImages(pane, pages) },
                onInsertPdf = { pane, after ->
                    pendingPdfInsert = PendingPdfInsert(pane, after)
                    insertPdfLauncher.launch(arrayOf("application/pdf"))
                },
            )
            if (!mainMenu) SplitHost(editor, actions, paneHomes.keys, onDocumentFocus = { homeFocus = null }) { pane ->
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(com.xnotes.ui.theme.LocalPalette.current.bg.toComposeColor())) {
                    val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                        screenWidthDp = maxWidth.value.toInt()
                        screenHeightDp = maxHeight.value.toInt()
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration) {
                        Column(Modifier.fillMaxSize().pointerInput(pane) {
                            awaitPointerEventScope { while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) homeFocus = pane.pane
                            } }
                        }) {
                            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.home), Modifier.weight(1f).padding(horizontal = Space.s16),
                                    color = com.xnotes.ui.theme.LocalPalette.current.text.toComposeColor())
                                androidx.compose.material3.IconButton(onClick = {
                                    closePaneHome(pane)
                                }) { Icon(XnotesIcons.close, stringResource(R.string.split_close_document)) }
                            }
                            Box(Modifier.weight(1f)) { home(pane) }
                        }
                    }
                }
            }

        }
    }
    if (showShareChooser) {
        val shareUri = pendingShareUri
        // Remembered because resolving the kind is a SAF query, and this is composition.
        val shareSuffix = remember(shareUri) {
            shareUri?.let { kindOf(it).suffix } ?: com.xnotes.core.util.DocumentKind.NOTE.suffix
        }
        com.xnotes.ui.AlertDialog(
            onDismissRequest = { showShareChooser = false; pendingShareUri = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.share_note)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.share_as, shareUri?.let { stemOf(it) } ?: "")) },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null; shareUri?.let { shareFile(it, asPdf = false) } }) {
                        androidx.compose.material3.Text(stringResource(R.string.file_of_kind, shareSuffix))
                    }
                    androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null; shareUri?.let { shareFile(it, asPdf = true) } }) {
                        androidx.compose.material3.Text("PDF")
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    pendingBackup?.let { backup ->
        com.xnotes.ui.AlertDialog(
            onDismissRequest = { pendingBackup = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.import_settings_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.import_settings_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { pendingBackup = null; editor.applySettingsBackup(backup) }) {
                    androidx.compose.material3.Text(stringResource(R.string.replace))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingBackup = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    guardAction?.let { request ->
        val guarded = request.editor
        val action = request.action
        com.xnotes.ui.AlertDialog(
            onDismissRequest = { guardAction = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.unsaved_changes)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.unsaved_changes_prompt, guarded.title)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    guardAction = null
                    val uri = guarded.currentUri
                    if (uri != null) {
                        // Off-thread: what the prompt was guarding runs once the bytes have landed.
                        guarded.saveToThen(uri) { ok -> if (ok) action() }
                    } else {
                        pendingAfterSave = action
                        launchSaveAs(guarded)
                    }
                }) { androidx.compose.material3.Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { guardAction = null; request.onDiscard(); action() }) {
                        androidx.compose.material3.Text(stringResource(R.string.discard))
                    }
                    androidx.compose.material3.TextButton(onClick = { guardAction = null }) {
                        androidx.compose.material3.Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }
    exportProgress?.let { p ->
        PdfExportDialog(done = p.done, total = p.total, counting = p.counting, onCancel = {
            exportCancel?.set(true) // abort this export's page loop AND its write stream
            exportProgress = null   // hide at once; the job then discards the half-written temp
        })
    }
    editor.batchExport?.let { p -> PdfBatchExportDialog(p) { editor.cancelBatchExport() } }
    // Either pane may be importing: a PDF inserted into its note.
    val importer = editor.takeIf { it.importing } ?: editor.secondary?.takeIf { it.importing }
    if (importer != null) {
        // A batch counts files, so it gets a determinate ring; a single import has nothing to count.
        val batch = importer.importProgress
        if (batch != null) PdfImportBatchDialog(batch.done, batch.total) { importer.cancelImportInProgress() }
        else PdfImportDialog(
            label = importer.busyWith ?: if (importer.pendingImport?.markdown == true) R.string.importing_markdown else R.string.importing_pdf,
            onCancel = { importer.cancelImportInProgress() }, // the stream-copy stops at its next buffer
        )
    }
    // Tapping a note reads it off-thread (editor.opening). Only show the spinner once the read has run
    // long enough to matter, so opening a small note never flashes a dialog; a big PDF gets the loader.
    var showOpening by remember { mutableStateOf(false) }
    val opening = editor.opening || editor.secondary?.opening == true
    LaunchedEffect(opening) {
        if (opening) { delay(160); showOpening = opening } else showOpening = false
    }
    if (showOpening && opening) {
        SpinnerDialog(stringResource(R.string.opening_note), onCancel = {
            // Discard whichever pane's note is still being read when it returns.
            editor.cancelOpenInProgress()
            editor.secondary?.cancelOpenInProgress()
            showOpening = false // dismiss at once; opening clears as the reads unwind
        })
    }
    // A dirty note is flushed off-thread on close/pause; show the saving overlay only once the write is
    // slow enough to matter, so closing a small note never flashes a dialog.
    var showSaving by remember { mutableStateOf(false) }
    val saving = editor.savingNote || editor.secondary?.savingNote == true
    LaunchedEffect(saving) {
        if (saving) { delay(160); showSaving = saving } else showSaving = false
    }
    if (showSaving && saving) SavingDialog()
    // AI chats follow the notes Trash: same retention, and with Trash off a delete is permanent.
    val trashDays = remember(editor.prefsVersion) { editor.preferences.trashDays }
    val aiSessions = com.xnotes.ai.AiSessions.get(LocalContext.current)
    LaunchedEffect(trashDays, aiSessions.ready) { aiSessions.trashDays = trashDays; aiSessions.purgeTrash(trashDays) }
    com.xnotes.ui.AiChatHost(if (mainMenu) emptySet() else editor.openPanes
        .filter { it.noteOpen && it.pane !in paneHomes }
        .map { it.visibleDocumentId.ifBlank { it.visibleDocumentUri ?: if (it.canvasOpen) it.infinite.document.title else it.state.document.title } }.toSet())
}

/** A pending "unsaved changes" prompt: the pane it asks about, and what to run once it's settled. */
private class GuardRequest(val editor: Editor, val action: () -> Unit, val onDiscard: () -> Unit)

/** A picked image on its way into [editor], at [at] when a long-press chose the spot. */
private class PendingInsert(val editor: Editor, val at: com.xnotes.core.geometry.Pt?)

/** Side-panel pages of [editor] waiting on a SAF "Save as" destination. */
private class PendingPages(val editor: Editor, val pages: List<Int>)

/** A side-panel "Insert PDF" waiting on the picker: the pane, and the page its pages go after. */
private class PendingPdfInsert(val editor: Editor, val after: Int)

/** Explorer picks waiting on the file their PDFs go to; [onStarted] runs once one is chosen. */
private class PendingPdfs(val picked: List<com.xnotes.ui.BrowseEntry>, val onStarted: () -> Unit)

/**
 * A running PDF export's progress: [done] of [total], and what those are. [counting] exists because
 * a canvas has no pages to count — it exports as one, and reports the items it is drawing instead —
 * so the dialog cannot assume the unit. `total < 0` marks the final write phase, where [done] is a
 * 0..1000 permille of the output bytes.
 */
private class ExportProgress(val done: Int, val total: Int, val counting: String) {
    companion object {
        /** What a document of [kind] counts on its way out. */
        fun countingFor(kind: com.xnotes.core.util.DocumentKind): String =
            if (kind == com.xnotes.core.util.DocumentKind.CANVAS) "item" else "page"
    }
}

/**
 * A tap here, or any pen stroke, lets the side panel's page selection go; a finger that scrolls or
 * pinches keeps it. Watched on the way in and never consumed, so whatever is under it still acts.
 */
private fun Modifier.endsPageSelection(editor: Editor): Modifier = pointerInput(editor) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!editor.inPageSelectionMode) return@awaitEachGesture
        if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
            editor.clearPageSelection()
            return@awaitEachGesture
        }
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
            val c = e.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            if (!c.pressed) {
                editor.clearPageSelection()
                return@awaitEachGesture
            }
        }
    }
}

/**
 * What a pane can ask the screen around it to do: open a SAF picker, run an export, or change the
 * window. Each callback takes the pane it is acting for, because in a split there are two of them
 * and a picker's result has to come back to the one that opened it.
 */
internal class PaneActions(
    val onToggleFullscreen: () -> Unit,
    val onMainMenu: () -> Unit,
    val onSaveSplit: () -> Unit,
    /** Leave for the backstage, closing every open pane. */
    val onOpenBackstage: (Editor) -> Unit,
    /** Close just this pane, leaving the other one to fill the window. */
    val onClosePane: (Editor) -> Unit,
    val onSplitDocument: (Editor) -> Unit,
    val onPickDocument: (Editor) -> Unit,
    val onInsertImage: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    val onInsertCanvasImage: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    /** Take a photo with the camera app and place it like an inserted picture, on the canvas when [Editor.canvasOpen]. */
    val onTakePhoto: (Editor) -> Unit,
    val onAddStickers: () -> Unit,
    val onInsertAudio: (Editor) -> Unit,
    val onRecordAudio: (Editor) -> Unit,
    val onSaveAudio: (Editor, com.xnotes.core.model.AudioItem) -> Unit,
    val onShareAudio: (Editor, com.xnotes.core.model.AudioItem) -> Unit,
    /** Hand a PNG the pane rendered (a selection or a capture) to the share sheet. */
    val onShareImage: (java.io.File) -> Unit,
    /** Open a stored note in [Editor]'s pane, as a tap on it in the explorer would. */
    val onOpenFile: (Editor, String) -> Unit,
    val onImportTemplate: () -> Unit,
    val onTemplateFromImage: (com.xnotes.core.template.TemplateKind) -> Unit,
    val onSharePages: (Editor, List<Int>, Boolean) -> Unit,
    val onSavePagesAsPdf: (Editor, List<Int>) -> Unit,
    val onSavePagesAsImages: (Editor, List<Int>) -> Unit,
    val onInsertPdf: (Editor, Int) -> Unit,
)

/** A recording's file name when it leaves the app: its name, made safe for any folder, and its format. */
private fun audioFileName(item: com.xnotes.core.model.AudioItem): String {
    val stem = item.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().ifEmpty { "recording" }.take(96)
    return "$stem.${item.audio.ext}"
}

/** The type another app is told a recording's file is. */
private fun audioMime(ext: String): String = when (ext.lowercase()) {
    "m4a", "mp4" -> "audio/mp4"
    "aac" -> "audio/aac"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "opus" -> "audio/opus"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "amr" -> "audio/amr"
    "3gp" -> "audio/3gpp"
    "webm" -> "audio/webm"
    else -> "audio/*"
}

/** Neither pane of a split may be squeezed below this share of the split axis. */
private const val MIN_PANE_RATIO = 0.18f

/** How wide the divider is to a finger. Mostly empty around the line, so it is easy to catch. */
private val DIVIDER = 32.dp

/** The accent line drawn down the middle of the divider, the same weight as a pane's focus line. */
private val DIVIDER_LINE = 2.dp

/**
 * Makes the open panes opaque to touch. Compose stops hit-testing lower siblings once a higher one
 * is hit, so this node — hit anywhere over the panes — keeps a tap that no child handled from
 * reaching the backstage composed underneath. It never consumes, so children still see every event.
 */
private val SwallowTouches = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial)
    }
}

/**
 * Lays the open panes over the backstage. Both open is a split: side by side in landscape, stacked
 * in portrait, with a draggable divider between them. One open is that pane full-screen, and none
 * leaves the backstage showing.
 *
 * The panes are placed by offset and size inside one Box rather than by a Row that becomes a Column,
 * so normal layout changes retain each logical pane's host. A pane switching between its file home
 * and its editor, a change of document kind in a pane, and the main-menu round trip still dispose
 * and remount the native View, so the `surfacesForAttach()` replacement stays required. Surface/EGL
 * recreation is still managed by Android; keeping the View attached does not guarantee that its
 * GPU context survives.
 */
private data class SplitTitleDrag(val source: Editor, val origin: Offset, val point: Offset, val returning: Boolean = false)

private fun Editor.toolbarDocuments(): Set<com.xnotes.core.tools.ToolbarDocument> = openPanes.map {
    when {
        it.canvasOpen -> com.xnotes.core.tools.ToolbarDocument.CANVAS
        it.hasPdf -> com.xnotes.core.tools.ToolbarDocument.PDF
        else -> com.xnotes.core.tools.ToolbarDocument.NOTE
    }
}.toSet()

@Composable
internal fun SplitHost(editor: Editor, actions: PaneActions, homes: Set<com.xnotes.ui.Pane>, onDocumentFocus: () -> Unit, home: @Composable (Editor) -> Unit) {
    val second = editor.secondary
    val primaryVisible = editor.noteOpen || com.xnotes.ui.Pane.PRIMARY in homes
    val secondVisible = second != null && (second.noteOpen || com.xnotes.ui.Pane.SECONDARY in homes)
    val split = primaryVisible && secondVisible
    // A pane that closed on its own leaves the other one full-screen; once neither is open the
    // second editor is released, freeing its canvas and GL surfaces.
    LaunchedEffect(editor.noteOpen, second?.noteOpen, homes) {
        if (second != null && !second.noteOpen && com.xnotes.ui.Pane.SECONDARY !in homes) editor.releaseClosedSecondary()
    }
    if (!primaryVisible && !secondVisible) return

    val sideBySide = editor.splitSideBySide ?: (LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    val ratio = editor.splitRatio.coerceIn(MIN_PANE_RATIO, 1f - MIN_PANE_RATIO)
    var swapAnimating by remember(editor, sideBySide, split) { mutableStateOf(false) }
    var titleDrag by remember(editor, sideBySide, split) { mutableStateOf<SplitTitleDrag?>(null) }
    Column(Modifier.fillMaxSize().then(SwallowTouches).imePadding()) {
        if (split) {
            val look = com.xnotes.ui.LocalToolbarLook.current
            CompositionLocalProvider(com.xnotes.ui.LocalToolbarLook provides look.copy(
                position = com.xnotes.settings.ToolbarPosition.TOP, floating = false,
            )) {
                val layout = com.xnotes.core.tools.workspaceToolbarLayout(editor.toolbarLayout, editor.canvasToolbarLayout)
                com.xnotes.ui.ToolbarFrame(armed = if (editor.active.canvasOpen) editor.active.infinite.toolbarTool else editor.active.toolbarTool, leading = {
                    com.xnotes.ui.ToolbarIcon(XnotesIcons.prev, stringResource(R.string.toolbar_home), onClick = actions.onMainMenu)
                }, trailing = {
                    com.xnotes.ui.ToolbarIcon(XnotesIcons.save, stringResource(R.string.split_save), onClick = actions.onSaveSplit)
                    com.xnotes.ui.ToolbarIcon(XnotesIcons.split, stringResource(R.string.split_change_direction)) { editor.splitSideBySide = !sideBySide }
                }) {
                    CompositionLocalProvider(com.xnotes.ui.LocalEmbeddedToolbar provides true,
                        com.xnotes.ui.LocalWorkspaceToolbarLayout provides layout.forDocumentControls(false, editor.toolbarDocuments())) {
                        if (homes.size < 2) EditorToolbar(editor.active, editor, actions, shared = true)
                    }
                }
            }
        }
        var hostOrigin by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().onGloballyPositioned { hostOrigin = it.positionInRoot() }) {
        val fullW = maxWidth
        val fullH = maxHeight
        val full = if (sideBySide) fullW else fullH
        // The panes meet along the split axis and take all of it; the divider is not a gap between
        // them but a handle floating over the seam, so there is no strip of its own colour to show
        // beside the toolbars. Across the axis both run full.
        val firstExtent = if (split) full * ratio else full
        val secondExtent = if (split) full - firstExtent else full

        /** Sizes a pane to [extent] along the split axis and the whole window across it. */
        fun paneSize(extent: Dp) = Modifier.size(
            if (sideBySide) extent else fullW,
            if (sideBySide) fullH else extent,
        )

        /** Offsets a pane [along] the split axis from the top-left of the editor area. */
        fun paneOffset(along: Dp) = Modifier.offset(
            x = if (sideBySide) along else 0.dp,
            y = if (sideBySide) 0.dp else along,
        )

        val density = LocalDensity.current
        val extentPx = with(density) { full.toPx() }
        val crossPx = with(density) { (if (sideBySide) fullH else fullW).toPx() }
        fun isDropTarget(drag: SplitTitleDrag): Boolean {
            val local = drag.point - hostOrigin
            val axis = if (sideBySide) local.x else local.y
            val cross = if (sideBySide) local.y else local.x
            val sourceFirst = (drag.source === editor) != editor.splitReversed
            return split && !drag.returning && com.xnotes.core.util.isOppositeSplitPane(
                sourceFirst, axis, cross, extentPx, crossPx, ratio)
        }
        val onTitleDrag: (Editor, Offset, Offset) -> Unit = { source, origin, point ->
            titleDrag = SplitTitleDrag(source, origin, point)
        }
        val onTitleDrop: (Boolean) -> Unit = { cancelled ->
            titleDrag?.let { drag ->
                if (!cancelled && isDropTarget(drag) && editor.openPanes.none { it.stylusInContact }) {
                    swapAnimating = true
                    editor.splitReversed = !editor.splitReversed
                    titleDrag = null
                } else titleDrag = drag.copy(point = drag.origin, returning = true)
            }
        }
        // Recreate on size/ratio changes so resizing stays immediate; animate only reordering.
        val primaryPosition = remember(firstExtent, sideBySide, split) {
            androidx.compose.animation.core.Animatable(if (split && editor.splitReversed) with(density) { firstExtent.toPx() } else 0f)
        }
        val secondaryPosition = remember(firstExtent, sideBySide, split) {
            androidx.compose.animation.core.Animatable(if (split && !editor.splitReversed) with(density) { firstExtent.toPx() } else 0f)
        }
        LaunchedEffect(editor.splitReversed, primaryPosition, secondaryPosition) {
            val primaryMotion = launch { primaryPosition.animateTo(if (split && editor.splitReversed) with(density) { firstExtent.toPx() } else 0f,
                androidx.compose.animation.core.tween(240)) }
            secondaryPosition.animateTo(if (split && !editor.splitReversed) with(density) { firstExtent.toPx() } else 0f,
                androidx.compose.animation.core.tween(240))
            primaryMotion.join()
            swapAnimating = false
        }
        fun animatedPosition(position: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) = Modifier.offset {
            androidx.compose.ui.unit.IntOffset(if (sideBySide) position.value.roundToInt() else 0,
                if (sideBySide) 0 else position.value.roundToInt())
        }
        if (primaryVisible) {
            if (com.xnotes.ui.Pane.PRIMARY in homes) Box(animatedPosition(primaryPosition)
                .then(paneSize(if (split && editor.splitReversed) secondExtent else firstExtent))) { home(editor) }
            else androidx.compose.runtime.key(editor, editor.canvasOpen) { EditorPane(
                editor = editor,
                app = editor,
                actions = actions,
                closable = split, onTitleDrag = onTitleDrag, onTitleDrop = onTitleDrop, onDocumentFocus = onDocumentFocus,
                modifier = animatedPosition(primaryPosition)
                    .then(paneSize(if (split && editor.splitReversed) secondExtent else firstExtent)),
            ) }
        }
        if (secondVisible && second != null) {
            if (com.xnotes.ui.Pane.SECONDARY in homes) Box(animatedPosition(secondaryPosition)
                .then(paneSize(if (split && editor.splitReversed) firstExtent else secondExtent))) { home(second) }
            else androidx.compose.runtime.key(second, second.canvasOpen) { EditorPane(
                editor = second,
                app = editor,
                actions = actions,
                closable = split, onTitleDrag = onTitleDrag, onTitleDrop = onTitleDrop, onDocumentFocus = onDocumentFocus,
                modifier = animatedPosition(secondaryPosition)
                    .then(paneSize(if (split && editor.splitReversed) firstExtent else secondExtent)),
            ) }
        }
        // Composed last so it sits over both panes and catches the drag before either of them.
        if (split) {
            SplitDivider(
                sideBySide = sideBySide,
                extentPx = with(LocalDensity.current) { full.toPx() },
                ratio = editor.splitRatio,
                onRatio = { editor.splitRatio = it },
                canDrag = { editor.openPanes.none { it.stylusInContact } },
                modifier = paneOffset((firstExtent - DIVIDER / 2).coerceAtLeast(0.dp))
                    .then(paneSize(DIVIDER)),
            )
        }
        // Capture the entire new gesture above both AndroidViews while their coordinates move.
        if (swapAnimating) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
        })
        titleDrag?.let { drag ->
            val palette = LocalPalette.current
            if (isDropTarget(drag)) {
                val sourceFirst = (drag.source === editor) != editor.splitReversed
                Box(paneOffset(if (sourceFirst) firstExtent else 0.dp)
                    .then(paneSize(if (sourceFirst) secondExtent else firstExtent))
                    .border(3.dp, palette.text.toComposeColor())) {
                    Text(stringResource(R.string.split_drop_swap), Modifier.align(Alignment.Center)
                        .background(palette.bg.toComposeColor()).border(1.dp, palette.text.toComposeColor()).padding(Space.s12),
                        color = palette.text.toComposeColor())
                }
            }
            val ghost by androidx.compose.animation.core.animateOffsetAsState(drag.point - hostOrigin,
                animationSpec = if (drag.returning) androidx.compose.animation.core.tween(180) else androidx.compose.animation.core.snap(), label = "file drag")
            LaunchedEffect(drag.returning) {
                if (drag.returning) { delay(200); if (titleDrag === drag) titleDrag = null }
            }
            Text(if (drag.source.canvasOpen) drag.source.infinite.title else drag.source.title,
                Modifier.offset { androidx.compose.ui.unit.IntOffset(ghost.x.roundToInt(), ghost.y.roundToInt()) }
                    .width(140.dp).background(palette.bg.toComposeColor()).border(2.dp, palette.text.toComposeColor()).padding(Space.s12),
                color = palette.text.toComposeColor(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        }
    }
}

@Composable
private fun EditorToolbar(editor: Editor, app: Editor, actions: PaneActions, shared: Boolean = false) {
    val toggleDirection: (() -> Unit)? = if (app.inSplit) ({
        val automatic = editor.view.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        app.splitSideBySide = !(app.splitSideBySide ?: automatic)
    }) else null
    // Canvases are for sketching on their own; only paged documents start a split.
    val split: (() -> Unit)? = if (!app.inSplit && !shared && !editor.canvasOpen) ({ actions.onSplitDocument(editor) }) else null
    if (editor.canvasOpen) {
        val renameFailed = stringResource(R.string.err_rename_note)
        com.xnotes.ui.InfiniteToolbar(
            editor.infinite,
            onToggleFullscreen = actions.onToggleFullscreen,
            onRename = { name -> if (!editor.renameCurrentCanvas(name)) editor.message = renameFailed },
            onOpenBackstage = { actions.onOpenBackstage(editor) },
            onInsertImage = { actions.onInsertCanvasImage(editor, null) },
            onSplitDocument = split,
            onToggleSplitDirection = toggleDirection,
            shared = shared,
            onInsertAudio = { actions.onInsertAudio(editor) },
            onRecordAudio = { actions.onRecordAudio(editor) },
            onTakePhoto = { actions.onTakePhoto(editor) },
        )
    } else {
        Toolbar(editor,
            onToggleFullscreen = actions.onToggleFullscreen,
            onOpenBackstage = { actions.onOpenBackstage(editor) },
            onInsertImage = { actions.onInsertImage(editor, null) },
            onAddStickers = actions.onAddStickers,
            onSplitDocument = split,
            onToggleSplitDirection = toggleDirection,
            shared = shared,
            onImportTemplate = actions.onImportTemplate,
            onInsertAudio = { actions.onInsertAudio(editor) },
            onRecordAudio = { actions.onRecordAudio(editor) },
            onTakePhoto = { actions.onTakePhoto(editor) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PaneToolbarAround(
    show: Boolean,
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    ToolbarAround(bar, onCover, showToolbar = show, content = content)
}

/**
 * One pane: its toolbar over its canvas, with the menus and overlays that belong to that document.
 * A paged note and an infinite canvas get different chrome, since the paged toolbar is mostly pages,
 * viewing modes and text, none of which mean anything on a canvas.
 *
 * [app] is the primary editor, which owns the split; [editor] is this pane's own. Touching anywhere
 * in the pane gives it the focus, so the keyboard and the file actions follow the pen.
 */
@Composable
private fun EditorPane(
    editor: Editor,
    app: Editor,
    actions: PaneActions,
    closable: Boolean,
    onTitleDrag: (Editor, Offset, Offset) -> Unit,
    onTitleDrop: (Boolean) -> Unit,
    onDocumentFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    editor.missingDocument?.let { missing ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if(uri != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                scope.launch { editor.relocateMissing(uri.toString()) }
            }
        }
        Column(modifier.fillMaxSize().padding(Space.s24), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(stringResource(R.string.missing_title))
            Text(stringResource(R.string.missing_body))
            Text(missing.uri.substringAfterLast('/'), maxLines = 2)
            var searching by remember(editor) { mutableStateOf(false) }
            if(searching) Text(stringResource(R.string.missing_searching))
            androidx.compose.material3.TextButton(enabled = !searching,onClick = { scope.launch { searching=true; try { editor.rediscoverMissing() } finally { searching=false } } }) { Text(stringResource(R.string.missing_search)) }
            androidx.compose.material3.TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.missing_locate)) }
            androidx.compose.material3.TextButton(onClick = { scope.launch { editor.relocateMissing(missing.uri) } }) { Text(stringResource(R.string.missing_retry)) }
            androidx.compose.material3.TextButton(onClick = { actions.onClosePane(editor) }) { Text(stringResource(R.string.missing_close_pane)) }
        }
        return
    }
    val palette = LocalPalette.current
    val focusRequester = remember { FocusRequester() }
    val focused = app.active === editor
    SideEffect { editor.openInPane = { uri -> actions.onOpenFile(editor, uri) } }
    // This pane owns the keyboard while it is the focused one; (re)grab it as that changes.
    LaunchedEffect(focused, editor.noteOpen) {
        if (focused && editor.noteOpen) runCatching { focusRequester.requestFocus() }
    }
    var titleOrigin by remember(editor) { mutableStateOf(Offset.Zero) }
    val currentDrag by rememberUpdatedState(onTitleDrag)
    val currentDrop by rememberUpdatedState(onTitleDrop)
    val dragTitle = Modifier.onGloballyPositioned { titleOrigin = it.positionInRoot() }
        .pointerInput(app, editor, closable) {
        var dragging = false
        var origin = Offset.Zero
        var point = Offset.Zero
        detectDragGesturesAfterLongPress(
            onDragStart = { start ->
                dragging = closable && app.openPanes.none { it.stylusInContact }
                origin = titleOrigin
                point = origin + start
                if (dragging) currentDrag(editor, origin, point)
            },
            onDragCancel = { if (dragging) currentDrop(true); dragging = false },
            onDragEnd = { if (dragging) currentDrop(false); dragging = false },
            onDrag = { change, delta ->
                if (dragging) {
                    change.consume()
                    point += delta
                    currentDrag(editor, origin, point)
                }
            },
        )
    }
    val takeFocus = Modifier.pointerInput(editor) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass and never consumed: the pane notices the touch, the canvas still gets it.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) { onDocumentFocus(); app.focusPane(editor.pane) }
            }
        }
    }
    // Keep the title touchable even in a narrow split, without wasting wide-screen toolbar space.
    BoxWithConstraints(modifier) {
        val titleWidth = ((maxWidth - 80.dp) * 0.23f).coerceIn(64.dp, 140.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg.toComposeColor())
                .then(takeFocus)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { ke ->
                    ke.type == KeyEventType.KeyDown && editor.handleKeyDown(ke.nativeKeyEvent)
                },
        ) {
            if (closable) {
                Row(Modifier.fillMaxWidth().height(56.dp).background(palette.panel.toComposeColor()).border(1.dp, palette.border.toComposeColor()),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (editor.canvasOpen) editor.infinite.title else editor.title,
                        Modifier.width(titleWidth).fillMaxHeight().then(dragTitle)
                            .clickable { app.focusPane(editor.pane) }.padding(horizontal = Space.s12, vertical = Space.s8)
                            .wrapContentHeight(Alignment.CenterVertically),
                        color = palette.text.toComposeColor(), maxLines = 1,
                        textDecoration = if (focused) androidx.compose.ui.text.style.TextDecoration.Underline else androidx.compose.ui.text.style.TextDecoration.None,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f)) {
                        val look = com.xnotes.ui.LocalToolbarLook.current
                        val layout = com.xnotes.core.tools.documentToolbarLayout(
                            com.xnotes.core.tools.workspaceToolbarLayout(app.toolbarLayout, app.canvasToolbarLayout), editor.canvasOpen, editor.hasPdf, app.toolbarDocuments())
                        CompositionLocalProvider(com.xnotes.ui.LocalToolbarLook provides look.copy(position = com.xnotes.settings.ToolbarPosition.TOP, floating = false),
                            com.xnotes.ui.LocalWorkspaceToolbarLayout provides layout,
                            com.xnotes.ui.LocalToolbarFocused provides focused) {
                            com.xnotes.ui.ToolbarFrame(armed = if (editor.canvasOpen) editor.infinite.toolbarTool else editor.toolbarTool) {
                                CompositionLocalProvider(com.xnotes.ui.LocalEmbeddedToolbar provides true) {
                                    EditorToolbar(editor, app, actions, shared = true)
                                }
                            }
                        }
                    }
                    androidx.compose.material3.IconButton(onClick = { actions.onPickDocument(editor) }, modifier = Modifier.size(com.xnotes.ui.theme.MinTouchTarget)) {
                        Icon(XnotesIcons.folder, stringResource(R.string.split_replace_document), tint = palette.textDim.toComposeColor())
                    }
                    androidx.compose.material3.IconButton(onClick = { actions.onClosePane(editor) }, modifier = Modifier.size(com.xnotes.ui.theme.MinTouchTarget)) {
                        Icon(XnotesIcons.close, stringResource(R.string.split_close_document), tint = palette.textDim.toComposeColor())
                    }
                }
            }
            if (editor.canvasOpen) {
                val canvas = editor.infinite
                val bar: @Composable () -> Unit = { EditorToolbar(editor, app, actions) }
                PaneToolbarAround(!closable, bar, onCover = canvas::setToolbarCover) { floatingBar ->
                    Row(Modifier.fillMaxSize()) {
                        if (canvas.layersSidebarVisible) {
                            Box(Modifier.width(com.xnotes.ui.theme.LayerSidebarWidth).fillMaxHeight()) {
                                com.xnotes.ui.LayersSidebar(canvas)
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            AndroidView(
                                factory = {
                                    detached(canvas.surfaces)
                                    canvas.surfacesForAttach()
                                },
                                modifier = Modifier.fillMaxSize(),
                                update = { canvas.view.publish() },
                            )
                            canvas.editingField?.let { field ->
                                com.xnotes.ui.TextEditorOverlay(canvas, field)
                            }
                            floatingBar()
                            com.xnotes.ui.SelectionMenu(
                                canvas,
                                onSaveAudio = { actions.onSaveAudio(editor, it) },
                                onShareAudio = { actions.onShareAudio(editor, it) },
                                onShareImage = actions.onShareImage,
                            )
                            com.xnotes.ui.ScreenshotMenu(canvas, onShare = actions.onShareImage)
                            com.xnotes.ui.CropOverlay(canvas)
                            com.xnotes.ui.TextStyleBar(canvas)
                            com.xnotes.ui.LongPressMenu(canvas, onInsertImageAt = { c -> actions.onInsertCanvasImage(editor, c) })
                            com.xnotes.ui.CanvasDebugOverlay(canvas)
                        }
                    }
                }
            } else {
                val bar: @Composable () -> Unit = { EditorToolbar(editor, app, actions) }
                PaneToolbarAround(!closable, bar, onCover = editor::setToolbarCover) { floatingBar ->
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (editor.sidebarVisible) {
                            com.xnotes.ui.SidePanel(
                                editor,
                                onSharePages = { pages, asPdf -> actions.onSharePages(editor, pages, asPdf) },
                                onSavePagesAsPdf = { pages -> actions.onSavePagesAsPdf(editor, pages) },
                                onSavePagesAsImages = { pages -> actions.onSavePagesAsImages(editor, pages) },
                                onInsertPdf = { after -> actions.onInsertPdf(editor, after) },
                                onImportTemplate = actions.onImportTemplate,
                                onTemplateFromImage = actions.onTemplateFromImage,
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds().endsPageSelection(editor)) {
                            AndroidView(
                                factory = {
                                    detached(editor.surfaces)
                                    editor.surfacesForAttach()
                                },
                                modifier = Modifier.fillMaxSize(),
                                update = { editor.view.requestRender() }, // repaint on (re)attach so a push never flashes blank
                            )
                            editor.editingField?.let { field ->
                                com.xnotes.ui.TextEditorOverlay(editor, field)
                            }
                            floatingBar()
                            com.xnotes.ui.SelectionMenu(
                                editor,
                                onSaveAudio = { actions.onSaveAudio(editor, it) },
                                onShareAudio = { actions.onShareAudio(editor, it) },
                                onShareImage = actions.onShareImage,
                            )
                            com.xnotes.ui.CropOverlay(editor)
                            com.xnotes.ui.ScreenshotMenu(editor, onShare = actions.onShareImage)
                            com.xnotes.ui.TextStyleBar(editor)
                            com.xnotes.ui.LongPressMenu(editor, onInsertImageAt = { c -> actions.onInsertImage(editor, c) })
                            com.xnotes.ui.FlowEditMenu(editor)
                            com.xnotes.ui.PdfSelectionMenu(editor)
                            com.xnotes.ui.MarkupNotePeek(editor)
                            com.xnotes.ui.MarkupMenu(editor)
                            com.xnotes.ui.MarkupNoteDialog(editor)
                            com.xnotes.ui.SlashMenu(editor)
                            com.xnotes.ui.EmojiMenu(editor)
                            com.xnotes.ui.FlowTableMenu(editor)
                            com.xnotes.ui.TableChrome(editor)
                            ZoomLockHint(editor)
                        }
                    }
                }
                // Last child of the resized column: rides directly above the soft keyboard.
                com.xnotes.ui.TextFormatBar(editor)
            }
        }
        com.xnotes.ui.ColorDropOverlay(if (editor.canvasOpen) editor.infinite else editor)
    }
}

/**
 * The handle over the seam between two split panes. Dragging it moves the boundary along the split
 * axis, keeping both panes at least [MIN_PANE_RATIO] of it; double-tapping puts it back in the
 * middle. Only the line is painted — the margin either side of it is what the finger catches, and it
 * stays transparent so the panes it lies over show through.
 */
@Composable
private fun SplitDivider(
    sideBySide: Boolean,
    extentPx: Float,
    ratio: Float,
    onRatio: (Float) -> Unit,
    canDrag: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val ratioNow = rememberUpdatedState(ratio)
    val onRatioNow = rememberUpdatedState(onRatio)
    val canDragNow = rememberUpdatedState(canDrag)
    var dragState by remember { mutableStateOf<com.xnotes.core.util.SplitDividerDrag?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val active = pressed || dragging
    val centered = kotlin.math.abs(ratio - 0.5f) < 0.0001f
    val hint = stringResource(R.string.split_drag_hint)
    Box(
        modifier = modifier.semantics { contentDescription = hint }
            .pointerInput(sideBySide, extentPx) {
                detectDragGestures(
                    onDragStart = {
                        if (canDragNow.value()) {
                            dragState = com.xnotes.core.util.SplitDividerDrag(ratioNow.value)
                            dragging = true
                        }
                    },
                    onDragEnd = { dragging = false; dragState = null },
                    onDragCancel = { dragging = false; dragState = null },
                    onDrag = { change, drag ->
                        change.consume()
                        if (extentPx > 0f && canDragNow.value()) {
                            val delta = if (sideBySide) drag.x else drag.y
                            dragState?.let { onRatioNow.value(it.move(delta / extentPx)) }
                        }
                    },
                )
            }
            .pointerInput(sideBySide) {
                detectTapGestures(
                    onPress = {
                        pressed = canDragNow.value()
                        try { tryAwaitRelease() } finally { pressed = false }
                    },
                    onDoubleTap = { if (canDragNow.value()) onRatioNow.value(0.5f) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().padding(
            horizontal = if (sideBySide) (DIVIDER - if (active) 4.dp else DIVIDER_LINE) / 2 else 0.dp,
            vertical = if (sideBySide) 0.dp else (DIVIDER - if (active) 4.dp else DIVIDER_LINE) / 2,
        ).background((if (active) palette.text else palette.border).toComposeColor()))
        Box(
            Modifier.requiredSize(
                if (sideBySide) (if (active) 46.dp else 24.dp) else 64.dp,
                if (sideBySide) 64.dp else (if (active) 32.dp else 24.dp),
            ).clip(RoundedCornerShape(Space.s6))
                .background((if (active) palette.text else palette.panel).toComposeColor())
                .border(1.dp, (if (active) palette.text else palette.textDim).toComposeColor(), RoundedCornerShape(Space.s6)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (active) (if (centered) "50%" else "${(ratio * 100).roundToInt()}%") else if (sideBySide) "⋮" else "⋯",
                color = (if (active) palette.bg else palette.text).toComposeColor(), fontSize = if (active) TextSize.label else TextSize.headline)
        }
    }
}

/**
 * Hands a reused View back to [AndroidView]. A pane's canvas can move to a fresh composition node
 * when the split opens or closes, and the new node parents the view itself, so it has to leave the
 * old parent first rather than throw.
 */
private fun <T : android.view.View> detached(view: T): T =
    view.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }

/**
 * "Exporting to PDF…" dialog shown while a (possibly large) note is flattened to a PDF off the main
 * thread. An animated spinner runs through the preparing/page phases (where there's no meaningful
 * percentage), then a determinate ring fills 0→100% during the final write. Dismissing it (Cancel,
 * back, or tapping outside) aborts the export via [onCancel]. Styled to match the monospace surfaces.
 */
@Composable
private fun PdfExportDialog(done: Int, total: Int, counting: String, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    // Only the final PDF write has a meaningful, moving percentage (reported as byte progress with
    // total = -1, done a 0..1000 permille). Preparing and the page phase sit at 0% (the page loop is
    // near-instant on the common path), so show an animated spinner there instead of a frozen 0% ring,
    // and switch to the determinate ring + percentage once writing begins.
    val writing = total < 0
    val fraction = if (writing) (done / 1000f).coerceIn(0f, 1f) else 0f
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = Space.s32, vertical = Space.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                if (writing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        trackColor = palette.border.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                    Text(
                        "${(fraction * 100).roundToInt()}%",
                        color = palette.text.toComposeColor(),
                        fontSize = TextSize.title,
                    )
                } else {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                }
            }
            Spacer(Modifier.height(Space.s20))
            Text(
                stringResource(R.string.exporting_pdf),
                color = palette.text.toComposeColor(),
                fontSize = TextSize.title,
            )
            Spacer(Modifier.height(Space.s4))
            Text(
                when {
                    writing -> stringResource(R.string.writing_pdf)
                    total == 0 -> stringResource(R.string.preparing)
                    done < total -> stringResource(if (counting == "item") R.string.export_item_progress else R.string.export_page_progress, done, total)
                    else -> pluralStringResource(if (counting == "item") R.plurals.export_writing_items else R.plurals.export_writing_pages, total, total)
                },
                color = palette.textDim.toComposeColor(),
                fontSize = TextSize.body,
            )
            Spacer(Modifier.height(Space.s16))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Indeterminate spinner dialog: a styled card with an animated [CircularProgressIndicator], a
 * [title], a "This may take a moment." line, and a Cancel button. Shared by the "Importing…"
 * (PDF/note copy) and "Opening note…" (off-thread read) flows, neither of which has a meaningful
 * percentage. Dismissing it (Cancel, back, or tapping outside) calls [onCancel]. Styled to match
 * [PdfExportDialog].
 */
@Composable
private fun SpinnerDialog(title: String, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = Space.s32, vertical = Space.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.accent.toComposeColor(),
                    strokeWidth = 4.dp,
                )
            }
            Spacer(Modifier.height(Space.s20))
            Text(title, color = palette.text.toComposeColor(), fontSize = TextSize.title)
            Spacer(Modifier.height(Space.s4))
            Text(stringResource(R.string.may_take_moment), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
            Spacer(Modifier.height(Space.s16))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Non-cancelable "Saving your notes…" dialog shown while a dirty note is flushed off the main thread
 * on close or pause, so quitting a large note shows progress instead of a frozen (ANR) screen.
 */
@Composable
private fun SavingDialog() {
    val palette = LocalPalette.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = Space.s32, vertical = Space.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.accent.toComposeColor(),
                    strokeWidth = 4.dp,
                )
            }
            Spacer(Modifier.height(Space.s20))
            Text(stringResource(R.string.saving_notes), color = palette.text.toComposeColor(), fontSize = TextSize.title)
            Spacer(Modifier.height(Space.s4))
            Text(stringResource(R.string.may_take_moment), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
        }
    }
}

/**
 * Indeterminate "Importing PDF…"/"Importing note…" dialog shown while a picked PDF (or `.xnote`) is
 * streamed into a new note off the main thread. Import has no natural page-by-page progress — the
 * cost is copying the (possibly large) source bytes — so it shows an animated spinner.
 */
@Composable
private fun PdfImportDialog(label: Int, onCancel: () -> Unit) = SpinnerDialog(stringResource(label), onCancel)

/**
 * Determinate "Importing PDFs…" dialog for a multi-file import: a ring filled by files finished plus
 * a running "%d of %d imported" line, so the count stays visible for the whole batch. Cancel stops
 * after the file in flight and keeps the ones already imported. Styled to match [PdfExportDialog].
 */
@Composable
private fun PdfImportBatchDialog(done: Int, total: Int, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = Space.s32, vertical = Space.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxSize(),
                    color = palette.accent.toComposeColor(),
                    trackColor = palette.border.toComposeColor(),
                    strokeWidth = 4.dp,
                )
                Text("$done/$total", color = palette.text.toComposeColor(), fontSize = TextSize.title)
            }
            Spacer(Modifier.height(Space.s20))
            Text(stringResource(R.string.importing_pdfs), color = palette.text.toComposeColor(), fontSize = TextSize.title)
            Spacer(Modifier.height(Space.s4))
            Text(
                stringResource(R.string.import_progress, done, total),
                color = palette.textDim.toComposeColor(),
                fontSize = TextSize.body,
            )
            Spacer(Modifier.height(Space.s16))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Progress of an export of explorer picks, styled like [PdfExportDialog]. It keeps the screen on, since a sleeping device
 * stalls a long batch, and ignores taps beside it, so a stray one can't throw the export away.
 */
@Composable
private fun PdfBatchExportDialog(p: com.xnotes.platform.PdfBatch.Progress, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        Column(
            modifier = Modifier
                .width(288.dp)
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = Space.s32, vertical = Space.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                if (p.count == 0) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                } else {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        trackColor = palette.border.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                    Text(
                        if (p.count > 1) "${p.index + 1}/${p.count}" else "${(p.fraction * 100).roundToInt()}%",
                        color = palette.text.toComposeColor(),
                        fontSize = TextSize.title,
                    )
                }
            }
            Spacer(Modifier.height(Space.s20))
            Text(
                stringResource(if (p.count == 1) R.string.exporting_pdf else R.string.exporting_pdfs),
                color = palette.text.toComposeColor(),
                fontSize = TextSize.title,
            )
            Spacer(Modifier.height(Space.s4))
            val canvas = com.xnotes.core.util.DocumentKind.ofName(p.name) == com.xnotes.core.util.DocumentKind.CANVAS
            val step = when {
                p.total < 0 || (p.total > 0 && p.done >= p.total) -> stringResource(R.string.export_writing_short)
                p.total > 0 -> stringResource(if (canvas) R.string.export_item_progress else R.string.export_page_progress, p.done + 1, p.total)
                else -> null
            }
            Text(
                if (p.count == 0) stringResource(R.string.preparing)
                else listOfNotNull(com.xnotes.core.util.DocumentKind.stripSuffix(p.name), step).joinToString(" · "),
                color = palette.textDim.toComposeColor(),
                fontSize = TextSize.body,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.s16))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Transient zoom-lock affordance, centred just below the toolbar. Appears when a pinch snaps to
 * fit-to-width ([Editor.zoomLockHint] bumps) and auto-dismisses after a moment. Tapping toggles the
 * zoom lock, so the same chip both locks and unlocks; each tap re-arms the dismiss timer so it
 * lingers long enough to tap again.
 */
@Composable
private fun BoxScope.ZoomLockHint(editor: Editor) {
    val palette = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    // Bumped on the initial fit-width snap and on every tap; (re)starts the auto-dismiss timer below.
    var armToken by remember { mutableStateOf(0) }
    LaunchedEffect(editor.zoomLockHint) {
        if (editor.zoomLockHint > 0) {
            visible = true
            armToken++
        }
    }
    LaunchedEffect(armToken) {
        if (armToken > 0) {
            delay(2500)
            visible = false
        }
    }
    // Breaking past the fit-width magnet dismisses the hint at once.
    LaunchedEffect(editor.zoomLockHintDismiss) {
        if (editor.zoomLockHintDismiss > 0) visible = false
    }
    val locked = editor.zoomLocked
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter).padding(com.xnotes.ui.LocalToolbarCover.current).padding(top = Space.s8),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { editor.toggleZoomLock(); armToken++ }
                .padding(horizontal = Space.s12, vertical = Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                contentDescription = if (locked) stringResource(R.string.unlock_zoom) else stringResource(R.string.lock_zoom_fit_width),
                tint = (if (locked) palette.accent else palette.textDim).toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.s8))
            Text(
                if (locked) stringResource(R.string.unlock_zoom) else stringResource(R.string.lock_zoom),
                color = palette.text.toComposeColor(),
                fontSize = TextSize.body,
            )
        }
    }
}

/** Queries the storage provider for a document's user-visible file name, if available. */
/** The types "Open with" and Share hand over for markdown, and its usual extensions. */
private val MARKDOWN_TYPES = setOf("text/markdown", "text/x-markdown")
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")

/** What the import filters take: a PDF becomes a note on its pages, markdown a note of its text. */
private val IMPORT_TYPES = MARKDOWN_TYPES + "application/pdf"

private fun displayNameOf(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0) c.getString(i) else null
        } else null
    }
}.getOrNull()
