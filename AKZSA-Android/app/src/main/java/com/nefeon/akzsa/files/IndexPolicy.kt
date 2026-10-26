package com.nefeon.akzsa.files

import java.util.Locale

/**
 * What the indexer visits, in what order, and what it ignores. Pure logic so it is unit-tested.
 *
 * Why this exists:
 *  - The old walk visited DCIM before Documents/Download and stopped at 5,000 files, so a large photo library used the
 *    whole budget and the PDFs/documents people actually search for were never indexed.
 *  - Hidden folders such as `.thumbnails`, `.Trash` and `.trashed-*` files were indexed and then showed up as
 *    "wrong files" in results.
 */
object IndexPolicy {
    const val MAX_FILES = 50_000
    const val MAX_VISITED = 200_000

    private val junkDirectories = setOf(
        "cache", "thumbnails", "thumbs", "trash", "lost.dir", "tmp", "temp", "node_modules", "gen", ".thumbnails",
    )
    private val junkExtensions = setOf("tmp", "temp", "crdownload", "part", "partial", "thumb", "thumbdata3", "bak~", "swp")

    private val documentDirectories = setOf("download", "downloads", "documents", "document", "books", "scans", "scanned", "docs", "pdf", "pdfs")
    private val middleDirectories = setOf("pictures", "screenshots", "screenshot", "whatsapp", "telegram", "media", "images", "photos")
    private val bulkMediaDirectories = setOf("dcim", "camera", "movies", "music", "recordings", "video", "videos", "audio", "ringtones", "alarms", "notifications", "podcasts")

    fun shouldSkipDirectory(name: String?): Boolean {
        val n = name?.trim().orEmpty().lowercase(Locale.ROOT)
        return n.isEmpty() || n.startsWith(".") || n in junkDirectories
    }

    fun shouldSkipFile(name: String?): Boolean {
        val n = name?.trim().orEmpty().lowercase(Locale.ROOT)
        if (n.isEmpty() || n.startsWith(".") || n.endsWith("~")) return true     // .nomedia, .trashed-123-IMG.jpg, editor backups
        return n.substringAfterLast('.', "") in junkExtensions
    }

    /** True if any folder on the path, or the file itself, is hidden/junk. Used at search time for rows indexed earlier. */
    fun isJunkPath(relativePath: String): Boolean {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.dropLast(1).any { shouldSkipDirectory(it) && it.isNotBlank() }) return true
        return shouldSkipFile(parts.last())
    }

    /** Lower = visited earlier. Documents and downloads first, bulk camera/video/music late, Android/ last. */
    fun childRank(name: String?, isDirectory: Boolean, isSupportedDocument: Boolean): Int {
        val n = name?.lowercase(Locale.ROOT).orEmpty()
        return if (isDirectory) when {
            n in documentDirectories -> 0
            n in middleDirectories -> 2
            n == "android" -> 5
            n in bulkMediaDirectories -> 4
            else -> 3
        } else if (isSupportedDocument) 1 else 6
    }
}
