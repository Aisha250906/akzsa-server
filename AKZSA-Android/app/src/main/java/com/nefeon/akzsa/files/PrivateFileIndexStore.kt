package com.nefeon.akzsa.files

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * App-private SQLite store for file metadata and extracted text. It is not shared with the backend
 * or other apps. Only AKZSA's own UID can read this database under Android's app sandbox.
 */
class PrivateFileIndexStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE file_index (
                file_id TEXT PRIMARY KEY NOT NULL,
                filename TEXT NOT NULL,
                uri TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                last_modified_ms INTEGER,
                extracted_text TEXT NOT NULL,
                extraction_kind TEXT NOT NULL,
                image_labels TEXT NOT NULL DEFAULT '',
                extraction_attempt_count INTEGER NOT NULL DEFAULT 0,
                gstins TEXT NOT NULL DEFAULT '',
                invoice_numbers TEXT NOT NULL DEFAULT '',
                amounts TEXT NOT NULL DEFAULT '',
                updated_at_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_file_index_name ON file_index(filename COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_file_index_path ON file_index(relative_path COLLATE NOCASE)")
        createSearchIndex(db, backfillComplete = true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migrations preserve the existing private file index across app upgrades.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE file_index ADD COLUMN image_labels TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) db.execSQL("DROP TABLE IF EXISTS semantic_index_status")
        if (oldVersion < 5) createSearchIndex(db, backfillComplete = false)
        else createSearchIndex(db, backfillComplete = true) // earlier v5 already populated FTS in its upgrade transaction
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE file_index ADD COLUMN extraction_attempt_count INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE file_index ADD COLUMN gstins TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE file_index ADD COLUMN invoice_numbers TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE file_index ADD COLUMN amounts TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun createSearchIndex(db: SQLiteDatabase, backfillComplete: Boolean) {
        // FTS4 unicode61 with remove_diacritics=0 preserves Indic combining marks. During
        // an upgrade we create the empty virtual table quickly and backfill in WorkManager batches,
        // avoiding a large text-index rebuild inside SQLiteOpenHelper's open/main-thread path.
        db.execSQL("""CREATE VIRTUAL TABLE IF NOT EXISTS file_index_fts USING fts4(
            file_id, filename, relative_path, extracted_text, image_labels,
            tokenize=unicode61 "remove_diacritics=0")""")
        db.execSQL("CREATE TABLE IF NOT EXISTS akzsa_store_meta (meta_key TEXT PRIMARY KEY NOT NULL, meta_value TEXT NOT NULL)")
        val value = if (backfillComplete) "1" else "0"
        db.execSQL("INSERT OR IGNORE INTO akzsa_store_meta(meta_key,meta_value) VALUES('fts_backfill_complete','$value')")
        if (backfillComplete) db.execSQL("UPDATE akzsa_store_meta SET meta_value='1' WHERE meta_key='fts_backfill_complete'")
    }

    init {
        migrateLegacyJsonOnce()
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM file_index", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Fetch only a bounded set of FTS candidates. The keyboard should not materialize every
     * document/OCR body merely to answer one query. Returned text is still processed locally.
     */
    fun searchCandidates(query: String, limit: Int = 300): List<IndexedPhoneFile> {
        val aliases = MultilingualQueryNormalizer.toEnglishConceptQuery(query)
        val raw = "$query $aliases"
        val tokens = Regex("[\\p{L}\\p{M}\\p{N}]+")
            .findAll(raw.lowercase(java.util.Locale.ROOT))
            .map { it.value }
            .filterNot { it in FTS_NOISE_WORDS }
            .distinct()
            .take(20)
            .toList()
        if (tokens.isEmpty()) return emptyList()
        val matchQuery = tokens.joinToString(" OR ") { token -> "\"${token.replace("\"", "\"\"")}\"" }
        val safeLimit = limit.coerceIn(1, 1000)
        val db = readableDatabase
        val indexed = db.rawQuery(
            "SELECT fi.file_id,fi.filename,fi.uri,fi.relative_path,fi.mime_type,fi.size_bytes,fi.last_modified_ms," +
                "snippet(file_index_fts,'','',' … ',3,28) AS extracted_text,fi.extraction_kind,fi.image_labels," +
                "fi.extraction_attempt_count,fi.gstins,fi.invoice_numbers,fi.amounts " +
                "FROM file_index AS fi JOIN file_index_fts ON file_index_fts.file_id=fi.file_id " +
                "WHERE file_index_fts MATCH ? ORDER BY COALESCE(fi.last_modified_ms,0) DESC LIMIT ?",
            arrayOf(matchQuery, safeLimit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }
        if (!searchBackfillPending()) return indexed

        // Migration fallback: until WorkManager fills the FTS table, run a bounded SQL LIKE pass
        // so users don't temporarily lose search over pre-upgrade records.
        val fallbackTokens = tokens.take(4)
        val fallbackWhere = fallbackTokens.joinToString(" OR ") {
            "filename LIKE ? OR relative_path LIKE ? OR extracted_text LIKE ? OR image_labels LIKE ?"
        }
        val fallbackArgs = fallbackTokens.flatMap { listOf("%$it%", "%$it%", "%$it%", "%$it%") }.toMutableList()
        fallbackArgs += safeLimit.toString()
        val fallback = db.rawQuery(
            "SELECT * FROM file_index WHERE $fallbackWhere ORDER BY COALESCE(last_modified_ms,0) DESC LIMIT ?",
            fallbackArgs.toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }
        return (indexed + fallback).distinctBy { it.fileId }.take(safeLimit)
    }

    fun searchBackfillPending(): Boolean = readableDatabase.rawQuery(
        "SELECT meta_value FROM akzsa_store_meta WHERE meta_key='fts_backfill_complete'", null,
    ).use { cursor -> !cursor.moveToFirst() || cursor.getString(0) != "1" }

    /** Adds a bounded batch of legacy rows to FTS. Returns true while further work remains. */
    fun backfillSearchIndexBatch(batchSize: Int = 100): Boolean {
        val safeBatch = batchSize.coerceIn(1, 500)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val rows = db.rawQuery(
                "SELECT fi.file_id,fi.filename,fi.relative_path,fi.extracted_text,fi.image_labels " +
                    "FROM file_index AS fi LEFT JOIN file_index_fts AS ft ON ft.file_id=fi.file_id " +
                    "WHERE ft.file_id IS NULL LIMIT ?", arrayOf(safeBatch.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(arrayOf(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    ))
                }
            }
            for (row in rows) {
                val values = ContentValues().apply {
                    put("file_id", row[0]); put("filename", row[1]); put("relative_path", row[2])
                    put("extracted_text", row[3]); put("image_labels", row[4])
                }
                db.insert("file_index_fts", null, values)
            }
            if (rows.isEmpty()) db.execSQL("UPDATE akzsa_store_meta SET meta_value='1' WHERE meta_key='fts_backfill_complete'")
            db.setTransactionSuccessful()
            return rows.isNotEmpty()
        } finally { db.endTransaction() }
    }

    /** Retrieve metadata for only the result IDs currently visible in the UI. */
    fun findByIds(ids: Collection<String>): List<IndexedPhoneFile> {
        if (ids.isEmpty()) return emptyList()
        val distinct = ids.distinct().take(100)
        val placeholders = distinct.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT file_id,filename,uri,relative_path,mime_type,size_bytes,last_modified_ms, " +
                "substr(extracted_text,1,4000) AS extracted_text,extraction_kind,image_labels,extraction_attempt_count,gstins,invoice_numbers,amounts " +
                "FROM file_index WHERE file_id IN ($placeholders)", distinct.toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }
    }

    /** Count photo candidates without loading OCR bodies or materializing the whole index. */
    fun imageCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM file_index WHERE $IMAGE_PREDICATE", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Metadata-only IDs are cheap enough for pruning orphaned vectors without loading document text. */
    fun imageFileIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT file_id FROM file_index WHERE $IMAGE_PREDICATE", null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** Newest-first keyset pagination for photo indexing; extracted text is deliberately not selected. */
    fun imagePage(afterModifiedMs: Long?, afterFileId: String?, limit: Int = 100): List<IndexedPhoneFile> {
        val safeLimit = limit.coerceIn(1, 250)
        val hasCursor = afterModifiedMs != null && afterFileId != null
        val where = buildString {
            append(IMAGE_PREDICATE)
            if (hasCursor) append(" AND (COALESCE(last_modified_ms,0) < ? OR (COALESCE(last_modified_ms,0) = ? AND file_id > ?))")
        }
        val args = if (hasCursor) arrayOf(afterModifiedMs.toString(), afterModifiedMs.toString(), requireNotNull(afterFileId), safeLimit.toString())
            else arrayOf(safeLimit.toString())
        val sql = "SELECT file_id,filename,uri,relative_path,mime_type,size_bytes,last_modified_ms, '' AS extracted_text, extraction_kind,image_labels, extraction_attempt_count, gstins, invoice_numbers, amounts " +
            "FROM file_index WHERE $where ORDER BY COALESCE(last_modified_ms,0) DESC, file_id ASC LIMIT ?"
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) }
        }
    }

    /** Prunes inaccessible/deleted file:// entries with a streaming cursor rather than loading OCR for every row. */
    fun removeStaleFileUris(): Int {
        val stale = ArrayList<String>(128)
        var deleted = 0
        readableDatabase.rawQuery("SELECT file_id, uri FROM file_index WHERE uri LIKE 'file:%'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val rawUri = cursor.getString(1)
                val staleEntry = try {
                    val uri = Uri.parse(rawUri)
                    uri.scheme == "file" && (uri.path.isNullOrBlank() || !File(uri.path!!).exists())
                } catch (_: Exception) { true }
                if (staleEntry) stale += cursor.getString(0)
            }
        }
        if (stale.isNotEmpty()) {
            stale.chunked(400).forEach { chunk -> deleteIds(chunk); deleted += chunk.size }
        }
        return deleted
    }

    fun find(fileId: String): IndexedPhoneFile? {
        readableDatabase.query(
            "file_index", null, "file_id=?", arrayOf(fileId), null, null, null, "1"
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toIndexedFile() else null }
    }

    /** Reuse OCR only when file ID, size and a known modification time all match. */
    fun findUnchanged(fileId: String, sizeBytes: Long, lastModifiedMs: Long?): IndexedPhoneFile? {
        if (lastModifiedMs == null) return null
        val old = find(fileId) ?: return null
        if (old.sizeBytes != sizeBytes || old.lastModifiedMs != lastModifiedMs) return null

        // Reprocess records when the image pipeline version changes or a prior extraction failed.
        // This deliberately does not treat a failed/blank attempt as a durable successful result.
        val extension = old.filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val isImage = extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "gif", "avif")
            || old.mimeType.startsWith("image/", true)
        val withinContentLimit = sizeBytes <= 20L * 1024L * 1024L
        val priorFailure = old.extractionKind.contains("failed", ignoreCase = true) ||
            old.extractionKind.contains("decode-failed", ignoreCase = true)
        val retryableFailure = priorFailure && old.extractionAttemptCount < 3
        val retryableEmptyImage = isImage && old.extractionKind.contains("no-text-or-labels", ignoreCase = true) && old.extractionAttemptCount < 2
        val needsImageUpgrade = isImage && sizeBytes <= 80L * 1024L * 1024L &&
            (!old.extractionKind.contains("image-analysis-v4") || retryableFailure || retryableEmptyImage)
        val pdfNeverProcessed = !old.extractionKind.contains("pdf-text-layer-or-ocr") &&
            !old.extractionKind.contains("metadata-only-extraction-failed")
        val needsPdfUpgrade = extension == "pdf" && withinContentLimit &&
            old.extractionKind != "metadata-only-file-too-large" && (pdfNeverProcessed || retryableFailure)
        val extractionPreviouslyFailed = (old.extractionKind.contains("extraction-failed", ignoreCase = true) && old.extractionAttemptCount < 3) ||
            (old.extractionKind == "processing-failed" && old.extractionAttemptCount < 3)
        return old.takeUnless { needsImageUpgrade || needsPdfUpgrade || extractionPreviouslyFailed }
    }

    /** Batch writes keep disk transactions short while allowing progress to survive process death. */
    fun upsertBatch(files: List<IndexedPhoneFile>) {
        if (files.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (file in files) {
                val values = ContentValues().apply {
                    put("file_id", file.fileId)
                    put("filename", file.filename)
                    put("uri", file.uri)
                    put("relative_path", file.relativePath)
                    put("mime_type", file.mimeType)
                    put("size_bytes", file.sizeBytes)
                    if (file.lastModifiedMs == null) putNull("last_modified_ms") else put("last_modified_ms", file.lastModifiedMs)
                    put("extracted_text", file.extractedText)
                    put("extraction_kind", file.extractionKind)
                    put("image_labels", file.imageLabels)
                    val oldAttempts = db.rawQuery("SELECT extraction_attempt_count FROM file_index WHERE file_id=?", arrayOf(file.fileId)).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    val failureOrEmpty = file.extractionKind.contains("failed", ignoreCase = true) ||
                        file.extractionKind.contains("decode-failed", ignoreCase = true) ||
                        file.extractionKind.contains("no-text-or-labels", ignoreCase = true)
                    val retryLimit = if (file.extractionKind.contains("no-text-or-labels", ignoreCase = true)) 2 else 3
                    val attempts = if (failureOrEmpty) (oldAttempts + 1).coerceAtMost(retryLimit) else 0
                    put("extraction_attempt_count", attempts)
                    val signals = LocalFileSearch.extractStructuredSignals(file.extractedText)
                    put("gstins", if (file.gstins.isNotEmpty()) file.gstins.distinct().take(10).joinToString("\n") else signals.gstins.joinToString("\n"))
                    put("invoice_numbers", if (file.invoiceNumbers.isNotEmpty()) file.invoiceNumbers.distinct().take(10).joinToString("\n") else signals.invoiceNumbers.joinToString("\n"))
                    put("amounts", if (file.amounts.isNotEmpty()) file.amounts.distinct().take(20).joinToString("\n") else signals.amounts.joinToString("\n"))
                    put("updated_at_ms", now)
                }
                db.insertWithOnConflict("file_index", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("file_index_fts", "file_id=?", arrayOf(file.fileId))
                val fts = ContentValues().apply {
                    put("file_id", file.fileId)
                    put("filename", file.filename)
                    put("relative_path", file.relativePath)
                    put("extracted_text", file.extractedText)
                    put("image_labels", file.imageLabels)
                }
                db.insert("file_index_fts", null, fts)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("file_index_fts", null, null)
            writableDatabase.delete("file_index", null, null)
            writableDatabase.execSQL("UPDATE akzsa_store_meta SET meta_value='1' WHERE meta_key='fts_backfill_complete'")
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun deleteIds(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.distinct().chunked(400).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.toTypedArray()
                db.delete("file_index_fts", "file_id IN ($placeholders)", args)
                db.delete("file_index", "file_id IN ($placeholders)", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.Cursor.toIndexedFile(): IndexedPhoneFile = IndexedPhoneFile(
        fileId = getString(getColumnIndexOrThrow("file_id")),
        filename = getString(getColumnIndexOrThrow("filename")),
        uri = getString(getColumnIndexOrThrow("uri")),
        relativePath = getString(getColumnIndexOrThrow("relative_path")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        lastModifiedMs = getColumnIndexOrThrow("last_modified_ms").let { idx -> if (isNull(idx)) null else getLong(idx) },
        extractedText = getString(getColumnIndexOrThrow("extracted_text")),
        extractionKind = getString(getColumnIndexOrThrow("extraction_kind")),
        imageLabels = getColumnIndex("image_labels").takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }.orEmpty(),
        extractionAttemptCount = getColumnIndex("extraction_attempt_count").takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: 0,
        gstins = getColumnIndex("gstins").takeIf { it >= 0 && !isNull(it) }?.let { getString(it).split('\n').filter(String::isNotBlank) }.orEmpty(),
        invoiceNumbers = getColumnIndex("invoice_numbers").takeIf { it >= 0 && !isNull(it) }?.let { getString(it).split('\n').filter(String::isNotBlank) }.orEmpty(),
        amounts = getColumnIndex("amounts").takeIf { it >= 0 && !isNull(it) }?.let { getString(it).split('\n').filter(String::isNotBlank) }.orEmpty(),
    )

    private fun migrateLegacyJsonOnce() {
        val prefs = appContext.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
        if (prefs.getBoolean("private_index_migrated_v1", false)) return
        // Mark first so malformed legacy data never causes repeated startup parsing. Current DB writes are independent.
        prefs.edit().putBoolean("private_index_migrated_v1", true).commit()
        if (count() > 0) return
        val legacy = File(appContext.filesDir, "akzsa-file-index.json")
        if (!legacy.isFile || legacy.length() > 64L * 1024L * 1024L) return
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val parsed = json.decodeFromString(ListSerializer(IndexedPhoneFile.serializer()), legacy.readText())
            parsed.take(LocalFileIndexer.MAX_INDEXED_FILES).chunked(50).forEach(::upsertBatch)
            // Once migrated successfully, remove the legacy duplicate from AKZSA's private storage.
            legacy.delete()
        } catch (_: Exception) {
            // Ignore a malformed/partial legacy JSON index; the worker can rebuild it safely.
        }
    }

    companion object {
        private const val DATABASE_NAME = "akzsa_private_file_index.db"
        private const val DATABASE_VERSION = 7
        private const val IMAGE_PREDICATE = "(mime_type LIKE 'image/%' OR lower(filename) GLOB '*.jpg' OR lower(filename) GLOB '*.jpeg' OR lower(filename) GLOB '*.png' OR lower(filename) GLOB '*.webp' OR lower(filename) GLOB '*.bmp' OR lower(filename) GLOB '*.tif' OR lower(filename) GLOB '*.tiff' OR lower(filename) GLOB '*.heic' OR lower(filename) GLOB '*.gif' OR lower(filename) GLOB '*.avif')"
        private val FTS_NOISE_WORDS = setOf(
            "find", "search", "show", "open", "give", "get", "please", "pls", "me", "my", "the", "a", "an",
            "of", "for", "to", "in", "on", "and", "with", "where", "is", "are", "photo", "photos", "image", "images",
            "picture", "pictures", "pic", "pics", "file", "files", "document", "documents", "mujhe", "mera", "meri",
            "mere", "dikhao", "dhundo", "dhoondo", "khojo", "गाड़ी", "कार", "ಫೋಟೋ", "ಚಿತ್ರ", "ಹುಡುಕು", "ಹುಡುಕಿ",
            "दिखाओ", "ढूंढो", "खोजो", "ನನ್ನ", "ನನಗೆ", "காட்டு", "தேடு", "చూపించు", "చిత్రం", "ছবি", "دکھاؤ"
        )
    }
}
