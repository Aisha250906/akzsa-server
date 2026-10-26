package com.nefeon.akzsa.files

import java.util.Locale

/**
 * File type from the file name. Used when storage reports no type or a useless one, because opening a file with
 * `application/octet-stream` or `*&#47;*` makes Android pick an arbitrary app (often Photos/Gallery home).
 */
object MimeResolver {
    private val byExtension = mapOf(
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "bmp" to "image/bmp",
        "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "tif" to "image/tiff", "tiff" to "image/tiff",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "3gp" to "video/3gpp", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/x-wav", "ogg" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac", "amr" to "audio/amr",
        "txt" to "text/plain", "md" to "text/plain", "log" to "text/plain", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "xml" to "text/xml", "html" to "text/html", "htm" to "text/html",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "rtf" to "application/rtf",
        "zip" to "application/zip", "apk" to "application/vnd.android.package-archive", "vcf" to "text/x-vcard",
    )

    private val useless = setOf("", "*/*", "application/octet-stream", "binary/octet-stream")

    /** Type implied by the extension, or `application/octet-stream` when unknown. */
    fun fromName(name: String): String =
        byExtension[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

    /** Prefer a specific stored type; otherwise derive it from the name. */
    fun resolve(name: String, storedMime: String?): String {
        val stored = storedMime?.trim().orEmpty().lowercase(Locale.ROOT)
        if (stored !in useless) return stored
        return fromName(name)
    }

    fun isMedia(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
}
