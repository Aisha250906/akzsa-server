package com.nefeon.akzsa.files

import android.content.Context

/** Called on app start: downloads the model automatically and begins indexing only after OS access was authorized. */
object AkzsaFileAiStartup {
    fun start(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
        // Consent first: nothing is downloaded or indexed for people who never turned File AI on.
        // (The 107 MB photo model used to be queued on EVERY launch, before onboarding.)
        if (!prefs.getBoolean("index_enabled", false)) return
        ClipModelManager.ensureDownload(app)      // light photo model (about 107 MB, Wi-Fi only)
        if (prefs.getBoolean("index_work_running", false)) return
        if (prefs.getBoolean("index_completed", false) && prefs.getBoolean("index_enabled", false)) {
            // Model may have just finished downloading after the basic index completed.
            ClipIndexScheduler.enqueue(app)
            return
        }
        val treeUri = prefs.getString("selected_tree_uri", null)
        when {
            LocalFileIndexer.hasFullSharedStorageAccess(app) -> FileIndexWorkScheduler.enqueueInitial(app)
            !treeUri.isNullOrBlank() -> FileIndexWorkScheduler.enqueueInitial(app, treeUri)
            else -> Unit // Android requires the user to grant file access; never bypass OS permission UI.
        }
    }
}
