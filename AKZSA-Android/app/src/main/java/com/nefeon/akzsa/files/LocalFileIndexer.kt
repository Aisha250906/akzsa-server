package com.nefeon.akzsa.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.text.Html
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import android.Manifest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Indexes the user's authorized Android shared storage (broad access on Android 11+ when the
 * user grants MANAGE_EXTERNAL_STORAGE), with an optional Storage Access Framework folder mode.
 * OCR, extracted text, metadata indexing, and search ranking all stay on-device. The Render
 * backend is not sent the phone's file index or document excerpts, keeping the free instance light.
 */
@Serializable
data class IndexedPhoneFile(
    val fileId: String,
    val filename: String,
    val uri: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
    val extractedText: String,
    val extractionKind: String,
    val imageLabels: String = "",
    val extractionAttemptCount: Int = 0,
    val gstins: List<String> = emptyList(),
    val invoiceNumbers: List<String> = emptyList(),
    val amounts: List<String> = emptyList(),
)

private data class PdfTextLayer(val metadata: String, val content: String)
private data class ImageContentResult(
    val extractedText: String,
    val labels: String,
    val status: String = "success",
)

object LocalFileIndexer {
    // ML Kit's bundled general image-labeling model is initialized on-device. Reuse one client
    // across a serialized index run instead of paying setup overhead for every photo.
    private val sharedImageLabeler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.10f).build())
    }
    @Volatile private var pdfBoxReady = false
    private val pdfBoxInitLock = Any()

    // The index is private on the phone. Search remains local; the app shares an actual document
    // only when the user explicitly invokes the Open/Share/Attach action.
    const val MAX_INDEXED_FILES = IndexPolicy.MAX_FILES
    private const val MAX_VISITED_NODES = IndexPolicy.MAX_VISITED
    private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    // Large JPEGs can still be analyzed via a sampled bitmap instead of loading the full image.
    private const val MAX_IMAGE_ANALYSIS_BYTES = 80L * 1024L * 1024L
    // Recall-oriented default-label threshold: re-index marker v3 makes this change apply to existing rows.
    private const val MIN_IMAGE_LABEL_CONFIDENCE = 0.15f
    private const val MAX_IMAGE_LABELS = 12
    private const val MAX_TEXT_FILE_BYTES = 120 * 1024
    // Text-layer PDFs are cheap to search through more pages; OCR fallback is deliberately capped
    // to keep battery, memory and background-worker duration reasonable on mid-range phones.
    private const val MAX_PDF_TEXT_PAGES = 40
    private const val MAX_PDF_OCR_PAGES = 30
    private const val PDF_TEXT_MIN_CHARS = 80
    private const val MAX_EXTRACTED_CHARS = 200000
    private const val OCR_BITMAP_MAX_WIDTH = 3000
    private const val LATIN_ONLY_MIN_CHARS = 250
    private const val LATIN_ONLY_MIN_LETTERS = 180
    private const val ZIP_ENTRY_LIMIT = 1_000_000

    /** True when Android has granted broad shared-storage access. */
    fun hasFullSharedStorageAccess(context: Context): Boolean = try {
        when {
            !com.nefeon.akzsa.BuildConfig.ALL_FILES_ACCESS -> false   // Play build: user-picked folders only
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> false // Target-35 scoped storage: use SAF on Android 10.
            else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Exception) { false }

    /**
     * Indexes accessible shared storage in one pass after the user grants Android's file-manager
     * permission. Android still protects other apps' private Android/data and Android/obb trees.
     * OCR/text extraction and every search stay on-device. The private index is never uploaded to
     * the backend; the Render service is not involved in local file matching.
     */
    suspend fun indexSharedStorage(
        context: Context,
        existingLookup: (fileId: String, sizeBytes: Long, lastModifiedMs: Long?) -> IndexedPhoneFile? = { _, _, _ -> null },
        onFileIndexed: suspend (IndexedPhoneFile) -> Unit = {},
        onProgress: suspend (indexed: Int, fileName: String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw SecurityException("Enable 'Allow access to manage all files' for AKZSA, then tap Index again.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw SecurityException("Android 10 does not support this full-storage scan for the current target SDK. Use the optional Android folder picker.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasFullSharedStorageAccess(context)) {
            throw SecurityException("Android storage permission is not granted. Use the Android folder picker on this device.")
        }

        val roots = linkedMapOf<String, File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists() && primary.isDirectory) roots[primary.canonicalPath] = primary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                manager.storageVolumes.forEach { volume ->
                    if (volume.state == Environment.MEDIA_MOUNTED) {
                        val directory = try { volume.directory } catch (_: Exception) { null }
                        if (directory != null && directory.exists() && directory.isDirectory) {
                            try { roots[directory.canonicalPath] = directory } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { /* Primary shared storage remains supported. */ }
        }
        if (roots.isEmpty()) throw IllegalStateException("No accessible shared-storage root was found on this device.")

        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        var indexedCount = 0
        var visited = 0
        val visitedCanonicalDirectories = HashSet<String>()
        val blockedRelativePrefixes = listOf("android/data", "android/obb")
        try {
            suspend fun walk(file: File, root: File, depth: Int) {
                if (depth > 24 || indexedCount >= MAX_INDEXED_FILES || visited >= MAX_VISITED_NODES) return
                if (file.isDirectory) {
                    val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
                    if (!visitedCanonicalDirectories.add(canonical)) return
                    val relative = try { file.relativeTo(root).path.replace('\\', '/').lowercase(Locale.ROOT) } catch (_: Exception) { "" }
                    if (blockedRelativePrefixes.any { relative == it || relative.startsWith("$it/") }) return
                    val children = try { file.listFiles()?.toList().orEmpty() } catch (_: SecurityException) { emptyList() } catch (_: Exception) { emptyList() }
                    val sorted = children
                        .filterNot { if (it.isDirectory) IndexPolicy.shouldSkipDirectory(it.name) else IndexPolicy.shouldSkipFile(it.name) }
                        .sortedWith(
                            compareBy<File> { child -> IndexPolicy.childRank(child.name, child.isDirectory, child.isFile && supportedDocumentForText(child.name)) }
                                .thenBy { it.name.lowercase(Locale.ROOT) }
                        )
                    for (child in sorted) {
                        if (indexedCount >= MAX_INDEXED_FILES || visited >= MAX_VISITED_NODES) break
                        visited++
                        try { walk(child, root, depth + 1) } catch (_: SecurityException) { /* Restricted OS folder. */ }
                    }
                } else if (file.isFile) {
                    val name = file.name.take(240).ifBlank { "Unnamed file" }
                    val path = try { file.relativeTo(root).path.replace('\\', '/') } catch (_: Exception) { name }
                    val uri = Uri.fromFile(file)
                    val mime = mimeFromName(name)
                    val size = try { file.length().coerceAtLeast(0L) } catch (_: Exception) { 0L }
                    val modified = file.lastModified().takeIf { it > 0L }
                    val fileId = stableId(file.absolutePath)
                    val cached = existingLookup(fileId, size, modified)
                    val indexed = if (cached != null) {
                        cached.copy(
                            filename = name, uri = uri.toString(), relativePath = path.take(800),
                            mimeType = mime, sizeBytes = size, lastModifiedMs = modified
                        )
                    } else {
                        var extracted = ""
                        var imageLabels = ""
                        var how = "metadata-only"
                        if (size <= MAX_FILE_BYTES || ((mime.startsWith("image/", ignoreCase = true) || isImage(name)) && size <= MAX_IMAGE_ANALYSIS_BYTES)) {
                            try {
                                when {
                                    isPdf(name, mime) -> {
                                        extracted = extractPdf(context, uri, latin, devanagari)
                                        how = if (extracted.isBlank()) "on-device-pdf-text-layer-or-ocr-no-text" else "on-device-pdf-text-layer-or-ocr"
                                    }
                                    mime.startsWith("image/", ignoreCase = true) || isImage(name) -> {
                                        val imageResult = try { analyzeImage(context, uri, latin, devanagari, forceDownsample = size > MAX_FILE_BYTES) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { ImageContentResult("", "", "processing-failed") }
                                        extracted = imageResult.extractedText
                                        imageLabels = imageResult.labels
                                        how = imageExtractionKind(imageResult)
                                    }
                                    isText(name, mime) -> {
                                        extracted = extractPlainText(context, uri).take(MAX_EXTRACTED_CHARS)
                                        how = if (extracted.isBlank()) "text-empty" else "text-extraction"
                                    }
                                    isOfficeZip(name) -> {
                                        extracted = extractOfficeText(context, uri, name).take(MAX_EXTRACTED_CHARS)
                                        how = if (extracted.isBlank()) "office-metadata-only" else "office-xml-text"
                                    }
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                extracted = ""
                                if (imageLabels.isBlank()) how = "metadata-only-extraction-failed"
                            }
                        } else {
                            how = "metadata-only-file-too-large"
                        }
                        IndexedPhoneFile(
                            fileId = fileId, filename = name, uri = uri.toString(),
                            relativePath = path.take(800), mimeType = mime, sizeBytes = size,
                            lastModifiedMs = modified, extractedText = extracted.take(MAX_EXTRACTED_CHARS),
                            extractionKind = how, imageLabels = imageLabels.take(600),
                        )
                    }
                    indexedCount++
                    onFileIndexed(indexed)
                    onProgress(indexedCount, name)
                }
            }
            for (root in roots.values) {
                walk(root, root, 0)
                if (indexedCount >= MAX_INDEXED_FILES || visited >= MAX_VISITED_NODES) break
            }
            indexedCount
        } finally {
            latin.close()
            devanagari.close()
        }
    }

    /**
     * Fast path for one MediaStore item-change event. Returns false for collection URIs, deletions,
     * provider limitations or unsupported paths; callers should then schedule a full reconciliation.
     * The file's stable ID matches the path-based ID used by the initial full scan.
     */
    suspend fun indexChangedMediaUri(
        context: Context,
        rawUri: String,
        existingLookup: (fileId: String, sizeBytes: Long, lastModifiedMs: Long?) -> IndexedPhoneFile? = { _, _, _ -> null },
        onFileIndexed: suspend (IndexedPhoneFile) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasFullSharedStorageAccess(context)) return@withContext false
        val uri = try { Uri.parse(rawUri) } catch (_: Exception) { return@withContext false }
        // Do not query a collection URL as though it represented one file.
        if (uri.lastPathSegment?.toLongOrNull() == null) return@withContext false

        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val row = try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                    fun long(column: String): Long? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                    arrayOf(
                        string(MediaStore.MediaColumns.DATA).orEmpty(),
                        string(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(),
                        (long(MediaStore.MediaColumns.SIZE) ?: 0L).toString(),
                        (long(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L).toString(),
                        string(MediaStore.MediaColumns.MIME_TYPE).orEmpty(),
                        string(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty(),
                    )
                }
            }
        } catch (_: Exception) { null } ?: return@withContext false

        val rawPath = row[0].takeIf { it.isNotBlank() } ?: return@withContext false
        val file = try { File(rawPath).canonicalFile } catch (_: Exception) { return@withContext false }
        if (!file.isFile) return@withContext false
        val roots = mutableListOf<File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists()) roots += try { primary.canonicalFile } catch (_: Exception) { primary }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                manager.storageVolumes.forEach { volume ->
                    if (volume.state == Environment.MEDIA_MOUNTED) {
                        val directory = try { volume.directory } catch (_: Exception) { null }
                        if (directory != null && directory.exists()) {
                            try { roots += directory.canonicalFile } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        val root = roots.firstOrNull { candidate ->
            val base = candidate.path.trimEnd(File.separatorChar) + File.separator
            file.path.startsWith(base, ignoreCase = true)
        } ?: return@withContext false
        val relative = try { file.relativeTo(root).path.replace('\\', '/') } catch (_: Exception) { file.name }
        val lowerRelative = relative.lowercase(Locale.ROOT)
        if (lowerRelative == "android/data" || lowerRelative.startsWith("android/data/") ||
            lowerRelative == "android/obb" || lowerRelative.startsWith("android/obb/")) return@withContext false

        // Hidden/trash/thumbnail files are never indexed (same rule as the full scan).
        if (IndexPolicy.isJunkPath(relative)) return@withContext true
        val name = file.name.take(240).ifBlank { row[1].take(240).ifBlank { "Unnamed file" } }
        val mime = MimeResolver.resolve(name, row[4])
        val size = file.length().coerceAtLeast(0L)
        val modified = file.lastModified().takeIf { it > 0L }
        val fileId = stableId(file.absolutePath)
        val cached = existingLookup(fileId, size, modified)
        val indexed: IndexedPhoneFile
        if (cached != null) {
            indexed = cached.copy(
                filename = name,
                uri = Uri.fromFile(file).toString(),
                relativePath = relative.take(800),
                mimeType = mime,
                sizeBytes = size,
                lastModifiedMs = modified,
            )
        } else {
            val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            try {
                var extracted = ""
                var imageLabels = ""
                var how = "metadata-only"
                if (size <= MAX_FILE_BYTES || ((mime.startsWith("image/", ignoreCase = true) || isImage(name)) && size <= MAX_IMAGE_ANALYSIS_BYTES)) {
                    try {
                        when {
                            isPdf(name, mime) -> {
                                extracted = extractPdf(context, Uri.fromFile(file), latin, devanagari)
                                how = if (extracted.isBlank()) "on-device-pdf-text-layer-or-ocr-no-text" else "on-device-pdf-text-layer-or-ocr"
                            }
                            mime.startsWith("image/", ignoreCase = true) || isImage(name) -> {
                                val imageResult = try { analyzeImage(context, Uri.fromFile(file), latin, devanagari, forceDownsample = size > MAX_FILE_BYTES) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { ImageContentResult("", "", "processing-failed") }
                                extracted = imageResult.extractedText
                                imageLabels = imageResult.labels
                                how = imageExtractionKind(imageResult)
                            }
                            isText(name, mime) -> {
                                extracted = extractPlainText(context, Uri.fromFile(file)).take(MAX_EXTRACTED_CHARS)
                                how = if (extracted.isBlank()) "text-empty" else "text-extraction"
                            }
                            isOfficeZip(name) -> {
                                extracted = extractOfficeText(context, Uri.fromFile(file), name).take(MAX_EXTRACTED_CHARS)
                                how = if (extracted.isBlank()) "office-metadata-only" else "office-xml-text"
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        extracted = ""
                        if (imageLabels.isBlank()) how = "metadata-only-extraction-failed"
                    }
                } else {
                    how = "metadata-only-file-too-large"
                }
                indexed = IndexedPhoneFile(
                    fileId = fileId,
                    filename = name,
                    uri = Uri.fromFile(file).toString(),
                    relativePath = relative.take(800),
                    mimeType = mime.ifBlank { "application/octet-stream" },
                    sizeBytes = size,
                    lastModifiedMs = modified,
                    extractedText = extracted.take(MAX_EXTRACTED_CHARS),
                    extractionKind = how,
                    imageLabels = imageLabels.take(600),
                )
            } finally {
                latin.close()
                devanagari.close()
            }
        }
        onFileIndexed(indexed)
        true
    }

    /** Converts indexed local file URIs to short-lived shareable content URIs. SAF content URIs
     * are returned unchanged because Android already granted AKZSA access to those documents. */
    fun shareableUri(context: Context, rawUri: String): Uri {
        val uri = Uri.parse(rawUri)
        if (uri.scheme != "file") return uri
        val path = uri.path ?: throw IllegalArgumentException("The indexed file has no readable path.")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    }

    private fun supportedDocumentForText(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("pdf", "jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "gif", "avif", "txt", "csv", "tsv", "md", "log", "json", "xml", "html", "htm", "yaml", "yml", "docx", "xlsx", "pptx", "odt")
    }

    suspend fun indexTree(
        context: Context,
        treeUri: Uri,
        existingLookup: (fileId: String, sizeBytes: Long, lastModifiedMs: Long?) -> IndexedPhoneFile? = { _, _, _ -> null },
        onFileIndexed: suspend (IndexedPhoneFile) -> Unit = {},
        onProgress: suspend (indexed: Int, fileName: String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Android could not open the selected folder. Choose the folder again.")
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        var indexedCount = 0
        var visited = 0
        try {
            suspend fun walk(directory: DocumentFile, depth: Int, relativeDirectory: String) {
                if (depth > 16 || indexedCount >= MAX_INDEXED_FILES || visited >= MAX_VISITED_NODES) return
                val children = try {
                    directory.listFiles()
                        .filterNot { if (it.isDirectory) IndexPolicy.shouldSkipDirectory(it.name) else IndexPolicy.shouldSkipFile(it.name) }
                        .sortedWith(
                            compareBy<DocumentFile> { child -> IndexPolicy.childRank(child.name, child.isDirectory, child.isFile && supportedDocumentForText(child.name.orEmpty())) }
                                .thenBy { it.name?.lowercase(Locale.ROOT).orEmpty() }
                        )
                } catch (_: Exception) { emptyList() }
                for (child in children) {
                    if (indexedCount >= MAX_INDEXED_FILES || visited >= MAX_VISITED_NODES) break
                    visited++
                    val childNameForPath = child.name?.take(240)?.ifBlank { "Unnamed file" } ?: "Unnamed file"
                    val childRelativePath = if (relativeDirectory.isBlank()) childNameForPath else "$relativeDirectory/$childNameForPath"
                    if (child.isDirectory) {
                        walk(child, depth + 1, childRelativePath)
                        continue
                    }
                    if (!child.isFile) continue
                    val name = child.name?.take(240)?.ifBlank { "Unnamed file" } ?: "Unnamed file"
                    val uriText = child.uri.toString()
                    val mime = MimeResolver.resolve(name, child.type)
                    val size = child.length().coerceAtLeast(0L)
                    val modified = child.lastModified().takeIf { it > 0L }
                    val fileId = stableId(uriText)
                    val cached = existingLookup(fileId, size, modified)
                    val indexed = if (cached != null) {
                        cached.copy(
                            filename = name, uri = uriText, relativePath = childRelativePath.take(800),
                            mimeType = mime.ifBlank { "application/octet-stream" },
                            sizeBytes = size, lastModifiedMs = modified
                        )
                    } else {
                        var extracted = ""
                        var imageLabels = ""
                        var how = "metadata-only"
                        if (size <= MAX_FILE_BYTES || ((mime.startsWith("image/", ignoreCase = true) || isImage(name)) && size <= MAX_IMAGE_ANALYSIS_BYTES)) {
                            try {
                                when {
                                    isPdf(name, mime) -> {
                                        extracted = extractPdf(context, child.uri, latin, devanagari)
                                        how = if (extracted.isBlank()) "on-device-pdf-text-layer-or-ocr-no-text" else "on-device-pdf-text-layer-or-ocr"
                                    }
                                    mime.startsWith("image/", ignoreCase = true) || isImage(name) -> {
                                        val imageResult = try { analyzeImage(context, child.uri, latin, devanagari, forceDownsample = size > MAX_FILE_BYTES) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { ImageContentResult("", "", "processing-failed") }
                                        extracted = imageResult.extractedText
                                        imageLabels = imageResult.labels
                                        how = imageExtractionKind(imageResult)
                                    }
                                    isText(name, mime) -> {
                                        extracted = extractPlainText(context, child.uri).take(MAX_EXTRACTED_CHARS)
                                        how = if (extracted.isBlank()) "text-empty" else "text-extraction"
                                    }
                                    isOfficeZip(name) -> {
                                        extracted = extractOfficeText(context, child.uri, name).take(MAX_EXTRACTED_CHARS)
                                        how = if (extracted.isBlank()) "office-metadata-only" else "office-xml-text"
                                    }
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                extracted = ""
                                if (imageLabels.isBlank()) how = "metadata-only-extraction-failed"
                            }
                        } else if (size > MAX_FILE_BYTES) {
                            how = "metadata-only-file-too-large"
                        }
                        IndexedPhoneFile(
                            fileId = fileId, filename = name, uri = uriText,
                            relativePath = childRelativePath.take(800),
                            mimeType = mime.ifBlank { "application/octet-stream" },
                            sizeBytes = size, lastModifiedMs = modified,
                            extractedText = extracted.take(MAX_EXTRACTED_CHARS), extractionKind = how,
                            imageLabels = imageLabels.take(600),
                        )
                    }
                    indexedCount++
                    onFileIndexed(indexed)
                    onProgress(indexedCount, name)
                }
            }
            walk(root, 0, "")
            indexedCount
        } finally {
            latin.close()
            devanagari.close()
        }
    }

    /** OCR + general semantic labels from one decoded image, all executed on-device.
     * InputImage.fromFilePath is preferred. Some providers and file:// URIs fail there, so a
     * bounded bitmap fallback is used rather than silently storing an empty analysis result.
     */
    private suspend fun analyzeImage(
        context: Context,
        uri: Uri,
        latin: TextRecognizer,
        devanagari: TextRecognizer,
        forceDownsample: Boolean = false,
    ): ImageContentResult {
        var fallbackBitmap: Bitmap? = null
        val image = if (forceDownsample) {
            fallbackBitmap = decodeFallbackBitmap(context, uri)
            val bitmap = fallbackBitmap ?: return ImageContentResult("", "", "decode-failed")
            InputImage.fromBitmap(bitmap, 0)
        } else try {
            InputImage.fromFilePath(context, uri)
        } catch (_: Exception) {
            fallbackBitmap = decodeFallbackBitmap(context, uri)
            val bitmap = fallbackBitmap ?: return ImageContentResult("", "", "decode-failed")
            InputImage.fromBitmap(bitmap, 0)
        }
        try {
            // OCR and object labeling are independent signals: run both even if one returns blank.
            val text = try { recognizeBoth(image, latin, devanagari).trim() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { "" }
            val labels = try {
                suspendCancellableCoroutine { continuation ->
                    sharedImageLabeler.process(image)
                        .addOnSuccessListener { results ->
                            if (continuation.isActive) {
                                continuation.resume(
                                    results.asSequence()
                                        .filter { it.confidence >= MIN_IMAGE_LABEL_CONFIDENCE }
                                        .sortedByDescending { it.confidence }
                                        .map { it.text.trim().lowercase(Locale.ROOT) }
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                        .take(MAX_IMAGE_LABELS)
                                        .joinToString(" "),
                                )
                            }
                        }
                        .addOnFailureListener { error ->
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { "" }
            val status = if (text.isNotBlank() || labels.isNotBlank()) "success" else "empty"
            return ImageContentResult(text, labels, status)
        } finally {
            // Keep the fallback bitmap alive until both asynchronous ML Kit tasks have completed.
            fallbackBitmap?.recycle()
        }
    }

    /** Decode file:// and content:// images at a bounded size if ML Kit cannot open the URI. */
    private fun decodeFallbackBitmap(context: Context, uri: Uri): Bitmap? {
        fun openStream(): InputStream? = try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let { path -> File(path).takeIf { it.isFile }?.inputStream() }
            } else context.contentResolver.openInputStream(uri)
        } catch (_: Exception) { null }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val maxDimension = OCR_BITMAP_MAX_WIDTH
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = openStream()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val orientation = try {
                openStream()?.use { stream -> ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            }
            if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { oriented ->
                if (oriented !== bitmap) bitmap.recycle()
            }
        } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }
    }

    private fun imageExtractionKind(result: ImageContentResult): String {
        val detail = when {
            result.extractedText.isNotBlank() && result.labels.isNotBlank() -> "labels-and-ocr"
            result.labels.isNotBlank() -> "labels-only"
            result.extractedText.isNotBlank() -> "ocr-only"
            result.status == "decode-failed" -> "decode-failed"
            result.status == "processing-failed" -> "processing-failed"
            else -> "no-text-or-labels"
        }
        // This version marker makes the next reconciliation reprocess image rows made by old APKs.
        return "image-analysis-v4-$detail"
    }

    private suspend fun extractPdf(
        context: Context,
        uri: Uri,
        latin: TextRecognizer,
        devanagari: TextRecognizer,
    ): String {
        // Prefer the real PDF text layer: it is much more accurate than OCR and lets searches
        // match invoice numbers, GSTINs, dates and amounts throughout ordinary multi-page PDFs.
        val layer = try { extractPdfTextLayer(context, uri) } catch (_: Exception) { null }
        val nativeText = listOf(layer?.metadata.orEmpty(), layer?.content.orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n")
        val usefulTextChars = layer?.content?.count { it.isLetterOrDigit() } ?: 0
        if (usefulTextChars >= PDF_TEXT_MIN_CHARS) return nativeText.take(MAX_EXTRACTED_CHARS)

        // Scanned/photo PDFs often have no text layer. Render a small bounded number of pages and
        // use bundled on-device OCR. This avoids sending documents to any cloud OCR service.
        val scanned = try { ocrPdfPages(context, uri, latin, devanagari) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { "" }
        return listOf(nativeText, scanned).filter { it.isNotBlank() }
            .distinct().joinToString("\n").take(MAX_EXTRACTED_CHARS)
    }

    private fun extractPdfTextLayer(context: Context, uri: Uri): PdfTextLayer? {
        if (!pdfBoxReady) synchronized(pdfBoxInitLock) {
            if (!pdfBoxReady) {
                PDFBoxResourceLoader.init(context.applicationContext)
                pdfBoxReady = true
            }
        }
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val info = document.documentInformation
                val metadata = listOfNotNull(info?.title, info?.subject, info?.keywords, info?.author)
                    .map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" ")
                if (document.isEncrypted || document.numberOfPages <= 0) {
                    PdfTextLayer(metadata = metadata, content = "")
                } else {
                    val stripper = PDFTextStripper().apply {
                        startPage = 1
                        endPage = minOf(document.numberOfPages, MAX_PDF_TEXT_PAGES)
                        sortByPosition = true
                    }
                    val content = stripper.getText(document).replace(Regex("""[\t\x0B\f\r ]+"""), " ")
                        .replace(Regex(""" *\n *"""), "\n").trim().take(MAX_EXTRACTED_CHARS)
                    PdfTextLayer(metadata = metadata, content = content)
                }
            }
        }
    }

    private suspend fun ocrPdfPages(
        context: Context,
        uri: Uri,
        latin: TextRecognizer,
        devanagari: TextRecognizer,
    ): String {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pieces = ArrayList<String>()
                val count = minOf(renderer.pageCount, MAX_PDF_OCR_PAGES)
                for (index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val scale = minOf(
                            200f / 72f,
                            OCR_BITMAP_MAX_WIDTH.toFloat() / page.width.toFloat(),
                            OCR_BITMAP_MAX_WIDTH.toFloat() / page.height.toFloat(),
                        )
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(
                                bitmap, null,
                                Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) },
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            val text = recognizeBoth(InputImage.fromBitmap(bitmap, 0), latin, devanagari)
                            if (text.isNotBlank()) pieces += "[PDF page ${index + 1}] $text"
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (pieces.sumOf { it.length } >= MAX_EXTRACTED_CHARS) break
                }
                pieces.joinToString("\n").take(MAX_EXTRACTED_CHARS)
            }
        }
    }

    private suspend fun recognizeBoth(image: InputImage, latin: TextRecognizer, devanagari: TextRecognizer): String {
        val latinText = try { recognize(latin, image) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { "" }
        // Speed: a long, clean Latin result is an English page; the Devanagari pass would only repeat the work.
        if (isCleanLatinPage(latinText)) return latinText.take(MAX_EXTRACTED_CHARS)
        val devaText = try { recognize(devanagari, image) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { "" }
        val devanagariChars = devaText.count { it in '\u0900'..'\u097F' }
        val devaShare = devanagariChars.toFloat() / devaText.count(Char::isLetter).coerceAtLeast(1)
        val latinLetters = latinText.count { it.isLetter() && it !in '\u0900'..'\u097F' }
        val latinShare = latinLetters.toFloat() / latinText.count(Char::isLetter).coerceAtLeast(1)
        return when {
            latinText.isBlank() -> devaText
            devaText.isBlank() || devaShare < 0.20f -> latinText
            latinShare < 0.20f -> devaText
            latinText.contains(devaText, ignoreCase = true) -> latinText
            devaText.contains(latinText, ignoreCase = true) -> devaText
            else -> "$latinText\n$devaText" // retain both when the page is genuinely mixed-script
        }.take(MAX_EXTRACTED_CHARS)
    }

    /**
     * True for a long Latin-script result with almost no stray symbols and no Indic characters. Such a page is English,
     * so the slower Devanagari recogniser is skipped. Short, noisy or mixed-script results still get both passes.
     * To always run both again, set LATIN_ONLY_MIN_CHARS to Int.MAX_VALUE.
     */
    private fun isCleanLatinPage(text: String): Boolean {
        if (text.length < LATIN_ONLY_MIN_CHARS) return false
        if (text.count { it.isLetter() } < LATIN_ONLY_MIN_LETTERS) return false
        if (text.any { it in '\u0900'..'\u097F' || it in '\u0C80'..'\u0CFF' }) return false
        val allowed = ".,:;-_/\\()[]{}@#%&*+=!?'\"<>|~\u2013\u2014\u20B9\u20AC\u00A3\$"
        val odd = text.count { !(it.isLetterOrDigit() || it.isWhitespace() || it in allowed) }
        return odd * 100 <= text.length * 3
    }

    private suspend fun recognize(recognizer: TextRecognizer, image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result.text) }
                .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        }

    private fun extractPlainText(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri) ?: return ""
        stream.use { input ->
            val bytes = readAtMost(input, MAX_TEXT_FILE_BYTES)
            val raw = bytes.toString(Charsets.UTF_8)
            // Don't render arbitrary HTML. Keep visible body text and normalize whitespace.
            val stripped = if (uri.lastPathSegment?.endsWith(".html", true) == true || uri.lastPathSegment?.endsWith(".xml", true) == true) {
                raw.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                    .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            } else raw
            return stripped.replace(Regex("\\s+"), " ").trim()
        }
    }

    private fun extractOfficeText(context: Context, uri: Uri, name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        return OfficeTextExtractor.extract(input, extension, MAX_EXTRACTED_CHARS)
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun unescapeXml(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }

    private fun isPdf(name: String, mime: String) = mime.equals("application/pdf", true) || name.endsWith(".pdf", true)
    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "gif", "avif")
    private fun isOfficeZip(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("docx", "xlsx", "pptx", "odt")
    private fun isText(name: String, mime: String) = mime.startsWith("text/", true) || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("txt", "csv", "tsv", "md", "log", "json", "xml", "html", "htm", "yaml", "yml")
    private fun mimeFromName(name: String): String = MimeResolver.fromName(name)
    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
}
