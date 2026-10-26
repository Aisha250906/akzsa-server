package com.nefeon.akzsa.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistent background indexer. Its database and extracted text stay in AKZSA app-private storage. */
class FileIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = processMutex.withLock { doWorkExclusive() }

    /** Serializes initial, event-triggered, and periodic scans so simultaneous jobs do not OCR a file twice. */
    private suspend fun doWorkExclusive(): Result {
        val prefs = applicationContext.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
        val explicitTreeUri = inputData.getString(FileIndexWorkScheduler.KEY_TREE_URI)
        val forceTree = inputData.getBoolean(FileIndexWorkScheduler.KEY_FORCE_TREE, false)
        val savedTreeUri = prefs.getString("selected_tree_uri", null)
        val hasBroadAccess = LocalFileIndexer.hasFullSharedStorageAccess(applicationContext)
        val treeUri = explicitTreeUri ?: savedTreeUri
        val useSharedStorage = hasBroadAccess && !forceTree
        if (!useSharedStorage && treeUri.isNullOrBlank()) {
            return Result.failure(workDataOf(KEY_ERROR to "Grant AKZSA shared-storage access, or authorize a folder in Android's picker."))
        }

        val store = PrivateFileIndexStore(applicationContext)
        val runTag = id.toString()
        val pending = ArrayList<IndexedPhoneFile>(BATCH_SIZE)
        var processed = 0
        var currentName = "Preparing to scan files"
        suspend fun flush() {
            if (pending.isNotEmpty()) {
                store.upsertBatch(pending.toList())
                pending.clear()
            }
        }
        suspend fun announce(force: Boolean = false) {
            if (force || processed == 1 || processed % PROGRESS_EVERY == 0) {
                flush()
                setProgress(workDataOf(KEY_PROCESSED to processed, KEY_CURRENT_FILE to currentName, KEY_TOTAL to store.count()))
                updateForeground(createForegroundInfo(processed, currentName))
            }
        }

        return try {
            ensureNotificationChannel()
            updateForeground(createForegroundInfo(0, "Starting local file indexing"))
            val existingLookup: (String, Long, Long?) -> IndexedPhoneFile? = { fileId, size, modified ->
                store.findUnchanged(fileId, size, modified)
            }
            val onIndexed: suspend (IndexedPhoneFile) -> Unit = { file ->
                pending.add(file)
                if (pending.size >= BATCH_SIZE) flush()
            }
            val onProgress: suspend (Int, String) -> Unit = { count, name ->
                processed = count
                currentName = name.take(100)
                announce()
            }

            prefs.edit().putBoolean("index_work_running", true).apply()
            val changedUris = inputData.getStringArray(FileIndexWorkScheduler.KEY_CHANGED_URIS)?.toList().orEmpty()
            if (changedUris.isNotEmpty() && useSharedStorage) {
                var allResolved = true
                var changedCount = 0
                for (changedUri in changedUris) {
                    val ok = LocalFileIndexer.indexChangedMediaUri(
                        context = applicationContext,
                        rawUri = changedUri,
                        existingLookup = existingLookup,
                        onFileIndexed = { changed -> store.upsertBatch(listOf(changed)) },
                    )
                    if (!ok) { allResolved = false; break }
                    changedCount++
                    setProgress(workDataOf(KEY_PROCESSED to changedCount, KEY_CURRENT_FILE to "Updated file $changedCount", KEY_TOTAL to store.count()))
                    updateForeground(createForegroundInfo(changedCount, "Updating changed files in the private index"))
                }
                if (allResolved) {
                    store.removeStaleFileUris()
                    maintainSearchIndexBackfill(store)
                    prefs.edit()
                        .putBoolean("index_work_running", false)
                        .putBoolean("index_completed", true)
                        .putLong("index_last_success_ms", System.currentTimeMillis())
                        .putString("index_last_run_id", runTag)
                        .apply()
                    ClipIndexScheduler.enqueue(applicationContext)
                    return Result.success(workDataOf(KEY_TOTAL to store.count(), KEY_COMPLETE to true))
                }
                // Unknown/deleted/collection URIs need a full walk to clean stale rows and catch all changes.
            }
            val files = if (useSharedStorage) {
                LocalFileIndexer.indexSharedStorage(
                    context = applicationContext,
                    existingLookup = existingLookup,
                    onFileIndexed = onIndexed,
                    onProgress = onProgress,
                )
            } else {
                LocalFileIndexer.indexTree(
                    context = applicationContext,
                    treeUri = Uri.parse(treeUri!!),
                    existingLookup = existingLookup,
                    onFileIndexed = onIndexed,
                    onProgress = onProgress,
                )
            }
            flush()
            store.removeStaleFileUris() // also removes records for deleted file:// entries.
            maintainSearchIndexBackfill(store)
            prefs.edit()
                .putBoolean("index_enabled", true)
                .putBoolean("index_work_running", false)
                .putBoolean("index_completed", true)
                .putLong("index_last_success_ms", System.currentTimeMillis())
                .putString("index_last_run_id", runTag)
                .apply()
            setProgress(workDataOf(KEY_PROCESSED to processed, KEY_CURRENT_FILE to "Finished", KEY_TOTAL to files, KEY_COMPLETE to true))
            updateForeground(createForegroundInfo(processed, "Index ready · ${files} files searchable"))
            ClipIndexScheduler.enqueue(applicationContext)
            Result.success(workDataOf(KEY_TOTAL to files, KEY_COMPLETE to true))
        } catch (cancelled: CancellationException) {
            // Leave already committed batches intact. WorkManager will resume with cached OCR.
            prefs.edit().putBoolean("index_work_running", false).apply()
            throw cancelled
        } catch (error: SecurityException) {
            flush()
            prefs.edit().putBoolean("index_work_running", false).apply()
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Storage access was not granted."), KEY_PROCESSED to processed))
        } catch (error: Exception) {
            flush()
            if (runAttemptCount < 2) Result.retry()
            else {
                prefs.edit().putBoolean("index_work_running", false).apply()
                Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Indexing failed."), KEY_PROCESSED to processed))
            }
        } finally {
            store.close()
        }
    }

    /** Keep schema migration off the UI thread and make progress in small, resumable FTS batches. */
    private fun maintainSearchIndexBackfill(store: PrivateFileIndexStore) {
        repeat(MAX_FTS_BACKFILL_BATCHES_PER_RUN) {
            if (!store.searchBackfillPending()) return
            if (!store.backfillSearchIndexBatch(FTS_BACKFILL_BATCH_SIZE)) return
        }
    }

    private suspend fun updateForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
            // Android can refuse foreground promotion for background-start-restricted work.
            // Continue as ordinary WorkManager work; do not fail indexing solely due to the notice.
        } catch (_: SecurityException) {
            // Notification/foreground permission is not available on this OS/user profile.
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AKZSA file indexing", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows progress while AKZSA indexes files privately on this phone."
                    }
                )
            }
        }
    }

    private fun createForegroundInfo(processed: Int, name: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AKZSA · private file indexing")
            .setContentText(if (processed > 0) "$processed files checked · ${name.take(60)}" else name.take(90))
            .setOnlyAlertOnce(true)
            .setOngoing(processed > 0 && name != "Finished")
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private val processMutex = Mutex()
        private const val FTS_BACKFILL_BATCH_SIZE = 100
        private const val MAX_FTS_BACKFILL_BATCHES_PER_RUN = 5
        const val KEY_PROCESSED = "processed"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_TOTAL = "total"
        const val KEY_COMPLETE = "complete"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "akzsa_file_indexing"
        const val NOTIFICATION_ID = 17041
        private const val BATCH_SIZE = 20
        private const val PROGRESS_EVERY = 10
    }
}
