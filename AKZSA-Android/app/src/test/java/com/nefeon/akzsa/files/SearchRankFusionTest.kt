package com.nefeon.akzsa.files

import com.nefeon.akzsa.network.FileSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankFusionTest {
    private fun hit(id: String, reason: String, modified: Long = 100L) = FileSearchHit(
        file_id = id,
        filename = "$id.jpg",
        last_modified_ms = modified,
        score = 99, // Deliberately extreme: RRF must ignore raw score scales.
        reason = reason,
    )

    @Test fun overlapAcrossSourcesOutranksSingleSourceAndMergesReasons() {
        val first = listOf(hit("shared", "Filename match"), hit("lexical", "OCR match"))
        val second = listOf(hit("shared", "Photo match"), hit("semantic", "Other source match"))
        val fused = FileSearch.fuseRankedSources(listOf(first, second), 10)

        assertEquals("shared", fused.first().file_id)
        assertTrue(fused.first().reason.contains("Filename match"))
        assertTrue(fused.first().reason.contains("Photo match"))
        assertEquals(setOf("shared", "lexical", "semantic"), fused.map { it.file_id }.toSet())
    }

    @Test fun tieBreakingIsStableAndDoesNotUseUncalibratedScore() {
        val source = listOf(hit("z-file", "Lexical", 100L), hit("a-file", "Semantic", 100L))
        val first = FileSearch.fuseRankedSources(listOf(source), 10).map { it.file_id }
        val second = FileSearch.fuseRankedSources(listOf(source), 10).map { it.file_id }
        assertEquals(first, second)
        assertEquals(listOf("a-file", "z-file"), first)
    }
}
