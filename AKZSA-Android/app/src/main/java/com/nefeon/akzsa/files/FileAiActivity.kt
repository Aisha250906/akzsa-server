package com.nefeon.akzsa.files

import android.app.Activity
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.pm.PackageManager
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nefeon.akzsa.network.FileSearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One-time File AI setup. On Android 11+ it indexes broad shared storage when granted; SAF remains a fallback. */
class FileAiActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE) }
    private val indexFile by lazy { File(filesDir, "akzsa-file-index.json") }

    private lateinit var queryInput: EditText
    private lateinit var folderStatus: TextView
    private lateinit var progressStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgressBar: ProgressBar
    private lateinit var semanticStatus: TextView
    private lateinit var semanticProgressBar: ProgressBar
    private lateinit var fileIndexProgressBar: ProgressBar
    private lateinit var resultContainer: LinearLayout
    private lateinit var consent: CheckBox
    private lateinit var indexButton: Button
    private lateinit var searchButton: Button
    /** Only the current visible search results are held in memory, never the full text index. */
    private var indexedFiles: List<IndexedPhoneFile> = emptyList()
    private var indexedFileCount: Int = 0
    private var isIndexing = false
    private var isSearching = false
    private var scanWorkObserver: Observer<List<WorkInfo>>? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
                folderStatus.text = "Optional folder permission saved. Tap Index selected folder to start background indexing of this folder."
                progressStatus.text = "Folder permission saved on this device."
            } catch (error: Exception) {
                progressStatus.text = "Could not save folder permission. Choose the folder again."
            }
        }
    }
    private var pendingIndexAfterPermission = false
    private val readStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingIndexAfterPermission = false
        if (granted) startIndexingAllStorage()
        else progressStatus.text = "Storage permission was not granted. You can use the optional Android folder picker instead."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let {
            if (::queryInput.isInitialized) queryInput.setText(it)
        }
        FileIndexAutoMonitor.start(applicationContext)
        FileIndexWorkScheduler.ensurePeriodic(applicationContext)
        ClipModelManager.ensureDownload(applicationContext)
        observeBackgroundIndexWork()
        observeModelDownload()
        observeSemanticIndexWork()
        lifecycleScope.launch {
            val savedCount = withContext(Dispatchers.IO) { loadIndexCount() }
            indexedFileCount = savedCount
            if (savedCount == 0 && !prefs.getBoolean("index_work_running", false)) {
                when {
                    LocalFileIndexer.hasFullSharedStorageAccess(applicationContext) -> FileIndexWorkScheduler.enqueueInitial(applicationContext)
                    !prefs.getString(KEY_TREE_URI, null).isNullOrBlank() -> FileIndexWorkScheduler.enqueueInitial(applicationContext, prefs.getString(KEY_TREE_URI, null))
                }
            }
            indexedFiles = emptyList()
            refreshFolderLabel()
            val prefs = getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
            progressStatus.text = when {
                prefs.getBoolean("index_work_running", false) -> "Indexing continues in the background. You can leave this screen; saved batches remain searchable. Indexed so far: $savedCount files."
                savedCount > 0 -> "Private local index loaded: $savedCount files. OCR and file contents remain on this phone."
                else -> "AKZSA is preparing local AI search automatically. Allow file access once to start indexing; no private files are uploaded."
            }
            updateButtons()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let {
            if (::queryInput.isInitialized) queryInput.setText(it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::folderStatus.isInitialized) refreshFolderLabel()
        if (pendingIndexAfterPermission && LocalFileIndexer.hasFullSharedStorageAccess(this)) {
            pendingIndexAfterPermission = false
            startIndexingAllStorage()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(column)
        setContentView(scroll)

        column.addView(TextView(this).apply {
            text = "AKZSA is setting up private search"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "One-time setup. AKZSA downloads a small on-device photo AI model (about 107 MB) over Wi-Fi and indexes files gently in the background. Basic filename, OCR, and PDF search keeps working meanwhile. Android requires your approval before AKZSA can scan files."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        column.addView(TextView(this).apply { text = "1 · Photo AI model (light)"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        modelStatus = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        column.addView(modelStatus)
        modelProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        column.addView(modelProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(14) })

        column.addView(TextView(this).apply { text = "2 · Your files"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        folderStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
        column.addView(folderStatus)
        column.addView(makeButton("Allow access once · start automatically", true) { requestFullStorageAccess() })
        indexButton = makeButton("Start / refresh file scan", false) { startIndexingAllStorage() }
        column.addView(indexButton)
        column.addView(makeButton("Choose a folder instead", false) { folderPicker.launch(null) })
        column.addView(makeButton("Index selected folder", false) { startIndexing() })
        fileIndexProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        column.addView(fileIndexProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4) })
        progressStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(progressStatus)

        column.addView(TextView(this).apply { text = "3 · Photo AI index"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
        semanticStatus = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        column.addView(semanticStatus)
        semanticProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        column.addView(semanticProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(12) })
        // Keep a result container for shared indexing/clear-index code, but do not render
        // search cards here; result cards are presented inside the keyboard's FILE panel.
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(makeButton("Retry photo AI", false) { retrySemanticIndex() })
        column.addView(makeButton("Download photo AI model now · mobile data (about 107 MB)", false) {
            modelStatus.text = "Downloading the photo AI model on mobile data…"
            modelProgressBar.isIndeterminate = true
            ClipModelManager.ensureDownload(applicationContext, allowMobileData = true)
        })
        column.addView(makeButton("Re-download photo AI model", false) { redownloadPhotoAi() })
        column.addView(makeButton("Clear local index", false) { clearIndex() })
        column.addView(makeButton("Done · return to your app", true) { finish() })
        column.addView(TextView(this).apply {
            text = "Private by design: model inference, vectors, OCR text and search stay on this phone. The photo AI model downloads once (about 107 MB) over Wi-Fi. Photo indexing pauses automatically when the phone gets warm. Android protects other apps' private folders, and permission to scan shared files must be granted by you. Very large/scanned PDFs may need future deeper page-by-page OCR."
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })
        updateButtons()
        modelStatus.text = "Checking the one-time photo AI model download…"
        semanticStatus.text = "Waiting for the photo AI model and initial file scan."
        if (ClipModelManager.isReady(applicationContext)) {
            modelStatus.text = "Photo AI model ready on this phone."
            modelProgressBar.isIndeterminate = false
            modelProgressBar.progress = 100
            ClipIndexScheduler.enqueue(applicationContext)
        }
    }

    private fun retrySemanticIndex() {
        ClipEngine.clearError(applicationContext)
        semanticStatus.text = "Retrying photo AI…"
        ClipModelManager.ensureDownload(applicationContext)
        ClipIndexScheduler.enqueue(applicationContext)
    }

    private fun redownloadPhotoAi() {
        modelStatus.text = "Re-downloading the photo AI model…"
        modelProgressBar.isIndeterminate = true
        ClipModelManager.redownload(applicationContext)
    }

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        }
    }

    private fun requestFullStorageAccess() {
        if (!com.nefeon.akzsa.BuildConfig.ALL_FILES_ACCESS) {
            // Play build: no all-files permission. The user chooses exactly which folders AKZSA may index.
            progressStatus.text = "Choose the folders AKZSA may search. Nothing outside them is read."
            folderPicker.launch(null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                progressStatus.text = "Storage access is ready. Starting the first background scan automatically."
                refreshFolderLabel()
                startIndexingAllStorage()
                return
            }
            pendingIndexAfterPermission = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                pendingIndexAfterPermission = false
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            progressStatus.text = "Android 10 uses scoped storage for this build. Use the optional folder picker below, or update to Android 11+ for broad shared-storage indexing."
            folderPicker.launch(null)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                startIndexingAllStorage()
            } else {
                pendingIndexAfterPermission = true
                readStoragePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun startIndexingAllStorage() {
        if (isIndexing) return
        if (!LocalFileIndexer.hasFullSharedStorageAccess(this)) {
            progressStatus.text = "Enable full shared-storage access first. Android will show the system permission screen."
            requestFullStorageAccess()
            return
        }
        prefs.edit().putBoolean("index_enabled", true).apply()
        isIndexing = true
        resultContainer.removeAllViews()
        progressStatus.text = "First-time indexing queued in the background. You can leave AKZSA; progress is saved locally in batches."
        updateButtons()
        FileIndexAutoMonitor.start(applicationContext)
        FileIndexWorkScheduler.enqueueInitial(applicationContext)
        FileIndexWorkScheduler.ensurePeriodic(applicationContext)
        observeBackgroundIndexWork()
    }

    private fun startIndexing() {
        if (isIndexing) return
        val treeText = prefs.getString(KEY_TREE_URI, null)
        if (treeText.isNullOrBlank()) {
            progressStatus.text = "Choose a folder first. Android will ask which folder AKZSA may access."
            return
        }
        prefs.edit().putBoolean("index_enabled", true).apply()
        isIndexing = true
        resultContainer.removeAllViews()
        progressStatus.text = "Selected-folder indexing is queued in the background. You can leave this screen."
        updateButtons()
        FileIndexWorkScheduler.enqueueInitial(applicationContext, treeText)
        FileIndexWorkScheduler.ensurePeriodic(applicationContext)
        observeBackgroundIndexWork()
    }

    private fun searchFiles() {
        if (isSearching) return
        val query = queryInput.text.toString().trim()
        if (query.length < 2) {
            progressStatus.text = "Enter a search request, for example: Find GST invoice for ₹25,000."
            return
        }
        if (indexedFileCount <= 0) {
            progressStatus.text = "No local index yet. Start background indexing once; then searches run locally without re-indexing every file."
            return
        }
        isSearching = true
        resultContainer.removeAllViews()
        progressStatus.text = "Searching $indexedFileCount indexed files locally. Nothing is being uploaded."
        updateButtons()
        lifecycleScope.launch {
            try {
                val (hits, matchedFiles) = withContext(Dispatchers.IO) {
                    PrivateFileIndexStore(applicationContext).use { store ->
                        val found = FileSearch.search(applicationContext, query, store, 20)
                        found to store.findByIds(found.map { it.file_id })
                    }
                }
                indexedFiles = matchedFiles
                if (hits.isEmpty()) {
                    progressStatus.text = "No local matches. New files are discovered by background refresh; an explicit rescan is also available."
                    resultContainer.addView(TextView(this@FileAiActivity).apply {
                        text = "No matches found. Try broader words or wait for new files to be indexed. OCR and search remain local to this phone."
                        textSize = 14f
                        setPadding(0, dp(10), 0, dp(10))
                    })
                } else {
                    progressStatus.text = "Found ${hits.size} local matches. Index contents were not sent to the backend."
                    hits.forEach { addResultCard(it) }
                }
            } catch (error: Exception) {
                progressStatus.text = "Local search failed: ${error.message ?: "Unexpected error"}"
            } finally {
                isSearching = false
                updateButtons()
            }
        }
    }

    private fun tokens() = com.nefeon.akzsa.design.GraphiteSignal.palette(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )

    private fun addResultCard(hit: FileSearchHit) {
        val file = indexedFiles.firstOrNull { it.fileId == hit.file_id } ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(tokens().surface)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), tokens().border)
            }
        }
        card.addView(TextView(this).apply {
            val changed = file.lastModifiedMs?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Date unavailable"
            text = "${hit.filename}\n${file.relativePath}\n$changed"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            val details = buildString {
                append(hit.reason)
                if (hit.gstins.isNotEmpty()) append("\nGSTIN: ${hit.gstins.joinToString()}")
                if (hit.invoice_numbers.isNotEmpty()) append("\nInvoice no.: ${hit.invoice_numbers.joinToString()}")
                if (hit.amounts.isNotEmpty()) append("\nAmounts: ${hit.amounts.joinToString()}")
                if (hit.text_preview.isNotBlank()) append("\n\nLocal image/OCR content: ${hit.text_preview.take(500)}")
            }
            text = details
            textSize = 13f
            setPadding(0, dp(6), 0, dp(8))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(makeButton("Open", true) { openDocument(file) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(makeButton("Share / attach", false) { shareDocument(file) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(makeButton("Copy details", false) { copyDetails(hit) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(actions)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(9)
        resultContainer.addView(card, lp)
    }

    private fun openDocument(file: IndexedPhoneFile) {
        when (FileOpener.open(this, file, newTask = false)) {
            FileOpener.OpenResult.Opened -> Unit
            FileOpener.OpenResult.Missing -> progressStatus.text = "${file.filename} was moved or deleted. Tap Index again to refresh the list."
            FileOpener.OpenResult.NoApp -> progressStatus.text = "No app can open this file type. Use Share / attach instead."
        }
    }

    private fun shareDocument(file: IndexedPhoneFile) {
        try {
            val uri = LocalFileIndexer.shareableUri(this, file.uri)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, file.filename, uri)
            }
            startActivity(Intent.createChooser(send, "Share or attach ${file.filename}"))
        } catch (_: Exception) {
            progressStatus.text = "Could not share this file. Re-authorize its folder and rescan."
        }
    }

    private fun copyDetails(hit: FileSearchHit) {
        val details = buildString {
            append("File: ${hit.filename}\nWhy it matched: ${hit.reason}")
            if (hit.gstins.isNotEmpty()) append("\nGSTIN: ${hit.gstins.joinToString()}")
            if (hit.invoice_numbers.isNotEmpty()) append("\nInvoice number: ${hit.invoice_numbers.joinToString()}")
            if (hit.amounts.isNotEmpty()) append("\nAmounts: ${hit.amounts.joinToString()}")
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AKZSA file details", details))
        progressStatus.text = "File details copied. Paste them into a text field, or use Share / attach for the original file."
    }

    private fun clearIndex() {
        lifecycleScope.launch {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(FileIndexWorkScheduler.UNIQUE_SCAN)
            WorkManager.getInstance(applicationContext).cancelUniqueWork(FileIndexWorkScheduler.UNIQUE_CHANGE)
            WorkManager.getInstance(applicationContext).cancelUniqueWork(FileIndexWorkScheduler.UNIQUE_PERIODIC)
            WorkManager.getInstance(applicationContext).cancelUniqueWork(ClipIndexScheduler.UNIQUE_WORK)
            withContext(Dispatchers.IO) {
                PrivateFileIndexStore(applicationContext).use { it.deleteAll() }
                ClipVectorStore(applicationContext).use { it.clear() }
                ClipVectorStore.bumpVersion(applicationContext)
                if (indexFile.exists()) indexFile.delete()
            }
            prefs.edit().putBoolean("index_enabled", false).putBoolean("index_completed", false).putBoolean("index_work_running", false).apply()
            indexedFiles = emptyList()
            indexedFileCount = 0
            resultContainer.removeAllViews()
            progressStatus.text = "Private local index cleared. Original documents have not been deleted."
            isIndexing = false
            updateButtons()
        }
    }

    private suspend fun loadIndexCount(): Int = withContext(Dispatchers.IO) {
        PrivateFileIndexStore(applicationContext).use { it.count() }
    }

    private fun observeBackgroundIndexWork() {
        val workManager = WorkManager.getInstance(applicationContext)
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(FileIndexWorkScheduler.UNIQUE_SCAN)
        liveData.removeObservers(this)
        liveData.observe(this, Observer { infos ->
            val info = infos.firstOrNull() ?: return@Observer
            val processed = info.progress.getInt(FileIndexWorker.KEY_PROCESSED, 0)
            val current = info.progress.getString(FileIndexWorker.KEY_CURRENT_FILE).orEmpty()
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    isIndexing = true
                    fileIndexProgressBar.isIndeterminate = true
                    progressStatus.text = "Background indexing queued. You can leave this screen; AKZSA saves progress locally."
                }
                WorkInfo.State.RUNNING -> {
                    isIndexing = true
                    fileIndexProgressBar.isIndeterminate = true
                    progressStatus.text = "Background indexing: $processed files checked${if (current.isNotBlank()) " · $current" else ""}. You can leave this screen."
                }
                WorkInfo.State.SUCCEEDED -> {
                    isIndexing = false
                    lifecycleScope.launch {
                        indexedFileCount = withContext(Dispatchers.IO) { loadIndexCount() }
                        indexedFiles = emptyList()
                        fileIndexProgressBar.isIndeterminate = false
                        fileIndexProgressBar.progress = 100
                        progressStatus.text = "File scan complete: $indexedFileCount files saved locally. Photo AI indexing continues separately."
                        ClipIndexScheduler.enqueue(applicationContext)
                        refreshFolderLabel()
                        updateButtons()
                    }
                }
                WorkInfo.State.FAILED -> {
                    isIndexing = false
                    fileIndexProgressBar.isIndeterminate = false
                    fileIndexProgressBar.progress = 0
                    progressStatus.text = "Background indexing failed: ${info.outputData.getString(FileIndexWorker.KEY_ERROR) ?: "Check storage permission and try again."}"
                    updateButtons()
                }
                WorkInfo.State.CANCELLED -> {
                    isIndexing = false
                    fileIndexProgressBar.isIndeterminate = false
                    progressStatus.text = "Indexing cancelled. Saved batches remain searchable; tap Start / refresh file scan to continue."
                    updateButtons()
                }
                else -> Unit
            }
            updateButtons()
        })
    }

    private fun observeModelDownload() {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ClipModelManager.UNIQUE_DOWNLOAD)
            .observe(this, Observer { infos ->
                if (ClipModelManager.isReady(applicationContext)) {
                    modelStatus.text = "Photo AI model ready on this phone (light MobileCLIP, about ${ClipModelManager.TOTAL_APPROX_MB} MB)."
                    modelProgressBar.isIndeterminate = false
                    modelProgressBar.progress = 100
                    return@Observer
                }
                val info = infos.firstOrNull()
                if (info == null) {
                    modelStatus.text = "Photo AI model (about ${ClipModelManager.TOTAL_APPROX_MB} MB) downloads automatically over Wi-Fi. Normal search works meanwhile."
                    modelProgressBar.isIndeterminate = true
                    return@Observer
                }
                val percent = info.progress.getInt(ClipModelDownloadWorker.KEY_PERCENT, 0)
                val done = info.progress.getLong(ClipModelDownloadWorker.KEY_BYTES_DONE, 0L)
                val total = info.progress.getLong(ClipModelDownloadWorker.KEY_BYTES_TOTAL, ClipModelManager.TOTAL_APPROX_MB * 1024L * 1024L)
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        modelStatus.text = "Photo AI model queued. It downloads on Wi-Fi when the battery is not low."
                        modelProgressBar.isIndeterminate = true
                    }
                    WorkInfo.State.RUNNING -> {
                        modelStatus.text = "Downloading photo AI model · $percent% (${formatMb(done)} / ${formatMb(total)} MB)"
                        modelProgressBar.isIndeterminate = false
                        modelProgressBar.progress = percent.coerceIn(0, 99)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        modelStatus.text = "Photo AI model downloaded. Preparing photo indexing…"
                        modelProgressBar.isIndeterminate = false
                        modelProgressBar.progress = 100
                    }
                    WorkInfo.State.FAILED -> {
                        modelStatus.text = "Photo AI download failed: ${info.outputData.getString(ClipModelDownloadWorker.KEY_ERROR) ?: "check your internet"}. Tap Re-download photo AI model."
                        modelProgressBar.isIndeterminate = false
                        modelProgressBar.progress = 0
                    }
                    WorkInfo.State.CANCELLED -> {
                        modelStatus.text = "Photo AI download paused. Tap Re-download photo AI model to start again."
                        modelProgressBar.isIndeterminate = true
                    }
                    else -> Unit
                }
            })
    }

    private fun observeSemanticIndexWork() {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ClipIndexScheduler.UNIQUE_WORK)
            .observe(this, Observer { infos ->
                val info = infos.firstOrNull()
                if (info == null) {
                    semanticStatus.text = when {
                        !ClipModelManager.isReady(applicationContext) -> "Waiting for the photo AI model download."
                        indexedFileCount == 0 -> "Waiting for file indexing to begin."
                        else -> "Photo AI is ready. New photos are added in the background."
                    }
                    return@Observer
                }
                val processed = info.progress.getInt(ClipImageIndexWorker.KEY_PROCESSED, 0)
                val total = info.progress.getInt(ClipImageIndexWorker.KEY_TOTAL, 0)
                val current = info.progress.getString(ClipImageIndexWorker.KEY_CURRENT_FILE).orEmpty()
                val status = info.progress.getString(ClipImageIndexWorker.KEY_STATUS).orEmpty()
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        semanticStatus.text = "Photo AI index queued. It runs gently and waits if the battery is low."
                        semanticProgressBar.isIndeterminate = true
                    }
                    WorkInfo.State.RUNNING -> {
                        semanticStatus.text = if (status.isNotBlank()) status
                            else "Photo AI index: $processed / ${total.coerceAtLeast(0)} photos${if (current.isNotBlank()) " · $current" else ""}"
                        semanticProgressBar.isIndeterminate = total <= 0
                        if (total > 0) semanticProgressBar.progress = (processed * 100 / total).coerceIn(0, 99)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val vectors = info.outputData.getInt(ClipImageIndexWorker.KEY_VECTORS, 0)
                        val failed = info.outputData.getInt(ClipImageIndexWorker.KEY_FAILURES, 0)
                        semanticStatus.text = "Photo AI ready · $vectors photos searchable by meaning" +
                            (if (failed > 0) " ($failed could not be read)" else "") + ". New photos update in the background."
                        semanticProgressBar.isIndeterminate = false
                        semanticProgressBar.progress = 100
                    }
                    WorkInfo.State.FAILED -> {
                        semanticStatus.text = "Photo AI failed: ${info.outputData.getString(ClipImageIndexWorker.KEY_ERROR) ?: ClipEngine.lastError(applicationContext) ?: "unknown error"}. Tap Retry photo AI."
                        semanticProgressBar.isIndeterminate = false
                        semanticProgressBar.progress = 0
                    }
                    WorkInfo.State.CANCELLED -> {
                        semanticStatus.text = "Photo AI indexing paused. It resumes automatically."
                        semanticProgressBar.isIndeterminate = true
                    }
                    else -> Unit
                }
            })
    }

    private fun formatMb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes.toDouble() / (1024.0 * 1024.0))

    private fun refreshFolderLabel() {
        val broad = LocalFileIndexer.hasFullSharedStorageAccess(this)
        val uriText = prefs.getString(KEY_TREE_URI, null)
        folderStatus.text = when {
            broad -> "Full accessible shared-storage access is enabled. Indexed files on this device: $indexedFileCount."
            !uriText.isNullOrBlank() -> "Optional folder permission saved. Indexed files on this device: $indexedFileCount. Full storage access is not enabled."
            else -> "Full phone storage access is not enabled yet. Tap Enable full phone storage access, then Index all accessible phone storage."
        }
    }

    private fun updateButtons() {
        if (!::indexButton.isInitialized) return
        indexButton.isEnabled = !isIndexing
        if (::searchButton.isInitialized) {
            searchButton.isEnabled = !isSearching && !isIndexing && indexedFileCount > 0
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUERY = "com.nefeon.akzsa.files.EXTRA_QUERY"
        private const val KEY_TREE_URI = "selected_tree_uri"
    }
}
