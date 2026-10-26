package com.nefeon.akzsa.files

import java.util.Locale
import kotlin.math.sqrt

/** Pure scoring helpers (no Android classes) so they can be tested on a plain JVM. */
object ClipRanking {
    const val DIM = 512
    /** Minimum cosine similarity to show a candidate; this is not a calibrated confidence threshold. */
    const val MIN_SIMILARITY = 0.20f
    /** Only keep results close to the best one, so unrelated photos do not fill the list. */
    const val BAND = 0.07f
    const val MAX_RESULTS = 12

    private val COMMAND_WORDS = setOf(
        "find", "search", "show", "give", "get", "open", "me", "my", "the", "a", "an", "please", "pls",
        "photo", "photos", "image", "images", "picture", "pictures", "pic", "pics", "jpg", "jpeg", "png",
        "file", "files", "of", "want", "need", "where", "is", "are", "i", "to", "for",
    )

    /** Returns a model-friendly query, translating known Hindi/Hinglish/Kannada concepts first. */
    fun cleanQuery(query: String): String? {
        val translated = MultilingualQueryNormalizer.toEnglishConceptQuery(query)
        val words = Regex("[\\p{L}\\p{N}']+").findAll(translated.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (words.isEmpty()) return null
        val kept = words.filterNot { it in COMMAND_WORDS || MultilingualQueryNormalizer.isCommandWord(it) }
        // CLIP text encoder was trained primarily for English. For unknown scripts, retain the
        // lexical fallback rather than pretending the model understands an unsupported language.
        val modelSafe = kept.filter { word -> word.all { it.code <= 0x24F || !it.isLetter() } }
        return modelSafe.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** Dot product of unit vectors = cosine similarity. Returns (index, similarity) best first. */
    fun topMatches(rowCount: Int, matrix: FloatArray, query: FloatArray, dim: Int = DIM): List<Pair<Int, Float>> {
        if (rowCount == 0 || query.size != dim || matrix.size < rowCount * dim) return emptyList()
        val scores = FloatArray(rowCount)
        for (row in 0 until rowCount) {
            var dot = 0f
            val base = row * dim
            for (d in 0 until dim) dot += matrix[base + d] * query[d]
            scores[row] = dot
        }
        val best = scores.maxOrNull() ?: return emptyList()
        val threshold = maxOf(MIN_SIMILARITY, best - BAND)
        return scores.indices
            .filter { scores[it] >= threshold }
            .sortedByDescending { scores[it] }
            .take(MAX_RESULTS)
            .map { it to scores[it] }
    }

    /** Maximum similarity per photo across its full-frame and tile embeddings. */
    fun topMatchesByPhoto(ids: Array<String>, rowCount: Int, matrix: FloatArray, query: FloatArray, dim: Int = DIM): List<Pair<String, Float>> {
        if (rowCount == 0 || query.size != dim || ids.size < rowCount || matrix.size < rowCount * dim) return emptyList()
        val bestByPhoto = linkedMapOf<String, Float>()
        for (row in 0 until rowCount) {
            var dot = 0f
            val base = row * dim
            for (d in 0 until dim) dot += matrix[base + d] * query[d]
            val photoId = ids[row]
            if (dot > (bestByPhoto[photoId] ?: Float.NEGATIVE_INFINITY)) bestByPhoto[photoId] = dot
        }
        val best = bestByPhoto.values.maxOrNull() ?: return emptyList()
        if (best < MIN_SIMILARITY) return emptyList()
        val threshold = maxOf(MIN_SIMILARITY, best - BAND)
        return bestByPhoto.entries.asSequence()
            .filter { it.value >= threshold }
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .take(MAX_RESULTS)
            .map { it.key to it.value }
            .toList()
    }

    /** Unit-normalizes an average of prompt vectors so the result remains a cosine query. */
    fun meanUnit(vectors: List<FloatArray>, dim: Int = DIM): FloatArray? {
        if (vectors.isEmpty() || vectors.any { it.size != dim }) return null
        val mean = FloatArray(dim)
        vectors.forEach { vector -> for (i in 0 until dim) mean[i] += vector[i] / vectors.size }
        var square = 0.0
        for (value in mean) square += value.toDouble() * value.toDouble()
        val norm = kotlin.math.sqrt(square).toFloat()
        if (norm <= 1e-12f) return null
        for (i in mean.indices) mean[i] /= norm
        return mean
    }

    /** This is a scaled raw cosine similarity, not a calibrated probability or confidence percentage. */
    fun toScore(similarity: Float): Int = (similarity.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
}

