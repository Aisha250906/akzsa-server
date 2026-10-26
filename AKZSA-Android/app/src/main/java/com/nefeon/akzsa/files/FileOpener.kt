package com.nefeon.akzsa.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Opens an indexed file in the right app.
 *
 * Fixes "Open jumps to the Photos home screen": for images/videos Photos and Gallery cannot resolve an arbitrary
 * FileProvider URI and fall back to their home screen. Media files are therefore opened through their MediaStore
 * content URI (the same URI the gallery itself uses). Documents use the FileProvider URI. The type is never
 * `application/octet-stream` when the name tells us better, and a file that no longer exists is reported instead of
 * launching an app that cannot show anything.
 */
object FileOpener {
    sealed interface OpenResult {
        data object Opened : OpenResult
        data object Missing : OpenResult
        data object NoApp : OpenResult
    }

    /** URI to hand to a VIEWER app. */
    fun viewUri(context: Context, file: IndexedPhoneFile): Uri {
        val raw = Uri.parse(file.uri)
        if (raw.scheme != "file") return raw                                   // SAF documents: access already granted
        val path = raw.path ?: throw IllegalArgumentException("The indexed file has no readable path.")
        val mime = MimeResolver.resolve(file.filename, file.mimeType)
        mediaStoreUri(context, path, mime)?.let { return it }
        return LocalFileIndexer.shareableUri(context, file.uri)
    }

    private fun mediaStoreUri(context: Context, path: String, mime: String): Uri? {
        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        return try {
            @Suppress("DEPRECATION")
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null)?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }

    fun open(context: Context, file: IndexedPhoneFile, newTask: Boolean): OpenResult {
        val raw = Uri.parse(file.uri)
        if (raw.scheme == "file") {
            val path = raw.path
            if (path.isNullOrBlank() || !File(path).exists()) return OpenResult.Missing
        }
        val uri = try { viewUri(context, file) } catch (_: Exception) { return OpenResult.NoApp }
        val mime = MimeResolver.resolve(file.filename, file.mimeType)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (newTask) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        view.clipData = ClipData.newUri(context.contentResolver, file.filename, uri)
        return try {
            context.startActivity(view)
            OpenResult.Opened
        } catch (_: ActivityNotFoundException) {
            // No default app for this type: let the user choose one.
            try {
                val chooser = Intent.createChooser(view, "Open ${file.filename} with")
                if (newTask) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                OpenResult.Opened
            } catch (_: Exception) { OpenResult.NoApp }
        } catch (_: SecurityException) {
            OpenResult.NoApp
        }
    }
}
