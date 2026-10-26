package com.nefeon.akzsa.files

import android.content.Context
import com.nefeon.akzsa.network.FileSearchHit
import java.util.Locale

/** Photo search using MobileCLIP2-S0 vectors. Never throws; a failing optional model does not block text search. */
object ClipSearch {
    fun search(context: Context, query: String, files: List<IndexedPhoneFile>): List<FileSearchHit> {
        if (files.isEmpty()) return emptyList()
        return searchInternal(context, query) { ids -> files.filter { it.fileId in ids } }
    }

    /** Production overload: reads only the top matched metadata rows after streaming vector scores. */
    fun search(context: Context, query: String, store: PrivateFileIndexStore): List<FileSearchHit> =
        searchInternal(context, query) { ids -> store.findByIds(ids) }

    private fun searchInternal(
        context: Context,
        query: String,
        lookupFiles: (List<String>) -> List<IndexedPhoneFile>,
    ): List<FileSearchHit> {
        try {
            val app = context.applicationContext
            if (!ClipModelManager.isReady(app)) return emptyList()
            val phrase = ClipRanking.cleanQuery(query) ?: return emptyList()
            // Prompt ensemble reduces brittleness compared with one fixed template.
            val prompts = listOf("a photo of $phrase", "a close-up photo of $phrase", "a photo of a small $phrase far away")
            val queryVector = ClipRanking.meanUnit(prompts.map { ClipEngine.embedText(app, it) }) ?: return emptyList()
            val store = ClipVectorStore(app)
            val matches = try { store.searchByVector(queryVector) } finally { store.close() }
            if (matches.isEmpty()) return emptyList()
            val fileMap = lookupFiles(matches.map { it.fileId }).associateBy { it.fileId }
            return matches.mapNotNull { match ->
                val file = fileMap[match.fileId] ?: return@mapNotNull null
                if (IndexPolicy.isJunkPath(file.relativePath) || IndexPolicy.shouldSkipFile(file.filename)) return@mapNotNull null
                FileSearchHit(
                    file_id = file.fileId,
                    filename = file.filename,
                    mime_type = file.mimeType,
                    size_bytes = file.sizeBytes,
                    last_modified_ms = file.lastModifiedMs,
                    score = ClipRanking.toScore(match.similarity),
                    reason = "On-device photo similarity match",
                    text_preview = buildString {
                        if (file.imageLabels.isNotBlank()) append("Image labels: ").append(file.imageLabels.take(200)).append('\n')
                        append(file.extractedText.take(250))
                    }.trim().take(450),
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ClipEngine.recordError(context, error)
            return emptyList()
        }
    }
}
