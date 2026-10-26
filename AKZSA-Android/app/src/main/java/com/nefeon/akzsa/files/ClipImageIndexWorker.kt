package com.nefeon.akzsa.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ClipIndexScheduler {
    const val UNIQUE_WORK = "akzsa-clip-photo-index"

    /** Queues photo indexing (only if the photo model is on the phone). Safe to call many times. */
    fun enqueue(context: Context) {
        val app = context.applicationContext
        if (!ClipModelManager.isReady(app)) return
        val request = OneTimeWorkRequestBuilder<ClipImageIndexWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag("akzsa-clip-index")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }
}

/** Keeps heavy work from heating the phone: pause when the battery or the system says it is warm. */
object ThermalGuard {
    private const val PAUSE_AT_C = 40.0f
    private const val RESUME_BELOW_C = 38.0f

    fun batteryTempC(context: Context): Float? {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (raw == Int.MIN_VALUE) null else raw / 10f
    }

    /** [alreadyPaused] adds a small gap so work does not flap on and off around the limit. */
    fun shouldPause(context: Context, alreadyPaused: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return true
        }
        val temp = batteryTempC(context) ?: return false
        return if (alreadyPaused) temp >= RESUME_BELOW_C else temp >= PAUSE_AT_C
    }
}

/**
 * Indexes photos with MobileCLIP2-S0. Gentle by design: 2-4 CPU threads, a short rest after every photo,
 * automatic pause when the phone is warm, and only new or changed photos are processed.
 */
class ClipImageIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private class TooHotException : RuntimeException("Phone stayed warm; will continue later")

    override suspend fun doWork(): Result = processMutex.withLock { runIndex() }

    private suspend fun runIndex(): Result {
        if (!ClipModelManager.isReady(applicationContext)) return Result.retry()
        // Photo AI starts right away (newest photos first). While the OCR scan is still running it paces itself with
        // a longer rest between photos, and ThermalGuard pauses everything if the phone gets warm.
        var textScanRunning = isTextScanRunning()
        val files = PrivateFileIndexStore(applicationContext)
        val vectors = ClipVectorStore(applicationContext)
        return try {
            ensureChannel()
            val total = files.imageCount()
            val validImageIds = files.imageFileIds()
            vectors.deleteIds(vectors.allIds().filterNot { it in validImageIds })
            updateForeground(0, total, "Preparing photo AI")
            var processed = 0
            var indexed = 0
            var failures = 0
            var consecutiveFailures = 0
            var firstError: Throwable? = null
            var afterModifiedMs: Long? = null
            var afterFileId: String? = null
            while (true) {
                // The store pages metadata only; OCR bodies for thousands of unrelated documents
                // never enter the photo worker's heap.
                val page = files.imagePage(afterModifiedMs, afterFileId, PAGE_SIZE)
                if (page.isEmpty()) break
                for (file in page) {
                    if (isStopped) throw CancellationException("Photo indexing was stopped")
                    afterModifiedMs = file.lastModifiedMs ?: 0L
                    afterFileId = file.fileId
                    val fingerprint = "${file.sizeBytes}:${file.lastModifiedMs ?: 0L}:multicrop-v3"
                    if (vectors.fingerprint(file.fileId) == fingerprint) {
                        processed++
                        continue
                    }
                    // Hidden / thumbnail / trash images are never worth embedding.
                    if (IndexPolicy.isJunkPath(file.relativePath) || IndexPolicy.shouldSkipFile(file.filename)) { processed++; continue }
                    coolDownIfNeeded(processed, total, indexed, failures)
                    try {
                        val cropVectors = ArrayList<FloatArray>(5)
                        val decoded = ClipPreprocess.forEachTensor(applicationContext, file.uri) { tensor ->
                            cropVectors += ClipEngine.embedImage(applicationContext, tensor)
                        }
                        if (!decoded || cropVectors.size != 5) {
                            failures++
                        } else {
                            vectors.putCrops(file.fileId, fingerprint, cropVectors)
                            indexed++
                            consecutiveFailures = 0
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (memoryError: OutOfMemoryError) {
                        failures++
                        consecutiveFailures++
                        ClipEngine.release(textToo = false)
                        if (firstError == null) firstError = memoryError
                        ClipEngine.recordError(applicationContext, IllegalStateException("Photo index skipped one image because memory was exhausted."))
                        System.gc()
                        if (consecutiveFailures >= 5 && indexed == 0) throw (firstError ?: memoryError)
                    } catch (error: Exception) {
                        failures++
                        consecutiveFailures++
                        if (firstError == null) firstError = error
                        ClipEngine.recordError(applicationContext, error)
                        if (consecutiveFailures >= 5 && indexed == 0) throw (firstError ?: error)
                    }
                    processed++
                    if (indexed > 0 && indexed % 25 == 0) ClipVectorStore.bumpVersion(applicationContext)
                    if (processed % 5 == 0 || processed == total) report(processed, total, file.filename, indexed, failures, "")
                    if (processed % SCAN_RECHECK_EVERY == 0) textScanRunning = isTextScanRunning()
                    delay(if (textScanRunning) REST_WHILE_SCANNING_MS else REST_BETWEEN_PHOTOS_MS) // short rest = lower heat
                }
            }
            ClipVectorStore.bumpVersion(applicationContext)
            applicationContext.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE).edit()
                .putLong("clip_index_last_success_ms", System.currentTimeMillis())
                .putInt("clip_index_last_count", vectors.count())
                .apply()
            updateForeground(total, total, "Photo AI ready · ${vectors.count()} photos")
            Result.success(workDataOf(KEY_PROCESSED to total, KEY_TOTAL to total, KEY_INDEXED to indexed, KEY_FAILURES to failures, KEY_VECTORS to vectors.count()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TooHotException) {
            Result.retry()
        } catch (error: Exception) {
            ClipEngine.recordError(applicationContext, error)
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to "${error.javaClass.simpleName}: ${error.message ?: "Photo indexing failed"}".take(300)))
        } finally {
            vectors.close()
            files.close()
            ClipEngine.release(textToo = false)
        }
    }

    /** True while the OCR / text / label scan (first stage) is running. Uses WorkManager's real state. */
    private suspend fun isTextScanRunning(): Boolean = withContext(Dispatchers.IO) {
        try {
            val manager = WorkManager.getInstance(applicationContext)
            listOf(FileIndexWorkScheduler.UNIQUE_SCAN, FileIndexWorkScheduler.UNIQUE_CHANGE, FileIndexWorkScheduler.UNIQUE_PERIODIC).any { name ->
                manager.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Waits (up to 10 minutes) while the phone is warm, then gives up and lets WorkManager retry later. */
    private suspend fun coolDownIfNeeded(processed: Int, total: Int, indexed: Int, failures: Int) {
        var paused = false
        var waitedMs = 0L
        while (ThermalGuard.shouldPause(applicationContext, paused)) {
            paused = true
            report(processed, total, "", indexed, failures, "Paused: phone is warm, cooling down…")
            delay(COOL_CHECK_MS)
            waitedMs += COOL_CHECK_MS
            if (isStopped) throw CancellationException("Photo indexing was stopped")
            if (waitedMs >= MAX_COOL_WAIT_MS) throw TooHotException()
        }
    }

    private suspend fun report(processed: Int, total: Int, current: String, indexed: Int, failures: Int, status: String) {
        setProgress(
            workDataOf(
                KEY_PROCESSED to processed, KEY_TOTAL to total, KEY_CURRENT_FILE to current.take(100),
                KEY_INDEXED to indexed, KEY_FAILURES to failures, KEY_STATUS to status,
            )
        )
        updateForeground(processed, total, if (status.isNotBlank()) status else "Photo AI · $processed/$total")
    }

    /** Foreground notification is a bonus: Android 12 can refuse it from the background, so never fail because of it. */
    private suspend fun updateForeground(processed: Int, total: Int, message: String) {
        try {
            setForeground(foregroundInfo(processed, total, message))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            // Android may refuse foreground promotion for background-start-restricted work.
        } catch (_: SecurityException) {
            // A denied notification/foreground permission must not abort ordinary deferrable work.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AKZSA photo AI indexing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while photos are indexed on this phone."
                }
            )
        }
    }

    private fun foregroundInfo(processed: Int, total: Int, message: String): ForegroundInfo {
        val determinate = total > 0
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AKZSA · photo AI")
            .setContentText(message.take(120))
            .setOnlyAlertOnce(true)
            .setOngoing(processed < total)
            .setProgress(if (determinate) total else 0, if (determinate) processed.coerceIn(0, total) else 0, !determinate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_INDEXED = "indexed"
        const val KEY_FAILURES = "failures"
        const val KEY_STATUS = "status"
        const val KEY_VECTORS = "vectors"
        const val KEY_ERROR = "error"
        private const val REST_BETWEEN_PHOTOS_MS = 30L      // ThermalGuard, not a long sleep, protects against heat
        private const val REST_WHILE_SCANNING_MS = 120L     // gentler while the OCR scan runs at the same time
        private const val SCAN_RECHECK_EVERY = 20           // WorkManager state is queried once per 20 photos
        private const val COOL_CHECK_MS = 20_000L
        private const val MAX_COOL_WAIT_MS = 10L * 60_000L
        private const val CHANNEL_ID = "akzsa_clip_index"
        private const val NOTIFICATION_ID = 17044
        private val processMutex = Mutex()
    }
}
