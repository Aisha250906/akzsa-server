package com.nefeon.akzsa.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks for photo-AI query cleaning and scoring (verified with kotlinc during development). */
class ClipRankingTest {
    @Test fun stripsCommandWordsButKeepsMeaning() {
        assertEquals("dog", ClipRanking.cleanQuery("find my dog photos"))
        assertEquals("dog on beach", ClipRanking.cleanQuery("show me a dog on the beach"))
        assertNull(ClipRanking.cleanQuery("find my photos"))
        assertNull(ClipRanking.cleanQuery("   "))
    }

    @Test fun supportedHindiHinglishAndKannadaTermsNormalizeForPhotoSearch() {
        assertEquals("car", ClipRanking.cleanQuery("Mujhe car ki photo dikhao"))
        assertEquals("car", ClipRanking.cleanQuery("गाड़ी की फोटो दिखाओ"))
        assertEquals("dog", ClipRanking.cleanQuery("कुत्ता फोटो दिखाओ"))
        assertEquals("car", ClipRanking.cleanQuery("ನನ್ನ ಕಾರು ಚಿತ್ರ"))
        assertEquals("cat", ClipRanking.cleanQuery("ಬೆಕ್ಕು ಫೋಟೋ"))
        assertNull(ClipRanking.cleanQuery("ನನ್ನ ಫೋಟೋ"))
        assertEquals("dog", ClipRanking.cleanQuery("कुत्ते की फोटो"))
    }

    @Test fun keepsOnlyCloseHighSimilarityMatches() {
        val dim = ClipRanking.DIM
        val matrix = FloatArray(3 * dim)
        matrix[0] = 0.30f; matrix[dim] = 0.28f; matrix[2 * dim] = 0.10f
        val query = FloatArray(dim).also { it[0] = 1f }
        assertEquals(listOf(0, 1), ClipRanking.topMatches(3, matrix, query).map { it.first })
        val low = FloatArray(dim).also { it[0] = 0.15f }
        assertTrue(ClipRanking.topMatches(1, low, query).isEmpty())
    }

    @Test fun multicropRankingReturnsEachPhotoOnceUsingItsBestCrop() {
        val ids = arrayOf("photo-a", "photo-b", "photo-a", "photo-c")
        val dim = ClipRanking.DIM
        val matrix = FloatArray(ids.size * dim)
        matrix[0] = 0.31f
        matrix[dim] = 0.29f
        matrix[2 * dim] = 0.34f // best tile for photo-a
        matrix[3 * dim] = 0.08f
        val query = FloatArray(dim).also { it[0] = 1f }
        val result = ClipRanking.topMatchesByPhoto(ids, ids.size, matrix, query)
        assertEquals(listOf("photo-a", "photo-b"), result.map { it.first })
        assertEquals(0.34f, result.first().second, 0.0001f)
    }

    @Test fun promptEnsembleProducesUnitVectorAndRejectsBadDimensions() {
        val a = FloatArray(ClipRanking.DIM).also { it[0] = 1f }
        val b = FloatArray(ClipRanking.DIM).also { it[1] = 1f }
        val average = ClipRanking.meanUnit(listOf(a, b))!!
        var norm = 0.0
        average.forEach { norm += it.toDouble() * it.toDouble() }
        assertEquals(1.0, kotlin.math.sqrt(norm), 0.0001)
        assertNull(ClipRanking.meanUnit(listOf(floatArrayOf(1f))))
    }
}
