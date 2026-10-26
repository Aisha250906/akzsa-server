package com.nefeon.akzsa.files

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.LinkedHashSet

/** Watches shared storage while the process lives, coalesces file events, and schedules private background updates. */
object FileIndexAutoMonitor {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var observer: ContentObserver? = null
    private var pendingRefresh: Runnable? = null
    private val pendingItemUris = LinkedHashSet<String>()
    private var requiresFullScan = false

    @Synchronized
    fun start(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val created = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = queueRefresh(app, null)
            override fun onChange(selfChange: Boolean, uri: Uri?) = queueRefresh(app, uri)
        }
        try {
            app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, created)
            observer = created
        } catch (_: Exception) {
            // Periodic WorkManager reconciliation still runs when enabled.
        }
    }

    @Synchronized
    private fun queueRefresh(context: Context, changedUri: Uri?) {
        val app = context.applicationContext
        if (!app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE).getBoolean("index_enabled", false)) return

        val last = changedUri?.lastPathSegment?.toLongOrNull()
        if (changedUri == null || last == null || pendingItemUris.size >= MAX_PENDING_URIS) {
            requiresFullScan = true
            pendingItemUris.clear()
        } else if (!requiresFullScan) {
            pendingItemUris.add(changedUri.toString())
        }

        pendingRefresh?.let { handler.removeCallbacks(it) }
        val refresh = Runnable {
            val items: List<String>
            val full: Boolean
            synchronized(this) {
                pendingRefresh = null
                items = pendingItemUris.toList()
                full = requiresFullScan || items.isEmpty()
                pendingItemUris.clear()
                requiresFullScan = false
            }
            val enabled = app.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE).getBoolean("index_enabled", false)
            if (!enabled) return@Runnable
            if (full) FileIndexWorkScheduler.enqueueChangeScan(app)
            else FileIndexWorkScheduler.enqueueChangedUris(app, items)
        }
        pendingRefresh = refresh
        handler.postDelayed(refresh, CHANGE_DEBOUNCE_MS)
    }

    @Synchronized
    fun stop(context: Context) {
        pendingRefresh?.let { handler.removeCallbacks(it) }
        pendingRefresh = null
        pendingItemUris.clear()
        requiresFullScan = false
        val current = observer ?: return
        try { context.applicationContext.contentResolver.unregisterContentObserver(current) } catch (_: Exception) { }
        observer = null
    }

    private const val CHANGE_DEBOUNCE_MS = 15_000L
    private const val MAX_PENDING_URIS = 30
}
