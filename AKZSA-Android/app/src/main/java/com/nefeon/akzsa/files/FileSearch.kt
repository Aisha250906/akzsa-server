package com.nefeon.akzsa.files

import android.content.Context
import com.nefeon.akzsa.network.FileSearchHit
import java.util.Locale

/** One local search boundary. Lexical and photo sources are ranked separately, then fused by rank. */
object FileSearch {
    /** Compatibility/testing path when callers already have a bounded in-memory list. */
    fun rank(context: Context, query: String, files: List<IndexedPhoneFile>, limit: Int = 3): List<FileSearchHit> {
        if (query.isBlank() || files.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 50)
        val lexical = LocalFileSearch.rank(query, files, maxOf(safeLimit, 20))
        val photos = ClipSearch.search(context, query, files)
        return fuseRankedSources(listOf(lexical, photos), safeLimit)
    }

    /** Production search uses bounded FTS candidates plus a streaming photo-vector query. */
    fun search(context: Context, query: String, store: PrivateFileIndexStore, limit: Int = 3): List<FileSearchHit> {
        if (query.isBlank() || store.count() == 0) return emptyList()
        val safeLimit = limit.coerceIn(1, 50)
        val candidates = store.searchCandidates(query, maxOf(300, safeLimit * 30))
        val lexical = LocalFileSearch.rank(query, candidates, maxOf(20, safeLimit * 5))
        val photos = ClipSearch.search(context, query, store)
        return fuseRankedSources(listOf(lexical, photos), safeLimit)
    }

    /** Pure, deterministic Reciprocal Rank Fusion. The source score scale is deliberately ignored. */
    internal fun fuseRankedSources(sources: List<List<FileSearchHit>>, limit: Int, rrfK: Int = 60): List<FileSearchHit> {
        require(rrfK > 0) { "rrfK must be positive" }
        data class Accumulator(val first: FileSearchHit, var value: Double, val reasons: LinkedHashSet<String>)
        val byId = linkedMapOf<String, Accumulator>()
        for (source in sources) {
            val seenInSource = HashSet<String>()
            source.forEachIndexed { index, hit ->
                if (!seenInSource.add(hit.file_id)) return@forEachIndexed
                val contribution = 1.0 / (rrfK + index + 1).toDouble()
                val reasons = hit.reason.split(';').map(String::trim).filter(String::isNotBlank)
                val current = byId[hit.file_id]
                if (current == null) byId[hit.file_id] = Accumulator(hit, contribution, LinkedHashSet(reasons))
                else { current.value += contribution; current.reasons.addAll(reasons) }
            }
        }
        return byId.values
            .sortedWith(compareByDescending<Accumulator> { it.value }
                .thenByDescending { it.first.last_modified_ms ?: 0L }
                .thenBy { it.first.file_id.lowercase(Locale.ROOT) }
                .thenBy { it.first.file_id })
            .take(limit.coerceIn(1, 50))
            .map { it.first.copy(reason = it.reasons.take(5).joinToString("; ")) }
    }

    fun isImage(file: IndexedPhoneFile): Boolean = file.mimeType.startsWith("image/", true) ||
        file.filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "gif", "avif")
}
