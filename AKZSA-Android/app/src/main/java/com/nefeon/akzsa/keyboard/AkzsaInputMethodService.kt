package com.nefeon.akzsa.keyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.nefeon.akzsa.MainActivity
import com.nefeon.akzsa.commands.ActionPlan
import com.nefeon.akzsa.commands.ActionValidator
import com.nefeon.akzsa.commands.PlanKind
import com.nefeon.akzsa.commands.ValidationResult
import com.nefeon.akzsa.design.GraphiteSignal
import com.nefeon.akzsa.diagnostics.DiagnosticStore
import com.nefeon.akzsa.files.FileAiActivity
import com.nefeon.akzsa.files.FileIndexAutoMonitor
import com.nefeon.akzsa.files.FileIndexWorkScheduler
import com.nefeon.akzsa.files.LocalFileIndexer
import com.nefeon.akzsa.files.ClipEngine
import com.nefeon.akzsa.files.FileOpener
import com.nefeon.akzsa.files.FileSearch
import com.nefeon.akzsa.files.LocalFileSearch
import com.nefeon.akzsa.files.PrivateFileIndexStore
import com.nefeon.akzsa.files.IndexedPhoneFile
import com.nefeon.akzsa.network.ApiResult
import com.nefeon.akzsa.network.BackendClient
import com.nefeon.akzsa.network.CommandRequest
import com.nefeon.akzsa.network.FileSearchHit
import com.nefeon.akzsa.security.InstallId
import com.nefeon.akzsa.security.SecureTokenStore
import com.nefeon.akzsa.settings.AppSettings
import com.nefeon.akzsa.settings.SettingsRepository
import com.nefeon.akzsa.voice.SegmentResult
import com.nefeon.akzsa.voice.StreamingDictationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** System IME. Speech callbacks are session-based; partial recognition is shown but never inserted. */
class AkzsaInputMethodService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyRepeatHandler = Handler(Looper.getMainLooper())
    private var repeatingBackspaceView: View? = null
    private var backspaceRepeatTask: Runnable? = null
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var diagnosticStore: DiagnosticStore
    private var settings = AppSettings()

    private var keyboardRoot: LinearLayout? = null
    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var previewActions: LinearLayout? = null
    private var micButton: TextView? = null
    private var modeButton: TextView? = null
    private var languageButton: TextView? = null
    private var status = "Ready to type"
    private var commandMode = false
    private var writingMode = false
    private var shiftEnabled = false
    private var symbolsMode = false
    private var listening = false
    private var resultHandledForSession = false
    private var sessionCounter = 0
    private var passwordField = false
    private var recognizer: SpeechRecognizer? = null
    private var cloudRecorder: MediaRecorder? = null
    private var cloudAudioFile: File? = null
    private var cloudRecordingActive = false
    private var cloudUploadInProgress = false
    private var cloudUploadJob: Job? = null
    private var cloudRecordingSession = 0
    private var streamingSession: StreamingDictationSession? = null
    private var pendingPlan: ActionPlan? = null

    // Adaptive kbMetrics, typing layers and voice-session helpers
    private var kbMetrics: KeyboardMetrics = KeyboardLayoutPolicy.metrics(360f, 800f)
    private var sensitivity = FieldSensitivity.NORMAL
    private var hindiLayout = false
    private var hindiPage = 0
    private var capsLock = false
    private var lastShiftTapMs = 0L
    private var lastPartial = ""
    private var fileAiBrowse = false

    // File AI searches the app-private on-device index directly inside the IME. The separate
    // Activity is used only for one-time Android storage authorization and starting background indexing.
    private var fileAiMode = false
    private var fileAiQuery = ""
    private var fileAiSearching = false
    private var fileAiMessage = "Type or speak a file search, then tap Search."
    private var fileAiQueryView: TextView? = null
    private var fileAiMessageView: TextView? = null
    private var fileAiResultsView: LinearLayout? = null
    private var fileAiSearchButton: TextView? = null
    private var fileAiAttachButton: TextView? = null
    /** Only the files matching the latest search are held in memory; the full index stays in SQLite. */
    private var indexedPhoneFiles: List<IndexedPhoneFile> = emptyList()
    private var indexedPhoneFileCount: Int = 0
    private var currentFileSearchHits: List<FileSearchHit> = emptyList()
    private val selectedFileIds = linkedSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        FileIndexAutoMonitor.start(applicationContext)
        FileIndexWorkScheduler.ensurePeriodic(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        tokenStore = SecureTokenStore(applicationContext)
        diagnosticStore = DiagnosticStore(applicationContext)
        serviceScope.launch {
            try {
                settingsRepository.settings.collect { latest ->
                    settings = latest
                    if (!writingMode && !fileAiMode) commandMode = latest.aiCommandModeDefault
                    renderKeyboard()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setStatus("Settings could not be loaded")
            }
        }
    }

    override fun onCreateInputView(): View {
        keyboardRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor())
        }
        renderKeyboard()
        return keyboardRoot!!
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // One policy decides what is protected (passwords, PIN/OTP/CVV hints, incognito editors).
        sensitivity = SensitiveFieldPolicy.classify(EditorFieldInfo.from(attribute))
        passwordField = sensitivity != FieldSensitivity.NORMAL
        lastPartial = ""
        fileAiBrowse = false
        // A plan belongs to the editor that produced it; never carry it into another field/app.
        pendingPlan = null
        updatePendingPreview()
        if (passwordField) {
            // Never start voice recognition or read/send editor contents for password inputs.
            fileAiMode = false
            cancelRecognition(updateStatus = false)
            status = SensitiveFieldPolicy.statusMessage(sensitivity)
        } else {
            status = "Ready to type"
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateAutoShift()
        renderKeyboard()
    }

    /** Rotation, fold/unfold, split-screen, font-scale and dark-mode changes re-evaluate the kbMetrics policy. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderKeyboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Do not leave key repeats or microphone recording active if the keyboard is hidden/focus changes.
        stopBackspaceRepeat()
        cancelRecognition(updateStatus = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopBackspaceRepeat()
        cancelRecognition(updateStatus = false, destroy = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun renderKeyboard() {
        val root = keyboardRoot ?: return
        stopBackspaceRepeat()
        root.removeAllViews()
        root.setBackgroundColor(surfaceColor())
        recomputeLayout()
        applyRootPadding(root)
        if (kbMetrics.showHeader) {
            val header = TextView(this).apply {
                text = "AKZSA  ·  $status"
                textSize = 12f
                setTextColor(mutedTextColor())
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }
            statusView = header
            root.addView(header, LinearLayout.LayoutParams(-1, dp(kbMetrics.headerHeightDp)))
        } else {
            statusView = null   // low-height / Files mode: state is carried by the toolbar and the Files panel
        }

        addWeightedRow(root, listOf(
            Key(if (passwordField) "🔒 Voice off" else when { cloudUploadInProgress -> "⏳ Processing"; listening -> "■ Stop"; else -> "🎙 Talk" }, 1.4f, passwordField) { onMicrophoneTapped() },
            Key(if (passwordField) "LOCK" else when { writingMode -> "WRITE"; commandMode -> "CMD"; else -> "TYPE" }, 1.05f, passwordField) { cycleAssistantMode() },
            Key(when (settings.recognitionLanguage) {
                "en-IN" -> "EN-IN"
                "hi-IN" -> "HI-IN"
                "kn-IN" -> "KN-IN"
                "hinglish" -> "HINGLISH"
                else -> "EN-US"
            }, 1.15f) { cycleLanguage() },
            Key(if (fileAiMode) "FILE ✓" else "FILE", 0.9f, fileAiMode) { toggleFileAiMode() },
            Key("⚙", 0.7f) { openApp() },
            Key("⌄", 0.7f) { requestHideSelf(0) },
        ), kbMetrics.toolbarHeightDp, toolbar = true)

        if (fileAiMode && !passwordField) addFileAiPanel(root)

        val preview = TextView(this).apply {
            textSize = 12f
            setTextColor(textColor())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            maxLines = 3
            visibility = View.GONE
        }
        previewView = preview
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        previewActions = actionRow
        root.addView(actionRow, LinearLayout.LayoutParams(-1, dp(kbMetrics.actionRowHeightDp)))
        updatePendingPreview()

        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val numeric = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE || inputClass == InputType.TYPE_CLASS_DATETIME
        if (fileAiMode && fileAiBrowse && !passwordField) {
            // Files mode: the results surface temporarily replaces the keys. "Edit query" brings them back.
        } else if (numeric) {
            addCharRow(root, listOf("1", "2", "3"))
            addCharRow(root, listOf("4", "5", "6"))
            addCharRow(root, listOf("7", "8", "9"))
            addWeightedRow(root, listOf(
                Key("-", 1f) { typeText("-") }, Key("+", 1f) { typeText("+") },
                Key("0", 1f) { typeText("0") }, Key(".", 1f) { typeText(".") },
                Key("⌫", 1.2f, true) { backspace() },
            ), kbMetrics.keyRowHeightDp)
            addWeightedRow(root, listOf(Key("*", 1f) { typeText("*") }, Key("#", 1f) { typeText("#") }, Key(enterKeyLabel(inputType), 2f, true) { pressEnter() }), kbMetrics.keyRowHeightDp)
        } else if (symbolsMode) {
            addCharRow(root, listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            addCharRow(root, listOf("@", "#", "\$", "%", "&", "-", "+", "*"))
            addWeightedRow(root, listOf("/", "(", ")", ":", ";", "⌫").map { label ->
                Key(label, 1f, label == "⌫") { if (label == "⌫") backspace() else typeText(label) }
            }, kbMetrics.keyRowHeightDp)
            addBottomRow(root, inputType)
        } else if (hindiLayout) {
            val rows = if (hindiPage == 0) DevanagariLayout.page0 else DevanagariLayout.page1
            rows.forEachIndexed { index, chars ->
                if (index == rows.lastIndex) {
                    val keys = chars.map { c -> Key(c, 1f) { typeText(c) } }.toMutableList()
                    keys.add(0, Key(if (hindiPage == 0) "अआ" else "कख", 1.3f, true) { hindiPage = 1 - hindiPage; renderKeyboard() })
                    keys.add(Key("⌫", 1.3f, true) { backspace() })
                    addWeightedRow(root, keys, kbMetrics.keyRowHeightDp)
                } else {
                    addCharRow(root, chars)
                }
            }
            addBottomRow(root, inputType)
        } else {
            addCharRow(root, "qwertyuiop".map { it.toString() })
            addCharRow(root, "asdfghjkl".map { it.toString() })
            val third = mutableListOf<Key>(Key(if (capsLock) "⇪" else "⇧", 1.3f, true) { onShiftTapped() })
            "zxcvbnm".forEach { c -> third.add(Key(if (shiftEnabled) c.uppercaseChar().toString() else c.toString(), 1f) { typeText(if (shiftEnabled) c.uppercaseChar().toString() else c.toString()) }) }
            third.add(Key("⌫", 1.3f, true) { backspace() })
            addWeightedRow(root, third, kbMetrics.keyRowHeightDp)
            addBottomRow(root, inputType)
        }
        micButton = null
        modeButton = null
        languageButton = null
    }

    private fun toggleFileAiMode() {
        if (passwordField) {
            setStatus("File search is disabled while a protected field is focused")
            return
        }
        fileAiMode = !fileAiMode
        fileAiBrowse = false
        if (fileAiMode) {
            commandMode = false
            writingMode = false
            fileAiMessage = "Search the private on-device index here. If this is the first run, tap Index phone once to start background indexing."
            serviceScope.launch { loadIndexedPhoneFiles() }
            setStatus("FILE AI · searches stay in the keyboard")
        } else {
            setStatus("File AI closed")
        }
        renderKeyboard()
    }

    private fun addFileAiPanel(root: LinearLayout) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(palette().surfaceElevated)
                setStroke(dp(1), borderColor())
            }
        }
        // Obvious one-tap way back to typing, plus a toggle between the results surface and the letter keys.
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navRow.addView(makeFileAiButton("← Typing") { toggleFileAiMode() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        navRow.addView(makeFileAiButton(if (fileAiBrowse) "⌨ Edit query" else "▤ Results") { fileAiBrowse = !fileAiBrowse; renderKeyboard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        panel.addView(navRow)
        panel.addView(TextView(this).apply {
            text = "FILE AI  ·  Private on-device search"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textColor())
            setPadding(dp(2), dp(4), 0, 0)
        })
        fileAiQueryView = TextView(this).apply {
            textSize = 14f
            setTextColor(textColor())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minHeight = dp(40)
            maxLines = 2
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(keyColor())
                setStroke(dp(1), borderColor())
            }
        }
        panel.addView(fileAiQueryView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = makeFileAiButton(if (fileAiSearching) "Searching…" else "Search") { runFileAiSearch() }
        fileAiSearchButton = search
        buttonRow.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        buttonRow.addView(makeFileAiButton("Index phone") { openFileAiSetup() }, LinearLayout.LayoutParams(0, dp(48), 1.05f).apply { setMargins(dp(2), 0, dp(2), 0) })
        buttonRow.addView(makeFileAiButton("Clear") {
            fileAiQuery = ""
            updateFileAiQueryUi()
            fileAiResultsView?.removeAllViews()
            selectedFileIds.clear()
            currentFileSearchHits = emptyList()
            updateFileAiAttachButton()
            fileAiMessage = "Query cleared. Type or speak a search request."
            fileAiMessageView?.text = fileAiMessage
            if (fileAiBrowse) { fileAiBrowse = false; renderKeyboard() }   // bring the letter keys back
        }, LinearLayout.LayoutParams(0, dp(48), 0.8f).apply { setMargins(dp(2), 0, dp(2), 0) })
        panel.addView(buttonRow)

        fileAiMessageView = TextView(this).apply {
            text = fileAiMessage
            textSize = 12f
            setTextColor(mutedTextColor())
            maxLines = 3
        }
        panel.addView(fileAiMessageView, LinearLayout.LayoutParams(-1, -2))
        val resultScroll = ScrollView(this).apply {
            isFillViewport = false
            visibility = View.VISIBLE
        }
        fileAiResultsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultScroll.addView(fileAiResultsView, LinearLayout.LayoutParams(-1, -2))
        panel.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(if (fileAiBrowse) kbMetrics.fileResultsHeightDp else 76)))
        fileAiAttachButton = makeFileAiButton("Attach selected (0)") { attachSelectedFiles() }
        panel.addView(fileAiAttachButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(3) })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(1), dp(3), dp(1), dp(3))
        })
        updateFileAiQueryUi()
        // A re-render (shift, rotation, mode change) must not wipe results the user is looking at.
        if (currentFileSearchHits.isNotEmpty()) renderInlineFileResults(currentFileSearchHits, restore = true)
        serviceScope.launch { loadIndexedPhoneFiles() }
    }

    private fun makeFileAiButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        contentDescription = KeyLabels.describe(label)
        val primary = label == "Search" && !fileAiSearching
        setTextColor(if (primary) onAccentColor() else textColor())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (primary) accentColor() else specialKeyColor())
            setStroke(dp(if (primary) 2 else 1), if (primary) focusColor() else borderColor())
        }
        setOnClickListener {
            if (settings.hapticFeedback) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun updateFileAiQueryUi() {
        fileAiQueryView?.text = fileAiQuery.ifBlank { "Type or use 🎙 Talk to enter a file query…" }
        fileAiSearchButton?.text = if (fileAiSearching) "Searching…" else "Search"
        updateFileAiAttachButton()
    }

    private fun setFileAiQueryFromSpeech(transcript: String) {
        fileAiQuery = transcript.trim().take(300)
        updateFileAiQueryUi()
        fileAiMessage = "Voice query captured. Searching the private on-device index…"
        fileAiMessageView?.text = fileAiMessage
        setStatus("File AI searching locally")
        runFileAiSearch()
    }

    private suspend fun loadIndexedPhoneFiles() {
        try {
            indexedPhoneFileCount = withContext(Dispatchers.IO) {
                PrivateFileIndexStore(applicationContext).use { it.count() }
            }
            val prefs = getSharedPreferences("akzsa_file_ai", MODE_PRIVATE)
            val indexing = prefs.getBoolean("index_work_running", false)
            if (fileAiMode) {
                fileAiMessage = when {
                    indexedPhoneFileCount == 0 && indexing -> "First-time indexing is running in the background. Files will appear as each batch is saved."
                    indexedPhoneFileCount == 0 -> "No private index yet. Tap Index phone once to grant storage access and start background indexing."
                    indexing -> "Searching $indexedPhoneFileCount locally indexed files; background indexing continues. Nothing is uploaded."
                    else -> "Private local index ready: $indexedPhoneFileCount files. Searches stay on this phone."
                }
                fileAiMessageView?.text = fileAiMessage
            }
        } catch (_: Exception) {
            indexedPhoneFiles = emptyList()
            indexedPhoneFileCount = 0
            if (fileAiMode) {
                fileAiMessage = "Could not read AKZSA's private file index. Open Index phone to check storage permission or resume indexing."
                fileAiMessageView?.text = fileAiMessage
            }
        }
    }

    private fun openFileAiSetup() {
        if (passwordField) return
        // If access was already granted once, start/refresh indexing right here so no other screen
        // opens and the keyboard stays visible. Only open the setup screen when Android permission
        // or a folder choice is still missing (that step needs a real Activity).
        val prefs = getSharedPreferences("akzsa_file_ai", MODE_PRIVATE)
        val treeUri = prefs.getString("selected_tree_uri", null)
        val hasAccess = LocalFileIndexer.hasFullSharedStorageAccess(applicationContext)
        if (hasAccess || !treeUri.isNullOrBlank()) {
            try {
                FileIndexWorkScheduler.enqueueInitial(applicationContext, if (hasAccess) null else treeUri)
                fileAiMessage = "Indexing started in the background. You can keep searching; results appear as batches finish."
                fileAiMessageView?.text = fileAiMessage
                serviceScope.launch { loadIndexedPhoneFiles() }
                return
            } catch (_: Exception) {
                // fall through to the setup screen
            }
        }
        try {
            // First-time permission / folder picker must be launched from an Activity.
            startActivity(Intent(this, FileAiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            fileAiMessage = "Open AKZSA File AI storage setup once to grant access and index your files."
            fileAiMessageView?.text = fileAiMessage
        }
    }

    private fun runFileAiSearch() {
        if (!fileAiMode || fileAiSearching) return
        val query = fileAiQuery.trim()
        if (query.length < 2) {
            fileAiMessage = "Enter or speak a file search first."
            fileAiMessageView?.text = fileAiMessage
            return
        }
        if (passwordField) {
            fileAiMessage = "File search is disabled for protected fields."
            fileAiMessageView?.text = fileAiMessage
            return
        }
        fileAiSearching = true
        fileAiResultsView?.removeAllViews()
        fileAiMessage = "Searching $indexedPhoneFileCount files locally…"
        fileAiMessageView?.text = fileAiMessage
        updateFileAiQueryUi()
        serviceScope.launch {
            try {
                // Reload the private SQLite index so newly completed background batches become searchable.
                loadIndexedPhoneFiles()
                if (indexedPhoneFileCount <= 0) {
                    fileAiMessage = "No local index yet. Tap Index phone once to authorize storage and start background indexing."
                    fileAiMessageView?.text = fileAiMessage
                    return@launch
                }
                val (hits, matchedFiles) = withContext(Dispatchers.IO) {
                    PrivateFileIndexStore(applicationContext).use { store ->
                        val found = FileSearch.search(this@AkzsaInputMethodService, query, store, limit = 3)
                        found to store.findByIds(found.map { it.file_id })
                    }
                }
                indexedPhoneFiles = matchedFiles
                val indexing = getSharedPreferences("akzsa_file_ai", MODE_PRIVATE)
                    .getBoolean("index_work_running", false)
                fileAiMessage = when {
                    hits.isEmpty() && indexing -> "No matches in the indexed portion yet. Background indexing is still running; try again later."
                    hits.isEmpty() -> "No matches in the private index. New files are picked up automatically; you can request a refresh."
                    indexing -> "Top ${hits.size} local matches from $indexedPhoneFileCount indexed files · background scan continues · no upload"
                    else -> "Top ${hits.size} local matches from $indexedPhoneFileCount files · private on-device search"
                }
                fileAiMessageView?.text = fileAiMessage
                renderInlineFileResults(hits)
                if (hits.isNotEmpty()) { fileAiBrowse = true; renderKeyboard() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Throwable (not just Exception) so out-of-memory or native-library errors can't kill the keyboard.
                if (error is OutOfMemoryError) ClipEngine.release(textToo = false)
                fileAiMessage = "Local file search failed: ${error.message ?: "Unexpected error"}"
                fileAiMessageView?.text = fileAiMessage
            } finally {
                fileAiSearching = false
                updateFileAiQueryUi()
            }
        }
    }

    private fun renderInlineFileResults(hits: List<FileSearchHit>, restore: Boolean = false) {
        val container = fileAiResultsView ?: return
        container.removeAllViews()
        currentFileSearchHits = hits.take(3)
        if (!restore) selectedFileIds.clear()
        updateFileAiAttachButton()
        if (currentFileSearchHits.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No matches. Try broader words, or tap Index phone to refresh the local index."
                textSize = 12f
                setTextColor(textColor())
            })
            return
        }
        currentFileSearchHits.forEach { hit ->
            val file = indexedPhoneFiles.firstOrNull { it.fileId == hit.file_id } ?: return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(4), dp(5), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(keyColor())
                    setStroke(dp(1), borderColor())
                }
            }
            val selectRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val check = CheckBox(this).apply {
                isChecked = file.fileId in selectedFileIds
                text = file.filename    // no invented confidence number: the reason line below says why it matched
                textSize = 13f
                maxLines = 2
                setTextColor(textColor())
                setPadding(0, 0, dp(3), 0)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedFileIds.add(file.fileId) else selectedFileIds.remove(file.fileId)
                    updateFileAiAttachButton()
                }
            }
            selectRow.addView(check, LinearLayout.LayoutParams(-1, -2))
            card.addView(selectRow)
            card.addView(TextView(this).apply {
                text = "${hit.reason}${if (file.relativePath.isNotBlank()) "\n${file.relativePath.take(160)}" else ""}"
                textSize = 11f
                maxLines = 2
                setTextColor(mutedTextColor())
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(makeFileAiButton("Open") { openIndexedPhoneFile(file) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), 0) })
            actions.addView(makeFileAiButton("Insert details") { insertFileHitDetails(hit) }, LinearLayout.LayoutParams(0, dp(44), 1.2f).apply { setMargins(dp(2), dp(2), dp(2), 0) })
            actions.addView(makeFileAiButton("Share") { shareIndexedPhoneFile(file) }, LinearLayout.LayoutParams(0, dp(44), 0.8f).apply { setMargins(dp(2), dp(2), dp(2), 0) })
            card.addView(actions)
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        updateFileAiAttachButton()
    }

    private fun updateFileAiAttachButton() {
        fileAiAttachButton?.text = "Attach selected (${selectedFileIds.size})"
    }


    private fun openIndexedPhoneFile(file: IndexedPhoneFile) {
        val message = when (FileOpener.open(this, file, newTask = true)) {
            FileOpener.OpenResult.Opened -> return
            FileOpener.OpenResult.Missing -> "${file.filename} was moved or deleted. Tap Index phone to refresh the list."
            FileOpener.OpenResult.NoApp -> "No app can open this file type. Try Share."
        }
        fileAiMessage = message
        fileAiMessageView?.text = message
    }

    private fun shareIndexedPhoneFile(file: IndexedPhoneFile) {
        shareFiles(listOf(file))
    }

    /** Try native IME content insertion only when the receiving editor declares a matching MIME
     * type. Apps that don't support file content from keyboards get the standard Android share
     * chooser; AKZSA never claims an unsupported attachment succeeded. */
    private fun attachSelectedFiles() {
        val selected = currentFileSearchHits.filter { it.file_id in selectedFileIds }
            .mapNotNull { hit -> indexedPhoneFiles.firstOrNull { it.fileId == hit.file_id } }
        if (selected.isEmpty()) {
            fileAiMessage = "Select one or more of the top three results first."
            fileAiMessageView?.text = fileAiMessage
            return
        }
        val ic = currentInputConnection
        val editor = currentInputEditorInfo
        val supportedMimeTypes = editor?.let { EditorInfoCompat.getContentMimeTypes(it).toList() }.orEmpty()
        val allSupported = ic != null && editor != null && selected.all { file ->
            supportedMimeTypes.any { supported -> ClipDescription.compareMimeTypes(file.mimeType, supported) }
        }
        if (allSupported) {
            var committed = 0
            for (file in selected) {
                try {
                    val uri = LocalFileIndexer.shareableUri(this, file.uri)
                    val content = InputContentInfoCompat(uri, ClipDescription(file.filename, arrayOf(file.mimeType)), null)
                    val accepted = InputConnectionCompat.commitContent(
                        ic!!, editor!!, content,
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        Bundle().apply { putString("akzsa_filename", file.filename) },
                    )
                    if (!accepted) break
                    committed++
                } catch (_: Exception) { break }
            }
            if (committed == selected.size) {
                fileAiMessage = "Sent ${committed} file attachment(s) to the focused editor."
                fileAiMessageView?.text = fileAiMessage
                selectedFileIds.clear()
                updateFileAiAttachButton()
                return
            }
            val remaining = selected.drop(committed)
            if (remaining.isNotEmpty()) shareFiles(remaining)
            fileAiMessage = if (committed > 0) "${committed} file(s) sent to the editor; opening Share for the remaining ${remaining.size}." else "The editor did not accept file content. Opening Android Share to continue."
            fileAiMessageView?.text = fileAiMessage
            return
        }
        fileAiMessage = "This chat editor does not advertise keyboard file attachments. Opening Android Share; choose the target app to continue."
        fileAiMessageView?.text = fileAiMessage
        shareFiles(selected)
    }

    private fun shareFiles(files: List<IndexedPhoneFile>) {
        try {
            val uris = files.map { LocalFileIndexer.shareableUri(this, it.uri) }
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
            send.type = if (uris.size == 1) files.first().mimeType.ifBlank { "*/*" } else "*/*"
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newUri(contentResolver, files.first().filename, uris.first())
            uris.drop(1).forEachIndexed { index, uri -> clip.addItem(ClipData.Item(uri)) }
            send.clipData = clip
            if (uris.size == 1) send.putExtra(Intent.EXTRA_STREAM, uris.first())
            else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            startActivity(Intent.createChooser(send, "Attach selected files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            fileAiMessage = "Could not share the selected file(s). Check storage permission and try again."
            fileAiMessageView?.text = fileAiMessage
        }
    }

    private fun insertFileHitDetails(hit: FileSearchHit) {
        if (!ensureInputConnection()) return
        val details = buildString {
            append(hit.filename)
            if (hit.gstins.isNotEmpty()) append(" · GSTIN: ").append(hit.gstins.joinToString())
            if (hit.invoice_numbers.isNotEmpty()) append(" · Invoice: ").append(hit.invoice_numbers.joinToString())
            if (hit.amounts.isNotEmpty()) append(" · Amounts: ").append(hit.amounts.joinToString())
            if (hit.reason.isNotBlank()) append(" · ").append(hit.reason)
        }.take(1600)
        currentInputConnection?.commitText(details, 1)
        fileAiMessage = "Inserted file details into the focused text field."
        fileAiMessageView?.text = fileAiMessage
    }

    private fun addBottomRow(root: LinearLayout, inputType: Int) {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isEmail = inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val isUri = inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        val bottom = mutableListOf<Key>()
        bottom += Key(if (symbolsMode) "ABC" else "?123", 1.25f, true) { symbolsMode = !symbolsMode; renderKeyboard() }
        // English <-> Hindi typing layer (Hinglish is typed with the English layer).
        bottom += Key(if (hindiLayout) "A/अ" else "अ/A", 0.95f, true) { hindiLayout = !hindiLayout; hindiPage = 0; symbolsMode = false; renderKeyboard() }
        if (canSwitchKeyboard()) bottom += Key("⌨", 0.8f, true) { switchKeyboard() }
        bottom += Key(",", 0.7f) { typeText(",") }
        if (isEmail) bottom += Key("@", 0.8f) { typeText("@") }
        if (isUri) bottom += Key(".com", 1.0f) { typeText(".com") }
        bottom += Key("space", 2.6f) { typeText(" ") }
        bottom += if (hindiLayout && !symbolsMode) Key("।", 0.8f) { typeText("।") } else Key(".", 0.8f) { typeText(".") }
        bottom += Key(enterKeyLabel(inputType), 1.2f, true) { pressEnter() }
        addWeightedRow(root, bottom, kbMetrics.keyRowHeightDp)
    }

    private fun enterKeyLabel(inputType: Int): String {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val isMultilineText = inputClass == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (isMultilineText) return "↵"
        return when ((currentInputEditorInfo?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> "↵"
        }
    }

    private data class Key(val label: String, val weight: Float = 1f, val special: Boolean = false, val action: () -> Unit)

    private fun addCharRow(root: LinearLayout, chars: List<String>) {
        addWeightedRow(root, chars.map { char ->
            Key(if (shiftEnabled) char.uppercase(Locale.ROOT) else char, 1f) {
                typeText(if (shiftEnabled) char.uppercase(Locale.ROOT) else char)
            }
        }, kbMetrics.keyRowHeightDp)
    }

    private fun addWeightedRow(root: LinearLayout, keys: List<Key>, heightDp: Int, toolbar: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keys.forEach { key ->
            val view = TextView(this).apply {
                text = key.label
                textSize = when {
                    key.label == "space" -> 12f
                    key.label.length >= 4 -> 12f
                    else -> kbMetrics.keyLabelSp
                }
                gravity = Gravity.CENTER
                maxLines = 1
                val toolbarAccent = toolbar && (
                    key.label in setOf("🎙 Talk", "■ Stop", "⏳ Processing") ||
                    (fileAiMode && key.label == "FILE ✓")
                )
                val accentFill = toolbarAccent || key.label == "Apply"
                setTextColor(if (accentFill) onAccentColor() else textColor())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(if (toolbar) 12 else 8).toFloat()
                    setColor(when {
                        accentFill -> accentColor()
                        key.special -> specialKeyColor()
                        else -> keyColor()
                    })
                    // Lime has ~1.2:1 contrast on Ivory, so active/accent keys get a dark-olive outline (focus token).
                    setStroke(dp(if (accentFill) 2 else 1), if (accentFill) focusColor() else borderColor())
                }
                isHapticFeedbackEnabled = settings.hapticFeedback
                setPadding(dp(1), 0, dp(1), 0)
                contentDescription = KeyLabels.describe(key.label)
                if (key.label == "⌫") {
                    // Accessibility click is supported separately. During a touch gesture the
                    // delete already happened on ACTION_DOWN, so the UP-click must not delete twice.
                    setOnClickListener {
                        if (repeatingBackspaceView == null) backspace()
                    }
                    // Delete once immediately, then repeat while the user keeps the key pressed.
                    setOnTouchListener { touchedView, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchedView.isPressed = true
                                if (settings.hapticFeedback) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                startBackspaceRepeat(touchedView)
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                touchedView.isPressed = false
                                touchedView.performClick() // announces the key to accessibility; no duplicate delete
                                stopBackspaceRepeat(touchedView)
                                true
                            }
                            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                                touchedView.isPressed = false
                                stopBackspaceRepeat(touchedView)
                                true
                            }
                            MotionEvent.ACTION_MOVE -> true
                            else -> true
                        }
                    }
                } else {
                    setOnClickListener {
                        if (settings.hapticFeedback) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        key.action()
                    }
                }
            }
            val gap = dp(maxOf(1, kbMetrics.keyGapDp / 2))
            val lp = LinearLayout.LayoutParams(0, dp(heightDp), key.weight).apply {
                setMargins(gap, gap, gap, gap)
            }
            row.addView(view, lp)
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(heightDp) + 2 * dp(maxOf(1, kbMetrics.keyGapDp / 2))))
    }

    private fun typeText(value: String) {
        if (fileAiMode) {
            if (fileAiQuery.length < 300) fileAiQuery = (fileAiQuery + value).take(300)
            updateFileAiQueryUi()
            fileAiMessage = "Query ready. Tap Search to rank matching files."
            fileAiMessageView?.text = fileAiMessage
            return
        }
        if (!ensureInputConnection()) return
        currentInputConnection?.commitText(value, 1)
        refreshShiftAfterEdit()
    }

    private fun onShiftTapped() {
        val now = SystemClock.uptimeMillis()
        when {
            capsLock -> { capsLock = false; shiftEnabled = false }
            now - lastShiftTapMs < 350L -> { capsLock = true; shiftEnabled = true }   // double-tap = caps lock
            else -> shiftEnabled = !shiftEnabled
        }
        lastShiftTapMs = now
        renderKeyboard()
    }

    /** One-shot Shift is consumed after a character; sentence-start fields turn it on again automatically. */
    private fun refreshShiftAfterEdit() {
        if (capsLock) return
        val before = shiftEnabled
        shiftEnabled = false
        updateAutoShift()
        if (shiftEnabled != before) renderKeyboard()
    }

    private fun updateAutoShift() {
        if (capsLock || passwordField || hindiLayout) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) { shiftEnabled = false; return }
        shiftEnabled = DictationFormatter.wantsCapital(ic.getCursorCapsMode(info.inputType))
    }

    /** Always offer a way out to the previous/next keyboard so AKZSA can never trap the user. */
    private fun canSwitchKeyboard(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val token = window?.window?.attributes?.token ?: return true
            return imm.shouldOfferSwitchingToNextInputMethod(token)
        }
        return true
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToNextInputMethod(false)) return
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun commitDictation(spokenText: String) {
        // Local clean-up first: spoken punctuation ("comma", "new line") and the user's personal dictionary.
        val value = TranscriptCleaner.clean(spokenText.trim(), settings.dictionaryTerms, settings.spokenPunctuation)
        val ic = currentInputConnection ?: run { setStatus("The text field is no longer available"); return }
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val safeToAppendSpace = inputClass == InputType.TYPE_CLASS_TEXT && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        val text = DictationFormatter.format(
            transcript = value,
            textBeforeCursor = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty(),   // local only, never stored or sent
            hasSelection = !ic.getSelectedText(0).isNullOrEmpty(),
            spaceSafe = safeToAppendSpace,
            appendTrailingSpace = settings.appendSpaceAfterDictation,
            capitalizeFirst = DictationFormatter.wantsCapital(ic.getCursorCapsMode(inputType)),
        )
        if (text.isEmpty()) return
        ic.commitText(text, 1)
        refreshShiftAfterEdit()
    }

    private fun startBackspaceRepeat(view: View) {
        stopBackspaceRepeat()
        repeatingBackspaceView = view
        backspace()
        val task = object : Runnable {
            override fun run() {
                if (repeatingBackspaceView !== view || !view.isPressed) {
                    stopBackspaceRepeat(view)
                    return
                }
                backspace()
                // Around 20 deletions per second after the long-press delay feels responsive
                // without flooding slower InputConnection implementations.
                keyRepeatHandler.postDelayed(this, 50L)
            }
        }
        backspaceRepeatTask = task
        keyRepeatHandler.postDelayed(task, 360L)
    }

    private fun stopBackspaceRepeat(view: View? = null) {
        if (view != null && repeatingBackspaceView !== view) return
        backspaceRepeatTask?.let(keyRepeatHandler::removeCallbacks)
        backspaceRepeatTask = null
        repeatingBackspaceView = null
    }

    private fun backspace() {
        if (fileAiMode) {
            if (fileAiQuery.isNotEmpty()) {
                val end = fileAiQuery.offsetByCodePoints(fileAiQuery.length, -1)
                fileAiQuery = fileAiQuery.substring(0, end)
                updateFileAiQueryUi()
            }
            return
        }
        if (!ensureInputConnection()) return
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            ic.commitText("", 1)
            return
        }
        // Delete one Unicode code point (emoji/supplementary symbols included) when supported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && ic.deleteSurroundingTextInCodePoints(1, 0)) { refreshShiftAfterEdit(); return }
        val before = ic.getTextBeforeCursor(16, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val units = Character.charCount(Character.codePointBefore(before, before.length))
        ic.deleteSurroundingText(units, 0)
        refreshShiftAfterEdit()
    }

    private fun pressEnter() {
        if (fileAiMode) {
            runFileAiSearch()
            return
        }
        if (!ensureInputConnection()) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val inputType = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        val imeOptions = info?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED
        when (EnterKeyPolicy.decide(inputType, imeOptions)) {
            EnterKeyPolicy.Behavior.INSERT_NEWLINE -> {
                ic.commitText("\n", 1)
            }
            EnterKeyPolicy.Behavior.PERFORM_EDITOR_ACTION -> {
                val action = imeOptions and EditorInfo.IME_MASK_ACTION
                if (!ic.performEditorAction(action)) sendEnterKeyEvents(ic)
            }
            EnterKeyPolicy.Behavior.SEND_ENTER_KEY -> {
                // Single-line fields should not receive an inserted newline. Send Enter to the host.
                sendEnterKeyEvents(ic)
            }
        }
    }

    private fun sendEnterKeyEvents(ic: InputConnection) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun onMicrophoneTapped() {
        if (pendingPlan != null) {
            setStatus("Review or cancel the pending action before starting another session")
            return
        }
        if (passwordField) {
            setStatus("Voice disabled for protected fields")
            return
        }
        if (cloudUploadInProgress) {
            setStatus("Cloud transcription is processing · please wait")
            return
        }
        if (listening) {
            if (cloudRecordingActive) stopCloudRecording()
            else {
                try { recognizer?.stopListening() } catch (_: Exception) { cancelRecognition(updateStatus = false) }
                setStatus("Finishing speech recognition…")
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            setStatus("Grant microphone access in AKZSA first")
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                })
            } catch (_: Exception) {
                Toast.makeText(this, "Open AKZSA and grant microphone permission, then return to your text field.", Toast.LENGTH_LONG).show()
            }
            return
        }
        // File AI queries are recognized on-device so the search phrase itself is not uploaded.
        // Cloud dictation remains available for ordinary TYPE mode when explicitly enabled.
        if (fileAiMode) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                setStatus("No on-device speech recognition service is available for private FILE search")
                return
            }
            startRecognition()
            return
        }
        if (settings.cloudDictationEnabled) {
            startCloudRecording()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("No speech recognition service is available")
            return
        }
        startRecognition()
    }

    /** Live cloud dictation: phrases are cut at pauses, transcribed and inserted while the user keeps talking. */
    private fun startStreamingDictation(): Boolean {
        if (tokenStore.readToken().isNullOrBlank()) return false
        val session = sessionCounter + 1
        val language = settings.recognitionLanguage
        val dictionary = UserDictionary.promptTerms(settings.dictionaryTerms)
        val baseUrl = settings.backendBaseUrl
        val installId = InstallId.get(applicationContext)
        sessionCounter = session
        val streaming = StreamingDictationSession(
            scope = serviceScope,
            noiseReduction = settings.noiseReduction,
            transcribe = { wav, contextTail -> transcribeStreamedPhrase(wav, contextTail, baseUrl, language, dictionary, installId) },
            onText = { text -> if (session == sessionCounter && !passwordField && currentInputConnection != null) commitDictation(text) },
            onStatus = { message -> if (session == sessionCounter) setStatus(message) },
            onFinished = { reason -> if (session == sessionCounter) finishStreamingDictation(reason) },
        )
        if (!streaming.start()) {
            sessionCounter = session - 1
            return false
        }
        streamingSession = streaming
        cloudRecordingActive = true
        listening = true
        cloudRecordingSession = session
        resultHandledForSession = false
        pendingPlan = null
        updatePendingPreview()
        renderKeyboard()
        return true
    }

    private suspend fun transcribeStreamedPhrase(
        wav: ByteArray,
        contextTail: String,
        baseUrl: String,
        language: String,
        dictionary: String,
        installId: String,
    ): SegmentResult {
        val token = tokenStore.readToken()
        if (token.isNullOrBlank()) return SegmentResult.Failed("No testing token saved. Open AKZSA Settings.", retryable = false, fatal = true)
        return when (val response = BackendClient.transcribeSegment(baseUrl, token, wav, language, dictionary, contextTail, installId)) {
            is ApiResult.Success -> {
                diagnosticStore.recordBackendSuccess(response.statusCode, response.latencyMs, "success")
                val text = response.value.text.trim().let { if (language == "hinglish") HinglishTransliterator.toRoman(it) else it }
                if (text.isBlank()) SegmentResult.Silent else SegmentResult.Text(text)
            }
            is ApiResult.Failure -> when (response.statusCode) {
                422 -> SegmentResult.Silent
                401, 403 -> SegmentResult.Failed(response.message, retryable = false, fatal = true)
                413, 415 -> SegmentResult.Failed(response.message, retryable = false)
                else -> SegmentResult.Failed(response.message, retryable = true)
            }
        }
    }

    private fun finishStreamingDictation(reason: String?) {
        streamingSession = null
        cloudRecordingActive = false
        listening = false
        cloudUploadInProgress = false
        renderKeyboard()
        setStatus(reason ?: "Cloud dictation finished")
    }

    /** Records a user-started voice session locally, then uploads it only when the user taps Talk again. */
    private fun startCloudRecording() {
        // Live mode for plain dictation; commands/writing keep the one-shot path. Falls back if the microphone cannot be opened.
        if (settings.streamingDictation && !commandMode && !writingMode && !fileAiMode && startStreamingDictation()) return
        val file = File(cacheDir, "akzsa-dictation-${System.currentTimeMillis()}.m4a")
        var created: MediaRecorder? = null
        try {
            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            created = recorder
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16_000)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(file.absolutePath)
            recorder.setMaxDuration(180_000)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && cloudRecordingActive) {
                    serviceScope.launch { stopCloudRecording() }
                }
            }
            recorder.prepare()
            recorder.start()
            cloudRecorder = recorder
            cloudAudioFile = file
            cloudRecordingActive = true
            listening = true
            cloudRecordingSession = ++sessionCounter
            resultHandledForSession = false
            pendingPlan = null
            updatePendingPreview()
            renderKeyboard()
            setStatus("Recording · tap Stop to finish (max 3 minutes)")
        } catch (_: Exception) {
            try { created?.release() } catch (_: Exception) { }
            file.delete()
            cloudRecorder = null
            cloudAudioFile = null
            cloudRecordingActive = false
            listening = false
            setStatus("Could not start cloud recording. Check microphone permission and device support.")
        }
    }

    private fun stopCloudRecording() {
        if (!cloudRecordingActive) return
        streamingSession?.let { streaming ->
            cloudRecordingActive = false
            listening = false
            cloudUploadInProgress = true   // keyboard shows "Processing" until the last phrase is inserted
            streaming.stop()
            renderKeyboard()
            setStatus("Finishing the last phrase...")
            return
        }
        val recorder = cloudRecorder
        val file = cloudAudioFile
        cloudRecorder = null
        cloudRecordingActive = false
        listening = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
            try { recorder?.release() } catch (_: Exception) { }
            file?.delete()
            cloudAudioFile = null
            setStatus("Recording was too short or could not be finalized. Please try again.")
            return
        } finally {
            try { recorder?.release() } catch (_: Exception) { }
        }
        if (file == null || !file.exists() || file.length() < 512L) {
            file?.delete()
            cloudAudioFile = null
            setStatus("No usable audio was recorded. Tap Talk and speak before stopping.")
            return
        }
        if (file.length() > 20L * 1024L * 1024L) {
            file.delete()
            cloudAudioFile = null
            setStatus("Recording is too large. Please dictate a shorter session.")
            return
        }
        cloudUploadInProgress = true
        val session = cloudRecordingSession
        renderKeyboard()
        setStatus("Uploading audio · transcribing with Whisper…")
        cloudUploadJob = serviceScope.launch {
            try {
                val latest = settingsRepository.settings.first()
                settings = latest
                if (session != sessionCounter || passwordField) return@launch
                val token = tokenStore.readToken()
                if (token.isNullOrBlank()) {
                    setStatus("No testing token saved. Audio was not uploaded; open AKZSA Settings.")
                    return@launch
                }
                val shouldPolish = !fileAiMode && !commandMode && !writingMode
                when (val response = BackendClient.transcribeAudio(
                    baseUrl = settings.backendBaseUrl,
                    bearerToken = token,
                    audioFile = file,
                    languageTag = settings.recognitionLanguage,
                    vocabulary = UserDictionary.promptTerms(settings.dictionaryTerms),
                    polish = shouldPolish,
                    installId = InstallId.get(applicationContext),
                )) {
                    is ApiResult.Failure -> {
                        if (session == sessionCounter) setStatus("Cloud transcription failed: ${response.message}")
                    }
                    is ApiResult.Success -> {
                        if (session != sessionCounter || passwordField || currentInputConnection == null) return@launch
                        val transcript = response.value.text.trim().let { if (settings.recognitionLanguage == "hinglish") HinglishTransliterator.toRoman(it) else it }
                        if (transcript.isBlank()) {
                            setStatus("The speech service returned no text. Nothing was inserted.")
                        } else if (fileAiMode) {
                            setFileAiQueryFromSpeech(transcript)
                        } else if (commandMode || writingMode) {
                            // Keep commands/prompts unpolished to avoid changing their intent.
                            handleTranscript(transcript)
                        } else {
                            commitDictation(transcript)
                            setStatus(if (response.value.polish_applied) "Cloud dictation inserted · AI polished" else "Cloud transcription inserted · raw text")
                        }
                        diagnosticStore.recordBackendSuccess(response.statusCode, response.latencyMs, "success")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (session == sessionCounter) setStatus("Cloud transcription failed. Check connection and AKZSA Settings.")
            } finally {
                file.delete()
                if (cloudAudioFile == file) cloudAudioFile = null
                if (session == sessionCounter) {
                    cloudUploadInProgress = false
                    cloudUploadJob = null
                    renderKeyboard()
                }
            }
        }
    }

    private fun startRecognition() {
        val session = ++sessionCounter
        resultHandledForSession = false
        lastPartial = ""
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(createRecognitionListener(session))
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Hinglish = Hindi speech recognised by the Hindi engine, then written in Roman letters (see handleTranscript).
                val deviceLanguage = if (settings.recognitionLanguage == "hinglish") "hi-IN" else settings.recognitionLanguage
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, deviceLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, deviceLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Best-effort hints (providers may ignore them): do not cut the user off at the first short pause.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Supported recognition services can return the formatted hypothesis first,
                    // with punctuation/capitalization; these hints are best-effort by provider.
                    putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                    val biasTerms = (listOf("AKZSA", "NEFEON", "delete the last five words", "clear all text", "new paragraph") +
                        UserDictionary.biasTerms(settings.dictionaryTerms, 50))
                        .distinct().take(60)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasTerms))
                }
            }
            listening = true
            pendingPlan = null
            updatePendingPreview()
            renderKeyboard()
            setStatus(
                when {
                    fileAiMode -> "Listening · FILE search query stays out of the AKZSA backend"
                    settings.recognitionLanguage == "hinglish" -> "Listening · speak Hinglish; it is typed in Roman letters"
                    else -> "Listening · speak now"
                }
            )
            recognizer?.startListening(intent)
        } catch (_: SecurityException) {
            listening = false
            setStatus("Microphone permission is required")
        } catch (_: Exception) {
            listening = false
            setStatus("Could not start speech recognition")
        }
    }

    private fun createRecognitionListener(session: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (session == sessionCounter) setStatus("Listening · speak now") }
        override fun onBeginningOfSpeech() { if (session == sessionCounter) setStatus("Listening…") }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { if (session == sessionCounter) setStatus("Processing speech…") }
        override fun onError(error: Int) {
            if (session != sessionCounter || resultHandledForSession) return
            resultHandledForSession = true
            listening = false
            renderKeyboard()
            // Do not lose speech that was already recognised: recover the last partial hypothesis when the engine
            // ends with "no match", a timeout or a transient server/network error.
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            val recovered = lastPartial.trim()
            lastPartial = ""
            if (recoverable && recovered.isNotBlank()) {
                handleTranscript(recovered)
                setStatus("Inserted what was heard before the speech service stopped")
                return
            }
            setStatus(speechErrorMessage(error))
        }
        override fun onResults(results: Bundle?) {
            if (session != sessionCounter || resultHandledForSession) return
            resultHandledForSession = true
            listening = false
            renderKeyboard()
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (transcript.isBlank()) {
                setStatus("No speech detected. Tap Talk to try again.")
                return
            }
            handleTranscript(transcript)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (session != sessionCounter || resultHandledForSession) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) { lastPartial = partial; setStatus("Listening · $partial") }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleTranscript(rawTranscript: String) {
        // Hinglish is typed in Roman letters even when the recogniser returns Devanagari.
        val transcript = if (settings.recognitionLanguage == "hinglish") HinglishTransliterator.toRoman(rawTranscript) else rawTranscript
        if (passwordField) {
            setStatus("Voice and AI actions are disabled for protected fields")
            return
        }
        if (fileAiMode) {
            setFileAiQueryFromSpeech(transcript)
            return
        }
        if (!ensureInputConnection()) return
        if (!commandMode && !writingMode) {
            commitDictation(transcript)
            setStatus("Speech inserted")
            return
        }
        setStatus(if (writingMode) "Generating writing with AI…" else "Interpreting command securely…")
        serviceScope.launch {
            try {
                val latestSettings = settingsRepository.settings.first()
                settings = latestSettings
                val token = tokenStore.readToken()
                if (token.isNullOrBlank()) {
                    setStatus("No test token saved. Open AKZSA Settings to configure one.")
                    return@launch
                }
                // Read editor context only after the user enabled the explicit context-sharing setting.
                val selected = if (settings.shareEditorContext) currentInputConnection?.getSelectedText(0)?.toString()?.take(10_000) else null
                val surrounding = if (settings.shareEditorContext) {
                    val before = currentInputConnection?.getTextBeforeCursor(500, 0)?.toString().orEmpty()
                    val after = currentInputConnection?.getTextAfterCursor(500, 0)?.toString().orEmpty()
                    (before + after).take(10_000).ifBlank { null }
                } else null
                val payload = CommandRequest(
                    transcript = transcript.take(2_000),
                    selected_text = selected,
                    surrounding_text = surrounding,
                    mode = if (writingMode) "write" else "command",
                )
                when (val response = BackendClient.interpret(settings.backendBaseUrl, token, payload, InstallId.get(applicationContext))) {
                    is ApiResult.Failure -> {
                        diagnosticStore.recordBackendFailure(response.statusCode, response.latencyMs, if (response.statusCode == 401 || response.statusCode == 403) "failed" else null)
                        setStatus(response.message)
                    }
                    is ApiResult.Success -> {
                        diagnosticStore.recordBackendSuccess(response.statusCode, response.latencyMs, "success")
                        when (val validated = ActionValidator.validate(response.value.action, transcript, selected)) {
                            is ValidationResult.Rejected -> {
                                // Local-only troubleshooting: never log/send the transcript outside this device here.
                                val heard = transcript.replace("\r", " ").replace("\n", " ").take(140)
                                val detail = "AKZSA heard: \"$heard\"\nBackend action: ${response.value.action.kind}\nModel: ${response.value.model}\nResult: ${validated.message}"
                                Toast.makeText(this@AkzsaInputMethodService, detail, Toast.LENGTH_LONG).show()
                                setStatus(if (response.value.action.kind == "unknown") "Not understood · heard: ${heard.take(44)}" else validated.message)
                            }
                            is ValidationResult.Accepted -> {
                                val serverAsked = response.value.action.requires_confirmation
                                val requireConfirmation = serverAsked || validated.plan.requiresConfirmation ||
                                    (settings.alwaysConfirmEdits && validated.plan.kind !in setOf(PlanKind.DICTATION, PlanKind.FIND_FILE))
                                val plan = validated.plan.copy(requiresConfirmation = requireConfirmation)
                                if (plan.requiresConfirmation) {
                                    pendingPlan = plan
                                    setStatus("Review the proposed action")
                                    updatePendingPreview()
                                } else {
                                    executePlan(plan)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setStatus("Could not process the command. Check AKZSA Settings and diagnostics.")
            }
        }
    }

    private fun confirmPendingAction() {
        val plan = pendingPlan ?: return
        pendingPlan = null
        updatePendingPreview()
        executePlan(plan)
    }

    private fun cancelPendingAction() {
        pendingPlan = null
        updatePendingPreview()
        setStatus("Action cancelled · no change made")
    }

    private fun executePlan(plan: ActionPlan) {
        if (passwordField) {
            setStatus("AI actions are disabled for protected fields")
            return
        }
        if (!ensureInputConnection()) return
        val ic = currentInputConnection ?: return
        when (plan.kind) {
            PlanKind.DICTATION -> {
                val text = plan.replacementText ?: run { setStatus("No text to insert"); return }
                commitDictation(text)
                setStatus("Text inserted")
            }
            PlanKind.NEW_PARAGRAPH -> {
                ic.commitText("\n", 1)
                setStatus("New paragraph inserted")
            }
            PlanKind.INSERT_GENERATED_TEXT -> {
                val text = plan.replacementText ?: run { setStatus("The AI returned no text"); return }
                if (ic.getSelectedText(0)?.toString().orEmpty().isNotEmpty()) {
                    setStatus("Text is selected. Clear the selection or ask Write mode to rewrite the selected text.")
                    return
                }
                if (text.length > 10_000) { setStatus("Generated text exceeds the safety limit"); return }
                ic.commitText(text, 1)
                setStatus("AI writing inserted")
            }
            PlanKind.DELETE_LAST_WORDS -> {
                val count = plan.count ?: run { setStatus("Invalid deletion count"); return }
                if (count !in 1..50) { setStatus("Deletion limit exceeded"); return }
                deleteFieldSuffix(ic, count, words = true)
            }
            PlanKind.DELETE_LAST_CHARACTERS -> {
                val count = plan.count ?: run { setStatus("Invalid deletion count"); return }
                if (count !in 1..100) { setStatus("Deletion limit exceeded"); return }
                deleteFieldSuffix(ic, count, words = false)
            }
            PlanKind.DELETE_ALL_TEXT -> clearCurrentTextField(ic)
            PlanKind.FIND_FILE -> {
                val query = plan.replacementText?.takeIf { it.isNotBlank() } ?: run { setStatus("Say what file you want to find"); return }
                val wasFileMode = fileAiMode
                fileAiMode = true
                fileAiQuery = query.take(300)
                fileAiMessage = "Searching the private on-device index…"
                // Rebuild only when the panel isn't showing yet; otherwise just refresh the query text.
                if (wasFileMode && fileAiQueryView != null) updateFileAiQueryUi() else renderKeyboard()
                setStatus("File AI query captured")
                serviceScope.launch {
                    loadIndexedPhoneFiles()
                    runFileAiSearch()
                }
            }
            PlanKind.REPLACE_SELECTED_TEXT -> {
                val expected = plan.sourceSelection ?: run { setStatus("No original selection was recorded"); return }
                val currentSelected = ic.getSelectedText(0)?.toString()
                if (currentSelected != expected) {
                    setStatus("Selection changed since preview. Select the original text and try again.")
                    return
                }
                val replacement = plan.replacementText ?: run { setStatus("No replacement text was provided"); return }
                if (replacement.length > 10_000) { setStatus("Replacement exceeds the safety limit"); return }
                ic.commitText(replacement, 1)
                setStatus("Selected text replaced")
            }
        }
        updatePendingPreview()
    }

    private fun cycleAssistantMode() {
        if (fileAiMode) {
            fileAiMode = false
        }
        if (passwordField) {
            setStatus("Voice, commands, and AI writing are disabled for protected fields")
            return
        }
        val nextIsWriting: Boolean
        val nextIsCommand: Boolean
        when {
            writingMode -> { nextIsWriting = false; nextIsCommand = false }
            commandMode -> { nextIsWriting = true; nextIsCommand = false }
            else -> { nextIsWriting = false; nextIsCommand = true }
        }
        writingMode = nextIsWriting
        commandMode = nextIsCommand
        pendingPlan = null
        updatePendingPreview()
        setStatus(when { writingMode -> "Write mode · speak a prompt to draft or transform text"; commandMode -> "Command mode · speak an editing action"; else -> "Type mode · speech inserts your words" })
        // Persist only the Type/Command default preference. Write is an explicit temporary mode.
        settings = settings.copy(aiCommandModeDefault = commandMode)
        serviceScope.launch {
            try { settingsRepository.update { it.copy(aiCommandModeDefault = commandMode) } } catch (_: Exception) { }
        }
        renderKeyboard()
    }

    /** Select and verify the complete focused text field locally; never send this text to the backend here. */
    private fun selectWholeFieldText(ic: InputConnection): String? {
        if (!ic.performContextMenuAction(android.R.id.selectAll)) {
            setStatus("This editor cannot select the whole field safely; nothing changed")
            return null
        }
        val selected = ic.getSelectedText(0)?.toString()
        if (selected == null) {
            setStatus("This editor did not expose the selected text; nothing changed")
            return null
        }
        if (selected.length > 20_000) {
            ic.setSelection(selected.length, selected.length)
            setStatus("The field is too long to edit safely in one operation; nothing changed")
            return null
        }
        return selected
    }

    /** Delete a final suffix from the entire focused field, not relative to the caret. */
    private fun deleteFieldSuffix(ic: InputConnection, count: Int, words: Boolean) {
        val whole = selectWholeFieldText(ic) ?: return
        if (whole.isEmpty()) {
            setStatus("The text field is already empty")
            return
        }
        val start = if (words) {
            val matches = Regex("\\S+").findAll(whole).toList()
            if (matches.size < count) {
                ic.setSelection(whole.length, whole.length)
                setStatus("There are fewer than $count words in the field; nothing changed")
                return
            }
            matches[matches.size - count].range.first
        } else {
            val codePoints = whole.codePointCount(0, whole.length)
            if (codePoints < count) {
                ic.setSelection(whole.length, whole.length)
                setStatus("There are fewer than $count characters in the field; nothing changed")
                return
            }
            whole.offsetByCodePoints(whole.length, -count)
        }
        val expected = whole.substring(start)
        if (!ic.setSelection(start, whole.length)) {
            ic.setSelection(whole.length, whole.length)
            setStatus("This editor could not select the final text safely; nothing changed")
            return
        }
        val selected = ic.getSelectedText(0)?.toString()
        if (selected != expected) {
            ic.setSelection(whole.length, whole.length)
            setStatus("The editor selection did not match the proposed suffix; nothing changed")
            return
        }
        ic.commitText("", 1)
        setStatus(if (words) "Deleted the last $count words at the end of the field" else "Deleted the last $count characters at the end of the field")
    }

    /** Clears only the currently focused text field after verifying Android selected its full contents. */
    private fun clearCurrentTextField(ic: InputConnection) {
        val whole = selectWholeFieldText(ic) ?: return
        if (whole.isEmpty()) {
            setStatus("The text field is already empty")
            return
        }
        val selected = ic.getSelectedText(0)?.toString()
        if (selected != whole) {
            ic.setSelection(whole.length, whole.length)
            setStatus("The selected text did not match the full field; nothing was deleted")
            return
        }
        ic.commitText("", 1)
        setStatus("Cleared all text in the current field")
    }

    private fun cycleLanguage() {
        serviceScope.launch {
            val next = when (settings.recognitionLanguage) {
                "en-US" -> "en-IN"
                "en-IN" -> "hi-IN"
                "hi-IN" -> "kn-IN"
                "kn-IN" -> "hinglish"
                else -> "en-US"
            }
            settingsRepository.update { it.copy(recognitionLanguage = next) }
            settings = settings.copy(recognitionLanguage = next)
            setStatus("Language: ${settings.languageLabel}")
            renderKeyboard()
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {
            Toast.makeText(this, "Open AKZSA from your app list to change settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureInputConnection(): Boolean {
        if (currentInputConnection == null) {
            setStatus("The text field is no longer available. Tap the field and try again.")
            return false
        }
        return true
    }

    private fun cancelRecognition(updateStatus: Boolean, destroy: Boolean = false) {
        sessionCounter += 1
        resultHandledForSession = true
        listening = false
        streamingSession?.cancel()
        streamingSession = null
        if (cloudRecordingActive) {
            cloudRecordingActive = false
            try { cloudRecorder?.stop() } catch (_: Exception) { }
            try { cloudRecorder?.release() } catch (_: Exception) { }
            cloudRecorder = null
            cloudAudioFile?.delete()
            cloudAudioFile = null
        }
        if (cloudUploadInProgress) {
            cloudUploadJob?.cancel()
            cloudUploadJob = null
            cloudUploadInProgress = false
        }
        try { recognizer?.cancel() } catch (_: Exception) { }
        if (destroy) {
            try { recognizer?.destroy() } catch (_: Exception) { }
            recognizer = null
        }
        if (updateStatus) setStatus("Listening stopped")
    }

    private fun setStatus(message: String) {
        status = message
        val header = statusView
        if (header != null) {
            header.text = "AKZSA  ·  $message"
            header.textSize = if (message.length > 80) 10.5f else 12f
        } else if (Regex("(?i)(fail|error|denied|disabled|not |could ?n|no speech|required|off in|can't|unavailable)").containsMatchIn(message)) {
            // Compact layouts hide the status line; problems must still be visible.
            Toast.makeText(this, message.take(120), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePendingPreview() {
        val plan = pendingPlan
        val preview = previewView ?: return
        val actions = previewActions ?: return
        if (plan == null) {
            preview.visibility = View.GONE
            actions.visibility = View.GONE
            actions.removeAllViews()
            return
        }
        val text = buildString {
            append("Review: ").append(plan.explanation)
            if ((plan.kind == PlanKind.REPLACE_SELECTED_TEXT || plan.kind == PlanKind.DICTATION || plan.kind == PlanKind.INSERT_GENERATED_TEXT) && !plan.replacementText.isNullOrBlank()) {
                if (plan.kind == PlanKind.REPLACE_SELECTED_TEXT) {
                    append("\nSelected: ").append(plan.sourceSelection.orEmpty().take(140))
                    if (plan.sourceSelection.orEmpty().length > 140) append("…")
                    append("\nReplace with: ")
                } else if (plan.kind == PlanKind.INSERT_GENERATED_TEXT) {
                    append("\nGenerated text: ")
                } else {
                    append("\nProposed text: ")
                }
                append(plan.replacementText.take(260))
                if (plan.replacementText.length > 260) append("…")
            }
        }
        preview.text = text
        preview.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        actions.removeAllViews()
        addWeightedRow(actions, listOf(
            Key("Cancel", 1f, true) { cancelPendingAction() },
            Key("Apply", 1f) { confirmPendingAction() },
        ), kbMetrics.actionRowHeightDp)
    }

    private fun speechErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Check the device microphone."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was interrupted. Tap Talk to try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was denied."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network error. Check connectivity."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try speaking more clearly."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again in a moment."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error. Try again later."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap Talk to try again."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service is rate-limiting requests. Wait a moment."
        // ERROR_LANGUAGE_NOT_SUPPORTED (12) / ERROR_LANGUAGE_UNAVAILABLE (13) exist from API 31.
        12, 13 -> "This phone's speech service can't use that language offline. Download it in Google speech settings or turn on Cloud Dictation in AKZSA."
        else -> "Speech recognition failed ($error). Try again."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun isNight(): Boolean = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    // Graphite Signal tokens (single source of truth: design/GraphiteSignal.kt)
    private fun palette() = GraphiteSignal.palette(isNight())
    private fun surfaceColor(): Int = palette().background
    private fun textColor(): Int = palette().textPrimary
    private fun mutedTextColor(): Int = palette().textSecondary
    private fun keyColor(): Int = palette().surface
    private fun specialKeyColor(): Int = palette().secondarySurface
    private fun borderColor(): Int = palette().border
    private fun accentColor(): Int = palette().accent
    private fun onAccentColor(): Int = palette().onAccent
    private fun focusColor(): Int = palette().focus

    private fun typingRowCount(): Int {
        val type = currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val cls = type and InputType.TYPE_MASK_CLASS
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        return if (numeric || (hindiLayout && !symbolsMode)) KeyboardLayoutPolicy.DEFAULT_TYPING_ROWS + 1 else KeyboardLayoutPolicy.DEFAULT_TYPING_ROWS
    }

    /** All sizing comes from measured space (never device model). Re-run on every render/config change. */
    private fun recomputeLayout() {
        val c = resources.configuration
        kbMetrics = KeyboardLayoutPolicy.metrics(
            widthDp = c.screenWidthDp.toFloat(),
            heightDp = c.screenHeightDp.toFloat(),
            fontScale = c.fontScale,
            fileMode = fileAiMode && !passwordField,
            typingRows = typingRowCount(),
        )
    }

    /** Bounded width (centred on tablets / landscape) plus system-bar and cutout insets. */
    private fun applyRootPadding(root: LinearLayout) {
        val screenWidthPx = (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        val side = maxOf(0, (screenWidthPx - dp(kbMetrics.contentWidthDp)) / 2)
        val h = dp(kbMetrics.horizontalPaddingDp) + side
        KeyboardInsets.install(root, intArrayOf(h, dp(4), h, dp(4)))
    }
}
