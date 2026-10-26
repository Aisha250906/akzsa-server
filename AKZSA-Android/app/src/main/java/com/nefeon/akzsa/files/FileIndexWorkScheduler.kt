package com.nefeon.akzsa.files

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules private on-device indexing. Observer events trigger debounced scans; periodic reconciliation is a fallback. No network is required. */
object FileIndexWorkScheduler {
    const val UNIQUE_SCAN = "akzsa-private-file-index-scan"
    const val UNIQUE_PERIODIC = "akzsa-private-file-index-periodic"
    const val UNIQUE_CHANGE = "akzsa-private-file-index-change"
    const val KEY_TREE_URI = "tree_uri"
    const val KEY_FORCE_TREE = "force_tree"
    const val KEY_CHANGED_URIS = "changed_uris"
    private const val MAX_CHANGED_URIS = 30

    fun enqueueInitial(context: Context, treeUri: String? = null) {
        val app = context.applicationContext
        app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE).edit()
            .putBoolean("index_enabled", true)
            .apply()
        val data = if (treeUri.isNullOrBlank()) workDataOf() else workDataOf(KEY_TREE_URI to treeUri, KEY_FORCE_TREE to true)
        val request = OneTimeWorkRequestBuilder<FileIndexWorker>()
            .setInputData(data)
            .addTag("akzsa-file-index")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_SCAN, ExistingWorkPolicy.KEEP, request)
        ensurePeriodic(app)
    }

    fun ensurePeriodic(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("index_enabled", false)) return
        val data = workDataOf()
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<FileIndexWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(data)
            .addTag("akzsa-file-index-periodic")
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueChangedUris(context: Context, uris: List<String>) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("index_enabled", false)) return
        if (uris.isEmpty() || uris.size > MAX_CHANGED_URIS) {
            enqueueChangeScan(app)
            return
        }
        val data = workDataOf(KEY_CHANGED_URIS to uris.toTypedArray())
        val request = OneTimeWorkRequestBuilder<FileIndexWorker>()
            .setInputData(data)
            .addTag("akzsa-file-index-changed-items")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_SCAN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueChangeScan(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("index_enabled", false)) return
        val data = workDataOf()
        val request = OneTimeWorkRequestBuilder<FileIndexWorker>()
            .setInputData(data)
            .addTag("akzsa-file-index-change")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_SCAN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
